#!/usr/bin/env bash

set -euo pipefail

PACKAGE_ROOT="$(cd "$(dirname "$0")" && pwd -P)"
INSTALL_ROOT="/opt/blockchain-manager-local"
RELEASE_ROOT="$INSTALL_ROOT/releases"
# 각 immutable release는 releases/ 아래에 설치하고 current symlink만 원자적으로 전환한다.
CURRENT_LINK="$INSTALL_ROOT/current"
PREVIOUS_LINK="$INSTALL_ROOT/previous"
CONFIG_ROOT="/etc/blockchain-manager-local"
STATE_ROOT="/var/lib/blockchain-manager-local"
SYSTEMD_ROOT="/etc/systemd/system"

fail() {
    echo "오류: $*" >&2
    exit 1
}

require_root() {
    [ "$(id -u)" -eq 0 ] || fail "설치와 롤백은 root 권한이 필요합니다."
}

verify_package() {
    [ "$(uname -s)" = "Linux" ] || fail "폐쇄망 배포물은 일반 Linux 서버 전용입니다."
    command -v sha256sum >/dev/null 2>&1 || fail "sha256sum을 찾을 수 없습니다."
    (cd "$PACKAGE_ROOT" && sha256sum -c SHA256SUMS)

    case "$(uname -m)" in
        x86_64) expected_suffix="linux-x86_64" ;;
        aarch64|arm64) expected_suffix="linux-aarch64" ;;
        *) fail "지원하지 않는 CPU 아키텍처입니다: $(uname -m)" ;;
    esac
    case "$(basename "$PACKAGE_ROOT")" in
        *"-$expected_suffix") ;;
        *) fail "서버 CPU와 배포물 아키텍처가 다릅니다: expected=$expected_suffix" ;;
    esac

    "$PACKAGE_ROOT/bin/anvil" --version 2>&1 | grep -Eq 'anvil Version: 1\.7\.1([[:space:]-]|$)' ||
        fail "Anvil 1.7.1 검증에 실패했습니다."
    "$PACKAGE_ROOT/jre/bin/java" -version 2>&1 | head -n 1 | grep -Eq 'version "25([\.\"]|$)' ||
        fail "JRE 25 검증에 실패했습니다."
}

ensure_runtime_user() {
    if ! id bcm-local >/dev/null 2>&1; then
        useradd --system --home-dir "$STATE_ROOT" --shell /usr/sbin/nologin bcm-local
    fi
    install -d -m 0755 "$INSTALL_ROOT" "$RELEASE_ROOT" "$CONFIG_ROOT"
    install -d -o bcm-local -g bcm-local -m 0700 "$STATE_ROOT"
}

install_units() {
    install -m 0644 "$CURRENT_LINK/systemd/bcm-local-anvil.service" "$SYSTEMD_ROOT/bcm-local-anvil.service"
    install -m 0644 "$CURRENT_LINK/systemd/bcm-local-stub.service" "$SYSTEMD_ROOT/bcm-local-stub.service"
    systemctl daemon-reload
}

start_services() {
    systemctl enable bcm-local-anvil.service bcm-local-stub.service >/dev/null || return 1
    systemctl restart bcm-local-anvil.service || return 1
    "$CURRENT_LINK/bin/bcm-local" health-chain || return 1
    systemctl restart bcm-local-stub.service || return 1
    "$CURRENT_LINK/bin/bcm-local" health-stub || return 1
}

stop_services() {
    if systemctl cat bcm-local-stub.service >/dev/null 2>&1; then
        systemctl stop bcm-local-stub.service
    fi
    if systemctl cat bcm-local-anvil.service >/dev/null 2>&1; then
        systemctl stop bcm-local-anvil.service
    fi
}

install_release() {
    verify_package
    require_root
    command -v systemctl >/dev/null 2>&1 || fail "systemd 서버에서만 설치할 수 있습니다."
    ensure_runtime_user

    release_name="$(basename "$PACKAGE_ROOT")"
    release_dir="$RELEASE_ROOT/$release_name"
    old_current="$(readlink -f "$CURRENT_LINK" 2>/dev/null || true)"
    stop_services

    if [ ! -d "$release_dir" ]; then
        temporary_release="$RELEASE_ROOT/.$release_name.tmp.$$"
        cp -a "$PACKAGE_ROOT" "$temporary_release"
        mv "$temporary_release" "$release_dir"
    fi
    if [ -n "$old_current" ] && [ "$old_current" != "$release_dir" ]; then
        ln -sfn "$old_current" "$PREVIOUS_LINK"
    fi
    ln -sfn "$release_dir" "$CURRENT_LINK"

    if [ ! -f "$CONFIG_ROOT/stub.env" ]; then
        install -m 0600 "$CURRENT_LINK/config/stub.env.example" "$CONFIG_ROOT/stub.env"
    fi
    chown root:root "$CONFIG_ROOT/stub.env"
    chmod 0600 "$CONFIG_ROOT/stub.env"
    install_units

    if ! start_services; then
        if [ -n "$old_current" ] && [ -d "$old_current" ]; then
            ln -sfn "$old_current" "$CURRENT_LINK"
            install_units
            start_services
        fi
        fail "새 release 기동에 실패해 이전 release를 복원했습니다."
    fi
    echo "설치 완료: $release_dir"
}

rollback_release() {
    require_root
    command -v systemctl >/dev/null 2>&1 || fail "systemd 서버에서만 롤백할 수 있습니다."
    target="${1:-$(readlink -f "$PREVIOUS_LINK" 2>/dev/null || true)}"
    [ -n "$target" ] || fail "롤백할 이전 release가 없습니다."
    case "$target" in
        /*) ;;
        *) target="$RELEASE_ROOT/$target" ;;
    esac
    target="$(readlink -f "$target" 2>/dev/null || true)"
    case "$target" in
        "$RELEASE_ROOT"/*) ;;
        *) fail "release 디렉터리 밖으로 롤백할 수 없습니다." ;;
    esac
    [ -d "$target" ] || fail "롤백 release가 없습니다: $target"

    old_current="$(readlink -f "$CURRENT_LINK" 2>/dev/null || true)"
    stop_services
    ln -sfn "$target" "$CURRENT_LINK"
    [ -z "$old_current" ] || ln -sfn "$old_current" "$PREVIOUS_LINK"
    install_units
    start_services
    echo "롤백 완료: $target"
}

case "${1:-install}" in
    verify) verify_package ;;
    install) install_release ;;
    rollback) rollback_release "${2:-}" ;;
    *) fail "사용법: ./install.sh {verify|install|rollback [release]}" ;;
esac

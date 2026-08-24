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
    status=0
    if systemctl cat bcm-local-stub.service >/dev/null 2>&1; then
        systemctl stop bcm-local-stub.service || status=1
    fi
    if systemctl cat bcm-local-anvil.service >/dev/null 2>&1; then
        systemctl stop bcm-local-anvil.service || status=1
    fi
    return "$status"
}

release_identity() {
    package_root="${1:-$PACKAGE_ROOT}"
    digest="$(sha256sum "$package_root/SHA256SUMS" | awk '{print $1}')"
    [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || return 1
    printf '%s-%s\n' "$(basename "$package_root")" "$digest"
}

secure_release_tree() {
    release_tree="$1"
    expected_digest="$2"
    actual_digest="$(sha256sum "$release_tree/SHA256SUMS" | awk '{print $1}')"
    [ "$actual_digest" = "$expected_digest" ] || return 1
    chown -R root:root "$release_tree"
    chmod -R go-w "$release_tree"
    (cd "$release_tree" && sha256sum -c SHA256SUMS)
}

recover_failed_install() {
    old_current="$1"
    stop_services || true
    if [ -n "$old_current" ] && [ -d "$old_current" ]; then
        ln -sfn "$old_current" "$CURRENT_LINK" || return 1
        install_units || return 1
        if start_services; then
            return 0
        fi
        stop_services || true
        return 1
    fi

    disable_failed=0
    systemctl disable bcm-local-anvil.service bcm-local-stub.service >/dev/null || disable_failed=1
    stop_services || disable_failed=1
    if [ -L "$CURRENT_LINK" ]; then
        unlink "$CURRENT_LINK" || disable_failed=1
    elif [ -e "$CURRENT_LINK" ]; then
        disable_failed=1
    fi
    return "$disable_failed"
}

switch_release() {
    target="$1"
    old_current="$2"
    if ! stop_services; then
        if [ -n "$old_current" ]; then
            start_services || true
        else
            recover_failed_install "" || true
        fi
        return 2
    fi
    if ! ln -sfn "$target" "$CURRENT_LINK"; then
        recover_failed_install "$old_current" || true
        return 2
    fi
    if install_units && start_services; then
        return 0
    fi
    if recover_failed_install "$old_current"; then
        return 1
    fi
    return 2
}

install_release() {
    verify_package
    require_root
    command -v systemctl >/dev/null 2>&1 || fail "systemd 서버에서만 설치할 수 있습니다."
    ensure_runtime_user

    release_name="$(release_identity "$PACKAGE_ROOT")" || fail "release content digest를 계산할 수 없습니다."
    package_digest="${release_name##*-}"
    release_dir="$RELEASE_ROOT/$release_name"
    old_current="$(readlink -f "$CURRENT_LINK" 2>/dev/null || true)"

    if [ ! -d "$release_dir" ]; then
        temporary_release="$RELEASE_ROOT/.$release_name.tmp.$$"
        cp -a "$PACKAGE_ROOT" "$temporary_release"
        secure_release_tree "$temporary_release" "$package_digest"
        mv "$temporary_release" "$release_dir"
    else
        secure_release_tree "$release_dir" "$package_digest"
    fi

    if [ ! -f "$CONFIG_ROOT/stub.env" ]; then
        install -m 0600 "$release_dir/config/stub.env.example" "$CONFIG_ROOT/stub.env"
    fi
    chown root:root "$CONFIG_ROOT/stub.env"
    chmod 0600 "$CONFIG_ROOT/stub.env"

    switch_status=0
    switch_release "$release_dir" "$old_current" || switch_status=$?
    case "$switch_status" in
        0) ;;
        1) fail "새 release 기동에 실패해 이전 상태를 복원했습니다." ;;
        *) fail "release 전환과 복구를 안전하게 완료하지 못했습니다. systemd 상태를 확인하세요." ;;
    esac
    if [ -n "$old_current" ] && [ "$old_current" != "$release_dir" ]; then
        ln -sfn "$old_current" "$PREVIOUS_LINK"
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
    [ -n "$old_current" ] || fail "현재 release가 없어 롤백할 수 없습니다."
    switch_status=0
    switch_release "$target" "$old_current" || switch_status=$?
    case "$switch_status" in
        0) ;;
        1) fail "롤백 대상 기동에 실패해 기존 release를 복원했습니다." ;;
        *) fail "롤백 전환과 복구를 안전하게 완료하지 못했습니다. systemd 상태를 확인하세요." ;;
    esac
    ln -sfn "$old_current" "$PREVIOUS_LINK"
    echo "롤백 완료: $target"
}

case "${1:-install}" in
    verify) verify_package ;;
    install) install_release ;;
    rollback) rollback_release "${2:-}" ;;
    *) fail "사용법: ./install.sh {verify|install|rollback [release]}" ;;
esac

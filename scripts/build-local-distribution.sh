#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/.."

fail() {
    echo "오류: $*" >&2
    exit 1
}

checksum() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1"
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1"
    else
        fail "SHA-256 checksum 명령을 찾을 수 없습니다."
    fi
}

verify_linux_architecture() {
    local file_path="$1"
    local description
    description="$(file -b "$file_path")"
    case "$target" in
        linux-x86_64)
            printf '%s' "$description" | grep -Eq 'ELF 64-bit.*(x86-64|x86_64)' ||
                fail "linux-x86_64 바이너리가 아닙니다: $file_path ($description)"
            ;;
        linux-aarch64)
            printf '%s' "$description" | grep -Eq 'ELF 64-bit.*(ARM aarch64|ARM64)' ||
                fail "linux-aarch64 바이너리가 아닙니다: $file_path ($description)"
            ;;
    esac
}

verify_anvil() {
    local version_output
    if version_output="$("$anvil_bin" --version 2>&1)"; then
        printf '%s' "$version_output" | grep -Eq 'anvil Version: 1\.7\.1([[:space:]-]|$)' ||
            fail "Anvil 1.7.1만 배포할 수 있습니다."
        return
    fi
    verify_linux_architecture "$anvil_bin"
    [ "${BCM_LOCAL_DIST_ANVIL_VERSION:-}" = "1.7.1" ] ||
        fail "크로스 조립은 검증한 BCM_LOCAL_DIST_ANVIL_VERSION=1.7.1이 필요합니다."
}

verify_jre() {
    local version_output
    if version_output="$("$jre_home/bin/java" -version 2>&1)"; then
        printf '%s\n' "$version_output" | head -n 1 | grep -Eq 'version "25([\.\"]|$)' ||
            fail "JRE 25만 배포할 수 있습니다."
        return
    fi
    verify_linux_architecture "$jre_home/bin/java"
    [ -f "$jre_home/release" ] || fail "크로스 조립 JRE의 release 메타데이터가 없습니다."
    grep -Eq '^JAVA_VERSION="25([\.\"]|$)' "$jre_home/release" || fail "JRE 25만 배포할 수 있습니다."
}

target="${1:-}"
case "$target" in
    linux-x86_64|linux-aarch64) ;;
    *) fail "대상은 linux-x86_64 또는 linux-aarch64여야 합니다." ;;
esac

version="$(tr -d '[:space:]' < VERSION)"
[ -n "$version" ] || fail "VERSION이 비어 있습니다."
output="${2:-build/distributions/blockchain-manager-local-${version}-${target}.tar.gz}"

anvil_bin="${BCM_LOCAL_DIST_ANVIL_BIN:-}"
jre_home="${BCM_LOCAL_DIST_JRE_HOME:-}"
stub_jar="${BCM_LOCAL_DIST_STUB_JAR:-}"
contract_dir="${BCM_LOCAL_DIST_CONTRACT_DIR:-}"

[ -x "$anvil_bin" ] || fail "실행 가능한 Anvil 파일이 필요합니다: BCM_LOCAL_DIST_ANVIL_BIN"
[ -x "$jre_home/bin/java" ] || fail "전용 JRE가 필요합니다: BCM_LOCAL_DIST_JRE_HOME/bin/java"
[ -s "$stub_jar" ] || fail "Stub Boot JAR가 필요합니다: BCM_LOCAL_DIST_STUB_JAR"
[ -s "$contract_dir/BcmSweep.sol/BcmSweep.json" ] || fail "BcmSweep artifact가 없습니다."
[ -s "$contract_dir/LocalGaslessDelegation.sol/LocalGaslessDelegation.json" ] || \
    fail "LocalGaslessDelegation artifact가 없습니다."
[ -s "$contract_dir/TestToken.sol/TestToken.json" ] || fail "TestToken artifact가 없습니다."

verify_anvil
verify_jre

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
root_name="blockchain-manager-local-${version}-${target}"
root_dir="$work_dir/$root_name"
mkdir -p "$root_dir/bin" "$root_dir/lib" "$root_dir/contracts" "$root_dir/config" "$root_dir/systemd"

cp "$anvil_bin" "$root_dir/bin/anvil"
cp config/local-distribution/bin/bcm-local "$root_dir/bin/bcm-local"
cp config/local-distribution/install.sh "$root_dir/install.sh"
cp config/local-distribution/RUNBOOK.md "$root_dir/RUNBOOK.md"
cp -R "$jre_home"/. "$root_dir/jre"
cp "$stub_jar" "$root_dir/lib/blockchain-manager-test-support.jar"
cp -R "$contract_dir"/. "$root_dir/contracts"
cp config/local-distribution/config/stub.env.example "$root_dir/config/stub.env.example"
cp config/local-distribution/systemd/bcm-local-anvil.service "$root_dir/systemd/bcm-local-anvil.service"
cp config/local-distribution/systemd/bcm-local-stub.service "$root_dir/systemd/bcm-local-stub.service"
chmod 0755 "$root_dir/bin/anvil" "$root_dir/bin/bcm-local" "$root_dir/install.sh" "$root_dir/jre/bin/java"

cat > "$root_dir/LICENSES.txt" <<EOF
Blockchain Manager local integration distribution ${version}

- Blockchain Manager: repository license applies to the bundled Stub and contracts.
- Foundry Anvil 1.7.1: Apache-2.0 OR MIT, https://github.com/foundry-rs/foundry
- Bundled JRE 25: use the license supplied by BCM_LOCAL_DIST_JRE_HOME provider.

This archive contains no PostgreSQL, Kafka, Docker image, Fireblocks credential, or production key.
EOF

(
    cd "$root_dir"
    find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | while IFS= read -r file; do
        checksum "$file"
    done > SHA256SUMS
)

mkdir -p "$(dirname "$output")"
output="$(cd "$(dirname "$output")" && pwd -P)/$(basename "$output")"
if tar --version 2>/dev/null | grep -q '^bsdtar '; then
    owner_options=(--uid 0 --gid 0)
else
    owner_options=(--owner=0 --group=0 --numeric-owner)
fi
COPYFILE_DISABLE=1 tar --no-xattrs "${owner_options[@]}" -czf "$output" -C "$work_dir" "$root_name"
echo "$output"

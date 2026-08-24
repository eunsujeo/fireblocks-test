#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/../.."

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

fixture_dir="$work_dir/fixture"
mkdir -p \
    "$fixture_dir/jre/bin" \
    "$fixture_dir/contracts/BcmSweep.sol" \
    "$fixture_dir/contracts/LocalGaslessDelegation.sol" \
    "$fixture_dir/contracts/TestToken.sol"

cat > "$fixture_dir/anvil" <<'EOF'
#!/usr/bin/env sh
echo 'anvil Version: 1.7.1-stable'
EOF
chmod +x "$fixture_dir/anvil"

cat > "$fixture_dir/jre/bin/java" <<'EOF'
#!/usr/bin/env sh
echo 'openjdk version "25"' >&2
EOF
chmod +x "$fixture_dir/jre/bin/java"

printf 'stub-jar\n' > "$fixture_dir/stub.jar"
printf '{"abi":[],"bytecode":{"object":"0x01"}}\n' > "$fixture_dir/contracts/BcmSweep.sol/BcmSweep.json"
printf '{"abi":[],"bytecode":{"object":"0x03"}}\n' > \
    "$fixture_dir/contracts/LocalGaslessDelegation.sol/LocalGaslessDelegation.json"
printf '{"abi":[],"bytecode":{"object":"0x02"}}\n' > "$fixture_dir/contracts/TestToken.sol/TestToken.json"

archive="$work_dir/blockchain-manager-local-test.tar.gz"
BCM_LOCAL_DIST_ANVIL_BIN="$fixture_dir/anvil" \
BCM_LOCAL_DIST_JRE_HOME="$fixture_dir/jre" \
BCM_LOCAL_DIST_STUB_JAR="$fixture_dir/stub.jar" \
BCM_LOCAL_DIST_CONTRACT_DIR="$fixture_dir/contracts" \
./scripts/build-local-distribution.sh linux-x86_64 "$archive"

python3 - "$archive" <<'PY'
import sys
import tarfile

with tarfile.open(sys.argv[1], "r:gz") as archive:
    unsafe = [(member.name, member.uid, member.gid) for member in archive.getmembers() if member.uid != 0 or member.gid != 0]
assert not unsafe, unsafe[:5]
PY

extract_dir="$work_dir/extract"
mkdir -p "$extract_dir"
tar -xzf "$archive" -C "$extract_dir"
root_dir="$(find "$extract_dir" -mindepth 1 -maxdepth 1 -type d)"

for required in \
    bin/anvil \
    bin/bcm-local \
    install.sh \
    RUNBOOK.md \
    jre/bin/java \
    lib/blockchain-manager-test-support.jar \
    contracts/BcmSweep.sol/BcmSweep.json \
    contracts/LocalGaslessDelegation.sol/LocalGaslessDelegation.json \
    contracts/TestToken.sol/TestToken.json \
    config/stub.env.example \
    systemd/bcm-local-anvil.service \
    systemd/bcm-local-stub.service \
    SHA256SUMS \
    LICENSES.txt; do
    [ -f "$root_dir/$required" ] || {
        echo "폐쇄망 배포 파일이 누락됐습니다: $required" >&2
        exit 1
    }
done

(cd "$root_dir" && sha256sum -c SHA256SUMS >/dev/null)

if find "$root_dir" -iname '*postgres*' -o -iname '*kafka*' -o -iname '*docker*' | grep -q .; then
    echo "폐쇄망 배포물에 PostgreSQL·Kafka·Docker가 포함됐습니다." >&2
    exit 1
fi

if grep -R -E 'api[_-]?key=.+|private[_-]?key=-----BEGIN|https?://(api|sandbox-api)\.fireblocks' "$root_dir" >/dev/null; then
    echo "폐쇄망 배포물에 실 Secret 또는 Fireblocks endpoint가 포함됐습니다." >&2
    exit 1
fi

if grep -R -E '(^|[[:space:]])(curl|wget)([[:space:]]|$)' "$root_dir/bin" "$root_dir/systemd" "$root_dir/install.sh" >/dev/null; then
    echo "폐쇄망 실행 스크립트가 미포함 다운로드·HTTP 도구에 의존합니다." >&2
    exit 1
fi

grep -q '^BCM_VENDOR_MODE=STUB$' "$root_dir/config/stub.env.example"
grep -q '^BCM_CHAIN_MODE=LOCAL$' "$root_dir/config/stub.env.example"
grep -q '127.0.0.1' "$root_dir/config/stub.env.example"
grep -q 'After=bcm-local-anvil.service' "$root_dir/systemd/bcm-local-stub.service"
grep -q 'ExecStartPre=.*/bcm-local health-chain' "$root_dir/systemd/bcm-local-stub.service"
grep -Fq '[ -r "$ENV_FILE" ]' "$root_dir/bin/bcm-local"
grep -q 'sha256sum -c SHA256SUMS' "$root_dir/install.sh"
grep -q 'releases/' "$root_dir/install.sh"
grep -q 'ln -sfn' "$root_dir/install.sh"
grep -q 'rollback' "$root_dir/install.sh"
grep -q 'PostgreSQL.*Kafka.*변경하지' "$root_dir/RUNBOOK.md"

function_file="$work_dir/start-services.sh"
awk '
    /^start_services\(\) \{/ { capture = 1 }
    capture { print }
    capture && /^}$/ { exit }
' "$root_dir/install.sh" > "$function_file"

fake_bin="$work_dir/fake-bin"
fake_current="$work_dir/fake-current"
mkdir -p "$fake_bin" "$fake_current/bin"
cat > "$fake_bin/systemctl" <<'EOF'
#!/usr/bin/env sh
case "$*" in
    'enable bcm-local-anvil.service bcm-local-stub.service') stage=enable ;;
    'restart bcm-local-anvil.service') stage=restart-anvil ;;
    'restart bcm-local-stub.service') stage=restart-stub ;;
    *) exit 2 ;;
esac
[ "${BCM_FAIL_STAGE:-}" != "$stage" ]
EOF
cat > "$fake_current/bin/bcm-local" <<'EOF'
#!/usr/bin/env sh
case "${1:-}" in
    health-chain) stage=health-chain ;;
    health-stub) stage=health-stub ;;
    *) exit 2 ;;
esac
[ "${BCM_FAIL_STAGE:-}" != "$stage" ]
EOF
chmod +x "$fake_bin/systemctl" "$fake_current/bin/bcm-local"

for stage in enable restart-anvil health-chain restart-stub health-stub; do
    if BCM_FAIL_STAGE="$stage" PATH="$fake_bin:$PATH" CURRENT_LINK="$fake_current" \
        bash -c 'set -euo pipefail; source "$1"; if start_services; then exit 0; else exit 1; fi' \
        bash "$function_file"; then
        echo "서비스 기동 중간 실패가 성공으로 가려졌습니다: $stage" >&2
        exit 1
    fi
done

extract_function() {
    local function_name="$1"
    local output="$2"
    awk -v signature="${function_name}() {" '
        $0 == signature { capture = 1 }
        capture { print }
        capture && /^}$/ { exit }
    ' "$root_dir/install.sh" > "$output"
    [ -s "$output" ] || {
        echo "설치기 함수가 없습니다: $function_name" >&2
        exit 1
    }
}

release_identity_file="$work_dir/release-identity.sh"
extract_function release_identity "$release_identity_file"
mkdir -p "$work_dir/package-a/blockchain-manager-local-same" "$work_dir/package-b/blockchain-manager-local-same"
printf 'first manifest\n' > "$work_dir/package-a/blockchain-manager-local-same/SHA256SUMS"
printf 'second manifest\n' > "$work_dir/package-b/blockchain-manager-local-same/SHA256SUMS"
first_identity="$(bash -c 'source "$1"; release_identity "$2"' bash \
    "$release_identity_file" "$work_dir/package-a/blockchain-manager-local-same")"
second_identity="$(bash -c 'source "$1"; release_identity "$2"' bash \
    "$release_identity_file" "$work_dir/package-b/blockchain-manager-local-same")"
[ "$first_identity" != "$second_identity" ] || {
    echo "내용이 다른 동일 버전 배포물이 같은 release 경로를 사용합니다." >&2
    exit 1
}
case "$first_identity" in
    blockchain-manager-local-same-[0-9a-f][0-9a-f]*) ;;
    *) echo "release 식별자에 content digest가 없습니다." >&2; exit 1 ;;
esac

recovery_function_file="$work_dir/recover-failed-install.sh"
extract_function recover_failed_install "$recovery_function_file"
secure_release_file="$work_dir/secure-release-tree.sh"
extract_function secure_release_tree "$secure_release_file"
recovery_root="$work_dir/recovery"
mkdir -p "$recovery_root/new" "$recovery_root/old" "$recovery_root/bin"
ln -s "$recovery_root/new" "$recovery_root/current"
cat > "$recovery_root/bin/systemctl" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$RECOVERY_LOG"
EOF
chmod +x "$recovery_root/bin/systemctl"
RECOVERY_LOG="$recovery_root/first.log" PATH="$recovery_root/bin:$PATH" CURRENT_LINK="$recovery_root/current" \
    bash -c '
        set -euo pipefail
        source "$1"
        stop_services() { printf "stop\n" >> "$RECOVERY_LOG"; }
        install_units() { return 99; }
        start_services() { return 99; }
        recover_failed_install ""
    ' bash "$recovery_function_file"
[ ! -e "$recovery_root/current" ] || {
    echo "최초 설치 실패 뒤 current 링크가 제거되지 않았습니다." >&2
    exit 1
}
grep -q '^stop$' "$recovery_root/first.log"
grep -q '^disable bcm-local-anvil.service bcm-local-stub.service$' "$recovery_root/first.log"

ln -s "$recovery_root/new" "$recovery_root/current"
RECOVERY_LOG="$recovery_root/rollback.log" CURRENT_LINK="$recovery_root/current" \
    bash -c '
        set -euo pipefail
        source "$1"
        stop_services() { printf "stop\n" >> "$RECOVERY_LOG"; }
        install_units() { printf "install-units\n" >> "$RECOVERY_LOG"; }
        start_services() { printf "start-services\n" >> "$RECOVERY_LOG"; }
        recover_failed_install "$2"
    ' bash "$recovery_function_file" "$recovery_root/old"
[ "$(readlink -f "$recovery_root/current")" = "$(cd "$recovery_root/old" && pwd -P)" ] || {
    echo "새 release 실패 뒤 이전 release가 복원되지 않았습니다." >&2
    exit 1
}
grep -q '^stop$' "$recovery_root/rollback.log"
grep -q '^install-units$' "$recovery_root/rollback.log"
grep -q '^start-services$' "$recovery_root/rollback.log"

security_root="$work_dir/security"
mkdir -p "$security_root/bin" "$security_root/release"
printf 'manifest\n' > "$security_root/release/SHA256SUMS"
for command in chown chmod; do
    cat > "$security_root/bin/$command" <<'EOF'
#!/usr/bin/env sh
printf '%s %s\n' "$(basename "$0")" "$*" >> "$SECURITY_LOG"
EOF
    chmod +x "$security_root/bin/$command"
done
cat > "$security_root/bin/sha256sum" <<'EOF'
#!/usr/bin/env sh
printf 'sha256sum %s\n' "$*" >> "$SECURITY_LOG"
case "${1:-}" in
    -c) ;;
    *) printf '%064d  %s\n' 0 "${1:-}" ;;
esac
EOF
chmod +x "$security_root/bin/sha256sum"
SECURITY_LOG="$security_root/security.log" PATH="$security_root/bin:$PATH" \
    bash -c 'set -euo pipefail; source "$1"; secure_release_tree "$2" "$3"' \
    bash "$secure_release_file" "$security_root/release" "$(printf '%064d' 0)"
grep -Fq "chown -R root:root $security_root/release" "$security_root/security.log"
grep -Fq "chmod -R go-w $security_root/release" "$security_root/security.log"
grep -q '^sha256sum -c SHA256SUMS$' "$security_root/security.log"
: > "$security_root/security.log"
if SECURITY_LOG="$security_root/security.log" PATH="$security_root/bin:$PATH" \
    bash -c 'set -euo pipefail; source "$1"; secure_release_tree "$2" "$3"' \
    bash "$secure_release_file" "$security_root/release" "$(printf '%064d' 1)"; then
    echo "release 경로와 다른 manifest digest가 허용됐습니다." >&2
    exit 1
fi
if grep -q '^chown ' "$security_root/security.log"; then
    echo "manifest identity 확인 전에 release 소유권을 변경했습니다." >&2
    exit 1
fi

echo "local distribution tests passed"

#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/.."

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

echo "local distribution tests passed"

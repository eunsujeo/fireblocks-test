#!/usr/bin/env bash

set -euo pipefail
cd "$(dirname "$0")/../.."

work_dir="$(mktemp -d)"
api_pid=""
webhook_pid=""
bat_pid=""
compose_project="bcm-production-boundary-$(python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.argv[1].encode()).hexdigest()[:12])' "$work_dir")"

free_port() {
    python3 - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

postgres_port="$(free_port)"
kafka_port="$(free_port)"
api_port="$(free_port)"
management_port="$(free_port)"
webhook_port="$(free_port)"
webhook_management_port="$(free_port)"
production_java_home="$({ ./gradlew -q javaToolchains; } | awk '
    /Location:/ {
        location = $0
        sub(/^.*Location:[[:space:]]*/, "", location)
    }
    /Language Version:[[:space:]]*25/ {
        print location
        exit
    }
')"
production_java="$production_java_home/bin/java"
[ -x "$production_java" ] || {
    echo "Gradle JDK 25 launcher를 찾을 수 없습니다." >&2
    exit 1
}

cleanup() {
    if [ -n "$api_pid" ] && kill -0 "$api_pid" 2>/dev/null; then
        kill "$api_pid" 2>/dev/null || true
        wait "$api_pid" 2>/dev/null || true
    fi
    if [ -n "$webhook_pid" ] && kill -0 "$webhook_pid" 2>/dev/null; then
        kill "$webhook_pid" 2>/dev/null || true
        wait "$webhook_pid" 2>/dev/null || true
    fi
    if [ -n "$bat_pid" ] && kill -0 "$bat_pid" 2>/dev/null; then
        kill "$bat_pid" 2>/dev/null || true
        wait "$bat_pid" 2>/dev/null || true
    fi
    BCM_LOCAL_POSTGRES_PORT="$postgres_port" \
        BCM_LOCAL_KAFKA_PORT="$kafka_port" \
        docker compose -p "$compose_project" -f config/local-compose.yaml \
        down --volumes --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$work_dir"
}
trap cleanup EXIT

if grep -Rq 'com\.whatto\.bcm\.infra\.' blockchain-manager-application/src/main; then
    echo "application 모듈이 infra 구현을 직접 import합니다." >&2
    exit 1
fi
if grep -Eq 'project\(":blockchain-manager-infra:' blockchain-manager-application/build.gradle.kts; then
    echo "application 모듈이 infra 프로젝트를 직접 의존합니다." >&2
    exit 1
fi

projects_output="$(./gradlew -PbcmProductionOnly=true projects)"
for excluded in ':blockchain-manager-app:bcm-admin' ':blockchain-manager-test-support'; do
    if grep -Fq -- "$excluded" <<<"$projects_output"; then
        echo "production-only 빌드에 제외 모듈이 남아 있습니다: $excluded" >&2
        exit 1
    fi
done

./gradlew -PbcmProductionOnly=true \
    :blockchain-manager-app:bcm-api:bootJar \
    :blockchain-manager-app:bcm-webhook:bootJar \
    :blockchain-manager-app:bcm-bat:bootJar

for module in ':blockchain-manager-app:bcm-api' ':blockchain-manager-app:bcm-webhook' ':blockchain-manager-app:bcm-bat'; do
    runtime_output="$(./gradlew -PbcmProductionOnly=true "$module:dependencies" --configuration runtimeClasspath)"
    for excluded in 'bcm-admin' 'blockchain-manager-test-support'; do
        if grep -Fq -- "$excluded" <<<"$runtime_output"; then
            echo "$module runtimeClasspath에 제외 모듈이 있습니다: $excluded" >&2
            exit 1
        fi
    done
done

api_jar="$(find blockchain-manager-app/bcm-api/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)"
webhook_jar="$(find blockchain-manager-app/bcm-webhook/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)"
bat_jar="$(find blockchain-manager-app/bcm-bat/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)"
[ -n "$api_jar" ] && [ -n "$webhook_jar" ] && [ -n "$bat_jar" ] || {
    echo "production bootJar 산출물을 찾을 수 없습니다." >&2
    exit 1
}

docker info >/dev/null 2>&1 || {
    echo "production 실행 경계 테스트에는 실행 중인 Docker가 필요합니다." >&2
    exit 1
}
BCM_LOCAL_POSTGRES_PORT="$postgres_port" \
    BCM_LOCAL_KAFKA_PORT="$kafka_port" \
    docker compose -p "$compose_project" -f config/local-compose.yaml \
    up -d --wait --wait-timeout 120 postgres kafka

common_environment=(
    "SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:$postgres_port/bcm"
    "SPRING_DATASOURCE_USERNAME=postgres"
    "SPRING_DATASOURCE_PASSWORD=bcm"
    "KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:$kafka_port"
    "BCM_WEBHOOK_WORKER_ENABLED=false"
    "BCM_OUTBOX_RELAY_ENABLED=false"
    "BCM_OPERATIONAL_METRICS_INITIAL_DELAY_MILLIS=3600000"
)

env \
    "${common_environment[@]}" \
    "BCM_API_PORT=$api_port" \
    "BCM_MANAGEMENT_PORT=$management_port" \
    "$production_java" -jar "$api_jar" >"$work_dir/api.log" 2>&1 &
api_pid=$!

api_ready=false
for _ in $(seq 1 120); do
    if curl -fsS "http://127.0.0.1:$management_port/actuator/health" >"$work_dir/api-health.json" 2>/dev/null; then
        api_ready=true
        break
    fi
    if ! kill -0 "$api_pid" 2>/dev/null; then
        break
    fi
    sleep 1
done
if [ "$api_ready" != true ]; then
    tail -n 120 "$work_dir/api.log" >&2 || true
    echo "production-only BCM API가 readiness에 도달하지 못했습니다." >&2
    exit 1
fi
grep -Fq '"status":"UP"' "$work_dir/api-health.json" || {
    echo "production-only BCM API health가 UP이 아닙니다." >&2
    exit 1
}

api_webhook_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --request POST --header 'Content-Type: application/json' --data '{}' \
    "http://127.0.0.1:$api_port/webhook")"
[ "$api_webhook_status" = 404 ] || {
    echo "production-only BCM API가 /webhook을 소유하고 있습니다: HTTP $api_webhook_status" >&2
    exit 1
}

env \
    "${common_environment[@]}" \
    "BCM_WEBHOOK_PORT=$webhook_port" \
    "BCM_WEBHOOK_MANAGEMENT_PORT=$webhook_management_port" \
    "$production_java" -jar "$webhook_jar" >"$work_dir/webhook.log" 2>&1 &
webhook_pid=$!

webhook_ready=false
for _ in $(seq 1 120); do
    if curl -fsS "http://127.0.0.1:$webhook_management_port/actuator/health" \
        >"$work_dir/webhook-health.json" 2>/dev/null; then
        webhook_ready=true
        break
    fi
    if ! kill -0 "$webhook_pid" 2>/dev/null; then
        break
    fi
    sleep 1
done
if [ "$webhook_ready" != true ]; then
    tail -n 120 "$work_dir/webhook.log" >&2 || true
    echo "production-only BCM Webhook이 readiness에 도달하지 못했습니다." >&2
    exit 1
fi
grep -Fq '"status":"UP"' "$work_dir/webhook-health.json" || {
    echo "production-only BCM Webhook health가 UP이 아닙니다." >&2
    exit 1
}

kill "$api_pid"
wait "$api_pid" 2>/dev/null || true
api_pid=""
kill "$webhook_pid"
wait "$webhook_pid" 2>/dev/null || true
webhook_pid=""

env \
    "${common_environment[@]}" \
    "BCM_JOB=sweep-reconciliation-once" \
    "$production_java" -jar "$bat_jar" >"$work_dir/bat.log" 2>&1 &
bat_pid=$!
for _ in $(seq 1 120); do
    if ! kill -0 "$bat_pid" 2>/dev/null; then
        break
    fi
    sleep 1
done
if kill -0 "$bat_pid" 2>/dev/null; then
    kill "$bat_pid" 2>/dev/null || true
    wait "$bat_pid" 2>/dev/null || true
    bat_pid=""
    tail -n 120 "$work_dir/bat.log" >&2 || true
    echo "production-only BCM BAT one-shot이 120초 안에 종료되지 않았습니다." >&2
    exit 1
fi
if ! wait "$bat_pid"; then
    bat_pid=""
        tail -n 120 "$work_dir/bat.log" >&2 || true
        echo "production-only BCM BAT one-shot 실행이 실패했습니다." >&2
        exit 1
fi
bat_pid=""
grep -Fq 'one-shot batch sweep reconciliation completed' "$work_dir/bat.log" || {
    tail -n 120 "$work_dir/bat.log" >&2 || true
    echo "production-only BCM BAT one-shot 완료 로그를 찾지 못했습니다." >&2
    exit 1
}

echo "production boundary tests passed"

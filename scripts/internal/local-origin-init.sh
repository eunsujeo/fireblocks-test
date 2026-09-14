#!/bin/sh

set -eu

# Docker entrypoint의 신규 로컬 Stub 데이터셋 전용. 기존 DB나 Fireblocks DB에는 등록하지 않는다.
[ "${BCM_LOCAL_DATASET:-}" = stub ] || exit 0
: "${BCM_LOCAL_PLATFORM_INSTANCE_ID:?local platform instance ID is required}"
psql --set ON_ERROR_STOP=1 --single-transaction \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set instance_id="$BCM_LOCAL_PLATFORM_INSTANCE_ID" <<'SQL'
INSERT INTO bcm_prvd_bndg_m VALUES (
    1, 'local-stub', 'local', 'fireblocks', :'instance_id', 'local-stub', 'LOCAL',
    to_char(CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS'),
    'SYSTEM', '9999', 'SYSTEM', '9999'
);
SQL

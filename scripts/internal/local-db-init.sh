#!/bin/sh

set -eu

schema_directory=/opt/bcm/db/migration
manifest=/opt/bcm/db/migration/manifest.txt

[ -r "$manifest" ] || {
    echo "BCM DB SQL manifest를 읽을 수 없습니다: $manifest" >&2
    exit 1
}

while IFS= read -r migration || [ -n "$migration" ]; do
    case "$migration" in
        ''|'#'*) continue ;;
        */*|*'..'*)
            echo "허용되지 않은 BCM DB SQL 경로입니다: $migration" >&2
            exit 1
            ;;
        V[0-9]*__*.sql) ;;
        *)
            echo "허용되지 않은 BCM DB SQL 파일명입니다: $migration" >&2
            exit 1
            ;;
    esac

    script="$schema_directory/$migration"
    [ -r "$script" ] || {
        echo "BCM DB SQL 파일을 읽을 수 없습니다: $script" >&2
        exit 1
    }

    echo "BCM DB SQL 적용 중: $migration"
    if grep -Fqx -- "-- bcm:transaction=off" "$script"; then
        psql --set ON_ERROR_STOP=1 \
            --username "$POSTGRES_USER" \
            --dbname "$POSTGRES_DB" \
            --file "$script"
    else
        psql --single-transaction --set ON_ERROR_STOP=1 \
            --username "$POSTGRES_USER" \
            --dbname "$POSTGRES_DB" \
            --file "$script"
    fi
done < "$manifest"

echo "BCM DB SQL 적용 완료"

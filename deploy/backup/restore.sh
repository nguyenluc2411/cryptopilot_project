#!/usr/bin/env bash
# Restore of the staging database from a backup (T-096, SRS 4.2.2).
#
#   restore.sh --drill [dump]   restore into a throw-away database container next to the live one, compare it with
#                               the live database table by table and report the time; staging keeps running
#   restore.sh --live  dump     replace the live database: the backend stops, the database is recreated from the
#                               dump, the backend starts and the script waits for it to be healthy; the time from the
#                               stop to healthy is the recovery time (RTO)
#
# Without a dump the newest file in $CD_ROOT/backups is used. TimescaleDB needs timescaledb_pre_restore() and
# timescaledb_post_restore() around a sequential pg_restore: a parallel one (-j) breaks its catalog.
set -euo pipefail

CD_ROOT=${CD_ROOT:-$HOME/cryptopilot}
BE_DIR=$CD_ROOT/cryptopilot_project
DB_CONTAINER=${DB_CONTAINER:-cryptopilot-staging-db-1}
DB_USER=${DB_USER:-cryptopilot}
DB_NAME=${DB_NAME:-cryptopilot}
mode=${1:-}
dump=${2:-$(ls -1t "$CD_ROOT"/backups/*.dump 2>/dev/null | head -1)}
[ "$mode" = --drill ] || [ "$mode" = --live ] || { sed -n '4,9p' "$0"; exit 2; }
[ -f "$dump" ] || { echo "no dump: $dump" >&2; exit 2; }

compose() { (cd "$BE_DIR" && docker compose -f deploy/docker-compose.yml --env-file deploy/.env.staging "$@"); }

# restore_into CONTAINER: recreate $DB_NAME there from $dump.
restore_into() {
  docker cp "$dump" "$1:/tmp/restore.dump"
  docker exec "$1" psql -X -q -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 \
    -c "DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE)" -c "CREATE DATABASE $DB_NAME OWNER $DB_USER"
  docker exec "$1" psql -X -q -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 \
    -c "CREATE EXTENSION IF NOT EXISTS timescaledb" -c "SELECT timescaledb_pre_restore()" > /dev/null 2>&1
  local rc=0
  docker exec "$1" pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner /tmp/restore.dump 2> "$CD_ROOT/backups/restore.log" || rc=$?
  docker exec "$1" psql -X -q -U "$DB_USER" -d "$DB_NAME" -c "SELECT timescaledb_post_restore()" > /dev/null
  docker exec "$1" rm -f /tmp/restore.dump
  if [ $rc -ne 0 ]; then echo "pg_restore reported errors, see $CD_ROOT/backups/restore.log" >&2; return 1; fi
}

# Exact row counts of every table of the public schema, plus the Flyway version.
fingerprint() {
  docker exec "$1" psql -X -At -U "$DB_USER" -d "$DB_NAME" -c "
    SELECT 'flyway', max(version::int)::text FROM flyway_schema_history WHERE version ~ '^[0-9]+$'
    UNION ALL
    SELECT t.table_name, (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM public.%I', t.table_name), false, true, '')))[1]::text
    FROM information_schema.tables t WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
    ORDER BY 1"
}

echo "dump $dump ($(du -h "$dump" | cut -f1))"
start=$SECONDS

if [ "$mode" = --drill ]; then
  image=$(docker inspect -f '{{.Config.Image}}' "$DB_CONTAINER")
  scratch=cryptopilot-restore-drill
  docker rm -f "$scratch" > /dev/null 2>&1 || true
  docker run -d --name "$scratch" --memory 1g -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD=drill \
    -e POSTGRES_DB=postgres "$image" > /dev/null
  until docker exec "$scratch" pg_isready -q -U "$DB_USER" -d postgres 2>/dev/null; do sleep 1; done
  sleep 3   # the image restarts PostgreSQL once after its first initialisation
  until docker exec "$scratch" pg_isready -q -U "$DB_USER" -d postgres 2>/dev/null; do sleep 1; done
  ready=$SECONDS
  restore_into "$scratch"
  restored=$SECONDS
  # Compared with the dump's own source only when the dump is the newest: the live database moves on afterwards.
  fingerprint "$scratch" > /tmp/drill-restored.txt
  fingerprint "$DB_CONTAINER" > /tmp/drill-live.txt
  tables=$(($(wc -l < /tmp/drill-restored.txt) - 1))
  differ=$(diff /tmp/drill-restored.txt /tmp/drill-live.txt | grep -c '^<' || true)
  docker rm -f "$scratch" > /dev/null
  echo "drill: container ready in $((ready - start)) s, restore $((restored - ready)) s, $tables tables;" \
    "$differ differ from live (rows written since the dump)"
  diff /tmp/drill-restored.txt /tmp/drill-live.txt | grep '^[<>]' | head -20 || true
  echo "flyway $(grep '^flyway|' /tmp/drill-restored.txt | cut -d'|' -f2) restored," \
    "$(grep '^flyway|' /tmp/drill-live.txt | cut -d'|' -f2) live"
  exit 0
fi

compose stop backend
restore_into "$DB_CONTAINER"
restored=$SECONDS
compose up -d backend
until [ "$(docker inspect -f '{{.State.Health.Status}}' cryptopilot-staging-backend-1)" = healthy ]; do
  [ $((SECONDS - start)) -gt 900 ] && { echo "backend not healthy after 15 min" >&2; exit 1; }
  sleep 3
done
echo "live restore: database $((restored - start)) s, backend healthy after $((SECONDS - restored)) s; RTO $((SECONDS - start)) s"

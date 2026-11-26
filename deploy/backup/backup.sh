#!/usr/bin/env bash
# Daily backup of the staging database (T-096, SRS 4.2.2), run by cryptopilot-backup.timer.
#
# A custom-format pg_dump of the database, written to a temporary name and renamed when complete, then checked with
# pg_restore --list so an unreadable file is never kept as a backup. The last $BACKUP_KEEP_DAYS daily dumps are kept
# (the deployment keeps its own pre-deploy dumps next to them). Restore with deploy/backup/restore.sh.
#
# Settings: CD_ROOT (default $HOME/cryptopilot), BACKUP_KEEP_DAYS (default 7), DB_CONTAINER, DB_USER, DB_NAME.
set -euo pipefail

CD_ROOT=${CD_ROOT:-$HOME/cryptopilot}
BACKUPS=$CD_ROOT/backups
KEEP=${BACKUP_KEEP_DAYS:-7}
DB_CONTAINER=${DB_CONTAINER:-cryptopilot-staging-db-1}
DB_USER=${DB_USER:-cryptopilot}
DB_NAME=${DB_NAME:-cryptopilot}

mkdir -p "$BACKUPS"
file=$BACKUPS/daily-$(date -u +%Y%m%dT%H%M%SZ).dump
start=$SECONDS
# pg_dump always warns about the circular keys of TimescaleDB's catalog: shown only when the dump fails.
docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$file.part" 2> "$file.err" \
  || { cat "$file.err" >&2; rm -f "$file.part" "$file.err"; exit 1; }
rm -f "$file.err"
docker exec -i "$DB_CONTAINER" pg_restore --list < "$file.part" > /dev/null
mv "$file.part" "$file"
ls -1t "$BACKUPS"/daily-*.dump | tail -n +$((KEEP + 1)) | xargs -r rm -f
echo "backup $file $(du -h "$file" | cut -f1) in $((SECONDS - start)) s"

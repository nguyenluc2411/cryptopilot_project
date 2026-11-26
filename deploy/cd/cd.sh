#!/usr/bin/env bash
# Continuous deployment of staging (T-096), pulled by the staging host itself.
#
# A systemd timer runs this every few minutes on the EC2 host. It looks at the tip of develop in the backend and the
# web repositories; when one has moved and GitHub reports that commit's checks as finished and green, it backs up the
# database, checks the new commits out, rebuilds with deploy/docker-compose.yml and waits until every service is
# healthy. When that fails it checks the previous commits out again, rebuilds them and never retries the failed
# commit; the next commit on develop is tried as usual. A commit whose CI is red is asked about again on every pass,
# so re-running a flaky job on GitHub is enough. Flyway migrations are not undone by a rollback: the dump taken
# just before is in $CD_ROOT/backups.
#
# Nothing is stored on GitHub and no port is opened for it: both repositories are public, git ls-remote has no rate
# limit and the check-run API is asked only while a new commit waits (GITHUB_TOKEN raises its limit when set).
#
# Settings (environment, /etc/cryptopilot-cd.env under systemd):
#   CD_ROOT            folder holding both clones (default $HOME/cryptopilot)
#   CD_BE_BRANCH       backend branch to follow (default develop)
#   CD_BE_FALLBACK     followed instead while CD_BE_BRANCH has no deploy/docker-compose.yml (default empty)
#   CD_FE_BRANCH       web branch to follow (default develop)
#   CD_HEALTH_TIMEOUT  seconds to wait for healthy services (default 420)
#   GITHUB_TOKEN       optional, read-only token for the check-run API
#
# Run by hand: deploy/cd/cd.sh [--force] (--force deploys the current tips without waiting for CI).
set -euo pipefail

CD_ROOT=${CD_ROOT:-$HOME/cryptopilot}
BE_DIR=$CD_ROOT/cryptopilot_project
FE_DIR=$CD_ROOT/cryptopilot-frontend
BE_BRANCH=${CD_BE_BRANCH:-develop}
BE_FALLBACK=${CD_BE_FALLBACK:-}
FE_BRANCH=${CD_FE_BRANCH:-develop}
HEALTH_TIMEOUT=${CD_HEALTH_TIMEOUT:-420}
STATE=$CD_ROOT/.cd
BACKUPS=$CD_ROOT/backups
ENV_FILE=$BE_DIR/deploy/.env.staging
FORCE=${1:-}

mkdir -p "$STATE" "$BACKUPS"
exec 9>"$STATE/lock"
flock -n 9 || exit 0

# On stderr, so that functions whose output is captured can still log.
log() { echo "$(date -u +%FT%TZ) $*" | tee -a "$STATE/deploy.log" >&2; }

compose() { (cd "$BE_DIR" && docker compose -f deploy/docker-compose.yml --env-file "$ENV_FILE" "$@"); }

slug() { git -C "$1" remote get-url origin | sed -E 's#^.*github\.com[:/]##; s#\.git$##'; }

# success | pending | failure for the checks GitHub holds on a commit.
ci_state() {
  local auth=()
  [ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
  curl -fsS --max-time 20 "${auth[@]}" -H 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/$1/commits/$2/check-runs?per_page=100" |
    python3 -I -c '
import json, sys
runs = json.load(sys.stdin).get("check_runs", [])
if not runs:
    print("pending")
elif any(r["status"] != "completed" for r in runs):
    print("pending")
elif all(r["conclusion"] in ("success", "skipped", "neutral") for r in runs):
    print("success")
else:
    print("failure")'
}

# The branch of a repository to follow, and its tip.
be_branch() {
  if [ -n "$BE_FALLBACK" ]; then
    git -C "$BE_DIR" fetch -q origin "$BE_BRANCH"
    git -C "$BE_DIR" cat-file -e FETCH_HEAD:deploy/docker-compose.yml 2>/dev/null || { echo "$BE_FALLBACK"; return; }
  fi
  echo "$BE_BRANCH"
}

tip() { git -C "$1" ls-remote origin "refs/heads/$2" | cut -f1; }

# Decide whether the tip of one repository should be deployed; prints the commit or nothing.
candidate() {
  local name=$1 dir=$2 target=$3 current
  current=$(git -C "$dir" rev-parse HEAD)
  [ -z "$target" ] || [ "$target" = "$current" ] && return 0
  [ "$target" = "$(cat "$STATE/$name.failed" 2>/dev/null)" ] && return 0
  if [ "$FORCE" = "--force" ]; then echo "$target"; return 0; fi
  local state
  state=$(ci_state "$(slug "$dir")" "$target") || { log "$name ${target:0:8}: GitHub not reachable, later"; return 0; }
  case $state in
    success) echo "$target" ;;
    pending) [ "$(cat "$STATE/$name.waiting" 2>/dev/null)" = "$target" ] || log "$name ${target:0:8}: waiting for CI"
             echo "$target" > "$STATE/$name.waiting" ;;
    # Asked again on every pass: a re-run that turns green (a flaky test) is still deployed.
    failure) [ "$(cat "$STATE/$name.cifailed" 2>/dev/null)" = "$target" ] || log "$name ${target:0:8}: CI failed, not deployed"
             echo "$target" > "$STATE/$name.cifailed" ;;
  esac
}

checkout() {
  git -C "$1" cat-file -e "$2^{commit}" 2>/dev/null || git -C "$1" fetch -q origin "$2"
  git -C "$1" checkout -q --detach -f "$2"
}

healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) id state bad
  while [ $SECONDS -lt $deadline ]; do
    bad=0
    for id in $(compose ps -aq); do
      state=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id")
      case $state in "running healthy" | "running none") ;; *) bad=1 ;; esac
    done
    [ $bad -eq 0 ] && return 0
    sleep 10
  done
  compose ps -a | tee -a "$STATE/deploy.log" >&2
  return 1
}

branch=$(be_branch)
be_target=$(candidate backend "$BE_DIR" "$(tip "$BE_DIR" "$branch")")
fe_target=$(candidate web "$FE_DIR" "$(tip "$FE_DIR" "$FE_BRANCH")")
# A backend commit without the staging compose cannot run here (develop before T-004): never deploy one.
if [ -n "$be_target" ]; then
  git -C "$BE_DIR" fetch -q origin "$be_target"
  if ! git -C "$BE_DIR" cat-file -e "$be_target:deploy/docker-compose.yml" 2>/dev/null; then
    log "backend ${be_target:0:8} ($branch) has no deploy/docker-compose.yml, not deployed"
    echo "$be_target" > "$STATE/backend.failed"
    be_target=
  fi
fi
[ -z "$be_target" ] && [ -z "$fe_target" ] && exit 0

be_prev=$(git -C "$BE_DIR" rev-parse HEAD)
fe_prev=$(git -C "$FE_DIR" rev-parse HEAD)
log "deploying backend ${be_target:-$be_prev} ($branch), web ${fe_target:-$fe_prev} ($FE_BRANCH)"

dump=$BACKUPS/pre-deploy-$(date -u +%Y%m%dT%H%M%SZ).dump
if docker exec cryptopilot-staging-db-1 pg_dump -U cryptopilot -d cryptopilot -Fc > "$dump" 2>/dev/null; then
  ls -1t "$BACKUPS"/pre-deploy-*.dump | tail -n +6 | xargs -r rm -f
else
  rm -f "$dump"; log "database backup failed, deployment stopped"; exit 1
fi

[ -n "$be_target" ] && checkout "$BE_DIR" "$be_target"
[ -n "$fe_target" ] && checkout "$FE_DIR" "$fe_target"

if compose up -d --build --remove-orphans >> "$STATE/build.log" 2>&1 && healthy; then
  # A newer TimescaleDB image only ships the library: the extension in the database follows here (a no-op otherwise).
  docker exec cryptopilot-staging-db-1 psql -X -q -U cryptopilot -d cryptopilot -c 'ALTER EXTENSION timescaledb UPDATE' \
    > /dev/null 2>&1 || log "ALTER EXTENSION timescaledb UPDATE failed: run it by hand"
  log "deployed backend $(git -C "$BE_DIR" rev-parse --short HEAD), web $(git -C "$FE_DIR" rev-parse --short HEAD)"
  rm -f "$STATE"/*.failed "$STATE"/*.waiting "$STATE"/*.cifailed
  docker image prune -f > /dev/null
  # A newer copy of this script comes with the backend; systemd runs the installed one.
  [ -w /usr/local/lib/cryptopilot-cd ] && install -m 755 "$BE_DIR/deploy/cd/cd.sh" "$BE_DIR/deploy/backup/backup.sh" \
    /usr/local/lib/cryptopilot-cd/
  exit 0
fi

log "deployment failed (build.log), rolling back to backend ${be_prev:0:8}, web ${fe_prev:0:8}; database dump $dump"
[ -n "$be_target" ] && echo "$be_target" > "$STATE/backend.failed"
[ -n "$fe_target" ] && echo "$fe_target" > "$STATE/web.failed"
checkout "$BE_DIR" "$be_prev"
checkout "$FE_DIR" "$fe_prev"
if compose up -d --build --remove-orphans >> "$STATE/build.log" 2>&1 && healthy; then
  log "rolled back"
else
  log "rollback did not come up healthy either: look at the host"
fi
exit 1

#!/usr/bin/env bash
# T-096: install the staging deployment and the daily backup on the host (as the ubuntu user with sudo; running it
# again updates both). The two repositories must already be cloned in $CD_ROOT and deploy/.env.staging filled in
# (deploy/README.md "Staging"). CD_BE_FALLBACK, when set here, goes into /etc/cryptopilot-cd.env.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
sudo install -d -o "$USER" -g "$USER" /usr/local/lib/cryptopilot-cd
install -m 755 "$here/cd.sh" "$here/../backup/backup.sh" /usr/local/lib/cryptopilot-cd/
[ -f /etc/cryptopilot-cd.env ] || printf '%s\n' "CD_ROOT=$HOME/cryptopilot" 'CD_BE_BRANCH=develop' \
  "CD_BE_FALLBACK=${CD_BE_FALLBACK:-}" 'CD_FE_BRANCH=develop' | sudo tee /etc/cryptopilot-cd.env > /dev/null
sudo install -m 644 "$here/cryptopilot-cd.service" "$here/cryptopilot-cd.timer" \
  "$here/../backup/cryptopilot-backup.service" "$here/../backup/cryptopilot-backup.timer" /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cryptopilot-cd.timer cryptopilot-backup.timer
systemctl list-timers 'cryptopilot-*' --no-pager

#!/usr/bin/env bash
# T-096: install the staging deployment on the host (once, as the ubuntu user with sudo). The two repositories must
# already be cloned in $CD_ROOT and deploy/.env.staging filled in (deploy/README.md "Staging").
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
sudo install -d -o "$USER" -g "$USER" /usr/local/lib/cryptopilot-cd
install -m 755 "$here/cd.sh" /usr/local/lib/cryptopilot-cd/cd.sh
[ -f /etc/cryptopilot-cd.env ] || printf '%s\n' "CD_ROOT=$HOME/cryptopilot" 'CD_BE_BRANCH=develop' \
  'CD_BE_FALLBACK=' 'CD_FE_BRANCH=develop' | sudo tee /etc/cryptopilot-cd.env > /dev/null
sudo install -m 644 "$here/cryptopilot-cd.service" "$here/cryptopilot-cd.timer" /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cryptopilot-cd.timer
systemctl list-timers cryptopilot-cd.timer --no-pager

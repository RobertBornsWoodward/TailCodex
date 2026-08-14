#!/usr/bin/env bash
set -euo pipefail

config_home=${XDG_CONFIG_HOME:-"$HOME/.config"}
unit_file=$config_home/systemd/user/tailcodex-app-server.service

systemctl --user disable --now tailcodex-app-server.service 2>/dev/null || true
if [[ -f "$unit_file" ]]; then
    rm -- "$unit_file"
fi
systemctl --user daemon-reload

echo "Service removed. The capability token remains in $config_home/tailcodex/."
echo "Tailscale Serve configuration was left unchanged; inspect it with: tailscale serve status"

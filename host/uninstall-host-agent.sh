#!/usr/bin/env bash
set -euo pipefail

config_home=${XDG_CONFIG_HOME:-"$HOME/.config"}
unit_file=$config_home/systemd/user/tailcodex-host-agent.service
binary_file=${XDG_BIN_HOME:-"$HOME/.local/bin"}/tailcodex-host-agent

systemctl --user disable --now tailcodex-host-agent.service 2>/dev/null || true
if [[ -f "$unit_file" ]]; then
    rm -- "$unit_file"
fi
if [[ -f "$binary_file" ]]; then
    rm -- "$binary_file"
fi
systemctl --user daemon-reload

echo "Host Agent service and binary removed."
echo "Device credentials, operations and audit history remain in $config_home/tailcodex-host-agent/."
echo "Tailscale Serve configuration was left unchanged; inspect it with: tailscale serve status"

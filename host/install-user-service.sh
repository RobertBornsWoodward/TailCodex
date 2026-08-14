#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
config_dir=${XDG_CONFIG_HOME:-"$HOME/.config"}/tailcodex
unit_dir=${XDG_CONFIG_HOME:-"$HOME/.config"}/systemd/user
token_file=$config_dir/app-server-token
unit_file=$unit_dir/tailcodex-app-server.service

command -v codex >/dev/null || { echo "codex is required" >&2; exit 1; }
command -v tailscale >/dev/null || { echo "tailscale is required" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

install -d -m 700 "$config_dir"
install -d -m 700 "$unit_dir"

if [[ ! -s "$token_file" ]]; then
    umask 077
    openssl rand -hex -out "$token_file" 32
fi
chmod 600 "$token_file"
install -m 644 "$script_dir/tailcodex-app-server.service" "$unit_file"

systemctl --user daemon-reload
systemctl --user enable --now tailcodex-app-server.service

for _ in {1..30}; do
    if curl -fsS http://127.0.0.1:4500/readyz >/dev/null; then
        break
    fi
    sleep 0.2
done
curl -fsS http://127.0.0.1:4500/readyz >/dev/null || {
    systemctl --user --no-pager --full status tailcodex-app-server.service >&2
    exit 1
}

tailscale serve --bg --https=8443 --yes http://127.0.0.1:4500

dns_name=$(tailscale status --json | jq -r '.Self.DNSName // empty' | sed 's/\.$//')
if [[ -n "$dns_name" ]]; then
    echo "TailCodex endpoint: wss://$dns_name:8443"
else
    echo "TailCodex endpoint: use this host's Tailnet HTTPS name on port 8443"
fi
echo "Capability token: $token_file"
echo "The token was not printed. Transfer it only to your Android device."

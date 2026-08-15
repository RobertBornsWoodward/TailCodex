#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$script_dir/.." && pwd)
config_home=${XDG_CONFIG_HOME:-"$HOME/.config"}
state_dir=$config_home/tailcodex-host-agent
unit_dir=$config_home/systemd/user
binary_dir=${XDG_BIN_HOME:-"$HOME/.local/bin"}
binary_file=$binary_dir/tailcodex-host-agent
unit_file=$unit_dir/tailcodex-host-agent.service
configure_serve=false

if [[ ${1:-} == "--configure-serve" ]]; then
    configure_serve=true
elif [[ $# -ne 0 ]]; then
    echo "Usage: $0 [--configure-serve]" >&2
    exit 2
fi

go_binary=${GO_BINARY:-go}
command -v "$go_binary" >/dev/null || {
    echo "Go 1.26 or newer is required (or set GO_BINARY to its absolute path)." >&2
    exit 1
}
command -v systemctl >/dev/null || { echo "systemctl is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
if [[ $configure_serve == true ]]; then
    command -v tailscale >/dev/null || { echo "tailscale is required with --configure-serve" >&2; exit 1; }
    command -v jq >/dev/null || { echo "jq is required with --configure-serve" >&2; exit 1; }
fi

install -d -m 700 "$state_dir"
install -d -m 700 "$unit_dir"
install -d -m 755 "$binary_dir"

build_dir=$(mktemp -d)
cleanup() {
    rm -rf -- "$build_dir"
}
trap cleanup EXIT

(cd -- "$repo_dir/host-agent" && "$go_binary" build -trimpath -o "$build_dir/tailcodex-host-agent" ./cmd/tailcodex-host-agent)
install -m 755 "$build_dir/tailcodex-host-agent" "$binary_file"
install -m 644 "$script_dir/tailcodex-host-agent.service" "$unit_file"

systemctl --user daemon-reload
systemctl --user enable tailcodex-host-agent.service
systemctl --user restart tailcodex-host-agent.service

for _ in {1..30}; do
    if curl -fsS http://127.0.0.1:4510/v1/hello >/dev/null 2>&1; then
        break
    fi
    sleep 0.2
done
curl -fsS http://127.0.0.1:4510/v1/hello >/dev/null || {
    systemctl --user --no-pager --full status tailcodex-host-agent.service >&2
    exit 1
}

if [[ $configure_serve == true ]]; then
    tailscale serve --bg --https=8444 --yes http://127.0.0.1:4510
fi

echo "Host Agent installed at $binary_file"
echo "Local endpoint: http://127.0.0.1:4510"
if [[ $configure_serve == true ]]; then
    echo "Tailnet endpoint: https://$(tailscale status --json | jq -r '.Self.DNSName' | sed 's/\.$//'):8444"
else
    echo "Tailscale Serve was not changed. Add --configure-serve after reviewing the route."
fi
echo "Create a one-time phone pairing code with: tailcodex-host-agent pair"

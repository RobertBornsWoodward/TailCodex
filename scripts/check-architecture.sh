#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$repo_dir"

fail_if_match() {
    local pattern=$1
    shift
    if rg -n -- "$pattern" "$@"; then
        echo "Architecture boundary violation: $pattern" >&2
        exit 1
    fi
}

fail_if_match 'hostcontrol|HostAgent' \
    app/src/main/java/com/woodward/tailcodex/session \
    app/src/main/java/com/woodward/tailcodex/protocol \
    app/src/main/java/com/woodward/tailcodex/rpc

fail_if_match 'okhttp3|com\.woodward\.tailcodex\.rpc|JsonRpcSession|RpcFailure|systemctl|app-server daemon|PTY|CDP' \
    app/src/main/java/com/woodward/tailcodex/presentation/TailCodexAppCoordinator.kt

fail_if_match 'Handle(Func)?\([^\n]*POST /exec|HandleFunc\([^\n]*POST /exec' host-agent

echo "Architecture boundaries verified"

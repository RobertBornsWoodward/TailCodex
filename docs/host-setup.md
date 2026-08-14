# Host setup

TailCodex uses a local Codex app-server and places it behind Tailscale Serve:

```text
Android -- WSS/Tailnet --> Tailscale Serve :8443
                              |
                              +--> ws://127.0.0.1:4500
                                      |
                                      +--> codex app-server
```

This keeps the unauthenticated HTTP listener on loopback, adds Tailnet TLS, and
still requires an independent high-entropy app-server capability token.

## Install

Review the scripts, then run:

```bash
./host/install-user-service.sh
```

The first `tailscale serve` invocation may print a Tailscale authorization URL
and wait. Open that URL as a Tailnet administrator, enable Serve, then rerun the
installer if the original command was interrupted.

The installer:

1. creates `~/.config/tailcodex/app-server-token` with mode `0600`;
2. installs and starts a systemd user service;
3. verifies `http://127.0.0.1:4500/readyz`;
4. exposes it as Tailnet-only HTTPS on port `8443`.

It does not print the token. Read it locally only when entering it into the
Android application:

```bash
cat ~/.config/tailcodex/app-server-token
```

If Codex needs the host's local HTTP proxy, create this untracked file before
starting the service:

```bash
mkdir -p ~/.config/tailcodex
printf '%s\n' \
  'HTTP_PROXY=http://127.0.0.1:2080' \
  'HTTPS_PROXY=http://127.0.0.1:2080' \
  > ~/.config/tailcodex/environment
chmod 600 ~/.config/tailcodex/environment
```

Do not add an API key to that file. The service uses the existing Codex login
stored on the host.

## Verify

```bash
systemctl --user status tailcodex-app-server.service
curl http://127.0.0.1:4500/readyz
tailscale serve status
```

On the phone, use the endpoint printed by the installer, for example:

```text
wss://arch.example-tailnet.ts.net:8443
```

The phone must be a distinct, online Tailnet node. When using FiClash's native
Tailscale outbound, route both `100.64.0.0/10` and the Tailnet DNS name through
that outbound.

## Concurrency

The desktop app currently starts its own app-server child process. Both clients
can see persisted threads, but do not submit turns to the same thread from the
desktop and TailCodex simultaneously. Return to the thread list or reopen the
thread on desktop after a mobile turn completes.

## Remove

```bash
./host/uninstall-user-service.sh
```

The removal script deliberately retains the token and does not reset unrelated
Tailscale Serve routes. Delete the retained token manually only after confirming
it is no longer needed.

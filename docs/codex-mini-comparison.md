# TailCodex and GPT Mini technical-route comparison

Review target: [`CoimgRain/Codex-Mini`](https://github.com/CoimgRain/Codex-Mini) at
commit [`018e4f2`](https://github.com/CoimgRain/Codex-Mini/commit/018e4f2ada4463f3142c4570c0c62bdf20091c5a)
(2026-08-14). Its README notes that packaged releases may lead the public source, so this is a
comparison against the inspectable repository rather than a claim about every closed release
feature.

## Decision

TailCodex should borrow GPT Mini's workstation-control product shape, but not its Codex transport
authority. GPT Mini is a desktop-GUI bridge with a browser/WebView client; TailCodex is a native
Android app-server client plus a separate Linux Host Control Plane.

| Concern | GPT Mini inspected route | TailCodex route | Decision |
|---|---|---|---|
| Codex send path | Focus desktop, copy to clipboard, paste, press Enter | Typed `turn/start` / `turn/steer` app-server RPC | Keep TailCodex; GUI input is too stateful to be authoritative |
| Thread/history truth | Reads Codex session JSONL and desktop logs, combines local caches | `thread/list` and `thread/read(includeTurns=true)` | Keep app-server truth; no internal-file inference |
| Desktop control | Deep links, AppleScript/System Events, optional CDP/full desktop coupling | `DesktopAppAdapter`, deep-link verification, optional read-only CDP | Borrow adapter UX, isolate it from Codex RPC |
| Host lifecycle | Swift manager + launchd around a Node bridge | Go Host Agent + systemd user adapter; native daemon experimental | Borrow one-click service control, retain Linux-native ownership model |
| Mobile UI | Responsive PWA plus small Android WebView wrapper | Kotlin + Jetpack Compose core UI | Keep native chat/approvals/status; consider one shared web workspace only for later Git/Files/metrics |
| Authentication | One shared token accepted in header, query or cookie | Per-device credential in Bearer header; hashes on host; revoke/rotate/grants | Keep TailCodex; never place credentials in URLs |
| Remote network | LAN URLs and an optional public relay/product service | loopback listeners exposed only through Tailscale Serve | Keep tailnet-only scope |
| Long operations | Primarily synchronous request/response and inferred GUI completion | durable operation ID, idempotency and authoritative GET | Keep TailCodex for reconnect recovery |
| Terminal | Not the inspected core route | dedicated tmux lifetime plus replaceable PTY attachments in I3 | Implement independently; do not emulate terminal through desktop input |
| License | source-available, non-commercial restriction | TailCodex MIT | Learn from behavior and architecture only; do not copy source |

## What to adopt

- A visible host dashboard with start/restart, health, version, logs summary and a clear entry URL.
- Mobile-first attachment previews, thread switching and long-running task status.
- Desktop deep links as the first launch/focus attempt, followed by verification and a remote-desktop
  fallback.
- Explicit graphical-session diagnostics: missing display session, desktop process, accessibility
  permission or focus failure should be separate results.
- A shared web workspace is reasonable for secondary Git, Files, logs and metrics surfaces if it
  prevents duplicate Compose and web implementations.

## What not to adopt

- Clipboard paste, keyboard Enter, GUI click or CDP `sendMessage` as the primary Codex send path.
- Session files, desktop logs, PID existence or a visible window as proof of app-server/model truth.
- A bearer token in a query string or a single non-revocable token shared by every phone.
- A public relay, cloud account service or dynamic plugin loader in the first TailCodex releases.
- Treating successful deep-link process exit as proof that the requested thread is visible.

The resulting boundary is deliberate: GPT Mini optimizes for quickly exposing the desktop product;
TailCodex optimizes for typed protocol correctness, reconnection, per-device security and a Linux
workstation that can later expose terminal and adapters without weakening Codex session authority.

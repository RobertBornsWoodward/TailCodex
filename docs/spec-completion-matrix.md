# Host Control Plane specification completion matrix

Audit basis: the 2026-08-15 Host Control Plane specification supplied with this worktree. Statuses
are deliberately narrower than feature intent:

- **Verified**: current code plus an automated or live check proves the requirement.
- **Implemented, device gate**: code exists, but required physical-device evidence is unavailable.
- **Reserved**: the required boundary/contract exists for a later staged release; the feature is
  not advertised or claimed usable.
- **Pending**: implementation required by its future milestone has not started.

## Architecture and authority

| Requirement | Status | Evidence |
|---|---|---|
| App coordinator composes four independent planes | Verified | `TailCodexAppCoordinator`, `HostControlCoordinator`, `TerminalCoordinator`, `WorkstationCoordinator`; source-boundary scan |
| Session/Codex RPC classes contain no Host Agent logic | Verified | source-boundary scan; Host HTTP terminates in `hostcontrol/transport` |
| app-server remains sole Codex thread/turn/approval authority | Verified | Codex protocol/reconnect/server-request tests; no GUI/CDP send path |
| No cross-domain inferred authority | Verified | composed-state test prevents Host local-ready from becoming Codex ready and requires WSS/init/thread; direct fallback remains explicit |

## Host Agent 0.3.0

| Requirement | Status | Evidence |
|---|---|---|
| Go package layout and loopback-only listener | Verified | package tree; root/non-loopback refusal tests; deployed listener `127.0.0.1:4510` |
| Separate Host/Codex ports, credentials and lifecycle | Verified | API boundaries, separate Android Keystore fields, deployed 4510 and existing 4500 services |
| systemd default; native daemon experimental | Verified | lifecycle adapters and flag; I0 PoC shows native control socket absent on this host |
| ownership/state model including external and conflict | Verified | lifecycle matrix tests plus real managed, foreign-listener conflict and manual external-process checks |
| start/stop/restart only managed instances | Verified | unit and real-process tests reject external mutation/conflict and preserve foreign processes; real managed stop/start E2E |
| v1 hello/health/capabilities/services/actions/operations/events | Verified | API tests, authenticated WebSocket test and deployed hello/capabilities checks |
| typed mutation, no arbitrary `/exec` | Verified | strict JSON decoding, safe request/idempotency formats, structured argv injection test |
| durable operation GET is authoritative | Verified | persistence/restart/idempotency/API recovery tests |
| per-device pairing, hash-only storage, list/revoke/rotate | Verified | registry tests and deployed pair/list/revoke checks |
| features and grants are distinct | Verified | capability API and lifecycle/log grant gates |
| risk levels; privileged unsupported | Verified | capability response and compile-time integration descriptors |
| configured roots block traversal/symlink escape | Verified | canonical root-guard tests |
| metadata-only audit and redaction | Verified | redaction/bounded-summary tests and live `host.logs` response |
| Tailscale Serve 8444 | Verified | route targets loopback 4510; Tailnet HTTPS hello and remote pair/auth/revoke returned 200/401 as expected |

## Android 0.3.1

| Requirement | Status | Evidence |
|---|---|---|
| Host page, pairing, encrypted per-host credential | Implemented, device gate | Compose UI, Keystore store, profile scoping; no physical-device pairing run |
| Host control build feature flag and 0.2 direct-WSS fallback | Verified | both enabled and `-Ptailcodex.hostControlEnabled=false` builds; composed fallback test |
| one-click ensure → WSS → initialize → read/start | Verified host-side, device gate | real Host stop/ensure/ready plus read-only WSS and ephemeral thread/real-turn tests; full phone chain remains below |
| Host/local Codex/RPC/model states remain independent | Verified | composed-state and typed failure-classification tests |
| required layered failure classes | Verified | Host unavailable/auth, stopped/start/conflict, transport, RPC timeout, initialization and model failure types surfaced in UI |
| process recreation resumes lifecycle operation safely | Verified | pre-POST and known-operation checkpoint recovery tests |
| metadata log summary | Verified in components | granted transport/coordinator/UI plus real deployed endpoint; phone rendering not yet observed |
| multi-host isolation | Partially verified | Codex host identity tests and per-profile credential/checkpoint keys; physical host switching pending |
| foreground/background, IME and notification behavior | Implemented, device gate | lifecycle-safe scopes, draft/config persistence, `imePadding`, notification code; requires physical Android validation |
| large font and landscape | Verified visually in JVM | Paparazzi golden suite |

## Staged extension work

| Milestone | Current status | What exists now |
|---|---|---|
| I0 PoCs | Host PoCs verified; terminal device comparison pending | systemd/native and graphical-session evidence; renderer-neutral terminal boundary |
| I3 PTY/tmux | Reserved | Host terminal session identity and Android coordinator boundary only; no PTY/WebSocket/session APIs or foreground service |
| I4 Desktop | Reserved | `DesktopAppAdapter`, reserved typed endpoints, remote-engine isolation; no Codex/VS Code/Mathematica adapters or verified deep-link/CDP control |
| I5 integrations/workspace | Reserved | compile-time typed declarations, root guard and Android catalog/workspace boundaries; no executable Git/Files/System/Mathematica adapters |
| I6 Gateway | Intentionally deferred | no gateway/proxy; introduce only when the specification's multi-client triggers occur |

## Required E2E matrix

The following cannot be marked complete without an attached, authorized physical Android device;
the 8444 Tailnet route is now verified:

1. stopped app-server → **phone** new session → automatic start → readyz → WSS → initialize →
   thread → real turn → result (the same host-side lifecycle/protocol chain is verified, but not the
   phone tap/UI/network path);
2. Wi-Fi/mobile switch and screen-off recovery;
3. revoked phone credential and multi-host switching;
4. Chinese IME/touch/layout/notification validation;
5. I3 terminal reattach (feature not implemented yet);
6. I4 remote-desktop-unavailable isolation (only adapter-level isolation is currently tested).

Host-side automated and real-process tests cover external Codex, non-Codex port conflict,
managed stop/start, WSS initialize, an ephemeral real turn, Host-down/direct-WSS composition,
model-versus-host failure separation, credentials, durable recovery, security and redaction.
These checks are not substitutes for the phone E2E cases above.

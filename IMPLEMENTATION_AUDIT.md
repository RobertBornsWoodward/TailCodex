# TailCodex 0.3.1 implementation audit

## Host Control Plane

- A standalone Go Host Agent owns pairing, device credentials, capabilities, durable operations,
  event notifications, Codex lifecycle adapters and metadata-only audit records. It is not a
  Codex JSON-RPC proxy.
- The process refuses root and non-loopback listen addresses. The installed systemd user service
  listens on `127.0.0.1:4510`, uses `NoNewPrivileges`, read-only home/system protection and a
  single private writable state directory.
- Pairing codes are short-lived and one-use. Device credentials are high entropy, stored only as
  hashes on the host and support list, revoke and rotation. Features and per-device grants are
  reported separately.
- Lifecycle detection distinguishes `MANAGED_SYSTEMD`, `MANAGED_NATIVE`, `EXTERNAL`, `UNKNOWN`
  and `CONFLICT`. A healthy external server is accepted without taking ownership; a non-Codex
  listener on 4500 blocks start. Native daemon mutation remains opt-in and experimental.
- Mutation APIs accept typed JSON only and require both a request ID and idempotency key. Durable
  operation GET is authoritative after reconnect; the authenticated WebSocket stream is advisory.
- Managed stop is available as a typed action and remains ownership-gated. A separately granted
  `host.logs` read returns only a bounded, redacted audit summary.
- Compile-time desktop, terminal and integration boundaries are present. File-root canonicalization
  already rejects directory traversal and symlink escape, but the I3-I5 feature surfaces are not
  advertised or enabled yet.

## Android Host orchestration

- `TailCodexAppCoordinator` composes, but does not implement, the Codex, Host, terminal and
  workstation planes. Existing `SessionCoordinator`, `JsonRpcSession` and `CodexApi` contain no
  Host Agent calls.
- The independent Host Agent credential is encrypted with Android Keystore and scoped, with its
  HTTPS endpoint, to each host profile. Existing 0.2 profiles default to an empty Host Agent
  endpoint and retain direct-WSS fallback.
- One-click entry performs pairing when needed, capability/service discovery, idempotent
  `ensure-running`, authoritative operation polling, Codex WSS initialization, then
  `thread/read(last)` or `thread/start`.
- Android commits a per-profile operation checkpoint before POST. Process recreation either
  resumes the known operation ID or repeats the original idempotency key without duplicating work.
- Composed state keeps host reachability, Codex local readiness, Codex RPC readiness and model
  request failure independent. `LOCAL_READY` never becomes `CODEX_READY` until WSS initialization
  and thread read/start succeed.
- If the Host Agent itself is unreachable, TailCodex may still attempt the separately authenticated
  direct WSS path to an already-running app-server. Lifecycle conflicts, authentication failures
  and start failures do not silently fall through as host success.
- Compose exposes Host Agent setup/pairing, separate status, ownership, current operation,
  metadata audit summary and an explicitly gated restart action. External Codex ownership never
  enables restart. The whole Host Control surface has a Gradle build feature flag.

## P0 correctness

- Explicit connection, thread, turn and soft-lease states: implemented in `domain` and reduced by
  `SessionReducer`.
- Initialization gate: `CodexApi` cannot issue ordinary RPC until initialize/initialized completes.
  Reconciliation may read through a separate protocol-ready gate, while writes require fully
  `Ready`.
- Reconnect: open thread snapshots become stale, reconnect initializes a new generation, then
  `thread/read(includeTurns=true)` replaces local state before writes re-enable.
- Historical read versus writer: list selection invokes `thread/read`; first write re-reads and
  only then invokes `thread/resume`. An observed active server thread is kept read-only.
- RPC: global timeout, single completion, disconnect cancellation (including retry wait), explicit
  local cancellation, unknown/duplicate response diagnostics, and bounded jittered `-32001`
  retries.
- Server requests: approvals, requestUserInput and MCP elicitation are typed and validated.
  Dynamic tool calls and unknown requests receive explicit `-32601`. Stale-generation responses
  are disabled in UI and rejected by the manager. A new connection discards old-generation
  requests before reconciliation and waits for the server to resend them.

## P1 mobile UX and boundaries

- Structured message, command, file-change, MCP, review and status cards; tool details are
  collapsed by default.
- Headings, lists, quotes, tables, images with fallback text, clickable links, fenced code,
  inline/display math and unified diffs have dedicated mobile render paths.
- Native LaTeX uses RaTeX Android. Formula, code and diff surfaces have horizontal scrolling,
  copy/selection and full-screen inspection.
- Approval, user-input and MCP requests are blocking first-class dialogs. Composer state survives
  configuration/process recreation through per-host/per-thread SharedPreferences drafts.
- Connection banners distinguish connecting, initializing, reconciling and stale snapshots.
- UI and presentation packages contain no OkHttp, WebSocket, raw JSON or wire DTO dependency. The
  object graph is isolated in `infrastructure`.
- Versioned Codex 0.147.0 JSON Schema is checked in; required wire DTOs live in the generated
  package and are converted to domain models at the protocol boundary.

## P1-C, P2 and P3

- Thread pagination, local per-host pinning, archive, fork and all four review targets are present.
- Gallery/screenshot selection and camera capture become schema-compliant image data URLs in
  presentation code before entering domain/repository APIs; selected text/code files become
  bounded fenced attachments and binary files are rejected.
- High-priority notifications cover approvals, user input, MCP, completed turns and failed turns;
  tapping returns to the active request screen, while approval notifications add nonce-protected
  one-time allow/reject actions that revalidate host, request, Ready state and soft lease.
- Multi-host profiles contain endpoint, Keystore-encrypted credential, cwd, last connection state
  and last thread. Host identity scopes thread models, drafts, notifications, reconnect generations, server requests
  and leases. A single coordinator remains active.
- Gateway is deliberately not implemented until concurrent clients require an authoritative
  distributed lease and event replay.
- `NoVncEngine` and `MoonlightEngine` are independent remote-workstation adapters. Host service
  installation and Android launching remain explicit deployment steps; neither adapter enters the
  Codex RPC/session stack.

## Verification evidence

Executed with Temurin JDK 21:

```text
./gradlew testDebugUnitTest lintDebug assembleDebug verifyPaparazziDebug
BUILD SUCCESSFUL
56 unit/screenshot tests, 0 failures, 0 errors
lint: 0 errors
APK: app/build/outputs/apk/debug/app-debug.apk
SHA-256: 5516eecbed251c8f1e23354d6dae138bde4b7d44bc26c871195a1ad43d2495b8
Paparazzi: 11 checked-in golden images verified
```

Executed with Go 1.26.5:

```text
go vet ./...
go test ./...
go test -race ./...
34 Go tests, 0 failures; 2 opt-in live tests skipped by default; race detector clean
```

Opt-in live tests connected to the real loopback Codex app-server. The read-only test completed
`initialize`/`initialized` and `thread/list`. A separately guarded mutating test created an
ephemeral thread, sent a real no-tool turn, received `turn/completed` and verified the expected
agent message.

The current Host Agent 0.1.1 user-service binary was installed and verified active. `/v1/hello` returned protocol v1;
pair/auth/services/revoke was exercised against the deployed process; the revoked credential then
returned HTTP 401. A newly paired device received distinct `codex.lifecycle` and `host.logs`
features/grants, and the deployed log-summary endpoint returned redacted metadata only. A real
`ensure-running` reported `MANAGED_SYSTEMD / LOCAL_READY`, completed as
`SUCCEEDED`, and left the existing app-server main PID unchanged.

A real lifecycle E2E then used Host Agent operations to stop the managed app-server, observed the
ready port close, ran `ensure-running`, and recovered `MANAGED_SYSTEMD / LOCAL_READY` with HTTP
200. Separate real-process checks proved a foreign listener remains alive while ensure fails with
`CODEX_PORT_CONFLICT`, and a manually started healthy app-server is `EXTERNAL`: ensure succeeds
without starting systemd while stop/restart both fail with `EXTERNAL_CODEX_PROCESS`. The managed
service was restored active and ready after each check.

Both Tailscale Serve routes are now present and were inspected: HTTPS 8443 targets the Codex
app-server on loopback 4500, and HTTPS 8444 targets Host Agent on loopback 4510. Direct Tailnet TLS
checks (bypassing only the host's unrelated HTTP proxy) returned protocol v1/Agent 0.1.1; remote
pair/auth succeeded with HTTP 200 and the same credential returned 401 immediately after revoke.

Android tests cover Host Agent response parsing, Bearer-header isolation, authoritative operation
recovery across process recreation (including the pre-response idempotency window), independent
Host reconnect, feature/grant gating, typed failure classification, log summaries, external-process
acceptance and managed start polling, plus connect/initialize/reconnect
generations, pre-initialize request and notification rejection, reconciliation, timeout, bounded
overload retry, disconnect/user retry cancellation, duplicate/unknown response, read/resume/interrupt,
soft lease and host isolation, approvals, user input, MCP, unknown requests, image wire shape,
Markdown/math parsing, stale WebSocket replacement and remote-engine isolation.

Paparazzi golden tests cover a 360dp-class small portrait, a large portrait at 1.5x font scaling,
and dark landscape. They exercise long Markdown, formula containers, long code, collapsed command
output, multi-file unified diff, approval, running composer and Host Control/log-summary layouts.
Visual inspection caught and fixed the small-screen composer compression defect; both Host Control
goldens were inspected and fit without clipping.

No authorized Android device or emulator is attached to this workstation, so the full stopped-server → phone
tap → real WSS/thread/turn E2E and final tactile/visual QA of real IME insets, RaTeX native drawing,
camera/document pickers and notification delivery/navigation still requires installing the built
APK on a phone. This is a validation boundary, not claimed as device-tested.

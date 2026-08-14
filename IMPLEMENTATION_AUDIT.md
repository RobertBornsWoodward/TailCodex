# TailCodex 0.2 implementation audit

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
  and last thread. Host
  identity scopes thread models, drafts, notifications, reconnect generations, server requests
  and leases. A single coordinator remains active.
- Gateway is deliberately not implemented until concurrent clients require an authoritative
  distributed lease and event replay.
- `NoVncEngine` and `MoonlightEngine` are independent remote-workstation adapters. Host service
  installation and Android launching remain explicit deployment steps; neither adapter enters the
  Codex RPC/session stack.

## Verification evidence

Executed with Temurin JDK 21:

```text
./gradlew test lint assembleDebug verifyPaparazziDebug
BUILD SUCCESSFUL
36 unit/screenshot tests, 0 failures, 0 errors
lint: 0 errors (two pre-existing toolchain/target-version update notices)
APK: app/build/outputs/apk/debug/app-debug.apk
Paparazzi: 9 checked-in golden images verified
```

Tests cover connect/initialize/reconnect generations, pre-initialize request and notification
rejection, reconciliation, timeout, bounded overload retry, disconnect/user retry cancellation,
duplicate/unknown response, read/resume/interrupt,
soft lease and host isolation, approvals, user input, MCP, unknown requests, image wire shape,
Markdown/math parsing, stale WebSocket replacement and remote-engine isolation.

Paparazzi golden tests cover a 360dp-class small portrait, a large portrait at 1.5x font scaling,
and dark landscape. They exercise long Markdown, formula containers, long code, collapsed command
output, multi-file unified diff, approval and running composer layouts. Visual inspection caught
and fixed the small-screen composer compression defect.

No Android device or emulator is installed on this workstation, so final tactile/visual QA of
real IME insets, RaTeX native drawing, camera/document pickers and notification delivery/navigation
still requires installing the built APK on a phone. This is a validation boundary, not claimed as
device-tested.

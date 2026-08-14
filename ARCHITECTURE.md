# TailCodex architecture

TailCodex is an Android client for Codex app-server over authenticated WSS on a Tailnet. The
current design keeps server truth above local convenience and defaults historical threads to
read-only.

```text
Compose UI
  -> presentation/TailCodexViewModel
  -> session/SessionCoordinator
       -> ConnectionManager -> rpc/JsonRpcSession -> transport/WebSocketTransport -> OkHttp
       -> ThreadSession -> repository/CodexRepository -> protocol/CodexApi
       -> LeaseManager
       -> ServerRequestManager

Codex JSON Schema snapshot -> protocol/generated wire DTOs
wire DTOs -> protocol/CodexWireProtocol -> domain models -> UI
```

`SessionCoordinator` owns ordering, not network details. `ConnectionManager` enforces
`Connecting -> Initializing -> Ready`; a reconnect with an open thread enters `Reconciling` and
runs `thread/read(includeTurns=true)` before writes become available. `ThreadSession` has
`NoThread / ReadOnly / Resuming / Active` states. `LeaseManager` records a host-scoped soft lease
(`NONE / LOCAL_PHONE / OTHER_CLIENT / UNKNOWN`) and is deliberately not advertised as a
distributed lock.

Server-initiated approvals, user-input questions and MCP elicitations are typed domain requests.
Unsupported or malformed future requests receive an explicit JSON-RPC error. RPC calls have a
global timeout, local cancellation, single completion and bounded jittered retry for `-32001`.
Turn interruption remains a separate `turn/interrupt` operation and passes through the same
ready/reconciled/writable/lease gate as other mutations. A locally-owned turn that survives a
disconnect returns as `UNKNOWN`, then must re-read and resume before the phone reclaims its soft
lease; a freshly observed active historical thread remains `OTHER_CLIENT` and read-only.

## Extension boundaries

- Multi-host profiles store endpoint, encrypted credential, default cwd and last thread. Thread,
  last connection state, draft, notification, reconnect and lease keys include host identity.
  Only one coordinator is active at a time.
- A Gateway is intentionally absent. Introduce it only when multiple phone/tablet/desktop/browser
  clients truly need authoritative leases, replay, device revocation and approval auditing.
- Remote workstation features live behind `RemoteDesktopEngine`. `NoVncEngine` and
  `MoonlightEngine` validate launch targets but never enter `JsonRpcSession`, `CodexApi`, or
  `SessionCoordinator`. Host installation and user-authorized Android app launching are separate
  deployment work.
- Future terminal, file management, system control and Mathematica controls should follow the
  same independent capability-adapter pattern instead of adding methods to the Codex protocol.

## Mobile rendering

Conversation items stay structured: messages, command executions, file changes, MCP calls,
reviews and status events each have their own card. Long tool output is collapsed by default.
Markdown scanning keeps fenced code opaque, recognizes inline/display math, and routes formulas
through `MathRenderer` into the open-source native RaTeX Android Canvas renderer. Code, formulas
and diffs support horizontal reading; source panels support copy and full-screen inspection.
Drafts are persisted per host and thread.

Approval notifications expose one-time allow/reject actions. Notification intents carry a
private nonce and are accepted only while the same host/request is still present, fully Ready,
reconciled and backed by a valid local soft lease. User-input and MCP notifications open the
blocking request surface without attempting an implicit response.

Paparazzi screenshot tests keep golden images for small portrait, large-font portrait and dark
landscape renderings. The test renderer deliberately substitutes text for the native RaTeX view;
real Canvas drawing, touch, notification delivery and IME behavior remain device-test concerns.

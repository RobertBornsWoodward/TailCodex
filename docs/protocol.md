# Protocol subset

TailCodex speaks one JSON-RPC message per WebSocket text frame. The wire omits
the `jsonrpc` member, matching Codex app-server.

Connection lifecycle:

1. HTTP Upgrade with `Authorization: Bearer <capability token>`
2. `initialize` request with client name `tailcodex_android`
3. `initialized` notification
4. normal thread and turn requests

Implemented client requests:

- `thread/list`
- `thread/start`
- `thread/resume`
- `turn/start`
- `turn/steer`
- `turn/interrupt`

Implemented streaming notifications:

- `item/started`
- `item/completed`
- `item/agentMessage/delta`
- `turn/started`
- `turn/completed`
- `error`

Implemented server requests:

- `item/commandExecution/requestApproval`
- `item/fileChange/requestApproval`
- `item/permissions/requestApproval`

Unknown item and notification types are ignored so a newer app-server can add
events without breaking this client. JSON-RPC error `-32001` should be retried
by a future request queue; the current MVP reports it in the UI.

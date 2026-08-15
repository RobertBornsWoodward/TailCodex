# Host Agent protocol v1

The Host Agent is a control plane independent from the Codex app-server data plane. It listens
only on `127.0.0.1:4510`; the supported remote layout exposes that loopback listener through a
separate Tailscale Serve route on HTTPS port `8444`. The Codex app-server remains on ports
`4500/8443` and remains authoritative for threads, turns, approvals, input and diffs.

## Trust and pairing

`GET /v1/hello` and `POST /v1/pair` are the only unauthenticated endpoints. Pairing starts on the
host:

```bash
tailcodex-host-agent pair
```

The short-lived, one-use code authorizes exactly one Android `deviceId`. The API returns a
high-entropy `tcx1_...` credential once. The host stores only credential and pairing-code hashes.
All later HTTP and WebSocket requests use `Authorization: Bearer <device credential>`; query-string
credentials are rejected.

Administrative commands are local-only:

```bash
tailcodex-host-agent devices list
tailcodex-host-agent devices rotate DEVICE_ID
tailcodex-host-agent devices revoke DEVICE_ID
```

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/hello` | protocol and minimum-client negotiation |
| `POST` | `/v1/pair` | exchange a one-time code for a device credential |
| `GET` | `/v1/health` | authenticated Host Agent health |
| `GET` | `/v1/capabilities` | offered features, device grants and risk levels |
| `GET` | `/v1/services` | current Host Agent and Codex lifecycle snapshots |
| `GET` | `/v1/logs/summary` | bounded metadata-only audit summary (`host.logs` grant) |
| `POST` | `/v1/actions/codex.ensure-running` | make managed Codex locally ready |
| `POST` | `/v1/actions/codex.restart` | restart managed Codex explicitly |
| `POST` | `/v1/actions/codex.stop` | stop only a managed Codex instance |
| `GET` | `/v1/operations/{operationId}` | authoritative asynchronous operation state |
| `GET` | `/v1/events` | advisory authenticated WebSocket event stream |

Desktop launch/focus paths are reserved and currently return `FEATURE_UNAVAILABLE`. Terminal paths
are intentionally absent until the terminal capability and its stronger grant are implemented.

Mutation requests require a safe `requestId`, an `Idempotency-Key` header and the relevant device
grant. Operations persist as `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED` or `CANCELLED`. WebSocket
events reduce latency but do not replace `GET /v1/operations/{operationId}` after reconnect.

## Codex lifecycle semantics

Ownership is one of `MANAGED_SYSTEMD`, `MANAGED_NATIVE`, `EXTERNAL`, `UNKNOWN` or `CONFLICT`.
Service state is one of `STOPPED`, `STARTING`, `LOCAL_READY`, `FAILED`, `EXTERNAL` or `CONFLICT`.
Systemd user-service control is the default adapter. Native Codex daemon mutation remains behind
`--enable-native-daemon`.

An external process with a healthy `/readyz` is accepted as ready but cannot be stopped or
restarted. Stop/restart are restricted to the detected lifecycle owner. A listener on port 4500
that does not answer the Codex readiness probe is a conflict;
the Host Agent will neither kill it nor start a second server.

## Security envelope

The service runs without root or `sudo`, accepts only loopback listen addresses, executes typed
argument arrays without a shell, and records metadata-only JSONL audit entries. Current actions
are classified `PROCESS_CONTROL`. `PRIVILEGED` actions are unsupported. Future workspace mutation
and full-terminal features require separate grants and configured workspace roots.

`host.logs` exposes only a bounded recent view of the same redacted audit metadata. It is separate
from `codex.lifecycle`, requires its own device grant and cannot read arbitrary log paths.

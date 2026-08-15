# TailCodex

TailCodex is a small Android client for controlling a Codex session running on
your own computer. It connects only to a Codex `app-server` endpoint that you
provide; the intended deployment places that endpoint behind Tailscale HTTPS.

TailCodex 0.2 can:

- connect with a bearer capability token;
- list, search, paginate, pin, fork, archive, read, and resume threads;
- render structured commands, file changes, MCP calls, review events, Markdown, code, diffs, and LaTeX;
- stream new agent and command output;
- start, steer, and interrupt turns;
- handle command/file/permission approvals, user input, and MCP elicitation;
- survive disconnect/reconnect through server reconciliation and a host-scoped soft lease;
- retain multiple host profiles and encrypt credentials with Android Keystore.

It does **not** automate pixels in the ChatGPT window and it does not contain an
OpenAI API key. Codex authentication remains on the host.

TailCodex 0.3 adds an independent Go Host Agent for pairing, durable/recoverable operations,
metadata-only audit summaries and safe Codex lifecycle control. It deliberately does not tunnel
these controls through Codex JSON-RPC. Hosts without an Agent retain the 0.2 direct-WSS path. See
[Host Agent protocol v1](docs/host-agent-protocol.md).

The I0 systemd/native-daemon, graphical-session and terminal-renderer findings are recorded in
[the PoC report](docs/i0-poc-report.md).
The requirement-by-requirement verified, staged and device-gated status is tracked in the
[specification completion matrix](docs/spec-completion-matrix.md).

The design decision against making desktop automation authoritative is documented in the
[GPT Mini route comparison](docs/codex-mini-comparison.md).

## Requirements

- Android 8.0 (API 26) or newer
- a phone connected to the same Tailnet as the host
- Codex CLI with `app-server` WebSocket support
- a TLS reverse proxy in front of the loopback WebSocket listener
- JDK 21 or newer for the full build and Paparazzi screenshot suite

The tested host in this repository is Arch Linux. See
[Host setup](docs/host-setup.md) for the service and Tailscale Serve layout.

## Build

```bash
export ANDROID_SDK_ROOT="$HOME/.local/share/android-sdk"
./gradlew test lint assembleDebug verifyPaparazziDebug
```

Build a direct-WSS-only variant with Host Control compiled off:

```bash
./gradlew -Ptailcodex.hostControlEnabled=false assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## First connection

1. Complete the host setup and copy the generated token securely.
2. Connect FiClash/Tailscale on the phone.
3. Open TailCodex and enter the `wss://` Tailnet endpoint.
4. Paste the capability token and connect.

Never commit a real token. TailCodex stores it in an AES-GCM key held by Android
Keystore.

## Protocol compatibility

The client uses a versioned Codex app-server JSON Schema snapshot under
`protocol/codex-0.147.0`. Generated wire DTOs and all raw JSON remain behind the
`protocol` package; Compose only receives domain models. See
[`ARCHITECTURE.md`](ARCHITECTURE.md) and [`protocol/README.md`](protocol/README.md).

## License

MIT

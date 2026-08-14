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

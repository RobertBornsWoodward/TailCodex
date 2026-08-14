# TailCodex

TailCodex is a small Android client for controlling a Codex session running on
your own computer. It connects only to a Codex `app-server` endpoint that you
provide; the intended deployment places that endpoint behind Tailscale HTTPS.

The MVP can:

- connect with a bearer capability token;
- list, search, start, and resume threads;
- render persisted user and agent messages;
- stream new agent output;
- start, steer, and interrupt turns;
- approve or decline command and file-change requests;
- retain the endpoint and encrypt the token with Android Keystore.

It does **not** automate pixels in the ChatGPT window and it does not contain an
OpenAI API key. Codex authentication remains on the host.

## Requirements

- Android 8.0 (API 26) or newer
- a phone connected to the same Tailnet as the host
- Codex CLI with `app-server` WebSocket support
- a TLS reverse proxy in front of the loopback WebSocket listener

The tested host in this repository is Arch Linux. See
[Host setup](docs/host-setup.md) for the service and Tailscale Serve layout.

## Build

```bash
export ANDROID_SDK_ROOT="$HOME/.local/share/android-sdk"
./gradlew :app:assembleDebug
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

The client uses the stable subset of the Codex app-server JSON-RPC protocol.
WebSocket transport itself is currently experimental. Protocol parsing is kept
in `data/CodexProtocol.kt` so changes can be adapted without rewriting the UI.

## License

MIT

# Codex protocol snapshot

The checked-in `codex-0.147.0` directory is the app-server JSON Schema snapshot used by this
client. Refresh it explicitly when upgrading the host Codex version:

```bash
codex app-server generate-json-schema --out protocol/codex-<version>
```

Wire-only Kotlin DTOs live in `protocol/generated`. They mirror thread-read, command/file/
permission approval, request-user-input and MCP-elicitation fields in the versioned schemas and
are converted immediately to `domain` objects by `CodexWireProtocol`.
Unknown server requests are not discarded: the client returns JSON-RPC `-32601`.

The snapshot is intentionally versioned. A Codex upgrade must update the directory, DTO
provenance header, protocol tests, and any anti-corruption mapping in one reviewable change.

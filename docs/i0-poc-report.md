# I0 host-control proof-of-concept report

Evidence date: 2026-08-15. These checks are host-specific and do not claim physical Android
validation.

## Lifecycle adapter comparison

- Installed Codex is `codex-cli 0.147.0` and exposes `codex app-server daemon` commands.
- `codex app-server daemon version` currently fails because
  `~/.codex/app-server-control/app-server-control.sock` does not exist. The running TailCodex
  app-server is the independent systemd user service on loopback WebSocket port 4500.
- The systemd adapter can inspect, start, stop and restart the named user unit with structured argv,
  and its real `ensure-running` path has preserved the existing main PID when already healthy.

Decision: systemd remains the stable default. Native daemon mutation stays behind
`--enable-native-daemon` until its control socket, ownership and restart behavior are verified
against an actual native-daemon-managed instance.

## Graphical-session proof of concept

- The user service environment reports `XDG_SESSION_TYPE=wayland`,
  `XDG_CURRENT_DESKTOP=Hyprland`, `WAYLAND_DISPLAY=wayland-1` and `DISPLAY=:1`.
- `hyprctl -j clients` returns real class, title, PID and workspace records.
- `x-scheme-handler/codex` resolves to `chatgpt.desktop`, whose desktop entry accepts `%U` URLs.

This is enough to implement a Hyprland status verifier in I4, but not to claim a deep link opened a
specific Codex thread. The required sequence remains launch/deep-link, then verify the real window
and thread, then optional read-only CDP inspection, then remote-desktop fallback.

## Terminal renderer proof of concept

- Host tmux `3.7b` is installed and suitable for durable session identity.
- The Android app currently contains neither an xterm.js/WebView dependency nor a native terminal
  emulator dependency.
- This workstation has no attached Android device or emulator, so Chinese IME composition, resize,
  Ctrl/Esc/Tab, arrow keys, clipboard and background reattach cannot yet be compared honestly.

Decision: retain the renderer-neutral `TerminalCoordinator` and Host PTY/tmux boundary. Select
native rendering versus xterm.js only after the I3 physical-device spike measures input fidelity,
rendering correctness, accessibility and reconnect behavior.

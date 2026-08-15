// Package terminal reserves the PTY/tmux boundary for I3.
package terminal

// Session is durable tmux identity, not a WebSocket attachment. Terminal data
// is intentionally absent because I1 must not persist terminal contents.
type Session struct {
	ID       string `json:"id"`
	TmuxName string `json:"tmuxName"`
	Title    string `json:"title"`
}

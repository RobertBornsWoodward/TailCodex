// Package desktop defines the non-authoritative desktop-control boundary.
package desktop

import "context"

type AppState string

const (
	Stopped AppState = "STOPPED"
	Running AppState = "RUNNING"
	Focused AppState = "FOCUSED"
	Unknown AppState = "UNKNOWN"
)

type Status struct {
	AppID  string   `json:"appId"`
	State  AppState `json:"state"`
	Detail string   `json:"detail,omitempty"`
}

// DesktopAppAdapter is reserved for I4. A successful process launch is not proof that
// a requested desktop window or Codex thread is visible; adapters must verify it.
type DesktopAppAdapter interface {
	ID() string
	Status(context.Context) (Status, error)
	Launch(context.Context, []string) (Status, error)
	Focus(context.Context) (Status, error)
}

type AppAdapter = DesktopAppAdapter

type CodexDesktopAdapter interface {
	DesktopAppAdapter
	OpenThread(context.Context, string) (Status, error)
}

type VSCodeAdapter interface{ DesktopAppAdapter }
type MathematicaAdapter interface{ DesktopAppAdapter }
type GenericDesktopAdapter interface{ DesktopAppAdapter }

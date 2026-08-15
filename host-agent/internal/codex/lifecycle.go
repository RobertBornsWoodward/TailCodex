package codex

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/services"
)

type Ownership string

const (
	ManagedSystemd Ownership = "MANAGED_SYSTEMD"
	ManagedNative  Ownership = "MANAGED_NATIVE"
	External       Ownership = "EXTERNAL"
	Unknown        Ownership = "UNKNOWN"
	Conflict       Ownership = "CONFLICT"
)

type ServiceState string

const (
	Stopped    ServiceState = "STOPPED"
	Starting   ServiceState = "STARTING"
	LocalReady ServiceState = "LOCAL_READY"
	Failed     ServiceState = "FAILED"
	ExternalOK ServiceState = "EXTERNAL"
	ConflictAt ServiceState = "CONFLICT"
)

type Snapshot struct {
	Ownership Ownership              `json:"ownership"`
	State     ServiceState           `json:"state"`
	PortOpen  bool                   `json:"portOpen"`
	Ready     bool                   `json:"ready"`
	ReadyURL  string                 `json:"readyUrl"`
	Systemd   services.AdapterStatus `json:"systemd"`
	Native    services.AdapterStatus `json:"native"`
	Detail    string                 `json:"detail,omitempty"`
	CheckedAt time.Time              `json:"checkedAt"`
}

type LifecycleError struct {
	Code    string
	Message string
}

func (e *LifecycleError) Error() string { return e.Message }

type Probe interface {
	Check(context.Context) ProbeResult
}

type ProbeResult struct {
	PortOpen bool
	Ready    bool
	Detail   string
}

// CodexLifecycleAdapter is the ownership-aware lifecycle boundary implemented by the stable
// systemd adapter and the experimental native-daemon adapter.
type CodexLifecycleAdapter = services.LifecycleAdapter

type HTTPProbe struct {
	Address  string
	ReadyURL string
	Client   *http.Client
}

func (p HTTPProbe) Check(ctx context.Context) ProbeResult {
	address := p.Address
	if address == "" {
		address = "127.0.0.1:4500"
	}
	dialer := net.Dialer{Timeout: 750 * time.Millisecond}
	conn, err := dialer.DialContext(ctx, "tcp", address)
	if err != nil {
		return ProbeResult{Detail: err.Error()}
	}
	_ = conn.Close()
	readyURL := p.ReadyURL
	if readyURL == "" {
		readyURL = "http://127.0.0.1:4500/readyz"
	}
	client := p.Client
	if client == nil {
		client = &http.Client{Timeout: 1500 * time.Millisecond}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, readyURL, nil)
	if err != nil {
		return ProbeResult{PortOpen: true, Detail: err.Error()}
	}
	response, err := client.Do(req)
	if err != nil {
		return ProbeResult{PortOpen: true, Detail: err.Error()}
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
	return ProbeResult{
		PortOpen: true,
		Ready:    response.StatusCode >= 200 && response.StatusCode < 300,
		Detail:   response.Status,
	}
}

type Manager struct {
	Systemd      CodexLifecycleAdapter
	Native       CodexLifecycleAdapter
	Probe        Probe
	NativeEnable bool
	ReadyURL     string
	WaitTimeout  time.Duration
	PollInterval time.Duration
	Now          func() time.Time
	mutationMu   sync.Mutex
}

func (m *Manager) Detect(ctx context.Context) Snapshot {
	systemd := statusOf(ctx, m.Systemd)
	native := statusOf(ctx, m.Native)
	probe := m.Probe.Check(ctx)
	now := time.Now
	if m.Now != nil {
		now = m.Now
	}
	snapshot := Snapshot{
		PortOpen: probe.PortOpen, Ready: probe.Ready, ReadyURL: m.ReadyURL,
		Systemd: systemd, Native: native, Detail: probe.Detail, CheckedAt: now().UTC(),
	}
	if snapshot.ReadyURL == "" {
		snapshot.ReadyURL = "http://127.0.0.1:4500/readyz"
	}
	if systemd.Active && native.Active {
		snapshot.Ownership, snapshot.State = Conflict, ConflictAt
		snapshot.Detail = "systemd and native daemon both claim the app-server"
		return snapshot
	}
	if probe.Ready {
		switch {
		case systemd.Active:
			snapshot.Ownership, snapshot.State = ManagedSystemd, LocalReady
		case native.Active:
			snapshot.Ownership, snapshot.State = ManagedNative, LocalReady
		default:
			snapshot.Ownership, snapshot.State = External, ExternalOK
		}
		return snapshot
	}
	if probe.PortOpen {
		if systemd.Starting || native.Starting {
			snapshot.State = Starting
			if systemd.Starting {
				snapshot.Ownership = ManagedSystemd
			} else {
				snapshot.Ownership = ManagedNative
			}
			return snapshot
		}
		snapshot.Ownership, snapshot.State = Conflict, ConflictAt
		snapshot.Detail = "port 4500 is occupied but /readyz is not healthy"
		return snapshot
	}
	if systemd.Active || systemd.Starting {
		snapshot.Ownership, snapshot.State = ManagedSystemd, Starting
		return snapshot
	}
	if native.Active || native.Starting {
		snapshot.Ownership, snapshot.State = ManagedNative, Starting
		return snapshot
	}
	if systemd.Installed {
		snapshot.Ownership, snapshot.State = ManagedSystemd, Stopped
		return snapshot
	}
	if native.Installed && m.NativeEnable {
		snapshot.Ownership, snapshot.State = ManagedNative, Stopped
		return snapshot
	}
	snapshot.Ownership, snapshot.State = Unknown, Stopped
	return snapshot
}

func (m *Manager) EnsureRunning(ctx context.Context) (Snapshot, error) {
	m.mutationMu.Lock()
	defer m.mutationMu.Unlock()
	if err := ctx.Err(); err != nil {
		return Snapshot{}, &LifecycleError{Code: "CODEX_LIFECYCLE_CANCELLED", Message: err.Error()}
	}
	current := m.Detect(ctx)
	switch current.State {
	case LocalReady, ExternalOK:
		return current, nil
	case ConflictAt:
		return current, &LifecycleError{Code: "CODEX_PORT_CONFLICT", Message: current.Detail}
	case Starting:
		return m.waitReady(ctx)
	case Stopped, Failed:
		var adapter services.LifecycleAdapter
		switch current.Ownership {
		case ManagedSystemd:
			adapter = m.Systemd
		case ManagedNative:
			if !m.NativeEnable {
				return current, &LifecycleError{Code: "NATIVE_DAEMON_DISABLED", Message: "native Codex daemon control is disabled"}
			}
			adapter = m.Native
		default:
			return current, &LifecycleError{Code: "NO_LIFECYCLE_ADAPTER", Message: "no managed Codex lifecycle adapter is available"}
		}
		if adapter == nil {
			return current, &LifecycleError{Code: "NO_LIFECYCLE_ADAPTER", Message: "selected lifecycle adapter is unavailable"}
		}
		if err := adapter.Start(ctx); err != nil {
			return current, &LifecycleError{Code: "CODEX_START_FAILED", Message: err.Error()}
		}
		return m.waitReady(ctx)
	default:
		return current, &LifecycleError{Code: "CODEX_START_FAILED", Message: "unsupported lifecycle state"}
	}
}

func (m *Manager) Restart(ctx context.Context) (Snapshot, error) {
	m.mutationMu.Lock()
	defer m.mutationMu.Unlock()
	if err := ctx.Err(); err != nil {
		return Snapshot{}, &LifecycleError{Code: "CODEX_LIFECYCLE_CANCELLED", Message: err.Error()}
	}
	current := m.Detect(ctx)
	if current.State == ConflictAt {
		return current, &LifecycleError{Code: "CODEX_PORT_CONFLICT", Message: current.Detail}
	}
	var adapter services.LifecycleAdapter
	switch current.Ownership {
	case ManagedSystemd:
		adapter = m.Systemd
	case ManagedNative:
		if !m.NativeEnable {
			return current, &LifecycleError{Code: "NATIVE_DAEMON_DISABLED", Message: "native Codex daemon control is disabled"}
		}
		adapter = m.Native
	case External:
		return current, &LifecycleError{Code: "EXTERNAL_CODEX_PROCESS", Message: "external Codex app-server cannot be restarted by Host Agent"}
	default:
		return current, &LifecycleError{Code: "NO_LIFECYCLE_ADAPTER", Message: "Codex lifecycle ownership is unknown"}
	}
	if adapter == nil {
		return current, &LifecycleError{Code: "NO_LIFECYCLE_ADAPTER", Message: "selected lifecycle adapter is unavailable"}
	}
	if err := adapter.Restart(ctx); err != nil {
		return current, &LifecycleError{Code: "CODEX_RESTART_FAILED", Message: err.Error()}
	}
	return m.waitReady(ctx)
}

func (m *Manager) Stop(ctx context.Context) (Snapshot, error) {
	m.mutationMu.Lock()
	defer m.mutationMu.Unlock()
	if err := ctx.Err(); err != nil {
		return Snapshot{}, &LifecycleError{Code: "CODEX_LIFECYCLE_CANCELLED", Message: err.Error()}
	}
	current := m.Detect(ctx)
	if current.State == Stopped && !current.PortOpen {
		return current, nil
	}
	if current.State == ConflictAt {
		return current, &LifecycleError{Code: "CODEX_PORT_CONFLICT", Message: current.Detail}
	}
	var adapter services.LifecycleAdapter
	switch current.Ownership {
	case ManagedSystemd:
		adapter = m.Systemd
	case ManagedNative:
		if !m.NativeEnable {
			return current, &LifecycleError{Code: "NATIVE_DAEMON_DISABLED", Message: "native Codex daemon control is disabled"}
		}
		adapter = m.Native
	case External:
		return current, &LifecycleError{Code: "EXTERNAL_CODEX_PROCESS", Message: "external Codex app-server cannot be stopped by Host Agent"}
	default:
		return current, &LifecycleError{Code: "NO_LIFECYCLE_ADAPTER", Message: "Codex lifecycle ownership is unknown"}
	}
	if adapter == nil {
		return current, &LifecycleError{Code: "NO_LIFECYCLE_ADAPTER", Message: "selected lifecycle adapter is unavailable"}
	}
	if err := adapter.Stop(ctx); err != nil {
		return current, &LifecycleError{Code: "CODEX_STOP_FAILED", Message: err.Error()}
	}
	return m.waitStopped(ctx)
}

func (m *Manager) waitReady(ctx context.Context) (Snapshot, error) {
	timeout := m.WaitTimeout
	if timeout <= 0 {
		timeout = 20 * time.Second
	}
	interval := m.PollInterval
	if interval <= 0 {
		interval = 250 * time.Millisecond
	}
	waitCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		snapshot := m.Detect(waitCtx)
		if snapshot.State == LocalReady || snapshot.State == ExternalOK {
			return snapshot, nil
		}
		if snapshot.State == ConflictAt {
			return snapshot, &LifecycleError{Code: "CODEX_PORT_CONFLICT", Message: snapshot.Detail}
		}
		select {
		case <-waitCtx.Done():
			return snapshot, &LifecycleError{Code: "CODEX_READY_TIMEOUT", Message: fmt.Sprintf("Codex did not become locally ready: %v", waitCtx.Err())}
		case <-ticker.C:
		}
	}
}

func (m *Manager) waitStopped(ctx context.Context) (Snapshot, error) {
	timeout := m.WaitTimeout
	if timeout <= 0 {
		timeout = 20 * time.Second
	}
	interval := m.PollInterval
	if interval <= 0 {
		interval = 250 * time.Millisecond
	}
	waitCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		snapshot := m.Detect(waitCtx)
		if snapshot.State == Stopped && !snapshot.PortOpen {
			return snapshot, nil
		}
		if snapshot.State == ExternalOK {
			return snapshot, &LifecycleError{Code: "CODEX_REPLACED_EXTERNALLY", Message: "an external Codex app-server appeared while stopping the managed service"}
		}
		if snapshot.State == ConflictAt {
			return snapshot, &LifecycleError{Code: "CODEX_PORT_CONFLICT", Message: snapshot.Detail}
		}
		select {
		case <-waitCtx.Done():
			return snapshot, &LifecycleError{Code: "CODEX_STOP_TIMEOUT", Message: fmt.Sprintf("Codex did not stop: %v", waitCtx.Err())}
		case <-ticker.C:
		}
	}
}

func statusOf(ctx context.Context, adapter CodexLifecycleAdapter) services.AdapterStatus {
	if adapter == nil {
		return services.AdapterStatus{}
	}
	statusCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	return adapter.Status(statusCtx)
}

func ErrorDetails(err error) (string, string) {
	var lifecycleError *LifecycleError
	if errors.As(err, &lifecycleError) {
		return lifecycleError.Code, lifecycleError.Message
	}
	if err == nil {
		return "", ""
	}
	return "CODEX_LIFECYCLE_FAILED", err.Error()
}

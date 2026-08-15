package codex

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/services"
)

type fakeAdapter struct {
	name     string
	mu       sync.Mutex
	status   services.AdapterStatus
	starts   int
	restarts int
	stops    int
	onStart  func()
	onStop   func()
}

func (a *fakeAdapter) Name() string { return a.name }
func (a *fakeAdapter) Status(context.Context) services.AdapterStatus {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.status
}
func (a *fakeAdapter) Start(context.Context) error {
	a.mu.Lock()
	a.starts++
	a.status.Active = true
	a.mu.Unlock()
	if a.onStart != nil {
		a.onStart()
	}
	return nil
}
func (a *fakeAdapter) Stop(context.Context) error {
	a.mu.Lock()
	a.stops++
	a.status.Active = false
	a.mu.Unlock()
	if a.onStop != nil {
		a.onStop()
	}
	return nil
}
func (a *fakeAdapter) Restart(context.Context) error {
	a.mu.Lock()
	a.restarts++
	a.status.Active = true
	a.mu.Unlock()
	if a.onStart != nil {
		a.onStart()
	}
	return nil
}

type fakeProbe struct {
	mu     sync.Mutex
	result ProbeResult
}

type blockingRestartAdapter struct {
	mu         sync.Mutex
	concurrent int
	maximum    int
	calls      int
	entered    chan struct{}
	release    chan struct{}
}

func (a *blockingRestartAdapter) Name() string { return "blocking" }
func (a *blockingRestartAdapter) Status(context.Context) services.AdapterStatus {
	return services.AdapterStatus{Installed: true, Active: true}
}
func (a *blockingRestartAdapter) Start(context.Context) error { return nil }
func (a *blockingRestartAdapter) Stop(context.Context) error  { return nil }
func (a *blockingRestartAdapter) Restart(context.Context) error {
	a.mu.Lock()
	a.calls++
	a.concurrent++
	if a.concurrent > a.maximum {
		a.maximum = a.concurrent
	}
	a.mu.Unlock()
	a.entered <- struct{}{}
	<-a.release
	a.mu.Lock()
	a.concurrent--
	a.mu.Unlock()
	return nil
}

func (p *fakeProbe) Check(context.Context) ProbeResult {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.result
}

func (p *fakeProbe) set(result ProbeResult) {
	p.mu.Lock()
	p.result = result
	p.mu.Unlock()
}

func TestDetectOwnershipAndConflicts(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name      string
		systemd   services.AdapterStatus
		native    services.AdapterStatus
		probe     ProbeResult
		ownership Ownership
		state     ServiceState
	}{
		{"managed systemd ready", services.AdapterStatus{Installed: true, Active: true}, services.AdapterStatus{Installed: true}, ProbeResult{PortOpen: true, Ready: true}, ManagedSystemd, LocalReady},
		{"external ready", services.AdapterStatus{Installed: true}, services.AdapterStatus{Installed: true}, ProbeResult{PortOpen: true, Ready: true}, External, ExternalOK},
		{"non Codex port conflict", services.AdapterStatus{Installed: true}, services.AdapterStatus{}, ProbeResult{PortOpen: true, Ready: false}, Conflict, ConflictAt},
		{"double ownership conflict", services.AdapterStatus{Installed: true, Active: true}, services.AdapterStatus{Installed: true, Active: true}, ProbeResult{PortOpen: true, Ready: true}, Conflict, ConflictAt},
		{"managed stopped", services.AdapterStatus{Installed: true}, services.AdapterStatus{Installed: true}, ProbeResult{}, ManagedSystemd, Stopped},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			manager := &Manager{
				Systemd: &fakeAdapter{name: "systemd", status: test.systemd},
				Native:  &fakeAdapter{name: "native", status: test.native},
				Probe:   &fakeProbe{result: test.probe},
			}
			snapshot := manager.Detect(context.Background())
			if snapshot.Ownership != test.ownership || snapshot.State != test.state {
				t.Fatalf("got %s/%s want %s/%s: %+v", snapshot.Ownership, snapshot.State, test.ownership, test.state, snapshot)
			}
		})
	}
}

func TestEnsureRunningStartsManagedSystemdAndWaitsForReady(t *testing.T) {
	t.Parallel()
	probe := &fakeProbe{}
	systemd := &fakeAdapter{name: "systemd", status: services.AdapterStatus{Installed: true}}
	systemd.onStart = func() { probe.set(ProbeResult{PortOpen: true, Ready: true}) }
	manager := &Manager{
		Systemd: systemd, Native: &fakeAdapter{name: "native"}, Probe: probe,
		WaitTimeout: time.Second, PollInterval: time.Millisecond,
	}
	snapshot, err := manager.EnsureRunning(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.State != LocalReady || snapshot.Ownership != ManagedSystemd || systemd.starts != 1 {
		t.Fatalf("unexpected ensure result: snapshot=%+v starts=%d", snapshot, systemd.starts)
	}
}

func TestEnsureRunningAcceptsExternalWithoutTakingOwnership(t *testing.T) {
	t.Parallel()
	systemd := &fakeAdapter{name: "systemd", status: services.AdapterStatus{Installed: true}}
	manager := &Manager{
		Systemd: systemd, Native: &fakeAdapter{name: "native"},
		Probe: &fakeProbe{result: ProbeResult{PortOpen: true, Ready: true}},
	}
	snapshot, err := manager.EnsureRunning(context.Background())
	if err != nil || snapshot.State != ExternalOK || systemd.starts != 0 {
		t.Fatalf("external ensure changed ownership: snapshot=%+v starts=%d err=%v", snapshot, systemd.starts, err)
	}
	_, err = manager.Restart(context.Background())
	var lifecycleError *LifecycleError
	if !errors.As(err, &lifecycleError) || lifecycleError.Code != "EXTERNAL_CODEX_PROCESS" {
		t.Fatalf("external restart was not rejected: %v", err)
	}
	if systemd.restarts != 0 || systemd.stops != 0 {
		t.Fatalf("external process was mutated: restarts=%d stops=%d", systemd.restarts, systemd.stops)
	}
	_, err = manager.Stop(context.Background())
	if !errors.As(err, &lifecycleError) || lifecycleError.Code != "EXTERNAL_CODEX_PROCESS" {
		t.Fatalf("external stop was not rejected: %v", err)
	}
	if systemd.stops != 0 {
		t.Fatalf("external process was stopped: %d", systemd.stops)
	}
}

func TestConflictNeverStartsSecondInstance(t *testing.T) {
	t.Parallel()
	systemd := &fakeAdapter{name: "systemd", status: services.AdapterStatus{Installed: true}}
	manager := &Manager{
		Systemd: systemd, Native: &fakeAdapter{name: "native"},
		Probe: &fakeProbe{result: ProbeResult{PortOpen: true, Ready: false}},
	}
	_, err := manager.EnsureRunning(context.Background())
	var lifecycleError *LifecycleError
	if !errors.As(err, &lifecycleError) || lifecycleError.Code != "CODEX_PORT_CONFLICT" {
		t.Fatalf("conflict was not reported: %v", err)
	}
	if systemd.starts != 0 {
		t.Fatalf("started a second app-server during conflict: %d", systemd.starts)
	}
}

func TestEnsureRunningTimesOutWithoutClaimingReady(t *testing.T) {
	t.Parallel()
	systemd := &fakeAdapter{name: "systemd", status: services.AdapterStatus{Installed: true}}
	manager := &Manager{
		Systemd: systemd, Native: &fakeAdapter{name: "native"}, Probe: &fakeProbe{},
		WaitTimeout: 5 * time.Millisecond, PollInterval: time.Millisecond,
	}
	snapshot, err := manager.EnsureRunning(context.Background())
	var lifecycleError *LifecycleError
	if !errors.As(err, &lifecycleError) || lifecycleError.Code != "CODEX_READY_TIMEOUT" {
		t.Fatalf("missing ready timeout: snapshot=%+v err=%v", snapshot, err)
	}
	if snapshot.Ready {
		t.Fatalf("timed-out lifecycle was reported ready: %+v", snapshot)
	}
}

func TestManagedStopUsesOwnerAndWaitsForClosedPort(t *testing.T) {
	t.Parallel()
	probe := &fakeProbe{result: ProbeResult{PortOpen: true, Ready: true}}
	systemd := &fakeAdapter{name: "systemd", status: services.AdapterStatus{Installed: true, Active: true}}
	systemd.onStop = func() { probe.set(ProbeResult{}) }
	manager := &Manager{
		Systemd: systemd, Native: &fakeAdapter{name: "native"}, Probe: probe,
		WaitTimeout: time.Second, PollInterval: time.Millisecond,
	}
	snapshot, err := manager.Stop(context.Background())
	if err != nil || snapshot.State != Stopped || snapshot.PortOpen || systemd.stops != 1 {
		t.Fatalf("unexpected stop result: snapshot=%+v stops=%d err=%v", snapshot, systemd.stops, err)
	}
}

func TestLifecycleMutationsAreSerializedAcrossOperationKeys(t *testing.T) {
	t.Parallel()
	adapter := &blockingRestartAdapter{
		entered: make(chan struct{}, 2),
		release: make(chan struct{}, 2),
	}
	manager := &Manager{
		Systemd: adapter, Native: &fakeAdapter{name: "native"},
		Probe:       &fakeProbe{result: ProbeResult{PortOpen: true, Ready: true}},
		WaitTimeout: time.Second, PollInterval: time.Millisecond,
	}
	errors := make(chan error, 2)
	go func() { _, err := manager.Restart(context.Background()); errors <- err }()
	<-adapter.entered
	go func() { _, err := manager.Restart(context.Background()); errors <- err }()
	select {
	case <-adapter.entered:
		t.Fatal("second restart entered adapter before the first mutation completed")
	case <-time.After(20 * time.Millisecond):
	}
	adapter.release <- struct{}{}
	<-adapter.entered
	adapter.release <- struct{}{}
	if err := <-errors; err != nil {
		t.Fatal(err)
	}
	if err := <-errors; err != nil {
		t.Fatal(err)
	}
	adapter.mu.Lock()
	defer adapter.mu.Unlock()
	if adapter.calls != 2 || adapter.maximum != 1 {
		t.Fatalf("lifecycle mutations overlapped: calls=%d maxConcurrent=%d", adapter.calls, adapter.maximum)
	}
}

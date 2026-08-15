package operations

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestOperationIdempotencyAndPersistence(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "operations.json")
	updates := make(chan Operation, 8)
	manager, err := New(path, func(op Operation) { updates <- op })
	if err != nil {
		t.Fatal(err)
	}
	work := func(context.Context) (any, *OperationError) { return map[string]bool{"ready": true}, nil }
	first, duplicate, err := manager.Start("phone", "same-key-123", "codex.ensure-running", "PROCESS_CONTROL", nil, work)
	if err != nil || duplicate {
		t.Fatalf("first start: duplicate=%v err=%v", duplicate, err)
	}
	second, duplicate, err := manager.Start("phone", "same-key-123", "codex.ensure-running", "PROCESS_CONTROL", nil, work)
	if err != nil || !duplicate || second.ID != first.ID {
		t.Fatalf("duplicate mismatch: first=%s second=%s duplicate=%v err=%v", first.ID, second.ID, duplicate, err)
	}
	completed := waitOperation(t, manager, first.ID)
	if completed.Status != Succeeded || !json.Valid(completed.Result) {
		t.Fatalf("unexpected completed operation: %+v", completed)
	}
	reloaded, err := New(path, nil)
	if err != nil {
		t.Fatal(err)
	}
	persisted, ok := reloaded.Get(first.ID)
	if !ok || persisted.Status != Succeeded {
		t.Fatalf("operation was not persisted: %+v", persisted)
	}
}

func TestInFlightOperationBecomesFailedAfterRestart(t *testing.T) {
	t.Parallel()
	directory := t.TempDir()
	path := filepath.Join(directory, "operations.json")
	state := persistedState{
		Operations: map[string]Operation{
			"op_inflight": {ID: "op_inflight", DeviceID: "phone", Kind: "test", Status: Running, CreatedAt: time.Now(), StartedAt: time.Now()},
		},
		Idempotency: map[string]string{"phone\x00test\x00key": "op_inflight"},
	}
	data, _ := json.Marshal(state)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	manager, err := New(path, nil)
	if err != nil {
		t.Fatal(err)
	}
	op, _ := manager.Get("op_inflight")
	if op.Status != Failed || op.Error == nil || op.Error.Code != "AGENT_RESTARTED" {
		t.Fatalf("in-flight operation did not fail authoritatively: %+v", op)
	}
}

func TestFailedInitialPersistenceDoesNotLeavePhantomIdempotency(t *testing.T) {
	t.Parallel()
	// Renaming the atomic temp file over an existing directory must fail on every platform.
	path := filepath.Join(t.TempDir(), "operations-as-directory")
	if err := os.Mkdir(path, 0o700); err != nil {
		t.Fatal(err)
	}
	manager := &Manager{
		path: path, operations: map[string]Operation{}, idempotency: map[string]string{},
		now: time.Now, operationTTL: 7 * 24 * time.Hour,
	}
	work := func(context.Context) (any, *OperationError) { return nil, nil }
	if _, _, err := manager.Start("phone", "persistence-key", "test", "READ", nil, work); err == nil {
		t.Fatal("unwritable operation state was accepted")
	}
	if len(manager.operations) != 0 || len(manager.idempotency) != 0 {
		t.Fatalf("failed persistence left phantom state: operations=%v idempotency=%v", manager.operations, manager.idempotency)
	}
}

func waitOperation(t *testing.T, manager *Manager, id string) Operation {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		op, ok := manager.Get(id)
		if ok && op.Status != Pending && op.Status != Running {
			return op
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("operation %s did not complete", id)
	return Operation{}
}

package operations

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

type Status string

const (
	Pending   Status = "PENDING"
	Running   Status = "RUNNING"
	Succeeded Status = "SUCCEEDED"
	Failed    Status = "FAILED"
	Cancelled Status = "CANCELLED"
)

type OperationError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type Operation struct {
	ID             string            `json:"operationId"`
	DeviceID       string            `json:"deviceId"`
	Kind           string            `json:"kind"`
	RiskLevel      string            `json:"riskLevel"`
	IdempotencyKey string            `json:"-"`
	Status         Status            `json:"status"`
	CreatedAt      time.Time         `json:"createdAt"`
	StartedAt      time.Time         `json:"startedAt,omitempty"`
	CompletedAt    time.Time         `json:"completedAt,omitempty"`
	Result         json.RawMessage   `json:"result,omitempty"`
	Error          *OperationError   `json:"error,omitempty"`
	Metadata       map[string]string `json:"metadata,omitempty"`
}

type Work func(context.Context) (any, *OperationError)
type Observer func(Operation)

type persistedState struct {
	Operations  map[string]Operation `json:"operations"`
	Idempotency map[string]string    `json:"idempotency"`
}

type Manager struct {
	path         string
	mu           sync.RWMutex
	operations   map[string]Operation
	idempotency  map[string]string
	observer     Observer
	now          func() time.Time
	operationTTL time.Duration
}

func New(path string, observer Observer) (*Manager, error) {
	m := &Manager{
		path: path, operations: map[string]Operation{}, idempotency: map[string]string{},
		observer: observer, now: time.Now, operationTTL: 7 * 24 * time.Hour,
	}
	if err := m.load(); err != nil {
		return nil, err
	}
	return m, nil
}

func (m *Manager) Start(deviceID, idempotencyKey, kind, risk string, metadata map[string]string, work Work) (Operation, bool, error) {
	if deviceID == "" || idempotencyKey == "" || kind == "" || work == nil {
		return Operation{}, false, errors.New("missing operation identity, kind, or work")
	}
	compound := deviceID + "\x00" + kind + "\x00" + idempotencyKey
	m.mu.Lock()
	if id, ok := m.idempotency[compound]; ok {
		if existing, found := m.operations[id]; found {
			m.mu.Unlock()
			return existing, true, nil
		}
	}
	id, err := operationID()
	if err != nil {
		m.mu.Unlock()
		return Operation{}, false, err
	}
	now := m.now().UTC()
	op := Operation{
		ID: id, DeviceID: deviceID, Kind: kind, RiskLevel: risk,
		IdempotencyKey: idempotencyKey, Status: Pending, CreatedAt: now,
		Metadata: copyMap(metadata),
	}
	m.operations[id] = op
	m.idempotency[compound] = id
	m.pruneLocked(now)
	err = m.persistLocked()
	if err != nil {
		delete(m.operations, id)
		delete(m.idempotency, compound)
	}
	m.mu.Unlock()
	if err != nil {
		return Operation{}, false, err
	}
	m.notify(op)
	go m.run(op.ID, work)
	return op, false, nil
}

func (m *Manager) Get(id string) (Operation, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	op, ok := m.operations[id]
	return op, ok
}

func (m *Manager) List(deviceID string) []Operation {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]Operation, 0, len(m.operations))
	for _, op := range m.operations {
		if deviceID == "" || op.DeviceID == deviceID {
			result = append(result, op)
		}
	}
	sort.Slice(result, func(i, j int) bool { return result[i].CreatedAt.After(result[j].CreatedAt) })
	return result
}

func (m *Manager) run(id string, work Work) {
	m.transition(id, func(op *Operation) {
		op.Status = Running
		op.StartedAt = m.now().UTC()
	})
	result, operationError := work(context.Background())
	m.transition(id, func(op *Operation) {
		op.CompletedAt = m.now().UTC()
		if operationError != nil {
			op.Status = Failed
			op.Error = operationError
			return
		}
		encoded, err := json.Marshal(result)
		if err != nil {
			op.Status = Failed
			op.Error = &OperationError{Code: "RESULT_ENCODING_FAILED", Message: err.Error()}
			return
		}
		op.Status = Succeeded
		op.Result = encoded
	})
}

func (m *Manager) transition(id string, mutate func(*Operation)) {
	m.mu.Lock()
	op, ok := m.operations[id]
	if !ok {
		m.mu.Unlock()
		return
	}
	mutate(&op)
	m.operations[id] = op
	_ = m.persistLocked()
	m.mu.Unlock()
	m.notify(op)
}

func (m *Manager) notify(op Operation) {
	if m.observer != nil {
		m.observer(op)
	}
}

func (m *Manager) load() error {
	data, err := os.ReadFile(m.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var state persistedState
	if err := json.Unmarshal(data, &state); err != nil {
		return err
	}
	if state.Operations != nil {
		m.operations = state.Operations
	}
	if state.Idempotency != nil {
		m.idempotency = state.Idempotency
	}
	now := m.now().UTC()
	changed := false
	for id, op := range m.operations {
		if op.Status == Pending || op.Status == Running {
			op.Status = Failed
			op.CompletedAt = now
			op.Error = &OperationError{Code: "AGENT_RESTARTED", Message: "host agent restarted before the operation completed"}
			m.operations[id] = op
			changed = true
		}
	}
	m.pruneLocked(now)
	if changed {
		return m.persistLocked()
	}
	return nil
}

func (m *Manager) pruneLocked(now time.Time) {
	for id, op := range m.operations {
		if !op.CompletedAt.IsZero() && now.Sub(op.CompletedAt) > m.operationTTL {
			delete(m.operations, id)
		}
	}
	for key, id := range m.idempotency {
		if _, ok := m.operations[id]; !ok {
			delete(m.idempotency, key)
		}
	}
}

func (m *Manager) persistLocked() error {
	if err := os.MkdirAll(filepath.Dir(m.path), 0o700); err != nil {
		return err
	}
	state := persistedState{Operations: m.operations, Idempotency: m.idempotency}
	data, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(m.path), ".operations-*.json")
	if err != nil {
		return err
	}
	name := temp.Name()
	defer os.Remove(name)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return err
	}
	if _, err := temp.Write(data); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(name, m.path)
}

func operationID() (string, error) {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return "op_" + hex.EncodeToString(bytes), nil
}

func copyMap(input map[string]string) map[string]string {
	if len(input) == 0 {
		return nil
	}
	result := make(map[string]string, len(input))
	for key, value := range input {
		result[key] = value
	}
	return result
}

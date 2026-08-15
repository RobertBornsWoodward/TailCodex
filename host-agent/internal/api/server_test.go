package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/audit"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/auth"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/codex"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/events"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/operations"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/services"
)

type apiFakeAdapter struct{ status services.AdapterStatus }

func (a *apiFakeAdapter) Name() string                                  { return "fake" }
func (a *apiFakeAdapter) Status(context.Context) services.AdapterStatus { return a.status }
func (a *apiFakeAdapter) Start(context.Context) error                   { a.status.Active = true; return nil }
func (a *apiFakeAdapter) Stop(context.Context) error                    { a.status.Active = false; return nil }
func (a *apiFakeAdapter) Restart(context.Context) error                 { a.status.Active = true; return nil }

type apiFakeProbe struct{ result codex.ProbeResult }

func (p *apiFakeProbe) Check(context.Context) codex.ProbeResult { return p.result }

type testAgent struct {
	server     *httptest.Server
	registry   *auth.Registry
	operations *operations.Manager
	events     *events.Hub
}

func newTestAgent(t *testing.T) *testAgent {
	t.Helper()
	directory := t.TempDir()
	registry := auth.NewRegistry(filepath.Join(directory, "devices.json"))
	hub := events.New()
	operationManager, err := operations.New(filepath.Join(directory, "operations.json"), func(op operations.Operation) {
		hub.Publish("operation.updated", op)
	})
	if err != nil {
		t.Fatal(err)
	}
	lifecycle := &codex.Manager{
		Systemd: &apiFakeAdapter{status: services.AdapterStatus{Installed: true}},
		Native:  &apiFakeAdapter{},
		Probe:   &apiFakeProbe{result: codex.ProbeResult{PortOpen: true, Ready: true}},
	}
	server, err := NewServer(Dependencies{
		Registry: registry, Operations: operationManager, Lifecycle: lifecycle,
		Events: hub, Audit: audit.New(filepath.Join(directory, "audit.jsonl")),
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)), Features: []string{"codex.lifecycle", "host.logs"},
	})
	if err != nil {
		t.Fatal(err)
	}
	return &testAgent{server: httptest.NewServer(server.Handler()), registry: registry, operations: operationManager, events: hub}
}

func (a *testAgent) close() { a.server.Close() }

func (a *testAgent) pair(t *testing.T) string {
	t.Helper()
	ticket, err := a.registry.CreatePairing(time.Minute, []string{"codex.lifecycle", "host.logs"})
	if err != nil {
		t.Fatal(err)
	}
	body := fmt.Sprintf(`{"code":%q,"deviceId":"phone-1","name":"Phone"}`, ticket.Code)
	response, err := http.Post(a.server.URL+"/v1/pair", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusCreated {
		data, _ := io.ReadAll(response.Body)
		t.Fatalf("pair status=%d body=%s", response.StatusCode, data)
	}
	var paired PairResponse
	if err := json.NewDecoder(response.Body).Decode(&paired); err != nil {
		t.Fatal(err)
	}
	return paired.Credential
}

func TestHelloPairAuthAndCapabilities(t *testing.T) {
	t.Parallel()
	agent := newTestAgent(t)
	defer agent.close()

	response, err := http.Get(agent.server.URL + "/v1/hello")
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("hello status=%d", response.StatusCode)
	}
	response.Body.Close()

	credential := agent.pair(t)
	response, _ = http.Get(agent.server.URL + "/v1/health?token=" + credential)
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("query credential was accepted: %d", response.StatusCode)
	}
	response.Body.Close()

	request, _ := http.NewRequest(http.MethodGet, agent.server.URL+"/v1/capabilities", nil)
	request.Header.Set("Authorization", "Bearer "+credential)
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("capabilities status=%d", response.StatusCode)
	}
	var payload struct {
		Features []string `json:"features"`
		Grants   []string `json:"grants"`
	}
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		t.Fatal(err)
	}
	if strings.Join(payload.Features, ",") != "codex.lifecycle,host.logs" || strings.Join(payload.Grants, ",") != "codex.lifecycle,host.logs" {
		t.Fatalf("unexpected capabilities: %+v", payload)
	}
}

func TestLogSummaryIsAuthenticatedCapabilityControlledAndRedacted(t *testing.T) {
	t.Parallel()
	agent := newTestAgent(t)
	defer agent.close()
	credential := agent.pair(t)

	request, _ := http.NewRequest(http.MethodGet, agent.server.URL+"/v1/logs/summary", nil)
	request.Header.Set("Authorization", "Bearer "+credential)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	data, _ := io.ReadAll(response.Body)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("log summary status=%d body=%s", response.StatusCode, data)
	}
	if bytes.Contains(data, []byte(credential)) || !bytes.Contains(data, []byte("device.pair")) {
		t.Fatalf("unsafe or missing log summary: %s", data)
	}
}

func TestReservedDesktopActionIsFeatureGated(t *testing.T) {
	t.Parallel()
	agent := newTestAgent(t)
	defer agent.close()
	credential := agent.pair(t)
	request, _ := http.NewRequest(
		http.MethodPost,
		agent.server.URL+"/v1/actions/desktop.launch",
		strings.NewReader(`{"requestId":"desktop-request","appId":"codex"}`),
	)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+credential)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusNotImplemented {
		t.Fatalf("reserved desktop action status=%d", response.StatusCode)
	}
}

func TestLifecycleOperationIsTypedIdempotentAndRecoverable(t *testing.T) {
	t.Parallel()
	agent := newTestAgent(t)
	defer agent.close()
	credential := agent.pair(t)

	start := func(key, body string) (int, OperationAcceptedResponse, string) {
		request, _ := http.NewRequest(http.MethodPost, agent.server.URL+"/v1/actions/codex.ensure-running", bytes.NewBufferString(body))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("Authorization", "Bearer "+credential)
		request.Header.Set("Idempotency-Key", key)
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		data, _ := io.ReadAll(response.Body)
		var accepted OperationAcceptedResponse
		_ = json.Unmarshal(data, &accepted)
		return response.StatusCode, accepted, string(data)
	}

	status, _, body := start("valid-key-123", `{"requestId":"request-1","unexpected":true}`)
	if status != http.StatusBadRequest || !strings.Contains(body, "unknown field") {
		t.Fatalf("unknown typed parameter accepted: status=%d body=%s", status, body)
	}
	status, first, body := start("valid-key-123", `{"requestId":"request-1"}`)
	if status != http.StatusAccepted || first.OperationID == "" {
		t.Fatalf("operation not accepted: status=%d body=%s", status, body)
	}
	status, second, _ := start("valid-key-123", `{"requestId":"request-1"}`)
	if status != http.StatusAccepted || !second.Duplicate || second.OperationID != first.OperationID {
		t.Fatalf("operation was not idempotent: first=%+v second=%+v", first, second)
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		request, _ := http.NewRequest(http.MethodGet, agent.server.URL+"/v1/operations/"+first.OperationID, nil)
		request.Header.Set("Authorization", "Bearer "+credential)
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		var payload struct {
			Operation operations.Operation `json:"operation"`
		}
		_ = json.NewDecoder(response.Body).Decode(&payload)
		response.Body.Close()
		if payload.Operation.Status == operations.Succeeded {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("authoritative operation endpoint never reached SUCCEEDED")
}

func TestOperationEventsAreAdvisoryAndAuthenticated(t *testing.T) {
	t.Parallel()
	agent := newTestAgent(t)
	defer agent.close()
	credential := agent.pair(t)
	wsURL := "ws" + strings.TrimPrefix(agent.server.URL, "http") + "/v1/events"
	if conn, response, _ := websocket.DefaultDialer.Dial(wsURL, nil); conn != nil || response.StatusCode != http.StatusUnauthorized {
		if conn != nil {
			conn.Close()
		}
		t.Fatalf("unauthenticated event stream was accepted")
	}
	header := http.Header{"Authorization": []string{"Bearer " + credential}}
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	request, _ := http.NewRequest(http.MethodPost, agent.server.URL+"/v1/actions/codex.ensure-running", strings.NewReader(`{"requestId":"request-ws"}`))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+credential)
	request.Header.Set("Idempotency-Key", "events-key-123")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()

	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	for {
		var event events.Event
		if err := conn.ReadJSON(&event); err != nil {
			t.Fatal(err)
		}
		encoded, _ := json.Marshal(event.Payload)
		var op operations.Operation
		_ = json.Unmarshal(encoded, &op)
		if event.Type == "operation.updated" && op.Status == operations.Succeeded {
			return
		}
	}
}

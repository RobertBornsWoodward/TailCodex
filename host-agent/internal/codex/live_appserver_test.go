package codex

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// This read-only smoke is opt-in because it targets the user's real app-server.
func TestLiveAppServerInitializeAndListThreads(t *testing.T) {
	endpoint := os.Getenv("TAILCODEX_LIVE_CODEX_WS")
	tokenFile := os.Getenv("TAILCODEX_LIVE_CODEX_TOKEN_FILE")
	if endpoint == "" || tokenFile == "" {
		t.Skip("set TAILCODEX_LIVE_CODEX_WS and TAILCODEX_LIVE_CODEX_TOKEN_FILE")
	}
	tokenBytes, err := os.ReadFile(tokenFile)
	if err != nil {
		t.Fatal(err)
	}
	header := http.Header{"Authorization": []string{"Bearer " + strings.TrimSpace(string(tokenBytes))}}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	connection, _, err := websocket.DefaultDialer.DialContext(ctx, endpoint, header)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_ = connection.SetReadDeadline(time.Now().Add(10 * time.Second))

	if err := connection.WriteJSON(map[string]any{
		"id": 1, "method": "initialize",
		"params": map[string]any{"clientInfo": map[string]any{
			"name": "tailcodex_host_agent_smoke", "title": "TailCodex read-only smoke", "version": "0.3.1",
		}},
	}); err != nil {
		t.Fatal(err)
	}
	initialize := readResponse(t, connection, 1)
	if initialize["error"] != nil {
		t.Fatalf("initialize returned an error")
	}
	if err := connection.WriteJSON(map[string]any{"method": "initialized", "params": map[string]any{}}); err != nil {
		t.Fatal(err)
	}
	if err := connection.WriteJSON(map[string]any{
		"id": 2, "method": "thread/list",
		"params": map[string]any{"limit": 1, "sortKey": "updated_at", "sortDirection": "desc"},
	}); err != nil {
		t.Fatal(err)
	}
	listed := readResponse(t, connection, 2)
	if listed["error"] != nil || listed["result"] == nil {
		t.Fatalf("thread/list did not return a result")
	}
}

// This mutating smoke creates an ephemeral thread and consumes a real model turn. It is guarded
// separately from the read-only smoke so ordinary test runs can never trigger model work.
func TestLiveAppServerEphemeralThreadAndRealTurn(t *testing.T) {
	if os.Getenv("TAILCODEX_LIVE_ALLOW_MUTATION") != "1" {
		t.Skip("set TAILCODEX_LIVE_ALLOW_MUTATION=1 explicitly")
	}
	endpoint := os.Getenv("TAILCODEX_LIVE_CODEX_WS")
	tokenFile := os.Getenv("TAILCODEX_LIVE_CODEX_TOKEN_FILE")
	if endpoint == "" || tokenFile == "" {
		t.Skip("set TAILCODEX_LIVE_CODEX_WS and TAILCODEX_LIVE_CODEX_TOKEN_FILE")
	}
	tokenBytes, err := os.ReadFile(tokenFile)
	if err != nil {
		t.Fatal(err)
	}
	header := http.Header{"Authorization": []string{"Bearer " + strings.TrimSpace(string(tokenBytes))}}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	connection, _, err := websocket.DefaultDialer.DialContext(ctx, endpoint, header)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_ = connection.SetReadDeadline(time.Now().Add(2 * time.Minute))

	if err := connection.WriteJSON(map[string]any{
		"id": 10, "method": "initialize",
		"params": map[string]any{"clientInfo": map[string]any{
			"name": "tailcodex_host_agent_e2e", "title": "TailCodex live E2E", "version": "0.3.1",
		}},
	}); err != nil {
		t.Fatal(err)
	}
	if response := readResponse(t, connection, 10); response["error"] != nil {
		t.Fatal("initialize returned an error")
	}
	if err := connection.WriteJSON(map[string]any{"method": "initialized", "params": map[string]any{}}); err != nil {
		t.Fatal(err)
	}
	if err := connection.WriteJSON(map[string]any{
		"id": 11, "method": "thread/start",
		"params": map[string]any{
			"cwd": "/tmp", "serviceName": "tailcodex_android", "ephemeral": true,
			"approvalPolicy": "on-request", "approvalsReviewer": "user",
		},
	}); err != nil {
		t.Fatal(err)
	}
	threadResponse := readResponse(t, connection, 11)
	threadID := nestedString(threadResponse, "result", "thread", "id")
	if threadResponse["error"] != nil || threadID == "" {
		t.Fatal("ephemeral thread/start did not return a thread")
	}

	const marker = "TAILCODEX_E2E_OK"
	if err := connection.WriteJSON(map[string]any{
		"id": 12, "method": "turn/start",
		"params": map[string]any{
			"threadId": threadID,
			"input": []any{map[string]any{
				"type": "text", "text": "Reply with exactly TAILCODEX_E2E_OK. Do not use tools.",
			}},
		},
	}); err != nil {
		t.Fatal(err)
	}
	turnResponse := readResponse(t, connection, 12)
	turnID := nestedString(turnResponse, "result", "turn", "id")
	if turnResponse["error"] != nil || turnID == "" {
		t.Fatal("turn/start did not return a turn")
	}

	for {
		_, payload, err := connection.ReadMessage()
		if err != nil {
			t.Fatal(err)
		}
		var message map[string]any
		if err := json.Unmarshal(payload, &message); err != nil {
			continue
		}
		if _, serverRequest := message["id"]; serverRequest && message["method"] != nil {
			t.Fatalf("unexpected server request during no-tool E2E: %v", message["method"])
		}
		if message["method"] != "turn/completed" || nestedString(message, "params", "turn", "id") != turnID {
			continue
		}
		status := nestedString(message, "params", "turn", "status")
		if status != "completed" {
			t.Fatalf("real turn ended with status %q", status)
		}
		turn := nestedMap(message, "params", "turn")
		items, _ := turn["items"].([]any)
		for _, rawItem := range items {
			item, _ := rawItem.(map[string]any)
			if item["type"] == "agentMessage" && strings.Contains(stringValue(item["text"]), marker) {
				return
			}
		}
		t.Fatal("completed turn did not contain the expected agent message")
	}
}

func readResponse(t *testing.T, connection *websocket.Conn, wantedID float64) map[string]any {
	t.Helper()
	for {
		_, payload, err := connection.ReadMessage()
		if err != nil {
			t.Fatal(err)
		}
		var message map[string]any
		if err := json.Unmarshal(payload, &message); err != nil {
			continue
		}
		if id, ok := message["id"].(float64); ok && id == wantedID {
			return message
		}
	}
}

func nestedMap(value map[string]any, path ...string) map[string]any {
	current := value
	for _, key := range path {
		next, _ := current[key].(map[string]any)
		if next == nil {
			return nil
		}
		current = next
	}
	return current
}

func nestedString(value map[string]any, path ...string) string {
	if len(path) == 0 {
		return ""
	}
	parent := nestedMap(value, path[:len(path)-1]...)
	if parent == nil {
		return ""
	}
	return stringValue(parent[path[len(path)-1]])
}

func stringValue(value any) string {
	text, _ := value.(string)
	return text
}

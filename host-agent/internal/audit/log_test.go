package audit

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestAuditRedactsSensitiveMetadata(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "audit.jsonl")
	logger := New(path)
	if err := logger.Append(Entry{
		Actor: "phone", Action: "test", RiskLevel: "READ", Outcome: "ok",
		Metadata: map[string]string{"credential": "secret", "terminalInput": "sudo password", "host": "arch"},
	}); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	if strings.Contains(text, "secret") || strings.Contains(text, "sudo password") {
		t.Fatalf("audit leaked sensitive metadata: %s", text)
	}
	if !strings.Contains(text, `"host":"arch"`) {
		t.Fatalf("audit dropped safe metadata: %s", text)
	}
}

func TestRecentAuditSummaryIsBoundedNewestFirstAndRedacted(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "audit.jsonl")
	logger := New(path)
	for _, action := range []string{"first", "second", "third"} {
		if err := logger.Append(Entry{
			Actor: "phone", Action: action, RiskLevel: "READ", Outcome: "ok",
			Metadata: map[string]string{"token": "secret-" + action},
		}); err != nil {
			t.Fatal(err)
		}
	}
	entries, err := logger.Recent(2)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 2 || entries[0].Action != "third" || entries[1].Action != "second" {
		t.Fatalf("unexpected summary order or bound: %+v", entries)
	}
	for _, entry := range entries {
		if entry.Metadata["token"] != "[redacted]" {
			t.Fatalf("summary leaked token metadata: %+v", entry)
		}
	}
}

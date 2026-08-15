package integrations

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"testing"
)

func TestRootGuardRejectsTraversalAndSymlinkEscape(t *testing.T) {
	parent := t.TempDir()
	root := filepath.Join(parent, "workspace")
	outside := filepath.Join(parent, "outside")
	if err := os.Mkdir(root, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(outside, 0o700); err != nil {
		t.Fatal(err)
	}
	insideFile := filepath.Join(root, "inside.txt")
	outsideFile := filepath.Join(outside, "secret.txt")
	if err := os.WriteFile(insideFile, []byte("inside"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(outsideFile, []byte("outside"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outsideFile, filepath.Join(root, "escape")); err != nil {
		t.Fatal(err)
	}
	guard, err := NewRootGuard([]string{root})
	if err != nil {
		t.Fatal(err)
	}
	if resolved, err := guard.ResolveExisting(insideFile); err != nil || resolved != insideFile {
		t.Fatalf("inside path rejected: %q %v", resolved, err)
	}
	if _, err := guard.ResolveExisting(filepath.Join(root, "..", "outside", "secret.txt")); err == nil {
		t.Fatal("directory traversal was accepted")
	}
	if _, err := guard.ResolveExisting(filepath.Join(root, "escape")); err == nil {
		t.Fatal("symbolic-link escape was accepted")
	}
}

func TestBuiltinIntegrationDeclarationsAreCompileTimeUniqueAndComplete(t *testing.T) {
	t.Parallel()
	registry, err := NewRegistry(BuiltinDeclarations()...)
	if err != nil {
		t.Fatal(err)
	}
	descriptors := registry.Descriptors()
	ids := make([]string, 0, len(descriptors))
	for _, descriptor := range descriptors {
		if descriptor.Version == "" || descriptor.RiskLevel == "" || descriptor.Mutability == "" ||
			descriptor.TimeoutClass == "" || descriptor.InputSchema == "" || descriptor.OutputSchema == "" ||
			descriptor.PresentationHint == "" || len(descriptor.RequiredCapabilities) == 0 {
			t.Fatalf("incomplete integration declaration: %+v", descriptor)
		}
		if !json.Valid([]byte(descriptor.InputSchema)) || !json.Valid([]byte(descriptor.OutputSchema)) {
			t.Fatalf("invalid integration schema: %+v", descriptor)
		}
		ids = append(ids, descriptor.ID)
	}
	sort.Strings(ids)
	want := []string{
		"files.list", "files.read", "files.upload", "git.diff", "git.log", "git.status",
		"mathematica.evaluate", "mathematica.status", "system.cpu", "system.disk", "system.gpu",
		"system.memory", "system.network", "systemd.status", "vscode.openWorkspace",
	}
	if len(ids) != len(want) {
		t.Fatalf("builtin count=%d want=%d: %v", len(ids), len(want), ids)
	}
	for index := range want {
		if ids[index] != want[index] {
			t.Fatalf("builtin ids=%v want=%v", ids, want)
		}
	}
}

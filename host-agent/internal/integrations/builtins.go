package integrations

// DeclaredAdapter reserves a compile-time integration contract without advertising or executing
// the future capability. I5 implementations must replace declarations with concrete adapters.
type DeclaredAdapter struct{ Definition Descriptor }

func (a DeclaredAdapter) Descriptor() Descriptor { return a.Definition }

func BuiltinDeclarations() []Adapter {
	definitions := []Descriptor{
		descriptor("git.status", "READ", "READ_ONLY", "SHORT", "git-status", "git.read"),
		descriptor("git.diff", "READ", "READ_ONLY", "MEDIUM", "unified-diff", "git.read"),
		descriptor("git.log", "READ", "READ_ONLY", "SHORT", "commit-list", "git.read"),
		descriptor("files.list", "READ", "READ_ONLY", "SHORT", "file-list", "files.read"),
		descriptor("files.read", "READ", "READ_ONLY", "SHORT", "source", "files.read"),
		descriptor("files.upload", "MUTATE_WORKSPACE", "MUTATING", "MEDIUM", "upload", "files.write"),
		descriptor("system.cpu", "READ", "READ_ONLY", "SHORT", "metric", "system.read"),
		descriptor("system.memory", "READ", "READ_ONLY", "SHORT", "metric", "system.read"),
		descriptor("system.disk", "READ", "READ_ONLY", "SHORT", "metric", "system.read"),
		descriptor("system.gpu", "READ", "READ_ONLY", "SHORT", "metric", "system.read"),
		descriptor("system.network", "READ", "READ_ONLY", "SHORT", "metric", "system.read"),
		descriptor("systemd.status", "READ", "READ_ONLY", "SHORT", "service-status", "systemd.read"),
		descriptor("mathematica.status", "READ", "READ_ONLY", "SHORT", "status", "mathematica.read"),
		descriptor("mathematica.evaluate", "PROCESS_CONTROL", "MUTATING", "LONG", "notebook-result", "mathematica.evaluate"),
		descriptor("vscode.openWorkspace", "PROCESS_CONTROL", "MUTATING", "MEDIUM", "desktop-action", "desktop.launch"),
	}
	result := make([]Adapter, 0, len(definitions))
	for _, definition := range definitions {
		result = append(result, DeclaredAdapter{Definition: definition})
	}
	return result
}

func descriptor(id, risk, mutability, timeout, hint, capability string) Descriptor {
	return Descriptor{
		ID: id, Version: "1", RiskLevel: risk, Mutability: mutability, TimeoutClass: timeout,
		InputSchema:      `{"type":"object","additionalProperties":false}`,
		OutputSchema:     `{"type":"object"}`,
		PresentationHint: hint, RequiredCapabilities: []string{capability},
	}
}

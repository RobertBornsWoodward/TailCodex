package services

import (
	"context"
	"strings"
)

type AdapterStatus struct {
	Installed bool   `json:"installed"`
	Active    bool   `json:"active"`
	Starting  bool   `json:"starting"`
	Detail    string `json:"detail,omitempty"`
}

type LifecycleAdapter interface {
	Name() string
	Status(context.Context) AdapterStatus
	Start(context.Context) error
	Stop(context.Context) error
	Restart(context.Context) error
}

// SystemdLifecycleAdapter is the stable Codex lifecycle implementation name used by protocol
// documentation. The alias preserves the concise original constructor without duplicating code.
type SystemdLifecycleAdapter = SystemdAdapter

type SystemdAdapter struct {
	Runner CommandRunner
	Unit   string
}

func (a SystemdAdapter) Name() string { return "systemd" }

func (a SystemdAdapter) Status(ctx context.Context) AdapterStatus {
	result, err := a.Runner.Run(ctx, "systemctl", "--user", "show", a.Unit, "--no-page",
		"--property=LoadState,ActiveState,SubState,MainPID")
	values := parseProperties(result.Stdout)
	load := values["LoadState"]
	active := values["ActiveState"]
	sub := values["SubState"]
	return AdapterStatus{
		Installed: load == "loaded",
		Active:    active == "active",
		Starting:  active == "activating" || sub == "start" || sub == "start-pre",
		Detail:    strings.TrimSpace(firstNonEmpty(sub, active, result.Stderr, errorText(err))),
	}
}

func (a SystemdAdapter) Start(ctx context.Context) error {
	_, err := a.Runner.Run(ctx, "systemctl", "--user", "start", a.Unit)
	return err
}

func (a SystemdAdapter) Stop(ctx context.Context) error {
	_, err := a.Runner.Run(ctx, "systemctl", "--user", "stop", a.Unit)
	return err
}

func (a SystemdAdapter) Restart(ctx context.Context) error {
	_, err := a.Runner.Run(ctx, "systemctl", "--user", "restart", a.Unit)
	return err
}

func parseProperties(output string) map[string]string {
	result := map[string]string{}
	for _, line := range strings.Split(output, "\n") {
		key, value, ok := strings.Cut(line, "=")
		if ok {
			result[key] = value
		}
	}
	return result
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

func errorText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

package services

import (
	"context"
	"os/exec"
	"strings"
)

type NativeDaemonAdapter struct {
	Runner  CommandRunner
	Command string
}

// NativeDaemonLifecycleAdapter remains experimental and is mutation-gated by the Host Agent flag.
type NativeDaemonLifecycleAdapter = NativeDaemonAdapter

func (a NativeDaemonAdapter) Name() string { return "native" }

func (a NativeDaemonAdapter) Status(ctx context.Context) AdapterStatus {
	command := a.Command
	if command == "" {
		command = "codex"
	}
	_, lookupErr := exec.LookPath(command)
	if lookupErr != nil {
		return AdapterStatus{Installed: false, Detail: lookupErr.Error()}
	}
	result, err := a.Runner.Run(ctx, command, "app-server", "daemon", "version")
	return AdapterStatus{
		Installed: true,
		Active:    err == nil && strings.TrimSpace(result.Stdout) != "",
		Detail:    firstNonEmpty(result.Stdout, result.Stderr, errorText(err)),
	}
}

func (a NativeDaemonAdapter) Start(ctx context.Context) error {
	_, err := a.Runner.Run(ctx, a.command(), "app-server", "daemon", "start")
	return err
}

func (a NativeDaemonAdapter) Stop(ctx context.Context) error {
	_, err := a.Runner.Run(ctx, a.command(), "app-server", "daemon", "stop")
	return err
}

func (a NativeDaemonAdapter) Restart(ctx context.Context) error {
	_, err := a.Runner.Run(ctx, a.command(), "app-server", "daemon", "restart")
	return err
}

func (a NativeDaemonAdapter) command() string {
	if a.Command == "" {
		return "codex"
	}
	return a.Command
}

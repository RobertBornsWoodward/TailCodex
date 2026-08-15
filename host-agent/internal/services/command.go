package services

import (
	"bytes"
	"context"
	"os/exec"
)

type CommandResult struct {
	Stdout string
	Stderr string
	Code   int
}

type CommandRunner interface {
	Run(context.Context, string, ...string) (CommandResult, error)
}

type ExecRunner struct{}

func (ExecRunner) Run(ctx context.Context, command string, args ...string) (CommandResult, error) {
	cmd := exec.CommandContext(ctx, command, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	result := CommandResult{Stdout: stdout.String(), Stderr: stderr.String()}
	if exit := cmd.ProcessState; exit != nil {
		result.Code = exit.ExitCode()
	}
	return result, err
}

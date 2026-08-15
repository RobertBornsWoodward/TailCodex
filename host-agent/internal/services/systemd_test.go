package services

import (
	"context"
	"errors"
	"reflect"
	"testing"
)

type recordedCall struct {
	command string
	args    []string
}

type fakeRunner struct {
	result CommandResult
	err    error
	calls  []recordedCall
}

func (r *fakeRunner) Run(_ context.Context, command string, args ...string) (CommandResult, error) {
	r.calls = append(r.calls, recordedCall{command: command, args: append([]string(nil), args...)})
	return r.result, r.err
}

func TestSystemdStatusAndTypedCommands(t *testing.T) {
	t.Parallel()
	runner := &fakeRunner{result: CommandResult{Stdout: "MainPID=123\nLoadState=loaded\nActiveState=active\nSubState=running\n"}}
	adapter := SystemdAdapter{Runner: runner, Unit: "tailcodex-app-server.service"}
	status := adapter.Status(context.Background())
	if !status.Installed || !status.Active || status.Starting {
		t.Fatalf("unexpected status: %+v", status)
	}
	runner.result = CommandResult{}
	if err := adapter.Restart(context.Background()); err != nil {
		t.Fatal(err)
	}
	last := runner.calls[len(runner.calls)-1]
	want := []string{"--user", "restart", "tailcodex-app-server.service"}
	if last.command != "systemctl" || !reflect.DeepEqual(last.args, want) {
		t.Fatalf("command was not structured: %+v", last)
	}
}

func TestSystemdStartPropagatesFailure(t *testing.T) {
	t.Parallel()
	runner := &fakeRunner{err: errors.New("denied")}
	adapter := SystemdAdapter{Runner: runner, Unit: "tailcodex-app-server.service"}
	if err := adapter.Start(context.Background()); err == nil {
		t.Fatal("start failure was swallowed")
	}
}

func TestSystemdUnitMetacharactersRemainOneArgument(t *testing.T) {
	t.Parallel()
	runner := &fakeRunner{}
	unit := "tailcodex.service;touch /tmp/should-not-run"
	adapter := SystemdAdapter{Runner: runner, Unit: unit}
	if err := adapter.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	last := runner.calls[len(runner.calls)-1]
	want := []string{"--user", "start", unit}
	if last.command != "systemctl" || !reflect.DeepEqual(last.args, want) {
		t.Fatalf("unit was interpreted instead of passed as one argv element: %+v", last)
	}
}

package main

import "testing"

func TestServeRequiresUnprivilegedUser(t *testing.T) {
	t.Parallel()
	if err := requireUnprivileged(0); err == nil {
		t.Fatal("root execution was accepted")
	}
	if err := requireUnprivileged(1000); err != nil {
		t.Fatalf("ordinary user was rejected: %v", err)
	}
}

func TestListenAddressMustBeLoopback(t *testing.T) {
	t.Parallel()
	for _, address := range []string{"127.0.0.1:4510", "[::1]:4510", "localhost:4510"} {
		if err := requireLoopback(address); err != nil {
			t.Errorf("loopback %q rejected: %v", address, err)
		}
	}
	for _, address := range []string{"0.0.0.0:4510", "[::]:4510", "100.64.0.1:4510", "arch.example:4510"} {
		if err := requireLoopback(address); err == nil {
			t.Errorf("non-loopback %q accepted", address)
		}
	}
}

func TestSystemdUnitRejectsOptionAndMetacharacterInjection(t *testing.T) {
	t.Parallel()
	for _, unit := range []string{"tailcodex-app-server.service", "tailcodex@app.service"} {
		if err := validateSystemdUnit(unit); err != nil {
			t.Errorf("valid unit %q rejected: %v", unit, err)
		}
	}
	for _, unit := range []string{
		"--system.service", "../escape.service", "tailcodex.service;touch-x", "tailcodex.socket", "",
	} {
		if err := validateSystemdUnit(unit); err == nil {
			t.Errorf("unsafe unit %q accepted", unit)
		}
	}
}

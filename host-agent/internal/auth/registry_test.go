package auth

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPairAuthenticateRotateAndRevoke(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "devices.json")
	registry := NewRegistry(path)
	ticket, err := registry.CreatePairing(10*time.Minute, []string{"desktop.launch", "codex.lifecycle", "codex.lifecycle"})
	if err != nil {
		t.Fatal(err)
	}
	device, credential, err := registry.Pair(ticket.Code, "phone-1", "Phone")
	if err != nil {
		t.Fatal(err)
	}
	if credential == "" || device.CredentialHash == "" {
		t.Fatal("pairing did not issue and hash a credential")
	}
	if got := strings.Join(device.Grants, ","); got != "codex.lifecycle,desktop.launch" {
		t.Fatalf("unexpected grants %q", got)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), ticket.Code) || strings.Contains(string(data), credential) {
		t.Fatal("registry persisted a raw pairing code or credential")
	}
	authenticated, err := registry.Authenticate(credential)
	if err != nil || authenticated.ID != device.ID {
		t.Fatalf("authenticate: device=%+v err=%v", authenticated, err)
	}
	rotated, err := registry.Rotate(device.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := registry.Authenticate(credential); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("old credential remained valid: %v", err)
	}
	if _, err := registry.Authenticate(rotated); err != nil {
		t.Fatalf("rotated credential invalid: %v", err)
	}
	if err := registry.Revoke(device.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := registry.Authenticate(rotated); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("revoked credential remained valid: %v", err)
	}
}

func TestPairingIsOneTimeAndExpires(t *testing.T) {
	t.Parallel()
	registry := NewRegistry(filepath.Join(t.TempDir(), "devices.json"))
	now := time.Date(2026, 8, 15, 0, 0, 0, 0, time.UTC)
	registry.now = func() time.Time { return now }
	ticket, err := registry.CreatePairing(time.Minute, []string{"codex.lifecycle"})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := registry.Pair(ticket.Code, "phone-1", "Phone"); err != nil {
		t.Fatal(err)
	}
	if _, _, err := registry.Pair(ticket.Code, "phone-2", "Tablet"); !errors.Is(err, ErrInvalidPairing) {
		t.Fatalf("pairing code was reusable: %v", err)
	}

	expiring, err := registry.CreatePairing(time.Minute, nil)
	if err != nil {
		t.Fatal(err)
	}
	now = now.Add(2 * time.Minute)
	if _, _, err := registry.Pair(expiring.Code, "phone-2", "Tablet"); !errors.Is(err, ErrInvalidPairing) {
		t.Fatalf("expired pairing code was accepted: %v", err)
	}
}

func TestActiveDeviceCannotBeSilentlyReplaced(t *testing.T) {
	t.Parallel()
	registry := NewRegistry(filepath.Join(t.TempDir(), "devices.json"))
	first, _ := registry.CreatePairing(time.Minute, nil)
	if _, _, err := registry.Pair(first.Code, "phone-1", "Phone"); err != nil {
		t.Fatal(err)
	}
	second, _ := registry.CreatePairing(time.Minute, nil)
	if _, _, err := registry.Pair(second.Code, "phone-1", "Impostor"); !errors.Is(err, ErrDeviceExists) {
		t.Fatalf("active device was replaced: %v", err)
	}
}

func TestRegistryPermissions(t *testing.T) {
	t.Parallel()
	directory := filepath.Join(t.TempDir(), "private")
	path := filepath.Join(directory, "devices.json")
	registry := NewRegistry(path)
	if _, err := registry.CreatePairing(time.Minute, nil); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("registry permissions = %o", info.Mode().Perm())
	}
}

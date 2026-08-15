package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"syscall"
	"time"
)

var deviceIDPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{3,128}$`)

var (
	ErrInvalidPairing = errors.New("invalid or expired pairing code")
	ErrUnauthorized   = errors.New("invalid device credential")
	ErrDeviceNotFound = errors.New("device not found")
	ErrDeviceExists   = errors.New("device already exists")
)

type Device struct {
	ID             string    `json:"id"`
	Name           string    `json:"name"`
	CredentialHash string    `json:"credentialHash"`
	Grants         []string  `json:"grants"`
	CreatedAt      time.Time `json:"createdAt"`
	UpdatedAt      time.Time `json:"updatedAt"`
	LastSeenAt     time.Time `json:"lastSeenAt,omitempty"`
	RevokedAt      time.Time `json:"revokedAt,omitempty"`
}

type Pairing struct {
	ID        string    `json:"id"`
	CodeHash  string    `json:"codeHash"`
	Grants    []string  `json:"grants"`
	CreatedAt time.Time `json:"createdAt"`
	ExpiresAt time.Time `json:"expiresAt"`
}

type PairingTicket struct {
	Code      string    `json:"code"`
	ExpiresAt time.Time `json:"expiresAt"`
	Grants    []string  `json:"grants"`
}

type registryState struct {
	Devices  map[string]Device  `json:"devices"`
	Pairings map[string]Pairing `json:"pairings"`
}

type Registry struct {
	path     string
	lockPath string
	now      func() time.Time
}

func NewRegistry(path string) *Registry {
	return &Registry{path: path, lockPath: path + ".lock", now: time.Now}
}

func (r *Registry) CreatePairing(ttl time.Duration, grants []string) (PairingTicket, error) {
	if ttl <= 0 || ttl > time.Hour {
		return PairingTicket{}, errors.New("pairing ttl must be between zero and one hour")
	}
	code, err := randomPairingCode()
	if err != nil {
		return PairingTicket{}, err
	}
	id, err := randomID("pair_")
	if err != nil {
		return PairingTicket{}, err
	}
	now := r.now().UTC()
	grants = normalizedStrings(grants)
	err = r.update(func(state *registryState) error {
		prunePairings(state, now)
		state.Pairings[id] = Pairing{
			ID: id, CodeHash: hashSecret(code), Grants: grants,
			CreatedAt: now, ExpiresAt: now.Add(ttl),
		}
		return nil
	})
	if err != nil {
		return PairingTicket{}, err
	}
	return PairingTicket{Code: code, ExpiresAt: now.Add(ttl), Grants: grants}, nil
}

func (r *Registry) Pair(code, deviceID, name string) (Device, string, error) {
	code = strings.ToUpper(strings.TrimSpace(code))
	deviceID = strings.TrimSpace(deviceID)
	name = strings.TrimSpace(name)
	if !deviceIDPattern.MatchString(deviceID) || name == "" || len(name) > 120 {
		return Device{}, "", errors.New("invalid device identity")
	}
	credential, err := randomCredential()
	if err != nil {
		return Device{}, "", err
	}
	now := r.now().UTC()
	var paired Device
	err = r.update(func(state *registryState) error {
		prunePairings(state, now)
		if existing, ok := state.Devices[deviceID]; ok && existing.RevokedAt.IsZero() {
			return ErrDeviceExists
		}
		var selected Pairing
		var selectedID string
		candidate := hashSecret(code)
		for id, pairing := range state.Pairings {
			if subtle.ConstantTimeCompare([]byte(pairing.CodeHash), []byte(candidate)) == 1 {
				selected, selectedID = pairing, id
			}
		}
		if selectedID == "" {
			return ErrInvalidPairing
		}
		delete(state.Pairings, selectedID)
		paired = Device{
			ID: deviceID, Name: name, CredentialHash: hashSecret(credential),
			Grants: append([]string(nil), selected.Grants...), CreatedAt: now, UpdatedAt: now,
		}
		state.Devices[deviceID] = paired
		return nil
	})
	if err != nil {
		return Device{}, "", err
	}
	return paired, credential, nil
}

func (r *Registry) Authenticate(credential string) (Device, error) {
	credential = strings.TrimSpace(credential)
	if credential == "" {
		return Device{}, ErrUnauthorized
	}
	candidate := hashSecret(credential)
	var found Device
	err := r.update(func(state *registryState) error {
		for id, device := range state.Devices {
			if device.RevokedAt.IsZero() && subtle.ConstantTimeCompare([]byte(device.CredentialHash), []byte(candidate)) == 1 {
				now := r.now().UTC()
				if device.LastSeenAt.IsZero() || now.Sub(device.LastSeenAt) >= time.Minute {
					device.LastSeenAt = now
					state.Devices[id] = device
				}
				found = device
			}
		}
		if found.ID == "" {
			return ErrUnauthorized
		}
		return nil
	})
	return found, err
}

func (r *Registry) ListDevices() ([]Device, error) {
	state, err := r.readLocked()
	if err != nil {
		return nil, err
	}
	devices := make([]Device, 0, len(state.Devices))
	for _, device := range state.Devices {
		device.CredentialHash = ""
		devices = append(devices, device)
	}
	sort.Slice(devices, func(i, j int) bool { return devices[i].CreatedAt.Before(devices[j].CreatedAt) })
	return devices, nil
}

func (r *Registry) Revoke(deviceID string) error {
	return r.update(func(state *registryState) error {
		device, ok := state.Devices[deviceID]
		if !ok {
			return ErrDeviceNotFound
		}
		device.RevokedAt = r.now().UTC()
		device.UpdatedAt = device.RevokedAt
		state.Devices[deviceID] = device
		return nil
	})
}

func (r *Registry) Rotate(deviceID string) (string, error) {
	credential, err := randomCredential()
	if err != nil {
		return "", err
	}
	err = r.update(func(state *registryState) error {
		device, ok := state.Devices[deviceID]
		if !ok || !device.RevokedAt.IsZero() {
			return ErrDeviceNotFound
		}
		device.CredentialHash = hashSecret(credential)
		device.UpdatedAt = r.now().UTC()
		state.Devices[deviceID] = device
		return nil
	})
	return credential, err
}

func HasGrant(device Device, grant string) bool {
	for _, candidate := range device.Grants {
		if candidate == grant {
			return true
		}
	}
	return false
}

func (r *Registry) update(fn func(*registryState) error) error {
	return r.withFileLock(func() error {
		state, err := r.read()
		if err != nil {
			return err
		}
		if err := fn(&state); err != nil {
			return err
		}
		return r.write(state)
	})
}

func (r *Registry) readLocked() (registryState, error) {
	var result registryState
	err := r.withFileLock(func() error {
		var err error
		result, err = r.read()
		return err
	})
	return result, err
}

func (r *Registry) withFileLock(fn func() error) error {
	if err := os.MkdirAll(filepath.Dir(r.path), 0o700); err != nil {
		return err
	}
	lock, err := os.OpenFile(r.lockPath, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); err != nil {
		return err
	}
	defer syscall.Flock(int(lock.Fd()), syscall.LOCK_UN) //nolint:errcheck
	return fn()
}

func (r *Registry) read() (registryState, error) {
	state := registryState{Devices: map[string]Device{}, Pairings: map[string]Pairing{}}
	data, err := os.ReadFile(r.path)
	if errors.Is(err, os.ErrNotExist) {
		return state, nil
	}
	if err != nil {
		return state, err
	}
	if err := json.Unmarshal(data, &state); err != nil {
		return state, fmt.Errorf("decode registry: %w", err)
	}
	if state.Devices == nil {
		state.Devices = map[string]Device{}
	}
	if state.Pairings == nil {
		state.Pairings = map[string]Pairing{}
	}
	return state, nil
}

func (r *Registry) write(state registryState) error {
	data, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(r.path), ".devices-*.json")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return err
	}
	if _, err := temp.Write(data); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(tempName, r.path)
}

func prunePairings(state *registryState, now time.Time) {
	for id, pairing := range state.Pairings {
		if !now.Before(pairing.ExpiresAt) {
			delete(state.Pairings, id)
		}
	}
}

func normalizedStrings(values []string) []string {
	seen := map[string]struct{}{}
	var result []string
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if _, ok := seen[value]; ok {
			continue
		}
		seen[value] = struct{}{}
		result = append(result, value)
	}
	sort.Strings(result)
	return result
}

func hashSecret(secret string) string {
	sum := sha256.Sum256([]byte(secret))
	return hex.EncodeToString(sum[:])
}

func randomCredential() (string, error) {
	bytes := make([]byte, 32)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return "tcx1_" + base64.RawURLEncoding.EncodeToString(bytes), nil
}

func randomID(prefix string) (string, error) {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return prefix + hex.EncodeToString(bytes), nil
}

func randomPairingCode() (string, error) {
	const alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
	bytes := make([]byte, 10)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	for i := range bytes {
		bytes[i] = alphabet[int(bytes[i])%len(alphabet)]
	}
	return string(bytes[:5]) + "-" + string(bytes[5:]), nil
}

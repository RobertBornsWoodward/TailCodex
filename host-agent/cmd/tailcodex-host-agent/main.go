package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"syscall"
	"time"

	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/api"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/audit"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/auth"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/codex"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/events"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/operations"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/services"
)

var supportedFeatures = []string{"codex.lifecycle", "host.logs"}
var systemdUnitPattern = regexp.MustCompile(`^[A-Za-z0-9_.:@-]{1,180}\.service$`)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stderr, nil))
	if err := run(os.Args[1:], logger); err != nil {
		logger.Error("host agent failed", "error", err)
		os.Exit(1)
	}
}

func run(args []string, logger *slog.Logger) error {
	command := "serve"
	if len(args) > 0 {
		command = args[0]
		args = args[1:]
	}
	switch command {
	case "serve":
		return runServe(args, logger)
	case "pair":
		return runPair(args)
	case "devices":
		return runDevices(args)
	case "version":
		fmt.Println(api.AgentVersion)
		return nil
	case "help", "-h", "--help":
		printUsage()
		return nil
	default:
		return fmt.Errorf("unknown command %q", command)
	}
}

func runServe(args []string, logger *slog.Logger) error {
	if err := requireUnprivileged(os.Geteuid()); err != nil {
		return err
	}
	flags := flag.NewFlagSet("serve", flag.ContinueOnError)
	listen := flags.String("listen", envOr("TAILCODEX_AGENT_LISTEN", "127.0.0.1:4510"), "loopback listen address")
	stateDir := flags.String("state-dir", defaultStateDir(), "private Host Agent state directory")
	unit := flags.String("codex-systemd-unit", envOr("TAILCODEX_CODEX_UNIT", "tailcodex-app-server.service"), "managed Codex systemd user unit")
	nativeEnabled := flags.Bool("enable-native-daemon", false, "allow experimental Codex native daemon lifecycle mutations")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if err := requireLoopback(*listen); err != nil {
		return err
	}
	if err := validateSystemdUnit(*unit); err != nil {
		return err
	}
	if err := os.MkdirAll(*stateDir, 0o700); err != nil {
		return err
	}
	if err := os.Chmod(*stateDir, 0o700); err != nil {
		return err
	}

	registry := auth.NewRegistry(filepath.Join(*stateDir, "devices.json"))
	auditLog := audit.New(filepath.Join(*stateDir, "audit.jsonl"))
	eventHub := events.New()
	operationManager, err := operations.New(filepath.Join(*stateDir, "operations.json"), func(op operations.Operation) {
		eventHub.Publish("operation.updated", op)
		if op.Status == operations.Succeeded || op.Status == operations.Failed || op.Status == operations.Cancelled {
			outcome := strings.ToLower(string(op.Status))
			_ = auditLog.Append(audit.Entry{
				OperationID: op.ID, Actor: op.DeviceID, Action: op.Kind,
				RiskLevel: op.RiskLevel, Outcome: outcome,
			})
		}
	})
	if err != nil {
		return fmt.Errorf("load operations: %w", err)
	}
	runner := services.ExecRunner{}
	lifecycle := &codex.Manager{
		Systemd:      services.SystemdLifecycleAdapter{Runner: runner, Unit: *unit},
		Native:       services.NativeDaemonLifecycleAdapter{Runner: runner, Command: "codex"},
		Probe:        codex.HTTPProbe{Address: "127.0.0.1:4500", ReadyURL: "http://127.0.0.1:4500/readyz"},
		NativeEnable: *nativeEnabled,
		ReadyURL:     "http://127.0.0.1:4500/readyz",
	}
	apiServer, err := api.NewServer(api.Dependencies{
		Registry: registry, Operations: operationManager, Lifecycle: lifecycle,
		Events: eventHub, Audit: auditLog, Logger: logger, Features: supportedFeatures,
	})
	if err != nil {
		return err
	}
	httpServer := &http.Server{
		Addr: *listen, Handler: apiServer.Handler(),
		ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second,
		WriteTimeout: 35 * time.Second, IdleTimeout: 75 * time.Second,
	}
	listener, err := net.Listen("tcp", *listen)
	if err != nil {
		return err
	}
	logger.Info("host agent listening", "address", *listen, "stateDir", *stateDir, "nativeDaemonEnabled", *nativeEnabled)
	serverErrors := make(chan error, 1)
	go func() {
		if err := httpServer.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErrors <- err
		}
		close(serverErrors)
	}()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return httpServer.Shutdown(shutdownCtx)
	case err := <-serverErrors:
		return err
	}
}

func requireUnprivileged(euid int) error {
	if euid == 0 {
		return errors.New("TailCodex Host Agent must run as an unprivileged user")
	}
	return nil
}

func runPair(args []string) error {
	flags := flag.NewFlagSet("pair", flag.ContinueOnError)
	stateDir := flags.String("state-dir", defaultStateDir(), "private Host Agent state directory")
	ttl := flags.Duration("ttl", 10*time.Minute, "pairing code lifetime")
	grantsValue := flags.String("grants", "codex.lifecycle,host.logs", "comma-separated initial grants")
	jsonOutput := flags.Bool("json", false, "emit JSON")
	if err := flags.Parse(args); err != nil {
		return err
	}
	grants, err := validateGrants(strings.Split(*grantsValue, ","))
	if err != nil {
		return err
	}
	registry := auth.NewRegistry(filepath.Join(*stateDir, "devices.json"))
	ticket, err := registry.CreatePairing(*ttl, grants)
	if err != nil {
		return err
	}
	if *jsonOutput {
		return json.NewEncoder(os.Stdout).Encode(ticket)
	}
	fmt.Printf("Pairing code: %s\nExpires: %s\nGrants: %s\n", ticket.Code, ticket.ExpiresAt.Format(time.RFC3339), strings.Join(ticket.Grants, ", "))
	return nil
}

func runDevices(args []string) error {
	if len(args) == 0 {
		return errors.New("devices requires list, revoke, or rotate")
	}
	action := args[0]
	flags := flag.NewFlagSet("devices "+action, flag.ContinueOnError)
	stateDir := flags.String("state-dir", defaultStateDir(), "private Host Agent state directory")
	jsonOutput := flags.Bool("json", false, "emit JSON")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	registry := auth.NewRegistry(filepath.Join(*stateDir, "devices.json"))
	switch action {
	case "list":
		devices, err := registry.ListDevices()
		if err != nil {
			return err
		}
		if *jsonOutput {
			return json.NewEncoder(os.Stdout).Encode(devices)
		}
		for _, device := range devices {
			status := "active"
			if !device.RevokedAt.IsZero() {
				status = "revoked"
			}
			fmt.Printf("%s\t%s\t%s\t%s\n", device.ID, device.Name, status, strings.Join(device.Grants, ","))
		}
		return nil
	case "revoke":
		if flags.NArg() != 1 {
			return errors.New("devices revoke requires a device ID")
		}
		return registry.Revoke(flags.Arg(0))
	case "rotate":
		if flags.NArg() != 1 {
			return errors.New("devices rotate requires a device ID")
		}
		credential, err := registry.Rotate(flags.Arg(0))
		if err != nil {
			return err
		}
		if *jsonOutput {
			return json.NewEncoder(os.Stdout).Encode(map[string]string{"deviceId": flags.Arg(0), "credential": credential})
		}
		fmt.Printf("New credential for %s: %s\nStore it immediately; it will not be shown again.\n", flags.Arg(0), credential)
		return nil
	default:
		return fmt.Errorf("unknown devices action %q", action)
	}
}

func validateGrants(values []string) ([]string, error) {
	allowed := map[string]bool{}
	for _, feature := range supportedFeatures {
		allowed[feature] = true
	}
	seen := map[string]bool{}
	var grants []string
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if !allowed[value] {
			return nil, fmt.Errorf("unsupported grant %q", value)
		}
		if !seen[value] {
			seen[value] = true
			grants = append(grants, value)
		}
	}
	sort.Strings(grants)
	return grants, nil
}

func requireLoopback(address string) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return fmt.Errorf("invalid listen address: %w", err)
	}
	if strings.EqualFold(host, "localhost") {
		return nil
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return fmt.Errorf("Host Agent refuses non-loopback listen address %q", address)
	}
	return nil
}

func validateSystemdUnit(unit string) error {
	if !systemdUnitPattern.MatchString(unit) || strings.HasPrefix(unit, "-") || strings.Contains(unit, "..") {
		return fmt.Errorf("invalid Codex systemd user unit %q", unit)
	}
	return nil
}

func defaultStateDir() string {
	if configured := strings.TrimSpace(os.Getenv("TAILCODEX_AGENT_STATE_DIR")); configured != "" {
		return configured
	}
	configHome, err := os.UserConfigDir()
	if err != nil {
		home, _ := os.UserHomeDir()
		configHome = filepath.Join(home, ".config")
	}
	return filepath.Join(configHome, "tailcodex-host-agent")
}

func envOr(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func printUsage() {
	fmt.Print(`TailCodex Host Agent

Usage:
  tailcodex-host-agent serve [options]
  tailcodex-host-agent pair [--ttl 10m] [--grants codex.lifecycle,host.logs]
  tailcodex-host-agent devices list
  tailcodex-host-agent devices revoke DEVICE_ID
  tailcodex-host-agent devices rotate DEVICE_ID
  tailcodex-host-agent version
`)
}

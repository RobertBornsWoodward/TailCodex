package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/audit"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/auth"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/codex"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/events"
	"github.com/RobertBornsWoodward/TailCodex/host-agent/internal/operations"
)

var (
	requestIDPattern      = regexp.MustCompile(`^[A-Za-z0-9._:-]{8,120}$`)
	idempotencyKeyPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{8,160}$`)
)

type Server struct {
	registry    *auth.Registry
	operations  *operations.Manager
	lifecycle   *codex.Manager
	events      *events.Hub
	audit       *audit.Logger
	logger      *slog.Logger
	startedAt   time.Time
	features    []string
	pairLimiter *pairRateLimiter
	mux         *http.ServeMux
}

type Dependencies struct {
	Registry   *auth.Registry
	Operations *operations.Manager
	Lifecycle  *codex.Manager
	Events     *events.Hub
	Audit      *audit.Logger
	Logger     *slog.Logger
	Features   []string
}

func NewServer(deps Dependencies) (*Server, error) {
	if deps.Registry == nil || deps.Operations == nil || deps.Lifecycle == nil || deps.Events == nil || deps.Audit == nil {
		return nil, errors.New("missing Host Agent server dependency")
	}
	if deps.Logger == nil {
		deps.Logger = slog.Default()
	}
	server := &Server{
		registry: deps.Registry, operations: deps.Operations, lifecycle: deps.Lifecycle,
		events: deps.Events, audit: deps.Audit, logger: deps.Logger,
		startedAt: time.Now().UTC(), features: append([]string(nil), deps.Features...),
		pairLimiter: newPairRateLimiter(), mux: http.NewServeMux(),
	}
	server.routes()
	return server, nil
}

func (s *Server) Handler() http.Handler {
	return securityHeaders(s.requestLog(s.mux))
}

func (s *Server) routes() {
	s.mux.HandleFunc("GET /v1/hello", s.handleHello)
	s.mux.HandleFunc("POST /v1/pair", s.handlePair)
	s.mux.Handle("GET /v1/health", s.authenticate(http.HandlerFunc(s.handleHealth)))
	s.mux.Handle("GET /v1/capabilities", s.authenticate(http.HandlerFunc(s.handleCapabilities)))
	s.mux.Handle("GET /v1/services", s.authenticate(http.HandlerFunc(s.handleServices)))
	s.mux.Handle("GET /v1/logs/summary", s.authenticate(http.HandlerFunc(s.handleLogSummary)))
	s.mux.Handle("POST /v1/actions/codex.ensure-running", s.authenticate(http.HandlerFunc(s.handleEnsureRunning)))
	s.mux.Handle("POST /v1/actions/codex.restart", s.authenticate(http.HandlerFunc(s.handleRestart)))
	s.mux.Handle("POST /v1/actions/codex.stop", s.authenticate(http.HandlerFunc(s.handleStop)))
	s.mux.Handle("POST /v1/actions/desktop.launch", s.authenticate(http.HandlerFunc(s.handleDesktopUnsupported)))
	s.mux.Handle("POST /v1/actions/desktop.focus", s.authenticate(http.HandlerFunc(s.handleDesktopUnsupported)))
	s.mux.Handle("GET /v1/operations/{operationId}", s.authenticate(http.HandlerFunc(s.handleOperation)))
	s.mux.Handle("GET /v1/events", s.authenticate(s.events))
}

func (s *Server) handleHello(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, HelloResponse{
		OK: true, ProtocolVersion: ProtocolVersion, AgentVersion: AgentVersion, MinClientVersion: MinClientVersion,
	})
}

func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	remoteIP := clientIP(r)
	if !s.pairLimiter.Allow(remoteIP, time.Now()) {
		writeError(w, http.StatusTooManyRequests, "PAIR_RATE_LIMITED", "too many pairing attempts")
		return
	}
	var request PairRequest
	if err := decodeJSON(w, r, &request); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", err.Error())
		return
	}
	device, credential, err := s.registry.Pair(request.Code, request.DeviceID, request.Name)
	if err != nil {
		_ = s.audit.Append(audit.Entry{Actor: "pairing", Action: "device.pair", RiskLevel: "PROCESS_CONTROL", Outcome: "denied", Metadata: map[string]string{"remoteIp": remoteIP}})
		writeError(w, http.StatusUnauthorized, "PAIRING_DENIED", "invalid or expired pairing code")
		return
	}
	_ = s.audit.Append(audit.Entry{Actor: device.ID, Action: "device.pair", RiskLevel: "PROCESS_CONTROL", Outcome: "succeeded"})
	writeJSON(w, http.StatusCreated, PairResponse{
		OK: true, DeviceID: device.ID, Credential: credential, Grants: device.Grants, PairedAt: device.CreatedAt,
	})
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	device := deviceFromContext(r.Context())
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "service": "tailcodex-host-agent", "agentVersion": AgentVersion,
		"deviceId": device.ID, "uptimeSeconds": int64(time.Since(s.startedAt).Seconds()), "now": time.Now().UTC(),
	})
}

func (s *Server) handleCapabilities(w http.ResponseWriter, r *http.Request) {
	device := deviceFromContext(r.Context())
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "features": s.features, "grants": device.Grants,
		"riskLevels":            []string{"READ", "MUTATE_WORKSPACE", "PROCESS_CONTROL", "FULL_TERMINAL"},
		"unsupportedRiskLevels": []string{"PRIVILEGED"},
	})
}

func (s *Server) handleServices(w http.ResponseWriter, r *http.Request) {
	snapshot := s.lifecycle.Detect(r.Context())
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true,
		"services": []any{
			map[string]any{"id": "host-agent", "state": "RUNNING", "version": AgentVersion},
			map[string]any{"id": "codex-app-server", "snapshot": snapshot},
		},
	})
}

func (s *Server) handleLogSummary(w http.ResponseWriter, r *http.Request) {
	if !s.hasFeature("host.logs") {
		writeError(w, http.StatusNotImplemented, "FEATURE_UNAVAILABLE", "host log summaries are unavailable")
		return
	}
	device := deviceFromContext(r.Context())
	if !auth.HasGrant(device, "host.logs") {
		writeError(w, http.StatusForbidden, "GRANT_REQUIRED", "host.logs grant is required")
		return
	}
	entries, err := s.audit.Recent(20)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "LOG_SUMMARY_FAILED", "could not read audit summary")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "entries": entries})
}

func (s *Server) handleEnsureRunning(w http.ResponseWriter, r *http.Request) {
	s.startLifecycleOperation(w, r, "codex.ensure-running", func(ctx context.Context) (codex.Snapshot, error) {
		return s.lifecycle.EnsureRunning(ctx)
	})
}

func (s *Server) handleRestart(w http.ResponseWriter, r *http.Request) {
	s.startLifecycleOperation(w, r, "codex.restart", func(ctx context.Context) (codex.Snapshot, error) {
		return s.lifecycle.Restart(ctx)
	})
}

func (s *Server) handleStop(w http.ResponseWriter, r *http.Request) {
	s.startLifecycleOperation(w, r, "codex.stop", func(ctx context.Context) (codex.Snapshot, error) {
		return s.lifecycle.Stop(ctx)
	})
}

func (s *Server) startLifecycleOperation(
	w http.ResponseWriter,
	r *http.Request,
	kind string,
	work func(context.Context) (codex.Snapshot, error),
) {
	device := deviceFromContext(r.Context())
	if !auth.HasGrant(device, "codex.lifecycle") {
		writeError(w, http.StatusForbidden, "GRANT_REQUIRED", "codex.lifecycle grant is required")
		return
	}
	var request ActionRequest
	if err := decodeJSON(w, r, &request); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", err.Error())
		return
	}
	if !requestIDPattern.MatchString(request.RequestID) {
		writeError(w, http.StatusBadRequest, "INVALID_REQUEST_ID", "requestId must be 8-120 safe characters")
		return
	}
	idempotencyKey := strings.TrimSpace(r.Header.Get("Idempotency-Key"))
	if !idempotencyKeyPattern.MatchString(idempotencyKey) {
		writeError(w, http.StatusBadRequest, "IDEMPOTENCY_KEY_REQUIRED", "a valid Idempotency-Key header is required")
		return
	}
	op, duplicate, err := s.operations.Start(
		device.ID, idempotencyKey, kind, "PROCESS_CONTROL",
		map[string]string{"requestId": request.RequestID},
		func(parent context.Context) (any, *operations.OperationError) {
			ctx, cancel := context.WithTimeout(parent, 30*time.Second)
			defer cancel()
			snapshot, err := work(ctx)
			if err != nil {
				code, message := codex.ErrorDetails(err)
				return nil, &operations.OperationError{Code: code, Message: message}
			}
			return snapshot, nil
		},
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "OPERATION_CREATE_FAILED", err.Error())
		return
	}
	_ = s.audit.Append(audit.Entry{
		RequestID: request.RequestID, OperationID: op.ID, Actor: device.ID, Action: kind,
		RiskLevel: "PROCESS_CONTROL", Outcome: map[bool]string{true: "duplicate", false: "accepted"}[duplicate],
	})
	writeJSON(w, http.StatusAccepted, OperationAcceptedResponse{
		OK: true, OperationID: op.ID, Status: string(op.Status), Duplicate: duplicate,
	})
}

func (s *Server) handleOperation(w http.ResponseWriter, r *http.Request) {
	device := deviceFromContext(r.Context())
	id := r.PathValue("operationId")
	op, ok := s.operations.Get(id)
	if !ok || op.DeviceID != device.ID {
		writeError(w, http.StatusNotFound, "OPERATION_NOT_FOUND", "operation was not found")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "operation": op})
}

func (s *Server) handleDesktopUnsupported(w http.ResponseWriter, r *http.Request) {
	if !s.hasFeature("desktop.launch") {
		writeError(w, http.StatusNotImplemented, "FEATURE_UNAVAILABLE", "desktop control is reserved for Host Agent I4")
		return
	}
	device := deviceFromContext(r.Context())
	if !auth.HasGrant(device, "desktop.launch") {
		writeError(w, http.StatusForbidden, "GRANT_REQUIRED", "desktop.launch grant is required")
		return
	}
	var request DesktopActionRequest
	if err := decodeJSON(w, r, &request); err != nil {
		writeError(w, http.StatusBadRequest, "BAD_REQUEST", err.Error())
		return
	}
	writeError(w, http.StatusNotImplemented, "FEATURE_UNAVAILABLE", "desktop control is reserved for Host Agent I4")
}

func (s *Server) hasFeature(feature string) bool {
	for _, candidate := range s.features {
		if candidate == feature {
			return true
		}
	}
	return false
}

func (s *Server) authenticate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		credential := bearerToken(r.Header.Get("Authorization"))
		device, err := s.registry.Authenticate(credential)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "valid device credential required")
			return
		}
		ctx := context.WithValue(r.Context(), deviceContextKey{}, device)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (s *Server) requestLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		s.logger.Info("http request", "method", r.Method, "path", r.URL.Path, "durationMs", time.Since(start).Milliseconds())
	})
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		next.ServeHTTP(w, r)
	})
}

func decodeJSON(w http.ResponseWriter, r *http.Request, destination any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 64*1024)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return fmt.Errorf("invalid JSON: %w", err)
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		if err == nil {
			return errors.New("multiple JSON values are not allowed")
		}
		return fmt.Errorf("invalid trailing JSON: %w", err)
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, ErrorResponse{OK: false, Code: code, Message: message})
}

func bearerToken(value string) string {
	prefix, token, ok := strings.Cut(strings.TrimSpace(value), " ")
	if !ok || !strings.EqualFold(prefix, "Bearer") {
		return ""
	}
	return strings.TrimSpace(token)
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

type deviceContextKey struct{}

func deviceFromContext(ctx context.Context) auth.Device {
	device, _ := ctx.Value(deviceContextKey{}).(auth.Device)
	return device
}

type pairRateLimiter struct {
	mu      sync.Mutex
	entries map[string][]time.Time
}

func newPairRateLimiter() *pairRateLimiter {
	return &pairRateLimiter{entries: map[string][]time.Time{}}
}

func (l *pairRateLimiter) Allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	cutoff := now.Add(-time.Minute)
	rows := l.entries[key][:0]
	for _, timestamp := range l.entries[key] {
		if timestamp.After(cutoff) {
			rows = append(rows, timestamp)
		}
	}
	if len(rows) >= 10 {
		l.entries[key] = rows
		return false
	}
	l.entries[key] = append(rows, now)
	return true
}

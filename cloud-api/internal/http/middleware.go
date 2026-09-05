package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"log/slog"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/go-chi/chi/v5"
)

const (
	requestIDHeader = "X-Request-ID"
	maxVisitors     = 10_000
	visitorTTL      = 10 * time.Minute
)

var requestIDPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$`)

type requestIDKey struct{}

// RequestIDFromContext returns the sanitized request identifier.
func RequestIDFromContext(ctx context.Context) string {
	value, _ := ctx.Value(requestIDKey{}).(string)
	return value
}

// RequestID accepts a safe caller identifier or creates a cryptographically
// random value. Invalid values are never copied into headers or logs.
func RequestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestID := strings.TrimSpace(r.Header.Get(requestIDHeader))
		if !requestIDPattern.MatchString(requestID) {
			requestID = newRequestID()
		}
		w.Header().Set(requestIDHeader, requestID)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), requestIDKey{}, requestID)))
	})
}

func newRequestID() string {
	var value [16]byte
	if _, err := rand.Read(value[:]); err == nil {
		return hex.EncodeToString(value[:])
	}
	return strconv.FormatInt(time.Now().UnixNano(), 36)
}

// Recoverer converts panics to a stable response without exposing internals.
func Recoverer(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			defer func() {
				if recovered := recover(); recovered != nil {
					logger.Error("request panic recovered", "request_id", RequestIDFromContext(r.Context()))
					writeError(w, r, http.StatusInternalServerError, "internal_error")
				}
			}()
			next.ServeHTTP(w, r)
		})
	}
}

type statusWriter struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (w *statusWriter) Unwrap() http.ResponseWriter {
	return w.ResponseWriter
}

func (w *statusWriter) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *statusWriter) Write(value []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	count, err := w.ResponseWriter.Write(value)
	w.bytes += count
	return count, err
}

// AccessLog records only bounded metadata. It intentionally excludes raw
// paths, query strings, headers, cookies, bodies, credentials, and client IPs.
func AccessLog(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			started := time.Now()
			writer := &statusWriter{ResponseWriter: w}
			next.ServeHTTP(writer, r)
			status := writer.status
			if status == 0 {
				status = http.StatusOK
			}
			logger.Info("http request",
				"request_id", RequestIDFromContext(r.Context()),
				"method", r.Method,
				"route", routePattern(r),
				"status", status,
				"response_bytes", writer.bytes,
				"duration_ms", time.Since(started).Milliseconds(),
			)
		})
	}
}

func routePattern(r *http.Request) string {
	if ctx := chi.RouteContext(r.Context()); ctx != nil {
		if pattern := ctx.RoutePattern(); pattern != "" {
			return pattern
		}
	}
	return "unmatched"
}

// CORS applies an exact-match origin allowlist and handles preflight locally.
func CORS(allowedOrigins []string) func(http.Handler) http.Handler {
	allowed := make(map[string]struct{}, len(allowedOrigins))
	for _, origin := range allowedOrigins {
		allowed[origin] = struct{}{}
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			if origin == "" {
				next.ServeHTTP(w, r)
				return
			}
			addVary(w.Header(), "Origin")
			if _, ok := allowed[origin]; !ok {
				writeError(w, r, http.StatusForbidden, "origin_not_allowed")
				return
			}
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Access-Control-Allow-Methods", "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Request-ID")
			w.Header().Set("Access-Control-Expose-Headers", requestIDHeader)
			if r.Method == http.MethodOptions && r.Header.Get("Access-Control-Request-Method") != "" {
				addVary(w.Header(), "Access-Control-Request-Method")
				addVary(w.Header(), "Access-Control-Request-Headers")
				w.Header().Set("Access-Control-Max-Age", "600")
				w.WriteHeader(http.StatusNoContent)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func addVary(header http.Header, value string) {
	for _, item := range header.Values("Vary") {
		for _, existing := range strings.Split(item, ",") {
			if strings.EqualFold(strings.TrimSpace(existing), value) {
				return
			}
		}
	}
	header.Add("Vary", value)
}

type visitor struct {
	tokens   float64
	lastSeen time.Time
}

// Limiter is a bounded, per-instance token bucket keyed by the direct peer IP.
// It deliberately ignores forwarding headers unless a future trusted-proxy
// configuration explicitly enables them.
type Limiter struct {
	mu        sync.Mutex
	visitors  map[string]*visitor
	rate      float64
	burst     float64
	now       func() time.Time
	lastSweep time.Time
}

// NewLimiter creates an in-memory limiter.
func NewLimiter(rate float64, burst int) *Limiter {
	return &Limiter{visitors: make(map[string]*visitor), rate: rate, burst: float64(burst), now: time.Now}
}

// Middleware rejects exhausted clients with 429. Health probes and CORS
// preflights are excluded to keep infrastructure checks deterministic. The
// /internal/ service boundary is excluded too: it is authenticated by its own
// constant-time shared service token, and rate limiting it by direct peer
// would collapse every Agent's per-session authorization polls into one
// public bucket.
func (l *Limiter) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodOptions || r.URL.Path == "/healthz" || r.URL.Path == "/readyz" || strings.HasPrefix(r.URL.Path, "/internal/") {
			next.ServeHTTP(w, r)
			return
		}
		if !l.allow(clientIP(r.RemoteAddr)) {
			w.Header().Set("Retry-After", "1")
			writeError(w, r, http.StatusTooManyRequests, "rate_limited")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func clientIP(remoteAddress string) string {
	host, _, err := net.SplitHostPort(remoteAddress)
	if err != nil || net.ParseIP(host) == nil {
		return "unknown"
	}
	return host
}

func (l *Limiter) allow(key string) bool {
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()

	current, ok := l.visitors[key]
	if !ok {
		if !l.makeRoom(now) {
			return false
		}
		l.visitors[key] = &visitor{tokens: l.burst - 1, lastSeen: now}
		return true
	}
	elapsed := now.Sub(current.lastSeen).Seconds()
	current.tokens = min(l.burst, current.tokens+elapsed*l.rate)
	current.lastSeen = now
	if current.tokens < 1 {
		return false
	}
	current.tokens--
	return true
}

func (l *Limiter) makeRoom(now time.Time) bool {
	if len(l.visitors) < maxVisitors {
		return true
	}
	// Sweep at most once per minute under high-cardinality traffic. Unknown
	// peers are rejected while the bounded table is full instead of forcing an
	// O(n) scan or evicting active visitors for every request.
	if !l.lastSweep.IsZero() && now.Sub(l.lastSweep) < time.Minute {
		return false
	}
	l.lastSweep = now
	cutoff := now.Add(-visitorTTL)
	for key, current := range l.visitors {
		if current.lastSeen.Before(cutoff) {
			delete(l.visitors, key)
		}
	}
	return len(l.visitors) < maxVisitors
}

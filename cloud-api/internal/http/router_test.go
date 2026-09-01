package httpapi

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/config"
)

type readinessFunc func(context.Context) error

func (function readinessFunc) Ping(ctx context.Context) error { return function(ctx) }

func TestHealthReadyAndPublicConfig(t *testing.T) {
	database := readinessFunc(func(context.Context) error { return nil })
	router := testRouter(database, nil)

	tests := []struct {
		path       string
		status     int
		body       string
		cacheValue string
	}{
		{path: "/healthz", status: http.StatusOK, body: `"status":"ok"`},
		{path: "/readyz", status: http.StatusOK, body: `"status":"ready"`},
		{path: "/api/v1/config", status: http.StatusOK, body: `"version":"test-version"`, cacheValue: "no-store"},
	}
	for _, test := range tests {
		t.Run(test.path, func(t *testing.T) {
			response := request(router, http.MethodGet, test.path, "")
			if response.Code != test.status || !strings.Contains(response.Body.String(), test.body) {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			if test.cacheValue != "" && response.Header().Get("Cache-Control") != test.cacheValue {
				t.Fatalf("Cache-Control = %q", response.Header().Get("Cache-Control"))
			}
			body := response.Body.String()
			for _, secret := range []string{"DATABASE_URL", "postgres://", "secret"} {
				if strings.Contains(body, secret) {
					t.Fatalf("public response leaked %q: %s", secret, body)
				}
			}
		})
	}
}

func TestReadyHidesDatabaseFailureAndHonorsDeadline(t *testing.T) {
	database := readinessFunc(func(ctx context.Context) error {
		<-ctx.Done()
		return errors.New("postgres://user:password@database/private")
	})
	start := time.Now()
	response := request(testRouter(database, nil), http.MethodGet, "/readyz", "")
	if response.Code != http.StatusServiceUnavailable || !strings.Contains(response.Body.String(), "not_ready") {
		t.Fatalf("response = %d %s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), "password") {
		t.Fatalf("database error leaked: %s", response.Body.String())
	}
	if elapsed := time.Since(start); elapsed > 250*time.Millisecond {
		t.Fatalf("readiness timeout took %s", elapsed)
	}
}

func TestCORSExactMatchAndPreflight(t *testing.T) {
	router := testRouter(readinessFunc(func(context.Context) error { return nil }), nil)

	allowed := request(router, http.MethodGet, "/api/v1/config", "http://127.0.0.1:5173")
	if allowed.Code != http.StatusOK || allowed.Header().Get("Access-Control-Allow-Origin") != "http://127.0.0.1:5173" || !strings.Contains(allowed.Header().Get("Vary"), "Origin") {
		t.Fatalf("allowed response headers = %#v, status = %d", allowed.Header(), allowed.Code)
	}

	denied := request(router, http.MethodGet, "/api/v1/config", "http://127.0.0.1:5173.evil.test")
	if denied.Code != http.StatusForbidden || denied.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Fatalf("denied response headers = %#v, status = %d", denied.Header(), denied.Code)
	}

	req := httptest.NewRequest(http.MethodOptions, "/api/v1/auth/login", nil)
	req.Header.Set("Origin", "http://127.0.0.1:5173")
	req.Header.Set("Access-Control-Request-Method", http.MethodPost)
	req.Header.Set("Access-Control-Request-Headers", "authorization,content-type")
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)
	if response.Code != http.StatusNoContent || response.Header().Get("Access-Control-Max-Age") != "600" {
		t.Fatalf("preflight = %d %#v", response.Code, response.Header())
	}
}

func TestRequestIDIsSanitizedAndLoggedWithoutSensitiveData(t *testing.T) {
	var logs bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&logs, nil))
	router := testRouter(readinessFunc(func(context.Context) error { return nil }), logger)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login?password=query-canary", strings.NewReader(`{"password":"body-canary"}`))
	req.Header.Set(requestIDHeader, "bad\nlog-injection")
	req.Header.Set("Authorization", "Bearer authorization-canary")
	req.Header.Set("Cookie", "refresh=refresh-canary")
	req.RemoteAddr = "203.0.113.10:54321"
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)

	requestID := response.Header().Get(requestIDHeader)
	if response.Code != http.StatusNotFound || !requestIDPattern.MatchString(requestID) || strings.Contains(requestID, "bad") {
		t.Fatalf("response = %d, request id = %q", response.Code, requestID)
	}
	logOutput := logs.String()
	if !strings.Contains(logOutput, requestID) || !strings.Contains(logOutput, `"route":"unmatched"`) {
		t.Fatalf("safe metadata missing from log: %s", logOutput)
	}
	for _, secret := range []string{"query-canary", "body-canary", "authorization-canary", "refresh-canary", "203.0.113.10"} {
		if strings.Contains(logOutput, secret) {
			t.Fatalf("log leaked %q: %s", secret, logOutput)
		}
	}
}

func TestPhoneVerificationRoutesValidateFormatBeforeReturningDisabled(t *testing.T) {
	router, _, _ := newAdminContractRouter(t, &adminContractStore{enabled: true})
	for _, endpoint := range []string{"/api/v1/auth/phone-verifications", "/api/v1/auth/phone-verifications/confirm"} {
		for _, test := range []struct {
			name, body string
			status     int
			errorCode  string
		}{
			{name: "valid phone is disabled", body: `{"phone":"13800138000"}`, status: http.StatusServiceUnavailable, errorCode: "verification_not_enabled"},
			{name: "invalid phone is rejected", body: `{"phone":"12000138000"}`, status: http.StatusBadRequest, errorCode: "invalid_request"},
		} {
			t.Run(endpoint+"/"+test.name, func(t *testing.T) {
				req := httptest.NewRequest(http.MethodPost, endpoint, strings.NewReader(test.body))
				req.Header.Set("Content-Type", "application/json")
				req.RemoteAddr = "127.0.0.1:12345"
				response := httptest.NewRecorder()
				router.ServeHTTP(response, req)
				if response.Code != test.status || !strings.Contains(response.Body.String(), test.errorCode) {
					t.Fatalf("response = %d %s", response.Code, response.Body.String())
				}
			})
		}
	}
}

func TestBusinessRoutesRequireConfiguredStore(t *testing.T) {
	router := testRouter(readinessFunc(func(context.Context) error { return nil }), nil)
	response := request(router, http.MethodPost, "/api/v1/auth/register", "")
	if response.Code != http.StatusNotFound {
		t.Fatalf("business route without store = %d %s", response.Code, response.Body.String())
	}
}

func testRouter(database Readiness, logger *slog.Logger) http.Handler {
	return NewRouter(RouterOptions{
		Config: config.Config{
			Environment:     "test",
			AllowedOrigins:  []string{"http://127.0.0.1:5173"},
			DatabaseTimeout: 20 * time.Millisecond,
			RateLimitRPS:    1000,
			RateLimitBurst:  1000,
		},
		Database: database,
		Logger:   logger,
		Version:  "test-version",
	})
}

func request(handler http.Handler, method, path, origin string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, nil)
	req.RemoteAddr = "127.0.0.1:12345"
	if origin != "" {
		req.Header.Set("Origin", origin)
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, req)
	return response
}

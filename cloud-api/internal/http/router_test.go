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

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
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

func TestMultiIdentityAuthHTTPContractIsStrictAndDoesNotExposePrivateIdentities(t *testing.T) {
	hash, err := auth.HashPassword("Aa123456")
	if err != nil {
		t.Fatal(err)
	}
	const email = "alice@example.com"
	store := &adminContractStore{enabled: true, phoneUser: domain.User{ID: uuid.New(), Username: "alice_01", Phone: "+8613800138000", Email: email, Role: string(domain.RoleUser)}, phoneHash: hash}
	router, issuer, now := newAdminContractRouter(t, store)
	for _, test := range []struct {
		name, path, body string
		status           int
	}{
		{"register exact", "/api/v1/auth/register", `{"username":"Alice_01","email":"Alice@Example.COM","phone":"13800138000","password":"Aa123456"}`, http.StatusCreated},
		{"register rejects unknown", "/api/v1/auth/register", `{"username":"alice_01","email":"alice@example.com","phone":"13800138000","password":"Aa123456","unknown":true}`, http.StatusBadRequest},
		{"login phone", "/api/v1/auth/login", `{"identifier":"13800138000","password":"Aa123456"}`, http.StatusOK},
		{"login email", "/api/v1/auth/login", `{"identifier":"Alice@Example.COM","password":"Aa123456"}`, http.StatusOK},
		{"login username", "/api/v1/auth/login", `{"identifier":"Alice_01","password":"Aa123456"}`, http.StatusOK},
		{"login unknown", "/api/v1/auth/login", `{"identifier":"missing_user","password":"Aa123456"}`, http.StatusUnauthorized},
		{"login disabled", "/api/v1/auth/login", `{"identifier":"alice_01","password":"Aa123456"}`, http.StatusUnauthorized},
		{"login wrong password", "/api/v1/auth/login", `{"identifier":"alice_01","password":"Wrong123"}`, http.StatusUnauthorized},
		{"login malformed", "/api/v1/auth/login", `{`, http.StatusBadRequest},
	} {
		t.Run(test.name, func(t *testing.T) {
			store.phoneQuery, store.emailQuery, store.usernameQuery = "", "", ""
			store.phoneCalls, store.emailCalls, store.usernameCalls = 0, 0, 0
			store.lookupErr = nil
			if test.name == "login unknown" || test.name == "login disabled" {
				store.lookupErr = domain.ErrNotFound
			}
			req := httptest.NewRequest(http.MethodPost, test.path, strings.NewReader(test.body))
			req.Header.Set("Content-Type", "application/json")
			req.RemoteAddr = "127.0.0.1:12345"
			response := httptest.NewRecorder()
			router.ServeHTTP(response, req)
			if response.Code != test.status {
				t.Fatalf("status = %d", response.Code)
			}
			if strings.Contains(response.Body.String(), "+8613800138000") || strings.Contains(response.Body.String(), email) || strings.Contains(response.Body.String(), `"phone"`) || strings.Contains(response.Body.String(), `"email"`) {
				t.Fatal("response exposed internal identity")
			}
			if strings.HasPrefix(test.name, "login ") && test.status == http.StatusUnauthorized && !strings.Contains(response.Body.String(), "invalid_credentials") {
				t.Fatal("login failure did not return generic credentials failure")
			}
			if test.name == "login unknown" && (store.phoneCalls != 0 || store.emailCalls != 0 || store.usernameCalls != 1) {
				t.Fatal("unknown login used the wrong store lookup")
			}
		})
	}
	if store.register.Username != "alice_01" || store.register.Email != email || store.register.Phone != "+8613800138000" || len(store.storedEmails) != 1 || store.storedEmails[0] != email {
		t.Fatal("registration did not persist canonical identities")
	}
	access, err := issuer.AccessToken(store.phoneUser.ID, string(domain.RoleUser), time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/users/me", nil)
	req.Header.Set("Authorization", "Bearer "+access)
	req.RemoteAddr = "127.0.0.1:12345"
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)
	if response.Code != http.StatusOK || strings.Contains(response.Body.String(), "+8613800138000") || strings.Contains(response.Body.String(), email) || strings.Contains(response.Body.String(), `"phone"`) || strings.Contains(response.Body.String(), `"email"`) {
		t.Fatal("me response exposed internal identity")
	}
}

func TestMultiIdentityLoginCanonicalizesEachAcceptedInputIndependently(t *testing.T) {
	hash, err := auth.HashPassword("Aa123456")
	if err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		name, identifier, phone, email, username string
	}{
		{name: "phone", identifier: "13800138000", phone: "+8613800138000"},
		{name: "email", identifier: "Alice@Example.COM", email: "alice@example.com"},
		{name: "username", identifier: "Alice_01", username: "alice_01"},
	} {
		t.Run(test.name, func(t *testing.T) {
			store := &adminContractStore{enabled: true, phoneUser: domain.User{ID: uuid.New(), Phone: "+8613800138000", Role: string(domain.RoleUser)}, phoneHash: hash}
			router, _, _ := newAdminContractRouter(t, store)
			req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", strings.NewReader(`{"identifier":"`+test.identifier+`","password":"Aa123456"}`))
			req.Header.Set("Content-Type", "application/json")
			req.RemoteAddr = "127.0.0.1:12345"
			response := httptest.NewRecorder()
			router.ServeHTTP(response, req)
			if response.Code != http.StatusOK || store.phoneQuery != test.phone || store.emailQuery != test.email || store.usernameQuery != test.username {
				t.Fatal("identifier was not canonicalized before the correct lookup")
			}
		})
	}
}

type verificationSpy struct{ calls int }

func (s *verificationSpy) Verify(context.Context, string) error {
	s.calls++
	return errVerificationNotEnabled
}

func TestPhoneVerificationUsesOnlyDisabledBoundary(t *testing.T) {
	for _, endpoint := range []string{"/api/v1/auth/phone-verifications", "/api/v1/auth/phone-verifications/confirm"} {
		t.Run(endpoint, func(t *testing.T) {
			spy := &verificationSpy{}
			router := NewRouter(RouterOptions{Config: config.Config{Environment: "test", DatabaseTimeout: time.Second, RateLimitRPS: 1000, RateLimitBurst: 1000}, Verification: spy})
			req := httptest.NewRequest(http.MethodPost, endpoint, strings.NewReader(`{"phone":"13800138000"}`))
			req.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			router.ServeHTTP(response, req)
			if response.Code != http.StatusServiceUnavailable || spy.calls != 1 {
				t.Fatal("verification endpoint did not invoke only the disabled boundary once")
			}
		})
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

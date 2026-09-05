package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
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

// agentContractStore backs the internal Agent authorization endpoint tests.
// It embeds the shared admin contract store so the router's full business
// surface is satisfied, and adds the persisted translation-session lifecycle
// lookup the authorization facade consults.
type agentContractStore struct {
	adminContractStore

	stateCalls  int
	stateIdent  []uuid.UUID
	stateResult domain.TranslationSessionAuthorization
	stateErr    error
}

func (s *agentContractStore) TranslationSessionState(_ context.Context, user, session, entitlement, jti uuid.UUID, _ time.Time) (domain.TranslationSessionAuthorization, error) {
	s.stateCalls++
	s.stateIdent = []uuid.UUID{user, session, entitlement, jti}
	if s.stateErr != nil {
		return domain.TranslationSessionAuthorization{}, s.stateErr
	}
	return s.stateResult, nil
}

const testAgentServiceToken = "agent-service-token-0123456789abcdef0123456789abcdef"

var agentTestNow = time.Date(2026, 8, 27, 12, 0, 0, 0, time.UTC)

func agentTestTokenIssuer() auth.TokenIssuer {
	return auth.TokenIssuer{
		Issuer:          "test-cloud-api",
		Audience:        "test-clients",
		SessionAudience: "test-agent",
		AccessSecret:    bytes.Repeat([]byte("a"), auth.MinimumSecretBytes),
		SessionSecret:   bytes.Repeat([]byte("s"), auth.MinimumSecretBytes),
	}
}

func newAgentInternalRouter(t *testing.T, store *agentContractStore, serviceToken string) http.Handler {
	t.Helper()
	return NewRouter(RouterOptions{
		Config: config.Config{
			Environment:       "test",
			AllowedOrigins:    []string{"http://127.0.0.1:5173"},
			DatabaseTimeout:   time.Second,
			RateLimitRPS:      1000,
			RateLimitBurst:    1000,
			AgentServiceToken: serviceToken,
		},
		Store:   store,
		Tokens:  agentTestTokenIssuer(),
		Now:     func() time.Time { return agentTestNow },
		Version: "test-version",
	})
}

// agentTestToken issues a structurally valid translation-session token bound
// to the identity the fake store resolves.
func agentTestToken(t *testing.T, issuer auth.TokenIssuer) string {
	t.Helper()
	token, err := issuer.TranslationTokenForInstall(
		uuid.MustParse("123e4567-e89b-12d3-a456-426614174000"),
		uuid.MustParse("123e4567-e89b-12d3-a456-426614174003"),
		uuid.MustParse("123e4567-e89b-12d3-a456-426614174001"),
		uuid.MustParse("123e4567-e89b-12d3-a456-426614174002"),
		"install-test",
		5*time.Minute,
		agentTestNow,
	)
	if err != nil {
		t.Fatal(err)
	}
	return token
}

func agentActiveState() domain.TranslationSessionAuthorization {
	return domain.TranslationSessionAuthorization{
		UserID:        uuid.MustParse("123e4567-e89b-12d3-a456-426614174001"),
		SessionID:     uuid.MustParse("123e4567-e89b-12d3-a456-426614174000"),
		EntitlementID: uuid.MustParse("123e4567-e89b-12d3-a456-426614174003"),
		JTI:           uuid.MustParse("123e4567-e89b-12d3-a456-426614174002"),
		Active:        true,
	}
}

func agentInternalRequest(handler http.Handler, method, path, bearerToken, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.RemoteAddr = "127.0.0.1:12345"
	req.Header.Set("Content-Type", "application/json")
	if bearerToken != "" {
		req.Header.Set("Authorization", "Bearer "+bearerToken)
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, req)
	return response
}

func TestAgentInternalAuthorizeNotMountedWithoutServiceToken(t *testing.T) {
	router := newAgentInternalRouter(t, &agentContractStore{stateResult: agentActiveState()}, "")
	response := agentInternalRequest(router, http.MethodPost, AgentInternalAuthorizePath, testAgentServiceToken, `{"token":"a.b.c"}`)
	if response.Code != http.StatusNotFound {
		t.Fatalf("response = %d %s", response.Code, response.Body.String())
	}
}

func TestAgentInternalAuthorizeEnforcesServiceAuthentication(t *testing.T) {
	store := &agentContractStore{stateResult: agentActiveState()}
	router := newAgentInternalRouter(t, store, testAgentServiceToken)

	tests := []struct {
		name   string
		bearer string
	}{
		{name: "missing authorization header", bearer: ""},
		{name: "wrong service token", bearer: "wrong-service-token-0123456789abcdef0123456"},
		{name: "short token", bearer: "short"},
		{name: "user access token", bearer: "access-token-canary"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response := agentInternalRequest(router, http.MethodPost, AgentInternalAuthorizePath, test.bearer, `{"token":"a.b.c"}`)
			if response.Code != http.StatusUnauthorized {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			if store.stateCalls != 0 {
				t.Fatalf("authorization facade was reached without valid service credentials")
			}
		})
	}
}

func TestAgentInternalAuthorizeActiveSession(t *testing.T) {
	issuer := agentTestTokenIssuer()
	store := &agentContractStore{stateResult: agentActiveState()}
	router := newAgentInternalRouter(t, store, testAgentServiceToken)

	response := agentInternalRequest(router, http.MethodPost, AgentInternalAuthorizePath, testAgentServiceToken, `{"token":"`+agentTestToken(t, issuer)+`"}`)
	if response.Code != http.StatusOK {
		t.Fatalf("response = %d %s", response.Code, response.Body.String())
	}
	var body agentAuthorizeResponse
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil || !body.Active || body.Reason != "" {
		t.Fatalf("body = %s (%v)", response.Body.String(), err)
	}
	// Identity must come from the verified token claims, not the request body.
	want := []uuid.UUID{
		uuid.MustParse("123e4567-e89b-12d3-a456-426614174001"),
		uuid.MustParse("123e4567-e89b-12d3-a456-426614174000"),
		uuid.MustParse("123e4567-e89b-12d3-a456-426614174003"),
		uuid.MustParse("123e4567-e89b-12d3-a456-426614174002"),
	}
	for i, id := range want {
		if store.stateIdent[i] != id {
			t.Fatalf("store identity[%d] = %s, want %s", i, store.stateIdent[i], id)
		}
	}
}

func TestAgentInternalAuthorizeSurfacesSafeTerminationReasons(t *testing.T) {
	issuer := agentTestTokenIssuer()
	for _, reason := range []domain.TranslationTerminationReason{
		domain.TerminationReplacedByDevice,
		domain.TerminationEnded,
		domain.TerminationRevoked,
		domain.TerminationExpired,
		domain.TerminationUserDisabled,
		domain.TerminationEntitlementRevoked,
	} {
		t.Run(string(reason), func(t *testing.T) {
			state := agentActiveState()
			state.Active = false
			state.TerminationReason = reason
			store := &agentContractStore{stateResult: state}
			router := newAgentInternalRouter(t, store, testAgentServiceToken)

			response := agentInternalRequest(router, http.MethodPost, AgentInternalAuthorizePath, testAgentServiceToken, `{"token":"`+agentTestToken(t, issuer)+`"}`)
			if response.Code != http.StatusOK {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
			var body agentAuthorizeResponse
			if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil || body.Active || body.Reason != string(reason) {
				t.Fatalf("body = %s (%v)", response.Body.String(), err)
			}
		})
	}
}

func TestAgentInternalAuthorizeDeniesWithoutTrustingClientIdentity(t *testing.T) {
	issuer := agentTestTokenIssuer()
	store := &agentContractStore{stateErr: domain.ErrUnauthorized}
	router := newAgentInternalRouter(t, store, testAgentServiceToken)

	// The contract is exactly {"token": ...}. Caller-supplied user or session
	// identifiers are unknown fields: rejected before the facade is reached,
	// so they can never influence an authorization decision.
	forged := `{"token":"` + agentTestToken(t, issuer) + `","user_id":"00000000-0000-0000-0000-000000000001","session_id":"00000000-0000-0000-0000-000000000002"}`
	response := agentInternalRequest(router, http.MethodPost, AgentInternalAuthorizePath, testAgentServiceToken, forged)
	if response.Code != http.StatusBadRequest || store.stateCalls != 0 {
		t.Fatalf("response = %d %s, facade calls = %d", response.Code, response.Body.String(), store.stateCalls)
	}

	// An unknown but structurally valid token receives a definitive inactive
	// answer with no reason, whatever identity it claims.
	response = agentInternalRequest(router, http.MethodPost, AgentInternalAuthorizePath, testAgentServiceToken, `{"token":"`+agentTestToken(t, issuer)+`"}`)
	if response.Code != http.StatusOK {
		t.Fatalf("response = %d %s", response.Code, response.Body.String())
	}
	var parsed agentAuthorizeResponse
	if err := json.Unmarshal(response.Body.Bytes(), &parsed); err != nil || parsed.Active || parsed.Reason != "" {
		t.Fatalf("body = %s (%v)", response.Body.String(), err)
	}
	if strings.Contains(response.Body.String(), "00000000") {
		t.Fatalf("response leaked caller-supplied identity: %s", response.Body.String())
	}
}

func TestAgentInternalAuthorizeUnparseableTokenFailsClosedWithoutReason(t *testing.T) {
	store := &agentContractStore{stateResult: agentActiveState()}
	router := newAgentInternalRouter(t, store, testAgentServiceToken)

	for _, token := range []string{"not-a-jwt", "a.b.c"} {
		response := agentInternalRequest(router, http.MethodPost, AgentInternalAuthorizePath, testAgentServiceToken, `{"token":"`+token+`"}`)
		if response.Code != http.StatusOK {
			t.Fatalf("token %q: response = %d %s", token, response.Code, response.Body.String())
		}
		if !strings.Contains(response.Body.String(), `"active":false`) || strings.Contains(response.Body.String(), "reason") {
			t.Fatalf("token %q: body = %s", token, response.Body.String())
		}
	}
}

func TestAgentInternalAuthorizeStoreFailureFailsClosedWithoutReason(t *testing.T) {
	issuer := agentTestTokenIssuer()
	store := &agentContractStore{stateErr: context.DeadlineExceeded}
	router := newAgentInternalRouter(t, store, testAgentServiceToken)

	response := agentInternalRequest(router, http.MethodPost, AgentInternalAuthorizePath, testAgentServiceToken, `{"token":"`+agentTestToken(t, issuer)+`"}`)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"active":false`) || strings.Contains(response.Body.String(), "reason") {
		t.Fatalf("response = %d %s", response.Code, response.Body.String())
	}
}

func TestAgentInternalAuthorizeRejectsMalformedRequests(t *testing.T) {
	store := &agentContractStore{stateResult: agentActiveState()}
	router := newAgentInternalRouter(t, store, testAgentServiceToken)

	tests := []struct {
		name string
		body string
	}{
		{name: "not json", body: "not-json"},
		{name: "missing token field", body: `{"other":1}`},
		{name: "oversized token", body: `{"token":"` + strings.Repeat("x", maxAgentTokenBytes+1) + `"}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response := agentInternalRequest(router, http.MethodPost, AgentInternalAuthorizePath, testAgentServiceToken, test.body)
			if response.Code != http.StatusBadRequest {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
		})
	}
}

func TestAgentInternalAuthorizeRejectsNonPostMethods(t *testing.T) {
	store := &agentContractStore{stateResult: agentActiveState()}
	router := newAgentInternalRouter(t, store, testAgentServiceToken)
	response := agentInternalRequest(router, http.MethodGet, AgentInternalAuthorizePath, testAgentServiceToken, "")
	if response.Code != http.StatusMethodNotAllowed {
		t.Fatalf("response = %d %s", response.Code, response.Body.String())
	}
}

func TestAgentInternalAuthorizePathStaysOutsidePublicAPIGroup(t *testing.T) {
	if strings.HasPrefix(AgentInternalAuthorizePath, "/api/v1") {
		t.Fatalf("internal endpoint must not live in the public group: %s", AgentInternalAuthorizePath)
	}
}

func TestLimiterExemptsInternalServicePaths(t *testing.T) {
	handler := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	limiter := NewLimiter(1, 1)
	served := limiter.Middleware(handler)

	// The single shared bucket admits exactly one public request.
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", nil)
	req.RemoteAddr = "127.0.0.1:12345"
	response := httptest.NewRecorder()
	served.ServeHTTP(response, req)
	if response.Code != http.StatusOK {
		t.Fatalf("first public request = %d", response.Code)
	}

	// The internal service boundary keeps working after the bucket is empty.
	for i := 0; i < 5; i++ {
		internal := httptest.NewRequest(http.MethodPost, AgentInternalAuthorizePath, nil)
		internal.RemoteAddr = "127.0.0.1:12345"
		internalResponse := httptest.NewRecorder()
		served.ServeHTTP(internalResponse, internal)
		if internalResponse.Code != http.StatusOK {
			t.Fatalf("internal request %d = %d", i, internalResponse.Code)
		}
	}

	// Public traffic is still rate limited.
	blocked := httptest.NewRecorder()
	served.ServeHTTP(blocked, req)
	if blocked.Code != http.StatusTooManyRequests {
		t.Fatalf("second public request = %d", blocked.Code)
	}
}

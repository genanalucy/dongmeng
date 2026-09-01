package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
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

type accountTestStore struct {
	adminContractStore
	overview      domain.AccountOverview
	overviewErr   error
	overviewUser  uuid.UUID
	usage         []domain.AccountUsage
	usageErr      error
	usageUser     uuid.UUID
	usageLimit    int
	usageOffset   int
	identity      domain.User
	identityErr   error
	identityInput domain.UpdateIdentityParams
}

func (s *accountTestStore) AccountOverview(_ context.Context, user uuid.UUID) (domain.AccountOverview, error) {
	s.overviewUser = user
	return s.overview, s.overviewErr
}

func (s *accountTestStore) ListAccountUsage(_ context.Context, user uuid.UUID, limit, offset int) ([]domain.AccountUsage, error) {
	s.usageUser, s.usageLimit, s.usageOffset = user, limit, offset
	return s.usage, s.usageErr
}

func (s *accountTestStore) UpdateIdentity(_ context.Context, input domain.UpdateIdentityParams) (domain.User, error) {
	s.identityInput = input
	return s.identity, s.identityErr
}

func newAccountRouter(t *testing.T, store *accountTestStore) (http.Handler, auth.TokenIssuer, time.Time) {
	t.Helper()
	_, issuer, now := newAdminContractRouter(t, &store.adminContractStore)
	// The account methods live on accountTestStore; construct a router against it.
	return NewRouter(RouterOptions{Config: configForTest(), Store: store, Tokens: issuer, Now: func() time.Time { return now }}), issuer, now
}

func configForTest() config.Config {
	return config.Config{Environment: "test", DatabaseTimeout: time.Second, RateLimitRPS: 1000, RateLimitBurst: 1000}
}

func accountRequest(router http.Handler, method, path, token, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.RemoteAddr = "127.0.0.1:12345"
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)
	return response
}

func TestAccountOverviewUsesTokenSubjectAndHidesPrivateIdentity(t *testing.T) {
	owner, other := uuid.New(), uuid.New()
	now := time.Date(2026, 8, 27, 12, 0, 0, 0, time.UTC)
	store := &accountTestStore{adminContractStore: adminContractStore{enabled: true}, overview: domain.AccountOverview{
		User:        domain.User{ID: owner, Email: "legacy@example.test", Phone: "+8613800138000"},
		Entitlement: &domain.Entitlement{ID: uuid.New(), UserID: owner, Kind: string(domain.EntitlementTrial), StartsAt: now.Add(-time.Hour), ExpiresAt: now.Add(time.Hour)},
		Usage:       domain.UsageSummary{AudioSeconds: 42, SessionCount: 3, LastUsedAt: &now},
	}}
	router, issuer, tokenNow := newAccountRouter(t, store)
	token := adminAccessToken(t, issuer, owner, domain.RoleUser, tokenNow)
	response := accountRequest(router, http.MethodGet, "/api/v1/account/overview", token, "")
	if response.Code != http.StatusOK || store.overviewUser != owner {
		t.Fatalf("status/user = %d/%s", response.Code, store.overviewUser)
	}
	body := response.Body.String()
	for _, private := range []string{"legacy@example.test", "+8613800138000"} {
		if strings.Contains(body, private) {
			t.Fatalf("overview leaked %q: %s", private, body)
		}
	}
	for _, required := range []string{`"username":"旧版用户"`, `"active":true`, `"remaining_seconds":3600`, `"audio_seconds":42`, `"session_count":3`} {
		if !strings.Contains(body, required) {
			t.Fatalf("overview missing %q: %s", required, body)
		}
	}
	if store.overviewUser == other {
		t.Fatal("overview used a client-supplied identity")
	}
}

func TestAccountUsageIsStrictlySubjectScopedAndValidatesPagination(t *testing.T) {
	owner, other := uuid.New(), uuid.New()
	store := &accountTestStore{adminContractStore: adminContractStore{enabled: true}, usage: []domain.AccountUsage{{SessionID: uuid.New(), StartedAt: time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC), DurationSeconds: 17}}}
	router, issuer, now := newAccountRouter(t, store)
	token := adminAccessToken(t, issuer, owner, domain.RoleUser, now)
	response := accountRequest(router, http.MethodGet, "/api/v1/account/usage?limit=10&offset=5", token, "")
	if response.Code != http.StatusOK || store.usageUser != owner || store.usageLimit != 10 || store.usageOffset != 5 {
		t.Fatalf("status/scope/page = %d/%s/%d/%d", response.Code, store.usageUser, store.usageLimit, store.usageOffset)
	}
	if store.usageUser == other || !strings.Contains(response.Body.String(), `"duration_seconds":17`) {
		t.Fatal("usage was not safely subject scoped or summarized")
	}
	for _, path := range []string{"/api/v1/account/usage?limit=0&offset=0", "/api/v1/account/usage?limit=51&offset=0", "/api/v1/account/usage?limit=10&offset=-1", "/api/v1/account/usage?limit=10&offset=not-a-number"} {
		invalid := accountRequest(router, http.MethodGet, path, token, "")
		if invalid.Code != http.StatusBadRequest || !strings.Contains(invalid.Body.String(), "invalid_request") {
			t.Fatalf("%s = %d %s", path, invalid.Code, invalid.Body.String())
		}
	}
}

func TestAccountIdentityNormalizesAndReturnsOnlyPublicUser(t *testing.T) {
	owner := uuid.New()
	store := &accountTestStore{adminContractStore: adminContractStore{enabled: true}, identity: domain.User{ID: owner, Username: "alice_01", Email: "alice@example.test", Phone: "+8613800138000", Role: string(domain.RoleUser)}}
	router, issuer, now := newAccountRouter(t, store)
	token := adminAccessToken(t, issuer, owner, domain.RoleUser, now)
	response := accountRequest(router, http.MethodPatch, "/api/v1/account/identity", token, `{"username":" Alice_01 ","email":" Alice@Example.Test ","phone":"13800138000","current_password":"Aa123456"}`)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d %s", response.Code, response.Body.String())
	}
	input := store.identityInput
	if input.UserID != owner || input.Username != "alice_01" || input.Email != "alice@example.test" || input.Phone != "+8613800138000" || input.CurrentPassword != "Aa123456" {
		t.Fatalf("identity input was not canonicalized: %#v", input)
	}
	for _, private := range []string{"alice@example.test", "+8613800138000", "Aa123456", `\"email\"`, `\"phone\"`} {
		if strings.Contains(response.Body.String(), private) {
			t.Fatalf("identity response leaked %q: %s", private, response.Body.String())
		}
	}
	if !strings.Contains(response.Body.String(), `"username":"alice_01"`) {
		t.Fatalf("identity response was not public: %s", response.Body.String())
	}
}

func TestAccountIdentityMapsCredentialAndConflictFailuresToGenericResponses(t *testing.T) {
	owner := uuid.New()
	for _, test := range []struct {
		name, code string
		err        error
		status     int
	}{
		{name: "password", code: "invalid_credentials", err: domain.ErrUnauthorized, status: http.StatusUnauthorized},
		{name: "conflict", code: "conflict", err: domain.ErrConflict, status: http.StatusConflict},
	} {
		t.Run(test.name, func(t *testing.T) {
			store := &accountTestStore{adminContractStore: adminContractStore{enabled: true}, identityErr: test.err}
			router, issuer, now := newAccountRouter(t, store)
			token := adminAccessToken(t, issuer, owner, domain.RoleUser, now)
			response := accountRequest(router, http.MethodPatch, "/api/v1/account/identity", token, `{"username":"alice_01","email":"alice@example.test","phone":"13800138000","current_password":"Aa123456"}`)
			if response.Code != test.status || !strings.Contains(response.Body.String(), test.code) || strings.Contains(response.Body.String(), "username") {
				t.Fatalf("response = %d %s", response.Code, response.Body.String())
			}
		})
	}
}

func TestAccountIdentityRejectsInvalidInputBeforeStore(t *testing.T) {
	owner := uuid.New()
	store := &accountTestStore{adminContractStore: adminContractStore{enabled: true}}
	router, issuer, now := newAccountRouter(t, store)
	token := adminAccessToken(t, issuer, owner, domain.RoleUser, now)
	response := accountRequest(router, http.MethodPatch, "/api/v1/account/identity", token, `{"username":"bad!","email":"not-an-email","phone":"12000138000","current_password":""}`)
	if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "invalid_request") || store.identityInput.UserID != uuid.Nil {
		t.Fatalf("response/store input = %d/%#v", response.Code, store.identityInput)
	}
}

func TestAccountIdentityAllowsWeakLegacyCurrentPassword(t *testing.T) {
	owner := uuid.New()
	store := &accountTestStore{adminContractStore: adminContractStore{enabled: true}, identity: domain.User{ID: owner, Username: "alice_01", Role: string(domain.RoleUser)}}
	router, issuer, now := newAccountRouter(t, store)
	response := accountRequest(router, http.MethodPatch, "/api/v1/account/identity", adminAccessToken(t, issuer, owner, domain.RoleUser, now), `{"username":"alice_01","email":"alice@example.test","phone":"13800138000","current_password":"x"}`)
	if response.Code != http.StatusOK || store.identityInput.CurrentPassword != "x" {
		t.Fatalf("legacy current password was rejected: %d %#v", response.Code, store.identityInput)
	}
}

var forbiddenResponseKeys = map[string]bool{
	"email": true, "phone": true, "password": true, "password_hash": true,
	"current_password": true, "token": true, "access_token": true,
	"refresh_token": true, "audio": true, "audio_url": true, "transcript": true,
	"object_key": true, "artifact": true,
}

type jsonSchema struct {
	fields  map[string]jsonSchema
	element *jsonSchema
}

func object(fields map[string]jsonSchema) jsonSchema { return jsonSchema{fields: fields} }
func array(element jsonSchema) jsonSchema            { return jsonSchema{element: &element} }

func assertJSONSchema(t *testing.T, raw []byte, schema jsonSchema) {
	t.Helper()
	if err := validateJSONSchema(raw, schema); err != nil {
		t.Fatal(err)
	}
}

func validateJSONSchema(raw []byte, schema jsonSchema) error {
	var value any
	if err := json.Unmarshal(raw, &value); err != nil {
		return err
	}
	return validateJSONValueSchema(value, schema, "$")
}

func validateJSONValueSchema(value any, schema jsonSchema, path string) error {
	switch item := value.(type) {
	case map[string]any:
		if schema.fields == nil {
			return fmt.Errorf("unexpected object at %s", path)
		}
		for key, child := range item {
			childSchema, allowed := schema.fields[key]
			if forbiddenResponseKeys[key] || !allowed {
				return fmt.Errorf("unexpected response key %s.%s", path, key)
			}
			if err := validateJSONValueSchema(child, childSchema, path+"."+key); err != nil {
				return err
			}
		}
	case []any:
		if schema.element == nil {
			return fmt.Errorf("unexpected array at %s", path)
		}
		for _, child := range item {
			if err := validateJSONValueSchema(child, *schema.element, path+"[]"); err != nil {
				return err
			}
		}
	default:
		if schema.fields != nil || schema.element != nil {
			return fmt.Errorf("unexpected scalar at %s", path)
		}
	}
	return nil
}

func TestAccountResponseSchemaRejectsUnknownNestedKeys(t *testing.T) {
	schema := object(map[string]jsonSchema{"usage": object(map[string]jsonSchema{"items": array(object(map[string]jsonSchema{"duration_seconds": {}}))})})
	assertJSONSchema(t, []byte(`{"usage":{"items":[{"duration_seconds":1}]}}`), schema)
	for _, payload := range []string{`{"usage":{"unexpected":true,"items":[]}}`, `{"usage":{"items":[{"duration_seconds":1,"access_token":"x"}]}}`} {
		t.Run(payload, func(t *testing.T) {
			if err := validateJSONSchema([]byte(payload), schema); err == nil {
				t.Fatal("unknown nested key was accepted")
			}
		})
	}
}

func TestAccountResponsesUseOnlySafeJSONKeys(t *testing.T) {
	owner := uuid.New()
	store := &accountTestStore{adminContractStore: adminContractStore{enabled: true}, overview: domain.AccountOverview{User: domain.User{ID: owner, Username: "alice_01"}}, usage: []domain.AccountUsage{{SessionID: uuid.New(), StartedAt: time.Now(), DurationSeconds: 1}}, identity: domain.User{ID: owner, Username: "alice_01", Role: string(domain.RoleUser)}}
	router, issuer, now := newAccountRouter(t, store)
	token := adminAccessToken(t, issuer, owner, domain.RoleUser, now)
	for _, test := range []struct {
		method, path, body string
		schema             jsonSchema
	}{
		{http.MethodGet, "/api/v1/account/overview", "", object(map[string]jsonSchema{"username": {}, "entitlement": object(map[string]jsonSchema{"kind": {}, "starts_at": {}, "expires_at": {}, "active": {}, "remaining_seconds": {}}), "usage": object(map[string]jsonSchema{"audio_seconds": {}, "session_count": {}, "last_used_at": {}})})},
		{http.MethodGet, "/api/v1/account/usage?limit=1&offset=0", "", object(map[string]jsonSchema{"usage": array(object(map[string]jsonSchema{"session_id": {}, "started_at": {}, "ended_at": {}, "duration_seconds": {}, "source_language": {}, "target_language": {}}))})},
		{http.MethodPatch, "/api/v1/account/identity", `{"username":"alice_01","email":"alice@example.test","phone":"13800138000","current_password":"x"}`, object(map[string]jsonSchema{"id": {}, "username": {}, "role": {}, "created_at": {}})},
	} {
		response := accountRequest(router, test.method, test.path, token, test.body)
		if response.Code != http.StatusOK {
			t.Fatalf("%s = %d %s", test.path, response.Code, response.Body.String())
		}
		assertJSONSchema(t, response.Body.Bytes(), test.schema)
	}
}

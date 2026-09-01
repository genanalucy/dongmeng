package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
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

type adminContractStore struct {
	domain.Store
	enabled      bool
	enabledErr   error
	users        []domain.User
	auditLogs    []domain.AuditLog
	usersErr     error
	auditLogsErr error
	userSearch   string
	userLimit    int
	userOffset   int
	auditLimit   int
	auditOffset  int
	phoneUser    domain.User
	phoneHash    string
	phoneQuery   string
	register     domain.RegisterParams
	registerErr  error
	refreshes    []domain.RefreshToken
}

func (s *adminContractStore) UserByID(_ context.Context, id uuid.UUID) (domain.User, error) {
	if s.phoneUser.ID == id {
		return s.phoneUser, nil
	}
	return domain.User{}, domain.ErrNotFound
}

func (s *adminContractStore) CreateRefreshToken(_ context.Context, params domain.CreateRefreshParams) (domain.RefreshToken, error) {
	token := domain.RefreshToken{ID: uuid.New(), UserID: params.UserID, FamilyID: params.FamilyID, TokenHash: params.Hash, ExpiresAt: params.ExpiresAt}
	s.refreshes = append(s.refreshes, token)
	return token, nil
}

func (s *adminContractStore) UserByPhone(_ context.Context, phone string) (domain.User, string, error) {
	s.phoneQuery = phone
	if s.phoneUser.ID == uuid.Nil {
		return domain.User{}, "", domain.ErrNotFound
	}
	return s.phoneUser, s.phoneHash, nil
}

func (s *adminContractStore) Register(_ context.Context, params domain.RegisterParams) (domain.User, domain.Entitlement, error) {
	s.register = params
	if s.registerErr != nil {
		return domain.User{}, domain.Entitlement{}, s.registerErr
	}
	user := domain.User{ID: uuid.New(), Username: params.Username, Phone: params.Phone, Role: string(domain.RoleUser), CreatedAt: params.Now}
	trial, _ := domain.NewTrialEntitlement(uuid.New(), user.ID, params.Now)
	return user, trial, nil
}

func (s *adminContractStore) UserEnabled(context.Context, uuid.UUID) (bool, error) {
	return s.enabled, s.enabledErr
}

func (s *adminContractStore) ListUsers(_ context.Context, search string, limit, offset int) ([]domain.User, error) {
	s.userSearch, s.userLimit, s.userOffset = search, limit, offset
	return s.users, s.usersErr
}

func (s *adminContractStore) ListAuditLogs(_ context.Context, limit, offset int) ([]domain.AuditLog, error) {
	s.auditLimit, s.auditOffset = limit, offset
	return s.auditLogs, s.auditLogsErr
}

func (s *adminContractStore) CreateSession(context.Context, domain.TranslationSession, time.Time) error {
	return errors.New("not implemented")
}

func (s *adminContractStore) DisableUser(context.Context, uuid.UUID, uuid.UUID, time.Time) error {
	return errors.New("not implemented")
}

func (s *adminContractStore) GrantEntitlementByAdmin(context.Context, uuid.UUID, uuid.UUID, time.Time) (domain.Entitlement, error) {
	return domain.Entitlement{}, errors.New("not implemented")
}

func (s *adminContractStore) RevokeEntitlementByAdmin(context.Context, uuid.UUID, uuid.UUID, uuid.UUID, time.Time) error {
	return errors.New("not implemented")
}

func (s *adminContractStore) StackAnnualEntitlement(context.Context, uuid.UUID, time.Time) (domain.Entitlement, error) {
	return domain.Entitlement{}, errors.New("not implemented")
}

func (s *adminContractStore) RevokeEntitlement(context.Context, uuid.UUID, uuid.UUID, time.Time) error {
	return errors.New("not implemented")
}

func (s *adminContractStore) CreateAuthorizedTranslationSession(context.Context, domain.TranslationSession, time.Time) error {
	return errors.New("not implemented")
}

func (s *adminContractStore) CreateAuthorizedTranslationSessionWithLimit(context.Context, domain.TranslationSession, time.Time, int) error {
	return errors.New("not implemented")
}

func (s *adminContractStore) EndTranslationSession(context.Context, uuid.UUID, uuid.UUID, time.Time) error {
	return errors.New("not implemented")
}

func (s *adminContractStore) RevokeTranslationSession(context.Context, uuid.UUID, uuid.UUID, time.Time) error {
	return errors.New("not implemented")
}

func newAdminContractRouter(t *testing.T, store *adminContractStore) (http.Handler, auth.TokenIssuer, time.Time) {
	t.Helper()
	now := time.Date(2026, 8, 27, 12, 0, 0, 0, time.UTC)
	issuer := auth.TokenIssuer{
		Issuer:          "test-cloud-api",
		Audience:        "test-clients",
		SessionAudience: "test-agent",
		AccessSecret:    bytes.Repeat([]byte("a"), auth.MinimumSecretBytes),
		SessionSecret:   bytes.Repeat([]byte("s"), auth.MinimumSecretBytes),
	}
	return NewRouter(RouterOptions{
		Config: config.Config{
			Environment: "test", DatabaseTimeout: time.Second,
			RateLimitRPS: 1000, RateLimitBurst: 1000,
		},
		Store: store, Tokens: issuer, Now: func() time.Time { return now },
	}), issuer, now
}

func adminRequest(router http.Handler, path, token string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, path, nil)
	req.RemoteAddr = "127.0.0.1:12345"
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)
	return response
}

func adminAccessToken(t *testing.T, issuer auth.TokenIssuer, userID uuid.UUID, role domain.Role, now time.Time) string {
	t.Helper()
	token, err := issuer.AccessToken(userID, string(role), time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	return token
}

func TestAdminRoutesEnforceAuthenticationRoleAndEnabledUser(t *testing.T) {
	adminID := uuid.New()
	store := &adminContractStore{enabled: true}
	router, issuer, now := newAdminContractRouter(t, store)
	userToken := adminAccessToken(t, issuer, uuid.New(), domain.RoleUser, now)
	adminToken := adminAccessToken(t, issuer, adminID, domain.RoleAdmin, now)

	for _, endpoint := range []string{"/api/v1/admin/users", "/api/v1/admin/audit-logs"} {
		for _, test := range []struct {
			name   string
			token  string
			status int
		}{
			{name: "missing token", status: http.StatusUnauthorized},
			{name: "invalid token", token: "not-a-valid-token", status: http.StatusUnauthorized},
			{name: "user role", token: userToken, status: http.StatusForbidden},
			{name: "admin role", token: adminToken, status: http.StatusOK},
		} {
			t.Run(endpoint+"/"+test.name, func(t *testing.T) {
				response := adminRequest(router, endpoint, test.token)
				if response.Code != test.status {
					t.Fatalf("status = %d", response.Code)
				}
			})
		}
	}

	store.enabled = false
	for _, endpoint := range []string{"/api/v1/admin/users", "/api/v1/admin/audit-logs"} {
		response := adminRequest(router, endpoint, adminToken)
		if response.Code != http.StatusUnauthorized {
			t.Fatalf("disabled user %s status = %d", endpoint, response.Code)
		}
	}
}

func TestAdminRoutesExposeDocumentedEnvelopesAndSafeAuditMetadata(t *testing.T) {
	adminID, targetID := uuid.New(), uuid.New()
	createdAt := time.Date(2026, 8, 27, 12, 0, 0, 0, time.UTC)
	store := &adminContractStore{
		enabled: true,
		users:   []domain.User{{ID: targetID, Email: "person@example.test", Role: string(domain.RoleUser), CreatedAt: createdAt}},
		auditLogs: []domain.AuditLog{{
			ID: uuid.New(), AdminID: adminID, Action: "user.disabled", TargetType: "user", TargetID: &targetID,
			Metadata: map[string]any{"canary_secret": "audit-metadata-canary"}, CreatedAt: createdAt,
		}},
	}
	router, issuer, now := newAdminContractRouter(t, store)
	token := adminAccessToken(t, issuer, adminID, domain.RoleAdmin, now)

	users := adminRequest(router, "/api/v1/admin/users?q=person%40example.test&limit=25&offset=75", token)
	if users.Code != http.StatusOK || !strings.HasPrefix(users.Header().Get("Content-Type"), "application/json") {
		t.Fatalf("users response status/content type = %d/%q", users.Code, users.Header().Get("Content-Type"))
	}
	var userEnvelope map[string]json.RawMessage
	if err := json.Unmarshal(users.Body.Bytes(), &userEnvelope); err != nil {
		t.Fatal(err)
	}
	if len(userEnvelope) != 1 || userEnvelope["users"] == nil || store.userSearch != "person@example.test" || store.userLimit != 25 || store.userOffset != 75 {
		t.Fatalf("users envelope or query forwarding failed")
	}
	var decodedUsers []domain.User
	if err := json.Unmarshal(userEnvelope["users"], &decodedUsers); err != nil || len(decodedUsers) != 1 || decodedUsers[0].Email != "person@example.test" {
		t.Fatalf("users item contract failed")
	}

	audit := adminRequest(router, "/api/v1/admin/audit-logs?limit=20&offset=40", token)
	if audit.Code != http.StatusOK || strings.Contains(audit.Body.String(), "audit-metadata-canary") {
		t.Fatalf("audit response leaked metadata or returned status %d", audit.Code)
	}
	var auditEnvelope map[string]json.RawMessage
	if err := json.Unmarshal(audit.Body.Bytes(), &auditEnvelope); err != nil {
		t.Fatal(err)
	}
	if len(auditEnvelope) != 1 || auditEnvelope["audit_logs"] == nil || store.auditLimit != 20 || store.auditOffset != 40 {
		t.Fatalf("audit envelope or query forwarding failed")
	}
	var decodedAudit []struct {
		ID       string         `json:"id"`
		Metadata map[string]any `json:"metadata"`
	}
	if err := json.Unmarshal(auditEnvelope["audit_logs"], &decodedAudit); err != nil || len(decodedAudit) != 1 || decodedAudit[0].ID == "" || len(decodedAudit[0].Metadata) != 0 {
		t.Fatalf("audit item safe contract failed")
	}
}

func TestAdminRoutesHideStoreFailures(t *testing.T) {
	store := &adminContractStore{enabled: true, usersErr: errors.New("users store failure"), auditLogsErr: errors.New("audit store failure")}
	router, issuer, now := newAdminContractRouter(t, store)
	token := adminAccessToken(t, issuer, uuid.New(), domain.RoleAdmin, now)

	for _, endpoint := range []string{"/api/v1/admin/users", "/api/v1/admin/audit-logs"} {
		response := adminRequest(router, endpoint, token)
		if response.Code != http.StatusInternalServerError || strings.Contains(response.Body.String(), "store failure") {
			t.Fatalf("%s status or error exposure invalid", endpoint)
		}
	}
}

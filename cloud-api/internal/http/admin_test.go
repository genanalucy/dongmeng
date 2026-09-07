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
	enabled           bool
	enabledErr        error
	authVersion       int
	users             []domain.User
	auditLogs         []domain.AuditLog
	entitlements      []domain.Entitlement
	codeBatches       []domain.CodeBatch
	usersErr          error
	auditLogsErr      error
	userSearch        string
	userLimit         int
	userOffset        int
	auditLimit        int
	auditOffset       int
	disabledBatch     uuid.UUID
	phoneUser         domain.User
	phoneHash         string
	phoneQuery        string
	emailQuery        string
	usernameQuery     string
	emailCalls        int
	usernameCalls     int
	lookupErr         error
	register          domain.RegisterParams
	registerErr       error
	reservedEmail     string
	storedEmails      []string
	phoneCalls        int
	refreshes         []domain.RefreshToken
	setupEnabled      bool
	setupEnabledErr   error
	setupErr          error
	setupParams       domain.AdminSetupParams
	passwordHash      string
	passwordHashErr   error
	passwordChange    domain.AdminPasswordChangeParams
	passwordChanged   bool
	passwordChangeErr error
}

func (s *adminContractStore) AdminSetupEnabled(context.Context, time.Time) (bool, error) {
	return s.setupEnabled, s.setupEnabledErr
}

func (s *adminContractStore) CreateAdminSetupChallenge(context.Context, domain.CreateAdminSetupChallengeParams) error {
	return errors.New("not implemented")
}

func (s *adminContractStore) CompleteAdminSetup(_ context.Context, params domain.AdminSetupParams) (domain.User, error) {
	s.setupParams = params
	if s.setupErr != nil {
		return domain.User{}, s.setupErr
	}
	return domain.User{ID: uuid.New(), Username: params.Username, Email: params.Email, Role: string(domain.RoleAdmin), CreatedAt: params.Now}, nil
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
	s.phoneCalls++
	s.phoneQuery = phone
	if s.lookupErr != nil {
		return domain.User{}, "", s.lookupErr
	}
	if s.phoneUser.ID == uuid.Nil {
		return domain.User{}, "", domain.ErrNotFound
	}
	return s.phoneUser, s.phoneHash, nil
}
func (s *adminContractStore) UserByEmail(_ context.Context, email string) (domain.User, string, error) {
	s.emailCalls++
	s.emailQuery = email
	if s.lookupErr != nil {
		return domain.User{}, "", s.lookupErr
	}
	if s.phoneUser.ID == uuid.Nil {
		return domain.User{}, "", domain.ErrNotFound
	}
	return s.phoneUser, s.phoneHash, nil
}
func (s *adminContractStore) UserByUsername(_ context.Context, username string) (domain.User, string, error) {
	s.usernameCalls++
	s.usernameQuery = username
	if s.lookupErr != nil {
		return domain.User{}, "", s.lookupErr
	}
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
	s.storedEmails = append(s.storedEmails, params.Email)
	user := domain.User{ID: uuid.New(), Username: params.Username, Email: params.Email, Phone: params.Phone, Role: string(domain.RoleUser), CreatedAt: params.Now}
	trial, _ := domain.NewTrialEntitlement(uuid.New(), user.ID, params.Now)
	return user, trial, nil
}

func (s *adminContractStore) UserAuthState(context.Context, uuid.UUID) (domain.UserAuthState, error) {
	return domain.UserAuthState{Enabled: s.enabled, AuthVersion: s.authVersion}, s.enabledErr
}

func (s *adminContractStore) UserPasswordHash(context.Context, uuid.UUID) (string, error) {
	return s.passwordHash, s.passwordHashErr
}

func (s *adminContractStore) ChangeAdminPassword(_ context.Context, params domain.AdminPasswordChangeParams) error {
	s.passwordChange = params
	s.passwordChanged = true
	return s.passwordChangeErr
}

func (s *adminContractStore) ListUsers(_ context.Context, search string, limit, offset int) ([]domain.User, error) {
	s.userSearch, s.userLimit, s.userOffset = search, limit, offset
	return s.users, s.usersErr
}

func (s *adminContractStore) ListAuditLogs(_ context.Context, limit, offset int) ([]domain.AuditLog, error) {
	s.auditLimit, s.auditOffset = limit, offset
	return s.auditLogs, s.auditLogsErr
}

func (s *adminContractStore) ListEntitlements(context.Context, uuid.UUID, int, int) ([]domain.Entitlement, error) {
	return s.entitlements, nil
}

func (s *adminContractStore) ListCodeBatches(context.Context, int, int) ([]domain.CodeBatch, error) {
	return s.codeBatches, nil
}

func (s *adminContractStore) DisableCodeBatch(_ context.Context, _ uuid.UUID, batchID uuid.UUID, _ time.Time) error {
	s.disabledBatch = batchID
	return nil
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

func (s *adminContractStore) RevokeTranslationSessionByAdmin(context.Context, uuid.UUID, uuid.UUID, uuid.UUID, time.Time) error {
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
	var decodedUsers []struct {
		ID    uuid.UUID `json:"id"`
		Email string    `json:"email"`
		Role  string    `json:"role"`
	}
	if err := json.Unmarshal(userEnvelope["users"], &decodedUsers); err != nil || len(decodedUsers) != 1 || decodedUsers[0].ID != targetID || decodedUsers[0].Email != "pn***@example.test" || decodedUsers[0].Role != string(domain.RoleUser) || strings.Contains(users.Body.String(), "person@example.test") {
		t.Fatal("users item contract failed")
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

func TestAdminOperationalRoutesExposeEntitlementsAndCodeBatches(t *testing.T) {
	adminID, userID, batchID := uuid.New(), uuid.New(), uuid.New()
	now := time.Date(2026, 9, 7, 12, 0, 0, 0, time.UTC)
	store := &adminContractStore{
		enabled:      true,
		entitlements: []domain.Entitlement{{ID: uuid.New(), UserID: userID, Kind: string(domain.EntitlementPackage), StartsAt: now, ExpiresAt: now.Add(domain.RedemptionDuration)}},
		codeBatches:  []domain.CodeBatch{{ID: batchID, Name: "internal", DurationDays: 365, CreatedBy: adminID, CreatedAt: now, TotalCodes: 1, UnredeemedCodes: 1}},
	}
	router, issuer, tokenTime := newAdminContractRouter(t, store)
	token := adminAccessToken(t, issuer, adminID, domain.RoleAdmin, tokenTime)

	entitlements := adminRequest(router, "/api/v1/admin/users/"+userID.String()+"/entitlements", token)
	if entitlements.Code != http.StatusOK || !strings.Contains(entitlements.Body.String(), `"entitlements"`) || !strings.Contains(entitlements.Body.String(), `"kind":"package"`) {
		t.Fatalf("entitlements response = %d %s", entitlements.Code, entitlements.Body.String())
	}
	batches := adminRequest(router, "/api/v1/admin/code-batches", token)
	if batches.Code != http.StatusOK || !strings.Contains(batches.Body.String(), `"code_batches"`) || !strings.Contains(batches.Body.String(), `"unredeemed_codes":1`) {
		t.Fatalf("batches response = %d %s", batches.Code, batches.Body.String())
	}

	req := httptest.NewRequest(http.MethodPost, "/api/v1/admin/code-batches/"+batchID.String()+"/disable", nil)
	req.RemoteAddr = "127.0.0.1:12345"
	req.Header.Set("Authorization", "Bearer "+token)
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)
	if response.Code != http.StatusNoContent || store.disabledBatch != batchID {
		t.Fatalf("disable response = %d, batch = %s", response.Code, store.disabledBatch)
	}
}

func TestAdminUsersHidePhoneAndReservedEmailForLegacyAndPhoneRecords(t *testing.T) {
	adminID := uuid.New()
	store := &adminContractStore{enabled: true, users: []domain.User{
		{ID: uuid.New(), Email: "legacy@example.test", Role: string(domain.RoleUser), CreatedAt: time.Now()},
		{ID: uuid.New(), Username: "alice_01", Phone: "+8613800138000", Email: "phone-internal@reserved.invalid", Role: string(domain.RoleUser), CreatedAt: time.Now()},
	}}
	router, issuer, now := newAdminContractRouter(t, store)
	response := adminRequest(router, "/api/v1/admin/users", adminAccessToken(t, issuer, adminID, domain.RoleAdmin, now))

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d", response.Code)
	}
	body := response.Body.String()
	if strings.Contains(body, "\"phone\"") || strings.Contains(body, "+8613800138000") || strings.Contains(body, "phone-internal@reserved.invalid") {
		t.Fatal("admin response leaked a phone identity key or value")
	}
	var envelope struct {
		Users []map[string]json.RawMessage `json:"users"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &envelope); err != nil || len(envelope.Users) != 2 {
		t.Fatal("admin response has an invalid user envelope")
	}
	legacy, phone := envelope.Users[0], envelope.Users[1]
	if string(legacy["email"]) != "\"ly***@example.test\"" || legacy["phone"] != nil || phone["email"] != nil || phone["phone"] != nil || string(phone["username"]) != "\"alice_01\"" || strings.Contains(body, "legacy@example.test") {
		t.Fatal("admin user DTO did not preserve legacy email or hide phone identities")
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

func TestAdminSetupStatusAndCompletionContract(t *testing.T) {
	store := &adminContractStore{setupEnabled: false}
	router, _, _ := newAdminContractRouter(t, store)
	status := func() *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/setup/status", nil)
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		return response
	}
	if response := status(); response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"enabled":false`) {
		t.Fatalf("disabled setup status = %d %s", response.Code, response.Body.String())
	}
	store.setupEnabled = true
	if response := status(); response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"enabled":true`) {
		t.Fatalf("enabled setup status = %d %s", response.Code, response.Body.String())
	}

	post := func(body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/admin/setup", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		return response
	}
	if response := post(`{"setup_token":"bad","username":"admin_01","email":"admin@example.test","password":"password1"}`); response.Code != http.StatusBadRequest {
		t.Fatalf("invalid setup request = %d", response.Code)
	}

	plaintext, hash, err := auth.RandomSecret(auth.MinimumSecretBytes)
	if err != nil {
		t.Fatal(err)
	}
	request := `{"setup_token":"` + plaintext + `","username":" Admin_01 ","email":" ADMIN@example.test ","password":"password1"}`
	store.setupEnabled = false
	if response := post(request); response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), "setup_unavailable") {
		t.Fatalf("setup without redeemable challenge = %d %s", response.Code, response.Body.String())
	}
	if len(store.setupParams.TokenHash) != 0 {
		t.Fatal("setup completion ran without a redeemable challenge")
	}
	store.setupEnabledErr = errors.New("probe failure")
	if response := post(request); response.Code != http.StatusInternalServerError {
		t.Fatalf("setup availability store failure = %d %s", response.Code, response.Body.String())
	}
	store.setupEnabledErr = nil
	store.setupEnabled = true
	store.setupErr = domain.ErrSetupUnavailable
	if response := post(request); response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), "setup_unavailable") {
		t.Fatalf("unavailable setup request = %d %s", response.Code, response.Body.String())
	}
	store.setupErr = nil
	if response := post(request); response.Code != http.StatusCreated || !strings.Contains(response.Body.String(), "configured") {
		t.Fatalf("successful setup request = %d %s", response.Code, response.Body.String())
	}
	if !auth.SecretHashEqual(store.setupParams.TokenHash, hash) || string(store.setupParams.TokenHash) == plaintext {
		t.Fatal("setup plaintext token reached the store")
	}
	if store.setupParams.Username != "admin_01" || store.setupParams.Email != "admin@example.test" || store.setupParams.PasswordHash == "password1" {
		t.Fatalf("setup input was not normalized and hashed: %#v", store.setupParams)
	}
}

func adminPasswordRequest(router http.Handler, token, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/api/v1/admin/password", strings.NewReader(body))
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

func TestAdminPasswordChangeEnforcesAuthenticationRoleAndEnabledUser(t *testing.T) {
	adminID := uuid.New()
	hash, err := auth.HashPassword("fixture-current-password")
	if err != nil {
		t.Fatal(err)
	}
	store := &adminContractStore{enabled: true, passwordHash: hash}
	router, issuer, now := newAdminContractRouter(t, store)
	userToken := adminAccessToken(t, issuer, uuid.New(), domain.RoleUser, now)
	adminToken := adminAccessToken(t, issuer, adminID, domain.RoleAdmin, now)
	body := `{"current_password":"fixture-current-password","new_password":"replacement-password-01"}`

	if response := adminPasswordRequest(router, "", body); response.Code != http.StatusUnauthorized {
		t.Fatalf("missing token status = %d", response.Code)
	}
	if response := adminPasswordRequest(router, "invalid-token", body); response.Code != http.StatusUnauthorized {
		t.Fatalf("invalid token status = %d", response.Code)
	}
	if response := adminPasswordRequest(router, userToken, body); response.Code != http.StatusForbidden || strings.Contains(response.Body.String(), "invalid_current_password") {
		t.Fatalf("user role status = %d %s", response.Code, response.Body.String())
	}
	store.enabled = false
	if response := adminPasswordRequest(router, adminToken, body); response.Code != http.StatusUnauthorized {
		t.Fatalf("disabled admin status = %d", response.Code)
	}
	if store.passwordChanged {
		t.Fatal("disabled admin reached the password change store call")
	}
	store.enabled = true
	if response := adminPasswordRequest(router, adminToken, body); response.Code != http.StatusNoContent {
		t.Fatalf("admin status = %d %s", response.Code, response.Body.String())
	}
	if !store.passwordChanged || store.passwordChange.AdminID != adminID {
		t.Fatalf("store call = %+v", store.passwordChange)
	}
}

func TestAdminPasswordChangeRejectsBadInputWithoutTouchingStore(t *testing.T) {
	adminID := uuid.New()
	hash, err := auth.HashPassword("fixture-current-password")
	if err != nil {
		t.Fatal(err)
	}
	store := &adminContractStore{enabled: true, passwordHash: hash}
	router, issuer, now := newAdminContractRouter(t, store)
	token := adminAccessToken(t, issuer, adminID, domain.RoleAdmin, now)

	for _, test := range []struct {
		name string
		body string
		code int
	}{
		{name: "unknown field", body: `{"current_password":"fixture-current-password","new_password":"replacement-password-01","extra":"x"}`, code: http.StatusBadRequest},
		{name: "missing field", body: `{"current_password":"fixture-current-password"}`, code: http.StatusBadRequest},
		{name: "empty current password", body: `{"current_password":"","new_password":"replacement-password-01"}`, code: http.StatusBadRequest},
		{name: "weak new password", body: `{"current_password":"fixture-current-password","new_password":"short"}`, code: http.StatusBadRequest},
		{name: "oversized new password", body: `{"current_password":"fixture-current-password","new_password":"` + strings.Repeat("x", 300) + `"}`, code: http.StatusBadRequest},
		{name: "wrong current password", body: `{"current_password":"wrong-current-password","new_password":"replacement-password-01"}`, code: http.StatusForbidden},
		{name: "unchanged password", body: `{"current_password":"fixture-current-password","new_password":"fixture-current-password"}`, code: http.StatusBadRequest},
	} {
		t.Run(test.name, func(t *testing.T) {
			store.passwordChanged = false
			response := adminPasswordRequest(router, token, test.body)
			if response.Code != test.code {
				t.Fatalf("status = %d %s, want %d", response.Code, response.Body.String(), test.code)
			}
			if store.passwordChanged {
				t.Fatal("rejected request reached the store")
			}
		})
	}

	store.passwordChanged = false
	response := adminPasswordRequest(router, token, `{"current_password":"fixture-current-password","new_password":"replacement-password-01","csrf":"1"}`)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("unknown field status = %d", response.Code)
	}
	if store.passwordChanged {
		t.Fatal("unknown field request reached the store")
	}
}

func TestAdminPasswordChangeRejectsBodiesAboveDedicatedLimit(t *testing.T) {
	adminID := uuid.New()
	hash, err := auth.HashPassword("fixture-current-password")
	if err != nil {
		t.Fatal(err)
	}
	store := &adminContractStore{enabled: true, passwordHash: hash}
	router, issuer, now := newAdminContractRouter(t, store)
	token := adminAccessToken(t, issuer, adminID, domain.RoleAdmin, now)

	// 16 KiB of credentials is far beyond any legitimate request yet below
	// the generic 1 MiB decode ceiling, so this proves the dedicated limit.
	body := `{"current_password":"` + strings.Repeat("x", 16<<10) + `","new_password":"replacement-password-01"}`
	response := adminPasswordRequest(router, token, body)
	if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "invalid_request") {
		t.Fatalf("oversized body = %d %s", response.Code, response.Body.String())
	}
	if store.passwordChanged {
		t.Fatal("oversized request reached the store")
	}
}

func TestAdminPasswordChangeStableErrorCodes(t *testing.T) {
	adminID := uuid.New()
	hash, err := auth.HashPassword("fixture-current-password")
	if err != nil {
		t.Fatal(err)
	}
	body := `{"current_password":"fixture-current-password","new_password":"replacement-password-01"}`

	wrongCurrent := &adminContractStore{enabled: true, passwordHash: hash}
	router, issuer, now := newAdminContractRouter(t, wrongCurrent)
	response := adminPasswordRequest(router, adminAccessToken(t, issuer, adminID, domain.RoleAdmin, now), `{"current_password":"not-the-current-password","new_password":"replacement-password-01"}`)
	if response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), "invalid_current_password") {
		t.Fatalf("wrong current password = %d %s", response.Code, response.Body.String())
	}
	if strings.Contains(response.Body.String(), "argon2") || strings.Contains(response.Body.String(), "hash") {
		t.Fatalf("wrong current password response leaked internals: %s", response.Body.String())
	}

	unchanged := &adminContractStore{enabled: true, passwordHash: hash}
	router, issuer, now = newAdminContractRouter(t, unchanged)
	response = adminPasswordRequest(router, adminAccessToken(t, issuer, adminID, domain.RoleAdmin, now), `{"current_password":"fixture-current-password","new_password":"fixture-current-password"}`)
	if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "password_unchanged") {
		t.Fatalf("unchanged password = %d %s", response.Code, response.Body.String())
	}

	store := &adminContractStore{enabled: true, passwordHash: hash}
	router, issuer, now = newAdminContractRouter(t, store)
	token := adminAccessToken(t, issuer, adminID, domain.RoleAdmin, now)
	for _, test := range []struct {
		name string
		err  error
		code int
		text string
	}{
		{name: "concurrent change", err: domain.ErrConflict, code: http.StatusConflict, text: "conflict"},
		{name: "no longer admin", err: domain.ErrForbidden, code: http.StatusForbidden, text: "forbidden"},
		{name: "store failure", err: errors.New("password store failure"), code: http.StatusInternalServerError, text: "internal_error"},
	} {
		t.Run(test.name, func(t *testing.T) {
			store.passwordChangeErr = test.err
			defer func() { store.passwordChangeErr = nil }()
			response := adminPasswordRequest(router, token, body)
			if response.Code != test.code || !strings.Contains(response.Body.String(), test.text) {
				t.Fatalf("status = %d %s, want %d containing %q", response.Code, response.Body.String(), test.code, test.text)
			}
			if strings.Contains(response.Body.String(), "password store failure") {
				t.Fatal("store failure detail leaked")
			}
		})
	}
}

func TestAdminPasswordChangeForwardsOnlyHashesToStore(t *testing.T) {
	adminID := uuid.New()
	hash, err := auth.HashPassword("fixture-current-password")
	if err != nil {
		t.Fatal(err)
	}
	store := &adminContractStore{enabled: true, passwordHash: hash}
	router, issuer, now := newAdminContractRouter(t, store)
	token := adminAccessToken(t, issuer, adminID, domain.RoleAdmin, now)

	response := adminPasswordRequest(router, token, `{"current_password":"fixture-current-password","new_password":"replacement-password-01"}`)
	if response.Code != http.StatusNoContent || response.Body.Len() != 0 {
		t.Fatalf("success = %d %q", response.Code, response.Body.String())
	}
	if !store.passwordChanged {
		t.Fatal("store was not called")
	}
	if store.passwordChange.CurrentHash != hash {
		t.Fatal("store did not receive the verified current hash as the CAS guard")
	}
	if store.passwordChange.NewHash == "" || store.passwordChange.NewHash == "replacement-password-01" || strings.Contains(store.passwordChange.NewHash, "replacement") {
		t.Fatal("store received plaintext or empty new password instead of a hash")
	}
	if store.passwordChange.CurrentHash == store.passwordChange.NewHash {
		t.Fatal("identical current and new hashes forwarded")
	}
	if !store.passwordChange.Now.Equal(now.UTC()) {
		t.Fatalf("store now = %s, want %s", store.passwordChange.Now, now.UTC())
	}
}

func TestRequireRejectsStaleAuthVersionImmediately(t *testing.T) {
	adminID := uuid.New()
	store := &adminContractStore{enabled: true, authVersion: 1, users: []domain.User{{ID: adminID, Username: "admin_01", Role: string(domain.RoleAdmin), CreatedAt: time.Now()}}}
	router, issuer, now := newAdminContractRouter(t, store)

	legacyToken, err := issuer.AccessToken(adminID, string(domain.RoleAdmin), time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	if response := adminRequest(router, "/api/v1/admin/users", legacyToken); response.Code != http.StatusUnauthorized {
		t.Fatalf("pre-change access token status = %d, want 401 after version bump", response.Code)
	}

	currentToken, err := issuer.AccessTokenVersioned(adminID, string(domain.RoleAdmin), 1, time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	if response := adminRequest(router, "/api/v1/admin/users", currentToken); response.Code != http.StatusOK {
		t.Fatalf("current-version access token status = %d", response.Code)
	}

	store.authVersion = 2
	if response := adminRequest(router, "/api/v1/admin/users", currentToken); response.Code != http.StatusUnauthorized {
		t.Fatalf("access token survived a later password change: %d", response.Code)
	}
}

func TestRequireStillAcceptsPreMigrationTokensAgainstDefaultVersion(t *testing.T) {
	adminID := uuid.New()
	store := &adminContractStore{enabled: true, users: []domain.User{{ID: adminID, Username: "admin_01", Role: string(domain.RoleAdmin), CreatedAt: time.Now()}}}
	router, issuer, now := newAdminContractRouter(t, store)
	token, err := issuer.AccessToken(adminID, string(domain.RoleAdmin), time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	if response := adminRequest(router, "/api/v1/admin/users", token); response.Code != http.StatusOK {
		t.Fatalf("pre-migration token status = %d, want 200 while version stays 0", response.Code)
	}
}

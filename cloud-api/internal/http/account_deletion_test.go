package httpapi

import (
	"context"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
)

type accountDeletionTestStore struct {
	adminContractStore
	deleteInput domain.DeleteAccountParams
	deleteCalls int
	deleteErr   error
}

func (s *accountDeletionTestStore) DeleteAccount(_ context.Context, x domain.DeleteAccountParams) error {
	s.deleteCalls++
	s.deleteInput = x
	return s.deleteErr
}

func newAccountDeletionRouter(t *testing.T, store *accountDeletionTestStore) (http.Handler, auth.TokenIssuer, time.Time) {
	t.Helper()
	issuer := auth.TokenIssuer{
		Issuer:          "cloud-api-test",
		Audience:        "cloud-api-clients",
		SessionAudience: "translator-agent",
		AccessSecret:    []byte("test-access-signing-key-at-least-32b"),
		SessionSecret:   []byte("test-session-signing-key-at-least-32b"),
	}
	now := time.Date(2026, 9, 2, 12, 0, 0, 0, time.UTC)
	router := NewRouter(RouterOptions{Config: configForTest(), Store: store, Tokens: issuer, Now: func() time.Time { return now }})
	return router, issuer, now
}

func TestDeleteAccountConfirmsExactCanonicalUsernameForTokenSubjectOnly(t *testing.T) {
	owner := uuid.New()
	store := &accountDeletionTestStore{adminContractStore: adminContractStore{enabled: true}}
	router, issuer, now := newAccountDeletionRouter(t, store)
	response := accountRequest(router, http.MethodDelete, "/api/v1/account", adminAccessToken(t, issuer, owner, domain.RoleUser, now), `{"username":" Deletion_01 "}`)
	if response.Code != http.StatusNoContent || response.Body.Len() != 0 {
		t.Fatalf("status/body = %d/%q", response.Code, response.Body.String())
	}
	if store.deleteCalls != 1 || store.deleteInput.UserID != owner || store.deleteInput.Username != "deletion_01" || !store.deleteInput.Now.Equal(now) {
		t.Fatalf("store input = %#v (calls=%d)", store.deleteInput, store.deleteCalls)
	}
}

func TestDeleteAccountRejectsMalformedRequestsBeforeStore(t *testing.T) {
	owner := uuid.New()
	for _, test := range []struct {
		name string
		body string
	}{
		{name: "empty username", body: `{"username":""}`},
		{name: "username not parseable", body: `{"username":"no"}`},
		{name: "digits only username", body: `{"username":"123456"}`},
		{name: "unknown field", body: `{"username":"deletion_01","user_id":"` + uuid.NewString() + `"}`},
		{name: "malformed json", body: `{"username":`},
	} {
		t.Run(test.name, func(t *testing.T) {
			store := &accountDeletionTestStore{adminContractStore: adminContractStore{enabled: true}}
			router, issuer, now := newAccountDeletionRouter(t, store)
			response := accountRequest(router, http.MethodDelete, "/api/v1/account", adminAccessToken(t, issuer, owner, domain.RoleUser, now), test.body)
			if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "invalid_request") || store.deleteCalls != 0 {
				t.Fatalf("response = %d %s (store calls=%d)", response.Code, response.Body.String(), store.deleteCalls)
			}
		})
	}
	store := &accountDeletionTestStore{adminContractStore: adminContractStore{enabled: true}}
	router, issuer, now := newAccountDeletionRouter(t, store)
	if response := accountRequest(router, http.MethodDelete, "/api/v1/account", adminAccessToken(t, issuer, owner, domain.RoleUser, now), ""); response.Code != http.StatusBadRequest || store.deleteCalls != 0 {
		t.Fatalf("missing content type response = %d %s", response.Code, response.Body.String())
	}
}

func TestDeleteAccountRejectsAdminPrincipalsWithoutStoreCall(t *testing.T) {
	admin := uuid.New()
	store := &accountDeletionTestStore{adminContractStore: adminContractStore{enabled: true}}
	router, issuer, now := newAccountDeletionRouter(t, store)
	response := accountRequest(router, http.MethodDelete, "/api/v1/account", adminAccessToken(t, issuer, admin, domain.RoleAdmin, now), `{"username":"admin_01"}`)
	if response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), "forbidden") || store.deleteCalls != 0 {
		t.Fatalf("admin response = %d %s (store calls=%d)", response.Code, response.Body.String(), store.deleteCalls)
	}
}

func TestDeleteAccountRequiresAuthenticationAndEnabledSubject(t *testing.T) {
	owner := uuid.New()
	store := &accountDeletionTestStore{adminContractStore: adminContractStore{enabled: true}}
	router, issuer, now := newAccountDeletionRouter(t, store)
	if response := accountRequest(router, http.MethodDelete, "/api/v1/account", "", `{"username":"deletion_01"}`); response.Code != http.StatusUnauthorized || store.deleteCalls != 0 {
		t.Fatalf("anonymous response = %d %s", response.Code, response.Body.String())
	}
	// The persisted lifecycle disabled the account as part of deletion, so the
	// same bearer token must stop authorizing every protected route, including
	// a repeated deletion request.
	store.enabled = false
	token := adminAccessToken(t, issuer, owner, domain.RoleUser, now)
	for _, path := range []string{"/api/v1/account", "/api/v1/users/me", "/api/v1/account/overview"} {
		method := http.MethodGet
		if path == "/api/v1/account" {
			method = http.MethodDelete
		}
		response := accountRequest(router, method, path, token, "")
		if response.Code != http.StatusUnauthorized {
			t.Fatalf("%s after deletion = %d %s", path, response.Code, response.Body.String())
		}
	}
	if store.deleteCalls != 0 {
		t.Fatalf("disabled subject reached the store %d times", store.deleteCalls)
	}
}

func TestDeleteAccountMapsDomainFailuresToSafeGenericResponses(t *testing.T) {
	owner := uuid.New()
	for _, test := range []struct {
		name string
		err  error
	}{
		{name: "confirmation mismatch", err: domain.ErrConflict},
		{name: "missing account", err: domain.ErrNotFound},
		{name: "store role check", err: domain.ErrForbidden},
		{name: "invalid arguments", err: domain.ErrInvalid},
		{name: "internal failure", err: context.DeadlineExceeded},
	} {
		t.Run(test.name, func(t *testing.T) {
			store := &accountDeletionTestStore{adminContractStore: adminContractStore{enabled: true}, deleteErr: test.err}
			router, issuer, now := newAccountDeletionRouter(t, store)
			response := accountRequest(router, http.MethodDelete, "/api/v1/account", adminAccessToken(t, issuer, owner, domain.RoleUser, now), `{"username":"deletion_01"}`)
			if response.Code < 400 || response.Code == http.StatusNoContent {
				t.Fatalf("expected failure, got %d %s", response.Code, response.Body.String())
			}
			// Failure bodies stay generic: the echoed username, any identity,
			// or store detail must never appear.
			if strings.Contains(response.Body.String(), "deletion_01") {
				t.Fatalf("failure leaked the confirmed username: %s", response.Body.String())
			}
		})
	}
}

func TestDeleteAccountIsSubjectScopedAndNeverUsesClientTargets(t *testing.T) {
	// A client cannot point the deletion at another account: the request has
	// no identifier field other than the confirmation username, and the store
	// receives only the token subject.
	owner := uuid.New()
	store := &accountDeletionTestStore{adminContractStore: adminContractStore{enabled: true}}
	router, issuer, now := newAccountDeletionRouter(t, store)
	response := accountRequest(router, http.MethodDelete, "/api/v1/account?user_id="+uuid.NewString(), adminAccessToken(t, issuer, owner, domain.RoleUser, now), `{"username":"deletion_01"}`)
	if response.Code != http.StatusNoContent || store.deleteInput.UserID != owner {
		t.Fatalf("response/subject = %d/%s", response.Code, store.deleteInput.UserID)
	}
}

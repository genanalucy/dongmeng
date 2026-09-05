package auth

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
)

// reasonLeakStore wraps the lifecycle store with an arbitrary termination
// reason to prove the facade allowlist cannot be bypassed by a buggy or
// hostile store implementation.
type reasonLeakStore struct {
	*lifecycleSessionStore
	leakedReason domain.TranslationTerminationReason
}

func (s reasonLeakStore) TranslationSessionState(ctx context.Context, user, session, entitlement, jti uuid.UUID, now time.Time) (domain.TranslationSessionAuthorization, error) {
	state, err := s.lifecycleSessionStore.TranslationSessionState(ctx, user, session, entitlement, jti, now)
	if err == nil && !state.Active {
		state.TerminationReason = s.leakedReason
	}
	return state, err
}

func terminatedReason(t *testing.T, err error) (domain.TranslationTerminationReason, bool) {
	t.Helper()
	var terminal domain.TerminatedTranslationSessionError
	if !errors.As(err, &terminal) {
		return "", false
	}
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("terminal reason error does not satisfy errors.Is(err, domain.ErrUnauthorized): %v", err)
	}
	return terminal.Reason, true
}

// TestAuthorizeTranslationSessionPropagatesSafeTerminalReason proves every
// terminal lifecycle path surfaces its reason through the authorization
// boundary as a TerminatedTranslationSessionError that still satisfies
// errors.Is(err, domain.ErrUnauthorized).
func TestAuthorizeTranslationSessionPropagatesSafeTerminalReason(t *testing.T) {
	for _, test := range []struct {
		name      string
		reason    domain.TranslationTerminationReason
		evaluated func(now time.Time) time.Time
		arrange   func(t *testing.T, service AuthorizationService, store *lifecycleSessionStore, grant TranslationGrant, now time.Time)
	}{
		{
			name:      "ended",
			reason:    domain.TerminationEnded,
			evaluated: func(now time.Time) time.Time { return now.Add(time.Minute) },
			arrange: func(t *testing.T, service AuthorizationService, _ *lifecycleSessionStore, grant TranslationGrant, now time.Time) {
				if err := service.EndTranslationSession(context.Background(), grant.Session.UserID, grant.Session.ID, now.Add(time.Second)); err != nil {
					t.Fatal(err)
				}
			},
		},
		{
			name:      "revoked",
			reason:    domain.TerminationRevoked,
			evaluated: func(now time.Time) time.Time { return now.Add(time.Minute) },
			arrange: func(t *testing.T, service AuthorizationService, _ *lifecycleSessionStore, grant TranslationGrant, now time.Time) {
				if err := service.RevokeTranslationSession(context.Background(), grant.Session.UserID, grant.Session.ID, now.Add(time.Second)); err != nil {
					t.Fatal(err)
				}
			},
		},
		{
			name:      "replaced_by_device",
			reason:    domain.TerminationReplacedByDevice,
			evaluated: func(now time.Time) time.Time { return now.Add(time.Minute) },
			arrange: func(t *testing.T, service AuthorizationService, _ *lifecycleSessionStore, grant TranslationGrant, now time.Time) {
				for i := range TwoActiveTranslationSessionLimit {
					if _, err := service.CreateTranslationSession(context.Background(), grant.Session.UserID, fmt.Sprintf("install-extra-%d", i), now.Add(time.Second)); err != nil {
						t.Fatal(err)
					}
				}
			},
		},
		{
			name:      "user_disabled",
			reason:    domain.TerminationUserDisabled,
			evaluated: func(now time.Time) time.Time { return now.Add(time.Minute) },
			arrange: func(_ *testing.T, _ AuthorizationService, store *lifecycleSessionStore, _ TranslationGrant, _ time.Time) {
				store.mu.Lock()
				store.disabled = true
				store.mu.Unlock()
			},
		},
		{
			name:      "entitlement_revoked",
			reason:    domain.TerminationEntitlementRevoked,
			evaluated: func(now time.Time) time.Time { return now.Add(time.Minute) },
			arrange: func(_ *testing.T, _ AuthorizationService, store *lifecycleSessionStore, _ TranslationGrant, _ time.Time) {
				store.mu.Lock()
				store.revoked = true
				store.mu.Unlock()
			},
		},
		{
			name:      "expired",
			reason:    domain.TerminationExpired,
			evaluated: func(now time.Time) time.Time { return now.Add(2 * time.Second) },
			arrange: func(_ *testing.T, _ AuthorizationService, store *lifecycleSessionStore, grant TranslationGrant, now time.Time) {
				store.mu.Lock()
				record := store.sessions[grant.Session.ID]
				record.session.ExpiresAt = now.Add(time.Second)
				store.sessions[grant.Session.ID] = record
				store.mu.Unlock()
			},
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			now := time.Date(2027, time.February, 1, 3, 4, 5, 0, time.UTC)
			userID := uuid.New()
			store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Minute)))
			service := AuthorizationService{Store: store, Tokens: testIssuer(), MaxConcurrentSessions: TwoActiveTranslationSessionLimit}
			grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
			if err != nil {
				t.Fatal(err)
			}
			test.arrange(t, service, store, grant, now)

			claims, err := service.AuthorizeTranslationSession(context.Background(), grant.Token, test.evaluated(now))
			assertEmptyClaims(t, claims)
			reason, matched := terminatedReason(t, err)
			if !matched {
				t.Fatalf("authorization error = %v, want a terminal-reason error", err)
			}
			if reason != test.reason {
				t.Fatalf("terminal reason = %q, want %q", reason, test.reason)
			}
		})
	}
}

// TestAuthorizeTranslationSessionUnknownOrMismatchedTokenStaysGeneric proves
// tokens without a full persisted identity match receive only the bare
// ErrUnauthorized sentinel: no terminal reason may cross the boundary for
// unknown sessions, mismatched JTIs, or store failures.
func TestAuthorizeTranslationSessionUnknownOrMismatchedTokenStaysGeneric(t *testing.T) {
	now := time.Date(2027, time.February, 2, 3, 4, 5, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Minute)))
	service := AuthorizationService{Store: store, Tokens: testIssuer(), MaxConcurrentSessions: TwoActiveTranslationSessionLimit}
	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if err != nil {
		t.Fatal(err)
	}

	// Unknown token: correctly signed identity that matches no persisted session.
	unknown, err := service.Tokens.TranslationTokenForInstall(uuid.New(), uuid.New(), uuid.New(), uuid.New(), "test-install", time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	claims, err := service.AuthorizeTranslationSession(context.Background(), unknown, now.Add(time.Second))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("unknown token error = %v", err)
	}
	if reason, matched := terminatedReason(t, err); matched {
		t.Fatalf("unknown token leaked terminal reason %q", reason)
	}
	assertEmptyClaims(t, claims)

	// Mismatched JTI: valid signature, full identity except the token nonce.
	tampered, err := service.Tokens.TranslationTokenForInstall(grant.Session.ID, grant.Session.EntitlementID, userID, uuid.New(), "test-install", time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	claims, err = service.AuthorizeTranslationSession(context.Background(), tampered, now.Add(time.Second))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("mismatched JTI error = %v", err)
	}
	if reason, matched := terminatedReason(t, err); matched {
		t.Fatalf("mismatched JTI leaked terminal reason %q", reason)
	}
	assertEmptyClaims(t, claims)

	// Store lookup failure: fail-closed generic unauthorized, no reason.
	otherStore := newLifecycleSessionStore(store.entitlement)
	service.SessionAuthorization = otherStore
	claims, err = service.AuthorizeTranslationSession(context.Background(), grant.Token, now.Add(time.Second))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("store failure error = %v", err)
	}
	if reason, matched := terminatedReason(t, err); matched {
		t.Fatalf("store failure leaked terminal reason %q", reason)
	}
	assertEmptyClaims(t, claims)
}

// TestAuthorizeTranslationSessionDropsReasonsOutsideTheAllowlist proves the
// facade only propagates reasons from the fixed safe vocabulary: any other
// value, even after a full identity match, degrades to the bare sentinel.
func TestAuthorizeTranslationSessionDropsReasonsOutsideTheAllowlist(t *testing.T) {
	now := time.Date(2027, time.February, 3, 3, 4, 5, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Minute)))
	service := AuthorizationService{Store: store, Tokens: testIssuer(), MaxConcurrentSessions: TwoActiveTranslationSessionLimit}
	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if err != nil {
		t.Fatal(err)
	}
	if err := service.EndTranslationSession(context.Background(), userID, grant.Session.ID, now.Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	service.SessionAuthorization = reasonLeakStore{lifecycleSessionStore: store, leakedReason: domain.TranslationTerminationReason("admin_note: suspicious device 10.0.0.9")}

	claims, err := service.AuthorizeTranslationSession(context.Background(), grant.Token, now.Add(time.Second))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("non-allowlisted reason error = %v", err)
	}
	if reason, matched := terminatedReason(t, err); matched {
		t.Fatalf("non-allowlisted reason %q crossed the authorization boundary", reason)
	}
	assertEmptyClaims(t, claims)
}

// TestTerminatedTranslationSessionErrorContract pins the typed error contract:
// it satisfies errors.Is against the sentinel, carries the reason, and admits
// exactly the fixed lifecycle vocabulary including the read-time expired
// reason.
func TestTerminatedTranslationSessionErrorContract(t *testing.T) {
	err := domain.TerminatedTranslationSessionError{Reason: domain.TerminationReplacedByDevice}
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatal("terminal reason error must satisfy errors.Is(err, domain.ErrUnauthorized)")
	}
	var terminal domain.TerminatedTranslationSessionError
	if !errors.As(err, &terminal) || terminal.Reason != domain.TerminationReplacedByDevice {
		t.Fatalf("errors.As did not recover the terminal reason: %+v", terminal)
	}
	for _, reason := range []domain.TranslationTerminationReason{
		domain.TerminationEnded, domain.TerminationRevoked, domain.TerminationReplacedByDevice,
		domain.TerminationEntitlementRevoked, domain.TerminationUserDisabled, domain.TerminationExpired,
	} {
		if !domain.SafeTranslationTerminationReason(reason) {
			t.Fatalf("reason %q must be considered safe", reason)
		}
	}
	for _, reason := range []domain.TranslationTerminationReason{"", "garbage", "USER_DISABLED", "ended ", "expired "} {
		if domain.SafeTranslationTerminationReason(reason) {
			t.Fatalf("reason %q must not be considered safe", reason)
		}
	}
}

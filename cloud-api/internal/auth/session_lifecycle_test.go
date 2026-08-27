package auth

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
)

type lifecycleSessionState uint8

const (
	lifecycleSessionActive lifecycleSessionState = iota
	lifecycleSessionEnded
	lifecycleSessionRevoked
)

type lifecycleSessionRecord struct {
	session domain.TranslationSession
	state   lifecycleSessionState
}

type lifecycleSessionStore struct {
	mu          sync.Mutex
	entitlement domain.Entitlement
	sessions    map[uuid.UUID]lifecycleSessionRecord
}

func newLifecycleSessionStore(entitlement domain.Entitlement) *lifecycleSessionStore {
	return &lifecycleSessionStore{entitlement: entitlement, sessions: make(map[uuid.UUID]lifecycleSessionRecord)}
}

func (s *lifecycleSessionStore) Register(context.Context, domain.RegisterParams) (domain.User, domain.Entitlement, error) {
	return domain.User{}, domain.Entitlement{}, domain.ErrConflict
}

func (s *lifecycleSessionStore) ActiveEntitlement(_ context.Context, userID uuid.UUID, now time.Time) (domain.Entitlement, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.entitlement.UserID != userID || !s.entitlement.ActiveAt(now) {
		return domain.Entitlement{}, domain.ErrNoEntitlement
	}
	return s.entitlement, nil
}

func (s *lifecycleSessionStore) CreateAuthorizedTranslationSession(ctx context.Context, session domain.TranslationSession, authorizedAt time.Time) error {
	return s.CreateAuthorizedTranslationSessionWithLimit(ctx, session, authorizedAt, int(^uint(0)>>1))
}

func (s *lifecycleSessionStore) CreateAuthorizedTranslationSessionWithLimit(_ context.Context, session domain.TranslationSession, authorizedAt time.Time, maxConcurrent int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if maxConcurrent < 1 {
		return domain.ErrInvalid
	}
	if s.entitlement.UserID != session.UserID || s.entitlement.ID != session.EntitlementID || !s.entitlement.ActiveAt(authorizedAt) || session.ExpiresAt.After(s.entitlement.ExpiresAt) {
		return domain.ErrNoEntitlement
	}
	active := 0
	for id, record := range s.sessions {
		if record.session.UserID != session.UserID || record.state != lifecycleSessionActive {
			continue
		}
		if !record.session.ExpiresAt.After(authorizedAt) {
			delete(s.sessions, id)
			continue
		}
		active++
	}
	if active >= maxConcurrent {
		return domain.ErrConflict
	}
	if _, exists := s.sessions[session.ID]; exists {
		return domain.ErrConflict
	}
	s.sessions[session.ID] = lifecycleSessionRecord{session: session, state: lifecycleSessionActive}
	return nil
}

func (s *lifecycleSessionStore) EndTranslationSession(_ context.Context, userID, sessionID uuid.UUID, _ time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, exists := s.sessions[sessionID]
	if !exists || record.session.UserID != userID {
		return domain.ErrNotFound
	}
	if record.state == lifecycleSessionRevoked {
		return domain.ErrConflict
	}
	record.state = lifecycleSessionEnded
	s.sessions[sessionID] = record
	return nil
}

func (s *lifecycleSessionStore) RevokeTranslationSession(_ context.Context, userID, sessionID uuid.UUID, _ time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, exists := s.sessions[sessionID]
	if !exists || record.session.UserID != userID {
		return domain.ErrNotFound
	}
	record.state = lifecycleSessionRevoked
	s.sessions[sessionID] = record
	return nil
}

func (s *lifecycleSessionStore) AuthorizeTranslationSession(_ context.Context, userID, sessionID, entitlementID, jti uuid.UUID, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, exists := s.sessions[sessionID]
	if !exists || record.state != lifecycleSessionActive || record.session.UserID != userID || record.session.EntitlementID != entitlementID || record.session.JTI != jti || !record.session.ExpiresAt.After(now) {
		return domain.ErrUnauthorized
	}
	return nil
}

func TestSingleActiveTranslationSessionConcurrentCreationFailsClosed(t *testing.T) {
	now := time.Date(2027, 1, 1, 2, 3, 4, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Hour)))
	service := AuthorizationService{
		Store:                 store,
		Tokens:                testIssuer(),
		MaxConcurrentSessions: SingleActiveTranslationSessionLimit,
	}

	const attempts = 16
	start := make(chan struct{})
	results := make(chan struct {
		grant TranslationGrant
		err   error
	}, attempts)
	var wait sync.WaitGroup
	for range attempts {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
			results <- struct {
				grant TranslationGrant
				err   error
			}{grant: grant, err: err}
		}()
	}
	close(start)
	wait.Wait()
	close(results)

	successes, conflicts := 0, 0
	for result := range results {
		switch {
		case result.err == nil:
			successes++
			if result.grant.Token == "" || result.grant.Session.ID == uuid.Nil {
				t.Fatalf("successful creation returned an empty grant: %+v", result.grant)
			}
		case errors.Is(result.err, domain.ErrConflict):
			conflicts++
			if result.grant != (TranslationGrant{}) {
				t.Fatalf("failed competitor received a grant: %+v", result.grant)
			}
		default:
			t.Fatalf("unexpected creation error: %v", result.err)
		}
	}
	if successes != 1 || conflicts != attempts-1 {
		t.Fatalf("single-active arbitration: successes=%d conflicts=%d", successes, conflicts)
	}
}

func TestTranslationSessionAuthorizationRequiresActivePersistedState(t *testing.T) {
	now := time.Date(2027, 1, 2, 3, 4, 5, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Hour)))
	service := AuthorizationService{Store: store, Tokens: testIssuer(), MaxConcurrentSessions: SingleActiveTranslationSessionLimit}
	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if err != nil {
		t.Fatal(err)
	}

	claims, err := service.AuthorizeTranslationSession(context.Background(), grant.Token, now.Add(time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if claims.Subject != userID.String() || claims.SessionID != grant.Session.ID.String() || claims.ID != grant.Session.JTI.String() {
		t.Fatalf("unexpected authorized claims: %+v", claims)
	}

	store.mu.Lock()
	record := store.sessions[grant.Session.ID]
	record.session.ExpiresAt = now.Add(2 * time.Second)
	store.sessions[grant.Session.ID] = record
	store.mu.Unlock()
	claims, err = service.AuthorizeTranslationSession(context.Background(), grant.Token, now.Add(2*time.Second))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("persisted expiry authorization error = %v", err)
	}
	assertEmptyClaims(t, claims)
}

func TestSingleActiveTranslationSessionEndAndRevokeReleaseSlot(t *testing.T) {
	now := time.Date(2027, 1, 3, 3, 4, 5, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Hour)))
	service := AuthorizationService{Store: store, Tokens: testIssuer(), MaxConcurrentSessions: SingleActiveTranslationSessionLimit}

	first, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now.Add(time.Second)); !errors.Is(err, domain.ErrConflict) {
		t.Fatalf("second active session error = %v", err)
	}
	if err := service.EndTranslationSession(context.Background(), userID, first.Session.ID, now.Add(2*time.Second)); err != nil {
		t.Fatal(err)
	}
	claims, err := service.AuthorizeTranslationSession(context.Background(), first.Token, now.Add(2*time.Second))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("ended session authorization error = %v", err)
	}
	assertEmptyClaims(t, claims)

	second, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now.Add(2*time.Second))
	if err != nil {
		t.Fatalf("ended session did not release slot: %v", err)
	}
	if err := service.RevokeTranslationSession(context.Background(), userID, second.Session.ID, now.Add(3*time.Second)); err != nil {
		t.Fatal(err)
	}
	claims, err = service.AuthorizeTranslationSession(context.Background(), second.Token, now.Add(3*time.Second))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("revoked session authorization error = %v", err)
	}
	assertEmptyClaims(t, claims)
	if _, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now.Add(3*time.Second)); err != nil {
		t.Fatalf("revoked session did not release slot: %v", err)
	}
}

func TestSingleActiveTranslationSessionExpiryReleasesSlot(t *testing.T) {
	now := time.Date(2027, 1, 4, 4, 5, 6, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Hour)))
	service := AuthorizationService{
		Store:                 store,
		Tokens:                testIssuer(),
		TranslationTTL:        2 * time.Second,
		MaxConcurrentSessions: SingleActiveTranslationSessionLimit,
	}

	first, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if err != nil {
		t.Fatal(err)
	}
	boundary := first.Session.ExpiresAt
	claims, err := service.AuthorizeTranslationSession(context.Background(), first.Token, boundary)
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("expired session authorization error = %v", err)
	}
	assertEmptyClaims(t, claims)
	second, err := service.CreateTranslationSession(context.Background(), userID, "test-install", boundary)
	if err != nil {
		t.Fatalf("expired session did not release slot: %v", err)
	}
	if second.Session.ID == first.Session.ID {
		t.Fatal("replacement reused expired session ID")
	}
}

func TestTranslationSessionAuthorizationRejectsValidJWTWithoutPersistentAuthorizer(t *testing.T) {
	now := time.Date(2027, 1, 5, 5, 6, 7, 0, time.UTC)
	issuer := testIssuer()
	token, err := issuer.TranslationTokenForInstall(uuid.New(), uuid.New(), uuid.New(), uuid.New(), "test-install", time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	service := AuthorizationService{Tokens: issuer}
	claims, err := service.AuthorizeTranslationSession(context.Background(), token, now.Add(time.Second))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("missing persistent authorizer error = %v", err)
	}
	assertEmptyClaims(t, claims)
}

func TestTranslationSessionAuthorizationFailsClosedOnStoreErrorOrMismatchedToken(t *testing.T) {
	now := time.Date(2027, 1, 6, 5, 6, 7, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Hour)))
	service := AuthorizationService{Store: store, Tokens: testIssuer(), MaxConcurrentSessions: SingleActiveTranslationSessionLimit}
	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if err != nil {
		t.Fatal(err)
	}

	otherStore := newLifecycleSessionStore(store.entitlement)
	service.SessionAuthorization = otherStore
	claims, err := service.AuthorizeTranslationSession(context.Background(), grant.Token, now.Add(time.Second))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("store miss authorization error = %v", err)
	}
	assertEmptyClaims(t, claims)

	service.SessionAuthorization = store
	tampered, err := service.Tokens.TranslationTokenForInstall(grant.Session.ID, grant.Session.EntitlementID, userID, uuid.New(), "test-install", time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	claims, err = service.AuthorizeTranslationSession(context.Background(), tampered, now.Add(time.Second))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("mismatched JTI authorization error = %v", err)
	}
	assertEmptyClaims(t, claims)
}

func assertEmptyClaims(t *testing.T, claims Claims) {
	t.Helper()
	if claims.Role != "" || claims.SessionID != "" || claims.EntitlementID != "" || claims.Scope != "" || claims.Subject != "" || claims.ID != "" || len(claims.Audience) != 0 || claims.ExpiresAt != nil || claims.IssuedAt != nil || claims.NotBefore != nil {
		t.Fatalf("authorization failure leaked claims: %+v", claims)
	}
}

var _ AuthorizationStore = (*lifecycleSessionStore)(nil)
var _ ConcurrentTranslationSessionStore = (*lifecycleSessionStore)(nil)
var _ TranslationSessionAuthorizationStore = (*lifecycleSessionStore)(nil)
var _ TranslationSessionAuthorizationFacade = AuthorizationService{}

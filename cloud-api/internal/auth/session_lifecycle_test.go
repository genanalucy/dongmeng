package auth

import (
	"context"
	"errors"
	"sort"
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
	session  domain.TranslationSession
	state    lifecycleSessionState
	createAt time.Time
	endedAt  time.Time
	reason   domain.TranslationTerminationReason
}

type lifecycleSessionStore struct {
	mu          sync.Mutex
	disabled    bool
	entitlement domain.Entitlement
	revoked     bool
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
	if s.disabled || s.revoked || s.entitlement.UserID != userID || !s.entitlement.ActiveAt(now) {
		return domain.Entitlement{}, domain.ErrNoEntitlement
	}
	return s.entitlement, nil
}

func (s *lifecycleSessionStore) CreateAuthorizedTranslationSession(ctx context.Context, session domain.TranslationSession, authorizedAt time.Time) error {
	return s.CreateAuthorizedTranslationSessionWithLimit(ctx, session, authorizedAt, int(^uint(0)>>1))
}

// CreateAuthorizedTranslationSessionWithLimit mirrors the persisted two-device
// governance: active sessions past their expiry are ignored, the oldest active
// sessions in the stable (created_at, id) order are ended with the
// device-replacement reason whenever the limit would be exceeded, and the new
// session is stored behind an entitlement re-check.
func (s *lifecycleSessionStore) CreateAuthorizedTranslationSessionWithLimit(_ context.Context, session domain.TranslationSession, authorizedAt time.Time, maxConcurrent int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if maxConcurrent < 1 {
		return domain.ErrInvalid
	}
	if s.entitlement.UserID != session.UserID || s.entitlement.ID != session.EntitlementID || !s.entitlement.ActiveAt(authorizedAt) || session.ExpiresAt.After(s.entitlement.ExpiresAt) {
		return domain.ErrNoEntitlement
	}
	if _, exists := s.sessions[session.ID]; exists {
		return domain.ErrConflict
	}
	active := s.activeIDsLocked(session.UserID, authorizedAt)
	if replace := len(active) - maxConcurrent + 1; replace > 0 {
		for _, id := range active[:replace] {
			record := s.sessions[id]
			record.state = lifecycleSessionEnded
			record.endedAt = authorizedAt
			record.reason = domain.TerminationReplacedByDevice
			s.sessions[id] = record
		}
	}
	s.sessions[session.ID] = lifecycleSessionRecord{session: session, createAt: authorizedAt}
	return nil
}

func (s *lifecycleSessionStore) activeIDsLocked(userID uuid.UUID, now time.Time) []uuid.UUID {
	active := make([]uuid.UUID, 0, len(s.sessions))
	for id, record := range s.sessions {
		if record.session.UserID != userID || record.state != lifecycleSessionActive || !record.session.ExpiresAt.After(now) {
			continue
		}
		active = append(active, id)
	}
	sort.Slice(active, func(i, j int) bool {
		first, second := s.sessions[active[i]], s.sessions[active[j]]
		if first.createAt.Equal(second.createAt) {
			return first.session.ID.String() < second.session.ID.String()
		}
		return first.createAt.Before(second.createAt)
	})
	return active
}

func (s *lifecycleSessionStore) EndTranslationSession(_ context.Context, userID, sessionID uuid.UUID, at time.Time) error {
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
	record.endedAt = at
	if record.reason == "" {
		record.reason = domain.TerminationEnded
	}
	s.sessions[sessionID] = record
	return nil
}

func (s *lifecycleSessionStore) RevokeTranslationSession(_ context.Context, userID, sessionID uuid.UUID, at time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, exists := s.sessions[sessionID]
	if !exists || record.session.UserID != userID {
		return domain.ErrNotFound
	}
	record.state = lifecycleSessionRevoked
	record.endedAt = at
	if record.reason == "" {
		record.reason = domain.TerminationRevoked
	}
	s.sessions[sessionID] = record
	return nil
}

func (s *lifecycleSessionStore) TranslationSessionState(_ context.Context, userID, sessionID, entitlementID, jti uuid.UUID, now time.Time) (domain.TranslationSessionAuthorization, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, exists := s.sessions[sessionID]
	if !exists || record.session.UserID != userID || record.session.EntitlementID != entitlementID || record.session.JTI != jti {
		return domain.TranslationSessionAuthorization{}, domain.ErrUnauthorized
	}
	state := domain.TranslationSessionAuthorization{
		SessionID:         record.session.ID,
		UserID:            record.session.UserID,
		EntitlementID:     record.session.EntitlementID,
		JTI:               record.session.JTI,
		InstallID:         record.session.InstallID,
		ExpiresAt:         record.session.ExpiresAt,
		TerminationReason: record.reason,
	}
	if record.state == lifecycleSessionEnded {
		state.EndedAt = &record.endedAt
	}
	if record.state == lifecycleSessionRevoked {
		revokedAt := record.endedAt
		state.RevokedAt = &revokedAt
	}
	state.Active = !s.disabled && !s.revoked && record.state == lifecycleSessionActive && record.session.ExpiresAt.After(now) && s.entitlement.ActiveAt(now)
	if !state.Active && state.TerminationReason == "" {
		switch {
		case s.disabled:
			state.TerminationReason = domain.TerminationUserDisabled
		case s.revoked || !s.entitlement.ActiveAt(now):
			state.TerminationReason = domain.TerminationEntitlementRevoked
		case !record.session.ExpiresAt.After(now):
			state.TerminationReason = domain.TerminationExpired
		}
	}
	return state, nil
}

func TestTwoActiveTranslationSessionsThirdReplacesOldest(t *testing.T) {
	now := time.Date(2027, 1, 1, 2, 3, 4, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Hour)))
	service := AuthorizationService{
		Store:                 store,
		Tokens:                testIssuer(),
		MaxConcurrentSessions: TwoActiveTranslationSessionLimit,
	}

	first, err := service.CreateTranslationSession(context.Background(), userID, "install-first", now)
	if err != nil {
		t.Fatal(err)
	}
	second, err := service.CreateTranslationSession(context.Background(), userID, "install-second", now.Add(time.Second))
	if err != nil {
		t.Fatalf("second device session creation: %v", err)
	}
	third, err := service.CreateTranslationSession(context.Background(), userID, "install-third", now.Add(2*time.Second))
	if err != nil {
		t.Fatalf("third device session creation must replace the oldest, not fail: %v", err)
	}

	state, err := store.TranslationSessionState(context.Background(), userID, first.Session.ID, first.Session.EntitlementID, first.Session.JTI, now.Add(2*time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if state.Active || state.TerminationReason != domain.TerminationReplacedByDevice {
		t.Fatalf("oldest session state = active:%v reason:%q, want replaced_by_device", state.Active, state.TerminationReason)
	}
	for _, kept := range []TranslationGrant{second, third} {
		state, err := store.TranslationSessionState(context.Background(), userID, kept.Session.ID, kept.Session.EntitlementID, kept.Session.JTI, now.Add(2*time.Second))
		if err != nil || !state.Active {
			t.Fatalf("retained session state = %+v err = %v, want active", state, err)
		}
	}
	if _, err := service.AuthorizeTranslationSession(context.Background(), first.Token, now.Add(2*time.Second)); !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("replaced device authorization error = %v", err)
	}
	for _, kept := range []TranslationGrant{second, third} {
		if _, err := service.AuthorizeTranslationSession(context.Background(), kept.Token, now.Add(2*time.Second)); err != nil {
			t.Fatalf("retained device authorization error = %v", err)
		}
	}
}

func TestTwoDeviceConcurrentCreationNeverExceedsLimit(t *testing.T) {
	now := time.Date(2027, 1, 1, 2, 3, 4, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Hour)))
	service := AuthorizationService{
		Store:                 store,
		Tokens:                testIssuer(),
		MaxConcurrentSessions: TwoActiveTranslationSessionLimit,
	}

	const attempts = 16
	start := make(chan struct{})
	grants := make(chan TranslationGrant, attempts)
	failures := make(chan error, attempts)
	var wait sync.WaitGroup
	for range attempts {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
			if err != nil {
				failures <- err
				return
			}
			if grant.Token == "" || grant.Session.ID == uuid.Nil {
				failures <- errors.New("creation returned an empty grant")
				return
			}
			grants <- grant
		}()
	}
	close(start)
	wait.Wait()
	close(grants)
	close(failures)
	for err := range failures {
		t.Fatalf("concurrent creation failed: %v", err)
	}

	store.mu.Lock()
	active, replaced := 0, 0
	for _, record := range store.sessions {
		switch {
		case record.state == lifecycleSessionActive && record.session.ExpiresAt.After(now):
			active++
		case record.reason == domain.TerminationReplacedByDevice:
			replaced++
		default:
			t.Fatalf("terminated session without a replacement reason: %+v", record)
		}
	}
	store.mu.Unlock()
	if active != TwoActiveTranslationSessionLimit {
		t.Fatalf("active sessions = %d, want %d", active, TwoActiveTranslationSessionLimit)
	}
	if replaced != attempts-TwoActiveTranslationSessionLimit {
		t.Fatalf("replaced sessions = %d, want %d", replaced, attempts-TwoActiveTranslationSessionLimit)
	}

	for grant := range grants {
		state, err := store.TranslationSessionState(context.Background(), userID, grant.Session.ID, grant.Session.EntitlementID, grant.Session.JTI, now)
		if err != nil {
			t.Fatalf("arbitrated grant lookup: %v", err)
		}
		_, authErr := service.AuthorizeTranslationSession(context.Background(), grant.Token, now)
		if state.Active != (authErr == nil) {
			t.Fatalf("grant authorization disagrees with persisted state: active=%v err=%v", state.Active, authErr)
		}
		if !state.Active && state.TerminationReason != domain.TerminationReplacedByDevice {
			t.Fatalf("terminated grant reason = %q, want replaced_by_device", state.TerminationReason)
		}
	}
}

func TestTwoDeviceCreationRequiresEntitlementAndRejectsDisabledOwner(t *testing.T) {
	now := time.Date(2027, 1, 2, 3, 4, 5, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-domain.TrialDuration)))
	service := AuthorizationService{
		Store:                 store,
		Tokens:                testIssuer(),
		MaxConcurrentSessions: TwoActiveTranslationSessionLimit,
	}
	if _, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now); !errors.Is(err, domain.ErrNoEntitlement) {
		t.Fatalf("expired entitlement creation error = %v", err)
	}

	fresh := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Minute)))
	service.Store = fresh
	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if err != nil {
		t.Fatal(err)
	}
	fresh.mu.Lock()
	fresh.disabled = true
	fresh.mu.Unlock()
	state, err := fresh.TranslationSessionState(context.Background(), userID, grant.Session.ID, grant.Session.EntitlementID, grant.Session.JTI, now.Add(time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if state.Active || state.TerminationReason != domain.TerminationUserDisabled {
		t.Fatalf("disabled owner state = %+v, want inactive user_disabled", state)
	}
	if _, err := service.AuthorizeTranslationSession(context.Background(), grant.Token, now.Add(time.Second)); !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("disabled owner authorization error = %v", err)
	}
	assertEmptyClaims(t, Claims{})
}

func TestTranslationSessionAuthorizationRequiresActivePersistedState(t *testing.T) {
	now := time.Date(2027, 1, 2, 3, 4, 5, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Hour)))
	service := AuthorizationService{Store: store, Tokens: testIssuer(), MaxConcurrentSessions: TwoActiveTranslationSessionLimit}
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
	state, err := store.TranslationSessionState(context.Background(), userID, grant.Session.ID, grant.Session.EntitlementID, grant.Session.JTI, now.Add(2*time.Second))
	if err != nil || state.Active || state.TerminationReason != domain.TerminationExpired {
		t.Fatalf("expired session state = %+v err = %v, want inactive expired", state, err)
	}
	claims, err = service.AuthorizeTranslationSession(context.Background(), grant.Token, now.Add(2*time.Second))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("persisted expiry authorization error = %v", err)
	}
	assertEmptyClaims(t, claims)
}

func TestTwoDeviceEndAndRevokeReleaseSlotAndRecordReason(t *testing.T) {
	now := time.Date(2027, 1, 3, 3, 4, 5, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Hour)))
	service := AuthorizationService{Store: store, Tokens: testIssuer(), MaxConcurrentSessions: TwoActiveTranslationSessionLimit}

	first, err := service.CreateTranslationSession(context.Background(), userID, "install-first", now)
	if err != nil {
		t.Fatal(err)
	}
	second, err := service.CreateTranslationSession(context.Background(), userID, "install-second", now.Add(time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if err := service.EndTranslationSession(context.Background(), userID, first.Session.ID, now.Add(2*time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := service.AuthorizeTranslationSession(context.Background(), first.Token, now.Add(2*time.Second)); !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("ended session authorization error = %v", err)
	}
	state, err := store.TranslationSessionState(context.Background(), userID, first.Session.ID, first.Session.EntitlementID, first.Session.JTI, now.Add(2*time.Second))
	if err != nil || state.Active || state.TerminationReason != domain.TerminationEnded {
		t.Fatalf("ended session state = %+v err = %v, want inactive ended", state, err)
	}

	third, err := service.CreateTranslationSession(context.Background(), userID, "install-third", now.Add(2*time.Second))
	if err != nil {
		t.Fatalf("ended session did not release slot: %v", err)
	}
	if _, err := service.AuthorizeTranslationSession(context.Background(), third.Token, now.Add(2*time.Second)); err != nil {
		t.Fatalf("slot-releasing creation is not usable: %v", err)
	}
	if err := service.RevokeTranslationSession(context.Background(), userID, second.Session.ID, now.Add(3*time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := service.AuthorizeTranslationSession(context.Background(), second.Token, now.Add(3*time.Second)); !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("revoked session authorization error = %v", err)
	}
	state, err = store.TranslationSessionState(context.Background(), userID, second.Session.ID, second.Session.EntitlementID, second.Session.JTI, now.Add(3*time.Second))
	if err != nil || state.Active || state.TerminationReason != domain.TerminationRevoked {
		t.Fatalf("revoked session state = %+v err = %v, want inactive revoked", state, err)
	}
	if _, err := service.CreateTranslationSession(context.Background(), userID, "install-fourth", now.Add(3*time.Second)); err != nil {
		t.Fatalf("revoked session did not release slot: %v", err)
	}
	if err := service.EndTranslationSession(context.Background(), userID, uuid.Nil, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("invalid end error = %v", err)
	}
}

func TestTwoDeviceExpiryReleasesSlot(t *testing.T) {
	now := time.Date(2027, 1, 4, 4, 5, 6, 0, time.UTC)
	userID := uuid.New()
	store := newLifecycleSessionStore(mustTrial(t, userID, now.Add(-time.Hour)))
	service := AuthorizationService{
		Store:                 store,
		Tokens:                testIssuer(),
		TranslationTTL:        2 * time.Second,
		MaxConcurrentSessions: TwoActiveTranslationSessionLimit,
	}

	first, err := service.CreateTranslationSession(context.Background(), userID, "install-first", now)
	if err != nil {
		t.Fatal(err)
	}
	second, err := service.CreateTranslationSession(context.Background(), userID, "install-second", now.Add(time.Second))
	if err != nil {
		t.Fatal(err)
	}
	boundary := first.Session.ExpiresAt
	claims, err := service.AuthorizeTranslationSession(context.Background(), first.Token, boundary)
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("expired session authorization error = %v", err)
	}
	assertEmptyClaims(t, claims)
	third, err := service.CreateTranslationSession(context.Background(), userID, "install-third", boundary)
	if err != nil {
		t.Fatalf("expired session did not release slot: %v", err)
	}
	if third.Session.ID == first.Session.ID || third.Session.ID == second.Session.ID {
		t.Fatal("replacement reused an existing session ID")
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
	service := AuthorizationService{Store: store, Tokens: testIssuer(), MaxConcurrentSessions: TwoActiveTranslationSessionLimit}
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

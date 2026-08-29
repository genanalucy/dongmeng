package auth

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
)

type authorizationStoreStub struct {
	registerUser         domain.User
	registerTrial        domain.Entitlement
	registerErr          error
	registerCalls        []domain.RegisterParams
	entitlement          domain.Entitlement
	entitlementErr       error
	entitlementCalls     []entitlementCall
	createdSessions      []authorizedSessionCall
	createSessionErr     error
	endedSessions        []lifecycleCall
	endSessionErr        error
	revokedSessions      []lifecycleCall
	revokeSessionErr     error
	stackedEntitlement   domain.Entitlement
	stackErr             error
	stackCalls           []entitlementCall
	revokeEntitlementErr error
	revokedEntitlements  []lifecycleCall
	limitedSessions      []limitedSessionCall
	limitedSessionErr    error
}

type entitlementCall struct {
	userID uuid.UUID
	now    time.Time
}

type lifecycleCall struct {
	userID    uuid.UUID
	sessionID uuid.UUID
	now       time.Time
}

type authorizedSessionCall struct {
	session      domain.TranslationSession
	authorizedAt time.Time
}

type limitedSessionCall struct {
	session       domain.TranslationSession
	authorizedAt  time.Time
	maxConcurrent int
}

func (s *authorizationStoreStub) Register(_ context.Context, params domain.RegisterParams) (domain.User, domain.Entitlement, error) {
	s.registerCalls = append(s.registerCalls, params)
	return s.registerUser, s.registerTrial, s.registerErr
}

func (s *authorizationStoreStub) ActiveEntitlement(_ context.Context, userID uuid.UUID, now time.Time) (domain.Entitlement, error) {
	s.entitlementCalls = append(s.entitlementCalls, entitlementCall{userID: userID, now: now})
	return s.entitlement, s.entitlementErr
}

func (s *authorizationStoreStub) CreateAuthorizedTranslationSession(_ context.Context, session domain.TranslationSession, authorizedAt time.Time) error {
	if s.createSessionErr != nil {
		return s.createSessionErr
	}
	s.createdSessions = append(s.createdSessions, authorizedSessionCall{session: session, authorizedAt: authorizedAt})
	return nil
}

func (s *authorizationStoreStub) CreateAuthorizedTranslationSessionWithLimit(_ context.Context, session domain.TranslationSession, authorizedAt time.Time, maxConcurrent int) error {
	s.limitedSessions = append(s.limitedSessions, limitedSessionCall{session: session, authorizedAt: authorizedAt, maxConcurrent: maxConcurrent})
	return s.limitedSessionErr
}

func (s *authorizationStoreStub) StackAnnualEntitlement(_ context.Context, userID uuid.UUID, now time.Time) (domain.Entitlement, error) {
	s.stackCalls = append(s.stackCalls, entitlementCall{userID: userID, now: now})
	return s.stackedEntitlement, s.stackErr
}

func (s *authorizationStoreStub) RevokeEntitlement(_ context.Context, userID, entitlementID uuid.UUID, now time.Time) error {
	s.revokedEntitlements = append(s.revokedEntitlements, lifecycleCall{userID: userID, sessionID: entitlementID, now: now})
	if s.revokeEntitlementErr == nil && s.entitlement.ID == entitlementID && s.entitlement.UserID == userID {
		s.entitlementErr = domain.ErrNoEntitlement
	}
	return s.revokeEntitlementErr
}

func (s *authorizationStoreStub) EndTranslationSession(_ context.Context, userID, sessionID uuid.UUID, now time.Time) error {
	s.endedSessions = append(s.endedSessions, lifecycleCall{userID: userID, sessionID: sessionID, now: now})
	return s.endSessionErr
}

func (s *authorizationStoreStub) RevokeTranslationSession(_ context.Context, userID, sessionID uuid.UUID, now time.Time) error {
	s.revokedSessions = append(s.revokedSessions, lifecycleCall{userID: userID, sessionID: sessionID, now: now})
	return s.revokeSessionErr
}

func TestAuthorizationServiceRegisterPersistsThreeDayTrial(t *testing.T) {
	now := time.Date(2026, 11, 1, 2, 3, 4, 5, time.FixedZone("UTC+8", 8*60*60))
	storedAt := now.UTC().Truncate(time.Microsecond)
	userID := uuid.New()
	trial, err := domain.NewTrialEntitlement(uuid.New(), userID, storedAt)
	if err != nil {
		t.Fatal(err)
	}
	store := &authorizationStoreStub{
		registerUser:  domain.User{ID: userID, Email: "user@example.com", Role: string(domain.RoleUser), CreatedAt: storedAt},
		registerTrial: trial,
	}
	service := AuthorizationService{
		Store: store,
		HashPasswordValue: func(password string) (string, error) {
			if password != "correct horse battery" {
				t.Fatalf("password passed to hasher = %q", password)
			}
			return "encoded-password-hash", nil
		},
	}

	result, err := service.Register(context.Background(), " User@Example.COM ", "correct horse battery", now)
	if err != nil {
		t.Fatal(err)
	}
	if result.User.ID != userID || result.Trial.ID != trial.ID {
		t.Fatalf("unexpected registration result: %+v", result)
	}
	if len(store.registerCalls) != 1 {
		t.Fatalf("register calls = %d", len(store.registerCalls))
	}
	params := store.registerCalls[0]
	if params.Email != "user@example.com" || params.PasswordHash != "encoded-password-hash" || !params.Now.Equal(storedAt) {
		t.Fatalf("unexpected persisted registration: %+v", params)
	}
	if result.Trial.ExpiresAt.Sub(result.Trial.StartsAt) != 72*time.Hour {
		t.Fatalf("trial duration = %s", result.Trial.ExpiresAt.Sub(result.Trial.StartsAt))
	}
}

func TestAuthorizationServiceRegisterAcceptsPostgresMicrosecondTrialTimestamps(t *testing.T) {
	now := time.Date(2026, 11, 1, 2, 3, 4, 123456789, time.UTC)
	storedAt := now.Truncate(time.Microsecond)
	userID := uuid.New()
	trial, err := domain.NewTrialEntitlement(uuid.New(), userID, storedAt)
	if err != nil {
		t.Fatal(err)
	}
	store := &authorizationStoreStub{
		registerUser:  domain.User{ID: userID, Email: "user@example.com", Role: string(domain.RoleUser), CreatedAt: storedAt},
		registerTrial: trial,
	}
	service := AuthorizationService{
		Store:             store,
		HashPasswordValue: func(string) (string, error) { return "encoded-password-hash", nil },
	}

	if _, err := service.Register(context.Background(), "user@example.com", "correct horse battery", now); err != nil {
		t.Fatalf("registration rejected PostgreSQL microsecond timestamp: %v", err)
	}
}

func TestAuthorizationServiceRegisterRejectsInvalidOrUnpersistedTrial(t *testing.T) {
	now := time.Date(2026, 11, 2, 3, 4, 5, 0, time.UTC)
	userID := uuid.New()
	validTrial, err := domain.NewTrialEntitlement(uuid.New(), userID, now)
	if err != nil {
		t.Fatal(err)
	}

	tests := []struct {
		name  string
		user  domain.User
		trial domain.Entitlement
		err   error
	}{
		{name: "store error", err: domain.ErrConflict},
		{name: "wrong email", user: domain.User{ID: userID, Email: "other@example.com", Role: string(domain.RoleUser)}, trial: validTrial},
		{name: "elevated role", user: domain.User{ID: userID, Email: "user@example.com", Role: string(domain.RoleAdmin)}, trial: validTrial},
		{name: "wrong owner", user: domain.User{ID: userID, Email: "user@example.com", Role: string(domain.RoleUser)}, trial: mustTrial(t, uuid.New(), now)},
		{name: "wrong duration", user: domain.User{ID: userID, Email: "user@example.com", Role: string(domain.RoleUser)}, trial: domain.Entitlement{ID: uuid.New(), UserID: userID, Kind: string(domain.EntitlementTrial), StartsAt: now, ExpiresAt: now.Add(48 * time.Hour)}},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			store := &authorizationStoreStub{registerUser: test.user, registerTrial: test.trial, registerErr: test.err}
			service := AuthorizationService{Store: store, HashPasswordValue: func(string) (string, error) { return "hash", nil }}
			result, err := service.Register(context.Background(), "user@example.com", "correct horse battery", now)
			if err == nil {
				t.Fatal("invalid registration result accepted")
			}
			if result != (RegistrationResult{}) {
				t.Fatalf("partial registration result leaked: %+v", result)
			}
		})
	}
}

func TestAuthorizationServiceDoesNotPersistEmptyPasswordHash(t *testing.T) {
	store := &authorizationStoreStub{}
	service := AuthorizationService{Store: store, HashPasswordValue: func(string) (string, error) { return "", nil }}

	result, err := service.Register(context.Background(), "user@example.com", "correct horse battery", time.Now())
	if err == nil || result != (RegistrationResult{}) {
		t.Fatalf("empty password hash accepted: result=%+v err=%v", result, err)
	}
	if len(store.registerCalls) != 0 {
		t.Fatalf("empty password hash reached persistence: %+v", store.registerCalls)
	}
}

func TestAuthorizationServiceActiveEntitlementUsesPersistedSourceOfTruth(t *testing.T) {
	now := time.Date(2026, 11, 3, 4, 5, 6, 0, time.UTC)
	userID := uuid.New()
	entitlement := mustTrial(t, userID, now.Add(-time.Hour))
	store := &authorizationStoreStub{entitlement: entitlement}
	service := AuthorizationService{Store: store}

	actual, err := service.ActiveEntitlement(context.Background(), userID, now)
	if err != nil {
		t.Fatal(err)
	}
	if actual.ID != entitlement.ID || len(store.entitlementCalls) != 1 || store.entitlementCalls[0].userID != userID {
		t.Fatalf("unexpected entitlement lookup: %+v calls=%+v", actual, store.entitlementCalls)
	}

	store.entitlement.UserID = uuid.New()
	if _, err := service.ActiveEntitlement(context.Background(), userID, now); !errors.Is(err, domain.ErrNoEntitlement) {
		t.Fatalf("foreign entitlement error = %v", err)
	}
	store.entitlement = entitlement
	store.entitlement.ExpiresAt = now
	if _, err := service.ActiveEntitlement(context.Background(), userID, now); !errors.Is(err, domain.ErrNoEntitlement) {
		t.Fatalf("expired entitlement error = %v", err)
	}
}

func TestAuthorizationServiceTrialExpiryBoundaryIsHalfOpen(t *testing.T) {
	startsAt := time.Date(2026, 11, 3, 0, 0, 0, 0, time.UTC)
	userID := uuid.New()
	trial := mustTrial(t, userID, startsAt)
	store := &authorizationStoreStub{entitlement: trial}
	service := AuthorizationService{Store: store}

	if _, err := service.ActiveEntitlement(context.Background(), userID, trial.ExpiresAt.Add(-time.Nanosecond)); err != nil {
		t.Fatalf("trial rejected immediately before expiry: %v", err)
	}
	if _, err := service.ActiveEntitlement(context.Background(), userID, trial.ExpiresAt); !errors.Is(err, domain.ErrNoEntitlement) {
		t.Fatalf("trial active at expiry: %v", err)
	}
	if _, err := service.CreateTranslationSession(context.Background(), userID, "test-install", trial.ExpiresAt); !errors.Is(err, domain.ErrNoEntitlement) {
		t.Fatalf("session granted at trial expiry: %v", err)
	}
}

func TestAuthorizationServiceStacksAnnualEntitlementAfterLatestExpiry(t *testing.T) {
	now := time.Date(2026, 11, 3, 4, 5, 6, 0, time.UTC)
	userID := uuid.New()
	latestExpiry := now.Add(48 * time.Hour)
	stacked, err := domain.NewRedemptionEntitlement(uuid.New(), userID, latestExpiry)
	if err != nil {
		t.Fatal(err)
	}
	store := &authorizationStoreStub{stackedEntitlement: stacked}
	service := AuthorizationService{Store: store, EntitlementLifecycle: store}

	actual, err := service.StackAnnualEntitlement(context.Background(), userID, now)
	if err != nil {
		t.Fatal(err)
	}
	if actual.ID != stacked.ID || !actual.StartsAt.Equal(latestExpiry) || actual.ExpiresAt.Sub(actual.StartsAt) != 365*24*time.Hour {
		t.Fatalf("unexpected stacked entitlement: %+v", actual)
	}
	if len(store.stackCalls) != 1 || store.stackCalls[0].userID != userID || !store.stackCalls[0].now.Equal(now) {
		t.Fatalf("stacking did not use persistent store: %+v", store.stackCalls)
	}
}

func TestAuthorizationServiceRejectsInvalidStackedEntitlement(t *testing.T) {
	now := time.Date(2026, 11, 3, 4, 5, 6, 0, time.UTC)
	userID := uuid.New()
	store := &authorizationStoreStub{stackedEntitlement: mustTrial(t, userID, now)}
	service := AuthorizationService{Store: store, EntitlementLifecycle: store}

	if _, err := service.StackAnnualEntitlement(context.Background(), userID, now); err == nil {
		t.Fatal("trial accepted as annual stacked entitlement")
	}
	if _, err := (AuthorizationService{}).StackAnnualEntitlement(context.Background(), userID, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("missing lifecycle store error = %v", err)
	}
}

func TestAuthorizationServicePersistsEntitlementRevocation(t *testing.T) {
	now := time.Date(2026, 11, 3, 4, 5, 6, 0, time.UTC)
	userID := uuid.New()
	entitlement := mustTrial(t, userID, now.Add(-time.Hour))
	store := &authorizationStoreStub{entitlement: entitlement}
	service := AuthorizationService{Store: store, EntitlementLifecycle: store}

	if err := service.RevokeEntitlement(context.Background(), userID, entitlement.ID, now); err != nil {
		t.Fatal(err)
	}
	if len(store.revokedEntitlements) != 1 || store.revokedEntitlements[0].userID != userID || store.revokedEntitlements[0].sessionID != entitlement.ID {
		t.Fatalf("entitlement revocation was not persisted: %+v", store.revokedEntitlements)
	}
	if _, err := service.ActiveEntitlement(context.Background(), userID, now); !errors.Is(err, domain.ErrNoEntitlement) {
		t.Fatalf("revoked entitlement remained active: %v", err)
	}
}

func TestAuthorizationServiceCreatesPersistedTranslationGrant(t *testing.T) {
	now := time.Date(2026, 11, 4, 5, 6, 7, 0, time.UTC)
	userID := uuid.New()
	entitlement := mustTrial(t, userID, now.Add(-time.Hour))
	store := &authorizationStoreStub{entitlement: entitlement}
	ids := []uuid.UUID{uuid.New(), uuid.New()}
	service := AuthorizationService{Store: store, Tokens: testIssuer(), NewID: idSequence(ids...)}

	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if err != nil {
		t.Fatal(err)
	}
	if grant.Session.ID != ids[0] || grant.Session.JTI != ids[1] || grant.Session.EntitlementID != entitlement.ID || grant.Token == "" {
		t.Fatalf("unexpected grant: %+v", grant)
	}
	if !grant.Session.ExpiresAt.Equal(now.Add(DefaultTranslationSessionTTL)) {
		t.Fatalf("session expiry = %s", grant.Session.ExpiresAt)
	}
	if len(store.createdSessions) != 1 || store.createdSessions[0].session != grant.Session || !store.createdSessions[0].authorizedAt.Equal(now) {
		t.Fatalf("session was not atomically authorized and persisted: %+v", store.createdSessions)
	}
	claims, err := service.Tokens.ParseTranslationAt(grant.Token, now.Add(time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if claims.SessionID != grant.Session.ID.String() || claims.EntitlementID != entitlement.ID.String() || claims.Subject != userID.String() || claims.ID != grant.Session.JTI.String() {
		t.Fatalf("unexpected grant claims: %+v", claims)
	}
}

func TestAuthorizationServiceEnforcesConcurrentSessionLimitAtomically(t *testing.T) {
	now := time.Date(2026, 11, 4, 5, 6, 7, 0, time.UTC)
	userID := uuid.New()
	store := &authorizationStoreStub{entitlement: mustTrial(t, userID, now)}
	service := AuthorizationService{Store: store, Tokens: testIssuer(), MaxConcurrentSessions: 2}

	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if err != nil {
		t.Fatal(err)
	}
	if grant.Token == "" || len(store.limitedSessions) != 1 || store.limitedSessions[0].maxConcurrent != 2 || store.limitedSessions[0].session != grant.Session {
		t.Fatalf("concurrent limit contract not used: grant=%+v calls=%+v", grant, store.limitedSessions)
	}
	if len(store.createdSessions) != 0 {
		t.Fatalf("unlimited creation path used: %+v", store.createdSessions)
	}
}

func TestAuthorizationServiceRejectsConcurrentSessionLimitExhaustion(t *testing.T) {
	now := time.Date(2026, 11, 4, 5, 6, 7, 0, time.UTC)
	userID := uuid.New()
	store := &authorizationStoreStub{entitlement: mustTrial(t, userID, now), limitedSessionErr: domain.ErrConflict}
	service := AuthorizationService{Store: store, Tokens: testIssuer(), MaxConcurrentSessions: 1}

	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if !errors.Is(err, domain.ErrConflict) || grant != (TranslationGrant{}) {
		t.Fatalf("limit exhaustion result: grant=%+v err=%v", grant, err)
	}
}

func TestAuthorizationServiceRejectsLimitWithoutAtomicStoreSupport(t *testing.T) {
	now := time.Date(2026, 11, 4, 5, 6, 7, 0, time.UTC)
	userID := uuid.New()
	base := &authorizationStoreWithoutLimit{store: &authorizationStoreStub{entitlement: mustTrial(t, userID, now)}}
	service := AuthorizationService{Store: base, Tokens: testIssuer(), MaxConcurrentSessions: 1}

	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if !errors.Is(err, domain.ErrInvalid) || grant != (TranslationGrant{}) {
		t.Fatalf("unsupported limit result: grant=%+v err=%v", grant, err)
	}
	if len(base.store.entitlementCalls) != 0 || len(base.store.createdSessions) != 0 {
		t.Fatalf("invalid limit configuration reached persistence: entitlement=%+v sessions=%+v", base.store.entitlementCalls, base.store.createdSessions)
	}
}

func TestAuthorizationServiceClipsGrantToEntitlementExpiry(t *testing.T) {
	now := time.Date(2026, 11, 5, 6, 7, 8, 0, time.UTC)
	userID := uuid.New()
	entitlement := domain.Entitlement{
		ID:        uuid.New(),
		UserID:    userID,
		Kind:      string(domain.EntitlementTrial),
		StartsAt:  now.Add(-domain.TrialDuration + 2*time.Minute),
		ExpiresAt: now.Add(2 * time.Minute),
	}
	store := &authorizationStoreStub{entitlement: entitlement}
	service := AuthorizationService{Store: store, Tokens: testIssuer()}

	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if err != nil {
		t.Fatal(err)
	}
	if !grant.Session.ExpiresAt.Equal(entitlement.ExpiresAt) {
		t.Fatalf("grant outlived entitlement: session=%s entitlement=%s", grant.Session.ExpiresAt, entitlement.ExpiresAt)
	}
}

func TestAuthorizationServiceRejectsTooShortGrant(t *testing.T) {
	now := time.Date(2026, 11, 5, 6, 7, 8, 0, time.UTC)
	userID := uuid.New()
	entitlement := domain.Entitlement{
		ID:        uuid.New(),
		UserID:    userID,
		Kind:      string(domain.EntitlementTrial),
		StartsAt:  now.Add(-domain.TrialDuration + 500*time.Millisecond),
		ExpiresAt: now.Add(500 * time.Millisecond),
	}
	store := &authorizationStoreStub{entitlement: entitlement}
	service := AuthorizationService{Store: store, Tokens: testIssuer()}

	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if !errors.Is(err, domain.ErrNoEntitlement) || grant != (TranslationGrant{}) || len(store.createdSessions) != 0 {
		t.Fatalf("sub-second grant accepted: grant=%+v err=%v sessions=%+v", grant, err, store.createdSessions)
	}
}

func TestAuthorizationServiceDoesNotPersistUnsignedGrant(t *testing.T) {
	now := time.Date(2026, 11, 6, 7, 8, 9, 0, time.UTC)
	userID := uuid.New()
	store := &authorizationStoreStub{entitlement: mustTrial(t, userID, now)}
	issuer := testIssuer()
	issuer.SessionAudience = ""
	service := AuthorizationService{Store: store, Tokens: issuer}

	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if err == nil || grant != (TranslationGrant{}) || len(store.createdSessions) != 0 {
		t.Fatalf("unsigned grant persisted: grant=%+v err=%v sessions=%+v", grant, err, store.createdSessions)
	}
}

func TestAuthorizationServiceDoesNotReturnUnpersistedGrant(t *testing.T) {
	now := time.Date(2026, 11, 6, 7, 8, 9, 0, time.UTC)
	userID := uuid.New()
	persistErr := errors.New("database unavailable")
	store := &authorizationStoreStub{entitlement: mustTrial(t, userID, now), createSessionErr: persistErr}
	service := AuthorizationService{Store: store, Tokens: testIssuer()}

	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if !errors.Is(err, persistErr) {
		t.Fatalf("persistence failure error = %v", err)
	}
	if grant != (TranslationGrant{}) {
		t.Fatalf("unpersisted grant leaked: %+v", grant)
	}
	if len(store.createdSessions) != 0 {
		t.Fatalf("failed session recorded as persisted: %+v", store.createdSessions)
	}
}

func TestAuthorizationServiceDelegatesSessionTerminalStates(t *testing.T) {
	now := time.Date(2026, 11, 7, 8, 9, 10, 0, time.FixedZone("UTC-5", -5*60*60))
	userID, sessionID := uuid.New(), uuid.New()
	store := &authorizationStoreStub{}
	service := AuthorizationService{Store: store}

	if err := service.EndTranslationSession(context.Background(), userID, sessionID, now); err != nil {
		t.Fatal(err)
	}
	if err := service.RevokeTranslationSession(context.Background(), userID, sessionID, now); err != nil {
		t.Fatal(err)
	}
	if len(store.endedSessions) != 1 || len(store.revokedSessions) != 1 || !store.endedSessions[0].now.Equal(now.UTC()) || !store.revokedSessions[0].now.Equal(now.UTC()) {
		t.Fatalf("terminal state calls not persisted: ended=%+v revoked=%+v", store.endedSessions, store.revokedSessions)
	}

	store.endSessionErr = domain.ErrNotFound
	if err := service.EndTranslationSession(context.Background(), userID, sessionID, now); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("end error = %v", err)
	}
	store.revokeSessionErr = domain.ErrForbidden
	if err := service.RevokeTranslationSession(context.Background(), userID, sessionID, now); !errors.Is(err, domain.ErrForbidden) {
		t.Fatalf("revoke error = %v", err)
	}
}

func TestAuthorizationServiceRejectsInvalidConfigurationAndIDs(t *testing.T) {
	now := time.Now()
	service := AuthorizationService{}
	if _, err := service.Register(context.Background(), "user@example.com", "correct horse battery", now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("nil registration store error = %v", err)
	}
	if _, err := service.ActiveEntitlement(context.Background(), uuid.Nil, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("nil entitlement user error = %v", err)
	}
	if _, err := service.CreateTranslationSession(context.Background(), uuid.Nil, "test-install", now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("nil session user error = %v", err)
	}
	if err := service.EndTranslationSession(context.Background(), uuid.Nil, uuid.New(), now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("nil end user error = %v", err)
	}
	if err := service.RevokeTranslationSession(context.Background(), uuid.New(), uuid.Nil, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("nil revoke session error = %v", err)
	}
}

func TestAuthorizationServiceRejectsInvalidTranslationConfiguration(t *testing.T) {
	now := time.Date(2026, 11, 8, 9, 10, 11, 0, time.UTC)
	userID := uuid.New()

	for _, test := range []struct {
		name    string
		service AuthorizationService
	}{
		{
			name:    "sub-second TTL",
			service: AuthorizationService{Store: &authorizationStoreStub{entitlement: mustTrial(t, userID, now)}, Tokens: testIssuer(), TranslationTTL: time.Millisecond},
		},
		{
			name:    "negative concurrent limit",
			service: AuthorizationService{Store: &authorizationStoreStub{entitlement: mustTrial(t, userID, now)}, Tokens: testIssuer(), MaxConcurrentSessions: -1},
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			grant, err := test.service.CreateTranslationSession(context.Background(), userID, "test-install", now)
			if !errors.Is(err, domain.ErrInvalid) || grant != (TranslationGrant{}) {
				t.Fatalf("invalid configuration accepted: grant=%+v err=%v", grant, err)
			}
		})
	}
}

func TestAuthorizationServiceRejectsInvalidGeneratedSessionIDs(t *testing.T) {
	now := time.Date(2026, 11, 8, 9, 10, 11, 0, time.UTC)
	userID := uuid.New()
	store := &authorizationStoreStub{entitlement: mustTrial(t, userID, now)}
	service := AuthorizationService{Store: store, Tokens: testIssuer(), NewID: func() uuid.UUID { return uuid.Nil }}

	grant, err := service.CreateTranslationSession(context.Background(), userID, "test-install", now)
	if !errors.Is(err, domain.ErrInvalid) || grant != (TranslationGrant{}) || len(store.createdSessions) != 0 {
		t.Fatalf("invalid generated IDs accepted: grant=%+v err=%v sessions=%+v", grant, err, store.createdSessions)
	}
}

func mustTrial(t *testing.T, userID uuid.UUID, startsAt time.Time) domain.Entitlement {
	t.Helper()
	entitlement, err := domain.NewTrialEntitlement(uuid.New(), userID, startsAt)
	if err != nil {
		t.Fatal(err)
	}
	return entitlement
}

func idSequence(ids ...uuid.UUID) func() uuid.UUID {
	index := 0
	return func() uuid.UUID {
		if index >= len(ids) {
			return uuid.Nil
		}
		id := ids[index]
		index++
		return id
	}
}

type authorizationStoreWithoutLimit struct {
	store *authorizationStoreStub
}

func (s *authorizationStoreWithoutLimit) Register(ctx context.Context, params domain.RegisterParams) (domain.User, domain.Entitlement, error) {
	return s.store.Register(ctx, params)
}

func (s *authorizationStoreWithoutLimit) ActiveEntitlement(ctx context.Context, userID uuid.UUID, now time.Time) (domain.Entitlement, error) {
	return s.store.ActiveEntitlement(ctx, userID, now)
}

func (s *authorizationStoreWithoutLimit) CreateAuthorizedTranslationSession(ctx context.Context, session domain.TranslationSession, authorizedAt time.Time) error {
	return s.store.CreateAuthorizedTranslationSession(ctx, session, authorizedAt)
}

func (s *authorizationStoreWithoutLimit) EndTranslationSession(ctx context.Context, userID, sessionID uuid.UUID, now time.Time) error {
	return s.store.EndTranslationSession(ctx, userID, sessionID, now)
}

func (s *authorizationStoreWithoutLimit) RevokeTranslationSession(ctx context.Context, userID, sessionID uuid.UUID, now time.Time) error {
	return s.store.RevokeTranslationSession(ctx, userID, sessionID, now)
}

var _ AuthorizationFacade = AuthorizationService{}
var _ EntitlementLifecycleFacade = AuthorizationService{}
var _ AuthorizationStore = (*authorizationStoreStub)(nil)
var _ EntitlementLifecycleStore = (*authorizationStoreStub)(nil)
var _ ConcurrentTranslationSessionStore = (*authorizationStoreStub)(nil)
var _ AuthorizationStore = (*authorizationStoreWithoutLimit)(nil)

package auth

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
)

const (
	DefaultTranslationSessionTTL     = 5 * time.Minute
	TwoActiveTranslationSessionLimit = 2
)

// RegistrationStore must atomically persist the user and exactly one trial
// entitlement described by RegisterParams. An error must leave neither record.
type RegistrationStore interface {
	Register(context.Context, domain.RegisterParams) (domain.User, domain.Entitlement, error)
}

// EntitlementStore is the persisted source of truth for access decisions.
// ActiveEntitlement must report a user without an active entitlement as
// domain.ErrNoEntitlement rather than a not-found entity error, so
// authorization outcomes stay identical across store implementations.
type EntitlementStore interface {
	ActiveEntitlement(context.Context, uuid.UUID, time.Time) (domain.Entitlement, error)
}

// EntitlementLifecycleStore owns paid-entitlement mutations. Stacking must lock
// the user entitlement timeline and atomically create a fixed 365-day package
// starting at max(now, latest non-revoked expiry). Revocation must persist an
// ownership-checked terminal state, and ActiveEntitlement must exclude revoked
// records. Revocation must serialize with concurrent translation-session
// creation for the same user (a shared per-user arbitration lock acquired
// before any row locks), so a creation can never return a grant whose
// entitlement revocation already committed.
type EntitlementLifecycleStore interface {
	StackAnnualEntitlement(context.Context, uuid.UUID, time.Time) (domain.Entitlement, error)
	RevokeEntitlement(context.Context, uuid.UUID, uuid.UUID, time.Time) error
}

// TranslationSessionStore persists the complete authorization lifecycle.
// CreateAuthorizedTranslationSession must atomically re-check that the named
// entitlement belongs to the user and is active at authorizedAt before storing
// the session. EndTranslationSession records a normal completion;
// RevokeTranslationSession invalidates a session before expiry; both must
// persist their termination reason. Implementations must reject ownership
// mismatches and make both terminal transitions idempotent. Token consumers
// must consult this persisted lifecycle; JWT validity alone does not make an
// ended or revoked session usable.
type TranslationSessionStore interface {
	CreateAuthorizedTranslationSession(context.Context, domain.TranslationSession, time.Time) error
	EndTranslationSession(context.Context, uuid.UUID, uuid.UUID, time.Time) error
	RevokeTranslationSession(context.Context, uuid.UUID, uuid.UUID, time.Time) error
}

// ConcurrentTranslationSessionStore extends session creation with atomic
// per-user governance. Implementations must, in one transaction, ignore
// sessions whose expiry is <= authorizedAt, count only non-ended/non-revoked
// sessions, re-check entitlement ownership and activity together with the
// owner still being enabled at insert time, end the oldest active sessions in
// the stable (created_at, id) order with the replaced_by_device termination
// reason whenever maxConcurrent would be exceeded, and create the new session.
// Creation therefore succeeds within the limit instead of failing with
// domain.ErrConflict, and must serialize with user disablement and entitlement
// revocation for the same user (a per-user arbitration lock acquired before
// any row locks) so it never returns a grant that a committed disablement or
// revocation has already doomed.
type ConcurrentTranslationSessionStore interface {
	CreateAuthorizedTranslationSessionWithLimit(context.Context, domain.TranslationSession, time.Time, int) error
}

// TranslationSessionAuthorizationStore is the persisted source of truth for a
// presented translation token. TranslationSessionState must return
// domain.ErrUnauthorized when the identifiers match no session, and otherwise
// expose the persisted owner/session/JTI/install data together with Active and
// the resolved TerminationReason. Token consumers must consult this persisted
// lifecycle; JWT validity alone does not make an ended, revoked, expired, or
// otherwise terminated session usable.
type TranslationSessionAuthorizationStore interface {
	TranslationSessionState(context.Context, uuid.UUID, uuid.UUID, uuid.UUID, uuid.UUID, time.Time) (domain.TranslationSessionAuthorization, error)
}

type AuthorizationStore interface {
	RegistrationStore
	EntitlementStore
	TranslationSessionStore
}

type AuthorizationFacade interface {
	Register(context.Context, string, string, string, string, time.Time) (RegistrationResult, error)
	ActiveEntitlement(context.Context, uuid.UUID, time.Time) (domain.Entitlement, error)
	CreateTranslationSession(context.Context, uuid.UUID, string, time.Time) (TranslationGrant, error)
	EndTranslationSession(context.Context, uuid.UUID, uuid.UUID, time.Time) error
	RevokeTranslationSession(context.Context, uuid.UUID, uuid.UUID, time.Time) error
}

type EntitlementLifecycleFacade interface {
	StackAnnualEntitlement(context.Context, uuid.UUID, time.Time) (domain.Entitlement, error)
	RevokeEntitlement(context.Context, uuid.UUID, uuid.UUID, time.Time) error
}

// TranslationSessionAuthorizationFacade verifies a presented translation token
// against the persisted session lifecycle. Authorization failures satisfy
// errors.Is(err, domain.ErrUnauthorized); when the token identity matches a
// persisted session exactly and that session is no longer usable, the error
// is a domain.TerminatedTranslationSessionError carrying one of the
// allowlisted safe terminal reasons. Unknown, mismatched, or unparseable
// tokens receive only the bare domain.ErrUnauthorized sentinel, so lifecycle
// detail never leaks to unauthenticated probing.
type TranslationSessionAuthorizationFacade interface {
	AuthorizeTranslationSession(context.Context, string, time.Time) (Claims, error)
}

type AuthorizationService struct {
	Store                 AuthorizationStore
	EntitlementLifecycle  EntitlementLifecycleStore
	Tokens                TokenIssuer
	TranslationTTL        time.Duration
	MaxConcurrentSessions int
	SessionAuthorization  TranslationSessionAuthorizationStore
	NewID                 func() uuid.UUID
	HashPasswordValue     func(string) (string, error)
}

type RegistrationResult struct {
	User  domain.User
	Trial domain.Entitlement
}

type TranslationGrant struct {
	Session domain.TranslationSession
	Token   string
}

func (s AuthorizationService) Register(ctx context.Context, username, email, phone, password string, now time.Time) (RegistrationResult, error) {
	if s.Store == nil || now.IsZero() {
		return RegistrationResult{}, fmt.Errorf("%w: invalid registration service arguments", domain.ErrInvalid)
	}
	parsedUsername, err := domain.ParseUsername(username)
	if err != nil {
		return RegistrationResult{}, err
	}
	parsedEmail, err := domain.ParseEmail(email)
	if err != nil {
		return RegistrationResult{}, err
	}
	credentials, err := domain.ParsePhoneCredentials(phone, password)
	if err != nil {
		return RegistrationResult{}, err
	}
	hash, err := s.hashPassword(credentials.Password.String())
	if err != nil {
		return RegistrationResult{}, fmt.Errorf("hash registration password: %w", err)
	}
	if hash == "" {
		return RegistrationResult{}, errors.New("password hasher returned an empty value")
	}
	now = postgresTimestamp(now)
	user, trial, err := s.Store.Register(ctx, domain.RegisterParams{
		Username:     parsedUsername.String(),
		Email:        parsedEmail.String(),
		Phone:        credentials.Phone.String(),
		PasswordHash: hash,
		Now:          now,
	})
	if err != nil {
		return RegistrationResult{}, fmt.Errorf("register user and trial: %w", err)
	}
	if err := validateRegistrationResult(user, trial, parsedUsername.String(), parsedEmail.String(), credentials.Phone.String(), now); err != nil {
		return RegistrationResult{}, err
	}
	return RegistrationResult{User: user, Trial: trial}, nil
}

func (s AuthorizationService) ActiveEntitlement(ctx context.Context, userID uuid.UUID, now time.Time) (domain.Entitlement, error) {
	if s.Store == nil || userID == uuid.Nil || now.IsZero() {
		return domain.Entitlement{}, fmt.Errorf("%w: invalid entitlement lookup arguments", domain.ErrInvalid)
	}
	now = now.UTC()
	entitlement, err := s.Store.ActiveEntitlement(ctx, userID, now)
	if err != nil {
		return domain.Entitlement{}, fmt.Errorf("lookup active entitlement: %w", err)
	}
	if !entitlement.Valid() || entitlement.UserID != userID || !entitlement.ActiveAt(now) {
		return domain.Entitlement{}, domain.ErrNoEntitlement
	}
	return entitlement, nil
}

func (s AuthorizationService) StackAnnualEntitlement(ctx context.Context, userID uuid.UUID, now time.Time) (domain.Entitlement, error) {
	store := s.entitlementLifecycleStore()
	if store == nil || userID == uuid.Nil || now.IsZero() {
		return domain.Entitlement{}, fmt.Errorf("%w: invalid entitlement stacking arguments", domain.ErrInvalid)
	}
	now = now.UTC()
	entitlement, err := store.StackAnnualEntitlement(ctx, userID, now)
	if err != nil {
		return domain.Entitlement{}, fmt.Errorf("stack annual entitlement: %w", err)
	}
	if !entitlement.Valid() || entitlement.UserID != userID || domain.EntitlementKind(entitlement.Kind) != domain.EntitlementPackage || entitlement.StartsAt.Before(now) || entitlement.ExpiresAt.Sub(entitlement.StartsAt) != domain.RedemptionDuration {
		return domain.Entitlement{}, errors.New("entitlement store returned an invalid annual entitlement")
	}
	return entitlement, nil
}

func (s AuthorizationService) RevokeEntitlement(ctx context.Context, userID, entitlementID uuid.UUID, now time.Time) error {
	store := s.entitlementLifecycleStore()
	if store == nil || userID == uuid.Nil || entitlementID == uuid.Nil || now.IsZero() {
		return fmt.Errorf("%w: invalid entitlement revocation arguments", domain.ErrInvalid)
	}
	if err := store.RevokeEntitlement(ctx, userID, entitlementID, now.UTC()); err != nil {
		return fmt.Errorf("revoke entitlement: %w", err)
	}
	return nil
}

func (s AuthorizationService) CreateTranslationSession(ctx context.Context, userID uuid.UUID, installID string, now time.Time) (TranslationGrant, error) {
	if s.Store == nil || userID == uuid.Nil || !validInstallID(installID) || now.IsZero() {
		return TranslationGrant{}, fmt.Errorf("%w: invalid translation session arguments", domain.ErrInvalid)
	}
	ttl, err := s.validateTranslationConfiguration()
	if err != nil {
		return TranslationGrant{}, err
	}
	now = now.UTC()
	entitlement, err := s.ActiveEntitlement(ctx, userID, now)
	if err != nil {
		return TranslationGrant{}, err
	}
	expiresAt := now.Add(ttl)
	if entitlement.ExpiresAt.Before(expiresAt) {
		expiresAt = entitlement.ExpiresAt
	}
	if expiresAt.Sub(now) < time.Second {
		return TranslationGrant{}, domain.ErrNoEntitlement
	}
	sessionID, jti := s.newID(), s.newID()
	if sessionID == uuid.Nil || jti == uuid.Nil || sessionID == jti {
		return TranslationGrant{}, fmt.Errorf("%w: invalid generated session identifiers", domain.ErrInvalid)
	}
	session := domain.TranslationSession{
		ID:            sessionID,
		UserID:        userID,
		EntitlementID: entitlement.ID,
		InstallID:     installID,
		JTI:           jti,
		ExpiresAt:     expiresAt,
	}
	token, err := s.Tokens.TranslationTokenForInstall(session.ID, session.EntitlementID, session.UserID, session.JTI, installID, expiresAt.Sub(now), now)
	if err != nil {
		return TranslationGrant{}, fmt.Errorf("issue translation token: %w", err)
	}
	if err := s.persistTranslationSession(ctx, session, now); err != nil {
		return TranslationGrant{}, fmt.Errorf("persist authorized translation session: %w", err)
	}
	return TranslationGrant{Session: session, Token: token}, nil
}

func (s AuthorizationService) AuthorizeTranslationSession(ctx context.Context, token string, now time.Time) (Claims, error) {
	store := s.translationSessionAuthorizationStore()
	if store == nil || now.IsZero() {
		return Claims{}, fmt.Errorf("%w: translation session authorization is not configured", domain.ErrUnauthorized)
	}
	claims, err := s.Tokens.ParseTranslationAt(token, now.UTC())
	if err != nil {
		return Claims{}, domain.ErrUnauthorized
	}
	userID, userErr := uuid.Parse(claims.Subject)
	sessionID, sessionErr := uuid.Parse(claims.SessionID)
	entitlementID, entitlementErr := uuid.Parse(claims.EntitlementID)
	jti, jtiErr := uuid.Parse(claims.ID)
	if userErr != nil || sessionErr != nil || entitlementErr != nil || jtiErr != nil {
		return Claims{}, domain.ErrUnauthorized
	}
	state, err := store.TranslationSessionState(ctx, userID, sessionID, entitlementID, jti, now.UTC())
	if err != nil {
		// Store failures stay fail-closed and generic: they carry no identity
		// match, so no terminal reason may be attached.
		return Claims{}, domain.ErrUnauthorized
	}
	if state.Active {
		return claims, nil
	}
	// Only a full identity match reaches this point, so the store-resolved
	// terminal reason is safe to surface. The allowlist keeps a buggy or
	// hostile store from smuggling arbitrary detail through the boundary.
	if domain.SafeTranslationTerminationReason(state.TerminationReason) {
		return Claims{}, domain.TerminatedTranslationSessionError{Reason: state.TerminationReason}
	}
	return Claims{}, domain.ErrUnauthorized
}

func (s AuthorizationService) EndTranslationSession(ctx context.Context, userID, sessionID uuid.UUID, now time.Time) error {
	if s.Store == nil || userID == uuid.Nil || sessionID == uuid.Nil || now.IsZero() {
		return fmt.Errorf("%w: invalid translation session completion arguments", domain.ErrInvalid)
	}
	if err := s.Store.EndTranslationSession(ctx, userID, sessionID, now.UTC()); err != nil {
		return fmt.Errorf("end translation session: %w", err)
	}
	return nil
}

func (s AuthorizationService) RevokeTranslationSession(ctx context.Context, userID, sessionID uuid.UUID, now time.Time) error {
	if s.Store == nil || userID == uuid.Nil || sessionID == uuid.Nil || now.IsZero() {
		return fmt.Errorf("%w: invalid translation session revocation arguments", domain.ErrInvalid)
	}
	if err := s.Store.RevokeTranslationSession(ctx, userID, sessionID, now.UTC()); err != nil {
		return fmt.Errorf("revoke translation session: %w", err)
	}
	return nil
}

func (s AuthorizationService) persistTranslationSession(ctx context.Context, session domain.TranslationSession, now time.Time) error {
	if s.MaxConcurrentSessions == 0 {
		return s.Store.CreateAuthorizedTranslationSession(ctx, session, now)
	}
	store, ok := s.Store.(ConcurrentTranslationSessionStore)
	if !ok {
		return fmt.Errorf("%w: store does not support concurrent session limits", domain.ErrInvalid)
	}
	return store.CreateAuthorizedTranslationSessionWithLimit(ctx, session, now, s.MaxConcurrentSessions)
}

func (s AuthorizationService) validateTranslationConfiguration() (time.Duration, error) {
	ttl, err := s.translationTTL()
	if err != nil {
		return 0, err
	}
	if s.MaxConcurrentSessions < 0 {
		return 0, fmt.Errorf("%w: concurrent session limit must not be negative", domain.ErrInvalid)
	}
	if s.MaxConcurrentSessions > 0 {
		if _, ok := s.Store.(ConcurrentTranslationSessionStore); !ok {
			return 0, fmt.Errorf("%w: store does not support concurrent session limits", domain.ErrInvalid)
		}
	}
	return ttl, nil
}

func (s AuthorizationService) translationSessionAuthorizationStore() TranslationSessionAuthorizationStore {
	if s.SessionAuthorization != nil {
		return s.SessionAuthorization
	}
	store, _ := s.Store.(TranslationSessionAuthorizationStore)
	return store
}

func (s AuthorizationService) entitlementLifecycleStore() EntitlementLifecycleStore {
	if s.EntitlementLifecycle != nil {
		return s.EntitlementLifecycle
	}
	store, _ := s.Store.(EntitlementLifecycleStore)
	return store
}

func validateRegistrationResult(user domain.User, trial domain.Entitlement, expectedUsername, expectedEmail, expectedPhone string, now time.Time) error {
	if user.ID == uuid.Nil || user.Username != expectedUsername || user.Email != expectedEmail || user.Phone != expectedPhone || domain.Role(user.Role) != domain.RoleUser {
		return errors.New("registration store returned an invalid user")
	}
	if !trial.Valid() || trial.UserID != user.ID || domain.EntitlementKind(trial.Kind) != domain.EntitlementTrial || !trial.StartsAt.Equal(now) || !trial.ExpiresAt.Equal(now.Add(domain.TrialDuration)) {
		return errors.New("registration store did not return the required three-day trial")
	}
	return nil
}

// PostgreSQL timestamptz preserves microseconds, so business timestamps written
// to and read from the store must use that same precision for strict comparisons.
func postgresTimestamp(value time.Time) time.Time {
	return value.UTC().Truncate(time.Microsecond)
}

func (s AuthorizationService) translationTTL() (time.Duration, error) {
	if s.TranslationTTL < 0 || (s.TranslationTTL > 0 && s.TranslationTTL < time.Second) {
		return 0, fmt.Errorf("%w: translation session TTL must be zero or at least one second", domain.ErrInvalid)
	}
	if s.TranslationTTL > 0 {
		return s.TranslationTTL, nil
	}
	return DefaultTranslationSessionTTL, nil
}

func (s AuthorizationService) newID() uuid.UUID {
	if s.NewID != nil {
		return s.NewID()
	}
	return uuid.New()
}

func (s AuthorizationService) hashPassword(password string) (string, error) {
	if s.HashPasswordValue != nil {
		return s.HashPasswordValue(password)
	}
	return HashPassword(password)
}

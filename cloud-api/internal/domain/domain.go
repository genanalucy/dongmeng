package domain

import (
	"context"
	"errors"
	"fmt"
	"net/mail"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
)

const (
	TrialDays          = 3
	RedemptionDays     = 365
	TrialDuration      = TrialDays * 24 * time.Hour
	RedemptionDuration = RedemptionDays * 24 * time.Hour
	MinPasswordBytes   = 8
	MaxPasswordBytes   = 256
)

var (
	ErrNotFound                       = errors.New("not found")
	ErrConflict                       = errors.New("conflict")
	ErrUnauthorized                   = errors.New("unauthorized")
	ErrForbidden                      = errors.New("forbidden")
	ErrInvalid                        = errors.New("invalid input")
	ErrNoEntitlement                  = errors.New("no active entitlement")
	ErrRegistrationVerificationFailed = errors.New("registration verification failed")
	ErrCaptchaFailed                  = errors.New("captcha failed")
	ErrRateLimited                    = errors.New("rate limited")
)

// RateLimitedError reports a rejected fixed-window rate limit together with
// the whole seconds callers should wait before the current window can admit
// another request. It satisfies errors.Is(err, ErrRateLimited) so existing
// generic mappings keep working while HTTP layers can emit an accurate
// Retry-After instead of a fixed guess.
type RateLimitedError struct {
	RetryAfterSeconds int
}

func (e RateLimitedError) Error() string { return ErrRateLimited.Error() }
func (e RateLimitedError) Unwrap() error { return ErrRateLimited }

type Role string

const (
	RoleUser  Role = "user"
	RoleAdmin Role = "admin"
)

func ParseRole(value string) (Role, error) {
	role := Role(value)
	if !role.Valid() {
		return "", fmt.Errorf("%w: invalid role", ErrInvalid)
	}
	return role, nil
}

func (r Role) Valid() bool {
	return r == RoleUser || r == RoleAdmin
}

func (r Role) IsAdmin() bool { return r == RoleAdmin }

func RequireAdmin(role Role) error {
	if !role.IsAdmin() {
		return ErrForbidden
	}
	return nil
}

func UserIsAdmin(user User) bool { return Role(user.Role).IsAdmin() }

func RequireAdminUser(user User) error { return RequireAdmin(Role(user.Role)) }

type Email string

func ParseEmail(value string) (Email, error) {
	value = strings.ToLower(strings.TrimSpace(value))
	if value == "" || len(value) > 254 || !utf8.ValidString(value) {
		return "", fmt.Errorf("%w: invalid email", ErrInvalid)
	}
	address, err := mail.ParseAddress(value)
	if err != nil || address.Address != value || strings.Count(value, "@") != 1 {
		return "", fmt.Errorf("%w: invalid email", ErrInvalid)
	}
	return Email(value), nil
}

func (e Email) String() string { return string(e) }

type Password string

func ParsePassword(value string) (Password, error) {
	if !utf8.ValidString(value) || len(value) < MinPasswordBytes || len(value) > MaxPasswordBytes {
		return "", fmt.Errorf("%w: password length must be %d-%d bytes", ErrInvalid, MinPasswordBytes, MaxPasswordBytes)
	}
	return Password(value), nil
}

func (p Password) String() string { return string(p) }

type Username string

func ParseUsername(value string) (Username, error) {
	value = strings.ToLower(strings.TrimSpace(value))
	if len(value) < 3 || len(value) > 32 {
		return "", fmt.Errorf("%w: invalid username", ErrInvalid)
	}
	digitsOnly := true
	for _, char := range value {
		if !((char >= 'a' && char <= 'z') || (char >= '0' && char <= '9') || char == '_') {
			return "", fmt.Errorf("%w: invalid username", ErrInvalid)
		}
		if char < '0' || char > '9' {
			digitsOnly = false
		}
	}
	if digitsOnly {
		return "", fmt.Errorf("%w: invalid username", ErrInvalid)
	}
	return Username(value), nil
}

func (u Username) String() string { return string(u) }

type RegistrationVerificationInput struct {
	Username Username
	Email    Email
	Password Password
}

// ParseRegistrationVerificationInput applies the current username, email, and
// password policy before registration verification material is created.
func ParseRegistrationVerificationInput(username, email, password string) (RegistrationVerificationInput, error) {
	parsedUsername, err := ParseUsername(username)
	if err != nil {
		return RegistrationVerificationInput{}, err
	}
	parsedEmail, err := ParseEmail(email)
	if err != nil {
		return RegistrationVerificationInput{}, err
	}
	parsedPassword, err := ParsePassword(password)
	if err != nil {
		return RegistrationVerificationInput{}, err
	}
	return RegistrationVerificationInput{Username: parsedUsername, Email: parsedEmail, Password: parsedPassword}, nil
}

type LoginIdentifierKind string

const (
	LoginIdentifierPhone    LoginIdentifierKind = "phone"
	LoginIdentifierEmail    LoginIdentifierKind = "email"
	LoginIdentifierUsername LoginIdentifierKind = "username"
)

func (k LoginIdentifierKind) String() string { return string(k) }

type LoginIdentifier struct {
	Kind  LoginIdentifierKind
	Value string
}

func ParseLoginIdentifier(value string) (LoginIdentifier, error) {
	if phone, err := ParsePhone(value); err == nil {
		return LoginIdentifier{Kind: LoginIdentifierPhone, Value: phone.String()}, nil
	}
	if email, err := ParseEmail(value); err == nil {
		return LoginIdentifier{Kind: LoginIdentifierEmail, Value: email.String()}, nil
	}
	username, err := ParseUsername(value)
	if err != nil {
		return LoginIdentifier{}, err
	}
	return LoginIdentifier{Kind: LoginIdentifierUsername, Value: username.String()}, nil
}

type Phone string

func ParsePhone(value string) (Phone, error) {
	value = strings.TrimSpace(value)
	if strings.HasPrefix(value, "+86") {
		value = value[3:]
	}
	if len(value) != 11 || value[0] != '1' || value[1] < '3' || value[1] > '9' {
		return "", fmt.Errorf("%w: invalid phone", ErrInvalid)
	}
	for _, char := range value {
		if char < '0' || char > '9' {
			return "", fmt.Errorf("%w: invalid phone", ErrInvalid)
		}
	}
	return Phone("+86" + value), nil
}

func (p Phone) String() string { return string(p) }

type PhoneCredentialsInput struct {
	Phone    Phone
	Password Password
}

// ParsePhoneCredentials applies the phone-registration password policy.
// ParsePassword remains intentionally length-only for legacy email credentials.
func ParsePhoneCredentials(phone, password string) (PhoneCredentialsInput, error) {
	parsedPhone, err := ParsePhone(phone)
	if err != nil {
		return PhoneCredentialsInput{}, err
	}
	parsedPassword, err := ParsePassword(password)
	if err != nil {
		return PhoneCredentialsInput{}, err
	}
	var upper, lower, digit bool
	for _, char := range password {
		switch {
		case char >= 'A' && char <= 'Z':
			upper = true
		case char >= 'a' && char <= 'z':
			lower = true
		case char >= '0' && char <= '9':
			digit = true
		}
	}
	if !upper || !lower || !digit {
		return PhoneCredentialsInput{}, fmt.Errorf("%w: invalid password", ErrInvalid)
	}
	return PhoneCredentialsInput{Phone: parsedPhone, Password: parsedPassword}, nil
}

type RefreshTokenValue string

func ParseRefreshToken(value string) (RefreshTokenValue, error) {
	if len(value) != 43 || strings.TrimSpace(value) != value {
		return "", fmt.Errorf("%w: invalid refresh token", ErrInvalid)
	}
	for _, char := range value {
		if !((char >= 'A' && char <= 'Z') || (char >= 'a' && char <= 'z') || (char >= '0' && char <= '9') || char == '-' || char == '_') {
			return "", fmt.Errorf("%w: invalid refresh token", ErrInvalid)
		}
	}
	return RefreshTokenValue(value), nil
}

func (t RefreshTokenValue) String() string { return string(t) }

type CredentialsInput struct {
	Email    Email
	Password Password
}

func ParseCredentials(email, password string) (CredentialsInput, error) {
	parsedEmail, err := ParseEmail(email)
	if err != nil {
		return CredentialsInput{}, err
	}
	parsedPassword, err := ParsePassword(password)
	if err != nil {
		return CredentialsInput{}, err
	}
	return CredentialsInput{Email: parsedEmail, Password: parsedPassword}, nil
}

type RefreshInput struct {
	Token RefreshTokenValue
}

func ParseRefreshInput(token string) (RefreshInput, error) {
	parsed, err := ParseRefreshToken(token)
	if err != nil {
		return RefreshInput{}, err
	}
	return RefreshInput{Token: parsed}, nil
}

type RedemptionCode string

func ParseRedemptionCode(value string) (RedemptionCode, error) {
	value = strings.TrimSpace(value)
	parts := strings.Split(value, "-")
	if len(parts) != 4 {
		return "", fmt.Errorf("%w: invalid redemption code", ErrInvalid)
	}
	for _, part := range parts {
		if len(part) != 6 {
			return "", fmt.Errorf("%w: invalid redemption code", ErrInvalid)
		}
		for _, char := range part {
			if !((char >= 'A' && char <= 'Z') || (char >= 'a' && char <= 'z') || (char >= '2' && char <= '7')) {
				return "", fmt.Errorf("%w: invalid redemption code", ErrInvalid)
			}
		}
	}
	return RedemptionCode(strings.ToUpper(value)), nil
}

func (c RedemptionCode) String() string { return string(c) }

type RedemptionInput struct {
	Code RedemptionCode
}

func ParseRedemptionInput(code string) (RedemptionInput, error) {
	parsed, err := ParseRedemptionCode(code)
	if err != nil {
		return RedemptionInput{}, err
	}
	return RedemptionInput{Code: parsed}, nil
}

type CreateBatchInput struct {
	Name  string
	Count int
}

func ParseCreateBatchInput(name string, count int) (CreateBatchInput, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > 100 || count < 1 || count > 1000 {
		return CreateBatchInput{}, fmt.Errorf("%w: batch name and count must be valid", ErrInvalid)
	}
	return CreateBatchInput{Name: name, Count: count}, nil
}

type User struct {
	ID        uuid.UUID `json:"id"`
	Username  string    `json:"username,omitempty"`
	Phone     string    `json:"-"`
	Role      string    `json:"role"`
	CreatedAt time.Time `json:"created_at"`
	Email     string    `json:"email,omitempty"`
}

type Device struct {
	ID         uuid.UUID `json:"id"`
	InstallID  string    `json:"install_id"`
	LastSeenAt time.Time `json:"last_seen_at"`
	CreatedAt  time.Time `json:"created_at"`
}

type EntitlementKind string

const (
	EntitlementTrial   EntitlementKind = "trial"
	EntitlementPackage EntitlementKind = "package"
)

type Entitlement struct {
	ID        uuid.UUID `json:"id"`
	UserID    uuid.UUID `json:"user_id"`
	Kind      string    `json:"kind"`
	StartsAt  time.Time `json:"starts_at"`
	ExpiresAt time.Time `json:"expires_at"`
}

func TrialPeriod(startsAt time.Time) (time.Time, time.Time, error) {
	return entitlementPeriod(startsAt, TrialDuration)
}

func RedemptionPeriod(startsAt time.Time) (time.Time, time.Time, error) {
	return entitlementPeriod(startsAt, RedemptionDuration)
}

func entitlementPeriod(startsAt time.Time, duration time.Duration) (time.Time, time.Time, error) {
	if startsAt.IsZero() {
		return time.Time{}, time.Time{}, fmt.Errorf("%w: entitlement start time is required", ErrInvalid)
	}
	startsAt = startsAt.UTC()
	return startsAt, startsAt.Add(duration), nil
}

func NewTrialEntitlement(id, userID uuid.UUID, startsAt time.Time) (Entitlement, error) {
	return newEntitlement(id, userID, EntitlementTrial, startsAt, TrialDuration)
}

func NewRedemptionEntitlement(id, userID uuid.UUID, startsAt time.Time) (Entitlement, error) {
	return newEntitlement(id, userID, EntitlementPackage, startsAt, RedemptionDuration)
}

func newEntitlement(id, userID uuid.UUID, kind EntitlementKind, startsAt time.Time, duration time.Duration) (Entitlement, error) {
	if id == uuid.Nil || userID == uuid.Nil || startsAt.IsZero() {
		return Entitlement{}, fmt.Errorf("%w: entitlement requires ids and start time", ErrInvalid)
	}
	startsAt = startsAt.UTC()
	return Entitlement{
		ID:        id,
		UserID:    userID,
		Kind:      string(kind),
		StartsAt:  startsAt,
		ExpiresAt: startsAt.Add(duration),
	}, nil
}

func (e Entitlement) ActiveAt(now time.Time) bool {
	return !now.Before(e.StartsAt) && now.Before(e.ExpiresAt)
}

func (e Entitlement) Valid() bool {
	if e.ID == uuid.Nil || e.UserID == uuid.Nil || e.StartsAt.IsZero() || !e.ExpiresAt.After(e.StartsAt) {
		return false
	}
	switch EntitlementKind(e.Kind) {
	case EntitlementTrial:
		return e.ExpiresAt.Equal(e.StartsAt.Add(TrialDuration))
	case EntitlementPackage:
		return e.ExpiresAt.Equal(e.StartsAt.Add(RedemptionDuration))
	default:
		return false
	}
}

type RefreshToken struct {
	ID           uuid.UUID
	UserID       uuid.UUID
	FamilyID     uuid.UUID
	TokenHash    []byte
	ExpiresAt    time.Time
	RevokedAt    *time.Time
	ReplacedByID *uuid.UUID
}

func (t RefreshToken) ActiveAt(now time.Time) bool {
	return t.RevokedAt == nil && now.Before(t.ExpiresAt)
}

func (t RefreshToken) Valid() bool {
	return t.ID != uuid.Nil && t.UserID != uuid.Nil && t.FamilyID != uuid.Nil && len(t.TokenHash) == 32 && !t.ExpiresAt.IsZero()
}

type TranslationSession struct {
	ID                uuid.UUID  `json:"id"`
	UserID            uuid.UUID  `json:"user_id"`
	EntitlementID     uuid.UUID  `json:"entitlement_id,omitempty"`
	InstallID         string     `json:"install_id"`
	JTI               uuid.UUID  `json:"jti"`
	ExpiresAt         time.Time  `json:"expires_at"`
	CreatedAt         time.Time  `json:"created_at"`
	EndedAt           *time.Time `json:"ended_at,omitempty"`
	RevokedAt         *time.Time `json:"revoked_at,omitempty"`
	TerminationReason string     `json:"termination_reason,omitempty"`
}

// TranslationTerminationReason explains why a translation session stopped
// being usable. Every non-expiry terminal transition persists one of these
// values alongside its terminal timestamp; natural expiry is resolved at read
// time instead. Agent clients use the reason to tell a displaced device why
// its session ended.
type TranslationTerminationReason string

const (
	TerminationEnded              TranslationTerminationReason = "ended"
	TerminationRevoked            TranslationTerminationReason = "revoked"
	TerminationReplacedByDevice   TranslationTerminationReason = "replaced_by_device"
	TerminationEntitlementRevoked TranslationTerminationReason = "entitlement_revoked"
	TerminationUserDisabled       TranslationTerminationReason = "user_disabled"
	// TerminationExpired is the read-time reason for a session whose
	// expires_at has passed; it is never stored because no terminal
	// transition occurred.
	TerminationExpired TranslationTerminationReason = "expired"
)

// ValidTranslationTerminationReason reports whether the value is one of the
// reasons the persisted lifecycle can record.
func ValidTranslationTerminationReason(value string) bool {
	switch TranslationTerminationReason(value) {
	case TerminationEnded, TerminationRevoked, TerminationReplacedByDevice, TerminationEntitlementRevoked, TerminationUserDisabled:
		return true
	default:
		return false
	}
}

// TranslationSessionAuthorization is the persisted authorization truth for
// one presented translation token identity set (owner, session, entitlement,
// JTI). Active reports whether the session is usable at the evaluation time;
// when it is not, TerminationReason carries the resolved explanation so the
// Agent boundary can distinguish device replacement from explicit end,
// revocation, disablement, entitlement revocation, and natural expiry.
type TranslationSessionAuthorization struct {
	SessionID         uuid.UUID
	UserID            uuid.UUID
	EntitlementID     uuid.UUID
	JTI               uuid.UUID
	InstallID         string
	Active            bool
	ExpiresAt         time.Time
	EndedAt           *time.Time
	RevokedAt         *time.Time
	TerminationReason TranslationTerminationReason
}

type UsageRecord struct {
	ID           uuid.UUID `json:"id"`
	UserID       uuid.UUID `json:"user_id"`
	SessionID    uuid.UUID `json:"session_id"`
	AudioSeconds int       `json:"audio_seconds"`
	Characters   int       `json:"characters"`
	CreatedAt    time.Time `json:"created_at"`
}

type FeedbackConsent struct {
	ID        uuid.UUID `json:"id"`
	UserID    uuid.UUID `json:"user_id"`
	Granted   bool      `json:"granted"`
	CreatedAt time.Time `json:"created_at"`
}

type FeedbackArtifact struct {
	ID        uuid.UUID `json:"id"`
	UserID    uuid.UUID `json:"user_id"`
	ConsentID uuid.UUID `json:"consent_id"`
	ObjectKey string    `json:"object_key"`
	ExpiresAt time.Time `json:"expires_at"`
	CreatedAt time.Time `json:"created_at"`
}

type AuditLog struct {
	ID         uuid.UUID      `json:"id"`
	AdminID    uuid.UUID      `json:"admin_id"`
	Action     string         `json:"action"`
	TargetType string         `json:"target_type"`
	TargetID   *uuid.UUID     `json:"target_id,omitempty"`
	Metadata   map[string]any `json:"metadata"`
	CreatedAt  time.Time      `json:"created_at"`
}

type CodeBatch struct {
	ID           uuid.UUID `json:"id"`
	Name         string    `json:"name"`
	DurationDays int       `json:"duration_days"`
	CreatedBy    uuid.UUID `json:"created_by"`
	CreatedAt    time.Time `json:"created_at"`
}

type RegisterParams struct {
	Username, Email, Phone, PasswordHash string
	Now                                  time.Time
}

type RegistrationVerification struct {
	ID            uuid.UUID
	ReservationID uuid.UUID
	Username      string
	Email         string
	PasswordHash  string
	CodeHash      []byte
	CodeSalt      []byte
	ExpiresAt     time.Time
	AttemptCount  int
	SentAt        time.Time
	CreatedAt     time.Time
	UpdatedAt     time.Time
}

type CreateRegistrationVerificationParams struct {
	Username, Email, PasswordHash     string
	CodeHash, CodeSalt                []byte
	EmailRateLimitKey, IPRateLimitKey []byte
	Now                               time.Time
	ExpiresAt                         time.Time
}

type InvalidateRegistrationVerificationParams struct {
	ReservationID uuid.UUID
	Email         string
	Now           time.Time
}

type ConfirmRegistrationVerificationParams struct {
	Email, Code       string
	CodePepper        []byte
	EmailRateLimitKey []byte
	Now               time.Time
}

type CreateRegistrationCaptchaParams struct {
	AnswerHash, AnswerSalt []byte
	Now, ExpiresAt         time.Time
}

type RegistrationCaptcha struct {
	ID        uuid.UUID
	ExpiresAt time.Time
}

type RegisterWithCaptchaParams struct {
	Username, Email, PasswordHash string
	CaptchaID                     uuid.UUID
	CaptchaAnswer                 string
	AnswerPepper                  []byte
	Now                           time.Time
}

// ReserveRegistrationCaptchaParams carries only the material needed to
// validate and reserve one challenge answer before any password hashing
// costs are paid.
type ReserveRegistrationCaptchaParams struct {
	CaptchaID     uuid.UUID
	CaptchaAnswer string
	AnswerPepper  []byte
	Now           time.Time
}

type CreateRefreshParams struct {
	UserID, FamilyID uuid.UUID
	Hash             []byte
	ExpiresAt        time.Time
}

type CreateBatchParams struct {
	AdminID      uuid.UUID
	Name         string
	DurationDays int
	CodeHashes   [][]byte
	Now          time.Time
}

type CreateUsageParams struct {
	UserID, SessionID        uuid.UUID
	AudioSeconds, Characters int
	Now                      time.Time
}

type CreateArtifactParams struct {
	UserID, ConsentID uuid.UUID
	ObjectKey         string
	ExpiresAt, Now    time.Time
}

type RefreshTokenStore interface {
	CreateRefreshToken(context.Context, CreateRefreshParams) (RefreshToken, error)
	// RotateRefreshToken must atomically revoke the current token, create its
	// replacement in the same family, and revoke the entire family when a
	// previously revoked token hash is presented again.
	RotateRefreshToken(context.Context, []byte, []byte, time.Time, time.Time) (RefreshToken, RefreshToken, error)
	RevokeRefreshToken(context.Context, []byte, time.Time) error
}

type Store interface {
	RefreshTokenStore
	Register(context.Context, RegisterParams) (User, Entitlement, error)
	RequestRegistrationVerification(context.Context, CreateRegistrationVerificationParams) (RegistrationVerification, error)
	ConfirmRegistrationVerification(context.Context, ConfirmRegistrationVerificationParams) (RegisterParams, error)
	InvalidateRegistrationVerification(context.Context, InvalidateRegistrationVerificationParams) error
	// ChargeCaptchaIssueWindow independently commits one increment of the
	// per trusted client IP fixed window guarding captcha issuance. It always
	// persists, including when the subsequent challenge insert fails.
	ChargeCaptchaIssueWindow(context.Context, []byte, time.Time) error
	// CreateRegistrationCaptcha persists only the salted answer hash; the
	// issue rate window is charged separately through ChargeCaptchaIssueWindow.
	CreateRegistrationCaptcha(context.Context, CreateRegistrationCaptchaParams) (RegistrationCaptcha, error)
	// ChargeCaptchaRegisterWindow independently commits one increment of the
	// per trusted client IP fixed window guarding registration attempts. It
	// must be charged for every validly formatted register request before any
	// expensive work and survives later transaction rollbacks such as identity
	// conflicts, so conflicts cannot bypass the persistent per-IP limit.
	ChargeCaptchaRegisterWindow(context.Context, []byte, time.Time) error
	// ReserveRegistrationCaptcha validates one captcha answer before any
	// expensive password hashing runs; only wrong answers mutate state, and
	// the challenge itself stays consumable exclusively through the committed
	// registration below so identity conflicts never consume it.
	ReserveRegistrationCaptcha(context.Context, ReserveRegistrationCaptchaParams) error
	// RegisterWithCaptcha atomically re-verifies and consumes one captcha
	// answer and, on match, creates the user, password credential, and trial
	// entitlement in a single transaction. The captcha is consumed only by the
	// committed success, expiry, attempt exhaustion, or a matching
	// verification; a rolled back registration leaves it in place.
	RegisterWithCaptcha(context.Context, RegisterWithCaptchaParams) (User, Entitlement, error)
	UserByEmail(context.Context, string) (User, string, error)
	UserByPhone(context.Context, string) (User, string, error)
	UserByUsername(context.Context, string) (User, string, error)
	UserByID(context.Context, uuid.UUID) (User, error)
	ActiveEntitlement(context.Context, uuid.UUID, time.Time) (Entitlement, error)
	CreateCodeBatch(context.Context, CreateBatchParams) (CodeBatch, error)
	RedeemCode(context.Context, uuid.UUID, []byte, time.Time) (Entitlement, error)
	CreateTranslationSession(context.Context, TranslationSession) error
	CreateUsageRecord(context.Context, CreateUsageParams) error
	CreateFeedbackConsent(context.Context, uuid.UUID, bool, time.Time) (FeedbackConsent, error)
	CreateFeedbackArtifact(context.Context, CreateArtifactParams) (FeedbackArtifact, error)
	FeedbackArtifact(context.Context, uuid.UUID, uuid.UUID) (FeedbackArtifact, error)
	ListUsers(context.Context, string, int, int) ([]User, error)
	ListUsageRecords(context.Context, uuid.UUID, int, int) ([]UsageRecord, error)
	ListTranslationSessions(context.Context, uuid.UUID, int, int) ([]TranslationSession, error)
	ListDevices(context.Context, uuid.UUID) ([]Device, error)
	ListAuditLogs(context.Context, int, int) ([]AuditLog, error)
	AccountStore
	HistoryStore
}

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
	ErrNotFound      = errors.New("not found")
	ErrConflict      = errors.New("conflict")
	ErrUnauthorized  = errors.New("unauthorized")
	ErrForbidden     = errors.New("forbidden")
	ErrInvalid       = errors.New("invalid input")
	ErrNoEntitlement = errors.New("no active entitlement")
)

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
	for _, char := range value {
		if !((char >= 'a' && char <= 'z') || (char >= '0' && char <= '9') || char == '_') {
			return "", fmt.Errorf("%w: invalid username", ErrInvalid)
		}
	}
	return Username(value), nil
}

func (u Username) String() string { return string(u) }

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
	Email     string    `json:"email"`
	Role      string    `json:"role"`
	CreatedAt time.Time `json:"created_at"`
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
	ID            uuid.UUID `json:"id"`
	UserID        uuid.UUID `json:"user_id"`
	EntitlementID uuid.UUID `json:"entitlement_id,omitempty"`
	InstallID     string    `json:"install_id"`
	JTI           uuid.UUID `json:"jti"`
	ExpiresAt     time.Time `json:"expires_at"`
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
	Email, PasswordHash string
	Now                 time.Time
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
	UserByEmail(context.Context, string) (User, string, error)
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
}

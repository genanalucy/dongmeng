package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base32"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

const (
	MinimumSecretBytes   = 32
	refreshSecretBytes   = 32
	redemptionCodeBytes  = 15
	translationTokenType = "translation_session"
)

type Scope string

const (
	ScopeAPI         Scope = "api"
	ScopeTranslation Scope = "translation"
)

func (s Scope) Valid() bool { return s == ScopeAPI || s == ScopeTranslation }

type Claims struct {
	Role      domain.Role `json:"role,omitempty"`
	UserID    string      `json:"user_id,omitempty"`
	SessionID string      `json:"session_id,omitempty"`
	InstallID string      `json:"install_id,omitempty"`
	// EntitlementID is retained only for Cloud's persisted authorization
	// checks. It is not used as, or substituted for, an install_id.
	EntitlementID string `json:"entitlement_id,omitempty"`
	Scope         Scope  `json:"scope"`
	jwt.RegisteredClaims
}

type TokenIssuer struct {
	Issuer          string
	Audience        string
	SessionAudience string
	AccessSecret    []byte
	SessionSecret   []byte
}

func (i TokenIssuer) AccessToken(userID uuid.UUID, role string, ttl time.Duration, now time.Time) (string, error) {
	if err := i.validateConfiguration(); err != nil {
		return "", err
	}
	parsedRole, err := domain.ParseRole(role)
	if err != nil {
		return "", err
	}
	if err := validateTokenArguments(i.Issuer, i.Audience, i.AccessSecret, userID, ttl, now); err != nil {
		return "", err
	}
	return sign(i.AccessSecret, Claims{Role: parsedRole, Scope: ScopeAPI, RegisteredClaims: registeredClaims(i.Issuer, i.Audience, userID, uuid.New(), ttl, now)}, "JWT")
}

// TranslationToken implements the main Agent contract. installID is an opaque,
// client-supplied installation identifier persisted with the session; it is not
// an entitlement identifier and is never synthesized by the server.
// TranslationTokenForInstall signs a main-Agent compatible token. installID is
// always caller-provided and never derived from an entitlement.
func (i TokenIssuer) TranslationTokenForInstall(sessionID, entitlementID, userID, jti uuid.UUID, installID string, ttl time.Duration, now time.Time) (string, error) {
	if err := i.validateConfiguration(); err != nil {
		return "", err
	}
	if sessionID == uuid.Nil || entitlementID == uuid.Nil || jti == uuid.Nil || !validInstallID(installID) {
		return "", fmt.Errorf("%w: session, entitlement, token, and install ids are required", domain.ErrInvalid)
	}
	audience := i.translationAudience()
	if err := validateTokenArguments(i.Issuer, audience, i.SessionSecret, userID, ttl, now); err != nil {
		return "", err
	}
	return sign(i.SessionSecret, Claims{UserID: userID.String(), SessionID: sessionID.String(), InstallID: installID, EntitlementID: entitlementID.String(), Scope: ScopeTranslation, RegisteredClaims: registeredClaims(i.Issuer, audience, userID, jti, ttl, now)}, translationTokenType)
}

// TranslationToken is intentionally retained for source compatibility only.
// Callers must migrate to TranslationTokenForInstall; no entitlement may be
// silently converted into an installation identity.
func (i TokenIssuer) TranslationToken(sessionID, entitlementID, userID, jti uuid.UUID, ttl time.Duration, now time.Time) (string, error) {
	return "", fmt.Errorf("%w: install_id is required; use TranslationTokenForInstall", domain.ErrInvalid)
}

func (i TokenIssuer) ParseAccess(token string) (Claims, error) {
	return i.ParseAccessAt(token, time.Now())
}
func (i TokenIssuer) ParseAccessAt(token string, now time.Time) (Claims, error) {
	if err := i.validateConfiguration(); err != nil {
		return Claims{}, errors.New("invalid access token")
	}
	claims, err := i.parseWithAudience(token, i.AccessSecret, i.Audience, now, "JWT")
	if err != nil || claims.Scope != ScopeAPI || !claims.Role.Valid() || claims.SessionID != "" || claims.UserID != "" || claims.InstallID != "" {
		return Claims{}, errors.New("invalid access token")
	}
	return claims, nil
}
func (i TokenIssuer) ParseTranslation(token string) (Claims, error) {
	return i.ParseTranslationAt(token, time.Now())
}
func (i TokenIssuer) ParseTranslationAt(token string, now time.Time) (Claims, error) {
	if err := i.validateConfiguration(); err != nil {
		return Claims{}, errors.New("invalid translation token")
	}
	claims, err := i.parseWithAudience(token, i.SessionSecret, i.translationAudience(), now, translationTokenType)
	if err != nil || claims.Scope != ScopeTranslation || claims.Role != "" || claims.UserID != claims.Subject || !requiredUUID(claims.SessionID) || !validInstallID(claims.InstallID) || claims.NotBefore == nil || !claims.NotBefore.Time.Equal(claims.IssuedAt.Time) {
		return Claims{}, errors.New("invalid translation token")
	}
	return claims, nil
}
func (i TokenIssuer) parseWithAudience(token string, secret []byte, audience string, now time.Time, expectedType string) (Claims, error) {
	if strings.TrimSpace(token) != token || token == "" || now.IsZero() || i.Issuer == "" || strings.TrimSpace(i.Issuer) != i.Issuer || audience == "" || strings.TrimSpace(audience) != audience || len(secret) < MinimumSecretBytes {
		return Claims{}, errors.New("invalid token")
	}
	var claims Claims
	parsed, err := jwt.ParseWithClaims(token, &claims, func(token *jwt.Token) (interface{}, error) {
		if token.Method != jwt.SigningMethodHS256 || token.Header["alg"] != jwt.SigningMethodHS256.Alg() || token.Header["typ"] != expectedType {
			return nil, errors.New("unexpected token header")
		}
		return secret, nil
	}, jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}), jwt.WithIssuer(i.Issuer), jwt.WithAudience(audience), jwt.WithExpirationRequired(), jwt.WithIssuedAt(), jwt.WithStrictDecoding(), jwt.WithTimeFunc(func() time.Time { return now }))
	if err != nil || parsed == nil || !parsed.Valid || claims.IssuedAt == nil || claims.NotBefore == nil || claims.ExpiresAt == nil || !claims.ExpiresAt.Time.After(claims.IssuedAt.Time) || len(claims.Audience) != 1 || claims.Audience[0] != audience || !requiredUUID(claims.Subject) || !requiredUUID(claims.ID) {
		return Claims{}, errors.New("invalid token")
	}
	return claims, nil
}
func requiredUUID(value string) bool {
	parsed, err := uuid.Parse(value)
	return err == nil && parsed != uuid.Nil && parsed.String() == value
}
func validInstallID(value string) bool {
	return value != "" && len(value) <= 128 && strings.TrimSpace(value) == value
}
func registeredClaims(issuer, audience string, subject, jti uuid.UUID, ttl time.Duration, now time.Time) jwt.RegisteredClaims {
	return jwt.RegisteredClaims{Issuer: issuer, Subject: subject.String(), Audience: jwt.ClaimStrings{audience}, ExpiresAt: jwt.NewNumericDate(now.Add(ttl)), IssuedAt: jwt.NewNumericDate(now), NotBefore: jwt.NewNumericDate(now), ID: jti.String()}
}
func validateTokenArguments(issuer, audience string, secret []byte, subject uuid.UUID, ttl time.Duration, now time.Time) error {
	if issuer == "" || strings.TrimSpace(issuer) != issuer || audience == "" || strings.TrimSpace(audience) != audience || len(secret) < MinimumSecretBytes || subject == uuid.Nil || ttl <= 0 || now.IsZero() {
		return fmt.Errorf("%w: invalid token issuer configuration or arguments", domain.ErrInvalid)
	}
	return nil
}
func (i TokenIssuer) validateConfiguration() error {
	if len(i.AccessSecret) < MinimumSecretBytes || len(i.SessionSecret) < MinimumSecretBytes || subtle.ConstantTimeCompare(i.AccessSecret, i.SessionSecret) == 1 {
		return fmt.Errorf("%w: access and session secrets must be strong and distinct", domain.ErrInvalid)
	}
	if i.SessionAudience == "" || strings.TrimSpace(i.SessionAudience) != i.SessionAudience || i.SessionAudience == i.Audience {
		return fmt.Errorf("%w: a distinct session audience is required", domain.ErrInvalid)
	}
	return nil
}
func (i TokenIssuer) translationAudience() string { return i.SessionAudience }
func sign(secret []byte, claims Claims, tokenTypes ...string) (string, error) {
	tokenType := "JWT"
	if len(tokenTypes) == 1 {
		tokenType = tokenTypes[0]
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	token.Header["typ"] = tokenType
	value, err := token.SignedString(secret)
	if err != nil {
		return "", fmt.Errorf("sign token: %w", err)
	}
	return value, nil
}
func RandomSecret(size int) (string, []byte, error) { return randomSecret(rand.Reader, size) }
func randomSecret(random io.Reader, size int) (string, []byte, error) {
	if size < MinimumSecretBytes || size > 1024 {
		return "", nil, fmt.Errorf("%w: secret size must be between %d and 1024 bytes", domain.ErrInvalid, MinimumSecretBytes)
	}
	raw := make([]byte, size)
	if _, err := io.ReadFull(random, raw); err != nil {
		return "", nil, fmt.Errorf("generate random secret: %w", err)
	}
	plain := base64.RawURLEncoding.EncodeToString(raw)
	return plain, HashSecret(plain), nil
}
func HashSecret(value string) []byte { sum := sha256.Sum256([]byte(value)); return sum[:] }
func SecretHashEqual(first, second []byte) bool {
	return len(first) == sha256.Size && len(second) == sha256.Size && subtle.ConstantTimeCompare(first, second) == 1
}
func RandomCode() (string, []byte, error) {
	raw := make([]byte, redemptionCodeBytes)
	if _, err := io.ReadFull(rand.Reader, raw); err != nil {
		return "", nil, fmt.Errorf("generate redemption code: %w", err)
	}
	encoded := base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(raw)
	plain := strings.Join([]string{encoded[0:6], encoded[6:12], encoded[12:18], encoded[18:24]}, "-")
	canonical, err := CanonicalRedemptionCode(plain)
	if err != nil {
		return "", nil, fmt.Errorf("canonicalize generated redemption code: %w", err)
	}
	return canonical, HashSecret(canonical), nil
}
func CanonicalRedemptionCode(value string) (string, error) {
	code, err := domain.ParseRedemptionCode(value)
	if err != nil {
		return "", err
	}
	return code.String(), nil
}
func HashRedemptionCode(value string) ([]byte, error) {
	canonical, err := CanonicalRedemptionCode(value)
	if err != nil {
		return nil, err
	}
	return HashSecret(canonical), nil
}

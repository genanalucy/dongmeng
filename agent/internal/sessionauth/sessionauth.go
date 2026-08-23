// Package sessionauth verifies short-lived translation-session JWTs.
package sessionauth

import (
	"errors"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const (
	// TokenType is the only accepted JWT typ header value.
	TokenType = "translation_session"

	minimumHMACKeyBytes = 32
)

var (
	// ErrInvalidConfig reports verifier configuration that is unsafe or incomplete.
	ErrInvalidConfig = errors.New("invalid session token verifier configuration")
	// ErrInvalidToken intentionally hides parsing and validation details so callers
	// cannot accidentally expose token contents or validation internals.
	ErrInvalidToken = errors.New("invalid translation session token")
)

// Config contains all trust and time inputs required by a Verifier.
// HMACKey is copied by NewVerifier and must be provisioned by the caller.
type Config struct {
	HMACKey     []byte
	Issuer      string
	Audience    string
	ClockSkew   time.Duration
	MaxLifetime time.Duration
	Now         func() time.Time
}

// Expected identifies the client context to which a token must be bound.
type Expected struct {
	Subject   string
	UserID    string
	SessionID string
	InstallID string
}

// Claims are the verified translation-session claims returned to callers.
type Claims struct {
	UserID    string `json:"user_id"`
	SessionID string `json:"session_id"`
	InstallID string `json:"install_id"`
	jwt.RegisteredClaims
}

// Verifier is immutable and safe for concurrent use.
type Verifier struct {
	key         []byte
	issuer      string
	audience    string
	clockSkew   time.Duration
	maxLifetime time.Duration
	now         func() time.Time
}

// NewVerifier validates config and returns an immutable verifier.
func NewVerifier(cfg Config) (*Verifier, error) {
	if len(cfg.HMACKey) < minimumHMACKeyBytes || !isCleanNonEmpty(cfg.Issuer) ||
		!isCleanNonEmpty(cfg.Audience) || cfg.ClockSkew < 0 || cfg.MaxLifetime <= 0 {
		return nil, ErrInvalidConfig
	}

	now := cfg.Now
	if now == nil {
		now = time.Now
	}

	return &Verifier{
		key:         append([]byte(nil), cfg.HMACKey...),
		issuer:      cfg.Issuer,
		audience:    cfg.Audience,
		clockSkew:   cfg.ClockSkew,
		maxLifetime: cfg.MaxLifetime,
		now:         now,
	}, nil
}

// Verify authenticates tokenString and strictly binds it to expected.
// Every token failure returns ErrInvalidToken without including tokenString.
func (v *Verifier) Verify(tokenString string, expected Expected) (Claims, error) {
	if v == nil || !validExpectedContext(expected) || strings.TrimSpace(tokenString) == "" {
		return Claims{}, ErrInvalidToken
	}

	claims := Claims{}
	token, err := jwt.ParseWithClaims(
		tokenString,
		&claims,
		func(token *jwt.Token) (any, error) {
			if token.Method != jwt.SigningMethodHS256 || token.Header["alg"] != jwt.SigningMethodHS256.Alg() {
				return nil, errors.New("unexpected signing method")
			}
			typ, ok := token.Header["typ"].(string)
			if !ok || typ != TokenType {
				return nil, errors.New("unexpected token type")
			}
			return v.key, nil
		},
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}),
		jwt.WithIssuer(v.issuer),
		jwt.WithAudience(v.audience),
		jwt.WithSubject(expected.Subject),
		jwt.WithExpirationRequired(),
		jwt.WithIssuedAt(),
		jwt.WithLeeway(v.clockSkew),
		jwt.WithTimeFunc(v.now),
		jwt.WithStrictDecoding(),
	)
	if err != nil || token == nil || !token.Valid {
		return Claims{}, ErrInvalidToken
	}

	if err := v.validateClaims(claims, expected); err != nil {
		return Claims{}, ErrInvalidToken
	}
	return claims, nil
}

func (v *Verifier) validateClaims(claims Claims, expected Expected) error {
	if claims.UserID != expected.UserID || claims.SessionID != expected.SessionID ||
		claims.InstallID != expected.InstallID {
		return errors.New("claims do not match expected context")
	}
	if claims.Subject != claims.UserID || claims.IssuedAt == nil || claims.ExpiresAt == nil {
		return errors.New("required claims are missing or inconsistent")
	}
	if len(claims.Audience) != 1 || claims.Audience[0] != v.audience {
		return errors.New("audience must exactly match")
	}
	if !claims.ExpiresAt.Time.After(claims.IssuedAt.Time) || claims.ExpiresAt.Time.Sub(claims.IssuedAt.Time) > v.maxLifetime {
		return errors.New("invalid token lifetime")
	}
	return nil
}

func validExpectedContext(expected Expected) bool {
	return isCleanNonEmpty(expected.Subject) && isCleanNonEmpty(expected.UserID) &&
		isCleanNonEmpty(expected.SessionID) && isCleanNonEmpty(expected.InstallID) &&
		expected.Subject == expected.UserID
}

func isCleanNonEmpty(value string) bool {
	return value != "" && strings.TrimSpace(value) == value
}

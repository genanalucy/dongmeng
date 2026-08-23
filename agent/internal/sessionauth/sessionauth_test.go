package sessionauth

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

var (
	testNow = time.Date(2026, time.January, 2, 3, 4, 5, 0, time.UTC)
	testKey = []byte("0123456789abcdef0123456789abcdef")
)

const (
	testIssuer   = "cloud-api"
	testAudience = "translator-agent"
	testUserID   = "user-123"
	testSession  = "session-456"
	testInstall  = "install-789"
)

func TestVerifyAcceptsValidTranslationSessionToken(t *testing.T) {
	verifier := newTestVerifier(t)
	token := signToken(t, validClaims(), jwt.SigningMethodHS256, map[string]any{"typ": TokenType})

	claims, err := verifier.Verify(token, validExpected())
	if err != nil {
		t.Fatalf("Verify() error = %v", err)
	}
	if claims.Subject != testUserID || claims.UserID != testUserID || claims.SessionID != testSession || claims.InstallID != testInstall {
		t.Fatalf("Verify() claims = %+v", claims)
	}
}

func TestNewVerifierRejectsInvalidConfig(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*Config)
	}{
		{name: "short key", mutate: func(c *Config) { c.HMACKey = []byte("too-short") }},
		{name: "empty issuer", mutate: func(c *Config) { c.Issuer = "" }},
		{name: "padded issuer", mutate: func(c *Config) { c.Issuer = " cloud-api" }},
		{name: "empty audience", mutate: func(c *Config) { c.Audience = "" }},
		{name: "negative skew", mutate: func(c *Config) { c.ClockSkew = -time.Second }},
		{name: "zero lifetime", mutate: func(c *Config) { c.MaxLifetime = 0 }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg := validConfig()
			tt.mutate(&cfg)
			_, err := NewVerifier(cfg)
			if !errors.Is(err, ErrInvalidConfig) {
				t.Fatalf("NewVerifier() error = %v, want ErrInvalidConfig", err)
			}
		})
	}
}

func TestVerifierCopiesHMACKey(t *testing.T) {
	cfg := validConfig()
	verifier, err := NewVerifier(cfg)
	if err != nil {
		t.Fatalf("NewVerifier() error = %v", err)
	}
	token := signToken(t, validClaims(), jwt.SigningMethodHS256, map[string]any{"typ": TokenType})

	for i := range cfg.HMACKey {
		cfg.HMACKey[i] = 'x'
	}
	if _, err := verifier.Verify(token, validExpected()); err != nil {
		t.Fatalf("Verify() after caller key mutation error = %v", err)
	}
}

func TestVerifyRejectsInvalidTokenHeaderAndSignature(t *testing.T) {
	verifier := newTestVerifier(t)
	tests := []struct {
		name  string
		token func(*testing.T) string
	}{
		{
			name: "HS384 algorithm",
			token: func(t *testing.T) string {
				return signToken(t, validClaims(), jwt.SigningMethodHS384, map[string]any{"typ": TokenType})
			},
		},
		{
			name: "missing typ",
			token: func(t *testing.T) string {
				return signToken(t, validClaims(), jwt.SigningMethodHS256, nil)
			},
		},
		{
			name: "wrong typ",
			token: func(t *testing.T) string {
				return signToken(t, validClaims(), jwt.SigningMethodHS256, map[string]any{"typ": "JWT"})
			},
		},
		{
			name: "wrong signature",
			token: func(t *testing.T) string {
				return signTokenWithKey(t, validClaims(), jwt.SigningMethodHS256, map[string]any{"typ": TokenType}, []byte("abcdef0123456789abcdef0123456789"))
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			assertInvalidToken(t, verifier, tt.token(t), validExpected())
		})
	}
}

func TestVerifyStrictlyChecksIdentityAndPurposeClaims(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*Claims)
	}{
		{name: "issuer", mutate: func(c *Claims) { c.Issuer = "other-api" }},
		{name: "audience", mutate: func(c *Claims) { c.Audience = jwt.ClaimStrings{"other-agent"} }},
		{name: "additional audience", mutate: func(c *Claims) { c.Audience = jwt.ClaimStrings{testAudience, "other-agent"} }},
		{name: "missing audience", mutate: func(c *Claims) { c.Audience = nil }},
		{name: "subject", mutate: func(c *Claims) { c.Subject = "other-user" }},
		{name: "missing subject", mutate: func(c *Claims) { c.Subject = "" }},
		{name: "user", mutate: func(c *Claims) { c.UserID = "other-user" }},
		{name: "missing user", mutate: func(c *Claims) { c.UserID = "" }},
		{name: "session", mutate: func(c *Claims) { c.SessionID = "other-session" }},
		{name: "missing session", mutate: func(c *Claims) { c.SessionID = "" }},
		{name: "install", mutate: func(c *Claims) { c.InstallID = "other-install" }},
		{name: "missing install", mutate: func(c *Claims) { c.InstallID = "" }},
	}

	verifier := newTestVerifier(t)
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			claims := validClaims()
			tt.mutate(&claims)
			token := signToken(t, claims, jwt.SigningMethodHS256, map[string]any{"typ": TokenType})
			assertInvalidToken(t, verifier, token, validExpected())
		})
	}
}

func TestVerifyRejectsWrongExpectedBinding(t *testing.T) {
	verifier := newTestVerifier(t)
	token := signToken(t, validClaims(), jwt.SigningMethodHS256, map[string]any{"typ": TokenType})
	tests := []struct {
		name   string
		mutate func(*Expected)
	}{
		{name: "subject", mutate: func(e *Expected) { e.Subject = "other-user"; e.UserID = "other-user" }},
		{name: "user", mutate: func(e *Expected) { e.UserID = "other-user" }},
		{name: "session", mutate: func(e *Expected) { e.SessionID = "other-session" }},
		{name: "install", mutate: func(e *Expected) { e.InstallID = "other-install" }},
		{name: "empty install", mutate: func(e *Expected) { e.InstallID = "" }},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			expected := validExpected()
			tt.mutate(&expected)
			assertInvalidToken(t, verifier, token, expected)
		})
	}
}

func TestVerifyStrictlyChecksExpirationIssuedAtAndLifetime(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*Claims)
	}{
		{name: "missing expiration", mutate: func(c *Claims) { c.ExpiresAt = nil }},
		{name: "missing issued at", mutate: func(c *Claims) { c.IssuedAt = nil }},
		{name: "expired beyond skew", mutate: func(c *Claims) { c.ExpiresAt = jwt.NewNumericDate(testNow.Add(-31 * time.Second)) }},
		{name: "issued in future beyond skew", mutate: func(c *Claims) {
			c.IssuedAt = jwt.NewNumericDate(testNow.Add(31 * time.Second))
			c.ExpiresAt = jwt.NewNumericDate(testNow.Add(5 * time.Minute))
		}},
		{name: "expiration before issued at", mutate: func(c *Claims) {
			c.IssuedAt = jwt.NewNumericDate(testNow.Add(time.Minute))
			c.ExpiresAt = jwt.NewNumericDate(testNow.Add(time.Minute))
		}},
		{name: "lifetime too long", mutate: func(c *Claims) { c.ExpiresAt = jwt.NewNumericDate(testNow.Add(5*time.Minute + time.Second)) }},
	}

	verifier := newTestVerifier(t)
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			claims := validClaims()
			tt.mutate(&claims)
			token := signToken(t, claims, jwt.SigningMethodHS256, map[string]any{"typ": TokenType})
			assertInvalidToken(t, verifier, token, validExpected())
		})
	}
}

func TestVerifyHonorsClockSkewBoundary(t *testing.T) {
	verifier := newTestVerifier(t)
	tests := []struct {
		name   string
		mutate func(*Claims)
	}{
		{
			name: "recently expired",
			mutate: func(c *Claims) {
				c.IssuedAt = jwt.NewNumericDate(testNow.Add(-5 * time.Minute))
				c.ExpiresAt = jwt.NewNumericDate(testNow.Add(-29 * time.Second))
			},
		},
		{
			name: "slightly future issued at",
			mutate: func(c *Claims) {
				c.IssuedAt = jwt.NewNumericDate(testNow.Add(29 * time.Second))
				c.ExpiresAt = jwt.NewNumericDate(testNow.Add(5 * time.Minute))
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			claims := validClaims()
			tt.mutate(&claims)
			token := signToken(t, claims, jwt.SigningMethodHS256, map[string]any{"typ": TokenType})
			if _, err := verifier.Verify(token, validExpected()); err != nil {
				t.Fatalf("Verify() error = %v", err)
			}
		})
	}
}

func TestVerifyErrorsNeverContainToken(t *testing.T) {
	verifier := newTestVerifier(t)
	wrongSignature := signTokenWithKey(t, validClaims(), jwt.SigningMethodHS256, map[string]any{"typ": TokenType}, []byte("abcdef0123456789abcdef0123456789"))
	tests := []struct {
		name     string
		verifier *Verifier
		token    string
		expected Expected
	}{
		{name: "wrong signature", verifier: verifier, token: wrongSignature, expected: validExpected()},
		{name: "malformed", verifier: verifier, token: "header.payload.signature", expected: validExpected()},
		{name: "empty", verifier: verifier, token: "", expected: validExpected()},
		{name: "padded", verifier: verifier, token: " " + wrongSignature + " ", expected: validExpected()},
		{name: "invalid expected context", verifier: verifier, token: wrongSignature, expected: Expected{}},
		{name: "nil verifier", verifier: nil, token: wrongSignature, expected: validExpected()},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := tt.verifier.Verify(tt.token, tt.expected)
			if !errors.Is(err, ErrInvalidToken) {
				t.Fatalf("Verify() error = %v, want ErrInvalidToken", err)
			}
			if tt.token != "" && strings.Contains(err.Error(), tt.token) {
				t.Fatalf("Verify() error leaked token: %v", err)
			}
			if err.Error() != ErrInvalidToken.Error() {
				t.Fatalf("Verify() error = %q, want stable public error", err)
			}
		})
	}
}

func newTestVerifier(t *testing.T) *Verifier {
	t.Helper()
	verifier, err := NewVerifier(validConfig())
	if err != nil {
		t.Fatalf("NewVerifier() error = %v", err)
	}
	return verifier
}

func validConfig() Config {
	return Config{
		HMACKey:     append([]byte(nil), testKey...),
		Issuer:      testIssuer,
		Audience:    testAudience,
		ClockSkew:   30 * time.Second,
		MaxLifetime: 5 * time.Minute,
		Now:         func() time.Time { return testNow },
	}
}

func validExpected() Expected {
	return Expected{Subject: testUserID, UserID: testUserID, SessionID: testSession, InstallID: testInstall}
}

func validClaims() Claims {
	return Claims{
		UserID:    testUserID,
		SessionID: testSession,
		InstallID: testInstall,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    testIssuer,
			Subject:   testUserID,
			Audience:  jwt.ClaimStrings{testAudience},
			IssuedAt:  jwt.NewNumericDate(testNow),
			ExpiresAt: jwt.NewNumericDate(testNow.Add(5 * time.Minute)),
		},
	}
}

func signToken(t *testing.T, claims Claims, method jwt.SigningMethod, headers map[string]any) string {
	t.Helper()
	return signTokenWithKey(t, claims, method, headers, testKey)
}

func signTokenWithKey(t *testing.T, claims Claims, method jwt.SigningMethod, headers map[string]any, key []byte) string {
	t.Helper()
	token := jwt.NewWithClaims(method, claims)
	for name, value := range headers {
		token.Header[name] = value
	}
	signed, err := token.SignedString(key)
	if err != nil {
		t.Fatalf("SignedString() error = %v", err)
	}
	return signed
}

func assertInvalidToken(t *testing.T, verifier *Verifier, token string, expected Expected) {
	t.Helper()
	_, err := verifier.Verify(token, expected)
	if !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("Verify() error = %v, want ErrInvalidToken", err)
	}
	if strings.Contains(err.Error(), token) {
		t.Fatalf("Verify() error leaked token")
	}
}

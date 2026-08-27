package auth

import (
	"bytes"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

func testIssuer() TokenIssuer {
	return TokenIssuer{
		Issuer:          "cloud-api",
		Audience:        "dngmeng-clients",
		SessionAudience: "translator-agent",
		AccessSecret:    bytes.Repeat([]byte("a"), MinimumSecretBytes),
		SessionSecret:   bytes.Repeat([]byte("s"), MinimumSecretBytes),
	}
}

func TestAccessTokenRoundTrip(t *testing.T) {
	issuer := testIssuer()
	now := time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)
	userID := uuid.New()

	token, err := issuer.AccessToken(userID, string(domain.RoleAdmin), 15*time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	claims, err := issuer.ParseAccessAt(token, now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if claims.Subject != userID.String() || claims.Role != domain.RoleAdmin || claims.Scope != ScopeAPI || claims.ID == "" {
		t.Fatalf("unexpected claims: %+v", claims)
	}
	if !claims.ExpiresAt.Time.Equal(now.Add(15 * time.Minute)) {
		t.Fatalf("expires at %s", claims.ExpiresAt.Time)
	}
	if _, err := issuer.ParseTranslationAt(token, now.Add(time.Minute)); err == nil {
		t.Fatal("access token accepted as translation token")
	}
	if _, err := issuer.ParseAccessAt(token, now.Add(16*time.Minute)); err == nil {
		t.Fatal("expired access token accepted")
	}
}

func TestTranslationTokenRoundTripAndScopeSeparation(t *testing.T) {
	issuer := testIssuer()
	now := time.Date(2026, 2, 3, 4, 5, 6, 0, time.UTC)
	sessionID, entitlementID, userID, jti := uuid.New(), uuid.New(), uuid.New(), uuid.New()

	token, err := issuer.TranslationTokenForInstall(sessionID, entitlementID, userID, jti, "test-install", 5*time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	claims, err := issuer.ParseTranslationAt(token, now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if claims.SessionID != sessionID.String() || claims.EntitlementID != entitlementID.String() || claims.Subject != userID.String() || claims.ID != jti.String() || claims.Scope != ScopeTranslation {
		t.Fatalf("unexpected claims: %+v", claims)
	}
	if _, err := issuer.ParseAccessAt(token, now.Add(time.Minute)); err == nil {
		t.Fatal("translation token accepted as access token")
	}
}

func TestTranslationTokenUsesDistinctConfiguredAudience(t *testing.T) {
	issuer := testIssuer()
	issuer.SessionAudience = "translator-agent"
	now := time.Date(2026, 2, 4, 5, 6, 7, 0, time.UTC)
	token, err := issuer.TranslationTokenForInstall(uuid.New(), uuid.New(), uuid.New(), uuid.New(), "test-install", time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	claims, err := issuer.ParseTranslationAt(token, now.Add(time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if len(claims.Audience) != 1 || claims.Audience[0] != issuer.SessionAudience || claims.Scope != ScopeTranslation {
		t.Fatalf("unexpected translation audience or scope: %+v", claims)
	}

	wrongAudience := issuer
	wrongAudience.SessionAudience = "other-translator"
	if _, err := wrongAudience.ParseTranslationAt(token, now.Add(time.Second)); err == nil {
		t.Fatal("translation token accepted for a different audience")
	}
	if _, err := issuer.ParseAccessAt(token, now.Add(time.Second)); err == nil {
		t.Fatal("translation audience token accepted as access token")
	}
}

func TestTokenIssuerRejectsInvalidArgumentsAndClaims(t *testing.T) {
	issuer := testIssuer()
	now := time.Date(2026, 3, 4, 5, 6, 7, 0, time.UTC)

	if _, err := issuer.AccessToken(uuid.Nil, string(domain.RoleUser), time.Minute, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("nil user error = %v", err)
	}
	if _, err := issuer.AccessToken(uuid.New(), "owner", time.Minute, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("invalid role error = %v", err)
	}
	shortSecret := issuer
	shortSecret.AccessSecret = []byte("short")
	if _, err := shortSecret.AccessToken(uuid.New(), string(domain.RoleUser), time.Minute, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("short secret error = %v", err)
	}
	reusedSecret := issuer
	reusedSecret.SessionSecret = append([]byte(nil), reusedSecret.AccessSecret...)
	if _, err := reusedSecret.AccessToken(uuid.New(), string(domain.RoleUser), time.Minute, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("reused secret error = %v", err)
	}
	reusedAudience := issuer
	reusedAudience.SessionAudience = issuer.Audience
	if _, err := reusedAudience.TranslationToken(uuid.New(), uuid.New(), uuid.New(), uuid.New(), time.Minute, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("reused audience error = %v", err)
	}
	whitespaceAudience := issuer
	whitespaceAudience.SessionAudience = " translator-agent "
	if _, err := whitespaceAudience.TranslationToken(uuid.New(), uuid.New(), uuid.New(), uuid.New(), time.Minute, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("whitespace audience error = %v", err)
	}

	claims := Claims{
		Role:  domain.RoleUser,
		Scope: ScopeAPI,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    issuer.Issuer,
			Subject:   uuid.Nil.String(),
			Audience:  jwt.ClaimStrings{issuer.Audience},
			ExpiresAt: jwt.NewNumericDate(now.Add(time.Minute)),
			IssuedAt:  jwt.NewNumericDate(now),
			NotBefore: jwt.NewNumericDate(now),
			ID:        uuid.NewString(),
		},
	}
	malformed, err := sign(issuer.AccessSecret, claims)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := issuer.ParseAccessAt(malformed, now.Add(time.Second)); err == nil {
		t.Fatal("nil UUID subject accepted")
	}

	claims.Subject = strings.ToUpper(uuid.NewString())
	malformed, err = sign(issuer.AccessSecret, claims)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := issuer.ParseAccessAt(malformed, now.Add(time.Second)); err == nil {
		t.Fatal("non-canonical UUID accepted")
	}

	claims.Subject = uuid.NewString()
	claims.NotBefore = nil
	malformed, err = sign(issuer.AccessSecret, claims)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := issuer.ParseAccessAt(malformed, now.Add(time.Second)); err == nil {
		t.Fatal("missing not-before claim accepted")
	}
}

func TestSecretAndRedemptionCodeHelpers(t *testing.T) {
	plain, hash, err := RandomSecret(MinimumSecretBytes)
	if err != nil {
		t.Fatal(err)
	}
	if len(hash) != 32 || !SecretHashEqual(hash, HashSecret(plain)) || SecretHashEqual(hash, HashSecret("different")) {
		t.Fatal("secret hash comparison failed")
	}
	if _, _, err := RandomSecret(MinimumSecretBytes - 1); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("small secret error = %v", err)
	}

	code, codeHash, err := RandomCode()
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := domain.ParseRedemptionCode(strings.ToLower(code))
	if err != nil {
		t.Fatal(err)
	}
	canonicalHash, err := HashRedemptionCode("  " + strings.ToLower(code) + "  ")
	if err != nil {
		t.Fatal(err)
	}
	if parsed.String() != code || !SecretHashEqual(codeHash, canonicalHash) {
		t.Fatalf("redemption code is not canonical: %q", code)
	}
	for _, r := range strings.ReplaceAll(code, "-", "") {
		if !(r >= 'A' && r <= 'Z') && !(r >= '2' && r <= '7') {
			t.Fatalf("unexpected code character %q in %q", r, code)
		}
	}
}

func TestCanonicalRedemptionCodeNormalizesOnlyCaseAndSurroundingWhitespace(t *testing.T) {
	const canonical = "ABCDE2-345672-ABCDE2-345672"
	for _, input := range []string{
		canonical,
		strings.ToLower(canonical),
		" \t" + strings.ToLower(canonical) + "\r\n",
	} {
		actual, err := CanonicalRedemptionCode(input)
		if err != nil {
			t.Fatalf("canonicalize %q: %v", input, err)
		}
		if actual != canonical {
			t.Fatalf("canonicalize %q = %q, want %q", input, actual, canonical)
		}
	}

	for _, input := range []string{
		"ABCDE2 345672 ABCDE2 345672",
		"ABCDE2-345672-ABCDE2-345670",
		"ＡBCDE2-345672-ABCDE2-345672",
		"ABCDſ2-345672-ABCDE2-345672",
	} {
		if _, err := CanonicalRedemptionCode(input); !errors.Is(err, domain.ErrInvalid) {
			t.Fatalf("invalid code %q error = %v", input, err)
		}
	}
}

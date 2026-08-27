package auth

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

func TestTokenPurposeIsolationRejectsCrossPurposeClaims(t *testing.T) {
	issuer := testIssuer()
	now := time.Date(2026, 9, 1, 2, 3, 4, 0, time.UTC)
	userID := uuid.New()

	accessClaims := Claims{
		Role:          domain.RoleUser,
		Scope:         ScopeTranslation,
		SessionID:     uuid.NewString(),
		EntitlementID: uuid.NewString(),
		RegisteredClaims: registeredClaims(
			issuer.Issuer,
			issuer.Audience,
			userID,
			uuid.New(),
			time.Minute,
			now,
		),
	}
	accessSignedAsTranslation, err := sign(issuer.AccessSecret, accessClaims)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := issuer.ParseAccessAt(accessSignedAsTranslation, now.Add(time.Second)); err == nil {
		t.Fatal("access key accepted translation-scoped claims")
	}
	if _, err := issuer.ParseTranslationAt(accessSignedAsTranslation, now.Add(time.Second)); err == nil {
		t.Fatal("translation parser accepted a token signed with the access key")
	}

	translationClaims := accessClaims
	translationClaims.Scope = ScopeAPI
	translationClaims.SessionID = ""
	translationClaims.EntitlementID = ""
	translationSignedAsAccess, err := sign(issuer.SessionSecret, translationClaims)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := issuer.ParseTranslationAt(translationSignedAsAccess, now.Add(time.Second)); err == nil {
		t.Fatal("session key accepted access-scoped claims")
	}
	if _, err := issuer.ParseAccessAt(translationSignedAsAccess, now.Add(time.Second)); err == nil {
		t.Fatal("access parser accepted a token signed with the session key")
	}
}

func TestAccessTokenRejectsIdentityAndLifetimeClaimViolations(t *testing.T) {
	issuer := testIssuer()
	now := time.Date(2026, 9, 2, 3, 4, 5, 0, time.UTC)
	base := Claims{
		Role:  domain.RoleUser,
		Scope: ScopeAPI,
		RegisteredClaims: registeredClaims(
			issuer.Issuer,
			issuer.Audience,
			uuid.New(),
			uuid.New(),
			5*time.Minute,
			now,
		),
	}

	tests := []struct {
		name   string
		mutate func(*Claims)
	}{
		{name: "wrong issuer", mutate: func(claims *Claims) { claims.Issuer = "other-issuer" }},
		{name: "missing issuer", mutate: func(claims *Claims) { claims.Issuer = "" }},
		{name: "wrong audience", mutate: func(claims *Claims) { claims.Audience = jwt.ClaimStrings{"other-audience"} }},
		{name: "multiple audiences", mutate: func(claims *Claims) { claims.Audience = jwt.ClaimStrings{issuer.Audience, "other-audience"} }},
		{name: "missing subject", mutate: func(claims *Claims) { claims.Subject = "" }},
		{name: "malformed subject", mutate: func(claims *Claims) { claims.Subject = "not-a-uuid" }},
		{name: "missing token id", mutate: func(claims *Claims) { claims.ID = "" }},
		{name: "missing expiry", mutate: func(claims *Claims) { claims.ExpiresAt = nil }},
		{name: "expiry equals issued at", mutate: func(claims *Claims) { claims.ExpiresAt = claims.IssuedAt }},
		{name: "expiry before issued at", mutate: func(claims *Claims) { claims.ExpiresAt = jwt.NewNumericDate(now.Add(-time.Second)) }},
		{name: "missing issued at", mutate: func(claims *Claims) { claims.IssuedAt = nil }},
		{name: "future issued at", mutate: func(claims *Claims) { claims.IssuedAt = jwt.NewNumericDate(now.Add(time.Minute)) }},
		{name: "missing not before", mutate: func(claims *Claims) { claims.NotBefore = nil }},
		{name: "future not before", mutate: func(claims *Claims) { claims.NotBefore = jwt.NewNumericDate(now.Add(time.Minute)) }},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			claims := cloneClaims(base)
			test.mutate(&claims)
			token, err := sign(issuer.AccessSecret, claims)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := issuer.ParseAccessAt(token, now.Add(time.Second)); err == nil {
				t.Fatal("invalid claims accepted")
			}
		})
	}
}

func TestTranslationTokenRejectsPurposeSpecificClaimViolations(t *testing.T) {
	issuer := testIssuer()
	now := time.Date(2026, 9, 3, 4, 5, 6, 0, time.UTC)
	base := Claims{
		Scope:         ScopeTranslation,
		SessionID:     uuid.NewString(),
		EntitlementID: uuid.NewString(),
		RegisteredClaims: registeredClaims(
			issuer.Issuer,
			issuer.Audience,
			uuid.New(),
			uuid.New(),
			5*time.Minute,
			now,
		),
	}

	tests := []struct {
		name   string
		mutate func(*Claims)
	}{
		{name: "wrong scope", mutate: func(claims *Claims) { claims.Scope = ScopeAPI }},
		{name: "missing scope", mutate: func(claims *Claims) { claims.Scope = "" }},
		{name: "wrong audience", mutate: func(claims *Claims) { claims.Audience = jwt.ClaimStrings{"other-audience"} }},
		{name: "multiple audiences", mutate: func(claims *Claims) { claims.Audience = jwt.ClaimStrings{issuer.Audience, "other-audience"} }},
		{name: "access role present", mutate: func(claims *Claims) { claims.Role = domain.RoleUser }},
		{name: "missing session id", mutate: func(claims *Claims) { claims.SessionID = "" }},
		{name: "nil session id", mutate: func(claims *Claims) { claims.SessionID = uuid.Nil.String() }},
		{name: "noncanonical session id", mutate: func(claims *Claims) { claims.SessionID = strings.ToUpper(uuid.NewString()) }},
		{name: "missing entitlement id", mutate: func(claims *Claims) { claims.EntitlementID = "" }},
		{name: "malformed entitlement id", mutate: func(claims *Claims) { claims.EntitlementID = "not-a-uuid" }},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			claims := cloneClaims(base)
			test.mutate(&claims)
			token, err := sign(issuer.SessionSecret, claims)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := issuer.ParseTranslationAt(token, now.Add(time.Second)); err == nil {
				t.Fatal("invalid translation claims accepted")
			}
		})
	}
}

func TestRedemptionCodeHashCanonicalizationAndRejection(t *testing.T) {
	code, generatedHash, err := RandomCode()
	if err != nil {
		t.Fatal(err)
	}

	variants := []string{code, strings.ToLower(code), "\t" + strings.ToLower(code) + "\n"}
	for _, variant := range variants {
		hash, err := HashRedemptionCode(variant)
		if err != nil {
			t.Fatalf("valid variant %q: %v", variant, err)
		}
		if !SecretHashEqual(generatedHash, hash) {
			t.Fatalf("variant %q produced a different hash", variant)
		}
	}

	invalid := []string{
		strings.Replace(code, "-", "_", 1),
		"0" + code[1:],
		"ABCDſ2-345672-ABCDE2-345672",
		"ABCDE2-345672-ABCDE2-34567",
	}
	for _, value := range invalid {
		if _, err := HashRedemptionCode(value); !errors.Is(err, domain.ErrInvalid) {
			t.Fatalf("invalid code %q error = %v", value, err)
		}
	}
}

func TestRefreshReplayRevokesEveryGenerationInFamily(t *testing.T) {
	ctx := context.Background()
	store := newRefreshStoreStub()
	manager := RefreshManager{Store: store}
	now := time.Date(2026, 9, 4, 5, 6, 7, 0, time.UTC)

	first, err := manager.Issue(ctx, uuid.New(), 24*time.Hour, now)
	if err != nil {
		t.Fatal(err)
	}
	second, err := manager.Rotate(ctx, first.Plaintext, 24*time.Hour, now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	third, err := manager.Rotate(ctx, second.Plaintext, 24*time.Hour, now.Add(2*time.Minute))
	if err != nil {
		t.Fatal(err)
	}

	replayAt := now.Add(3 * time.Minute)
	replayed, err := manager.Rotate(ctx, first.Plaintext, 24*time.Hour, replayAt)
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("first-generation replay error = %v", err)
	}
	assertEmptyRefreshIssue(t, replayed)
	for generation, plaintext := range map[string]string{
		"first":  first.Plaintext,
		"second": second.Plaintext,
		"third":  third.Plaintext,
	} {
		token := store.tokens[string(HashSecret(plaintext))]
		if token.RevokedAt == nil || token.ActiveAt(replayAt) {
			t.Fatalf("%s generation remained active after replay: %+v", generation, token)
		}
	}
	latest := store.tokens[string(HashSecret(third.Plaintext))]
	if !latest.RevokedAt.Equal(replayAt) {
		t.Fatalf("latest generation was not revoked by replay: %+v", latest)
	}
	if _, err := manager.Rotate(ctx, third.Plaintext, 24*time.Hour, replayAt.Add(time.Minute)); !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("latest generation remained usable after family revocation: %v", err)
	}
}

func cloneClaims(claims Claims) Claims {
	cloned := claims
	cloned.Audience = append(jwt.ClaimStrings(nil), claims.Audience...)
	return cloned
}

package contracttest

import (
	"bytes"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

// This test pins the public token wire contract consumed by main Agent's
// internal/sessionauth verifier. The Agent package is intentionally internal
// and cannot be imported by this separate Go module; decoding here is strict
// and checks every claim the verifier binds.
func TestTranslationSessionTokenMatchesMainAgentContract(t *testing.T) {
	now := time.Date(2026, time.August, 24, 1, 2, 3, 0, time.UTC)
	key := bytes.Repeat([]byte("s"), auth.MinimumSecretBytes)
	issuer := auth.TokenIssuer{Issuer: "cloud-api", Audience: "cloud-api-clients", SessionAudience: "translator-agent", AccessSecret: bytes.Repeat([]byte("a"), auth.MinimumSecretBytes), SessionSecret: key}
	user := uuid.MustParse("123e4567-e89b-12d3-a456-426614174010")
	session := uuid.MustParse("123e4567-e89b-12d3-a456-426614174011")
	entitlement := uuid.MustParse("123e4567-e89b-12d3-a456-426614174012")
	jti := uuid.MustParse("123e4567-e89b-12d3-a456-426614174013")
	token, err := issuer.TranslationTokenForInstall(session, entitlement, user, jti, "install-789", 5*time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	claims := struct {
		UserID    string `json:"user_id"`
		SessionID string `json:"session_id"`
		InstallID     string     `json:"install_id"`
		EntitlementID string     `json:"entitlement_id"`
		Scope         auth.Scope `json:"scope"`
		jwt.RegisteredClaims
	}{}
	parsed, err := jwt.ParseWithClaims(token, &claims, func(tok *jwt.Token) (any, error) {
		if tok.Method != jwt.SigningMethodHS256 || tok.Header["alg"] != "HS256" || tok.Header["typ"] != "translation_session" {
			t.Fatalf("header=%v", tok.Header)
		}
		return key, nil
	}, jwt.WithValidMethods([]string{"HS256"}), jwt.WithIssuer("cloud-api"), jwt.WithAudience("translator-agent"), jwt.WithSubject(user.String()), jwt.WithStrictDecoding(), jwt.WithTimeFunc(func() time.Time { return now.Add(time.Minute) }))
	if err != nil || parsed == nil || !parsed.Valid {
		t.Fatalf("parse token: %v", err)
	}
	if claims.UserID != user.String() || claims.SessionID != session.String() || claims.InstallID != "install-789" || claims.EntitlementID != entitlement.String() || claims.Scope != auth.ScopeTranslation || claims.Subject != claims.UserID || claims.ID != jti.String() {
		t.Fatalf("identity claims=%+v", claims)
	}
	if len(claims.Audience) != 1 || claims.Audience[0] != "translator-agent" || claims.IssuedAt == nil || claims.ExpiresAt == nil || claims.ExpiresAt.Time.Sub(claims.IssuedAt.Time) > 5*time.Minute {
		t.Fatalf("registered claims=%+v", claims.RegisteredClaims)
	}
}

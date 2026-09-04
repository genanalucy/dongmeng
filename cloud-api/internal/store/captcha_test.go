package store

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
)

// This compile-time contract protects the Store API used by the captcha
// registration flow. PostgreSQL behavior is exercised in integration.
func TestRegistrationCaptchaStoreContract(t *testing.T) {
	var captchaStore interface {
		ChargeCaptchaIssueWindow(context.Context, []byte, time.Time) error
		CreateRegistrationCaptcha(context.Context, domain.CreateRegistrationCaptchaParams) (domain.RegistrationCaptcha, error)
		ChargeCaptchaRegisterWindow(context.Context, []byte, time.Time) error
		ReserveRegistrationCaptcha(context.Context, domain.ReserveRegistrationCaptchaParams) error
		RegisterWithCaptcha(context.Context, domain.RegisterWithCaptchaParams) (domain.User, domain.Entitlement, error)
	} = (*Postgres)(nil)
	if captchaStore == nil {
		t.Fatal("Postgres must implement the captcha registration store contract")
	}
}

func TestRegistrationCaptchaMigrationPersistsOnlyHashedMaterial(t *testing.T) {
	up, err := os.ReadFile("../../../migrations/000008_registration_captchas.up.sql")
	if err != nil {
		t.Fatalf("read captcha migration: %v", err)
	}
	schema := string(up)
	for _, fragment := range []string{
		"CREATE TABLE registration_captchas",
		"answer_hash bytea NOT NULL CHECK (octet_length(answer_hash) = 32)",
		"answer_salt bytea NOT NULL CHECK (octet_length(answer_salt) BETWEEN 16 AND 64)",
		"CHECK (attempt_count BETWEEN 0 AND 5)",
		"CREATE INDEX registration_captchas_expires_at_idx ON registration_captchas (expires_at, id)",
		"CREATE TABLE captcha_rate_limits",
		"CHECK (key_type IN ('issue', 'register'))",
		"PRIMARY KEY (key_type, key_hash)",
		"CREATE INDEX captcha_rate_limits_expiry_idx",
	} {
		if !strings.Contains(schema, fragment) {
			t.Errorf("captcha migration missing %q", fragment)
		}
	}
	for _, plaintext := range []string{"answer text NOT NULL", "challenge text NOT NULL", "svg text", "captcha_answer", "password text"} {
		if strings.Contains(schema, plaintext) {
			t.Errorf("captcha migration must not persist %q", plaintext)
		}
	}
	if strings.Contains(schema, "email_verification_rate_limits") {
		t.Error("captcha rate limits must not share the legacy email verification buckets")
	}

	down, err := os.ReadFile("../../../migrations/000008_registration_captchas.down.sql")
	if err != nil {
		t.Fatalf("read captcha rollback: %v", err)
	}
	// Rollback must be idempotent and follow dependency order, matching the
	// repository's established DROP TABLE IF EXISTS pattern.
	rollback := string(down)
	for _, fragment := range []string{"DROP TABLE IF EXISTS captcha_rate_limits", "DROP TABLE IF EXISTS registration_captchas"} {
		if !strings.Contains(rollback, fragment) {
			t.Errorf("captcha rollback missing %q", fragment)
		}
	}
	if strings.Contains(rollback, "DROP TABLE captcha_rate_limits;") || strings.Contains(rollback, "DROP TABLE registration_captchas;") {
		t.Error("captcha rollback must drop with IF EXISTS")
	}
	if strings.Index(rollback, "captcha_rate_limits") > strings.Index(rollback, "registration_captchas") {
		t.Error("captcha rollback must drop the rate-limit table before the captcha table")
	}
}

func TestCaptchaFailuresRemainGeneric(t *testing.T) {
	if domain.ErrCaptchaFailed.Error() != "captcha failed" {
		t.Fatal("captcha failures must use a generic domain error")
	}
	if domain.ErrRateLimited.Error() != "rate limited" {
		t.Fatal("rate limiting must use a generic domain error")
	}
}

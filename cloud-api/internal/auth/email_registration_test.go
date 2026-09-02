package auth

import (
	"errors"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
)

func TestParseRegistrationVerificationCode(t *testing.T) {
	for _, invalid := range []string{"12345", "1234567", "12345a", "１２３４５６", "12345\u00a0"} {
		_, err := ParseRegistrationVerificationCode(invalid)
		if !errors.Is(err, domain.ErrInvalid) {
			t.Errorf("ParseRegistrationVerificationCode(%q) error = %v, want domain.ErrInvalid", invalid, err)
		}
	}

	got, err := ParseRegistrationVerificationCode("012345")
	if err != nil {
		t.Fatalf("ParseRegistrationVerificationCode(012345) error = %v", err)
	}
	if got != "012345" {
		t.Errorf("ParseRegistrationVerificationCode(012345) = %q, want %q", got, "012345")
	}
}

func TestParseRegistrationVerificationRequestUsesExistingCredentialRules(t *testing.T) {
	input, err := domain.ParseRegistrationVerificationInput(" Example_User ", " USER@example.com ", "Password1")
	if err != nil {
		t.Fatalf("ParseRegistrationVerificationInput() error = %v", err)
	}
	if input.Username.String() != "example_user" || input.Email.String() != "user@example.com" || input.Password.String() != "Password1" {
		t.Errorf("ParseRegistrationVerificationInput() = %#v, want normalized username and email with accepted password", input)
	}

	for _, request := range [][3]string{
		{"bad-name", "user@example.com", "Password1"},
		{"example_user", "not-an-email", "Password1"},
		{"example_user", "user@example.com", "password1"},
	} {
		_, err := domain.ParseRegistrationVerificationInput(request[0], request[1], request[2])
		if !errors.Is(err, domain.ErrInvalid) {
			t.Errorf("ParseRegistrationVerificationInput(%q, %q, %q) error = %v, want domain.ErrInvalid", request[0], request[1], request[2], err)
		}
	}
}

func TestRegistrationVerificationCodeSecurityPrimitives(t *testing.T) {
	code, err := generateSixDigitCode()
	if err != nil {
		t.Fatalf("generateSixDigitCode() error = %v", err)
	}
	if _, err := ParseRegistrationVerificationCode(code); err != nil {
		t.Errorf("generateSixDigitCode() = %q, which is not a valid six-digit code: %v", code, err)
	}

	salt, err := generateRegistrationVerificationSalt()
	if err != nil {
		t.Fatalf("generateRegistrationVerificationSalt() error = %v", err)
	}
	hash := hashCode(salt, "012345")
	if !constantTimeCodeMatch(salt, hash, "012345") {
		t.Error("constantTimeCodeMatch() rejected the matching code")
	}
	if constantTimeCodeMatch(salt, hash, "012346") {
		t.Error("constantTimeCodeMatch() accepted a different code")
	}
}

func TestRegistrationVerificationExpiredAtExpiryBoundary(t *testing.T) {
	expiresAt := time.Date(2026, 9, 2, 12, 0, 0, 0, time.UTC)
	if registrationVerificationExpired(expiresAt, expiresAt.Add(-time.Nanosecond)) {
		t.Error("registration verification expired before expiry")
	}
	if !registrationVerificationExpired(expiresAt, expiresAt) {
		t.Error("registration verification did not expire at expiry")
	}
}

package domain

import (
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestParseCredentialsCanonicalizesEmail(t *testing.T) {
	input, err := ParseCredentials("  User@Example.COM ", "correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	if input.Email.String() != "user@example.com" || input.Password.String() != "correct horse battery" {
		t.Fatalf("unexpected credentials: %+v", input)
	}

	invalidEmails := []string{"", "display <user@example.com>", "a@@example.com", "user@example.com " + string([]byte{0xff})}
	for _, email := range invalidEmails {
		if _, err := ParseEmail(email); !errors.Is(err, ErrInvalid) {
			t.Fatalf("invalid email %q error = %v", email, err)
		}
	}
}

func TestParsePhoneCredentialsCanonicalizesPhoneAndEnforcesStrongPassword(t *testing.T) {
	input, err := ParsePhoneCredentials(" 13800138000 ", "Aa123456")
	if err != nil {
		t.Fatal(err)
	}
	if input.Phone.String() != "+8613800138000" || input.Password.String() != "Aa123456" {
		t.Fatalf("unexpected phone credentials: %+v", input)
	}

	canonical, err := ParsePhone("+8613800138000")
	if err != nil || canonical.String() != "+8613800138000" {
		t.Fatalf("canonical phone = %q, err = %v", canonical, err)
	}
	for _, value := range []string{"12800138000", "1380013800", "+8612800138000", "008613800138000", "+8613800138000x"} {
		if _, err := ParsePhone(value); !errors.Is(err, ErrInvalid) {
			t.Fatalf("invalid phone %q error = %v", value, err)
		}
	}
	for _, password := range []string{"aa123456", "AA123456", "Aaabcdef"} {
		if _, err := ParsePhoneCredentials("13800138000", password); !errors.Is(err, ErrInvalid) {
			t.Fatalf("weak password error = %v", err)
		}
	}
}

func TestParseUsernameCanonicalizesAndRestrictsCharacters(t *testing.T) {
	username, err := ParseUsername("  Alice_01 ")
	if err != nil || username.String() != "alice_01" {
		t.Fatalf("username = %q, err = %v", username, err)
	}
	for _, value := range []string{"ab", "abcdefghijklmnopqrstuvwxyzabcdefg", "alice-name", "alice name", "阿丽斯"} {
		if _, err := ParseUsername(value); !errors.Is(err, ErrInvalid) {
			t.Fatalf("invalid username %q error = %v", value, err)
		}
	}
}

func TestTrialAndRedemptionPeriods(t *testing.T) {
	start := time.Date(2026, 6, 1, 12, 0, 0, 0, time.FixedZone("UTC+8", 8*60*60))
	trialStart, trialEnd, err := TrialPeriod(start)
	if err != nil {
		t.Fatal(err)
	}
	if trialStart.Location() != time.UTC || trialEnd.Sub(trialStart) != 72*time.Hour {
		t.Fatalf("unexpected trial period: %s - %s", trialStart, trialEnd)
	}
	packageStart, packageEnd, err := RedemptionPeriod(start)
	if err != nil {
		t.Fatal(err)
	}
	if packageEnd.Sub(packageStart) != 365*24*time.Hour {
		t.Fatalf("unexpected package period: %s - %s", packageStart, packageEnd)
	}
	if _, _, err := TrialPeriod(time.Time{}); !errors.Is(err, ErrInvalid) {
		t.Fatalf("zero trial start error = %v", err)
	}
}

func TestEntitlementValidityAndHalfOpenActivity(t *testing.T) {
	start := time.Date(2026, 7, 1, 0, 0, 0, 0, time.UTC)
	entitlement, err := NewTrialEntitlement(uuid.New(), uuid.New(), start)
	if err != nil {
		t.Fatal(err)
	}
	if !entitlement.Valid() || !entitlement.ActiveAt(start) || !entitlement.ActiveAt(entitlement.ExpiresAt.Add(-time.Nanosecond)) || entitlement.ActiveAt(entitlement.ExpiresAt) {
		t.Fatalf("unexpected entitlement behavior: %+v", entitlement)
	}

	annual, err := NewRedemptionEntitlement(uuid.New(), entitlement.UserID, start)
	if err != nil {
		t.Fatal(err)
	}
	if !annual.Valid() || annual.ExpiresAt.Sub(annual.StartsAt) != RedemptionDuration {
		t.Fatalf("invalid annual entitlement: %+v", annual)
	}
	if _, err := NewTrialEntitlement(uuid.Nil, uuid.New(), start); !errors.Is(err, ErrInvalid) {
		t.Fatalf("nil entitlement id error = %v", err)
	}
}

func TestAdminHelpersFailClosed(t *testing.T) {
	if err := RequireAdmin(RoleAdmin); err != nil {
		t.Fatal(err)
	}
	for _, role := range []Role{RoleUser, "owner", ""} {
		if err := RequireAdmin(role); !errors.Is(err, ErrForbidden) {
			t.Fatalf("role %q error = %v", role, err)
		}
	}
	if !UserIsAdmin(User{Role: string(RoleAdmin)}) || UserIsAdmin(User{Role: "owner"}) {
		t.Fatal("user admin helper did not fail closed")
	}
}

func TestInputValidation(t *testing.T) {
	code, err := ParseRedemptionCode(" abcde2-345672-abcde2-345672 ")
	if err != nil || code.String() != "ABCDE2-345672-ABCDE2-345672" {
		t.Fatalf("code=%q err=%v", code, err)
	}
	for _, value := range []string{"ABCDE-234567-ABCDE2-345672", "ABCDſ2-345672-ABCDE2-345672"} {
		if _, err := ParseRedemptionCode(value); !errors.Is(err, ErrInvalid) {
			t.Fatalf("invalid code %q error = %v", value, err)
		}
	}
	if _, err := ParseCreateBatchInput(" annual ", 1000); err != nil {
		t.Fatal(err)
	}
	if _, err := ParseCreateBatchInput("annual", 1001); !errors.Is(err, ErrInvalid) {
		t.Fatalf("large batch error = %v", err)
	}
	if _, err := ParseRefreshToken(" short "); !errors.Is(err, ErrInvalid) {
		t.Fatalf("invalid refresh error = %v", err)
	}
}

func TestRefreshTokenState(t *testing.T) {
	now := time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC)
	token := RefreshToken{
		ID:        uuid.New(),
		UserID:    uuid.New(),
		FamilyID:  uuid.New(),
		TokenHash: make([]byte, 32),
		ExpiresAt: now.Add(time.Hour),
	}
	if !token.Valid() || !token.ActiveAt(now) || token.ActiveAt(token.ExpiresAt) {
		t.Fatalf("unexpected token state: %+v", token)
	}
	revokedAt := now
	token.RevokedAt = &revokedAt
	if token.ActiveAt(now) {
		t.Fatal("revoked refresh token is active")
	}
}

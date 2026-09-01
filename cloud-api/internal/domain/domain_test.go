package domain

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestUserSerializationNeverExposesPhone(t *testing.T) {
	user := User{ID: uuid.New(), Username: "alice_01", Phone: "+8613800138000", Email: "phone-internal@reserved.invalid", Role: string(RoleUser)}
	encoded, err := json.Marshal(user)
	if err != nil {
		t.Fatal(err)
	}
	for _, forbidden := range []string{`"phone"`, "+8613800138000"} {
		if strings.Contains(string(encoded), forbidden) {
			t.Fatalf("public user JSON contains forbidden identity field")
		}
	}
	if !strings.Contains(string(encoded), `"username":"alice_01"`) {
		t.Fatal("public user JSON lost username")
	}
}

func TestParseCredentialsCanonicalizesEmail(t *testing.T) {
	input, err := ParseCredentials("  User@Example.COM ", "correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	if input.Email.String() != "user@example.com" || input.Password.String() != "correct horse battery" {
		t.Fatal("credentials were not canonicalized")
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
		t.Fatal("valid phone credentials were rejected")
	}
	if input.Phone.String() != "+8613800138000" {
		t.Fatal("phone canonicalization mismatch")
	}
	if input.Password.String() != "Aa123456" {
		t.Fatal("password preservation mismatch")
	}

	for _, test := range []struct {
		name     string
		password string
	}{
		{name: "missing_uppercase", password: "aa123456"},
		{name: "missing_lowercase", password: "AA123456"},
		{name: "missing_digit", password: "Aaabcdef"},
	} {
		t.Run(test.name, func(t *testing.T) {
			if _, err := ParsePhoneCredentials("13800138000", test.password); !errors.Is(err, ErrInvalid) {
				t.Fatal("weak password was accepted")
			}
		})
	}
}

func TestParsePhoneBoundaries(t *testing.T) {
	for _, test := range []struct {
		name        string
		value       string
		canonical   string
		wantInvalid bool
	}{
		{name: "second_digit_three", value: "13000138000", canonical: "+8613000138000"},
		{name: "second_digit_nine", value: "+8613900138000", canonical: "+8613900138000"},
		{name: "empty", value: "", wantInvalid: true},
		{name: "second_digit_two", value: "12000138000", wantInvalid: true},
		{name: "second_digit_ten", value: "1:000138000", wantInvalid: true},
		{name: "bare_country_code", value: "8613800138000", wantInvalid: true},
		{name: "double_country_code", value: "+86+8613800138000", wantInvalid: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			phone, err := ParsePhone(test.value)
			if test.wantInvalid {
				if !errors.Is(err, ErrInvalid) {
					t.Fatal("invalid phone was accepted")
				}
				return
			}
			if err != nil {
				t.Fatal("valid phone was rejected")
			}
			if phone.String() != test.canonical {
				t.Fatal("phone canonicalization mismatch")
			}
		})
	}
}

func TestParseLoginIdentifierPrioritizesCanonicalPhoneThenEmailThenUsername(t *testing.T) {
	for _, test := range []struct {
		name, value, wantKind, wantValue string
	}{
		{name: "phone", value: " +8613800138000 ", wantKind: "phone", wantValue: "+8613800138000"},
		{name: "email", value: " User@Example.COM ", wantKind: "email", wantValue: "user@example.com"},
		{name: "username", value: " Alice_01 ", wantKind: "username", wantValue: "alice_01"},
	} {
		t.Run(test.name, func(t *testing.T) {
			identity, err := ParseLoginIdentifier(test.value)
			if err != nil {
				t.Fatal("valid identifier was rejected")
			}
			if identity.Kind.String() != test.wantKind || identity.Value != test.wantValue {
				t.Fatal("identifier was not classified and canonicalized")
			}
		})
	}
}

func TestParseUsernameBoundaries(t *testing.T) {
	for _, test := range []struct {
		name        string
		value       string
		canonical   string
		wantInvalid bool
	}{
		{name: "exact_minimum", value: "Ab1", canonical: "ab1"},
		{name: "exact_maximum", value: strings.Repeat("a", 32), canonical: strings.Repeat("a", 32)},
		{name: "all_uppercase", value: "ALICE_01", canonical: "alice_01"},
		{name: "pure_digits", value: "123456", wantInvalid: true},
		{name: "over_maximum", value: strings.Repeat("a", 33), wantInvalid: true},
		{name: "too_short", value: "ab", wantInvalid: true},
		{name: "hyphen", value: "alice-name", wantInvalid: true},
		{name: "space", value: "alice name", wantInvalid: true},
		{name: "non_ascii", value: "阿丽斯", wantInvalid: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			username, err := ParseUsername(test.value)
			if test.wantInvalid {
				if !errors.Is(err, ErrInvalid) {
					t.Fatal("invalid username was accepted")
				}
				return
			}
			if err != nil {
				t.Fatal("valid username was rejected")
			}
			if username.String() != test.canonical {
				t.Fatal("username canonicalization mismatch")
			}
		})
	}
}

func TestParsePhoneCredentialsPasswordBoundaries(t *testing.T) {
	for _, test := range []struct {
		name     string
		password string
	}{
		{name: "too_short", password: "Aa12345"},
		{name: "too_long", password: "Aa1" + strings.Repeat("a", MaxPasswordBytes-2)},
		{name: "invalid_utf8", password: string([]byte{'A', 'a', '1', 0xff, '2', '3', '4', '5'})},
	} {
		t.Run(test.name, func(t *testing.T) {
			if _, err := ParsePhoneCredentials("13800138000", test.password); !errors.Is(err, ErrInvalid) {
				t.Fatal("invalid phone credential password was accepted")
			}
		})
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

package auth

import (
	"context"
	"errors"
	"net/netip"
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
	input, err := domain.ParseRegistrationVerificationInput(" Example_User ", " USER@example.com ", "password1")
	if err != nil {
		t.Fatalf("ParseRegistrationVerificationInput() error = %v", err)
	}
	if input.Username.String() != "example_user" || input.Email.String() != "user@example.com" || input.Password.String() != "password1" {
		t.Errorf("ParseRegistrationVerificationInput() = %#v, want normalized username and email with accepted password", input)
	}

	for _, request := range [][3]string{
		{"bad-name", "user@example.com", "password1"},
		{"example_user", "not-an-email", "password1"},
		{"example_user", "user@example.com", "short"},
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
	pepper := []byte("test-only-pepper")
	hash, err := hashCode(pepper, salt, "012345")
	if err != nil {
		t.Fatalf("hashCode() error = %v", err)
	}
	if !constantTimeCodeMatch(pepper, salt, hash, "012345") {
		t.Error("constantTimeCodeMatch() rejected the matching code")
	}
	if constantTimeCodeMatch(pepper, salt, hash, "012346") {
		t.Error("constantTimeCodeMatch() accepted a different code")
	}
	if _, err := hashCode(nil, salt, "012345"); !errors.Is(err, domain.ErrInvalid) {
		t.Errorf("hashCode() error = %v, want domain.ErrInvalid for an empty pepper", err)
	}
}

type fakeRegistrationVerificationWriter struct {
	drafts []RegistrationVerificationDraft
	err    error
}

func (w *fakeRegistrationVerificationWriter) WriteRegistrationVerification(_ context.Context, draft RegistrationVerificationDraft) error {
	w.drafts = append(w.drafts, draft)
	return w.err
}

type fakeRegistrationCodeSender struct {
	sent []sentRegistrationCode
	err  error
}

type sentRegistrationCode struct {
	email     string
	code      string
	expiresAt time.Time
}

func (s *fakeRegistrationCodeSender) SendRegistrationCode(_ context.Context, email, code string, expiresAt time.Time) error {
	s.sent = append(s.sent, sentRegistrationCode{email: email, code: code, expiresAt: expiresAt})
	return s.err
}

func TestEmailRegistrationServiceRequestSendsCode(t *testing.T) {
	now := time.Date(2026, 9, 2, 12, 0, 0, 0, time.UTC)
	sender := &fakeRegistrationCodeSender{}
	writer := &fakeRegistrationVerificationWriter{}
	service := newTestEmailRegistrationService(t, now, sender)
	service.WriteVerification = writer.WriteRegistrationVerification

	result, err := service.RequestVerification(context.Background(), RegistrationVerificationRequest{
		Username: "example_user",
		Email:    "user@example.com",
		Password: "password1",
		ClientIP: netip.MustParseAddr("203.0.113.10"),
	})
	if err != nil {
		t.Fatalf("RequestVerification() error = %v", err)
	}
	if result.RetryAfterSeconds != int(RegistrationVerificationResendDelay.Seconds()) {
		t.Errorf("RequestVerification() retry after = %d, want %d", result.RetryAfterSeconds, int(RegistrationVerificationResendDelay.Seconds()))
	}
	if len(sender.sent) != 1 {
		t.Fatalf("sender calls = %d, want 1", len(sender.sent))
	}
	if len(writer.drafts) != 1 {
		t.Fatalf("writer calls = %d, want 1", len(writer.drafts))
	}
	if writer.drafts[0].Username != "example_user" || writer.drafts[0].Email != "user@example.com" || writer.drafts[0].PasswordHash != "hashed:password1" || !constantTimeCodeMatch([]byte("test-only-pepper"), writer.drafts[0].Salt, writer.drafts[0].CodeHash, "012345") {
		t.Errorf("stored draft = %#v, want normalized credentials and a peppered code digest", writer.drafts[0])
	}
	if sender.sent[0].email != "user@example.com" || sender.sent[0].code != "012345" || !sender.sent[0].expiresAt.Equal(now.Add(RegistrationVerificationCodeTTL)) {
		t.Errorf("sender input = %#v, want normalized email, generated code, and ten-minute expiry", sender.sent[0])
	}
}

func TestEmailRegistrationServiceRequestReturnsSenderFailure(t *testing.T) {
	sender := &fakeRegistrationCodeSender{err: errors.New("delivery unavailable")}
	writer := &fakeRegistrationVerificationWriter{}
	service := newTestEmailRegistrationService(t, time.Now().UTC(), sender)
	service.WriteVerification = writer.WriteRegistrationVerification

	_, err := service.RequestVerification(context.Background(), RegistrationVerificationRequest{
		Username: "example_user", Email: "user@example.com", Password: "password1", ClientIP: netip.MustParseAddr("203.0.113.10"),
	})
	if err == nil || errors.Is(err, domain.ErrInvalid) {
		t.Errorf("RequestVerification() error = %v, want sender failure", err)
	}
	if len(writer.drafts) != 0 {
		t.Errorf("writer calls = %d, want 0 after sender failure", len(writer.drafts))
	}
}

func TestEmailRegistrationServiceConfirmMapsVerificationFailuresIndistinguishably(t *testing.T) {
	now := time.Date(2026, 9, 2, 12, 0, 0, 0, time.UTC)
	cases := []struct {
		name   string
		lookup func(context.Context, string) (RegistrationVerificationRecord, error)
	}{
		{name: "missing", lookup: func(context.Context, string) (RegistrationVerificationRecord, error) {
			return RegistrationVerificationRecord{}, domain.ErrNotFound
		}},
		{name: "expired", lookup: func(context.Context, string) (RegistrationVerificationRecord, error) {
			return RegistrationVerificationRecord{ExpiresAt: now}, nil
		}},
		{name: "wrong code", lookup: func(context.Context, string) (RegistrationVerificationRecord, error) {
			return RegistrationVerificationRecord{Salt: []byte("salt"), CodeHash: []byte("different"), ExpiresAt: now.Add(time.Minute)}, nil
		}},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			service := newTestEmailRegistrationService(t, now, &fakeRegistrationCodeSender{})
			service.LookupVerification = tc.lookup
			err := service.ConfirmVerification(context.Background(), RegistrationVerificationConfirmation{Email: "user@example.com", Code: "012345"})
			if !errors.Is(err, ErrRegistrationVerificationFailed) {
				t.Errorf("ConfirmVerification() error = %v, want ErrRegistrationVerificationFailed", err)
			}
		})
	}
}

func newTestEmailRegistrationService(t *testing.T, now time.Time, sender RegistrationCodeSender) EmailRegistrationService {
	t.Helper()
	service, err := NewEmailRegistrationService(EmailRegistrationService{
		HashPasswordValue: func(password string) (string, error) { return "hashed:" + password, nil },
		GenerateCode:      func() (string, error) { return "012345", nil },
		GenerateSalt:      func() ([]byte, error) { return []byte("salt"), nil },
		CodePepper:        []byte("test-only-pepper"),
		Sender:            sender,
		WriteVerification: func(context.Context, RegistrationVerificationDraft) error { return nil },
		Clock:             func() time.Time { return now },
	})
	if err != nil {
		t.Fatalf("NewEmailRegistrationService() error = %v", err)
	}
	return service
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

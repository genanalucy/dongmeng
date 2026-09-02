package mail

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

type fakeTransport struct {
	from    string
	to      string
	message []byte
	err     error
}

func (f *fakeTransport) Send(_ context.Context, _ SMTPConfig, from, to string, message []byte) error {
	f.from = from
	f.to = to
	f.message = append([]byte(nil), message...)
	return f.err
}

func TestSMTPRegistrationCodeSenderSendsFixedPlainTextVerificationMessage(t *testing.T) {
	transport := &fakeTransport{}
	sender, err := NewSMTPRegistrationCodeSender(validSMTPConfig(), transport)
	if err != nil {
		t.Fatalf("NewSMTPRegistrationCodeSender() error = %v", err)
	}

	err = sender.SendRegistrationCode(context.Background(), "user@example.com", "012345", time.Now().Add(10*time.Minute))
	if err != nil {
		t.Fatalf("SendRegistrationCode() error = %v", err)
	}
	message := string(transport.message)
	if transport.from != "no-reply@verba.example" || transport.to != "user@example.com" {
		t.Fatalf("transport envelope = %q -> %q", transport.from, transport.to)
	}
	for _, expected := range []string{"From: no-reply@verba.example", "To: user@example.com", "Verba", "012345", "10 分钟内有效"} {
		if !strings.Contains(message, expected) {
			t.Fatalf("message does not contain %q: %q", expected, message)
		}
	}
	for _, forbidden := range []string{"password", "token", "127.0.0.1", "localhost"} {
		if strings.Contains(strings.ToLower(message), forbidden) {
			t.Fatalf("message contains forbidden content %q: %q", forbidden, message)
		}
	}
}

func TestSMTPRegistrationCodeSenderRejectsUnsafeInput(t *testing.T) {
	tests := []struct {
		name   string
		config SMTPConfig
		email  string
		code   string
	}{
		{name: "non-loopback host", config: SMTPConfig{Host: "mail.example.com", Port: 25, From: "no-reply@verba.example", ConnectTimeout: time.Second, SendTimeout: time.Second}},
		{name: "invalid sender", config: SMTPConfig{Host: "127.0.0.1", Port: 25, From: "invalid", ConnectTimeout: time.Second, SendTimeout: time.Second}},
		{name: "zero connect timeout", config: SMTPConfig{Host: "127.0.0.1", Port: 25, From: "no-reply@verba.example", SendTimeout: time.Second}},
		{name: "zero send timeout", config: SMTPConfig{Host: "127.0.0.1", Port: 25, From: "no-reply@verba.example", ConnectTimeout: time.Second}},
		{name: "invalid recipient", config: validSMTPConfig(), email: "invalid", code: "012345"},
		{name: "invalid code", config: validSMTPConfig(), email: "user@example.com", code: "12345a"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			sender, err := NewSMTPRegistrationCodeSender(test.config, &fakeTransport{})
			if test.email == "" {
				if err == nil {
					t.Fatal("NewSMTPRegistrationCodeSender() error = nil")
				}
				return
			}
			if err != nil {
				t.Fatalf("NewSMTPRegistrationCodeSender() error = %v", err)
			}
			if err := sender.SendRegistrationCode(context.Background(), test.email, test.code, time.Now().Add(time.Minute)); err == nil {
				t.Fatal("SendRegistrationCode() error = nil")
			}
		})
	}
}

func TestSMTPRegistrationCodeSenderReturnsTransportFailure(t *testing.T) {
	want := errors.New("smtp submission unavailable")
	sender, err := NewSMTPRegistrationCodeSender(validSMTPConfig(), &fakeTransport{err: want})
	if err != nil {
		t.Fatalf("NewSMTPRegistrationCodeSender() error = %v", err)
	}

	err = sender.SendRegistrationCode(context.Background(), "user@example.com", "012345", time.Now().Add(time.Minute))
	if !errors.Is(err, want) {
		t.Fatalf("SendRegistrationCode() error = %v, want %v", err, want)
	}
}

func validSMTPConfig() SMTPConfig {
	return SMTPConfig{Host: "127.0.0.1", Port: 25, From: "no-reply@verba.example", ConnectTimeout: time.Second, SendTimeout: time.Second}
}

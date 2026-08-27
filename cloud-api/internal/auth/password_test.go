package auth

import (
	"errors"
	"strings"
	"testing"

	"github.com/dngmeng/cloud-api/internal/domain"
)

func TestPasswordHashAndVerify(t *testing.T) {
	const password = "correct horse battery staple"

	first, err := HashPassword(password)
	if err != nil {
		t.Fatal(err)
	}
	second, err := HashPassword(password)
	if err != nil {
		t.Fatal(err)
	}
	if first == password || first == second {
		t.Fatal("Argon2id hash must use a fresh salt")
	}
	if !strings.HasPrefix(first, "$argon2id$v=19$m=65536,t=3,p=2$") {
		t.Fatalf("unexpected hash encoding: %q", first)
	}

	ok, err := VerifyPassword(first, password)
	if err != nil || !ok {
		t.Fatalf("correct password rejected: ok=%v err=%v", ok, err)
	}
	ok, err = VerifyPassword(first, "wrong password")
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Fatal("wrong password accepted")
	}
}

func TestPasswordValidationAndMalformedHash(t *testing.T) {
	if _, err := HashPassword("short"); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("short password error = %v", err)
	}

	cases := []string{
		"not-a-hash",
		"$argon2i$v=19$m=65536,t=3,p=2$c2FsdHNhbHQ$aGFzaGhhc2hoYXNoaGFzaA",
		"$argon2id$v=19$m=1048576,t=3,p=2$c2FsdHNhbHQ$aGFzaGhhc2hoYXNoaGFzaA",
		"$argon2id$v=19$m=65536,t=3,p=2$not+raw$not+raw",
	}
	for _, encoded := range cases {
		if _, err := VerifyPassword(encoded, "correct horse battery staple"); err == nil {
			t.Fatalf("malformed hash accepted: %q", encoded)
		}
	}
}

package store

import (
	"context"
	"errors"
	"testing"

	"github.com/dngmeng/cloud-api/internal/domain"
)

// This compile-time contract protects the Store API used by the email
// registration service. PostgreSQL behavior is exercised in integration.
func TestRegistrationVerificationStoreContract(t *testing.T) {
	var verificationStore interface {
		RequestRegistrationVerification(context.Context, domain.CreateRegistrationVerificationParams) (domain.RegistrationVerification, error)
		ConfirmRegistrationVerification(context.Context, domain.ConfirmRegistrationVerificationParams) (domain.RegisterParams, error)
		InvalidateRegistrationVerification(context.Context, domain.InvalidateRegistrationVerificationParams) error
	} = (*Postgres)(nil)
	if verificationStore == nil {
		t.Fatal("Postgres must implement the registration verification store contract")
	}
}

func TestRegistrationVerificationStoreCanonicalizesIdentity(t *testing.T) {
	username, err := domain.ParseUsername(" Example_User ")
	if err != nil || username.String() != "example_user" {
		t.Fatalf("ParseUsername() = %q, %v", username, err)
	}
	email, err := domain.ParseEmail(" USER@Example.com ")
	if err != nil || email.String() != "user@example.com" {
		t.Fatalf("ParseEmail() = %q, %v", email, err)
	}
}

func TestRegistrationVerificationFailuresRemainGeneric(t *testing.T) {
	if domain.ErrRegistrationVerificationFailed.Error() != "registration verification failed" {
		t.Fatal("verification failures must use a generic domain error")
	}
	if !errors.Is(domain.ErrRegistrationVerificationFailed, domain.ErrRegistrationVerificationFailed) {
		t.Fatal("verification failure must support errors.Is")
	}
}

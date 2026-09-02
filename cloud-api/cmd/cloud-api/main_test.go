package main

import (
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/config"
)

func TestRegistrationVerificationDependenciesAreDisabledWithoutFeatureGate(t *testing.T) {
	dependencies, err := newRegistrationVerificationDependencies(config.Config{})
	if err != nil {
		t.Fatalf("newRegistrationVerificationDependencies() error = %v", err)
	}
	if dependencies.sender != nil {
		t.Fatalf("dependencies = %#v, want disabled dependencies", dependencies)
	}
}

func TestRouterOptionsReceivesEnabledRegistrationVerificationService(t *testing.T) {
	dependencies, err := newRegistrationVerificationDependencies(config.Config{
		EmailVerificationEnabled:         true,
		SMTPHost:                         "127.0.0.1",
		SMTPPort:                         25,
		SMTPFrom:                         "no-reply@verba.example",
		SMTPConnectTimeout:               time.Second,
		SMTPSendTimeout:                  time.Second,
		EmailVerificationRateLimitSecret: "rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr",
	})
	if err != nil {
		t.Fatalf("newRegistrationVerificationDependencies() error = %v", err)
	}

	options := newRouterOptions(config.Config{}, nil, nil, dependencies)
	if options.RegistrationVerification != dependencies.service {
		t.Fatal("router options did not receive the registration verification service")
	}
}

func TestNewRegistrationCodeSenderUsesSMTPConfig(t *testing.T) {
	sender, err := newRegistrationCodeSender(config.Config{
		EmailVerificationEnabled: true,
		SMTPHost:                 "127.0.0.1",
		SMTPPort:                 25,
		SMTPFrom:                 "no-reply@verba.example",
		SMTPConnectTimeout:       time.Second,
		SMTPSendTimeout:          time.Second,
	})
	if err != nil {
		t.Fatalf("newRegistrationCodeSender() error = %v", err)
	}
	if sender == nil {
		t.Fatal("newRegistrationCodeSender() returned nil sender")
	}
}

func TestRegistrationVerificationDependenciesRequireEnabledSafeConfig(t *testing.T) {
	_, err := newRegistrationVerificationDependencies(config.Config{EmailVerificationEnabled: true})
	if err == nil {
		t.Fatal("newRegistrationVerificationDependencies() error = nil")
	}
}

package main

import (
	"bytes"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
)

func TestNewCaptchaServiceFailsClosedWithoutValidSecret(t *testing.T) {
	for _, cfg := range []config.Config{
		{},
		{CaptchaSecret: "tooshort"},
	} {
		if _, err := newCaptchaService(cfg); err == nil {
			t.Fatalf("newCaptchaService(%+v) error = nil, want fail-closed rejection", cfg)
		}
	}
}

func TestNewCaptchaServiceBuildsIssuingPrimitiveFromValidConfig(t *testing.T) {
	service, err := newCaptchaService(config.Config{CaptchaSecret: string(bytes.Repeat([]byte("c"), auth.MinimumSecretBytes))})
	if err != nil {
		t.Fatalf("newCaptchaService() error = %v", err)
	}
	draft, err := service.Issue()
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}
	if len(draft.MasterImage) == 0 || len(draft.TileImage) == 0 || len(draft.AnswerHash) != 32 || len(draft.AnswerSalt) == 0 || !draft.ExpiresAt.After(time.Now().Add(auth.CaptchaTTL-time.Minute)) {
		t.Fatalf("draft = %+v", draft)
	}
	if !auth.ValidCaptchaCoordinate(draft.TargetX) {
		t.Fatalf("draft target %d escapes the challenge canvas", draft.TargetX)
	}
	if !auth.CaptchaCoordinateMatches(service.AnswerPepper, draft.AnswerSalt, draft.AnswerHash, draft.TargetX) {
		t.Fatal("issued draft does not verify against its own target coordinate")
	}
}

func TestRouterOptionsReceiveCaptchaServiceAndNoEmailVerificationWiring(t *testing.T) {
	service, err := newCaptchaService(config.Config{CaptchaSecret: string(bytes.Repeat([]byte("c"), auth.MinimumSecretBytes))})
	if err != nil {
		t.Fatalf("newCaptchaService() error = %v", err)
	}

	options := newRouterOptions(config.Config{}, nil, nil, service)
	if options.Captcha != service {
		t.Fatal("router options did not receive the captcha service")
	}
	// EMAIL_VERIFICATION_ENABLED must no longer enable the registration path:
	// no email verification service is wired into the router regardless of
	// configuration.
	if options.RegistrationVerification != nil {
		t.Fatal("router options wired a registration verification service")
	}
}

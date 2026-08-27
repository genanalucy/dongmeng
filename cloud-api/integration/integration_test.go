package integration_test

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
	httpapi "github.com/dngmeng/cloud-api/internal/http"
	"github.com/dngmeng/cloud-api/internal/store"
	"github.com/google/uuid"
)

type readyDatabase struct{}

func (readyDatabase) Ping(context.Context) error { return nil }

type expiryStore interface {
	ExpiredFeedbackArtifacts(context.Context, time.Time, int) ([]store.ExpiredFeedbackArtifact, error)
	ExpiredTranslationSessions(context.Context, time.Time, int) ([]store.ExpiredTranslationSession, error)
	ExpiredRefreshTokens(context.Context, time.Time, int) ([]store.ExpiredRefreshToken, error)
}

var (
	_ httpapi.Readiness = (*store.Postgres)(nil)
	_ expiryStore       = (*store.Postgres)(nil)
)

func TestAuthCoreAndPlatformRouterIntegrate(t *testing.T) {
	issuer := auth.TokenIssuer{
		Issuer:          "cloud-api-integration",
		Audience:        "cloud-api-clients",
		SessionAudience: "translator-agent",
		AccessSecret:    bytes.Repeat([]byte("a"), auth.MinimumSecretBytes),
		SessionSecret:   bytes.Repeat([]byte("s"), auth.MinimumSecretBytes),
	}
	now := time.Date(2026, time.August, 24, 0, 0, 0, 0, time.UTC)
	token, err := issuer.AccessToken(uuid.New(), "user", time.Minute, now)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := issuer.ParseAccessAt(token, now.Add(30*time.Second)); err != nil {
		t.Fatalf("parse integrated access token: %v", err)
	}

	code, storedHash, err := auth.RandomCode()
	if err != nil {
		t.Fatal(err)
	}
	inputHash, err := auth.HashRedemptionCode("  " + strings.ToLower(code) + "  ")
	if err != nil {
		t.Fatal(err)
	}
	if !auth.SecretHashEqual(storedHash, inputHash) {
		t.Fatal("canonical redemption input does not match generated code")
	}

	router := httpapi.NewRouter(httpapi.RouterOptions{
		Config: config.Config{
			Environment:     "test",
			AllowedOrigins:  []string{"https://app.example"},
			DatabaseTimeout: time.Second,
			RateLimitRPS:    100,
			RateLimitBurst:  100,
		},
		Database: readyDatabase{},
		Logger:   slog.New(slog.NewTextHandler(io.Discard, nil)),
		Version:  "integration-test",
	})

	request := httptest.NewRequest(http.MethodGet, "/readyz", nil)
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("ready status = %d, body = %s", response.Code, response.Body.String())
	}
}

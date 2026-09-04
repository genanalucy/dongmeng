package config

import (
	"strings"
	"testing"
	"time"
)

func TestLoadReadsSafeDefaultsAndOverrides(t *testing.T) {
	setRequiredEnvironment(t)
	t.Setenv("HTTP_READ_TIMEOUT", "3s")
	t.Setenv("HTTP_READ_HEADER_TIMEOUT", "2s")
	t.Setenv("HTTP_WRITE_TIMEOUT", "4s")
	t.Setenv("HTTP_IDLE_TIMEOUT", "30s")
	t.Setenv("RATE_LIMIT_RPS", "2.5")
	t.Setenv("RATE_LIMIT_BURST", "7")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.ReadTimeout != 3*time.Second || cfg.ReadHeaderTimeout != 2*time.Second || cfg.WriteTimeout != 4*time.Second || cfg.IdleTimeout != 30*time.Second {
		t.Fatalf("unexpected HTTP timeouts: %+v", cfg)
	}
	if cfg.RateLimitRPS != 2.5 || cfg.RateLimitBurst != 7 {
		t.Fatalf("unexpected rate limit: %+v", cfg)
	}
	if len(cfg.AllowedOrigins) != 1 || cfg.AllowedOrigins[0] != "http://127.0.0.1:5173" {
		t.Fatalf("AllowedOrigins = %#v", cfg.AllowedOrigins)
	}
}

func TestLoadRejectsUnsafeValues(t *testing.T) {
	tests := []struct {
		name     string
		variable string
		value    string
		want     string
	}{
		{name: "wildcard origin", variable: "CORS_ALLOWED_ORIGINS", value: "*", want: "wildcard"},
		{name: "non finite rate", variable: "RATE_LIMIT_RPS", value: "NaN", want: "finite positive"},
		{name: "zero timeout", variable: "HTTP_READ_TIMEOUT", value: "0s", want: "HTTP_READ_TIMEOUT"},
		{name: "missing database", variable: "DATABASE_URL", value: "", want: "DATABASE_URL is required"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			setRequiredEnvironment(t)
			t.Setenv(test.variable, test.value)
			_, err := Load()
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("Load() error = %v, want substring %q", err, test.want)
			}
		})
	}
}

func TestValidateRequiresCaptchaSecretUnconditionally(t *testing.T) {
	short := validConfig()
	short.CaptchaSecret = "tooshort"
	err := short.Validate()
	if err == nil || !strings.Contains(err.Error(), "CAPTCHA_SECRET") {
		t.Fatalf("Validate() error = %v, want CAPTCHA_SECRET rejection", err)
	}
	if strings.Contains(err.Error(), short.CaptchaSecret) {
		t.Fatalf("Validate() exposed captcha secret: %v", err)
	}

	missing := validConfig()
	missing.CaptchaSecret = ""
	missing.EmailVerificationEnabled = false
	if err := missing.Validate(); err == nil || !strings.Contains(err.Error(), "CAPTCHA_SECRET") {
		t.Fatalf("Validate() error = %v, want unconditional CAPTCHA_SECRET rejection", err)
	}
}

func TestLoadRejectsMissingCaptchaSecret(t *testing.T) {
	setRequiredEnvironment(t)
	t.Setenv("CAPTCHA_SECRET", "")

	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "CAPTCHA_SECRET") {
		t.Fatalf("Load() error = %v, want CAPTCHA_SECRET rejection", err)
	}
}

func TestValidateRejectsNonLoopbackSMTPHostInProduction(t *testing.T) {
	cfg := validConfig()
	cfg.EmailVerificationEnabled = true
	cfg.Environment = "production"
	cfg.SMTPHost = "mail.example.com"

	if err := cfg.Validate(); err == nil {
		t.Fatal("Validate() error = nil, want non-loopback SMTP host rejection")
	}
}

func TestValidateRejectsUnsafeSMTPConfigurationWithoutLeakingSecret(t *testing.T) {
	cfg := validConfig()
	cfg.EmailVerificationEnabled = true
	cfg.SMTPFrom = "not an email"
	cfg.SMTPConnectTimeout = 0
	cfg.SMTPSendTimeout = 0
	cfg.EmailVerificationRateLimitSecret = "short"

	err := cfg.Validate()
	if err == nil || !strings.Contains(err.Error(), "SMTP_FROM") || !strings.Contains(err.Error(), "SMTP_CONNECT_TIMEOUT") || !strings.Contains(err.Error(), "SMTP_SEND_TIMEOUT") || !strings.Contains(err.Error(), "EMAIL_VERIFICATION_RATE_LIMIT_SECRET") {
		t.Fatalf("Validate() error = %v", err)
	}
	if strings.Contains(err.Error(), cfg.EmailVerificationRateLimitSecret) {
		t.Fatalf("Validate() exposed rate limit secret: %v", err)
	}
}

func TestLoadAllowsExistingDeploymentWhenEmailVerificationIsDisabled(t *testing.T) {
	setRequiredEnvironment(t)
	t.Setenv("EMAIL_VERIFICATION_ENABLED", "false")
	t.Setenv("SMTP_HOST", "")
	t.Setenv("SMTP_PORT", "")
	t.Setenv("SMTP_FROM", "")
	t.Setenv("EMAIL_VERIFICATION_RATE_LIMIT_SECRET", "")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.EmailVerificationEnabled {
		t.Fatal("EmailVerificationEnabled = true, want false")
	}
}

func TestLoadRejectsEnabledEmailVerificationWithoutSafeConfiguration(t *testing.T) {
	setRequiredEnvironment(t)
	t.Setenv("EMAIL_VERIFICATION_ENABLED", "true")
	t.Setenv("SMTP_FROM", "")

	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "SMTP_FROM") {
		t.Fatalf("Load() error = %v", err)
	}
}

func TestValidateRejectsProductionInsecureTransport(t *testing.T) {
	cfg := validConfig()
	cfg.Environment = "production"
	cfg.DatabaseURL = "postgres://cloud:secret@db:5432/cloud?sslmode=disable"
	cfg.AllowedOrigins = []string{"http://app.example.com"}

	err := cfg.Validate()
	if err == nil || !strings.Contains(err.Error(), "must not disable TLS") || !strings.Contains(err.Error(), "must use https") {
		t.Fatalf("Validate() error = %v", err)
	}
}

func setRequiredEnvironment(t *testing.T) {
	t.Helper()
	t.Setenv("CLOUD_API_ENV", "development")
	t.Setenv("CLOUD_API_ADDR", "127.0.0.1:8080")
	t.Setenv("DATABASE_URL", "postgres://cloud:secret@127.0.0.1:5432/cloud?sslmode=disable")
	t.Setenv("CORS_ALLOWED_ORIGINS", "http://127.0.0.1:5173,http://127.0.0.1:5173")
	t.Setenv("DATABASE_TIMEOUT", "")
	t.Setenv("SHUTDOWN_TIMEOUT", "")
	t.Setenv("HTTP_READ_TIMEOUT", "")
	t.Setenv("HTTP_READ_HEADER_TIMEOUT", "")
	t.Setenv("HTTP_WRITE_TIMEOUT", "")
	t.Setenv("HTTP_IDLE_TIMEOUT", "")
	t.Setenv("RATE_LIMIT_RPS", "")
	t.Setenv("RATE_LIMIT_BURST", "")
	t.Setenv("TOKEN_ISSUER", "cloud-api-test")
	t.Setenv("ACCESS_TOKEN_AUDIENCE", "cloud-api-clients")
	t.Setenv("TRANSLATION_SESSION_AUDIENCE", "translator-agent")
	t.Setenv("ACCESS_TOKEN_HS256_KEY", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	t.Setenv("TRANSLATION_SESSION_HS256_KEY", "ssssssssssssssssssssssssssssssss")
	t.Setenv("SMTP_HOST", "127.0.0.1")
	t.Setenv("SMTP_PORT", "25")
	t.Setenv("SMTP_FROM", "no-reply@verba.example")
	t.Setenv("SMTP_CONNECT_TIMEOUT", "")
	t.Setenv("SMTP_SEND_TIMEOUT", "")
	t.Setenv("EMAIL_VERIFICATION_RATE_LIMIT_SECRET", "rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr")
	t.Setenv("CAPTCHA_SECRET", "cccccccccccccccccccccccccccccccc")
}

func validConfig() Config {
	return Config{
		Environment:                      "test",
		Address:                          "127.0.0.1:8080",
		DatabaseURL:                      "postgres://cloud:secret@127.0.0.1:5432/cloud?sslmode=disable",
		AllowedOrigins:                   []string{"http://127.0.0.1:5173"},
		DatabaseTimeout:                  time.Second,
		ShutdownTimeout:                  time.Second,
		ReadTimeout:                      time.Second,
		ReadHeaderTimeout:                time.Second,
		WriteTimeout:                     time.Second,
		IdleTimeout:                      time.Second,
		RateLimitRPS:                     10,
		RateLimitBurst:                   20,
		TokenIssuer:                      "cloud-api-test",
		AccessAudience:                   "cloud-api-clients",
		SessionAudience:                  "translator-agent",
		AccessSecret:                     "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		SessionSecret:                    "ssssssssssssssssssssssssssssssss",
		EmailVerificationEnabled:         true,
		SMTPHost:                         "127.0.0.1",
		SMTPPort:                         25,
		SMTPFrom:                         "no-reply@verba.example",
		SMTPConnectTimeout:               time.Second,
		SMTPSendTimeout:                  time.Second,
		EmailVerificationRateLimitSecret: "rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr",
		CaptchaSecret:                    "cccccccccccccccccccccccccccccccc",
	}
}

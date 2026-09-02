// Package config loads and validates cloud-api runtime configuration.
package config

import (
	"errors"
	"fmt"
	"math"
	"net"
	"net/mail"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultAddress            = ":8080"
	defaultDatabaseTimeout    = 2 * time.Second
	defaultShutdownTimeout    = 10 * time.Second
	defaultReadTimeout        = 10 * time.Second
	defaultReadHeaderTimeout  = 5 * time.Second
	defaultWriteTimeout       = 15 * time.Second
	defaultIdleTimeout        = 60 * time.Second
	defaultRateLimitRPS       = 10.0
	defaultRateLimitBurst     = 20
	defaultSMTPHost           = "127.0.0.1"
	defaultSMTPPort           = 25
	defaultSMTPConnectTimeout = 5 * time.Second
	defaultSMTPSendTimeout    = 10 * time.Second
)

// Config contains all runtime settings. Secret values must never be logged.
type Config struct {
	Environment                      string
	Address                          string
	DatabaseURL                      string
	AllowedOrigins                   []string
	DatabaseTimeout                  time.Duration
	ShutdownTimeout                  time.Duration
	ReadTimeout                      time.Duration
	ReadHeaderTimeout                time.Duration
	WriteTimeout                     time.Duration
	IdleTimeout                      time.Duration
	RateLimitRPS                     float64
	RateLimitBurst                   int
	TokenIssuer                      string
	AccessAudience                   string
	SessionAudience                  string
	AccessSecret                     string
	SessionSecret                    string
	EmailVerificationEnabled         bool
	SMTPHost                         string
	SMTPPort                         int
	SMTPFrom                         string
	SMTPConnectTimeout               time.Duration
	SMTPSendTimeout                  time.Duration
	EmailVerificationRateLimitSecret string
}

// Load reads environment variables and validates the resulting configuration.
func Load() (Config, error) {
	cfg := Config{
		Environment:                      envOrDefault("CLOUD_API_ENV", "development"),
		Address:                          envOrDefault("CLOUD_API_ADDR", defaultAddress),
		DatabaseURL:                      strings.TrimSpace(os.Getenv("DATABASE_URL")),
		AllowedOrigins:                   splitCSV(os.Getenv("CORS_ALLOWED_ORIGINS")),
		DatabaseTimeout:                  defaultDatabaseTimeout,
		ShutdownTimeout:                  defaultShutdownTimeout,
		ReadTimeout:                      defaultReadTimeout,
		ReadHeaderTimeout:                defaultReadHeaderTimeout,
		WriteTimeout:                     defaultWriteTimeout,
		IdleTimeout:                      defaultIdleTimeout,
		RateLimitRPS:                     defaultRateLimitRPS,
		RateLimitBurst:                   defaultRateLimitBurst,
		TokenIssuer:                      strings.TrimSpace(os.Getenv("TOKEN_ISSUER")),
		AccessAudience:                   strings.TrimSpace(os.Getenv("ACCESS_TOKEN_AUDIENCE")),
		SessionAudience:                  strings.TrimSpace(os.Getenv("TRANSLATION_SESSION_AUDIENCE")),
		AccessSecret:                     os.Getenv("ACCESS_TOKEN_HS256_KEY"),
		SessionSecret:                    os.Getenv("TRANSLATION_SESSION_HS256_KEY"),
		SMTPHost:                         envOrDefault("SMTP_HOST", defaultSMTPHost),
		SMTPPort:                         defaultSMTPPort,
		SMTPFrom:                         strings.TrimSpace(os.Getenv("SMTP_FROM")),
		SMTPConnectTimeout:               defaultSMTPConnectTimeout,
		SMTPSendTimeout:                  defaultSMTPSendTimeout,
		EmailVerificationRateLimitSecret: os.Getenv("EMAIL_VERIFICATION_RATE_LIMIT_SECRET"),
	}

	var err error
	if cfg.DatabaseTimeout, err = durationEnv("DATABASE_TIMEOUT", cfg.DatabaseTimeout); err != nil {
		return Config{}, err
	}
	if cfg.ShutdownTimeout, err = durationEnv("SHUTDOWN_TIMEOUT", cfg.ShutdownTimeout); err != nil {
		return Config{}, err
	}
	if cfg.ReadTimeout, err = durationEnv("HTTP_READ_TIMEOUT", cfg.ReadTimeout); err != nil {
		return Config{}, err
	}
	if cfg.ReadHeaderTimeout, err = durationEnv("HTTP_READ_HEADER_TIMEOUT", cfg.ReadHeaderTimeout); err != nil {
		return Config{}, err
	}
	if cfg.WriteTimeout, err = durationEnv("HTTP_WRITE_TIMEOUT", cfg.WriteTimeout); err != nil {
		return Config{}, err
	}
	if cfg.IdleTimeout, err = durationEnv("HTTP_IDLE_TIMEOUT", cfg.IdleTimeout); err != nil {
		return Config{}, err
	}
	if cfg.RateLimitRPS, err = floatEnv("RATE_LIMIT_RPS", cfg.RateLimitRPS); err != nil {
		return Config{}, err
	}
	if cfg.RateLimitBurst, err = intEnv("RATE_LIMIT_BURST", cfg.RateLimitBurst); err != nil {
		return Config{}, err
	}
	if cfg.EmailVerificationEnabled, err = boolEnv("EMAIL_VERIFICATION_ENABLED", false); err != nil {
		return Config{}, err
	}
	if cfg.SMTPPort, err = intEnv("SMTP_PORT", cfg.SMTPPort); err != nil {
		return Config{}, err
	}
	if cfg.SMTPConnectTimeout, err = durationEnv("SMTP_CONNECT_TIMEOUT", cfg.SMTPConnectTimeout); err != nil {
		return Config{}, err
	}
	if cfg.SMTPSendTimeout, err = durationEnv("SMTP_SEND_TIMEOUT", cfg.SMTPSendTimeout); err != nil {
		return Config{}, err
	}

	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

// Validate enforces safe, explicit network and database settings.
func (c Config) Validate() error {
	var problems []string

	switch c.Environment {
	case "development", "test", "staging", "production":
	default:
		problems = append(problems, "CLOUD_API_ENV must be development, test, staging, or production")
	}
	if err := validateAddress(c.Address); err != nil {
		problems = append(problems, "CLOUD_API_ADDR is invalid")
	}
	if err := validateDatabaseURL(c.DatabaseURL, c.Environment); err != nil {
		problems = append(problems, err.Error())
	}
	if len(c.AllowedOrigins) == 0 {
		problems = append(problems, "CORS_ALLOWED_ORIGINS must contain at least one explicit origin")
	}
	for _, origin := range c.AllowedOrigins {
		if err := validateOrigin(origin, c.Environment); err != nil {
			problems = append(problems, err.Error())
		}
	}
	if c.DatabaseTimeout <= 0 {
		problems = append(problems, "DATABASE_TIMEOUT must be positive")
	}
	if c.ShutdownTimeout <= 0 {
		problems = append(problems, "SHUTDOWN_TIMEOUT must be positive")
	}
	if c.ReadTimeout <= 0 {
		problems = append(problems, "HTTP_READ_TIMEOUT must be positive")
	}
	if c.ReadHeaderTimeout <= 0 {
		problems = append(problems, "HTTP_READ_HEADER_TIMEOUT must be positive")
	}
	if c.WriteTimeout <= 0 {
		problems = append(problems, "HTTP_WRITE_TIMEOUT must be positive")
	}
	if c.IdleTimeout <= 0 {
		problems = append(problems, "HTTP_IDLE_TIMEOUT must be positive")
	}
	if c.RateLimitRPS <= 0 || math.IsNaN(c.RateLimitRPS) || math.IsInf(c.RateLimitRPS, 0) {
		problems = append(problems, "RATE_LIMIT_RPS must be a finite positive number")
	}
	if c.RateLimitBurst <= 0 {
		problems = append(problems, "RATE_LIMIT_BURST must be positive")
	}
	if strings.TrimSpace(c.TokenIssuer) == "" || strings.TrimSpace(c.AccessAudience) == "" || strings.TrimSpace(c.SessionAudience) == "" || c.AccessAudience == c.SessionAudience || len(c.AccessSecret) < 32 || len(c.SessionSecret) < 32 || c.AccessSecret == c.SessionSecret {
		problems = append(problems, "token issuer, distinct audiences, and distinct 32-byte token keys are required")
	}
	if c.EmailVerificationEnabled {
		if err := validateSMTPHost(c.SMTPHost, c.Environment); err != nil {
			problems = append(problems, err.Error())
		}
		if c.SMTPPort < 1 || c.SMTPPort > 65535 {
			problems = append(problems, "SMTP_PORT must be between 1 and 65535")
		}
		sender, err := mail.ParseAddress(c.SMTPFrom)
		if err != nil || sender.Address != c.SMTPFrom {
			problems = append(problems, "SMTP_FROM must be a valid sender address")
		}
		if c.SMTPConnectTimeout <= 0 {
			problems = append(problems, "SMTP_CONNECT_TIMEOUT must be positive")
		}
		if c.SMTPSendTimeout <= 0 {
			problems = append(problems, "SMTP_SEND_TIMEOUT must be positive")
		}
		if len(c.EmailVerificationRateLimitSecret) < 32 {
			problems = append(problems, "EMAIL_VERIFICATION_RATE_LIMIT_SECRET must be at least 32 bytes")
		}
	}

	if len(problems) > 0 {
		return errors.New(strings.Join(problems, "; "))
	}
	return nil
}

func validateSMTPHost(host, environment string) error {
	host = strings.TrimSpace(host)
	if host == "" {
		return errors.New("SMTP_HOST is required")
	}
	if environment == "production" && host != "localhost" && host != "127.0.0.1" {
		return errors.New("SMTP_HOST must be localhost or 127.0.0.1 in production")
	}
	return nil
}

func validateAddress(address string) error {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return err
	}
	if host != "" && net.ParseIP(host) == nil && host != "localhost" {
		return errors.New("host must be empty, localhost, or an IP address")
	}
	parsedPort, err := strconv.Atoi(port)
	if err != nil || parsedPort < 1 || parsedPort > 65535 {
		return errors.New("port must be between 1 and 65535")
	}
	return nil
}

func validateDatabaseURL(value, environment string) error {
	if value == "" {
		return errors.New("DATABASE_URL is required")
	}
	parsed, err := url.Parse(value)
	if err != nil || (parsed.Scheme != "postgres" && parsed.Scheme != "postgresql") || parsed.Host == "" || parsed.User == nil || parsed.User.Username() == "" || strings.Trim(parsed.Path, "/") == "" {
		return errors.New("DATABASE_URL must be a postgres URL with host, user, and database name")
	}
	if environment == "production" && strings.EqualFold(parsed.Query().Get("sslmode"), "disable") {
		return errors.New("DATABASE_URL must not disable TLS in production")
	}
	return nil
}

func validateOrigin(origin, environment string) error {
	if origin == "*" {
		return errors.New("CORS_ALLOWED_ORIGINS does not permit wildcard origins")
	}
	parsed, err := url.Parse(origin)
	if err != nil || parsed.Host == "" || parsed.Path != "" || parsed.RawQuery != "" || parsed.Fragment != "" || parsed.User != nil {
		return fmt.Errorf("invalid CORS origin %q", origin)
	}
	if parsed.Scheme != "https" && parsed.Scheme != "http" {
		return fmt.Errorf("CORS origin %q must use http or https", origin)
	}
	if environment != "development" && environment != "test" && parsed.Scheme != "https" {
		return fmt.Errorf("CORS origin %q must use https outside development/test", origin)
	}
	return nil
}

func envOrDefault(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func splitCSV(value string) []string {
	seen := make(map[string]struct{})
	var values []string
	for _, item := range strings.Split(value, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		if _, exists := seen[item]; exists {
			continue
		}
		seen[item] = struct{}{}
		values = append(values, item)
	}
	return values
}

func durationEnv(name string, fallback time.Duration) (time.Duration, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback, nil
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("%s must be a duration: %w", name, err)
	}
	return parsed, nil
}

func boolEnv(name string, fallback bool) (bool, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return false, fmt.Errorf("%s must be true or false: %w", name, err)
	}
	return parsed, nil
}

func floatEnv(name string, fallback float64) (float64, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.ParseFloat(value, 64)
	if err != nil {
		return 0, fmt.Errorf("%s must be numeric: %w", name, err)
	}
	return parsed, nil
}

func intEnv(name string, fallback int) (int, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return 0, fmt.Errorf("%s must be an integer: %w", name, err)
	}
	return parsed, nil
}

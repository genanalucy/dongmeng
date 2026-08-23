// Package config loads local-agent configuration without exposing credentials.
package config

import (
	"errors"
	"fmt"
	"math"
	"os"
	"strconv"
	"strings"
	"time"
)

const DefaultResourceID = "volc.service_type.10053"

// Config contains only the credential form selected for the AST client.
// Secret Key is intentionally not supported or read.
type Config struct {
	APIKey          string
	AppID           string
	AccessToken     string
	ResourceID      string
	DashScopeAPIKey string
	QwenAPIHost     string
}

// SessionAuth contains optional translation-session JWT verification inputs.
// Disabled is the default to preserve the loopback development workflow.
type SessionAuth struct {
	Enabled     bool
	HMACKey     []byte
	Issuer      string
	Audience    string
	ClockSkew   time.Duration
	MaxLifetime time.Duration
}

// Load reads a current API key or the legacy App ID plus access-token pair.
func Load(getenv func(string) string) (Config, error) {
	if getenv == nil {
		getenv = os.Getenv
	}

	cfg := Config{
		APIKey:          getenv("VOLCENGINE_API_KEY"),
		AppID:           getenv("VOLCENGINE_APP_ID"),
		AccessToken:     getenv("VOLCENGINE_ACCESS_TOKEN"),
		ResourceID:      getenv("VOLCENGINE_RESOURCE_ID"),
		DashScopeAPIKey: getenv("DASHSCOPE_API_KEY"),
		QwenAPIHost:     getenv("QWEN_API_HOST"),
	}
	if cfg.ResourceID == "" {
		cfg.ResourceID = DefaultResourceID
	}

	hasAPIKey := cfg.APIKey != ""
	hasLegacyPart := cfg.AppID != "" || cfg.AccessToken != ""
	hasLegacyPair := cfg.AppID != "" && cfg.AccessToken != ""

	switch {
	case hasAPIKey && hasLegacyPart:
		return Config{}, errors.New("invalid Volcengine authentication configuration: choose API key or legacy credentials")
	case hasAPIKey:
		return cfg, nil
	case hasLegacyPair:
		return cfg, nil
	case cfg.DashScopeAPIKey != "" && cfg.QwenAPIHost != "":
		return cfg, nil
	case hasLegacyPart:
		return Config{}, errors.New("invalid legacy Volcengine authentication configuration: both App ID and access token are required")
	default:
		return Config{}, fmt.Errorf("missing Volcengine authentication configuration")
	}
}

// LoadSessionAuth reads session authorization through the supplied lookup only.
// When disabled, it does not request any JWT key or trust configuration.
func LoadSessionAuth(getenv func(string) string) (SessionAuth, error) {
	if getenv == nil {
		getenv = os.Getenv
	}

	rawEnabled := strings.TrimSpace(getenv("TRANSLATION_SESSION_AUTH_ENABLED"))
	if rawEnabled == "" || rawEnabled == "false" {
		return SessionAuth{}, nil
	}
	if rawEnabled != "true" {
		return SessionAuth{}, errors.New("invalid translation session authorization enabled flag")
	}

	clockSkew, err := positiveSeconds(getenv("TRANSLATION_SESSION_CLOCK_SKEW_SECONDS"), 30, true)
	if err != nil {
		return SessionAuth{}, errors.New("invalid translation session clock skew")
	}
	maxLifetime, err := positiveSeconds(getenv("TRANSLATION_SESSION_MAX_LIFETIME_SECONDS"), 300, false)
	if err != nil {
		return SessionAuth{}, errors.New("invalid translation session maximum lifetime")
	}

	cfg := SessionAuth{
		Enabled:     true,
		HMACKey:     []byte(getenv("TRANSLATION_SESSION_HS256_KEY")),
		Issuer:      getenv("TRANSLATION_SESSION_ISSUER"),
		Audience:    getenv("TRANSLATION_SESSION_AUDIENCE"),
		ClockSkew:   clockSkew,
		MaxLifetime: maxLifetime,
	}
	if len(cfg.HMACKey) < 32 || strings.TrimSpace(cfg.Issuer) != cfg.Issuer || cfg.Issuer == "" ||
		strings.TrimSpace(cfg.Audience) != cfg.Audience || cfg.Audience == "" {
		return SessionAuth{}, errors.New("incomplete translation session authorization configuration")
	}
	return cfg, nil
}

func positiveSeconds(raw string, fallback int64, allowZero bool) (time.Duration, error) {
	if strings.TrimSpace(raw) == "" {
		return time.Duration(fallback) * time.Second, nil
	}
	seconds, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || seconds < 0 || (!allowZero && seconds == 0) || seconds > math.MaxInt64/int64(time.Second) {
		return 0, errors.New("invalid duration")
	}
	return time.Duration(seconds) * time.Second, nil
}

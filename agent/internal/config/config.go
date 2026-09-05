// Package config loads local-agent configuration without exposing credentials.
package config

import (
	"errors"
	"fmt"
	"math"
	"net/url"
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

// SessionAuth contains the caller-supplied trust contract for translation-session
// JWTs. Disabled is the loopback development default; production deployments
// must enable it explicitly and the loader rejects a production configuration
// with authorization disabled. HMACKey is signing trust material supplied by the
// deployment secret mechanism, not a Provider API key or a client-side value.
type SessionAuth struct {
	Enabled     bool
	HMACKey     []byte
	Issuer      string
	Audience    string
	ClockSkew   time.Duration
	MaxLifetime time.Duration
	// Cloud authorization consults the Cloud API's internal service endpoint
	// so ended, revoked, replaced, disabled, and expired sessions stop the
	// Agent even while the JWT itself is still locally valid. Both values are
	// required together in production; omitting both keeps development local.
	CloudAuthorizeURL string
	CloudServiceToken string
	// ReauthInterval bounds how often an active connection re-authorizes;
	// ReauthTimeout bounds each authorize request; ReauthTolerance bounds how
	// long an unreachable Cloud is tolerated before the connection fails
	// closed. Their sum plus the teardown budget must stay well inside the
	// five-second governance termination deadline.
	ReauthInterval  time.Duration
	ReauthTimeout   time.Duration
	ReauthTolerance time.Duration
}

const (
	defaultReauthInterval  = time.Second
	defaultReauthTimeout   = 750 * time.Millisecond
	defaultReauthTolerance = 2 * time.Second
	maxReauthInterval      = time.Second
	maxReauthTimeout       = time.Second
	maxReauthTolerance     = 3 * time.Second
	// maxReauthBudget keeps interval+timeout+tolerance low enough that a
	// governance decision still terminates the connection inside five
	// seconds including the teardown write budget.
	maxReauthBudget = 4 * time.Second
)

// LoadEnvironment reads AGENT_ENV. The empty value means development, which
// keeps local loopback runs unchanged.
func LoadEnvironment(getenv func(string) string) (string, error) {
	if getenv == nil {
		getenv = os.Getenv
	}
	environment := strings.TrimSpace(getenv("AGENT_ENV"))
	if environment == "" {
		return "development", nil
	}
	switch environment {
	case "development", "test", "staging", "production":
		return environment, nil
	default:
		return "", errors.New("invalid agent environment")
	}
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
// Missing, empty, or trimmed "false" leaves authorization disabled and does not
// request key, issuer, audience, or duration inputs. Trimmed "true" requires the
// complete HS256 trust contract; every other non-empty value is rejected. In a
// production environment authorization must be enabled and the Cloud
// authorization endpoint plus service token must be configured, so production
// fails closed at startup instead of silently degrading to local-only checks.
// Errors describe only the invalid field class and never include signing key
// material.
func LoadSessionAuth(getenv func(string) string) (SessionAuth, error) {
	if getenv == nil {
		getenv = os.Getenv
	}

	rawEnabled := strings.TrimSpace(getenv("TRANSLATION_SESSION_AUTH_ENABLED"))
	if rawEnabled != "" && rawEnabled != "false" && rawEnabled != "true" {
		return SessionAuth{}, errors.New("invalid translation session authorization enabled flag")
	}
	environment, err := LoadEnvironment(getenv)
	if err != nil {
		return SessionAuth{}, errors.New("invalid agent environment")
	}
	if rawEnabled == "" || rawEnabled == "false" {
		if environment == "production" {
			return SessionAuth{}, errors.New("translation session authorization must be enabled in production")
		}
		return SessionAuth{}, nil
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

	cloudURL := strings.TrimSpace(getenv("TRANSLATION_SESSION_CLOUD_AUTHORIZE_URL"))
	cloudToken := strings.TrimSpace(getenv("TRANSLATION_SESSION_CLOUD_SERVICE_TOKEN"))
	if (cloudURL == "") != (cloudToken == "") {
		return SessionAuth{}, errors.New("incomplete translation session cloud authorization configuration")
	}
	if cloudURL != "" {
		parsed, err := url.ParseRequestURI(cloudURL)
		if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" ||
			parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
			return SessionAuth{}, errors.New("invalid translation session cloud authorization endpoint")
		}
		if len(cloudToken) < 32 {
			return SessionAuth{}, errors.New("incomplete translation session cloud authorization configuration")
		}
		interval, err := boundedDuration(getenv("TRANSLATION_SESSION_REAUTH_INTERVAL_SECONDS"), defaultReauthInterval, maxReauthInterval)
		if err != nil {
			return SessionAuth{}, errors.New("invalid translation session reauthorization interval")
		}
		timeout, err := boundedDuration(getenv("TRANSLATION_SESSION_REAUTH_TIMEOUT_SECONDS"), defaultReauthTimeout, maxReauthTimeout)
		if err != nil {
			return SessionAuth{}, errors.New("invalid translation session reauthorization timeout")
		}
		tolerance, err := boundedDuration(getenv("TRANSLATION_SESSION_REAUTH_TOLERANCE_SECONDS"), defaultReauthTolerance, maxReauthTolerance)
		if err != nil {
			return SessionAuth{}, errors.New("invalid translation session reauthorization tolerance")
		}
		if tolerance < interval || interval+timeout+tolerance > maxReauthBudget {
			return SessionAuth{}, errors.New("invalid translation session reauthorization timing")
		}
		cfg.CloudAuthorizeURL = cloudURL
		cfg.CloudServiceToken = cloudToken
		cfg.ReauthInterval = interval
		cfg.ReauthTimeout = timeout
		cfg.ReauthTolerance = tolerance
	} else if environment == "production" {
		return SessionAuth{}, errors.New("translation session cloud authorization is required in production")
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

// boundedDuration accepts positive millisecond-granular durations up to max.
// Sub-second values keep governance termination inside its deadline.
func boundedDuration(raw string, fallback, max time.Duration) (time.Duration, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return fallback, nil
	}
	milliseconds, err := strconv.ParseInt(trimmed, 10, 64)
	if err != nil || milliseconds <= 0 || time.Duration(milliseconds)*time.Millisecond > max {
		return 0, errors.New("invalid duration")
	}
	return time.Duration(milliseconds) * time.Millisecond, nil
}

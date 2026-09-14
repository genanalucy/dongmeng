package config

import (
	"strings"
	"testing"
	"time"
)

func TestLoadNewAPIKey(t *testing.T) {
	cfg, err := Load(func(name string) string {
		if name == "VOLCENGINE_API_KEY" {
			return "test-key-not-a-real-secret"
		}
		return ""
	})
	if err != nil || cfg.APIKey == "" || cfg.ResourceID != DefaultResourceID {
		t.Fatalf("Load() = %#v, %v", cfg, err)
	}
}

func TestLoadLegacyCredentials(t *testing.T) {
	cfg, err := Load(func(name string) string {
		return map[string]string{
			"VOLCENGINE_APP_ID":       "test-app",
			"VOLCENGINE_ACCESS_TOKEN": "test-token",
		}[name]
	})
	if err != nil || cfg.AppID == "" || cfg.AccessToken == "" {
		t.Fatalf("Load() = %#v, %v", cfg, err)
	}
}

func TestLoadSessionAuthDefaultsToDisabledWithoutReadingTrustInputs(t *testing.T) {
	var requested []string
	cfg, err := LoadSessionAuth(func(name string) string {
		requested = append(requested, name)
		return ""
	})
	if err != nil || cfg.Enabled {
		t.Fatalf("LoadSessionAuth() = %#v, %v", cfg, err)
	}
	// The disabled path reads only the enabled flag and the environment; it
	// never reads key, issuer, audience, or cloud trust inputs.
	if len(requested) != 2 || requested[0] != "TRANSLATION_SESSION_AUTH_ENABLED" || requested[1] != "AGENT_ENV" {
		t.Fatalf("disabled config requested unexpected inputs: %v", requested)
	}
}

func TestLoadSessionAuthExplicitFalseDoesNotReadTrustInputs(t *testing.T) {
	var requested []string
	cfg, err := LoadSessionAuth(func(name string) string {
		requested = append(requested, name)
		if name == "TRANSLATION_SESSION_AUTH_ENABLED" {
			return "  false  "
		}
		if name == "AGENT_ENV" {
			return ""
		}
		t.Fatal("disabled session auth read trust input " + name)
		return ""
	})
	if err != nil || cfg.Enabled || len(cfg.HMACKey) != 0 || cfg.Issuer != "" || cfg.Audience != "" || cfg.ClockSkew != 0 || cfg.MaxLifetime != 0 {
		t.Fatalf("LoadSessionAuth() = %#v, %v", cfg, err)
	}
	if len(requested) != 2 || requested[0] != "TRANSLATION_SESSION_AUTH_ENABLED" || requested[1] != "AGENT_ENV" {
		t.Fatalf("disabled config requested unexpected inputs: %v", requested)
	}
}

func TestLoadSessionAuthEnabledUsesDocumentedDefaults(t *testing.T) {
	values := map[string]string{
		"TRANSLATION_SESSION_AUTH_ENABLED": "  true  ",
		"TRANSLATION_SESSION_HS256_KEY":    "0123456789abcdef0123456789abcdef",
		"TRANSLATION_SESSION_ISSUER":       "cloud-api",
		"TRANSLATION_SESSION_AUDIENCE":     "translator-agent",
	}
	cfg, err := LoadSessionAuth(func(name string) string { return values[name] })
	if err != nil {
		t.Fatalf("LoadSessionAuth() error = %v", err)
	}
	if !cfg.Enabled || cfg.ClockSkew != 30*time.Second || cfg.MaxLifetime != 5*time.Minute {
		t.Fatalf("LoadSessionAuth() defaults = %#v", cfg)
	}
}

func TestLoadSessionAuthEnabled(t *testing.T) {
	values := map[string]string{
		"TRANSLATION_SESSION_AUTH_ENABLED":         "true",
		"TRANSLATION_SESSION_HS256_KEY":            "0123456789abcdef0123456789abcdef",
		"TRANSLATION_SESSION_ISSUER":               "cloud-api",
		"TRANSLATION_SESSION_AUDIENCE":             "translator-agent",
		"TRANSLATION_SESSION_CLOCK_SKEW_SECONDS":   "15",
		"TRANSLATION_SESSION_MAX_LIFETIME_SECONDS": "120",
	}
	cfg, err := LoadSessionAuth(func(name string) string { return values[name] })
	if err != nil {
		t.Fatalf("LoadSessionAuth() error = %v", err)
	}
	if !cfg.Enabled || cfg.Issuer != "cloud-api" || cfg.Audience != "translator-agent" ||
		cfg.ClockSkew != 15*time.Second || cfg.MaxLifetime != 2*time.Minute || len(cfg.HMACKey) != 32 {
		t.Fatalf("LoadSessionAuth() = %#v", cfg)
	}
}

func TestLoadSessionAuthAllowsZeroClockSkew(t *testing.T) {
	values := map[string]string{
		"TRANSLATION_SESSION_AUTH_ENABLED":       "true",
		"TRANSLATION_SESSION_HS256_KEY":          "0123456789abcdef0123456789abcdef",
		"TRANSLATION_SESSION_ISSUER":             "cloud-api",
		"TRANSLATION_SESSION_AUDIENCE":           "translator-agent",
		"TRANSLATION_SESSION_CLOCK_SKEW_SECONDS": "0",
	}
	cfg, err := LoadSessionAuth(func(name string) string { return values[name] })
	if err != nil {
		t.Fatalf("LoadSessionAuth() error = %v", err)
	}
	if cfg.ClockSkew != 0 {
		t.Fatalf("ClockSkew = %s, want 0", cfg.ClockSkew)
	}
}

func TestLoadSessionAuthRejectsInvalidConfigurationWithoutLeakingKey(t *testing.T) {
	const marker = "not-a-real-session-key"
	tests := []map[string]string{
		{"TRANSLATION_SESSION_AUTH_ENABLED": "yes"},
		{"TRANSLATION_SESSION_AUTH_ENABLED": "true", "TRANSLATION_SESSION_HS256_KEY": marker},
		{"TRANSLATION_SESSION_AUTH_ENABLED": "true", "TRANSLATION_SESSION_HS256_KEY": strings.Repeat("x", 32), "TRANSLATION_SESSION_ISSUER": " cloud-api", "TRANSLATION_SESSION_AUDIENCE": "agent"},
		{"TRANSLATION_SESSION_AUTH_ENABLED": "true", "TRANSLATION_SESSION_HS256_KEY": strings.Repeat("x", 32), "TRANSLATION_SESSION_ISSUER": "cloud-api", "TRANSLATION_SESSION_AUDIENCE": "agent", "TRANSLATION_SESSION_CLOCK_SKEW_SECONDS": "-1"},
		{"TRANSLATION_SESSION_AUTH_ENABLED": "true", "TRANSLATION_SESSION_HS256_KEY": strings.Repeat("x", 32), "TRANSLATION_SESSION_ISSUER": "cloud-api", "TRANSLATION_SESSION_AUDIENCE": "agent", "TRANSLATION_SESSION_MAX_LIFETIME_SECONDS": "0"},
		{"TRANSLATION_SESSION_AUTH_ENABLED": "true", "TRANSLATION_SESSION_HS256_KEY": strings.Repeat("x", 32), "TRANSLATION_SESSION_ISSUER": "cloud-api", "TRANSLATION_SESSION_AUDIENCE": "agent", "TRANSLATION_SESSION_MAX_LIFETIME_SECONDS": "9223372037"},
	}
	for _, values := range tests {
		_, err := LoadSessionAuth(func(name string) string { return values[name] })
		if err == nil {
			t.Fatalf("LoadSessionAuth(%v) succeeded", values)
		}
		if strings.Contains(err.Error(), marker) || strings.Contains(err.Error(), strings.Repeat("x", 32)) {
			t.Fatalf("error leaked session key: %q", err)
		}
	}
}

func TestLoadRejectsInvalidCredentialCombinations(t *testing.T) {
	tests := []map[string]string{
		{},
		{"VOLCENGINE_APP_ID": "test-app"},
		{"VOLCENGINE_ACCESS_TOKEN": "test-token"},
		{"VOLCENGINE_API_KEY": "test-key", "VOLCENGINE_APP_ID": "test-app", "VOLCENGINE_ACCESS_TOKEN": "test-token"},
	}
	for _, values := range tests {
		_, err := Load(func(name string) string { return values[name] })
		if err == nil {
			t.Fatalf("Load(%v) succeeded", values)
		}
		if got := err.Error(); got == "" || got == values["VOLCENGINE_API_KEY"] || got == values["VOLCENGINE_ACCESS_TOKEN"] {
			t.Fatalf("error leaks or omits a safe explanation: %q", got)
		}
	}
}

func TestLoadSessionAuthCloudAuthorizationConfiguration(t *testing.T) {
	base := map[string]string{
		"TRANSLATION_SESSION_AUTH_ENABLED": "true",
		"TRANSLATION_SESSION_HS256_KEY":    "0123456789abcdef0123456789abcdef",
		"TRANSLATION_SESSION_ISSUER":       "cloud-api",
		"TRANSLATION_SESSION_AUDIENCE":     "translator-agent",
	}
	withCloud := func(extra map[string]string) map[string]string {
		values := make(map[string]string, len(base)+len(extra)+2)
		for key, value := range base {
			values[key] = value
		}
		for key, value := range extra {
			values[key] = value
		}
		if _, exists := values["TRANSLATION_SESSION_CLOUD_AUTHORIZE_URL"]; !exists {
			values["TRANSLATION_SESSION_CLOUD_AUTHORIZE_URL"] = "http://127.0.0.1:8080/internal/v1/agent/translation-sessions/authorize"
		}
		if _, exists := values["TRANSLATION_SESSION_CLOUD_SERVICE_TOKEN"]; !exists {
			values["TRANSLATION_SESSION_CLOUD_SERVICE_TOKEN"] = "cloud-service-token-0123456789abcdef"
		}
		return values
	}

	t.Run("defaults keep development local-only", func(t *testing.T) {
		cfg, err := LoadSessionAuth(func(name string) string { return base[name] })
		if err != nil || !cfg.Enabled || cfg.CloudAuthorizeURL != "" || cfg.CloudServiceToken != "" {
			t.Fatalf("LoadSessionAuth() = %#v, %v", cfg, err)
		}
	})

	t.Run("cloud authorization uses safe documented defaults", func(t *testing.T) {
		cfg, err := LoadSessionAuth(func(name string) string { return withCloud(nil)[name] })
		if err != nil {
			t.Fatalf("LoadSessionAuth() error = %v", err)
		}
		if cfg.CloudAuthorizeURL == "" || cfg.CloudServiceToken == "" ||
			cfg.ReauthInterval != time.Second || cfg.ReauthTimeout != 750*time.Millisecond || cfg.ReauthTolerance != 2*time.Second {
			t.Fatalf("LoadSessionAuth() cloud defaults = %#v", cfg)
		}
	})

	t.Run("partial cloud configuration is rejected", func(t *testing.T) {
		onlyURL := make(map[string]string, len(base)+1)
		for key, value := range base {
			onlyURL[key] = value
		}
		onlyURL["TRANSLATION_SESSION_CLOUD_AUTHORIZE_URL"] = "http://127.0.0.1:8080/internal"
		if _, err := LoadSessionAuth(func(name string) string { return onlyURL[name] }); err == nil {
			t.Fatal("URL without service token was accepted")
		}
	})

	t.Run("short service token is rejected", func(t *testing.T) {
		weak := withCloud(map[string]string{"TRANSLATION_SESSION_CLOUD_SERVICE_TOKEN": "short"})
		if _, err := LoadSessionAuth(func(name string) string { return weak[name] }); err == nil {
			t.Fatal("short service token was accepted")
		}
	})

	t.Run("unsafe timing budgets are rejected", func(t *testing.T) {
		for _, extra := range []map[string]string{
			{"TRANSLATION_SESSION_REAUTH_INTERVAL_SECONDS": "2000"},
			{"TRANSLATION_SESSION_REAUTH_TIMEOUT_SECONDS": "0"},
			{"TRANSLATION_SESSION_REAUTH_TOLERANCE_SECONDS": "5000"},
			{"TRANSLATION_SESSION_REAUTH_TOLERANCE_SECONDS": "1"},
			{"TRANSLATION_SESSION_REAUTH_INTERVAL_SECONDS": "1000", "TRANSLATION_SESSION_REAUTH_TIMEOUT_SECONDS": "1500"},
		} {
			values := withCloud(extra)
			if _, err := LoadSessionAuth(func(name string) string { return values[name] }); err == nil {
				t.Fatalf("unsafe timing %v was accepted", extra)
			}
		}
	})

	t.Run("production requires cloud authorization", func(t *testing.T) {
		if _, err := LoadSessionAuth(func(name string) string {
			if name == "AGENT_ENV" {
				return "production"
			}
			return base[name]
		}); err == nil {
			t.Fatal("production without cloud authorization was accepted")
		}
	})

	t.Run("production rejects disabled authorization", func(t *testing.T) {
		if _, err := LoadSessionAuth(func(name string) string {
			switch name {
			case "AGENT_ENV":
				return "production"
			case "TRANSLATION_SESSION_AUTH_ENABLED":
				return "false"
			}
			return ""
		}); err == nil {
			t.Fatal("production with disabled authorization was accepted")
		}
	})

	t.Run("production accepts complete configuration", func(t *testing.T) {
		values := withCloud(nil)
		cfg, err := LoadSessionAuth(func(name string) string {
			if name == "AGENT_ENV" {
				return "production"
			}
			return values[name]
		})
		if err != nil || !cfg.Enabled || cfg.CloudAuthorizeURL == "" {
			t.Fatalf("LoadSessionAuth() = %#v, %v", cfg, err)
		}
	})
}

func TestLoadEnvironment(t *testing.T) {
	values := map[string]string{"AGENT_ENV": " production "}
	environment, err := LoadEnvironment(func(name string) string { return values[name] })
	if err != nil || environment != "production" {
		t.Fatalf("LoadEnvironment() = %q, %v", environment, err)
	}
	if _, err := LoadEnvironment(func(string) string { return "prod" }); err == nil {
		t.Fatal("invalid environment was accepted")
	}
}

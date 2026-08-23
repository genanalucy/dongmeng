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
	if len(requested) != 1 || requested[0] != "TRANSLATION_SESSION_AUTH_ENABLED" {
		t.Fatalf("disabled config requested unexpected inputs: %v", requested)
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

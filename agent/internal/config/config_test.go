package config

import "testing"

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

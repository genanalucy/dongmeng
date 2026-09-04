package config

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"strings"
	"testing"
)

// randomHistoryRootKey generates fresh key material for each test so no
// fixture value is ever logged, committed, or reused.
func randomHistoryRootKey(t *testing.T) string {
	t.Helper()
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		t.Fatalf("generate history root key: %v", err)
	}
	return base64.StdEncoding.EncodeToString(key)
}

func enableHistoryEnvironment(t *testing.T, rootKey, version string) {
	t.Helper()
	setRequiredEnvironment(t)
	t.Setenv("HISTORY_ENABLED", "true")
	t.Setenv("HISTORY_ROOT_KEY", rootKey)
	t.Setenv("HISTORY_KEY_VERSION", version)
}

func TestLoadAcceptsEnabledHistoryWithSafeKeyMaterial(t *testing.T) {
	rootKey := randomHistoryRootKey(t)
	enableHistoryEnvironment(t, rootKey, "2")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if !cfg.HistoryEnabled {
		t.Fatal("HistoryEnabled = false, want true")
	}
	if cfg.HistoryKeyVersion != 2 {
		t.Fatalf("HistoryKeyVersion = %d, want 2", cfg.HistoryKeyVersion)
	}
	if len(cfg.HistoryRootKey) != 32 {
		t.Fatalf("HistoryRootKey decoded length = %d, want 32", len(cfg.HistoryRootKey))
	}
	if encoded := base64.StdEncoding.EncodeToString(cfg.HistoryRootKey); encoded != rootKey {
		t.Fatal("HistoryRootKey did not round trip from base64")
	}
}

func TestLoadKeepsHistoryDisabledWithoutKeyMaterial(t *testing.T) {
	setRequiredEnvironment(t)
	t.Setenv("HISTORY_ENABLED", "false")
	t.Setenv("HISTORY_ROOT_KEY", "")
	t.Setenv("HISTORY_KEY_VERSION", "")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.HistoryEnabled || cfg.HistoryRootKey != nil || cfg.HistoryKeyVersion != 0 {
		t.Fatalf("disabled history must not require key material: %+v", cfg.HistoryEnabled)
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
}

func TestLoadFailsClosedWhenEnabledHistoryKeyMaterialIsWeak(t *testing.T) {
	weakKeys := map[string]string{
		"not base64":         "!!!not-base64!!!",
		"empty after decode": strings.Repeat("A", 4), // decodes to 3 zero bytes
		"too short":          base64.StdEncoding.EncodeToString(make([]byte, 31)),
		"uniform bytes":      base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{0x71}, 32)),
		"repeated two bytes": base64.StdEncoding.EncodeToString(repeatPattern("ab", 32)),
	}
	for name, key := range weakKeys {
		t.Run(name, func(t *testing.T) {
			enableHistoryEnvironment(t, key, "1")
			_, err := Load()
			if err == nil {
				t.Fatal("Load() error = nil, want fail-closed rejection")
			}
			if !strings.Contains(err.Error(), "HISTORY_ROOT_KEY") {
				t.Fatalf("Load() error = %v, want HISTORY_ROOT_KEY problem", err)
			}
			if strings.Contains(err.Error(), key) {
				t.Fatal("Load() error echoed key material")
			}
		})
	}
}

func repeatPattern(pattern string, total int) []byte {
	out := make([]byte, 0, total)
	for len(out) < total {
		out = append(out, pattern...)
	}
	return out[:total]
}

func TestLoadFailsClosedWhenEnabledHistoryKeyVersionIsInvalid(t *testing.T) {
	tests := []struct {
		name     string
		version  string
		segments []string
	}{
		{name: "missing version", version: "", segments: []string{"HISTORY_KEY_VERSION"}},
		{name: "zero version", version: "0", segments: []string{"HISTORY_KEY_VERSION"}},
		{name: "negative version", version: "-1", segments: []string{"HISTORY_KEY_VERSION"}},
		{name: "non numeric version", version: "one", segments: []string{"HISTORY_KEY_VERSION"}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			enableHistoryEnvironment(t, randomHistoryRootKey(t), test.version)
			_, err := Load()
			if err == nil {
				t.Fatal("Load() error = nil, want fail-closed rejection")
			}
			for _, segment := range test.segments {
				if !strings.Contains(err.Error(), segment) {
					t.Fatalf("Load() error = %v, want %q", err, segment)
				}
			}
		})
	}
}

func TestValidateRejectsEnabledHistoryConfigWithMissingKeyWithoutLeakingIt(t *testing.T) {
	cfg := validConfig()
	cfg.HistoryEnabled = true
	cfg.HistoryRootKey = nil
	cfg.HistoryKeyVersion = 0

	err := cfg.Validate()
	if err == nil {
		t.Fatal("Validate() error = nil, want fail-closed rejection")
	}
	if !strings.Contains(err.Error(), "HISTORY_ROOT_KEY") || !strings.Contains(err.Error(), "HISTORY_KEY_VERSION") {
		t.Fatalf("Validate() error = %v, want both history problems", err)
	}
}

func TestValidateRejectsMalformedHistoryRootKeyEncoding(t *testing.T) {
	setRequiredEnvironment(t)
	t.Setenv("HISTORY_ENABLED", "false")
	t.Setenv("HISTORY_ROOT_KEY", "not!base64!")

	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "HISTORY_ROOT_KEY") {
		t.Fatalf("Load() error = %v, want HISTORY_ROOT_KEY decoding rejection", err)
	}
}

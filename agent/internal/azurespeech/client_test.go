package azurespeech

import (
	"context"
	"errors"
	"testing"

	"translator-agent/internal/ast"
)

func TestNewFailsClosedWhenCredentialsAreIncomplete(t *testing.T) {
	for _, cfg := range []Config{{}, {Key: "key"}, {Region: "japaneast"}} {
		_, err := New(cfg).Start(context.Background(), ast.StartRequest{}, nil)
		if !errors.Is(err, ErrUnavailable) {
			t.Fatalf("New(%#v).Start() error = %v, want unavailable", cfg, err)
		}
	}
}

func TestNewConfiguredReturnsStubSession(t *testing.T) {
	session, err := New(Config{Key: "key", Region: "japaneast"}).Start(context.Background(), ast.StartRequest{}, nil)
	if err != nil || session == nil {
		t.Fatalf("configured Start() = (%#v, %v)", session, err)
	}
}

func TestVoiceAllowlistUsesTargetLanguageLocale(t *testing.T) {
	for _, testCase := range []struct {
		language string
		voice    string
	}{
		{"zh", "zh-CN-XiaoxiaoNeural"},
		{"en", "en-US-GuyNeural"},
		{"fr", "fr-FR-DeniseNeural"},
		{"vi", "vi-VN-NamMaleNeural"},
	} {
		if !IsVoiceAllowed(testCase.voice, []string{testCase.language}) {
			t.Fatalf("voice %q was rejected for %q", testCase.voice, testCase.language)
		}
	}
	if IsVoiceAllowed("en-US-JennyNeural", []string{"zh"}) {
		t.Fatal("cross-language voice was accepted")
	}
}

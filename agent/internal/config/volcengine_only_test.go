package config

import "testing"

func TestQwenCredentialsCannotReplaceRequiredVolcengineAuthentication(t *testing.T) {
	values := map[string]string{"DASHSCOPE_API_KEY": "test-only", "QWEN_API_HOST": "must-not-connect.invalid"}
	if _, err := Load(func(k string) string { return values[k] }); err == nil {
		t.Fatal("Qwen-only configuration accepted")
	}
}

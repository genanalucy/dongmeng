package config

import "testing"

func TestQwenRequiresExplicitProviderAndCompleteConfiguration(t *testing.T) {
	values := map[string]string{"TRANSLATION_PROVIDER": "qwen", "DASHSCOPE_API_KEY": "test-key", "QWEN_API_HOST": "workspace.cn-beijing.maas.aliyuncs.com"}
	c, err := Load(func(k string) string { return values[k] })
	if err != nil || c.TranslationProvider != "qwen" {
		t.Fatal("explicit Qwen configuration rejected")
	}
	for _, host := range []string{"", "host/path", "user:secret@host", "host?key=value"} {
		values["QWEN_API_HOST"] = host
		if _, err := Load(func(k string) string { return values[k] }); err == nil {
			t.Fatal("unsafe Qwen endpoint accepted")
		}
	}
}

// Package azurespeech provides the Azure Speech translation client boundary.
package azurespeech

import (
	"context"
	"translator-agent/internal/ast"
)

var ErrUnavailable = ast.ErrProviderUnavailable

// Config contains Azure Speech credentials loaded by the embedding application.
type Config struct {
	Key    string
	Region string
}

// LocaleForLanguage maps protocol language codes to Azure Speech locales.
func LocaleForLanguage(language string) (string, bool) {
	locale, ok := map[string]string{
		"zh": "zh-CN",
		"en": "en-US",
		"fr": "fr-FR",
		"vi": "vi-VN",
	}[language]
	return locale, ok
}

// IsVoiceAllowed verifies a voice against the requested target-language candidates.
func IsVoiceAllowed(voice string, candidateLanguages []string) bool {
	for _, language := range candidateLanguages {
		locale, ok := LocaleForLanguage(language)
		if !ok {
			continue
		}
		for _, allowed := range voicesByLocale[locale] {
			if voice == allowed {
				return true
			}
		}
	}
	return false
}

var voicesByLocale = map[string][]string{
	"zh-CN": {"zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural"},
	"en-US": {"en-US-JennyNeural", "en-US-GuyNeural"},
	"fr-FR": {"fr-FR-DeniseNeural", "fr-FR-HenriNeural"},
	"vi-VN": {"vi-VN-HoaiMyNeural", "vi-VN-NamMaleNeural"},
}

type client struct{ configured bool }

// New returns a fail-closed Azure client stub. The real transport is added in work order 02.
func New(cfg Config) ast.Client {
	return client{configured: cfg.Key != "" && cfg.Region != ""}
}

func (c client) Start(context.Context, ast.StartRequest, ast.EventSink) (ast.Session, error) {
	if !c.configured {
		return nil, ErrUnavailable
	}
	return stubSession{}, nil
}

type stubSession struct{}

func (stubSession) SendAudio(context.Context, []byte) error { return nil }
func (stubSession) Finish(context.Context) error            { return nil }
func (stubSession) Close() error                            { return nil }

//go:build smoketest

// azure-smoke exercises the real Azure speech translation pipeline end to
// end: synthesize a sentence with Azure TTS, feed it into the translation
// session, and print the event stream. Run on the server with
// AZURE_SPEECH_KEY/AZURE_SPEECH_REGION set.
package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"translator-agent/internal/ast"
	"translator-agent/internal/azurespeech"
)

type printSink struct {
	events chan ast.Event
}

func (s *printSink) Emit(e ast.Event) {
	if e.Type == "tts_audio" {
		fmt.Printf("  event=tts_audio bytes=%d\n", len(e.Binary))
	} else {
		fmt.Printf("  event=%s text=%q code=%s\n", e.Type, e.Message, e.Code)
	}
	if e.Type == "finished" || e.Type == "error" {
		select {
		case s.events <- e:
		default:
		}
	}
}

// synthesizeInput is a minimal standalone Azure TTS call producing 16k mono
// PCM16, used only to generate smoke-test input audio.
func synthesizeInput(ctx context.Context, region, key, locale, voice, text string) ([]byte, error) {
	ssml := fmt.Sprintf(
		"<speak version='1.0' xml:lang='%s'><voice name='%s'>%s</voice></speak>",
		locale, voice, strings.ReplaceAll(text, "&", "&amp;"))
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		fmt.Sprintf("https://%s.tts.speech.microsoft.com/cognitiveservices/v1", region),
		strings.NewReader(ssml))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Ocp-Apim-Subscription-Key", key)
	req.Header.Set("Content-Type", "application/ssml+xml")
	req.Header.Set("X-Microsoft-OutputFormat", "raw-16khz-16bit-mono-pcm")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("tts status %d: %.120s", resp.StatusCode, body)
	}
	return body, nil
}

func main() {
	region := os.Getenv("AZURE_SPEECH_REGION")
	key := os.Getenv("AZURE_SPEECH_KEY")
	if region == "" || key == "" {
		fmt.Println("missing AZURE_SPEECH_REGION/AZURE_SPEECH_KEY")
		os.Exit(1)
	}
	inputVoice := map[string]string{"zh": "zh-CN-YunxiNeural", "en": "en-US-GuyNeural", "vi": "vi-VN-NamMaleNeural"}
	pairs := [][3]string{
		{"zh", "vi", "今天天气很好，我们一起去学校吧。"},
		{"en", "vi", "I am going to school to study English today."},
		{"zh", "en", "欢迎使用实时翻译系统。"},
	}
	failures := 0
	for _, pair := range pairs {
		from, to, sentence := pair[0], pair[1], pair[2]
		fromLocale, _ := azurespeech.LocaleForLanguage(from)
		fmt.Printf("=== %s -> %s: %s ===\n", from, to, sentence)
		client := azurespeech.New(azurespeech.Config{Key: key, Region: region})
		sink := &printSink{events: make(chan ast.Event, 4)}
		ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
		session, err := client.Start(ctx, ast.StartRequest{SourceLanguage: from, TargetLanguage: to}, sink)
		if err != nil {
			fmt.Printf("  START FAILED: %v\n", err)
			failures++
			cancel()
			continue
		}
		pcm, err := synthesizeInput(ctx, region, key, fromLocale, inputVoice[from], sentence)
		if err != nil {
			fmt.Printf("  INPUT TTS FAILED: %v\n", err)
			failures++
			session.Close()
			cancel()
			continue
		}
		fmt.Printf("  input audio: %d bytes\n", len(pcm))
		go func() {
			const chunk = 2560
			for offset := 0; offset < len(pcm); offset += chunk {
				end := offset + chunk
				if end > len(pcm) {
					end = len(pcm)
				}
				if err := session.SendAudio(ctx, pcm[offset:end]); err != nil {
					fmt.Printf("  send failed: %v\n", err)
					return
				}
				time.Sleep(40 * time.Millisecond)
			}
			if err := session.Finish(ctx); err != nil {
				fmt.Printf("  finish failed: %v\n", err)
			}
		}()
		sawFinal := false
		sawAudio := false
		terminal := (*ast.Event)(nil)
		for terminal == nil {
			select {
			case e := <-sink.events:
				if e.Type == "source_final" || e.Type == "translation_final" {
					sawFinal = true
				}
				if e.Type == "tts_audio" {
					sawAudio = true
				}
				if e.Type == "finished" || e.Type == "error" {
					terminal = &e
				}
			case <-ctx.Done():
				fmt.Println("  PAIR FAILED: timeout waiting for terminal event")
				failures++
				terminal = &ast.Event{Type: "timeout"}
			}
		}
		if terminal.Type != "finished" || !sawFinal || !sawAudio {
			fmt.Printf("  PAIR FAILED: terminal=%s sawFinal=%v sawAudio=%v\n", terminal.Type, sawFinal, sawAudio)
			failures++
		}
		session.Close()
		cancel()
	}
	if failures > 0 {
		fmt.Printf("SMOKE RESULT: %d checks failed\n", failures)
		os.Exit(1)
	}
	fmt.Println("SMOKE RESULT: all pairs passed")
}

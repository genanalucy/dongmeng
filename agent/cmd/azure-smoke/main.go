//go:build smoketest

// azure-smoke exercises the real Azure Speech client against credentials loaded
// from its environment. It intentionally prints only fixed, non-sensitive
// diagnostic categories so it is safe to run on a production host.
package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"translator-agent/internal/ast"
	"translator-agent/internal/azurespeech"
)

const (
	attemptTimeout = 25 * time.Second
	inputText      = "今天天气很好，我们一起去学校吧。"
)

type eventSink struct{ events chan ast.Event }

func (s *eventSink) Emit(event ast.Event) {
	select {
	case s.events <- event:
	default:
	}
}

// synthesizeInput produces valid PCM16 input without exposing request or
// response contents if Azure TTS rejects the request.
func synthesizeInput(ctx context.Context, region, key string) ([]byte, error) {
	ssml := "<speak version='1.0' xml:lang='zh-CN'><voice name='zh-CN-YunxiNeural'>" + inputText + "</voice></speak>"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		"https://"+region+".tts.speech.microsoft.com/cognitiveservices/v1", strings.NewReader(ssml))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Ocp-Apim-Subscription-Key", key)
	req.Header.Set("Content-Type", "application/ssml+xml")
	req.Header.Set("X-Microsoft-OutputFormat", "raw-16khz-16bit-mono-pcm")
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	pcm, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, err
	}
	if response.StatusCode != http.StatusOK || len(pcm) == 0 || len(pcm)%2 != 0 {
		return nil, fmt.Errorf("Azure TTS rejected or returned invalid PCM")
	}
	return pcm, nil
}

func runAttempt(key, region, mode string, attempt int) string {
	ctx, cancel := context.WithTimeout(context.Background(), attemptTimeout)
	defer cancel()

	request := ast.StartRequest{
		SourceLanguage: "zh", TargetLanguage: "en", TargetAudioFormat: "pcm", TargetAudioRate: 16000,
	}
	if mode == "automatic" {
		request.CandidateLanguages = []string{"zh", "en"}
	}
	sink := &eventSink{events: make(chan ast.Event, 16)}
	session, err := azurespeech.New(azurespeech.Config{Key: key, Region: region}).Start(ctx, request, sink)
	if err != nil {
		return "start_" + safeDiagnostic(ast.ErrorDiagnostic(err))
	}
	defer session.Close()

	pcm, err := synthesizeInput(ctx, region, key)
	if err != nil {
		return "input_tts_failed"
	}
	for offset := 0; offset < len(pcm); offset += 2560 {
		end := offset + 2560
		if end > len(pcm) {
			end = len(pcm)
		}
		if err := session.SendAudio(ctx, pcm[offset:end]); err != nil {
			return terminalResult(sink.events, mode, attempt, "send_"+safeDiagnostic(ast.ErrorDiagnostic(err)))
		}
		time.Sleep(40 * time.Millisecond)
	}
	if err := session.Finish(ctx); err != nil {
		return terminalResult(sink.events, mode, attempt, "finish_"+safeDiagnostic(ast.ErrorDiagnostic(err)))
	}
	for {
		select {
		case event := <-sink.events:
			fmt.Printf("EVENT mode=%s number=%d class=%s\n", mode, attempt, event.Type)
			switch event.Type {
			case "error":
				return "event_" + safeDiagnostic(event.Diagnostic)
			case "finished":
				return "finished"
			}
		case <-ctx.Done():
			return "wait_net_timeout"
		}
	}
}

// terminalResult gives the reader goroutine a bounded chance to publish its
// safe event diagnostic when a concurrent writer observes cancellation first.
func terminalResult(events <-chan ast.Event, mode string, attempt int, fallback string) string {
	timer := time.NewTimer(time.Second)
	defer timer.Stop()
	for {
		select {
		case event := <-events:
			fmt.Printf("EVENT mode=%s number=%d class=%s\n", mode, attempt, event.Type)
			if event.Type == "error" {
				return "event_" + safeDiagnostic(event.Diagnostic)
			}
			if event.Type == "finished" {
				return "finished"
			}
		case <-timer.C:
			return fallback
		}
	}
}

func safeDiagnostic(value string) string {
	if value == "" {
		return "unclassified"
	}
	// Diagnostics are produced by azurespeech and use fixed ASCII categories.
	// Avoid printing an unexpected value if a future error path regresses.
	for _, character := range value {
		if (character < 'a' || character > 'z') && (character < '0' || character > '9') && character != '_' {
			return "unclassified"
		}
	}
	return value
}

func main() {
	attempts := flag.Int("attempts", 3, "attempts per mode")
	flag.Parse()
	if *attempts < 1 || *attempts > 10 {
		fmt.Println("RESULT invalid_attempt_count")
		os.Exit(2)
	}
	region, key := os.Getenv("AZURE_SPEECH_REGION"), os.Getenv("AZURE_SPEECH_KEY")
	if region == "" || key == "" {
		fmt.Println("RESULT missing_azure_configuration")
		os.Exit(2)
	}

	failures := 0
	for _, mode := range []string{"automatic", "legacy"} {
		for attempt := 1; attempt <= *attempts; attempt++ {
			result := runAttempt(key, region, mode, attempt)
			fmt.Printf("ATTEMPT mode=%s number=%d result=%s\n", mode, attempt, result)
			if result != "finished" {
				failures++
			}
		}
	}
	fmt.Printf("RESULT failures=%d attempts=%d\n", failures, *attempts*2)
	if failures != 0 {
		os.Exit(1)
	}
}

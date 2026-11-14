package ast

import (
	"context"
	"errors"
	"testing"
	"translator-agent/internal/config"
)

type recordingClient struct{ starts int }

func (c *recordingClient) Start(context.Context, StartRequest, EventSink) (Session, error) {
	c.starts++
	return &testSession{}, nil
}

type testSession struct{}

func (*testSession) SendAudio(context.Context, []byte) error { return nil }
func (*testSession) Finish(context.Context) error            { return nil }
func (*testSession) Close() error                            { return nil }

type discardSink struct{}

func (discardSink) Emit(Event) {}

func TestRoutingClientUsesVolcengineForEveryShippedPairEvenWithQwenConfigured(t *testing.T) {
	volc := &recordingClient{}
	router := NewRoutingClient(volc, config.Config{DashScopeAPIKey: "unused-test-key", QwenAPIHost: "must-not-connect.invalid"})
	for _, request := range []StartRequest{
		{SourceLanguage: "zh", TargetLanguage: "en"},
		{SourceLanguage: "zh", TargetLanguage: "vi"},
		{SourceLanguage: "vi", TargetLanguage: "zh"},
		{SourceLanguage: "fr", TargetLanguage: "en"},
	} {
		if _, err := router.Start(context.Background(), request, discardSink{}); err != nil {
			t.Fatal(err)
		}
	}
	if volc.starts != 4 {
		t.Fatalf("Volcengine starts=%d, want 4", volc.starts)
	}
}

type failingVolcengine struct{}

func (failingVolcengine) Start(context.Context, StartRequest, EventSink) (Session, error) {
	return nil, ErrProviderUnavailable
}
func TestVolcengineFailureDoesNotFallBackToQwen(t *testing.T) {
	router := NewRoutingClient(failingVolcengine{}, config.Config{DashScopeAPIKey: "unused-test-key", QwenAPIHost: "must-not-connect.invalid"})
	_, err := router.Start(context.Background(), StartRequest{SourceLanguage: "vi", TargetLanguage: "zh"}, discardSink{})
	if !errors.Is(err, ErrProviderUnavailable) {
		t.Fatal("Volcengine failure was replaced by another provider")
	}
}

func TestResample24kTo16kPreservesExpectedSampleCount(t *testing.T) {
	input := make([]byte, 12) // six 16-bit samples at 24 kHz
	for i := range input {
		input[i] = byte(i)
	}
	output := resample24kTo16k(input)
	if len(output) != 8 {
		t.Fatalf("got %d bytes, want 8", len(output))
	}
}

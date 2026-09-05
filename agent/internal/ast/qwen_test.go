package ast

import (
	"context"
	"testing"
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

func TestRoutingClientUsesQwenForAllShippedPairs(t *testing.T) {
	volc, qwen := &recordingClient{}, &recordingClient{}
	router := routingClient{volc: volc, qwen: qwen}
	for _, request := range []StartRequest{
		{SourceLanguage: "zh", TargetLanguage: "en"},
		{SourceLanguage: "zh", TargetLanguage: "vi"},
		{SourceLanguage: "fr", TargetLanguage: "en"},
	} {
		if _, err := router.Start(context.Background(), request, discardSink{}); err != nil {
			t.Fatal(err)
		}
	}
	if volc.starts != 0 || qwen.starts != 3 {
		t.Fatalf("volc=%d qwen=%d", volc.starts, qwen.starts)
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

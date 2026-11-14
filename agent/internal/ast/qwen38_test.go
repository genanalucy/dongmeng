package ast

import (
	"bytes"
	"context"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

type qwenTestSink struct{ events chan Event }

func (s qwenTestSink) Emit(e Event) { s.events <- e }
func configuredSession() map[string]any {
	return map[string]any{"type": "session.updated", "session": map[string]any{"model": qwenModel, "audio": map[string]any{"input": map[string]any{"format": map[string]any{"type": "pcm", "sample_rate": 16000}}, "output": map[string]any{"format": map[string]any{"type": "pcm", "sample_rate": 24000}}}}}
}

func TestQwen38WebSocketConfigurationDeltasAudioAndFinish(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("model") != qwenModel {
			t.Error("wrong model")
		}
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		defer conn.CloseNow()
		var update map[string]any
		if wsjson.Read(ctx, conn, &update) != nil {
			return
		}
		conf := update["session"].(map[string]any)
		if _, legacy := conf["modalities"]; legacy {
			t.Error("3.5 fields sent")
		}
		if _, legacy := conf["input_audio_transcription"]; legacy {
			t.Error("independent ASR configured")
		}
		if conf["enable_voice_clone"] != false {
			t.Error("voice cloning enabled")
		}
		if _, ok := conf["output_modalities"]; !ok {
			t.Error("3.8 output modalities missing")
		}
		_ = wsjson.Write(ctx, conn, configuredSession())
		var audio map[string]any
		if wsjson.Read(ctx, conn, &audio) != nil {
			return
		}
		if audio["type"] != "input_audio_buffer.append" {
			t.Error("audio append missing")
		}
		for _, event := range []map[string]any{
			{"type": "conversation.item.input_audio_transcription.delta", "item_id": "source1", "delta": "你"},
			{"type": "conversation.item.input_audio_transcription.delta", "item_id": "source1", "delta": "好"},
			{"type": "conversation.item.input_audio_transcription.completed", "item_id": "source1", "transcript": "你好"},
			{"type": "response.audio_transcript.delta", "item_id": "translated1", "delta": "Hel"},
			{"type": "response.audio_transcript.delta", "item_id": "translated1", "delta": "lo"},
			{"type": "response.audio_transcript.done", "item_id": "translated1", "transcript": "Hello"},
			{"type": "response.audio.delta", "delta": base64.StdEncoding.EncodeToString(make([]byte, 12))},
			{"type": "response.audio.done"},
			{"type": "response.done", "response": map[string]string{"status": "completed"}},
			{"type": "conversation.item.input_audio_transcription.delta", "item_id": "source2", "delta": "第二句"},
		} {
			if wsjson.Write(ctx, conn, event) != nil {
				return
			}
		}
		var finish map[string]any
		if wsjson.Read(ctx, conn, &finish) != nil {
			return
		}
		if finish["type"] != "session.finish" {
			t.Error("finish not sent")
		}
		_ = wsjson.Write(ctx, conn, map[string]string{"type": "session.finished"})
	}))
	defer server.Close()
	sink := qwenTestSink{make(chan Event, 32)}
	client := qwenClient{apiKey: "test-key", host: strings.TrimPrefix(server.URL, "https://"), httpClient: server.Client()}
	session, err := client.Start(ctx, StartRequest{TargetLanguage: "en"}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if err := session.SendAudio(ctx, make([]byte, 2560)); err != nil {
		t.Fatal(err)
	}
	var seen []Event
	for {
		select {
		case e := <-sink.events:
			seen = append(seen, e)
			if e.Type == "finished" {
				t.Fatal("response.done incorrectly ended session")
			}
			if e.Message == "第二句" {
				goto finish
			}
		case <-ctx.Done():
			t.Fatal("timed out before second utterance")
		}
	}
finish:
	if len(seen) != 8 || seen[1].Message != "你好" || seen[4].Message != "Hello" || seen[6].Type != "tts_audio" || len(seen[6].Binary) != 8 {
		t.Fatalf("unexpected mapped event sequence: %d", len(seen))
	}
	if err := session.Finish(ctx); err != nil {
		t.Fatal(err)
	}
	if err := session.Finish(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case e := <-sink.events:
		if e.Type != "finished" {
			t.Fatal("missing terminal finish")
		}
	case <-ctx.Done():
		t.Fatal("finish timeout")
	}
}

func TestQwen38UnexpectedCloseIsAnErrorAndClosesSession(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		defer conn.CloseNow()
		var update map[string]any
		_ = wsjson.Read(ctx, conn, &update)
		_ = wsjson.Write(ctx, conn, configuredSession())
	}))
	defer server.Close()
	sink := qwenTestSink{make(chan Event, 4)}
	client := qwenClient{apiKey: "test", host: strings.TrimPrefix(server.URL, "https://"), httpClient: server.Client()}
	s, err := client.Start(ctx, StartRequest{TargetLanguage: "vi"}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	select {
	case e := <-sink.events:
		if e.Code != "QWEN_CONNECTION_CLOSED" {
			t.Fatal("unexpected close not reported")
		}
	case <-ctx.Done():
		t.Fatal("close leaked")
	}
}

func TestQwen38ResamplingIsIndependentOfChunkBoundaries(t *testing.T) {
	input := make([]byte, 2400)
	for i := range input {
		input[i] = byte(i)
	}
	whole := resample24kTo16k(input)
	for _, size := range []int{1, 2, 7, 127, 1000} {
		var r pcm24To16
		var result []byte
		for i := 0; i < len(input); i += size {
			end := i + size
			if end > len(input) {
				end = len(input)
			}
			result = append(result, r.offer(input[i:end])...)
		}
		if !bytes.Equal(whole, result) {
			t.Fatalf("resampler changes at chunk size %d", size)
		}
	}
}

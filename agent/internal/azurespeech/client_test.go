package azurespeech

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"translator-agent/internal/ast"
)

type recordingSink struct{ events chan ast.Event }

func newRecordingSink() *recordingSink        { return &recordingSink{events: make(chan ast.Event, 16)} }
func (s *recordingSink) Emit(event ast.Event) { s.events <- event }
func (s *recordingSink) next(t *testing.T) ast.Event {
	t.Helper()
	select {
	case event := <-s.events:
		return event
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for event")
		return ast.Event{}
	}
}

func TestNewFailsClosedWhenCredentialsAreIncomplete(t *testing.T) {
	for _, cfg := range []Config{{}, {Key: "key"}, {Region: "japaneast"}} {
		_, err := New(cfg).Start(context.Background(), ast.StartRequest{}, nil)
		if !errors.Is(err, ErrUnavailable) {
			t.Fatalf("New(%#v).Start() error = %v, want unavailable", cfg, err)
		}
	}
}

func TestStartRejectsUnsupportedLanguages(t *testing.T) {
	_, err := New(Config{Key: "key", Region: "japaneast"}).Start(context.Background(), ast.StartRequest{SourceLanguage: "de", TargetLanguage: "en"}, newRecordingSink())
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("Start() error = %v, want unavailable", err)
	}
}

func TestVoiceAllowlistUsesTargetLanguageLocale(t *testing.T) {
	for _, testCase := range []struct {
		language string
		voice    string
	}{
		{"zh", "zh-CN-XiaoxiaoNeural"}, {"en", "en-US-GuyNeural"},
		{"fr", "fr-FR-DeniseNeural"}, {"vi", "vi-VN-NamMaleNeural"},
	} {
		if !IsVoiceAllowed(testCase.voice, []string{testCase.language}) {
			t.Fatalf("voice %q was rejected for %q", testCase.voice, testCase.language)
		}
	}
	if IsVoiceAllowed("en-US-JennyNeural", []string{"zh"}) {
		t.Fatal("cross-language voice was accepted")
	}
}

func TestTranslationWebSocketConfigAudioAndEventMapping(t *testing.T) {
	configReceived := make(chan struct{})
	audioReceived := make(chan []byte, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Ocp-Apim-Subscription-Key") != "secret" {
			t.Errorf("subscription header = %q", r.Header.Get("Ocp-Apim-Subscription-Key"))
		}
		query := r.URL.Query()
		if query.Get("language") != "zh-CN" || query.Get("to") != "en-US" || query.Get("format") != "simple" {
			t.Errorf("query = %s", r.URL.RawQuery)
		}
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			t.Errorf("Accept: %v", err)
			return
		}
		defer conn.CloseNow()
		ctx := context.Background()
		typ, payload, err := conn.Read(ctx)
		if err != nil || typ != websocket.MessageText {
			t.Errorf("config frame = (%v, %q, %v)", typ, payload, err)
			return
		}
		var config map[string]any
		if err := json.Unmarshal(payload, &config); err != nil || config["context"] == nil {
			t.Errorf("speech.config = %s (%v)", payload, err)
		}
		close(configReceived)
		typ, payload, err = conn.Read(ctx)
		if err != nil || typ != websocket.MessageBinary {
			t.Errorf("audio frame = (%v, %q, %v)", typ, payload, err)
			return
		}
		audioReceived <- payload
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.hypothesis","text":"你好","translations":{"en-US":"hello"}}`))
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.result","text":"你好。","translations":{"en-US":"hello."}}`))
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"turn.end"}`))
	}))
	defer server.Close()

	tts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte{0, 1, 2, 3}) }))
	defer tts.Close()
	sink := newRecordingSink()
	session := startTestSession(t, server.URL, tts.URL, sink)
	defer session.Close()
	if err := session.SendAudio(context.Background(), []byte{1, 2, 3, 4}); err != nil {
		t.Fatalf("SendAudio: %v", err)
	}
	select {
	case <-configReceived:
	case <-time.After(time.Second):
		t.Fatal("did not receive config")
	}
	frame := <-audioReceived
	if got := binary.BigEndian.Uint16(frame[:2]); got != 4 || string(frame[2:]) != string([]byte{1, 2, 3, 4}) {
		t.Fatalf("audio frame = %v", frame)
	}
	assertEvent(t, sink.next(t), "source_partial", "你好")
	assertEvent(t, sink.next(t), "translation_partial", "hello")
	assertEvent(t, sink.next(t), "source_final", "你好。")
	assertEvent(t, sink.next(t), "translation_final", "hello.")
	event := sink.next(t)
	if event.Type != "tts_audio" || string(event.Binary) != string([]byte{0, 1, 2, 3}) {
		t.Fatalf("tts event = %#v", event)
	}
}

func TestFinishWaitsForActiveTurnAndIsIdempotent(t *testing.T) {
	allowEnd := make(chan struct{})
	server := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) {
		readConfig(t, ctx, conn)
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"speech.startDetected"}`))
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.hypothesis","text":"active","translations":{"en-US":"active"}}`))
		<-allowEnd
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"turn.end"}`))
	})
	defer server.Close()
	sink := newRecordingSink()
	session := startTestSession(t, server.URL, "", sink)
	defer session.Close()
	assertEvent(t, sink.next(t), "source_partial", "active")
	assertEvent(t, sink.next(t), "translation_partial", "active")
	if err := session.Finish(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := session.Finish(context.Background()); err != nil {
		t.Fatal(err)
	}
	select {
	case event := <-sink.events:
		t.Fatalf("premature event: %#v", event)
	default:
	}
	close(allowEnd)
	assertEvent(t, sink.next(t), "finished", "")
	select {
	case event := <-sink.events:
		t.Fatalf("duplicate event: %#v", event)
	default:
	}
}

func TestFinishImmediatelyWhenIdle(t *testing.T) {
	server := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) { readConfig(t, ctx, conn); <-ctx.Done() })
	defer server.Close()
	sink := newRecordingSink()
	session := startTestSession(t, server.URL, "", sink)
	defer session.Close()
	if err := session.Finish(context.Background()); err != nil {
		t.Fatal(err)
	}
	assertEvent(t, sink.next(t), "finished", "")
}

func TestTTSErrorAndWebSocketDisconnectFailSession(t *testing.T) {
	t.Run("tts", func(t *testing.T) {
		ws := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) {
			readConfig(t, ctx, conn)
			_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.result","text":"你好","translations":{"en-US":"hello"}}`))
		})
		defer ws.Close()
		tts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Error(w, "no", http.StatusBadGateway) }))
		defer tts.Close()
		sink := newRecordingSink()
		session := startTestSession(t, ws.URL, tts.URL, sink)
		defer session.Close()
		assertEvent(t, sink.next(t), "source_final", "你好")
		assertEvent(t, sink.next(t), "translation_final", "hello")
		event := sink.next(t)
		if event.Type != "error" || event.Code != "AZURE_TTS_FAILED" {
			t.Fatalf("error event = %#v", event)
		}
	})
	t.Run("disconnect", func(t *testing.T) {
		ws := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) { readConfig(t, ctx, conn) })
		defer ws.Close()
		sink := newRecordingSink()
		session := startTestSession(t, ws.URL, "", sink)
		defer session.Close()
		event := sink.next(t)
		if event.Type != "error" || event.Code != "AZURE_SESSION_FAILED" {
			t.Fatalf("error event = %#v", event)
		}
	})
}

func startTestSession(t *testing.T, wsBase, ttsBase string, sink ast.EventSink) ast.Session {
	t.Helper()
	client := &client{configured: true, key: "secret", region: "japaneast", wsBase: strings.Replace(wsBase, "http://", "ws://", 1), ttsBase: ttsBase, httpClient: http.DefaultClient}
	session, err := client.Start(context.Background(), ast.StartRequest{SourceLanguage: "zh", TargetLanguage: "en"}, sink)
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	return session
}

func newWSServer(t *testing.T, handler func(context.Context, *websocket.Conn)) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			t.Errorf("Accept: %v", err)
			return
		}
		defer conn.CloseNow()
		handler(r.Context(), conn)
	}))
}
func readConfig(t *testing.T, ctx context.Context, conn *websocket.Conn) {
	t.Helper()
	typ, _, err := conn.Read(ctx)
	if err != nil || typ != websocket.MessageText {
		t.Errorf("config frame = %v, %v", typ, err)
	}
}
func assertEvent(t *testing.T, event ast.Event, typ, message string) {
	t.Helper()
	if event.Type != typ || event.Message != message {
		t.Fatalf("event = %#v, want type=%q message=%q", event, typ, message)
	}
}

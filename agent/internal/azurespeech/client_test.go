package azurespeech

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	neturl "net/url"
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

func TestAutomaticCandidateLanguagesUseAzureUniversalV2AndExposeDetectedLanguage(t *testing.T) {
	endpoint, err := translationEndpoint("wss://japaneast.stt.speech.microsoft.com", "zh-CN", "en-US", []string{"zh", "en"})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(endpoint, "/stt/speech/universal/v2") {
		t.Fatalf("automatic endpoint = %q", endpoint)
	}
	url, err := neturl.Parse(endpoint)
	if err != nil {
		t.Fatal(err)
	}
	if got := url.Query().Get("to"); got != "zh-Hans,en" {
		t.Fatalf("automatic targets = %q", got)
	}
	config, err := automaticSpeechContext([]string{"zh", "en"})
	if err != nil {
		t.Fatal(err)
	}
	var context map[string]any
	if err := json.Unmarshal([]byte(config), &context); err != nil {
		t.Fatal(err)
	}
	if _, wrapped := context["context"]; wrapped {
		t.Fatalf("speech.context must be the context object, got %s", config)
	}
	for _, wanted := range []string{"DetectContinuous", "PrioritizeLatency", "Recognize", "zh-CN", "en-US", "zh-Hans"} {
		if !strings.Contains(config, wanted) {
			t.Fatalf("automatic context missing %q: %s", wanted, config)
		}
	}
	legacy, err := legacySpeechConfig()
	if err != nil {
		t.Fatal(err)
	}
	var legacyBody map[string]any
	if err := json.Unmarshal([]byte(legacy), &legacyBody); err != nil || legacyBody["context"] == nil {
		t.Fatalf("legacy speech.config must retain context wrapper: %s (%v)", legacy, err)
	}

	message := upstreamMessage{PrimaryLanguage: struct {
		Language string `json:"Language"`
	}{Language: "en-US"}}
	if got := message.detectedLanguage(); got != "en-US" {
		t.Fatalf("detectedLanguage() = %q, want en-US", got)
	}
}

func TestAutomaticFinalEmitsDetectedLanguageBeforeFinalsAndTTS(t *testing.T) {
	ws := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) {
		readContext(t, ctx, conn)
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.hypothesis","Text":"hello","Translations":{"zh-Hans":"你好"}}`))
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.phrase","Text":"hello","PrimaryLanguage":{"Language":"en-US"},"RecognitionStatus":"Success","Translations":{"zh-Hans":"你好"}}`))
	})
	defer ws.Close()
	tts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte{0, 1}) }))
	defer tts.Close()
	sink := newRecordingSink()
	client := &client{configured: true, key: "secret", region: "japaneast", wsBase: strings.Replace(ws.URL, "http://", "ws://", 1), ttsBase: tts.URL, httpClient: http.DefaultClient}
	session, err := client.Start(context.Background(), ast.StartRequest{SourceLanguage: "zh", TargetLanguage: "en", CandidateLanguages: []string{"zh", "en"}}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if event := sink.next(t); event.Type != "source_partial" || event.Message != "hello" {
		t.Fatalf("hypothesis must be display-only partial = %#v", event)
	}
	if event := sink.next(t); event.Type != "detected_language" || event.Language != "en" || event.SegmentID != 1 || event.TargetLanguage != "zh" {
		t.Fatalf("detected final direction = %#v", event)
	}
	if event := sink.next(t); event.Type != "source_final" || event.Message != "hello" || event.SegmentID != 1 {
		t.Fatalf("source final = %#v", event)
	}
	if event := sink.next(t); event.Type != "translation_final" || event.Message != "你好" || event.TargetLanguage != "zh" {
		t.Fatalf("translation final = %#v", event)
	}
	if event := sink.next(t); event.Type != "tts_audio" || event.SegmentID != 1 || event.TargetLanguage != "zh" {
		t.Fatalf("tts = %#v", event)
	}
}

func TestAutomaticTranslationResponseSpeechPhraseUsesOfficialWrapperSchema(t *testing.T) {
	ws := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) {
		readContext(t, ctx, conn)
		// Azure Speech SDK JS TranslationPhrase.fromTranslationResponse maps this
		// header-framed production wrapper's SpeechPhrase.DisplayText and root
		// Translations array into the final translation phrase.
		_ = conn.Write(ctx, websocket.MessageText, []byte("X-RequestId:test\r\nPath:translation.response\r\nContent-Type:application/json; charset=utf-8\r\n\r\n"+`{"SpeechPhrase":{"RecognitionStatus":"Success","DisplayText":"hello","PrimaryLanguage":{"Language":"en-US"}},"Translations":[{"Language":"zh-Hans","DisplayText":"你好"}]}`))
		<-ctx.Done()
	})
	defer ws.Close()
	tts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte{0, 1}) }))
	defer tts.Close()
	sink := newRecordingSink()
	client := &client{configured: true, key: "secret", region: "japaneast", wsBase: strings.Replace(ws.URL, "http://", "ws://", 1), ttsBase: tts.URL, httpClient: http.DefaultClient}
	session, err := client.Start(context.Background(), ast.StartRequest{SourceLanguage: "zh", TargetLanguage: "en", CandidateLanguages: []string{"zh", "en"}}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if event := sink.next(t); event.Type != "detected_language" || event.Language != "en" || event.TargetLanguage != "zh" {
		t.Fatalf("detected = %#v", event)
	}
	assertEvent(t, sink.next(t), "source_final", "hello")
	assertEvent(t, sink.next(t), "translation_final", "你好")
	if event := sink.next(t); event.Type != "tts_audio" || event.SegmentID != 1 {
		t.Fatalf("tts = %#v", event)
	}
}

func TestAutomaticTranslationResponseIgnoresUnsuccessfulSpeechPhrase(t *testing.T) {
	ws := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) {
		readContext(t, ctx, conn)
		_ = conn.Write(ctx, websocket.MessageText, []byte("X-RequestId:test\r\nPath:translation.response\r\n\r\n"+`{"SpeechPhrase":{"RecognitionStatus":"NoMatch","DisplayText":"ignored","PrimaryLanguage":{"Language":"en-US"}},"Translations":[{"Language":"zh-Hans","DisplayText":"忽略"}]}`))
		<-ctx.Done()
	})
	defer ws.Close()
	sink := newRecordingSink()
	client := &client{configured: true, key: "secret", region: "japaneast", wsBase: strings.Replace(ws.URL, "http://", "ws://", 1), httpClient: http.DefaultClient}
	session, err := client.Start(context.Background(), ast.StartRequest{SourceLanguage: "zh", TargetLanguage: "en", CandidateLanguages: []string{"zh", "en"}}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	select {
	case event := <-sink.events:
		t.Fatalf("unexpected event: %#v", event)
	case <-time.After(100 * time.Millisecond):
	}
}

func TestAutomaticEmptyFinalPairFailsClosedWithoutFinalsOrTTS(t *testing.T) {
	ws := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) {
		readContext(t, ctx, conn)
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.phrase","Text":"hello","PrimaryLanguage":{"Language":"en-US"},"RecognitionStatus":"Success","Translations":{"zh-Hans":"   "}}`))
		<-ctx.Done()
	})
	defer ws.Close()
	ttsCalls := 0
	tts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { ttsCalls++ }))
	defer tts.Close()
	sink := newRecordingSink()
	client := &client{configured: true, key: "secret", region: "japaneast", wsBase: strings.Replace(ws.URL, "http://", "ws://", 1), ttsBase: tts.URL, httpClient: http.DefaultClient}
	session, err := client.Start(context.Background(), ast.StartRequest{SourceLanguage: "zh", TargetLanguage: "en", CandidateLanguages: []string{"zh", "en"}}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if event := sink.next(t); event.Type != "error" || event.Code != "AZURE_SESSION_FAILED" {
		t.Fatalf("terminal event = %#v", event)
	}
	if ttsCalls != 0 {
		t.Fatalf("TTS calls = %d, want 0", ttsCalls)
	}
	select {
	case event := <-sink.events:
		t.Fatalf("unexpected event after empty final: %#v", event)
	default:
	}
}

func TestLegacyProductionChineseTranslationEmitsFinalsAndTTS(t *testing.T) {
	ws := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) {
		readConfig(t, ctx, conn)
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.phrase","Text":"hello","Translations":{"zh-Hans":"你好"}}`))
	})
	defer ws.Close()
	tts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte{0, 1}) }))
	defer tts.Close()
	sink := newRecordingSink()
	client := &client{configured: true, key: "secret", region: "japaneast", wsBase: strings.Replace(ws.URL, "http://", "ws://", 1), ttsBase: tts.URL, httpClient: http.DefaultClient}
	session, err := client.Start(context.Background(), ast.StartRequest{SourceLanguage: "en", TargetLanguage: "zh"}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	assertEvent(t, sink.next(t), "source_final", "hello")
	assertEvent(t, sink.next(t), "translation_final", "你好")
	if event := sink.next(t); event.Type != "tts_audio" || string(event.Binary) != string([]byte{0, 1}) {
		t.Fatalf("tts = %#v", event)
	}
}

func TestLegacyFinalWithoutTargetTranslationFailsClosed(t *testing.T) {
	ws := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) {
		readConfig(t, ctx, conn)
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.phrase","Text":"hello","Translations":{"fr":"bonjour"}}`))
		<-ctx.Done()
	})
	defer ws.Close()
	ttsCalls := 0
	tts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { ttsCalls++ }))
	defer tts.Close()
	sink := newRecordingSink()
	client := &client{configured: true, key: "secret", region: "japaneast", wsBase: strings.Replace(ws.URL, "http://", "ws://", 1), ttsBase: tts.URL, httpClient: http.DefaultClient}
	session, err := client.Start(context.Background(), ast.StartRequest{SourceLanguage: "en", TargetLanguage: "zh"}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if event := sink.next(t); event.Type != "error" || event.Code != "AZURE_SESSION_FAILED" {
		t.Fatalf("terminal event = %#v", event)
	}
	if ttsCalls != 0 {
		t.Fatalf("TTS calls = %d, want 0", ttsCalls)
	}
	select {
	case event := <-sink.events:
		t.Fatalf("unexpected event after incomplete final: %#v", event)
	default:
	}
}

func TestTranslationWebSocketConfigAudioAndEventMapping(t *testing.T) {
	configReceived := make(chan struct{})
	configRequestID := make(chan string, 1)
	audioReceived := make(chan []byte, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Ocp-Apim-Subscription-Key") != "secret" {
			t.Errorf("subscription header = %q", r.Header.Get("Ocp-Apim-Subscription-Key"))
		}
		query := r.URL.Query()
		if query.Get("from") != "zh-CN" || query.Get("to") != "en-US" || query.Get("format") != "simple" {
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
		configStr := string(payload)
		if !strings.Contains(configStr, "Path:speech.config") {
			t.Errorf("speech.config missing Path header: %q", configStr)
		}
		bodyIdx := strings.Index(configStr, "\r\n\r\n")
		if bodyIdx < 0 {
			t.Errorf("speech.config missing header/body separator: %q", configStr)
			return
		}
		var config map[string]any
		if err := json.Unmarshal([]byte(configStr[bodyIdx+4:]), &config); err != nil || config["context"] == nil {
			t.Errorf("speech.config body = %s (%v)", configStr[bodyIdx+4:], err)
		}
		configRequestID <- headerValue(t, configStr, "X-RequestId")
		close(configReceived)
		typ, payload, err = conn.Read(ctx)
		if err != nil || typ != websocket.MessageBinary {
			t.Errorf("audio frame = (%v, %q, %v)", typ, payload, err)
			return
		}
		audioReceived <- payload
		// Production-shaped messages: Path header + capitalized payload keys.
		writeUpstream := func(path, body string) {
			_ = conn.Write(ctx, websocket.MessageText, []byte("X-RequestId:test0000000000000000000000000000\r\nPath:"+path+"\r\nContent-Type:application/json; charset=utf-8\r\n\r\n"+body))
		}
		writeUpstream("translation.hypothesis", `{"Text":"你好","Translations":{"en-US":"hello"}}`)
		writeUpstream("translation.phrase", `{"Text":"你好。","Translation":{"Translations":[{"Language":"en","Text":"hello."}]}}`)
		writeUpstream("turn.end", `{}`)
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
	headerLen := int(binary.BigEndian.Uint16(frame[:2]))
	header := string(frame[2 : 2+headerLen])
	if !strings.Contains(header, "Path:audio") || !strings.Contains(header, "audio/x-wav") {
		t.Fatalf("audio frame header = %q", header)
	}
	if audioRequestID := headerValue(t, header, "X-RequestId"); audioRequestID != <-configRequestID {
		t.Fatalf("audio request ID = %q, want config request ID", audioRequestID)
	}
	audio := frame[2+headerLen:]
	if string(audio[:4]) != "RIFF" {
		t.Fatalf("first frame must carry RIFF header, got %q", audio[:4])
	}
	if pcm := audio[44:]; string(pcm) != string([]byte{1, 2, 3, 4}) {
		t.Fatalf("audio payload = %v", pcm)
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
func readContext(t *testing.T, ctx context.Context, conn *websocket.Conn) {
	t.Helper()
	typ, payload, err := conn.Read(ctx)
	if err != nil || typ != websocket.MessageText {
		t.Fatalf("speech.context frame = (%v, %q, %v)", typ, payload, err)
	}
	frame := string(payload)
	if !strings.Contains(frame, "Path:speech.context") {
		t.Fatalf("speech.context path missing: %q", frame)
	}
	bodyIndex := strings.Index(frame, headerBodySeparator)
	if bodyIndex < 0 {
		t.Fatalf("speech.context separator missing: %q", frame)
	}
	var body map[string]any
	if err := json.Unmarshal([]byte(frame[bodyIndex+len(headerBodySeparator):]), &body); err != nil {
		t.Fatalf("speech.context body: %v", err)
	}
	if _, wrapped := body["context"]; wrapped {
		t.Fatalf("speech.context must not have context wrapper: %s", frame[bodyIndex+len(headerBodySeparator):])
	}
}

func readConfig(t *testing.T, ctx context.Context, conn *websocket.Conn) {
	t.Helper()
	typ, _, err := conn.Read(ctx)
	if err != nil || typ != websocket.MessageText {
		t.Errorf("config frame = %v, %v", typ, err)
	}
}
func headerValue(t *testing.T, header, name string) string {
	t.Helper()
	for _, line := range strings.Split(header, "\r\n") {
		if key, value, ok := strings.Cut(line, ":"); ok && key == name {
			return value
		}
	}
	t.Fatalf("header %q missing %s", header, name)
	return ""
}

func assertEvent(t *testing.T, event ast.Event, typ, message string) {
	t.Helper()
	if event.Type != typ || event.Message != message {
		t.Fatalf("event = %#v, want type=%q message=%q", event, typ, message)
	}
}

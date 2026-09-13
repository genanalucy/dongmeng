package azurespeech

import (
	"context"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	neturl "net/url"
	"reflect"
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
	url, err := neturl.Parse(endpoint)
	if err != nil {
		t.Fatal(err)
	}
	if url.Path != "/stt/speech/universal/v2" {
		t.Fatalf("automatic endpoint path = %q", url.Path)
	}
	if got, want := url.RawQuery, "format=simple&from=zh-CN&scenario=conversation&to=zh-Hans%2Cen"; got != want {
		t.Fatalf("automatic endpoint query = %q, want %q", got, want)
	}
	legacyEndpoint, err := translationEndpoint("wss://japaneast.stt.speech.microsoft.com", "zh-CN", "en-US", nil)
	if err != nil {
		t.Fatal(err)
	}
	legacyURL, err := neturl.Parse(legacyEndpoint)
	if err != nil {
		t.Fatal(err)
	}
	if legacyURL.Path != "/speech/translation/cognitiveservices/v1" || legacyURL.Query().Has("scenario") {
		t.Fatalf("legacy endpoint = %q", legacyEndpoint)
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
	for _, wanted := range []string{"DetectContinuous", "PrioritizeLatency", "Recognize", "zh-CN", "en-US", "zh-Hans", `"phraseOutput":{"interimResults":{"resultType":"None"},"phraseResults":{"resultType":"None"}}`} {
		if !strings.Contains(config, wanted) {
			t.Fatalf("automatic context missing %q: %s", wanted, config)
		}
	}
	for _, metadata := range []string{"system", "os", "device"} {
		if _, exists := context[metadata]; exists {
			t.Fatalf("automatic speech.context must not repeat speech.config metadata %q: %s", metadata, config)
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

func TestAutomaticSessionUsesConnectionIDAndSeparateRIFFBeforePCM(t *testing.T) {
	type sequence struct {
		requestIDs []string
		riff       []byte
		pcm        []byte
	}
	sequences := make(chan sequence, 1)
	var handshakeID string
	ws := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handshakeID = r.Header.Get("X-ConnectionId")
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			t.Errorf("Accept: %v", err)
			return
		}
		defer conn.CloseNow()
		frames := make([][]byte, 4)
		for index := range frames {
			typ, payload, err := conn.Read(r.Context())
			if err != nil {
				t.Errorf("frame %d read: %v", index, err)
				return
			}
			if (index < 2 && typ != websocket.MessageText) || (index >= 2 && typ != websocket.MessageBinary) {
				t.Errorf("frame %d type = %v", index, typ)
				return
			}
			frames[index] = payload
		}
		configPath, _ := splitMessage(frames[0])
		contextPath, _ := splitMessage(frames[1])
		if configPath != "speech.config" || contextPath != "speech.context" {
			t.Errorf("paths = %q, %q", configPath, contextPath)
			return
		}
		requestIDs := []string{
			headerValue(t, string(frames[0]), "X-RequestId"),
			headerValue(t, string(frames[1]), "X-RequestId"),
		}
		for _, frame := range frames[2:] {
			headerLength := int(binary.BigEndian.Uint16(frame[:2]))
			header := string(frame[2 : 2+headerLength])
			requestIDs = append(requestIDs, headerValue(t, header, "X-RequestId"))
			if strings.Contains(header, "X-RequestId:") && !strings.HasPrefix(header, "Path:audio\r\nX-RequestId:") {
				t.Errorf("audio header ordering = %q", header)
			}
		}
		if configHeader, contextHeader := string(frames[0]), string(frames[1]); !strings.HasPrefix(configHeader, "Path:speech.config\r\nX-RequestId:") || !strings.HasPrefix(contextHeader, "Path:speech.context\r\nX-RequestId:") {
			t.Errorf("text header ordering = %q / %q", configHeader, contextHeader)
		}
		riffHeaderLength := int(binary.BigEndian.Uint16(frames[2][:2]))
		pcmHeaderLength := int(binary.BigEndian.Uint16(frames[3][:2]))
		if riffHeader := string(frames[2][2 : 2+riffHeaderLength]); !strings.Contains(riffHeader, "Content-Type:audio/x-wav") {
			t.Errorf("RIFF header Content-Type = %q", riffHeader)
		}
		if pcmHeader := string(frames[3][2 : 2+pcmHeaderLength]); strings.Contains(pcmHeader, "Content-Type:") {
			t.Errorf("PCM header must omit Content-Type = %q", pcmHeader)
		}
		sequences <- sequence{requestIDs: requestIDs, riff: frames[2][2+riffHeaderLength:], pcm: frames[3][2+pcmHeaderLength:]}
		<-r.Context().Done()
	}))
	defer ws.Close()
	sink := newRecordingSink()
	client := &client{configured: true, key: "secret", region: "japaneast", wsBase: strings.Replace(ws.URL, "http://", "ws://", 1), httpClient: http.DefaultClient}
	session, err := client.Start(context.Background(), ast.StartRequest{SourceLanguage: "zh", TargetLanguage: "en", CandidateLanguages: []string{"zh", "en"}}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if err := session.SendAudio(context.Background(), []byte{1, 2, 3, 4}); err != nil {
		t.Fatal(err)
	}
	got := <-sequences
	if len(handshakeID) != 32 {
		t.Fatalf("X-ConnectionId length = %d, want 32", len(handshakeID))
	}
	if _, err := hex.DecodeString(handshakeID); err != nil {
		t.Fatalf("X-ConnectionId must be hexadecimal: %v", err)
	}
	for _, requestID := range got.requestIDs[1:] {
		if requestID != got.requestIDs[0] {
			t.Fatalf("frame request IDs = %q", got.requestIDs)
		}
	}
	if len(got.riff) != 44 || string(got.riff[:4]) != "RIFF" {
		t.Fatalf("standalone RIFF = %v", got.riff)
	}
	if got, want := binary.LittleEndian.Uint32(got.riff[4:8]), uint32(0); got != want {
		t.Fatalf("RIFF streaming size = %d, want %d", got, want)
	}
	if got, want := binary.LittleEndian.Uint32(got.riff[40:44]), uint32(0); got != want {
		t.Fatalf("WAV data streaming size = %d, want %d", got, want)
	}
	if string(got.pcm) != string([]byte{1, 2, 3, 4}) {
		t.Fatalf("PCM frame = %v", got.pcm)
	}
}

func TestAzureDialErrorClassifiesHTTPStatusWithoutBody(t *testing.T) {
	err := azureDialError(&http.Response{StatusCode: http.StatusForbidden}, errors.New("body contains secret"))
	if status := ast.ErrorUpstreamStatus(err); status != http.StatusForbidden {
		t.Fatalf("status = %d", status)
	}
	if diagnostic := ast.ErrorDiagnostic(err); diagnostic != "azure_handshake_http" {
		t.Fatalf("diagnostic = %q", diagnostic)
	}
	if strings.Contains(err.Error(), "secret") {
		t.Fatalf("body leaked into error: %q", err)
	}
}

func TestAzureReadRejectsBinaryFrameWithSafeCategory(t *testing.T) {
	ws := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) {
		readConfig(t, ctx, conn)
		_ = conn.Write(ctx, websocket.MessageBinary, []byte("sensitive transcript"))
		<-ctx.Done()
	})
	defer ws.Close()
	sink := newRecordingSink()
	session := startTestSession(t, ws.URL, "", sink)
	defer session.Close()
	event := sink.next(t)
	if event.Type != "error" || event.Code != "AZURE_SESSION_FAILED" || event.Diagnostic != "azure_read_frame_binary" {
		t.Fatalf("event = %#v", event)
	}
}

func TestAzurePathErrorMapsToGenericSessionFailure(t *testing.T) {
	ws := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) {
		readConfig(t, ctx, conn)
		_ = conn.Write(ctx, websocket.MessageText, []byte("X-RequestId:test\r\nPath:error\r\n\r\n{\"sensitive\":\"transcript\"}"))
		<-ctx.Done()
	})
	defer ws.Close()
	sink := newRecordingSink()
	session := startTestSession(t, ws.URL, "", sink)
	defer session.Close()
	event := sink.next(t)
	if event.Type != "error" || event.Code != "AZURE_SESSION_FAILED" || event.Message != "translation session failed" || event.Diagnostic != "azure_read_path_error" {
		t.Fatalf("event = %#v", event)
	}
}

func TestAzureReadErrorClassifiesCloseWithoutReason(t *testing.T) {
	err := azureReadError(websocket.CloseError{Code: websocket.StatusPolicyViolation, Reason: "secret Azure detail"})
	if diagnostic := azureDiagnostic(err); diagnostic != "azure_read_close_1008_reason_present" {
		t.Fatalf("diagnostic = %q", diagnostic)
	}
	if strings.Contains(err.Error(), "secret") {
		t.Fatalf("close reason leaked into error: %q", err)
	}
}

func TestAzureReadErrorClassifiesSafeTransportCategories(t *testing.T) {
	for _, testCase := range []struct {
		err  error
		want string
	}{
		{io.EOF, "azure_read_io_eof"},
		{net.ErrClosed, "azure_read_net_closed"},
		{context.Canceled, "azure_read_context_canceled"},
		{context.DeadlineExceeded, "azure_read_net_timeout"},
		{&neturl.Error{Op: "read", URL: "wss://secret.example", Err: errors.New("secret")}, "azure_read_url_error"},
		{errors.New("secret Azure detail"), "azure_read_failed"},
	} {
		err := azureReadError(testCase.err)
		if diagnostic := azureDiagnostic(err); diagnostic != testCase.want {
			t.Fatalf("diagnostic = %q, want %q", diagnostic, testCase.want)
		}
		if strings.Contains(err.Error(), "secret") {
			t.Fatalf("transport detail leaked into error: %q", err)
		}
	}
}

func TestAzurePhaseErrorClassifiesInitialSetupAndWriteWithoutErrorDetails(t *testing.T) {
	for _, testCase := range []struct {
		phase string
		want  string
	}{
		{phase: "initial_config", want: "azure_initial_config_failed"},
		{phase: "initial_context", want: "azure_initial_context_failed"},
		{phase: "initial_riff", want: "azure_initial_riff_failed"},
		{phase: "write", want: "azure_write_failed"},
	} {
		err := azurePhaseError(testCase.phase, errors.New("secret Azure detail"))
		if diagnostic := azureDiagnostic(err); diagnostic != testCase.want {
			t.Fatalf("%s diagnostic = %q, want %q", testCase.phase, diagnostic, testCase.want)
		}
		if strings.Contains(err.Error(), "secret") {
			t.Fatalf("%s error leaked detail: %q", testCase.phase, err)
		}
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
	if event := sink.next(t); event.Type != "tts_start" || event.SegmentID != 1 || event.TargetLanguage != "zh" {
		t.Fatalf("tts prelude = %#v", event)
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
	if event := sink.next(t); event.Type != "tts_start" || event.SegmentID != 1 {
		t.Fatalf("tts prelude = %#v", event)
	}
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
	riffReceived := make(chan []byte, 1)
	audioReceived := make(chan []byte, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Ocp-Apim-Subscription-Key") != "secret" {
			t.Errorf("subscription header = %q", r.Header.Get("Ocp-Apim-Subscription-Key"))
		}
		connectionID := r.Header.Get("X-ConnectionId")
		if len(connectionID) != 32 {
			t.Errorf("X-ConnectionId length = %d, want 32", len(connectionID))
		}
		if _, err := hex.DecodeString(connectionID); err != nil {
			t.Errorf("X-ConnectionId must be hexadecimal: %v", err)
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
			t.Errorf("RIFF frame = (%v, %q, %v)", typ, payload, err)
			return
		}
		riffReceived <- payload
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
	riffFrame := <-riffReceived
	riffHeaderLen := int(binary.BigEndian.Uint16(riffFrame[:2]))
	riffHeader := string(riffFrame[2 : 2+riffHeaderLen])
	if !strings.Contains(riffHeader, "Path:audio") || !strings.Contains(riffHeader, "audio/x-wav") {
		t.Fatalf("RIFF frame header = %q", riffHeader)
	}
	configID := <-configRequestID
	if riffRequestID := headerValue(t, riffHeader, "X-RequestId"); riffRequestID != configID {
		t.Fatalf("RIFF request ID = %q, want config request ID", riffRequestID)
	}
	if riff := riffFrame[2+riffHeaderLen:]; len(riff) != 44 || string(riff[:4]) != "RIFF" {
		t.Fatalf("standalone RIFF frame = %v", riff)
	}
	frame := <-audioReceived
	headerLen := int(binary.BigEndian.Uint16(frame[:2]))
	header := string(frame[2 : 2+headerLen])
	if audioRequestID := headerValue(t, header, "X-RequestId"); audioRequestID != configID {
		t.Fatalf("audio request ID = %q, want config request ID", audioRequestID)
	}
	if pcm := frame[2+headerLen:]; string(pcm) != string([]byte{1, 2, 3, 4}) {
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
	readText := func(wantPath string) (string, map[string]any) {
		typ, payload, err := conn.Read(ctx)
		if err != nil || typ != websocket.MessageText {
			t.Fatalf("%s frame = (%v, %q, %v)", wantPath, typ, payload, err)
		}
		frame := string(payload)
		path, body := splitMessage(payload)
		if path != wantPath {
			t.Fatalf("frame path = %q, want %q: %q", path, wantPath, frame)
		}
		var decoded map[string]any
		if err := json.Unmarshal(body, &decoded); err != nil {
			t.Fatalf("%s body: %v", wantPath, err)
		}
		return headerValue(t, frame, "X-RequestId"), decoded
	}
	configRequestID, config := readText("speech.config")
	configContext, ok := config["context"].(map[string]any)
	if !ok || len(config) != 1 || len(configContext) != 3 {
		t.Fatalf("speech.config must be the legacy required context wrapper: %#v", config)
	}
	contextRequestID, body := readText("speech.context")
	if contextRequestID != configRequestID {
		t.Fatalf("speech.context request ID = %q, want speech.config request ID %q", contextRequestID, configRequestID)
	}
	if _, wrapped := body["context"]; wrapped || len(body) != 4 {
		t.Fatalf("speech.context must be an exact unwrapped V2 context: %#v", body)
	}
	languageID, ok := body["languageId"].(map[string]any)
	if !ok || len(languageID) != 5 || languageID["mode"] != "DetectContinuous" || languageID["priority"] != "PrioritizeLatency" {
		t.Fatalf("speech.context languageId = %#v", languageID)
	}
	if languages, ok := languageID["languages"].([]any); !ok || len(languages) != 2 || languages[0] != "zh-CN" || languages[1] != "en-US" {
		t.Fatalf("speech.context candidate locales = %#v", languageID["languages"])
	}
	translation, ok := body["translation"].(map[string]any)
	if !ok || len(translation) != 4 {
		t.Fatalf("speech.context translation = %#v", translation)
	}
	if targets, ok := translation["targetLanguages"].([]any); !ok || len(targets) != 2 || targets[0] != "zh-Hans" || targets[1] != "en" {
		t.Fatalf("speech.context translation targets = %#v", translation["targetLanguages"])
	}
	phraseDetection, ok := body["phraseDetection"].(map[string]any)
	if !ok || len(phraseDetection) != 3 || phraseDetection["mode"] != "Conversation" {
		t.Fatalf("speech.context phraseDetection = %#v", phraseDetection)
	}
	phraseOutput, ok := body["phraseOutput"].(map[string]any)
	if !ok || !reflect.DeepEqual(phraseOutput, map[string]any{
		"interimResults": map[string]any{"resultType": "None"},
		"phraseResults":  map[string]any{"resultType": "None"},
	}) {
		t.Fatalf("speech.context phraseOutput = %#v", phraseOutput)
	}
	for _, metadata := range []string{"system", "os", "device"} {
		if _, exists := body[metadata]; exists {
			t.Fatalf("speech.context must not repeat speech.config metadata %q: %#v", metadata, body)
		}
	}
	readStandaloneRIFF(t, ctx, conn)
}

func readStandaloneRIFF(t *testing.T, ctx context.Context, conn *websocket.Conn) {
	t.Helper()
	typ, payload, err := conn.Read(ctx)
	if err != nil || typ != websocket.MessageBinary {
		t.Fatalf("RIFF frame = %v, %v", typ, err)
	}
	if len(payload) < 2 {
		t.Fatalf("RIFF frame too short: %d", len(payload))
	}
	headerLength := int(binary.BigEndian.Uint16(payload[:2]))
	if len(payload) != 2+headerLength+44 || string(payload[2+headerLength:2+headerLength+4]) != "RIFF" {
		t.Fatalf("RIFF frame must contain only a 44-byte RIFF header: %v", payload)
	}
}

func readConfig(t *testing.T, ctx context.Context, conn *websocket.Conn) {
	t.Helper()
	typ, _, err := conn.Read(ctx)
	if err != nil || typ != websocket.MessageText {
		t.Errorf("config frame = %v, %v", typ, err)
		return
	}
	readStandaloneRIFF(t, ctx, conn)
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

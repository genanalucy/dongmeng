package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
	"github.com/golang-jwt/jwt/v5"

	"translator-agent/internal/ast"
	"translator-agent/internal/sessionauth"
)

const (
	testSessionID = "123e4567-e89b-12d3-a456-426614174000"
	testUserID    = "user-123"
	testInstallID = "install-456"
	testIssuer    = "cloud-api"
	testAudience  = "translator-agent"
)

var testSessionKey = []byte("0123456789abcdef0123456789abcdef")

type fakeClient struct {
	mu         sync.Mutex
	startErr   error
	session    *fakeSession
	startCalls int
}

type emittingClient struct{}
type zeroTTSClient struct{}

func (zeroTTSClient) Start(_ context.Context, _ ast.StartRequest, sink ast.EventSink) (ast.Session, error) {
	sink.Emit(ast.Event{Type: "finished"})
	return &fakeSession{}, nil
}

func (emittingClient) Start(_ context.Context, _ ast.StartRequest, sink ast.EventSink) (ast.Session, error) {
	sink.Emit(ast.Event{Type: "source_final", Message: ""})
	sink.Emit(ast.Event{Type: "translation_final", Message: "   "})
	sink.Emit(ast.Event{Type: "tts_start"})
	sink.Emit(ast.Event{Type: "tts_audio", Binary: []byte{1, 2, 3, 4}})
	sink.Emit(ast.Event{Type: "tts_end"})
	sink.Emit(ast.Event{Type: "finished"})
	return &fakeSession{}, nil
}

func (f *fakeClient) Start(_ context.Context, _ ast.StartRequest, _ ast.EventSink) (ast.Session, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.startCalls++
	if f.startErr != nil {
		return nil, f.startErr
	}
	if f.session == nil {
		f.session = &fakeSession{}
	}
	return f.session, nil
}

func (f *fakeClient) starts() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.startCalls
}

type fakeSession struct {
	mu          sync.Mutex
	audio       [][]byte
	finishCalls int
	closed      bool
	blockAudio  chan struct{}
}

func (f *fakeSession) SendAudio(_ context.Context, pcm []byte) error {
	if f.blockAudio != nil {
		<-f.blockAudio
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	f.audio = append(f.audio, append([]byte(nil), pcm...))
	return nil
}
func (f *fakeSession) Finish(context.Context) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.finishCalls++
	return nil
}
func (f *fakeSession) Close() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.closed = true
	return nil
}

func testHTTPServer(client ast.Client) *httptest.Server {
	return httptest.NewServer(New(Options{ASTClient: client}).Handler())
}

func testAuthorizedHTTPServer(t *testing.T, client ast.Client, logger *slog.Logger) *httptest.Server {
	t.Helper()
	verifier, err := sessionauth.NewVerifier(sessionauth.Config{
		HMACKey: testSessionKey, Issuer: testIssuer, Audience: testAudience,
		ClockSkew: 30 * time.Second, MaxLifetime: 5 * time.Minute,
	})
	if err != nil {
		t.Fatalf("NewVerifier() error = %v", err)
	}
	return httptest.NewServer(New(Options{ASTClient: client, Logger: logger, SessionVerifier: verifier}).Handler())
}

func dial(t *testing.T, serverURL string, origin string) *websocket.Conn {
	t.Helper()
	return dialWithProtocols(t, serverURL, origin, nil)
}

func dialWithProtocols(t *testing.T, serverURL string, origin string, protocols []string) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(serverURL, "http")+"/ws/translate", &websocket.DialOptions{
		HTTPHeader: http.Header{"Origin": {origin}}, Subprotocols: protocols,
	})
	if err != nil {
		t.Fatal(err)
	}
	return conn
}

func start(t *testing.T, conn *websocket.Conn, updates map[string]any) {
	t.Helper()
	message := map[string]any{
		"type": "start", "sessionId": testSessionID, "mode": "s2s",
		"sourceLanguage": "zh", "targetLanguage": "en", "targetAudioFormat": "pcm", "targetAudioRate": 16000,
	}
	for key, value := range updates {
		message[key] = value
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := wsjson.Write(ctx, conn, message); err != nil {
		t.Fatal(err)
	}
}

func startAuthorized(t *testing.T, conn *websocket.Conn, updates map[string]any) {
	t.Helper()
	authUpdates := map[string]any{"userId": testUserID, "installId": testInstallID}
	for key, value := range updates {
		authUpdates[key] = value
	}
	start(t, conn, authUpdates)
}

func responseStatus(response *http.Response) int {
	if response == nil {
		return 0
	}
	return response.StatusCode
}

func readEvent(t *testing.T, conn *websocket.Conn) browserEvent {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	var event browserEvent
	if err := wsjson.Read(ctx, conn, &event); err != nil {
		t.Fatal(err)
	}
	return event
}

func TestHealth(t *testing.T) {
	ts := testHTTPServer(&fakeClient{})
	defer ts.Close()
	response, err := ts.Client().Get(ts.URL + "/api/health")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK || response.Header.Get("Content-Type") != "application/json" {
		t.Fatalf("unexpected health response: %s, %q", response.Status, response.Header.Get("Content-Type"))
	}
	var body map[string]string
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body["status"] != "ok" || body["service"] != "translator-agent" {
		t.Fatalf("unexpected health payload: %#v", body)
	}
}

func TestHealthCORSAllowsOnlyConfiguredOrigins(t *testing.T) {
	ts := testHTTPServer(&fakeClient{})
	defer ts.Close()

	for _, testCase := range []struct {
		origin string
		want   string
	}{
		{origin: "http://127.0.0.1:5173", want: "http://127.0.0.1:5173"},
		{origin: "http://evil.example", want: ""},
		{origin: "", want: ""},
	} {
		req, err := http.NewRequest(http.MethodGet, ts.URL+"/api/health", nil)
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Origin", testCase.origin)
		response, err := ts.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		if got := response.Header.Get("Access-Control-Allow-Origin"); got != testCase.want {
			response.Body.Close()
			t.Fatalf("origin %q: got CORS origin %q, want %q", testCase.origin, got, testCase.want)
		}
		response.Body.Close()
	}
}

func TestOriginValidation(t *testing.T) {
	ts := testHTTPServer(&fakeClient{})
	defer ts.Close()
	for _, origin := range []string{"http://127.0.0.1:5173", "http://localhost:5173"} {
		conn := dial(t, ts.URL, origin)
		_ = conn.CloseNow()
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, response, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/ws/translate", &websocket.DialOptions{HTTPHeader: http.Header{"Origin": {"http://evil.example"}}})
	if err == nil || response == nil || response.StatusCode != http.StatusForbidden {
		t.Fatalf("unexpected rejected origin response: %v, %#v", err, response)
	}
}

func TestSessionAuthorizationDisabledKeepsLegacyClientCompatible(t *testing.T) {
	fake := &fakeClient{}
	ts := testHTTPServer(fake)
	defer ts.Close()

	conn := dial(t, ts.URL, "http://localhost:5173")
	defer conn.CloseNow()
	if got := conn.Subprotocol(); got != "" {
		t.Fatalf("legacy connection negotiated unexpected subprotocol %q", got)
	}
	start(t, conn, nil)
	if event := readEvent(t, conn); event.Type != "ready" {
		t.Fatalf("legacy client event = %#v, want ready", event)
	}
	if fake.starts() != 1 {
		t.Fatalf("provider started %d times, want 1", fake.starts())
	}
}

func TestOriginIsRejectedBeforeSessionTokenValidation(t *testing.T) {
	fake := &fakeClient{}
	ts := testAuthorizedHTTPServer(t, fake, nil)
	defer ts.Close()
	token := signSessionToken(t, testSessionKey, testUserID, testSessionID, testInstallID)

	tests := []struct {
		name      string
		protocols []string
	}{
		{name: "missing token"},
		{name: "malformed token", protocols: []string{SessionSubprotocol, SessionTokenProtocolPrefix + "malformed"}},
		{name: "valid token", protocols: []string{SessionSubprotocol, SessionTokenProtocolPrefix + token}},
	}
	for _, tt := range tests {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		_, response, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/ws/translate", &websocket.DialOptions{
			HTTPHeader: http.Header{"Origin": {"http://evil.example"}}, Subprotocols: tt.protocols,
		})
		cancel()
		if err == nil || response == nil || response.StatusCode != http.StatusForbidden {
			t.Fatalf("%s: status = %v, err missing = %v; want 403 with dial error", tt.name, responseStatus(response), err == nil)
		}
		body, readErr := io.ReadAll(response.Body)
		response.Body.Close()
		if readErr != nil {
			t.Fatalf("read rejection: %v", readErr)
		}
		if strings.Contains(string(body), token) || response.Header.Get("Sec-WebSocket-Protocol") != "" {
			t.Fatal("origin rejection leaked token or negotiated a protocol")
		}
	}
	if fake.starts() != 0 {
		t.Fatalf("provider started %d times", fake.starts())
	}
}

func TestSessionAuthorizationRejectsMissingTokenBeforeUpgrade(t *testing.T) {
	fake := &fakeClient{}
	ts := testAuthorizedHTTPServer(t, fake, nil)
	defer ts.Close()

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, response, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/ws/translate", &websocket.DialOptions{
		HTTPHeader: http.Header{"Origin": {"http://localhost:5173"}},
	})
	if err == nil || response == nil || response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("missing token response = (%v, %#v), want 401", err, response)
	}
	body, readErr := io.ReadAll(response.Body)
	response.Body.Close()
	if readErr != nil {
		t.Fatalf("read rejection: %v", readErr)
	}
	if strings.Contains(string(body), SessionTokenProtocolPrefix) || response.Header.Get("Sec-WebSocket-Protocol") != "" {
		t.Fatal("missing-token rejection exposed protocol credential details")
	}
	if fake.starts() != 0 {
		t.Fatalf("provider started %d times", fake.starts())
	}
}

func TestSessionAuthorizationRejectsMalformedProtocolTokenWithoutLeakingIt(t *testing.T) {
	fake := &fakeClient{}
	ts := testAuthorizedHTTPServer(t, fake, nil)
	defer ts.Close()
	malformed := "not-a-jwt"

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, response, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/ws/translate", &websocket.DialOptions{
		HTTPHeader:   http.Header{"Origin": {"http://localhost:5173"}},
		Subprotocols: []string{SessionSubprotocol, SessionTokenProtocolPrefix + malformed},
	})
	if err == nil || response == nil || response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("malformed token status = %d, err missing = %v; want 401 with dial error", responseStatus(response), err == nil)
	}
	body, readErr := io.ReadAll(response.Body)
	response.Body.Close()
	if readErr != nil {
		t.Fatalf("read rejection: %v", readErr)
	}
	if strings.Contains(string(body), malformed) || response.Header.Get("Sec-WebSocket-Protocol") != "" {
		t.Fatal("malformed-token rejection leaked token or negotiated a protocol")
	}
	if fake.starts() != 0 {
		t.Fatalf("provider started %d times", fake.starts())
	}
}

func TestSessionAuthorizationRejectsAuthorizationHeaderWithoutSubprotocol(t *testing.T) {
	fake := &fakeClient{}
	ts := testAuthorizedHTTPServer(t, fake, nil)
	defer ts.Close()
	token := signSessionToken(t, testSessionKey, testUserID, testSessionID, testInstallID)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_, response, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/ws/translate", &websocket.DialOptions{
		HTTPHeader: http.Header{"Origin": {"http://localhost:5173"}, "Authorization": {"Bearer " + token}},
	})
	if err == nil || response == nil || response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("Authorization-only response = (%v, %#v), want 401", err, response)
	}
	body, readErr := io.ReadAll(response.Body)
	response.Body.Close()
	if readErr != nil {
		t.Fatalf("read rejection: %v", readErr)
	}
	if strings.Contains(string(body), token) {
		t.Fatal("Authorization-only rejection leaked token")
	}
	if fake.starts() != 0 {
		t.Fatalf("provider started %d times", fake.starts())
	}
}

func TestSessionAuthorizationAcceptsValidBoundToken(t *testing.T) {
	fake := &fakeClient{}
	ts := testAuthorizedHTTPServer(t, fake, nil)
	defer ts.Close()
	token := signSessionToken(t, testSessionKey, testUserID, testSessionID, testInstallID)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, response, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/ws/translate", &websocket.DialOptions{
		HTTPHeader:   http.Header{"Origin": {"http://localhost:5173"}},
		Subprotocols: []string{SessionSubprotocol, SessionTokenProtocolPrefix + token},
	})
	if err != nil {
		t.Fatalf("Dial() error = %v", err)
	}
	defer conn.CloseNow()
	if got := conn.Subprotocol(); got != SessionSubprotocol {
		t.Fatalf("negotiated subprotocol = %q, want %q", got, SessionSubprotocol)
	}
	if negotiated := response.Header.Get("Sec-WebSocket-Protocol"); negotiated != SessionSubprotocol || strings.Contains(negotiated, token) {
		t.Fatalf("response subprotocol = %q", negotiated)
	}
	startAuthorized(t, conn, nil)
	if event := readEvent(t, conn); event.Type != "ready" {
		t.Fatalf("event = %#v, want ready", event)
	}
	if fake.starts() != 1 {
		t.Fatalf("provider started %d times, want 1", fake.starts())
	}
}

func TestSessionAuthorizationRejectsInvalidOrMismatchedTokenWithoutLeakingIt(t *testing.T) {
	tests := []struct {
		name    string
		key     []byte
		userID  string
		session string
		install string
		updates map[string]any
	}{
		{name: "invalid signature", key: []byte("abcdef0123456789abcdef0123456789"), userID: testUserID, session: testSessionID, install: testInstallID},
		{name: "wrong user binding", key: testSessionKey, userID: "other-user", session: testSessionID, install: testInstallID},
		{name: "wrong session binding", key: testSessionKey, userID: testUserID, session: "123e4567-e89b-12d3-a456-426614174001", install: testInstallID},
		{name: "wrong install binding", key: testSessionKey, userID: testUserID, session: testSessionID, install: "other-install"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			fake := &fakeClient{}
			var logs bytes.Buffer
			logger := slog.New(slog.NewTextHandler(&logs, nil))
			ts := testAuthorizedHTTPServer(t, fake, logger)
			defer ts.Close()
			token := signSessionToken(t, tt.key, tt.userID, tt.session, tt.install)

			conn := dialWithProtocols(t, ts.URL, "http://localhost:5173", []string{SessionSubprotocol, SessionTokenProtocolPrefix + token})
			defer conn.CloseNow()
			startAuthorized(t, conn, tt.updates)
			event := readEvent(t, conn)
			if event.Type != "error" || event.Code != "TRANSLATION_AUTH_INVALID" || event.Message != "translation session authorization failed" {
				t.Fatalf("event = %#v", event)
			}
			if fake.starts() != 0 {
				t.Fatalf("provider started %d times", fake.starts())
			}
			if strings.Contains(logs.String(), token) {
				t.Fatal("server log leaked session token")
			}
		})
	}
}

func TestSessionAuthorizationRejectsTokenWithNonTranslationScopeBeforeProviderStart(t *testing.T) {
	fake := &fakeClient{}
	ts := testAuthorizedHTTPServer(t, fake, nil)
	defer ts.Close()
	token := signSessionTokenWithScope(t, testSessionKey, testUserID, testSessionID, testInstallID, "api")

	conn := dialWithProtocols(t, ts.URL, "http://localhost:5173", []string{SessionSubprotocol, SessionTokenProtocolPrefix + token})
	defer conn.CloseNow()
	startAuthorized(t, conn, nil)
	if event := readEvent(t, conn); event.Type != "error" || event.Code != "TRANSLATION_AUTH_INVALID" {
		t.Fatalf("event = %#v", event)
	}
	if fake.starts() != 0 {
		t.Fatalf("provider started %d times", fake.starts())
	}
}

func TestSessionAuthorizationRequiresStartBindings(t *testing.T) {
	for _, updates := range []map[string]any{{"userId": ""}, {"installId": ""}} {
		fake := &fakeClient{}
		ts := testAuthorizedHTTPServer(t, fake, nil)
		token := signSessionToken(t, testSessionKey, testUserID, testSessionID, testInstallID)
		conn := dialWithProtocols(t, ts.URL, "http://localhost:5173", []string{SessionSubprotocol, SessionTokenProtocolPrefix + token})
		startAuthorized(t, conn, updates)
		event := readEvent(t, conn)
		if event.Type != "error" || event.Code != "INVALID_START" {
			t.Fatalf("updates %#v: event = %#v", updates, event)
		}
		if fake.starts() != 0 {
			t.Fatalf("provider started %d times", fake.starts())
		}
		_ = conn.CloseNow()
		ts.Close()
	}
}

func TestSessionTokenProtocolParsingIsStrict(t *testing.T) {
	valid := "header.payload.signature"
	tests := []struct {
		header string
		ok     bool
	}{
		{header: SessionSubprotocol + ", " + SessionTokenProtocolPrefix + valid, ok: true},
		{header: SessionTokenProtocolPrefix + valid, ok: false},
		{header: SessionSubprotocol, ok: false},
		{header: SessionSubprotocol + ", unknown, " + SessionTokenProtocolPrefix + valid, ok: false},
		{header: SessionSubprotocol + ", " + SessionTokenProtocolPrefix + valid + ", " + SessionTokenProtocolPrefix + valid, ok: false},
		{header: SessionSubprotocol + ", " + SessionTokenProtocolPrefix + "not-a-jwt", ok: false},
	}
	for _, tt := range tests {
		req := httptest.NewRequest(http.MethodGet, "/ws/translate", nil)
		req.Header.Set("Sec-WebSocket-Protocol", tt.header)
		token, ok := sessionTokenFromRequest(req)
		if ok != tt.ok {
			t.Fatalf("header %q: ok = %v, want %v", tt.header, ok, tt.ok)
		}
		if ok && token != valid {
			t.Fatalf("header %q: token = %q", tt.header, token)
		}
	}

	req := httptest.NewRequest(http.MethodGet, "/ws/translate", nil)
	req.Header.Add("Sec-WebSocket-Protocol", SessionSubprotocol)
	req.Header.Add("Sec-WebSocket-Protocol", SessionTokenProtocolPrefix+valid)
	if token, ok := sessionTokenFromRequest(req); !ok || token != valid {
		t.Fatalf("split header fields yielded token = %q, ok = %v", token, ok)
	}
}

func TestStartParsingAndLanguageValidation(t *testing.T) {
	for _, language := range []string{"zh", "en", "fr", "vi"} {
		request := map[string]any{"sourceLanguage": language, "targetLanguage": "en"}
		if language == "en" {
			request["targetLanguage"] = "zh"
		}
		payload, err := json.Marshal(map[string]any{
			"type": "start", "sessionId": testSessionID, "mode": "s2s",
			"sourceLanguage": request["sourceLanguage"], "targetLanguage": request["targetLanguage"],
			"targetAudioFormat": "pcm", "targetAudioRate": 16000,
		})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := parseStart(payload, false); err != nil {
			t.Fatalf("language %q should be accepted: %v", language, err)
		}
	}

	tests := []map[string]any{
		{"mode": "bad"},
		{"sessionId": "not-a-uuid"},
		{"sourceLanguage": "de"},
		{"sourceLanguage": "zh", "targetLanguage": "zh"},
		{"targetAudioRate": 8000},
		{"userId": testUserID},
		{"extra": true},
	}
	for _, update := range tests {
		ts := testHTTPServer(&fakeClient{})
		conn := dial(t, ts.URL, "http://localhost:5173")
		start(t, conn, update)
		event := readEvent(t, conn)
		if event.Type != "error" || event.Code != "INVALID_START" {
			t.Fatalf("update %#v yielded %#v", update, event)
		}
		_ = conn.CloseNow()
		ts.Close()
	}
}

func TestPCMSizeFinishAndDisconnect(t *testing.T) {
	fake := &fakeClient{}
	ts := testHTTPServer(fake)
	defer ts.Close()
	conn := dial(t, ts.URL, "http://localhost:5173")
	start(t, conn, nil)
	if event := readEvent(t, conn); event.Type != "ready" {
		t.Fatalf("expected ready, got %#v", event)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := conn.Write(ctx, websocket.MessageBinary, make([]byte, PCMFrameBytes-1)); err != nil {
		t.Fatal(err)
	}
	if event := readEvent(t, conn); event.Code != "INVALID_PCM_FRAME" {
		t.Fatalf("expected invalid PCM event, got %#v", event)
	}
	_ = conn.CloseNow()
	deadline := time.Now().Add(time.Second)
	for !fake.session.closed && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if !fake.session.closed {
		t.Fatal("AST session was not closed after client disconnect")
	}
}

func TestFinishIsIdempotent(t *testing.T) {
	fake := &fakeClient{}
	ts := testHTTPServer(fake)
	defer ts.Close()
	conn := dial(t, ts.URL, "http://localhost:5173")
	defer conn.CloseNow()
	start(t, conn, nil)
	readEvent(t, conn)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	for range 2 {
		if err := wsjson.Write(ctx, conn, map[string]string{"type": "finish"}); err != nil {
			t.Fatal(err)
		}
	}
	time.Sleep(20 * time.Millisecond)
	fake.session.mu.Lock()
	defer fake.session.mu.Unlock()
	if fake.session.finishCalls != 1 {
		t.Fatalf("finish called %d times", fake.session.finishCalls)
	}
}

func TestQueueOverflow(t *testing.T) {
	fake := &fakeClient{session: &fakeSession{blockAudio: make(chan struct{})}}
	ts := testHTTPServer(fake)
	defer ts.Close()
	conn := dial(t, ts.URL, "http://localhost:5173")
	defer conn.CloseNow()
	start(t, conn, nil)
	readEvent(t, conn)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	for range QueueCapacity + 2 {
		if err := conn.Write(ctx, websocket.MessageBinary, make([]byte, PCMFrameBytes)); err != nil {
			t.Fatal(err)
		}
	}
	if event := readEvent(t, conn); event.Code != "AUDIO_QUEUE_OVERFLOW" {
		t.Fatalf("expected overflow, got %#v", event)
	}
	close(fake.session.blockAudio)
}

func TestUpstreamEventsUseOneOrderedTextAndBinaryWriterAndSkipEmptySubtitles(t *testing.T) {
	ts := testHTTPServer(emittingClient{})
	defer ts.Close()
	conn := dial(t, ts.URL, "http://localhost:5173")
	defer conn.CloseNow()
	start(t, conn, nil)

	if event := readEvent(t, conn); event.Type != "ready" {
		t.Fatalf("first event = %#v, want ready", event)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	messageType, payload, err := conn.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if messageType != websocket.MessageBinary || string(payload) != string([]byte{1, 2, 3, 4}) {
		t.Fatalf("binary message = (%v, %v)", messageType, payload)
	}
	if event := readEvent(t, conn); event.Type != "finished" {
		t.Fatalf("third event = %#v, want finished", event)
	}
}

func TestZeroTTSCanFinishImmediatelyAfterReady(t *testing.T) {
	ts := testHTTPServer(zeroTTSClient{})
	defer ts.Close()
	conn := dial(t, ts.URL, "http://localhost:5173")
	defer conn.CloseNow()
	start(t, conn, nil)

	if event := readEvent(t, conn); event.Type != "ready" {
		t.Fatalf("first event = %#v, want ready", event)
	}
	if event := readEvent(t, conn); event.Type != "finished" {
		t.Fatalf("second event = %#v, want finished", event)
	}
}

func TestUnavailableCodecNeverSendsReady(t *testing.T) {
	ts := testHTTPServer(&fakeClient{startErr: ast.ErrCodecUnavailable})
	defer ts.Close()
	conn := dial(t, ts.URL, "http://localhost:5173")
	defer conn.CloseNow()
	start(t, conn, nil)
	event := readEvent(t, conn)
	if event.Type != "error" || event.Code != "AST_CODEC_UNAVAILABLE" {
		t.Fatalf("unexpected unavailable-codec event: %#v", event)
	}
}

func signSessionToken(t *testing.T, key []byte, userID, sessionID, installID string) string {
	t.Helper()
	return signSessionTokenWithScope(t, key, userID, sessionID, installID, "translation")
}

func signSessionTokenWithScope(t *testing.T, key []byte, userID, sessionID, installID, scope string) string {
	t.Helper()
	now := time.Now().UTC()
	claims := jwt.MapClaims{
		"iss": testIssuer, "sub": userID, "aud": []string{testAudience},
		"iat": now.Unix(), "exp": now.Add(5 * time.Minute).Unix(),
		"user_id": userID, "session_id": sessionID, "install_id": installID,
		"scope": scope,
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	token.Header["typ"] = sessionauth.TokenType
	signed, err := token.SignedString(key)
	if err != nil {
		t.Fatalf("SignedString() error = %v", err)
	}
	return signed
}

var _ = errors.New

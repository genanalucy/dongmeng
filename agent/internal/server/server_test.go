package server

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"translator-agent/internal/ast"
)

const testSessionID = "123e4567-e89b-12d3-a456-426614174000"

type fakeClient struct {
	startErr error
	session  *fakeSession
}

func (f *fakeClient) Start(_ context.Context, _ ast.StartRequest, _ ast.EventSink) (ast.Session, error) {
	if f.startErr != nil {
		return nil, f.startErr
	}
	if f.session == nil {
		f.session = &fakeSession{}
	}
	return f.session, nil
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

func dial(t *testing.T, serverURL string, origin string) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(serverURL, "http")+"/ws/translate", &websocket.DialOptions{HTTPHeader: http.Header{"Origin": {origin}}})
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

func TestStartParsingAndLanguageValidation(t *testing.T) {
	tests := []map[string]any{
		{"mode": "bad"},
		{"sessionId": "not-a-uuid"},
		{"sourceLanguage": "fr"},
		{"targetLanguage": "zh"},
		{"targetAudioRate": 8000},
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

var _ = errors.New

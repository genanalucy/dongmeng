//go:build officialast

package ast

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"google.golang.org/protobuf/proto"

	"translator-agent/internal/config"
	eventproto "translator-agent/internal/officialastproto/common/event"
	rpcmetaproto "translator-agent/internal/officialastproto/common/rpcmeta"
	astproto "translator-agent/internal/officialastproto/products/understanding/ast"
)

const officialTestSessionID = "123e4567-e89b-12d3-a456-426614174000"

type recordedUpstream struct {
	mu       sync.Mutex
	headers  http.Header
	requests []*astproto.TranslateRequest
	received chan struct{}
}

func newRecordedUpstream() *recordedUpstream {
	return &recordedUpstream{received: make(chan struct{}, 3)}
}

func (u *recordedUpstream) handler(t *testing.T) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		u.headers = r.Header.Clone()
		u.mu.Unlock()
		w.Header().Set("X-Tt-Logid", "test-log-id")
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			t.Errorf("accept upstream WebSocket: %v", err)
			return
		}
		defer conn.CloseNow()

		start := u.readRequest(t, r.Context(), conn)
		if start == nil {
			return
		}
		u.writeResponse(t, r.Context(), conn, &astproto.TranslateResponse{Event: eventproto.Type_SessionStarted})

		task := u.readRequest(t, r.Context(), conn)
		if task == nil {
			return
		}
		finish := u.readRequest(t, r.Context(), conn)
		if finish == nil {
			return
		}

		responses := []*astproto.TranslateResponse{
			{Event: eventproto.Type_SourceSubtitleResponse, Text: "你"},
			{Event: eventproto.Type_SourceSubtitleEnd, Text: "你好"},
			{Event: eventproto.Type_TranslationSubtitleResponse, Text: "Hel"},
			{Event: eventproto.Type_TranslationSubtitleEnd, Text: "Hello"},
			{Event: eventproto.Type_TTSSentenceStart},
			{Event: eventproto.Type_TTSResponse, Data: []byte{1, 2, 3, 4}},
			{Event: eventproto.Type_TTSSentenceEnd},
			{Event: eventproto.Type_SessionFinished},
		}
		for _, response := range responses {
			u.writeResponse(t, r.Context(), conn, response)
		}
	}
}

func (u *recordedUpstream) readRequest(t *testing.T, ctx context.Context, conn *websocket.Conn) *astproto.TranslateRequest {
	t.Helper()
	messageType, payload, err := conn.Read(ctx)
	if err != nil {
		t.Errorf("read upstream request: %v", err)
		return nil
	}
	if messageType != websocket.MessageBinary {
		t.Errorf("request message type = %v, want binary", messageType)
		return nil
	}
	request := new(astproto.TranslateRequest)
	if err := proto.Unmarshal(payload, request); err != nil {
		t.Errorf("decode upstream request: %v", err)
		return nil
	}
	u.mu.Lock()
	u.requests = append(u.requests, request)
	u.mu.Unlock()
	u.received <- struct{}{}
	return request
}

func (u *recordedUpstream) writeResponse(t *testing.T, ctx context.Context, conn *websocket.Conn, response *astproto.TranslateResponse) {
	t.Helper()
	payload, err := proto.Marshal(response)
	if err != nil {
		t.Errorf("encode upstream response: %v", err)
		return
	}
	if err := conn.Write(ctx, websocket.MessageBinary, payload); err != nil {
		t.Errorf("write upstream response: %v", err)
	}
}

type collectingSink struct {
	mu     sync.Mutex
	events []Event
	done   chan struct{}
}

func (s *collectingSink) Emit(event Event) {
	s.mu.Lock()
	s.events = append(s.events, event)
	s.mu.Unlock()
	if event.Type == "finished" || event.Type == "error" {
		select {
		case <-s.done:
		default:
			close(s.done)
		}
	}
}

func TestOfficialClientHeadersRequestsAndEventMapping(t *testing.T) {
	upstream := newRecordedUpstream()
	server := httptest.NewServer(upstream.handler(t))
	defer server.Close()

	client := &officialClient{
		config:   config.Config{APIKey: "test-api-key", ResourceID: "test-resource"},
		endpoint: "ws" + strings.TrimPrefix(server.URL, "http"),
	}
	sink := &collectingSink{done: make(chan struct{})}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	session, err := client.Start(ctx, StartRequest{
		SessionID: officialTestSessionID, Mode: "s2s", SourceLanguage: "zh", TargetLanguage: "en",
		TargetAudioFormat: "pcm", TargetAudioRate: 16000,
	}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if err := session.SendAudio(ctx, []byte{10, 11, 12, 13}); err != nil {
		t.Fatal(err)
	}
	if err := session.Finish(ctx); err != nil {
		t.Fatal(err)
	}

	select {
	case <-sink.done:
	case <-ctx.Done():
		t.Fatal("timed out waiting for mapped events")
	}

	upstream.mu.Lock()
	headers := upstream.headers.Clone()
	requests := append([]*astproto.TranslateRequest(nil), upstream.requests...)
	upstream.mu.Unlock()
	if headers.Get("X-Api-Key") != "test-api-key" || headers.Get("X-Api-Resource-Id") != "test-resource" || headers.Get("X-Api-Connect-Id") == "" {
		t.Fatalf("unexpected upstream headers: key=%q resource=%q connect=%q", headers.Get("X-Api-Key"), headers.Get("X-Api-Resource-Id"), headers.Get("X-Api-Connect-Id"))
	}
	if len(requests) != 3 {
		t.Fatalf("received %d requests, want 3", len(requests))
	}
	if requests[0].GetEvent() != eventproto.Type_StartSession || requests[1].GetEvent() != eventproto.Type_TaskRequest || requests[2].GetEvent() != eventproto.Type_FinishSession {
		t.Fatalf("request events = %v, %v, %v", requests[0].GetEvent(), requests[1].GetEvent(), requests[2].GetEvent())
	}
	start := requests[0]
	if start.GetRequestMeta().GetSessionID() != officialTestSessionID || start.GetSourceAudio().GetFormat() != "wav" || start.GetSourceAudio().GetCodec() != "raw" || start.GetSourceAudio().GetRate() != 16000 || start.GetSourceAudio().GetBits() != 16 || start.GetSourceAudio().GetChannel() != 1 {
		t.Fatalf("unexpected StartSession audio/meta: %#v", start)
	}
	if start.GetRequest().GetMode() != "s2s" || start.GetRequest().GetSourceLanguage() != "zh" || start.GetRequest().GetTargetLanguage() != "en" || start.GetTargetAudio().GetFormat() != "pcm" || start.GetTargetAudio().GetRate() != 16000 {
		t.Fatalf("unexpected StartSession config: %#v", start)
	}
	if got := requests[1].GetSourceAudio().GetBinaryData(); string(got) != string([]byte{10, 11, 12, 13}) {
		t.Fatalf("TaskRequest audio = %v", got)
	}

	sink.mu.Lock()
	events := append([]Event(nil), sink.events...)
	sink.mu.Unlock()
	wantTypes := []string{"source_partial", "source_final", "translation_partial", "translation_final", "tts_audio", "finished"}
	if len(events) != len(wantTypes) {
		t.Fatalf("mapped event count = %d, want %d: %#v", len(events), len(wantTypes), events)
	}
	for index, eventType := range wantTypes {
		if events[index].Type != eventType {
			t.Fatalf("event[%d] = %#v, want type %q", index, events[index], eventType)
		}
		if index < 4 && events[index].LogID != "test-log-id" {
			t.Fatalf("text event[%d] has log ID %q", index, events[index].LogID)
		}
		if index >= 4 && index != 4 && events[index].LogID != "" {
			t.Fatalf("control event[%d] must not expose extra JSON fields: %#v", index, events[index])
		}
	}
	if string(events[4].Binary) != string([]byte{1, 2, 3, 4}) {
		t.Fatalf("TTS binary = %v", events[4].Binary)
	}
}

func TestOfficialClientMapsSessionFailed(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Tt-Logid", "failed-log-id")
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			t.Errorf("accept upstream WebSocket: %v", err)
			return
		}
		defer conn.CloseNow()
		messageType, _, err := conn.Read(r.Context())
		if err != nil || messageType != websocket.MessageBinary {
			t.Errorf("read StartSession: type=%v err=%v", messageType, err)
			return
		}
		writeTestResponse(t, r.Context(), conn, &astproto.TranslateResponse{Event: eventproto.Type_SessionStarted})
		writeTestResponse(t, r.Context(), conn, &astproto.TranslateResponse{
			Event:        eventproto.Type_SessionFailed,
			ResponseMeta: &rpcmetaproto.ResponseMeta{StatusCode: 11200, Message: "denied"},
		})
	}))
	defer server.Close()

	client := &officialClient{
		config:   config.Config{APIKey: "test-api-key", ResourceID: "resource"},
		endpoint: "ws" + strings.TrimPrefix(server.URL, "http"),
	}
	sink := &collectingSink{done: make(chan struct{})}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	session, err := client.Start(ctx, StartRequest{
		SessionID: officialTestSessionID, Mode: "s2s", SourceLanguage: "zh", TargetLanguage: "en",
		TargetAudioFormat: "pcm", TargetAudioRate: 16000,
	}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	select {
	case <-sink.done:
	case <-ctx.Done():
		t.Fatal("timed out waiting for SessionFailed mapping")
	}
	sink.mu.Lock()
	defer sink.mu.Unlock()
	if len(sink.events) != 1 || sink.events[0].Type != "error" || sink.events[0].Code != "VOLCENGINE_SESSION_FAILED" || sink.events[0].LogID != "failed-log-id" {
		t.Fatalf("unexpected SessionFailed mapping: %#v", sink.events)
	}
}

func writeTestResponse(t *testing.T, ctx context.Context, conn *websocket.Conn, response *astproto.TranslateResponse) {
	t.Helper()
	payload, err := proto.Marshal(response)
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.Write(ctx, websocket.MessageBinary, payload); err != nil {
		t.Errorf("write test response: %v", err)
	}
}

func TestOfficialClientUsesLegacyHeaders(t *testing.T) {
	client := &officialClient{config: config.Config{AppID: "app", AccessToken: "token", ResourceID: "resource"}}
	header := client.httpHeader("connect")
	if header.Get("X-Api-App-Id") != "app" || header.Get("X-Api-Access-Key") != "token" || header.Get("X-Api-Key") != "" {
		t.Fatalf("unexpected legacy headers: %#v", header)
	}
}

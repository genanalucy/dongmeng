package ast

import (
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

type blockingTerminalSink struct {
	entered chan struct{}
	release chan struct{}
}

func (s blockingTerminalSink) Emit(Event) { close(s.entered); <-s.release }

func TestQwen38TerminalClosesConnectionBeforeBlockedSink(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	serverClosed := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		defer conn.CloseNow()
		_, _, _ = conn.Read(ctx)
		close(serverClosed)
	}))
	defer server.Close()
	conn, _, err := websocket.Dial(ctx, "wss"+strings.TrimPrefix(server.URL, "https"), &websocket.DialOptions{HTTPClient: server.Client()})
	if err != nil {
		t.Fatal(err)
	}
	inner, stop := context.WithCancel(ctx)
	sink := blockingTerminalSink{make(chan struct{}), make(chan struct{})}
	s := &qwenSession{conn: conn, ctx: inner, cancel: stop, done: make(chan struct{}), sink: sink}
	defer s.Close()
	defer close(sink.release)
	go s.terminal(Event{Type: "error"})
	select {
	case <-sink.entered:
	case <-ctx.Done():
		t.Fatal("terminal not delivered")
	}
	select {
	case <-s.done:
	case <-ctx.Done():
		t.Fatal("blocked sink retained connection")
	}
	select {
	case <-serverClosed:
	case <-ctx.Done():
		t.Fatal("server connection not closed")
	}
}

func TestQwen38RejectsTopLevelPurchaseErrorWithoutWaitingForTimeout(t *testing.T) {
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
		_ = wsjson.Write(ctx, conn, map[string]string{"code": "AccessDenied.Unpurchased", "message": "private billing detail"})
		_, _, _ = conn.Read(ctx)
	}))
	defer server.Close()
	client := qwenClient{apiKey: "test", host: strings.TrimPrefix(server.URL, "https://"), httpClient: server.Client()}
	_, err := client.Start(ctx, StartRequest{TargetLanguage: "en"}, discardSink{})
	if err == nil || err.Error() != "QWEN_SESSION_REJECTED" {
		t.Fatal("top-level provider error was not safely rejected")
	}
}

func TestQwen38SessionFinishedCannotHideUnfinishedAudio(t *testing.T) {
	for _, size := range []int{3, 12} {
		t.Run(string(rune('a'+size)), func(t *testing.T) {
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
				_ = wsjson.Write(ctx, conn, map[string]string{"type": "response.audio.delta", "delta": base64.StdEncoding.EncodeToString(make([]byte, size))})
				_ = wsjson.Write(ctx, conn, map[string]string{"type": "session.finished"})
				_, _, _ = conn.Read(ctx)
			}))
			defer server.Close()
			sink := qwenTestSink{make(chan Event, 8)}
			client := qwenClient{apiKey: "test", host: strings.TrimPrefix(server.URL, "https://"), httpClient: server.Client()}
			s, err := client.Start(ctx, StartRequest{TargetLanguage: "en"}, sink)
			if err != nil {
				t.Fatal(err)
			}
			defer s.Close()
			for {
				select {
				case e := <-sink.events:
					if e.Type == "tts_audio" {
						continue
					}
					if e.Code != "QWEN_INVALID_AUDIO" {
						t.Fatal("unfinished audio accepted")
					}
					return
				case <-ctx.Done():
					t.Fatal("missing terminal error")
				}
			}
		})
	}
}

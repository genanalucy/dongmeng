package azurespeech

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/coder/websocket"

	"translator-agent/internal/ast"
)

func TestContinuousAutomaticSessionRoutesAlternatingFinalsWithoutReconnect(t *testing.T) {
	connections := 0
	ws := newWSServer(t, func(ctx context.Context, conn *websocket.Conn) {
		connections++
		readContext(t, ctx, conn)
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.hypothesis","Text":"hello","Translations":{"zh-Hans":"你好"}}`))
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.phrase","Text":"hello","PrimaryLanguage":{"Language":"en-US"},"RecognitionStatus":"Success","Translations":{"zh-Hans":"你好","en":"hello"}}`))
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"translation.phrase","Text":"你好","PrimaryLanguage":{"Language":"zh-CN"},"RecognitionStatus":"Success","Translations":{"zh-Hans":"你好","en":"hello"}}`))
		<-ctx.Done()
	})
	defer ws.Close()
	voices := make(chan string, 2)
	tts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body := make([]byte, r.ContentLength)
		_, _ = r.Body.Read(body)
		voices <- string(body)
		_, _ = w.Write([]byte{0, 1})
	}))
	defer tts.Close()
	sink := newRecordingSink()
	client := &client{configured: true, key: "secret", region: "japaneast", wsBase: strings.Replace(ws.URL, "http://", "ws://", 1), ttsBase: tts.URL, httpClient: http.DefaultClient}
	session, err := client.Start(context.Background(), ast.StartRequest{SourceLanguage: "zh", TargetLanguage: "en", CandidateLanguages: []string{"zh", "en"}}, sink)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()

	var finals []ast.Event
	for len(finals) < 10 { // detection, source, translation, prelude and PCM per final
		event := sink.next(t)
		if event.Type != "source_partial" {
			finals = append(finals, event)
		}
	}
	if connections != 1 {
		t.Fatalf("connections = %d, want one continuous socket", connections)
	}
	for _, check := range []struct {
		index                  int
		kind, language, target string
		segment                int64
	}{
		{0, "detected_language", "en", "zh", 1}, {1, "source_final", "", "zh", 1}, {2, "translation_final", "", "zh", 1}, {3, "tts_start", "", "zh", 1}, {4, "tts_audio", "", "zh", 1},
		{5, "detected_language", "zh", "en", 2}, {6, "source_final", "", "en", 2}, {7, "translation_final", "", "en", 2}, {8, "tts_start", "", "en", 2}, {9, "tts_audio", "", "en", 2},
	} {
		event := finals[check.index]
		if event.Type != check.kind || event.Language != check.language || event.TargetLanguage != check.target || event.SegmentID != check.segment {
			t.Fatalf("event[%d] = %#v", check.index, event)
		}
	}
	if first, second := <-voices, <-voices; !strings.Contains(first, "zh-CN-XiaoxiaoNeural") || !strings.Contains(second, "en-US-JennyNeural") {
		t.Fatalf("per-direction voices = %q, %q", first, second)
	}
}

package ast

import (
	"context"
	"encoding/base64"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
	"translator-agent/internal/config"
)

const qwenModel = "qwen3.5-livetranslate-flash-realtime"

// QwenError is safe to expose as a product error code; it never retains credentials.
type QwenError struct{ Code string }

func (e QwenError) Error() string { return e.Code }

type routingClient struct{ volc, qwen Client }

// NewRoutingClient uses the configured Qwen realtime client for every shipped
// language pair. The Volcengine client remains injectable for an official AST
// codec build, but the default Agent build deliberately has no such codec and
// must never route a supported product pair to ErrCodecUnavailable.
func NewRoutingClient(volc Client, cfg config.Config) Client {
	return routingClient{volc: volc, qwen: NewQwenClient(cfg)}
}

func (c routingClient) Start(ctx context.Context, request StartRequest, sink EventSink) (Session, error) {
	return c.qwen.Start(ctx, request, sink)
}

type qwenClient struct{ apiKey, host string }

func NewQwenClient(cfg config.Config) Client {
	return qwenClient{apiKey: cfg.DashScopeAPIKey, host: cfg.QwenAPIHost}
}

func (c qwenClient) Start(ctx context.Context, request StartRequest, sink EventSink) (Session, error) {
	if c.apiKey == "" || c.host == "" {
		return nil, QwenError{Code: "QWEN_CONFIGURATION_MISSING"}
	}
	endpoint := url.URL{Scheme: "wss", Host: c.host, Path: "/api-ws/v1/realtime"}
	query := endpoint.Query()
	query.Set("model", qwenModel)
	endpoint.RawQuery = query.Encode()
	dialCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(dialCtx, endpoint.String(), &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + c.apiKey}}})
	if err != nil {
		return nil, QwenError{Code: "QWEN_CONNECT_FAILED"}
	}
	session := &qwenSession{conn: conn, sink: sink, done: make(chan struct{})}
	update := map[string]any{"type": "session.update", "session": map[string]any{
		"modalities": []string{"text", "audio"}, "input_audio_format": "pcm", "output_audio_format": "pcm",
		"input_audio_transcription": map[string]string{"model": "qwen3-asr-flash-realtime", "language": request.SourceLanguage},
		"translation":               map[string]string{"language": request.TargetLanguage},
	}}
	if err := session.write(ctx, update); err != nil {
		_ = conn.CloseNow()
		return nil, QwenError{Code: "QWEN_SESSION_UPDATE_FAILED"}
	}
	if err := session.awaitConfigured(ctx); err != nil {
		_ = conn.CloseNow()
		return nil, err
	}
	go session.readLoop()
	return session, nil
}

type qwenSession struct {
	conn    *websocket.Conn
	sink    EventSink
	writeMu sync.Mutex
	done    chan struct{}
	once    sync.Once
}

func (s *qwenSession) write(ctx context.Context, message any) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return wsjson.Write(ctx, s.conn, message)
}
func (s *qwenSession) SendAudio(ctx context.Context, pcm []byte) error {
	return s.write(ctx, map[string]any{"type": "input_audio_buffer.append", "audio": base64.StdEncoding.EncodeToString(pcm)})
}
func (s *qwenSession) Finish(ctx context.Context) error {
	return s.write(ctx, map[string]any{"type": "session.finish"})
}
func (s *qwenSession) Close() error {
	s.once.Do(func() { close(s.done); _ = s.conn.CloseNow() })
	return nil
}

type qwenEvent struct {
	Type       string `json:"type"`
	Text       string `json:"text"`
	Stash      string `json:"stash"`
	Transcript string `json:"transcript"`
	Delta      string `json:"delta"`
	Error      struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

func (s *qwenSession) awaitConfigured(ctx context.Context) error {
	for {
		var event qwenEvent
		if err := wsjson.Read(ctx, s.conn, &event); err != nil {
			return QwenError{Code: "QWEN_SESSION_UPDATE_FAILED"}
		}
		switch event.Type {
		case "session.updated":
			return nil
		case "error":
			return QwenError{Code: nonEmpty(event.Error.Code, "QWEN_SESSION_UPDATE_FAILED")}
		}
	}
}
func (s *qwenSession) readLoop() {
	defer s.Close()
	for {
		var event qwenEvent
		if err := wsjson.Read(context.Background(), s.conn, &event); err != nil {
			return
		}
		switch event.Type {
		case "conversation.item.input_audio_transcription.text":
			s.emit("source_partial", event.Text+event.Stash)
		case "conversation.item.input_audio_transcription.completed":
			s.emit("source_final", event.Transcript)
		case "response.audio_transcript.text":
			s.emit("translation_partial", event.Text+event.Stash)
		case "response.audio_transcript.done":
			s.emit("translation_final", event.Transcript)
		case "response.audio.delta":
			if pcm, err := base64.StdEncoding.DecodeString(event.Delta); err == nil && len(pcm)%2 == 0 {
				s.sink.Emit(Event{Type: "tts_audio", Binary: resample24kTo16k(pcm)})
			}
		case "session.finished":
			s.sink.Emit(Event{Type: "finished"})
			return
		case "error":
			s.sink.Emit(Event{Type: "error", Code: nonEmpty(event.Error.Code, "QWEN_ERROR"), Message: "Qwen translation service error"})
			return
		}
	}
}
func (s *qwenSession) emit(kind, text string) {
	if strings.TrimSpace(text) != "" {
		s.sink.Emit(Event{Type: kind, Message: text})
	}
}
func nonEmpty(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

// resample24kTo16k downsamples signed 16-bit mono PCM by 3:2 using deterministic decimation.
func resample24kTo16k(input []byte) []byte {
	samples := len(input) / 2
	if samples == 0 {
		return nil
	}
	outputSamples := samples * 2 / 3
	output := make([]byte, outputSamples*2)
	for i := 0; i < outputSamples; i++ {
		source := i * 3 / 2
		value := int16(input[source*2]) | int16(input[source*2+1])<<8
		output[i*2] = byte(value)
		output[i*2+1] = byte(value >> 8)
	}
	return output
}

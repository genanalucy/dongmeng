package ast

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
	"translator-agent/internal/config"
)

const qwenModel = "qwen3.8-livetranslate-flash-realtime"

// Only fixed error codes cross this boundary, never provider messages/credentials.
type QwenError struct{ Code string }

func (e QwenError) Error() string { return e.Code }

// Provider selection is explicit. Neither provider silently falls back to the other.
func NewRoutingClient(volc Client, cfg config.Config) Client {
	if cfg.TranslationProvider == "qwen" {
		return NewQwenClient(cfg)
	}
	return volc
}

type qwenClient struct {
	apiKey, host string
	httpClient   *http.Client
}

func NewQwenClient(cfg config.Config) Client {
	return qwenClient{apiKey: cfg.DashScopeAPIKey, host: cfg.QwenAPIHost}
}
func (c qwenClient) Start(ctx context.Context, request StartRequest, sink EventSink) (Session, error) {
	if c.apiKey == "" || c.host == "" {
		return nil, QwenError{"QWEN_CONFIGURATION_MISSING"}
	}
	endpoint := url.URL{Scheme: "wss", Host: c.host, Path: "/api-ws/v1/realtime"}
	query := endpoint.Query()
	query.Set("model", qwenModel)
	endpoint.RawQuery = query.Encode()
	dialCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	conn, _, err := websocket.Dial(dialCtx, endpoint.String(), &websocket.DialOptions{HTTPClient: c.httpClient, HTTPHeader: http.Header{"Authorization": {"Bearer " + c.apiKey}}})
	cancel()
	if err != nil {
		return nil, QwenError{"QWEN_CONNECT_FAILED"}
	}
	conn.SetReadLimit(2 << 20)
	sessionCtx, stop := context.WithCancel(ctx)
	s := &qwenSession{conn: conn, sink: sink, ctx: sessionCtx, cancel: stop, done: make(chan struct{}), source: make(map[string]string), translation: make(map[string]string)}
	readyCtx, readyCancel := context.WithTimeout(sessionCtx, 15*time.Second)
	defer readyCancel()
	update := map[string]any{"type": "session.update", "session": map[string]any{
		"output_modalities":  []string{"text", "audio"},
		"audio":              map[string]any{"input": map[string]any{"format": map[string]any{"type": "pcm", "sample_rate": 16000}, "turn_detection": map[string]any{"type": "server_vad"}}},
		"enable_voice_clone": false,
		"translation":        map[string]string{"language": request.TargetLanguage},
	}}
	if err = s.write(readyCtx, update); err != nil {
		s.Close()
		return nil, QwenError{"QWEN_SESSION_UPDATE_FAILED"}
	}
	for {
		var event qwenEvent
		if err = wsjson.Read(readyCtx, conn, &event); err != nil {
			s.Close()
			return nil, QwenError{"QWEN_SESSION_UPDATE_FAILED"}
		}
		if event.Code != "" || event.Type == "error" {
			s.Close()
			return nil, QwenError{"QWEN_SESSION_REJECTED"}
		}
		if event.Type == "session.updated" {
			if !validQwenSession(event.Session) {
				s.Close()
				return nil, QwenError{"QWEN_SESSION_FORMAT_MISMATCH"}
			}
			break
		}
	}
	go s.readLoop()
	go func() {
		select {
		case <-sessionCtx.Done():
			s.Close()
		case <-s.done:
		}
	}()
	// Bound even a continuously-fed forgotten session; no automatic paid reconnect.
	s.timerMu.Lock()
	select {
	case <-s.done:
	default:
		s.lifetimeTimer = time.AfterFunc(15*time.Minute, func() {
			s.terminal(Event{Type: "error", Code: "QWEN_SESSION_LIMIT", Message: "translation session reached its time limit"})
		})
	}
	s.timerMu.Unlock()
	return s, nil
}

type qwenAudioFormat struct {
	Type       string `json:"type"`
	SampleRate int    `json:"sample_rate"`
}
type qwenSessionConfig struct {
	Model string `json:"model"`
	Audio struct {
		Input struct {
			Format qwenAudioFormat `json:"format"`
		} `json:"input"`
		Output struct {
			Format qwenAudioFormat `json:"format"`
		} `json:"output"`
	} `json:"audio"`
}

func validQwenSession(s qwenSessionConfig) bool {
	return s.Model == qwenModel && s.Audio.Input.Format.Type == "pcm" && s.Audio.Input.Format.SampleRate == 16000 && s.Audio.Output.Format.Type == "pcm" && s.Audio.Output.Format.SampleRate == 24000
}

type qwenSession struct {
	conn            *websocket.Conn
	sink            EventSink
	ctx             context.Context
	cancel          context.CancelFunc
	done            chan struct{}
	closeOnce       sync.Once
	terminalOnce    sync.Once
	finishOnce      sync.Once
	finishErr       error
	writeMu         sync.Mutex
	timerMu         sync.Mutex
	finishTimer     *time.Timer
	lifetimeTimer   *time.Timer
	source          map[string]string
	translation     map[string]string
	resampler       pcm24To16
	audioInProgress bool
}

func (s *qwenSession) write(ctx context.Context, m any) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	writeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return wsjson.Write(writeCtx, s.conn, m)
}
func (s *qwenSession) SendAudio(ctx context.Context, pcm []byte) error {
	if len(pcm)%2 != 0 {
		return QwenError{"QWEN_INVALID_PCM"}
	}
	return s.write(ctx, map[string]any{"type": "input_audio_buffer.append", "audio": base64.StdEncoding.EncodeToString(pcm)})
}
func (s *qwenSession) Finish(ctx context.Context) error {
	s.finishOnce.Do(func() {
		s.finishErr = s.write(ctx, map[string]string{"type": "session.finish"})
		if s.finishErr != nil {
			s.Close()
			return
		}
		s.timerMu.Lock()
		select {
		case <-s.done:
		default:
			s.finishTimer = time.AfterFunc(20*time.Second, func() {
				s.terminal(Event{Type: "error", Code: "QWEN_FINISH_TIMEOUT", Message: "translation session did not finish in time"})
			})
		}
		s.timerMu.Unlock()
	})
	return s.finishErr
}
func (s *qwenSession) Close() error {
	s.closeOnce.Do(func() {
		s.cancel()
		close(s.done)
		_ = s.conn.CloseNow()
		s.timerMu.Lock()
		if s.finishTimer != nil {
			s.finishTimer.Stop()
		}
		if s.lifetimeTimer != nil {
			s.lifetimeTimer.Stop()
		}
		s.timerMu.Unlock()
	})
	return nil
}
func (s *qwenSession) terminal(e Event) {
	s.Close() // Never retain a billable connection while downstream delivery blocks.
	s.terminalOnce.Do(func() { s.sink.Emit(e) })
}

type qwenEvent struct {
	Code       string            `json:"code"`
	Type       string            `json:"type"`
	ItemID     string            `json:"item_id"`
	ResponseID string            `json:"response_id"`
	Delta      string            `json:"delta"`
	Text       string            `json:"text"`
	Transcript string            `json:"transcript"`
	Session    qwenSessionConfig `json:"session"`
	Response   struct {
		Status string `json:"status"`
	} `json:"response"`
}

func (s *qwenSession) readLoop() {
	defer s.Close()
	for {
		var e qwenEvent
		if wsjson.Read(s.ctx, s.conn, &e) != nil {
			if s.ctx.Err() == nil {
				s.terminal(Event{Type: "error", Code: "QWEN_CONNECTION_CLOSED", Message: "translation connection closed before completion"})
			}
			return
		}
		if e.Code != "" {
			s.terminal(Event{Type: "error", Code: "QWEN_UPSTREAM_ERROR", Message: "Qwen translation service error"})
			return
		}
		switch e.Type {
		case "conversation.item.input_audio_transcription.delta":
			if !s.accumulate(s.source, e.ItemID, e.Delta, "source_partial") {
				return
			}
		case "conversation.item.input_audio_transcription.completed":
			s.emit("source_final", e.Transcript)
			delete(s.source, e.ItemID)
		case "response.audio_transcript.delta", "response.text.delta":
			if !s.accumulate(s.translation, e.ItemID, e.Delta, "translation_partial") {
				return
			}
		case "response.audio_transcript.done", "response.text.done":
			text := e.Transcript
			if text == "" {
				text = e.Text
			}
			if text == "" {
				text = s.translation[e.ItemID]
			}
			s.emit("translation_final", text)
			delete(s.translation, e.ItemID)
		case "response.audio.delta":
			raw, err := base64.StdEncoding.DecodeString(e.Delta)
			if err != nil {
				s.terminal(Event{Type: "error", Code: "QWEN_INVALID_AUDIO", Message: "translation returned invalid audio"})
				return
			}
			s.audioInProgress = true
			pcm := s.resampler.offer(raw)
			if len(pcm) > 0 {
				s.sink.Emit(Event{Type: "tts_audio", Binary: pcm})
			}
		case "response.audio.done":
			if len(s.resampler.tail)%2 != 0 {
				s.terminal(Event{Type: "error", Code: "QWEN_INVALID_AUDIO", Message: "translation returned truncated audio"})
				return
			}
			s.resampler = pcm24To16{}
			s.audioInProgress = false
		case "response.done":
			if e.Response.Status != "completed" {
				s.terminal(Event{Type: "error", Code: "QWEN_RESPONSE_FAILED", Message: "translation response did not complete"})
				return
			}
			// A response is one utterance, NOT the end of the WebSocket session.
		case "session.finished":
			if s.audioInProgress || len(s.resampler.tail)%2 != 0 {
				s.terminal(Event{Type: "error", Code: "QWEN_INVALID_AUDIO", Message: "translation ended before audio completion"})
				return
			}
			s.terminal(Event{Type: "finished"})
			return
		case "error", "conversation.item.input_audio_transcription.failed":
			s.terminal(Event{Type: "error", Code: "QWEN_UPSTREAM_ERROR", Message: "Qwen translation service error"})
			return
		}
	}
}
func (s *qwenSession) accumulate(store map[string]string, id, delta, kind string) bool {
	if id == "" || len(store) > 128 || len(store[id])+len(delta) > 128*1024 {
		s.terminal(Event{Type: "error", Code: "QWEN_EVENT_LIMIT", Message: "translation event exceeded safe bounds"})
		return false
	}
	store[id] += delta
	s.emit(kind, store[id])
	return true
}
func (s *qwenSession) emit(kind, text string) {
	if strings.TrimSpace(text) != "" {
		s.sink.Emit(Event{Type: kind, Message: text})
	}
}

// Streaming 24k->16k linear resampling. Preserve partial samples and phase across
// arbitrary WebSocket audio chunks instead of dropping every chunk's remainder.
type pcm24To16 struct {
	tail  []byte
	phase int
}

func (r *pcm24To16) offer(input []byte) []byte {
	r.tail = append(r.tail, input...)
	n := len(r.tail) / 2
	var out []byte
	for r.phase/2+1 < n {
		i := r.phase / 2
		a := int32(int16(binary.LittleEndian.Uint16(r.tail[i*2:])))
		b := int32(int16(binary.LittleEndian.Uint16(r.tail[(i+1)*2:])))
		value := a
		if r.phase%2 == 1 {
			value = (a + b) / 2
		}
		out = binary.LittleEndian.AppendUint16(out, uint16(int16(value)))
		r.phase += 3
	}
	consumed := r.phase / 2
	if consumed > n {
		consumed = n
	}
	r.tail = append([]byte(nil), r.tail[consumed*2:]...)
	r.phase -= consumed * 2
	return out
}
func resample24kTo16k(input []byte) []byte { var r pcm24To16; return r.offer(input) }

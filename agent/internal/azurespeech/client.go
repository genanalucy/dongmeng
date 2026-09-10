// Package azurespeech provides an Azure Speech translation client.
package azurespeech

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"

	"github.com/coder/websocket"

	"translator-agent/internal/ast"
)

var ErrUnavailable = ast.ErrProviderUnavailable

// Config contains Azure Speech credentials loaded by the embedding application.
type Config struct{ Key, Region string }

// LocaleForLanguage maps protocol language codes to Azure Speech locales.
func LocaleForLanguage(language string) (string, bool) {
	locale, ok := map[string]string{"zh": "zh-CN", "en": "en-US", "fr": "fr-FR", "vi": "vi-VN"}[language]
	return locale, ok
}

func IsVoiceAllowed(voice string, candidateLanguages []string) bool {
	for _, language := range candidateLanguages {
		locale, ok := LocaleForLanguage(language)
		if !ok {
			continue
		}
		for _, allowed := range voicesByLocale[locale] {
			if voice == allowed {
				return true
			}
		}
	}
	return false
}

var voicesByLocale = map[string][]string{
	"zh-CN": {"zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural"},
	"en-US": {"en-US-JennyNeural", "en-US-GuyNeural"},
	"fr-FR": {"fr-FR-DeniseNeural", "fr-FR-HenriNeural"},
	"vi-VN": {"vi-VN-HoaiMyNeural", "vi-VN-NamMaleNeural"},
}

var defaultVoice = map[string]string{
	"zh-CN": "zh-CN-XiaoxiaoNeural", "en-US": "en-US-JennyNeural",
	"fr-FR": "fr-FR-DeniseNeural", "vi-VN": "vi-VN-HoaiMyNeural",
}

type client struct {
	configured  bool
	key, region string
	// wsBase and ttsBase are deliberately package-private test seams.
	wsBase, ttsBase string
	httpClient      *http.Client
}

func New(cfg Config) ast.Client {
	return &client{
		configured: cfg.Key != "" && cfg.Region != "", key: cfg.Key, region: cfg.Region,
		wsBase:     "wss://" + cfg.Region + ".stt.speech.microsoft.com",
		ttsBase:    "https://" + cfg.Region + ".tts.speech.microsoft.com",
		httpClient: http.DefaultClient,
	}
}

func (c *client) Start(ctx context.Context, request ast.StartRequest, sink ast.EventSink) (ast.Session, error) {
	if !c.configured {
		return nil, ErrUnavailable
	}
	from, ok := LocaleForLanguage(request.SourceLanguage)
	if !ok {
		return nil, ErrUnavailable
	}
	to, ok := LocaleForLanguage(request.TargetLanguage)
	if !ok {
		return nil, ErrUnavailable
	}
	if sink == nil {
		return nil, errors.New("Azure Speech event sink is required")
	}
	endpoint, err := translationEndpoint(c.wsBase, from, to)
	if err != nil {
		return nil, fmt.Errorf("build Azure endpoint: %w", err)
	}
	headers := make(http.Header)
	headers.Set("Ocp-Apim-Subscription-Key", c.key)
	conn, _, err := websocket.Dial(ctx, endpoint, &websocket.DialOptions{HTTPHeader: headers})
	if err != nil {
		return nil, fmt.Errorf("dial Azure Speech: %w", err)
	}
	session := newSession(ctx, conn, c, to, request.Voice, sink)
	if err := session.send(ctx, websocket.MessageText, speechConfig); err != nil {
		_ = session.Close()
		return nil, fmt.Errorf("send Azure speech.config: %w", err)
	}
	session.wg.Add(1)
	go session.readLoop()
	return session, nil
}

func translationEndpoint(base, from, to string) (string, error) {
	u, err := url.Parse(strings.TrimRight(base, "/") + "/speech/translation/cognitiveservices/v1")
	if err != nil {
		return "", err
	}
	q := u.Query()
	q.Set("language", from)
	q.Set("to", to)
	q.Set("format", "simple")
	u.RawQuery = q.Encode()
	return u.String(), nil
}

var speechConfig = []byte(`{"context":{"system":{"name":"dngmeng-agent","version":"1.0.0"},"os":{"platform":"linux"},"device":{"manufacturer":"dngmeng"}}}`)

type writeRequest struct {
	typ     websocket.MessageType
	payload []byte
	result  chan error
}

type session struct {
	ctx        context.Context
	cancel     context.CancelFunc
	conn       *websocket.Conn
	client     *client
	to, voice  string
	sink       ast.EventSink
	writes     chan writeRequest
	wg         sync.WaitGroup
	closeOnce  sync.Once
	finishOnce sync.Once
	finishErr  error

	// commandMu orders SendAudio and Finish, so no audio can be submitted after
	// a completed Finish call.
	commandMu                                    sync.Mutex
	stateMu                                      sync.Mutex
	accepting, active, finishRequested, terminal bool
	// eventMu serializes text, synchronous TTS, errors, and finished.
	eventMu sync.Mutex
	errMu   sync.Mutex
	err     error
}

func newSession(parent context.Context, conn *websocket.Conn, c *client, to, voice string, sink ast.EventSink) *session {
	ctx, cancel := context.WithCancel(parent)
	s := &session{ctx: ctx, cancel: cancel, conn: conn, client: c, to: to, voice: voice, sink: sink, writes: make(chan writeRequest), accepting: true}
	s.wg.Add(1)
	go s.writeLoop()
	return s
}

func (s *session) SendAudio(ctx context.Context, pcm []byte) error {
	s.commandMu.Lock()
	defer s.commandMu.Unlock()
	s.stateMu.Lock()
	accepting := s.accepting && !s.terminal
	s.stateMu.Unlock()
	if !accepting {
		return context.Canceled
	}
	if len(pcm) > 65535 {
		return fmt.Errorf("Azure audio chunk exceeds 65535 bytes")
	}
	frame := make([]byte, len(pcm)+2)
	binary.BigEndian.PutUint16(frame, uint16(len(pcm)))
	copy(frame[2:], pcm)
	return s.send(ctx, websocket.MessageBinary, frame)
}

func (s *session) Finish(context.Context) error {
	s.finishOnce.Do(func() {
		s.eventMu.Lock()
		defer s.eventMu.Unlock()
		s.commandMu.Lock()
		defer s.commandMu.Unlock()
		s.stateMu.Lock()
		s.accepting = false
		s.finishRequested = true
		active := s.active
		s.stateMu.Unlock()
		if !active {
			s.finishedLocked()
		}
	})
	return s.finishErr
}

func (s *session) Close() error {
	s.closeOnce.Do(func() { s.cancel(); _ = s.conn.CloseNow(); s.wg.Wait() })
	return nil
}

func (s *session) send(ctx context.Context, typ websocket.MessageType, payload []byte) error {
	request := writeRequest{typ: typ, payload: append([]byte(nil), payload...), result: make(chan error, 1)}
	select {
	case s.writes <- request:
	case <-ctx.Done():
		return ctx.Err()
	case <-s.ctx.Done():
		return s.sessionError()
	}
	select {
	case err := <-request.result:
		return err
	case <-ctx.Done():
		return ctx.Err()
	case <-s.ctx.Done():
		return s.sessionError()
	}
}
func (s *session) writeLoop() {
	defer s.wg.Done()
	for {
		select {
		case request := <-s.writes:
			err := s.conn.Write(s.ctx, request.typ, request.payload)
			request.result <- err
			if err != nil {
				s.fail("AZURE_SESSION_FAILED", "translation session failed", err)
				return
			}
		case <-s.ctx.Done():
			return
		}
	}
}
func (s *session) readLoop() {
	defer s.wg.Done()
	for {
		typ, data, err := s.conn.Read(s.ctx)
		if err != nil {
			if s.ctx.Err() == nil {
				s.fail("AZURE_SESSION_FAILED", "translation session failed", err)
			}
			return
		}
		if typ != websocket.MessageText {
			s.fail("AZURE_SESSION_FAILED", "translation session failed", errors.New("unexpected Azure WebSocket frame"))
			return
		}
		if err := s.handleMessage(data); err != nil {
			var ttsErr azureTTSError
			if errors.As(err, &ttsErr) {
				s.fail("AZURE_TTS_FAILED", "text-to-speech synthesis failed", err)
			} else {
				s.fail("AZURE_SESSION_FAILED", "translation session failed", err)
			}
			return
		}
	}
}

type upstreamMessage struct {
	Type         string            `json:"type"`
	Text         string            `json:"text"`
	Translations map[string]string `json:"translations"`
	Signal       struct {
		Name string `json:"name"`
	} `json:"signal"`
}

func (s *session) handleMessage(data []byte) error {
	var message upstreamMessage
	if err := json.Unmarshal(data, &message); err != nil {
		return fmt.Errorf("decode Azure message: %w", err)
	}
	if message.Type == "speech.event" && message.Signal.Name == "telemetry" {
		return s.send(s.ctx, websocket.MessageText, []byte(`{"type":"telemetry","receivedMessages":[]}`))
	}
	s.eventMu.Lock()
	defer s.eventMu.Unlock()
	switch message.Type {
	case "translation.hypothesis":
		s.sink.Emit(ast.Event{Type: "source_partial", Message: message.Text})
		s.sink.Emit(ast.Event{Type: "translation_partial", Message: message.Translations[s.to]})
	case "translation.result":
		s.sink.Emit(ast.Event{Type: "source_final", Message: message.Text})
		translation := message.Translations[s.to]
		s.sink.Emit(ast.Event{Type: "translation_final", Message: translation})
		if err := s.synthesizeLocked(translation); err != nil {
			return azureTTSError{err}
		}
	case "speech.startDetected":
		s.stateMu.Lock()
		s.active = true
		s.stateMu.Unlock()
	case "turn.end":
		s.stateMu.Lock()
		s.active = false
		finish := s.finishRequested
		s.stateMu.Unlock()
		if finish {
			s.finishedLocked()
		}
	}
	return nil
}

type azureTTSError struct{ error }

func (s *session) synthesizeLocked(text string) error {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	voice := s.voice
	if voice == "" {
		voice = defaultVoice[s.to]
	}
	ssml := "<speak version='1.0' xml:lang='" + s.to + "'><voice name='" + xmlEscape(voice) + "'>" + xmlEscape(text) + "</voice></speak>"
	req, err := http.NewRequestWithContext(s.ctx, http.MethodPost, strings.TrimRight(s.client.ttsBase, "/")+"/cognitiveservices/v1", strings.NewReader(ssml))
	if err != nil {
		return err
	}
	req.Header.Set("Ocp-Apim-Subscription-Key", s.client.key)
	req.Header.Set("Content-Type", "application/ssml+xml")
	req.Header.Set("X-Microsoft-OutputFormat", "raw-16khz-16bit-mono-pcm")
	req.Header.Set("User-Agent", "dngmeng-agent")
	response, err := s.client.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	pcm, err := io.ReadAll(response.Body)
	if err != nil {
		return err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("Azure TTS returned HTTP %d", response.StatusCode)
	}
	if len(pcm) == 0 || len(pcm)%2 != 0 {
		return errors.New("Azure TTS returned invalid PCM")
	}
	s.sink.Emit(ast.Event{Type: "tts_audio", Binary: pcm})
	return nil
}
func xmlEscape(value string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", "'", "&apos;", `"`, "&quot;").Replace(value)
}
func (s *session) finishedLocked() {
	s.stateMu.Lock()
	if s.terminal {
		s.stateMu.Unlock()
		return
	}
	s.terminal = true
	s.stateMu.Unlock()
	s.sink.Emit(ast.Event{Type: "finished"})
	s.cancel()
	_ = s.conn.CloseNow()
}
func (s *session) fail(code, message string, err error) {
	s.eventMu.Lock()
	defer s.eventMu.Unlock()
	s.stateMu.Lock()
	if s.terminal {
		s.stateMu.Unlock()
		return
	}
	s.terminal = true
	s.accepting = false
	s.stateMu.Unlock()
	s.errMu.Lock()
	if s.err == nil {
		s.err = err
	}
	s.errMu.Unlock()
	s.sink.Emit(ast.Event{Type: "error", Code: code, Message: message})
	s.cancel()
	_ = s.conn.CloseNow()
}
func (s *session) sessionError() error {
	s.errMu.Lock()
	defer s.errMu.Unlock()
	if s.err != nil {
		return s.err
	}
	return context.Canceled
}

// Package azurespeech provides an Azure Speech translation client.
package azurespeech

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

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

func translationLanguage(language string) (string, bool) {
	code, ok := map[string]string{"zh": "zh-Hans", "en": "en", "fr": "fr", "vi": "vi"}[language]
	return code, ok
}

func protocolLanguage(locale string) (string, bool) {
	language := strings.ToLower(strings.TrimSpace(locale))
	if code, ok := map[string]string{
		"zh": "zh", "zh-cn": "zh", "zh-hans": "zh",
		"en": "en", "en-us": "en",
		"fr": "fr", "fr-fr": "fr",
		"vi": "vi", "vi-vn": "vi",
	}[language]; ok {
		return code, true
	}
	return "", false
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
	automatic := len(request.CandidateLanguages) != 0
	if automatic && !validCandidates(request.CandidateLanguages) {
		return nil, ErrUnavailable
	}
	endpoint, err := translationEndpoint(c.wsBase, from, to, request.CandidateLanguages)
	if err != nil {
		return nil, fmt.Errorf("build Azure endpoint: %w", err)
	}
	headers := make(http.Header)
	headers.Set("Ocp-Apim-Subscription-Key", c.key)
	conn, _, err := websocket.Dial(ctx, endpoint, &websocket.DialOptions{HTTPHeader: headers})
	if err != nil {
		return nil, fmt.Errorf("dial Azure Speech: %w", err)
	}
	session := newSession(ctx, conn, c, to, request.Voice, request.CandidateLanguages, sink)
	config, err := legacySpeechConfig()
	configPath := "speech.config"
	if automatic {
		// Universal v2 requires the context object itself, unlike legacy
		// speech.config which wraps that object in {"context": ...}.
		config, err = automaticSpeechContext(request.CandidateLanguages)
		configPath = "speech.context"
	}
	if err != nil {
		_ = session.Close()
		return nil, fmt.Errorf("build Azure speech configuration: %w", err)
	}
	if err := session.send(ctx, websocket.MessageText, textFrameWithRequestID(session.requestID, configPath, config)); err != nil {
		_ = session.Close()
		return nil, fmt.Errorf("send Azure speech.config: %w", err)
	}
	session.wg.Add(1)
	go session.readLoop()
	return session, nil
}

func translationEndpoint(base, from, to string, candidates []string) (string, error) {
	path := "/speech/translation/cognitiveservices/v1"
	translationTargets := []string{to}
	if len(candidates) != 0 {
		// The official Azure Speech SDK's TranslationConnectionFactory uses
		// /stt/speech/universal/v2 for V2 translation (not /speech/universal/v2).
		path = "/stt/speech/universal/v2"
		translationTargets = make([]string, 0, len(candidates))
		for _, candidate := range candidates {
			code, _ := translationLanguage(candidate)
			translationTargets = append(translationTargets, code)
		}
	}
	u, err := url.Parse(strings.TrimRight(base, "/") + path)
	if err != nil {
		return "", err
	}
	q := u.Query()
	q.Set("from", from)
	q.Set("to", strings.Join(translationTargets, ","))
	q.Set("format", "simple")
	if len(candidates) != 0 {
		q.Set("scenario", "interactive")
	}
	u.RawQuery = q.Encode()
	return u.String(), nil
}

func validCandidates(candidates []string) bool {
	if len(candidates) != 2 || candidates[0] == candidates[1] {
		return false
	}
	for _, candidate := range candidates {
		if _, ok := LocaleForLanguage(candidate); !ok {
			return false
		}
	}
	return true
}

func legacySpeechConfig() (string, error) {
	context, err := speechContext(nil)
	if err != nil {
		return "", err
	}
	body, err := json.Marshal(map[string]any{"context": context})
	return string(body), err
}

func automaticSpeechContext(candidates []string) (string, error) {
	context, err := speechContext(candidates)
	if err != nil {
		return "", err
	}
	body, err := json.Marshal(context)
	return string(body), err
}

func speechContext(candidates []string) (map[string]any, error) {
	context := map[string]any{
		"system": map[string]string{"name": "dngmeng-agent", "version": "1.0.0"},
		"os":     map[string]string{"platform": "linux"},
		"device": map[string]string{"manufacturer": "dngmeng"},
	}
	if len(candidates) != 0 {
		locales := make([]string, 0, len(candidates))
		targets := make([]string, 0, len(candidates))
		for _, candidate := range candidates {
			locale, _ := LocaleForLanguage(candidate)
			target, _ := translationLanguage(candidate)
			locales = append(locales, locale)
			targets = append(targets, target)
		}
		context["languageId"] = map[string]any{
			// Universal v2 continuous recognition uses the exact value emitted by
			// the official JS SDK when LanguageIdMode.Continuous is selected.
			"languages": locales, "mode": "DetectContinuous",
			"onSuccess": map[string]string{"action": "Recognize"},
			"onUnknown": map[string]string{"action": "None"}, "priority": "PrioritizeLatency",
		}
		context["translation"] = map[string]any{
			"onPassthrough":   map[string]string{"action": "None"},
			"onSuccess":       map[string]string{"action": "None"},
			"output":          map[string]any{"includePassThroughResults": true, "interimResults": map[string]string{"mode": "Always"}},
			"targetLanguages": targets,
		}
		context["phraseDetection"] = map[string]any{
			"onInterim": map[string]string{"action": "Translate"},
			"onSuccess": map[string]string{"action": "Translate"},
		}
	}
	return context, nil
}

// Azure text frames carry `X-RequestId`/`Path` headers separated from the
// JSON body by a blank line; a bare JSON payload is rejected with
// "Text message contains no header separator".
const headerBodySeparator = "\r\n\r\n"

func textFrame(path, body string) []byte {
	return textFrameWithRequestID(randomRequestID(), path, body)
}

func textFrameWithRequestID(requestID, path, body string) []byte {
	return []byte("X-RequestId:" + requestID + "\r\nPath:" + path + "\r\nContent-Type:application/json; charset=utf-8" + headerBodySeparator + body)
}

func randomRequestID() string {
	var buf [16]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "00000000000000000000000000000000"
	}
	return hex.EncodeToString(buf[:])
}

// splitMessage separates the `X-RequestId:...\r\nPath:<path>\r\n...\r\n\r\n`
// header block Azure prefixes to every text message, returning the Path value
// and the JSON body. Headerless messages fall back to a "" path (fake
// upstreams) and the JSON `type` field.
func splitMessage(data []byte) (path string, body []byte) {
	idx := bytes.Index(data, []byte(headerBodySeparator))
	if idx < 0 {
		return "", data
	}
	for _, line := range strings.Split(string(data[:idx]), "\r\n") {
		if strings.HasPrefix(line, "Path:") {
			path = strings.TrimSpace(strings.TrimPrefix(line, "Path:"))
		}
	}
	return path, data[idx+len(headerBodySeparator):]
}

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
	candidates []string
	requestID  string
	sink       ast.EventSink
	writes     chan writeRequest
	wg         sync.WaitGroup
	closeOnce  sync.Once
	finishOnce sync.Once
	finishErr  error

	// commandMu orders SendAudio and Finish, so no audio can be submitted after
	// a completed Finish call.
	commandMu                                               sync.Mutex
	stateMu                                                 sync.Mutex
	accepting, active, turnEnded, finishRequested, terminal bool
	riffSent                                                bool
	segmentID                                               int64
	// eventMu serializes text, synchronous TTS, errors, and finished.
	eventMu sync.Mutex
	errMu   sync.Mutex
	err     error
}

func newSession(parent context.Context, conn *websocket.Conn, c *client, to, voice string, candidates []string, sink ast.EventSink) *session {
	ctx, cancel := context.WithCancel(parent)
	s := &session{ctx: ctx, cancel: cancel, conn: conn, client: c, to: to, voice: voice, candidates: append([]string(nil), candidates...), requestID: randomRequestID(), sink: sink, writes: make(chan writeRequest), accepting: true}
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
	// Unified Speech Protocol audio frame: [2-byte BE header length][header
	// block (Path:audio, Content-Type:audio/x-wav)][payload]; the first frame
	// prepends a 44-byte RIFF header declaring 16k/mono/16-bit PCM.
	payload := pcm
	if !s.riffSent {
		payload = append(riffHeaderPCM16k(), pcm...)
		s.riffSent = true
	}
	return s.send(ctx, websocket.MessageBinary, s.audioFrame(payload))
}

// audioFrame encodes an audio fragment. An empty payload marks end-of-stream
// after the preceding PCM frames, so Azure commits the active recognition turn.
func (s *session) audioFrame(payload []byte) []byte {
	header := "X-RequestId:" + s.requestID + "\r\nPath:audio\r\nContent-Type:audio/x-wav\r\nX-Timestamp:" + azureTimestamp() + "\r\n\r\n"
	frame := make([]byte, 2+len(header)+len(payload))
	binary.BigEndian.PutUint16(frame, uint16(len(header)))
	copy(frame[2:], header)
	copy(frame[2+len(header):], payload)
	return frame
}

// riffHeaderPCM16k declares the stream format once, in the first audio frame.
func riffHeaderPCM16k() []byte {
	h := make([]byte, 44)
	copy(h[0:4], "RIFF")
	binary.LittleEndian.PutUint32(h[4:8], 0xffffffff)
	copy(h[8:12], "WAVE")
	copy(h[12:16], "fmt ")
	binary.LittleEndian.PutUint32(h[16:20], 16)
	binary.LittleEndian.PutUint16(h[20:22], 1)
	binary.LittleEndian.PutUint16(h[22:24], 1)
	binary.LittleEndian.PutUint32(h[24:28], 16000)
	binary.LittleEndian.PutUint32(h[28:32], 32000)
	binary.LittleEndian.PutUint16(h[32:34], 2)
	binary.LittleEndian.PutUint16(h[34:36], 16)
	copy(h[36:40], "data")
	binary.LittleEndian.PutUint32(h[40:44], 0xffffffff)
	return h
}

func azureTimestamp() string {
	return time.Now().UTC().Format("2006-01-02T15:04:05.0000000Z")
}

func (s *session) Finish(ctx context.Context) error {
	s.finishOnce.Do(func() {
		s.eventMu.Lock()
		defer s.eventMu.Unlock()
		s.commandMu.Lock()
		defer s.commandMu.Unlock()
		s.stateMu.Lock()
		s.accepting = false
		s.finishRequested = true
		hasAudio := s.riffSent
		active := s.active
		turnEnded := s.turnEnded
		s.stateMu.Unlock()
		if (!hasAudio && !active) || turnEnded {
			s.finishedLocked()
			return
		}
		if err := s.send(ctx, websocket.MessageBinary, s.audioFrame(nil)); err != nil {
			s.finishErr = err
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

type translation struct {
	Language    string `json:"Language"`
	Text        string `json:"Text"`
	DisplayText string `json:"DisplayText"`
}

func (translation translation) text() string {
	if translation.DisplayText != "" {
		return translation.DisplayText
	}
	return translation.Text
}

type translationResponse struct {
	SpeechPhrase struct {
		DisplayText       string `json:"DisplayText"`
		Text              string `json:"Text"`
		RecognitionStatus string `json:"RecognitionStatus"`
		PrimaryLanguage   struct {
			Language string `json:"Language"`
		} `json:"PrimaryLanguage"`
	} `json:"SpeechPhrase"`
	Translations []translation `json:"Translations"`
}

type upstreamMessage struct {
	Type              string `json:"type"` // fallback for headerless (fake upstream) messages
	Text              string `json:"text"`
	Language          string `json:"Language"`
	LanguageLower     string `json:"language"`
	RecognitionStatus string `json:"RecognitionStatus"`
	PrimaryLanguage   struct {
		Language string `json:"Language"`
	} `json:"PrimaryLanguage"`
	// Azure production payloads use capitalized keys.
	TextCapital         string            `json:"Text"`
	Translations        map[string]string `json:"translations"`
	TranslationsCapital map[string]string `json:"Translations"`
	Translation         struct {
		Translations []translation `json:"Translations"`
	} `json:"Translation"`
	Signal struct {
		Name string `json:"name"`
	} `json:"signal"`
}

func (m *upstreamMessage) text() string {
	if m.Text != "" {
		return m.Text
	}
	return m.TextCapital
}

func (m *upstreamMessage) translation(locale string) string {
	language, ok := protocolLanguage(locale)
	if !ok {
		return ""
	}
	keys := translationKeys(locale, language)
	for _, key := range keys {
		for _, translations := range []map[string]string{m.Translations, m.TranslationsCapital} {
			if text := strings.TrimSpace(translations[key]); text != "" {
				return text
			}
		}
	}
	for _, translations := range []map[string]string{m.Translations, m.TranslationsCapital} {
		if text := translationFromMap(translations, language); text != "" {
			return text
		}
	}
	for _, key := range keys {
		for _, translation := range m.Translation.Translations {
			if translation.Language == key && strings.TrimSpace(translation.text()) != "" {
				return translation.text()
			}
		}
	}
	for _, translation := range m.Translation.Translations {
		if candidate, ok := protocolLanguage(translation.Language); ok && candidate == language && strings.TrimSpace(translation.text()) != "" {
			return translation.text()
		}
	}
	return ""
}

// translationKeys orders exact Azure aliases before the strict protocol-level
// fallback. It intentionally recognizes only locales supported by this agent.
func translationKeys(locale, language string) []string {
	keys := []string{locale}
	if code, ok := translationLanguage(language); ok {
		keys = append(keys, code)
	}
	keys = append(keys, language)
	if canonical, ok := LocaleForLanguage(language); ok {
		keys = append(keys, canonical)
	}
	seen := make(map[string]struct{}, len(keys))
	unique := keys[:0]
	for _, key := range keys {
		if _, exists := seen[key]; !exists {
			seen[key] = struct{}{}
			unique = append(unique, key)
		}
	}
	return unique
}

func translationFromMap(translations map[string]string, language string) string {
	// Azure can vary casing in locale keys. Restrict fallback to recognized
	// aliases, and sort it so a malformed payload remains deterministic.
	fallback := make([]string, 0, len(translations))
	for key, text := range translations {
		if candidate, ok := protocolLanguage(key); ok && candidate == language && strings.TrimSpace(text) != "" {
			fallback = append(fallback, key)
		}
	}
	sort.Strings(fallback)
	if len(fallback) != 0 {
		return translations[fallback[0]]
	}
	return ""
}

func (m *upstreamMessage) detectedLanguage() string {
	if m.PrimaryLanguage.Language != "" {
		return m.PrimaryLanguage.Language
	}
	if m.Language != "" {
		return m.Language
	}
	return m.LanguageLower
}

func (s *session) handleMessage(data []byte) error {
	path, body := splitMessage(data)
	// Production messages carry the message type in the Path header; the JSON
	// `type` field is only a fallback for headerless fakes.
	kind := path
	var message upstreamMessage
	if kind == "translation.response" {
		var response translationResponse
		if err := json.Unmarshal(body, &response); err != nil {
			return fmt.Errorf("decode Azure translation.response: %w", err)
		}
		if response.SpeechPhrase.RecognitionStatus != "" && !strings.EqualFold(response.SpeechPhrase.RecognitionStatus, "Success") {
			return nil
		}
		// Azure's official JS SDK maps translation.response by moving the root
		// Translations array into SpeechPhrase.Translation, then uses DisplayText
		// as Text (TranslationPhrase.fromTranslationResponse).
		message.TextCapital = response.SpeechPhrase.DisplayText
		if message.TextCapital == "" {
			message.TextCapital = response.SpeechPhrase.Text
		}
		message.RecognitionStatus = response.SpeechPhrase.RecognitionStatus
		message.PrimaryLanguage = response.SpeechPhrase.PrimaryLanguage
		message.Translation.Translations = response.Translations
		kind = "translation.phrase"
	} else {
		if err := json.Unmarshal(body, &message); err != nil {
			return fmt.Errorf("decode Azure message: %w", err)
		}
		if kind == "" {
			kind = message.Type
		}
	}
	if kind == "speech.event" && message.Signal.Name == "telemetry" {
		return s.send(s.ctx, websocket.MessageText, textFrame("telemetry", `{"type":"telemetry","receivedMessages":[]}`))
	}
	s.eventMu.Lock()
	defer s.eventMu.Unlock()
	switch kind {
	case "translation.hypothesis":
		// Hypotheses are display-only. Continuous LID is authoritative only for
		// finals, so neither routing nor synthesis may happen here.
		source := strings.TrimSpace(message.text())
		if source != "" {
			s.sink.Emit(ast.Event{Type: "source_partial", Message: source})
		}
		if len(s.candidates) == 0 {
			if translation := strings.TrimSpace(message.translation(s.to)); translation != "" {
				s.sink.Emit(ast.Event{Type: "translation_partial", Message: translation})
			}
		}
	case "translation.result", "translation.phrase":
		targetLocale := s.to
		language := ""
		if len(s.candidates) != 0 {
			var err error
			language, targetLocale, err = s.resolveDetectedLanguage(&message)
			if err != nil {
				return err
			}
		}
		source := strings.TrimSpace(message.text())
		translation := strings.TrimSpace(message.translation(targetLocale))
		// A continuous automatic final is independently routable only when it
		// has source, candidate LID, and the opposite-target translation.
		if len(s.candidates) != 0 && (source == "" || translation == "") {
			return errors.New("Azure returned an empty automatic final pair")
		}
		if len(s.candidates) == 0 && source != "" && translation == "" {
			return errors.New("Azure returned no target translation for final result")
		}
		segmentID := int64(0)
		targetLanguage := ""
		if len(s.candidates) != 0 {
			s.segmentID++
			segmentID = s.segmentID
			targetLanguage, _ = protocolLanguage(targetLocale)
			s.sink.Emit(ast.Event{Type: "detected_language", Language: language, SegmentID: segmentID, TargetLanguage: targetLanguage})
		}
		s.sink.Emit(ast.Event{Type: "source_final", Message: source, SegmentID: segmentID, TargetLanguage: targetLanguage})
		s.sink.Emit(ast.Event{Type: "translation_final", Message: translation, SegmentID: segmentID, TargetLanguage: targetLanguage})
		if err := s.synthesizeLocked(translation, targetLocale, segmentID, targetLanguage); err != nil {
			return azureTTSError{err}
		}
	case "speech.startDetected":
		s.stateMu.Lock()
		s.active = true
		s.stateMu.Unlock()
	case "turn.end":
		s.stateMu.Lock()
		s.active = false
		s.turnEnded = true
		finish := s.finishRequested
		s.stateMu.Unlock()
		if finish {
			s.finishedLocked()
		}
	}
	return nil
}

func (s *session) resolveDetectedLanguage(message *upstreamMessage) (string, string, error) {
	if status := strings.TrimSpace(message.RecognitionStatus); status != "" && !strings.EqualFold(status, "Success") {
		return "", "", fmt.Errorf("Azure recognition status %q", status)
	}
	language, ok := protocolLanguage(message.detectedLanguage())
	if !ok || (language != s.candidates[0] && language != s.candidates[1]) {
		return "", "", errors.New("Azure did not return a candidate detected language")
	}
	target := s.candidates[0]
	if language == target {
		target = s.candidates[1]
	}
	targetLocale, _ := LocaleForLanguage(target)
	return language, targetLocale, nil
}

type azureTTSError struct{ error }

func (s *session) synthesizeLocked(text, targetLocale string, segmentID int64, targetLanguage string) error {
	if strings.TrimSpace(text) == "" {
		return nil
	}
	voice := s.voice
	if voice == "" || !IsVoiceAllowed(voice, []string{targetLanguage}) {
		voice = defaultVoice[targetLocale]
	}
	ssml := "<speak version='1.0' xml:lang='" + targetLocale + "'><voice name='" + xmlEscape(voice) + "'>" + xmlEscape(text) + "</voice></speak>"
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
	s.sink.Emit(ast.Event{Type: "tts_audio", Binary: pcm, SegmentID: segmentID, TargetLanguage: targetLanguage})
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

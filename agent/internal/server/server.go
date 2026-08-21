// Package server implements the loopback HTTP and Browser WebSocket boundary.
package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"translator-agent/internal/ast"
)

const (
	DefaultAddress = "127.0.0.1:18765"
	PCMFrameBytes  = 2560
	QueueCapacity  = 50
)

var DefaultOrigins = map[string]struct{}{
	"http://127.0.0.1:5173": {},
	"http://localhost:5173": {},
}

var supportedLanguages = map[string]struct{}{
	"zh": {},
	"en": {},
	"fr": {},
	"vi": {},
}

type Options struct {
	ASTClient ast.Client
	Origins   map[string]struct{}
	Logger    *slog.Logger
}

type Server struct {
	astClient ast.Client
	origins   map[string]struct{}
	logger    *slog.Logger
}

func New(opts Options) *Server {
	client := opts.ASTClient
	if client == nil {
		client = ast.UnavailableClient{}
	}
	origins := opts.Origins
	if origins == nil {
		origins = DefaultOrigins
	}
	logger := opts.Logger
	if logger == nil {
		logger = slog.Default()
	}
	return &Server{astClient: client, origins: origins, logger: logger}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/health", s.health)
	mux.HandleFunc("/ws/translate", s.translate)
	return mux
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	origin := r.Header.Get("Origin")
	if _, ok := s.origins[origin]; ok {
		w.Header().Set("Access-Control-Allow-Origin", origin)
		w.Header().Set("Vary", "Origin")
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write([]byte(`{"status":"ok","service":"translator-agent"}`))
}

func (s *Server) translate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	if _, ok := s.origins[r.Header.Get("Origin")]; !ok {
		http.Error(w, "forbidden origin", http.StatusForbidden)
		return
	}

	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
	if err != nil {
		return
	}
	defer func() { _ = conn.CloseNow() }()

	s.runConnection(context.Background(), conn)
}

type startMessage struct {
	Type              string `json:"type"`
	SessionID         string `json:"sessionId"`
	Mode              string `json:"mode"`
	SourceLanguage    string `json:"sourceLanguage"`
	TargetLanguage    string `json:"targetLanguage"`
	TargetAudioFormat string `json:"targetAudioFormat"`
	TargetAudioRate   int    `json:"targetAudioRate"`
}

type finishMessage struct {
	Type string `json:"type"`
}

type browserEvent struct {
	Type    string `json:"type"`
	Code    string `json:"code,omitempty"`
	Message string `json:"message,omitempty"`
	LogID   string `json:"logId,omitempty"`
}

type outgoingMessage struct {
	event  *browserEvent
	binary []byte
}

func (s *Server) runConnection(parent context.Context, conn *websocket.Conn) {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()

	outgoing := make(chan outgoingMessage, 16)
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		for message := range outgoing {
			writeCtx, writeCancel := context.WithTimeout(ctx, 5*time.Second)
			var err error
			if message.event != nil {
				err = wsjson.Write(writeCtx, conn, *message.event)
			} else {
				err = conn.Write(writeCtx, websocket.MessageBinary, message.binary)
			}
			writeCancel()
			if err != nil {
				cancel()
				return
			}
		}
	}()
	defer func() {
		close(outgoing)
		<-writerDone
	}()

	emitMessage := func(message outgoingMessage) bool {
		select {
		case outgoing <- message:
			return true
		case <-ctx.Done():
			return false
		}
	}
	emit := func(event browserEvent) bool {
		return emitMessage(outgoingMessage{event: &event})
	}

	start, err := readStart(ctx, conn)
	if err != nil {
		s.logError("", "", "start_rejected", errorCode(err), "")
		emit(browserEvent{Type: "error", Code: errorCode(err), Message: "invalid start request"})
		return
	}
	direction := start.SourceLanguage + "→" + start.TargetLanguage
	s.logError(start.SessionID, direction, "start_received", "", "")

	var upstreamMu sync.Mutex
	upstreamTerminal := false
	sink := &eventSink{emit: func(event ast.Event) {
		s.logASTEvent(start.SessionID, direction, event)
		upstreamMu.Lock()
		defer upstreamMu.Unlock()
		if upstreamTerminal {
			return
		}
		switch event.Type {
		case "tts_start", "tts_end":
			// Sentence boundaries are upstream implementation details. The Browser
			// consumes one continuous PCM stream and must not validate them.
			return
		case "tts_audio":
			if len(event.Binary) == 0 || len(event.Binary)%2 != 0 {
				upstreamTerminal = true
				emit(browserEvent{Type: "error", Code: "TRANSLATION_PROTOCOL_ERROR", Message: "translation service returned invalid PCM"})
				return
			}
			emitMessage(outgoingMessage{binary: append([]byte(nil), event.Binary...)})
		case "source_partial", "source_final", "translation_partial", "translation_final":
			if strings.TrimSpace(event.Message) == "" {
				return
			}
			emit(browserEvent{Type: event.Type, Message: event.Message, LogID: event.LogID})
		case "finished":
			upstreamTerminal = true
			emit(browserEvent{Type: "finished"})
		case "error":
			upstreamTerminal = true
			emit(browserEvent{Type: "error", Code: event.Code, Message: event.Message, LogID: event.LogID})
		default:
			upstreamTerminal = true
			emit(browserEvent{Type: "error", Code: "TRANSLATION_PROTOCOL_ERROR", Message: "translation service returned an unsupported event"})
		}
	}}
	astSession, err := s.astClient.Start(ctx, start, sink)
	if err != nil {
		code := "VOLCENGINE_CONNECT_FAILED"
		var qwenError ast.QwenError
		if errors.As(err, &qwenError) {
			code = qwenError.Code
		} else if errors.Is(err, ast.ErrCodecUnavailable) {
			code = "AST_CODEC_UNAVAILABLE"
		}
		logID := ast.ErrorLogID(err)
		s.logError(start.SessionID, direction, "ast_start_failed", code, logID)
		emit(browserEvent{Type: "error", Code: code, Message: "translation service is unavailable", LogID: logID})
		return
	}
	defer func() { _ = astSession.Close() }()

	if !emit(browserEvent{Type: "ready"}) {
		return
	}
	sink.activate()
	queue := make(chan []byte, QueueCapacity)
	workerDone := make(chan struct{})
	go func() {
		defer close(workerDone)
		for pcm := range queue {
			if err := astSession.SendAudio(ctx, pcm); err != nil {
				s.logError(start.SessionID, direction, "audio_send_failed", "VOLCENGINE_SESSION_FAILED", "")
				emit(browserEvent{Type: "error", Code: "VOLCENGINE_SESSION_FAILED", Message: "translation session failed"})
				cancel()
				return
			}
		}
	}()
	queueClosed := false
	defer func() {
		if !queueClosed {
			close(queue)
			<-workerDone
		}
	}()

	finished := false
	for ctx.Err() == nil {
		messageType, payload, err := conn.Read(ctx)
		if err != nil {
			return
		}
		switch messageType {
		case websocket.MessageBinary:
			if finished || len(payload) != PCMFrameBytes {
				s.logError(start.SessionID, direction, "audio_rejected", "INVALID_PCM_FRAME", "")
				emit(browserEvent{Type: "error", Code: "INVALID_PCM_FRAME", Message: "audio frame must be exactly 2560 bytes"})
				return
			}
			select {
			case queue <- payload:
			case <-ctx.Done():
				return
			default:
				s.logError(start.SessionID, direction, "queue_overflow", "AUDIO_QUEUE_OVERFLOW", "")
				emit(browserEvent{Type: "error", Code: "AUDIO_QUEUE_OVERFLOW", Message: "audio queue is full"})
				return
			}
		case websocket.MessageText:
			if err := validateFinish(payload); err != nil {
				s.logError(start.SessionID, direction, "message_rejected", errorCode(err), "")
				emit(browserEvent{Type: "error", Code: errorCode(err), Message: "invalid session message"})
				return
			}
			if finished {
				continue
			}
			finished = true
			close(queue)
			<-workerDone
			queueClosed = true
			if err := astSession.Finish(ctx); err != nil {
				s.logError(start.SessionID, direction, "finish_failed", "VOLCENGINE_SESSION_FAILED", "")
				emit(browserEvent{Type: "error", Code: "VOLCENGINE_SESSION_FAILED", Message: "translation session failed"})
				return
			}
			s.logError(start.SessionID, direction, "finish_sent", "", "")
		default:
			emit(browserEvent{Type: "error", Code: "INVALID_MESSAGE", Message: "unsupported WebSocket message"})
			return
		}
	}
}

type eventSink struct {
	mu      sync.Mutex
	active  bool
	pending []ast.Event
	emit    func(ast.Event)
}

func (s *eventSink) Emit(event ast.Event) {
	event.Binary = append([]byte(nil), event.Binary...)
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.active {
		s.pending = append(s.pending, event)
		return
	}
	s.emit(event)
}

func (s *eventSink) activate() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.active = true
	for _, event := range s.pending {
		s.emit(event)
	}
	s.pending = nil
}

func readStart(ctx context.Context, conn *websocket.Conn) (ast.StartRequest, error) {
	readCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	messageType, payload, err := conn.Read(readCtx)
	if err != nil {
		return ast.StartRequest{}, fmt.Errorf("invalid start: %w", err)
	}
	if messageType != websocket.MessageText {
		return ast.StartRequest{}, errors.New("INVALID_START")
	}
	return parseStart(payload)
}

func parseStart(payload []byte) (ast.StartRequest, error) {
	var message startMessage
	decoder := json.NewDecoder(strings.NewReader(string(payload)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&message); err != nil || decoder.Decode(&struct{}{}) != io.EOF {
		return ast.StartRequest{}, errors.New("INVALID_START")
	}
	if message.Type != "start" || !validUUID(message.SessionID) || message.Mode != "s2s" ||
		!isSupportedLanguage(message.SourceLanguage) || !isSupportedLanguage(message.TargetLanguage) ||
		message.SourceLanguage == message.TargetLanguage || message.TargetAudioFormat != "pcm" || message.TargetAudioRate != 16000 {
		return ast.StartRequest{}, errors.New("INVALID_START")
	}
	return ast.StartRequest{SessionID: message.SessionID, Mode: message.Mode, SourceLanguage: message.SourceLanguage, TargetLanguage: message.TargetLanguage, TargetAudioFormat: message.TargetAudioFormat, TargetAudioRate: message.TargetAudioRate}, nil
}

func isSupportedLanguage(language string) bool {
	_, ok := supportedLanguages[language]
	return ok
}

func validateFinish(payload []byte) error {
	var message finishMessage
	decoder := json.NewDecoder(strings.NewReader(string(payload)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&message); err != nil || decoder.Decode(&struct{}{}) != io.EOF || message.Type != "finish" {
		return errors.New("INVALID_MESSAGE")
	}
	return nil
}

func validUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for i, char := range value {
		switch i {
		case 8, 13, 18, 23:
			if char != '-' {
				return false
			}
		default:
			if !((char >= '0' && char <= '9') || (char >= 'a' && char <= 'f') || (char >= 'A' && char <= 'F')) {
				return false
			}
		}
	}
	return true
}

func errorCode(err error) string {
	if err == nil {
		return ""
	}
	return "INVALID_START"
}

func (s *Server) logError(session, direction, event, code, logID string) {
	attrs := []any{"session", session, "direction", direction, "event", event, "error_code", code, "logId", logID}
	s.logger.Info("agent event", attrs...)
}

func (s *Server) logASTEvent(session, direction string, event ast.Event) {
	attrs := []any{
		"session", session,
		"direction", direction,
		"event", "ast_" + event.Type,
		"error_code", event.Code,
		"logId", event.LogID,
	}
	if event.Type == "error" && event.UpstreamStatus != 0 {
		attrs = append(attrs, "upstream_status", event.UpstreamStatus)
	}
	s.logger.Info("agent event", attrs...)
}

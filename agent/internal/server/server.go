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
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"translator-agent/internal/ast"
	"translator-agent/internal/cloudauth"
	"translator-agent/internal/sessionauth"
)

const (
	DefaultAddress = "127.0.0.1:18765"
	PCMFrameBytes  = 2560
	QueueCapacity  = 50

	// SessionSubprotocol is the only protocol negotiated when session
	// authorization is enabled. The credential protocol is never echoed.
	SessionSubprotocol         = "translation.v1"
	SessionTokenProtocolPrefix = "translation.jwt."
	maxSessionTokenBytes       = 4096

	// Governance protocol error codes. TRANSLATION_SESSION_REPLACED is the
	// only terminal state a client may map to a "replaced by another device"
	// explanation; every other safe terminal state shares the generic
	// TRANSLATION_SESSION_ENDED code so the vocabulary cannot leak lifecycle
	// detail. TRANSLATION_AUTH_UNAVAILABLE reports fail-closed termination
	// after the Cloud stayed unreachable past the tolerated window.
	SessionReplacedCode = "TRANSLATION_SESSION_REPLACED"
	SessionEndedCode    = "TRANSLATION_SESSION_ENDED"
	AuthUnavailableCode = "TRANSLATION_AUTH_UNAVAILABLE"

	// defaultReauthInterval/Timeout/Tolerance mirror the documented config
	// defaults; the interval must never exceed one second so a replacement or
	// termination decision reaches live connections well inside five seconds.
	defaultGovernanceInterval  = time.Second
	defaultGovernanceTimeout   = 750 * time.Millisecond
	defaultGovernanceTolerance = 2 * time.Second
	// connectionDrainBudget bounds how long connection teardown waits for the
	// serialized writer to flush a terminal event before the socket is closed
	// regardless, keeping total termination inside the five-second deadline.
	connectionDrainBudget = 500 * time.Millisecond

	sessionReplacedMessage = "translation session was replaced by another device"
	sessionEndedMessage    = "translation session is no longer active"
	authUnavailableMessage = "translation session authorization is unavailable"
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

// CloudAuthorizer is the Cloud-authorization surface the server depends on;
// *cloudauth.Client satisfies it.
type CloudAuthorizer interface {
	Authorize(ctx context.Context, sessionToken string) (cloudauth.Decision, error)
}

// GovernanceTimings bounds periodic Cloud reauthorization. Zero values fall
// back to the documented defaults. The interval must never exceed one second
// and interval+timeout+tolerance must stay inside the five-second governance
// termination deadline including the teardown drain budget; the config loader
// enforces both for deployments.
type GovernanceTimings struct {
	Interval  time.Duration
	Timeout   time.Duration
	Tolerance time.Duration
}

type Options struct {
	ASTClient       ast.Client
	Origins         map[string]struct{}
	Logger          *slog.Logger
	SessionVerifier *sessionauth.Verifier
	CloudAuthorizer CloudAuthorizer
	Governance      GovernanceTimings
}

type Server struct {
	astClient       ast.Client
	origins         map[string]struct{}
	logger          *slog.Logger
	sessionVerifier *sessionauth.Verifier
	cloudAuthorizer CloudAuthorizer
	governance      GovernanceTimings
	registry        *connectionRegistry
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
	return &Server{
		astClient: client, origins: origins, logger: logger,
		sessionVerifier: opts.SessionVerifier, cloudAuthorizer: opts.CloudAuthorizer,
		governance: normalizeGovernance(opts.Governance), registry: newConnectionRegistry(),
	}
}

func normalizeGovernance(timings GovernanceTimings) GovernanceTimings {
	if timings.Interval <= 0 {
		timings.Interval = defaultGovernanceInterval
	}
	if timings.Timeout <= 0 {
		timings.Timeout = defaultGovernanceTimeout
	}
	if timings.Tolerance <= 0 {
		timings.Tolerance = defaultGovernanceTolerance
	}
	return timings
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

	var sessionToken string
	acceptOptions := &websocket.AcceptOptions{InsecureSkipVerify: true}
	if s.sessionVerifier != nil {
		var ok bool
		sessionToken, ok = sessionTokenFromRequest(r)
		if !ok {
			http.Error(w, "translation session authorization required", http.StatusUnauthorized)
			return
		}
		// Remove the credential-bearing protocol before the handshake so it
		// cannot be selected or retained by downstream request processing.
		r.Header.Set("Sec-WebSocket-Protocol", SessionSubprotocol)
		acceptOptions.Subprotocols = []string{SessionSubprotocol}
	}

	conn, err := websocket.Accept(w, r, acceptOptions)
	if err != nil {
		return
	}
	defer func() { _ = conn.CloseNow() }()
	if s.sessionVerifier != nil && conn.Subprotocol() != SessionSubprotocol {
		return
	}

	s.runConnection(context.Background(), conn, sessionToken)
}

type startMessage struct {
	Type              string `json:"type"`
	SessionID         string `json:"sessionId"`
	UserID            string `json:"userId"`
	InstallID         string `json:"installId"`
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

type connectionStart struct {
	ast.StartRequest
	UserID    string
	InstallID string
}

// cloudGovernanceEnabled reports whether this connection carries a verified
// session identity that must also stay authorized by the Cloud lifecycle.
func (s *Server) cloudGovernanceEnabled() bool {
	return s.sessionVerifier != nil && s.cloudAuthorizer != nil
}

// authorizeSession performs one bounded Cloud authorization round trip.
func (s *Server) authorizeSession(ctx context.Context, sessionToken string) (cloudauth.Decision, error) {
	authCtx, cancel := context.WithTimeout(ctx, s.governance.Timeout)
	defer cancel()
	return s.cloudAuthorizer.Authorize(authCtx, sessionToken)
}

// governSession periodically re-authorizes the session token against the
// Cloud lifecycle until the connection ends. A definitive denial terminates
// the connection with the mapped safe code; an unreachable Cloud is
// tolerated for at most the configured window and then fails closed. With
// the configured interval, timeout, and tolerance the connection terminates
// well inside the five-second governance deadline including teardown.
func (s *Server) governSession(ctx context.Context, sessionToken string, terminate func(code, message string)) {
	ticker := time.NewTicker(s.governance.Interval)
	defer ticker.Stop()
	unreachableSince := time.Time{}
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			decision, err := s.authorizeSession(ctx, sessionToken)
			if err != nil {
				if unreachableSince.IsZero() {
					unreachableSince = time.Now()
					continue
				}
				if time.Since(unreachableSince) >= s.governance.Tolerance {
					terminate(AuthUnavailableCode, authUnavailableMessage)
					return
				}
				continue
			}
			if !decision.Active {
				code, message := governanceDenial(nil, decision)
				terminate(code, message)
				return
			}
			unreachableSince = time.Time{}
		}
	}
}

// governanceDenial maps a Cloud authorization failure to the safe client
// vocabulary. Replacement is the only distinguishable state; every other
// terminal reason shares the generic ended code so lifecycle detail cannot
// leak, and an unreachable Cloud fails closed with the unavailable code.
func governanceDenial(err error, decision cloudauth.Decision) (code, message string) {
	switch {
	case err != nil:
		return AuthUnavailableCode, authUnavailableMessage
	case decision.Reason == cloudauth.ReasonReplacedByDevice:
		return SessionReplacedCode, sessionReplacedMessage
	default:
		return SessionEndedCode, sessionEndedMessage
	}
}

// connectionRegistryKey derives the stable session identity from verified
// claims. A re-issued token keeps this identity, so a duplicate connection
// presenting the same identity supersedes the older one locally.
func connectionRegistryKey(claims sessionauth.Claims) string {
	return claims.UserID + "\x1f" + claims.SessionID + "\x1f" + claims.InstallID
}

func (s *Server) runConnection(parent context.Context, conn *websocket.Conn, sessionToken string) {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()

	outgoing := make(chan outgoingMessage, 16)
	writerDone := make(chan struct{})
	// governanceTerminal suppresses every event after a governance terminal
	// event so a cancelled provider or audio worker cannot overwrite the safe
	// termination code the client must act on.
	var governanceTerminal atomic.Bool
	// writeMu serializes every socket write: the background writer and a
	// synchronous governance terminal write must never overlap. Each write is
	// bounded by connectionDrainBudget so a wedged or dead socket cannot stall
	// teardown past the five-second governance deadline.
	var writeMu sync.Mutex
	writeMessage := func(message outgoingMessage) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		writeCtx, writeCancel := context.WithTimeout(context.Background(), connectionDrainBudget)
		defer writeCancel()
		if message.event != nil {
			return wsjson.Write(writeCtx, conn, *message.event)
		}
		return conn.Write(writeCtx, websocket.MessageBinary, message.binary)
	}
	go func() {
		defer close(writerDone)
		for message := range outgoing {
			// Once the terminal governance event has been written
			// synchronously, queued traffic is stale by definition; dropping
			// it keeps the safe termination code the last word on the socket.
			if governanceTerminal.Load() {
				return
			}
			if err := writeMessage(message); err != nil {
				cancel()
				return
			}
		}
	}()
	// emitMu guards outgoing against close: governance, upstream callbacks,
	// and teardown can race, and sending on a closed channel would panic.
	var emitMu sync.Mutex
	outgoingClosed := false
	defer func() {
		emitMu.Lock()
		outgoingClosed = true
		close(outgoing)
		emitMu.Unlock()
		select {
		case <-writerDone:
		case <-time.After(connectionDrainBudget):
			// Unstick a wedged writer; the handler's deferred CloseNow then
			// aborts its in-flight write regardless.
		}
	}()
	sendMessage := func(message outgoingMessage) bool {
		emitMu.Lock()
		defer emitMu.Unlock()
		if outgoingClosed {
			return false
		}
		select {
		case outgoing <- message:
			return true
		case <-ctx.Done():
			return false
		}
	}
	emitMessage := func(message outgoingMessage) bool {
		if governanceTerminal.Load() {
			return false
		}
		return sendMessage(message)
	}
	emit := func(event browserEvent) bool {
		return emitMessage(outgoingMessage{event: &event})
	}

	start, err := readStart(ctx, conn, s.sessionVerifier != nil)
	if err != nil {
		s.logError("", "", "start_rejected", errorCode(err), "")
		emit(browserEvent{Type: "error", Code: errorCode(err), Message: "invalid start request"})
		return
	}
	direction := start.SourceLanguage + "→" + start.TargetLanguage
	var claims sessionauth.Claims
	if s.sessionVerifier != nil {
		var err error
		claims, err = s.sessionVerifier.Verify(sessionToken, sessionauth.Expected{
			Subject: start.UserID, UserID: start.UserID, SessionID: start.SessionID, InstallID: start.InstallID,
		})
		if err != nil {
			s.logError(start.SessionID, direction, "session_auth_rejected", "TRANSLATION_AUTH_INVALID", "")
			emit(browserEvent{Type: "error", Code: "TRANSLATION_AUTH_INVALID", Message: "translation session authorization failed"})
			return
		}
	}
	// Cloud governance: consult the persisted session lifecycle before the
	// provider starts, so an already ended, replaced, revoked, or otherwise
	// terminated session never reaches the provider. An unreachable Cloud is
	// an immediate fail-closed denial: the session has not been proven active.
	if s.cloudGovernanceEnabled() {
		decision, err := s.authorizeSession(ctx, sessionToken)
		if err != nil || !decision.Active {
			code, message := governanceDenial(err, decision)
			s.logError(claims.SessionID, direction, "session_governance_denied", code, "")
			emit(browserEvent{Type: "error", Code: code, Message: message})
			return
		}
		var handle *registeredConnection
		handle = s.registry.register(connectionRegistryKey(claims), cancel)
		defer s.registry.unregister(handle)
		terminate := func(code, message string) {
			if governanceTerminal.CompareAndSwap(false, true) {
				s.logError(claims.SessionID, direction, "session_governance_terminated", code, "")
				// Write the terminal event synchronously before cancelling:
				// cancelling unblocks the blocked reader and coder/websocket then
				// closes the socket immediately, which would race the background
				// writer flush and drop the code the client must act on.
				_ = writeMessage(outgoingMessage{event: &browserEvent{Type: "error", Code: code, Message: message}})
				cancel()
			}
		}
		go s.governSession(ctx, sessionToken, terminate)
	}
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
	astSession, err := s.astClient.Start(ctx, start.StartRequest, sink)
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
				// A governance cancellation is not an upstream failure; stay
				// silent so the safe termination code survives.
				if ctx.Err() == nil {
					s.logError(start.SessionID, direction, "audio_send_failed", "VOLCENGINE_SESSION_FAILED", "")
					emit(browserEvent{Type: "error", Code: "VOLCENGINE_SESSION_FAILED", Message: "translation session failed"})
					cancel()
				}
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

func readStart(ctx context.Context, conn *websocket.Conn, authRequired bool) (connectionStart, error) {
	readCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	messageType, payload, err := conn.Read(readCtx)
	if err != nil {
		return connectionStart{}, fmt.Errorf("invalid start: %w", err)
	}
	if messageType != websocket.MessageText {
		return connectionStart{}, errors.New("INVALID_START")
	}
	return parseStart(payload, authRequired)
}

func parseStart(payload []byte, authRequired bool) (connectionStart, error) {
	var message startMessage
	decoder := json.NewDecoder(strings.NewReader(string(payload)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&message); err != nil || decoder.Decode(&struct{}{}) != io.EOF {
		return connectionStart{}, errors.New("INVALID_START")
	}
	if message.Type != "start" || !validUUID(message.SessionID) || message.Mode != "s2s" ||
		!isSupportedLanguage(message.SourceLanguage) || !isSupportedLanguage(message.TargetLanguage) ||
		message.SourceLanguage == message.TargetLanguage || message.TargetAudioFormat != "pcm" || message.TargetAudioRate != 16000 ||
		(authRequired && (strings.TrimSpace(message.UserID) == "" || strings.TrimSpace(message.InstallID) == "")) ||
		(!authRequired && (message.UserID != "" || message.InstallID != "")) {
		return connectionStart{}, errors.New("INVALID_START")
	}
	return connectionStart{
		StartRequest: ast.StartRequest{SessionID: message.SessionID, Mode: message.Mode, SourceLanguage: message.SourceLanguage, TargetLanguage: message.TargetLanguage, TargetAudioFormat: message.TargetAudioFormat, TargetAudioRate: message.TargetAudioRate},
		UserID:       message.UserID, InstallID: message.InstallID,
	}, nil
}

func sessionTokenFromRequest(r *http.Request) (string, bool) {
	var token string
	hasApplicationProtocol := false
	for _, header := range r.Header.Values("Sec-WebSocket-Protocol") {
		for _, raw := range strings.Split(header, ",") {
			protocol := strings.TrimSpace(raw)
			switch {
			case protocol == SessionSubprotocol:
				if hasApplicationProtocol {
					return "", false
				}
				hasApplicationProtocol = true
			case strings.HasPrefix(protocol, SessionTokenProtocolPrefix):
				if token != "" {
					return "", false
				}
				token = strings.TrimPrefix(protocol, SessionTokenProtocolPrefix)
				if token == "" || len(token) > maxSessionTokenBytes || strings.Count(token, ".") != 2 {
					return "", false
				}
			default:
				return "", false
			}
		}
	}
	return token, hasApplicationProtocol && token != ""
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

//go:build officialast

package ast

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
	"google.golang.org/protobuf/proto"

	"translator-agent/internal/config"
	eventproto "translator-agent/internal/officialastproto/common/event"
	rpcmetaproto "translator-agent/internal/officialastproto/common/rpcmeta"
	astproto "translator-agent/internal/officialastproto/products/understanding/ast"
	baseproto "translator-agent/internal/officialastproto/products/understanding/base"
)

const officialEndpoint = "wss://openspeech.bytedance.com/api/v4/ast/v2/translate"

type officialClient struct {
	config   config.Config
	endpoint string
}

func newOfficialClient(cfg config.Config) *officialClient {
	return &officialClient{config: cfg, endpoint: officialEndpoint}
}

func (c *officialClient) Start(ctx context.Context, request StartRequest, sink EventSink) (Session, error) {
	connectionID, err := newUUID()
	if err != nil {
		return nil, fmt.Errorf("create AST connection ID: %w", err)
	}

	header := c.httpHeader(connectionID)
	conn, response, err := websocket.Dial(ctx, c.endpoint, &websocket.DialOptions{HTTPHeader: header})
	logID := ""
	if response != nil {
		logID = response.Header.Get("X-Tt-Logid")
	}
	if err != nil {
		return nil, upstreamTransportError{logID: logID, err: fmt.Errorf("dial AST upstream: %w", err)}
	}

	session := newOfficialSession(ctx, conn, request.SessionID, logID, sink)
	if err := session.send(ctx, startSessionRequest(request)); err != nil {
		_ = session.Close()
		return nil, upstreamTransportError{logID: logID, err: fmt.Errorf("send AST StartSession: %w", err)}
	}

	startCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	responseMessage, err := readResponse(startCtx, conn)
	if err != nil {
		_ = session.Close()
		return nil, upstreamTransportError{logID: logID, err: fmt.Errorf("wait for AST SessionStarted: %w", err)}
	}
	if responseMessage.GetEvent() != eventproto.Type_SessionStarted {
		_ = session.Close()
		if responseMessage.GetEvent() == eventproto.Type_SessionFailed {
			return nil, upstreamTransportError{logID: logID, err: upstreamFailure(responseMessage)}
		}
		return nil, upstreamTransportError{logID: logID, err: fmt.Errorf("wait for AST SessionStarted: unexpected event %d", responseMessage.GetEvent())}
	}

	session.startReader()
	return session, nil
}

func (c *officialClient) httpHeader(connectionID string) http.Header {
	header := make(http.Header)
	header.Set("X-Api-Resource-Id", c.config.ResourceID)
	header.Set("X-Api-Connect-Id", connectionID)
	if c.config.APIKey != "" {
		header.Set("X-Api-Key", c.config.APIKey)
	} else {
		header.Set("X-Api-App-Id", c.config.AppID)
		header.Set("X-Api-Access-Key", c.config.AccessToken)
	}
	return header
}

func startSessionRequest(request StartRequest) *astproto.TranslateRequest {
	return &astproto.TranslateRequest{
		RequestMeta: &rpcmetaproto.RequestMeta{SessionID: request.SessionID},
		Event:       eventproto.Type_StartSession,
		User: &baseproto.User{
			Uid: "translator-agent",
			Did: "translator-agent",
		},
		SourceAudio: &baseproto.Audio{
			Format:  "wav",
			Codec:   "raw",
			Rate:    16000,
			Bits:    16,
			Channel: 1,
		},
		TargetAudio: &baseproto.Audio{
			Format:  request.TargetAudioFormat,
			Rate:    int32(request.TargetAudioRate),
			Bits:    16,
			Channel: 1,
		},
		Request: &astproto.ReqParams{
			Mode:           request.Mode,
			SourceLanguage: request.SourceLanguage,
			TargetLanguage: request.TargetLanguage,
		},
	}
}

type writeRequest struct {
	message *astproto.TranslateRequest
	result  chan error
}

type officialSession struct {
	ctx       context.Context
	cancel    context.CancelFunc
	conn      *websocket.Conn
	sessionID string
	logID     string
	sink      EventSink
	writes    chan writeRequest

	finishOnce sync.Once
	finishErr  error
	closeOnce  sync.Once
	wg         sync.WaitGroup

	errorMu sync.Mutex
	err     error
}

func newOfficialSession(parent context.Context, conn *websocket.Conn, sessionID, logID string, sink EventSink) *officialSession {
	ctx, cancel := context.WithCancel(parent)
	session := &officialSession{
		ctx:       ctx,
		cancel:    cancel,
		conn:      conn,
		sessionID: sessionID,
		logID:     logID,
		sink:      sink,
		writes:    make(chan writeRequest),
	}
	session.wg.Add(1)
	go session.writeLoop()
	return session
}

func (s *officialSession) startReader() {
	s.wg.Add(1)
	go s.readLoop()
}

func (s *officialSession) SendAudio(ctx context.Context, pcm []byte) error {
	data := append([]byte(nil), pcm...)
	return s.send(ctx, &astproto.TranslateRequest{
		RequestMeta: &rpcmetaproto.RequestMeta{SessionID: s.sessionID},
		Event:       eventproto.Type_TaskRequest,
		SourceAudio: &baseproto.Audio{BinaryData: data},
	})
}

func (s *officialSession) Finish(ctx context.Context) error {
	s.finishOnce.Do(func() {
		s.finishErr = s.send(ctx, &astproto.TranslateRequest{
			RequestMeta: &rpcmetaproto.RequestMeta{SessionID: s.sessionID},
			Event:       eventproto.Type_FinishSession,
		})
	})
	return s.finishErr
}

func (s *officialSession) Close() error {
	s.closeOnce.Do(func() {
		s.cancel()
		_ = s.conn.CloseNow()
		s.wg.Wait()
	})
	return nil
}

func (s *officialSession) send(ctx context.Context, message *astproto.TranslateRequest) error {
	if err := s.sessionError(); err != nil {
		return err
	}
	result := make(chan error, 1)
	request := writeRequest{message: message, result: result}
	select {
	case s.writes <- request:
	case <-ctx.Done():
		return ctx.Err()
	case <-s.ctx.Done():
		return s.closedError()
	}
	select {
	case err := <-result:
		return err
	case <-ctx.Done():
		return ctx.Err()
	case <-s.ctx.Done():
		select {
		case err := <-result:
			return err
		default:
			return s.closedError()
		}
	}
}

func (s *officialSession) writeLoop() {
	defer s.wg.Done()
	for {
		select {
		case request := <-s.writes:
			payload, err := proto.Marshal(request.message)
			if err == nil {
				err = s.conn.Write(s.ctx, websocket.MessageBinary, payload)
			}
			if err != nil {
				s.setError(fmt.Errorf("write AST request: %w", err))
			}
			request.result <- err
			if err != nil {
				return
			}
		case <-s.ctx.Done():
			return
		}
	}
}

func (s *officialSession) readLoop() {
	defer s.wg.Done()
	for {
		response, err := readResponse(s.ctx, s.conn)
		if err != nil {
			if s.ctx.Err() == nil {
				s.fail(fmt.Errorf("read AST response: %w", err))
			}
			return
		}
		if terminal := s.mapResponse(response); terminal {
			return
		}
	}
}

func readResponse(ctx context.Context, conn *websocket.Conn) (*astproto.TranslateResponse, error) {
	messageType, payload, err := conn.Read(ctx)
	if err != nil {
		return nil, err
	}
	if messageType != websocket.MessageBinary && messageType != websocket.MessageText {
		return nil, fmt.Errorf("unexpected WebSocket message type %d", messageType)
	}
	response := new(astproto.TranslateResponse)
	if err := proto.Unmarshal(payload, response); err != nil {
		return nil, fmt.Errorf("decode AST protobuf: %w", err)
	}
	return response, nil
}

func (s *officialSession) mapResponse(response *astproto.TranslateResponse) bool {
	emitText := func(eventType string) {
		s.sink.Emit(Event{Type: eventType, Message: response.GetText(), LogID: s.logID})
	}
	switch response.GetEvent() {
	case eventproto.Type_SourceSubtitleResponse:
		emitText("source_partial")
	case eventproto.Type_SourceSubtitleEnd:
		emitText("source_final")
	case eventproto.Type_TranslationSubtitleResponse:
		emitText("translation_partial")
	case eventproto.Type_TranslationSubtitleEnd:
		emitText("translation_final")
	case eventproto.Type_TTSSentenceStart:
		// Sentence boundaries are upstream implementation details. The browser
		// consumes a continuous PCM stream until SessionFinished.
	case eventproto.Type_TTSResponse:
		if len(response.GetData()) > 0 {
			s.sink.Emit(Event{Type: "tts_audio", Binary: append([]byte(nil), response.GetData()...), LogID: s.logID})
		}
	case eventproto.Type_TTSSentenceEnd:
		if len(response.GetData()) > 0 {
			s.sink.Emit(Event{Type: "tts_audio", Binary: append([]byte(nil), response.GetData()...)})
		}
	case eventproto.Type_SessionFinished:
		s.sink.Emit(Event{Type: "finished"})
		s.cancel()
		return true
	case eventproto.Type_SessionFailed, eventproto.Type_SessionCanceled:
		s.fail(upstreamFailure(response))
		return true
	}
	return false
}

type upstreamTransportError struct {
	logID string
	err   error
}

func (e upstreamTransportError) Error() string { return e.err.Error() }
func (e upstreamTransportError) Unwrap() error { return e.err }
func (e upstreamTransportError) LogID() string { return e.logID }

func upstreamFailure(response *astproto.TranslateResponse) error {
	meta := response.GetResponseMeta()
	if meta == nil {
		return errors.New("AST session failed")
	}
	return fmt.Errorf("AST session failed with status %d: %s", meta.GetStatusCode(), meta.GetMessage())
}

func (s *officialSession) fail(err error) {
	s.setError(err)
	s.sink.Emit(Event{Type: "error", Code: "VOLCENGINE_SESSION_FAILED", Message: "translation session failed", LogID: s.logID})
}

func (s *officialSession) setError(err error) {
	s.errorMu.Lock()
	if s.err == nil {
		s.err = err
	}
	s.errorMu.Unlock()
	s.cancel()
	_ = s.conn.CloseNow()
}

func (s *officialSession) sessionError() error {
	s.errorMu.Lock()
	defer s.errorMu.Unlock()
	return s.err
}

func (s *officialSession) closedError() error {
	if err := s.sessionError(); err != nil {
		return err
	}
	return context.Canceled
}

func newUUID() (string, error) {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return "", err
	}
	id[6] = (id[6] & 0x0f) | 0x40
	id[8] = (id[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", id[0:4], id[4:6], id[6:8], id[8:10], id[10:16]), nil
}

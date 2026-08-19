// Package ast defines the boundary to the Volcengine AST protocol.
package ast

import (
	"context"
	"errors"
)

var ErrCodecUnavailable = errors.New("AST_CODEC_UNAVAILABLE")

// StartRequest is the validated Browser-to-Agent session configuration.
type StartRequest struct {
	SessionID         string
	Mode              string
	SourceLanguage    string
	TargetLanguage    string
	TargetAudioFormat string
	TargetAudioRate   int
}

// Client starts an AST session. A production codec is deliberately absent until
// official Volcengine AST protobuf definitions are integrated.
type Client interface {
	Start(context.Context, StartRequest, EventSink) (Session, error)
}

// Session accepts ordered audio and an idempotent finish request.
type Session interface {
	SendAudio(context.Context, []byte) error
	Finish(context.Context) error
	Close() error
}

// EventSink accepts events to be serialized to a browser by the server.
type EventSink interface {
	Emit(Event)
}

// Event is a browser-safe AST outcome. Details must never include credentials.
type Event struct {
	Type    string `json:"type"`
	Code    string `json:"code,omitempty"`
	Message string `json:"message,omitempty"`
	LogID   string `json:"logId,omitempty"`
}

// UnavailableClient is the explicit safe default. It never claims the AST
// session is ready and never creates or guesses protobuf messages.
type UnavailableClient struct{}

func (UnavailableClient) Start(context.Context, StartRequest, EventSink) (Session, error) {
	return nil, ErrCodecUnavailable
}

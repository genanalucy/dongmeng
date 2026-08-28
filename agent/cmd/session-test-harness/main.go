//go:build integrationtest

// session-test-harness is an integration-test-only Agent process. It is
// deliberately excluded from normal builds and never loads Provider settings.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"sync"
	"time"

	"translator-agent/internal/ast"
	"translator-agent/internal/server"
	"translator-agent/internal/sessionauth"
)

const (
	keyEnvironment      = "E2E_AGENT_SESSION_KEY"
	issuerEnvironment   = "E2E_AGENT_SESSION_ISSUER"
	audienceEnvironment = "E2E_AGENT_SESSION_AUDIENCE"
	originEnvironment   = "E2E_AGENT_ORIGIN"
)

type fakeProvider struct {
	mu     sync.Mutex
	starts int
}

func (p *fakeProvider) Start(context.Context, ast.StartRequest, ast.EventSink) (ast.Session, error) {
	p.mu.Lock()
	p.starts++
	p.mu.Unlock()
	return fakeSession{}, nil
}

func (p *fakeProvider) startCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.starts
}

type fakeSession struct{}

func (fakeSession) SendAudio(context.Context, []byte) error { return nil }
func (fakeSession) Finish(context.Context) error            { return nil }
func (fakeSession) Close() error                            { return nil }

func main() {
	key := []byte(os.Getenv(keyEnvironment))
	issuer := os.Getenv(issuerEnvironment)
	audience := os.Getenv(audienceEnvironment)
	origin := os.Getenv(originEnvironment)
	if len(key) < 32 || issuer == "" || audience == "" || origin == "" {
		fmt.Fprintln(os.Stderr, "invalid test harness configuration")
		os.Exit(2)
	}
	verifier, err := sessionauth.NewVerifier(sessionauth.Config{
		HMACKey: key, Issuer: issuer, Audience: audience, ClockSkew: 0, MaxLifetime: 5 * time.Minute,
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, "invalid test harness verifier")
		os.Exit(2)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fmt.Fprintln(os.Stderr, "test harness loopback bind failed")
		os.Exit(2)
	}
	provider := &fakeProvider{}
	mux := http.NewServeMux()
	mux.Handle("/", server.New(server.Options{
		ASTClient:       provider,
		Origins:         map[string]struct{}{origin: {}},
		SessionVerifier: verifier,
	}).Handler())
	mux.HandleFunc("GET /test/provider-starts", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(struct {
			Starts int `json:"starts"`
		}{Starts: provider.startCount()})
	})
	fmt.Println(listener.Addr().String())
	if err := http.Serve(listener, mux); err != nil && err != http.ErrServerClosed {
		fmt.Fprintln(os.Stderr, "test harness server failed")
		os.Exit(1)
	}
}

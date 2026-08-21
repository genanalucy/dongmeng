package main

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"translator-agent/internal/ast"
	"translator-agent/internal/config"
	"translator-agent/internal/server"
)

func allowedOrigins(extra string) map[string]struct{} {
	origins := make(map[string]struct{}, len(server.DefaultOrigins))
	for origin := range server.DefaultOrigins {
		origins[origin] = struct{}{}
	}
	for _, origin := range strings.Split(extra, ",") {
		if normalized := strings.TrimSpace(origin); normalized != "" {
			origins[normalized] = struct{}{}
		}
	}
	return origins
}

func main() {
	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		logger.Error("agent configuration invalid", "error_code", "INVALID_CONFIGURATION")
		os.Exit(1)
	}

	httpServer := &http.Server{
		Addr:              server.DefaultAddress,
		Handler:           server.New(server.Options{ASTClient: ast.NewConfiguredClient(cfg), Origins: allowedOrigins(os.Getenv("TRANSLATOR_AGENT_EXTRA_ORIGINS")), Logger: logger}).Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	listener, err := net.Listen("tcp", server.DefaultAddress)
	if err != nil {
		logger.Error("agent failed to bind", "error_code", "BIND_FAILED")
		os.Exit(1)
	}
	logger.Info("agent started", "event", "listening")

	errCh := make(chan error, 1)
	go func() { errCh <- httpServer.Serve(listener) }()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := httpServer.Shutdown(shutdownCtx); err != nil {
			logger.Error("agent shutdown failed", "error_code", "SHUTDOWN_FAILED")
			os.Exit(1)
		}
	case err := <-errCh:
		if !errors.Is(err, http.ErrServerClosed) {
			logger.Error("agent server failed", "error_code", "SERVER_FAILED")
			os.Exit(1)
		}
	}
}

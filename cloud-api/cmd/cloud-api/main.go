package main

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
	httpapi "github.com/dngmeng/cloud-api/internal/http"
	"github.com/dngmeng/cloud-api/internal/store"
)

var version = "dev"

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(logger); err != nil {
		logger.Error("cloud-api stopped", "error", err)
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	cfg, err := config.Load()
	if err != nil {
		return errors.New("invalid cloud-api configuration")
	}

	rootContext, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	database, err := store.Open(rootContext, cfg.DatabaseURL)
	if err != nil {
		return errors.New("initialize database pool")
	}
	defer database.Close()

	server := &http.Server{
		Addr: cfg.Address,
		Handler: httpapi.NewRouter(httpapi.RouterOptions{
			Config: cfg, Database: database, Logger: logger, Version: version,
			Store:  database,
			Tokens: auth.TokenIssuer{Issuer: cfg.TokenIssuer, Audience: cfg.AccessAudience, SessionAudience: cfg.SessionAudience, AccessSecret: []byte(cfg.AccessSecret), SessionSecret: []byte(cfg.SessionSecret)},
		}),
		ReadTimeout:       cfg.ReadTimeout,
		ReadHeaderTimeout: cfg.ReadHeaderTimeout,
		WriteTimeout:      cfg.WriteTimeout,
		IdleTimeout:       cfg.IdleTimeout,
		BaseContext: func(_ net.Listener) context.Context {
			return rootContext
		},
	}

	serveErrors := make(chan error, 1)
	go func() {
		logger.Info("cloud-api listening", "address", cfg.Address, "environment", cfg.Environment, "version", version)
		serveErrors <- server.ListenAndServe()
	}()

	select {
	case err := <-serveErrors:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-rootContext.Done():
		shutdownContext, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
		defer cancel()
		if err := server.Shutdown(shutdownContext); err != nil {
			return errors.New("graceful shutdown timed out")
		}
		return nil
	}
}

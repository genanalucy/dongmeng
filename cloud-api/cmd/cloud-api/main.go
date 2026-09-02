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
	mailapi "github.com/dngmeng/cloud-api/internal/mail"
	"github.com/dngmeng/cloud-api/internal/store"
)

var version = "dev"

type registrationVerificationDependencies struct {
	sender  *mailapi.SMTPRegistrationCodeSender
	service *auth.EmailRegistrationService
}

func newRegistrationVerificationDependencies(cfg config.Config) (registrationVerificationDependencies, error) {
	if !cfg.EmailVerificationEnabled {
		return registrationVerificationDependencies{}, nil
	}
	sender, err := newRegistrationCodeSender(cfg)
	if err != nil {
		return registrationVerificationDependencies{}, err
	}
	service, err := auth.NewEmailRegistrationService(auth.EmailRegistrationService{
		HashPasswordValue:  auth.HashPassword,
		CodePepper:         []byte(cfg.EmailVerificationRateLimitSecret),
		RateLimitKeySecret: []byte(cfg.EmailVerificationRateLimitSecret),
		Sender:             sender,
		WriteVerification: func(context.Context, auth.RegistrationVerificationDraft) error {
			return errors.New("registration verification persistence is not wired")
		},
	})
	if err != nil {
		return registrationVerificationDependencies{}, err
	}
	return registrationVerificationDependencies{sender: sender, service: &service}, nil
}

func newRegistrationCodeSender(cfg config.Config) (*mailapi.SMTPRegistrationCodeSender, error) {
	return mailapi.NewSMTPRegistrationCodeSender(mailapi.SMTPConfig{
		Host:           cfg.SMTPHost,
		Port:           cfg.SMTPPort,
		From:           cfg.SMTPFrom,
		ConnectTimeout: cfg.SMTPConnectTimeout,
		SendTimeout:    cfg.SMTPSendTimeout,
	}, nil)
}

func newRouterOptions(cfg config.Config, database *store.Postgres, logger *slog.Logger, dependencies registrationVerificationDependencies) httpapi.RouterOptions {
	return httpapi.RouterOptions{
		Config:                   cfg,
		Database:                 database,
		Logger:                   logger,
		Version:                  version,
		Store:                    database,
		Tokens:                   auth.TokenIssuer{Issuer: cfg.TokenIssuer, Audience: cfg.AccessAudience, SessionAudience: cfg.SessionAudience, AccessSecret: []byte(cfg.AccessSecret), SessionSecret: []byte(cfg.SessionSecret)},
		RegistrationVerification: dependencies.service,
	}
}

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
	dependencies, err := newRegistrationVerificationDependencies(cfg)
	if err != nil {
		return errors.New("initialize registration email sender")
	}

	rootContext, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	database, err := store.Open(rootContext, cfg.DatabaseURL)
	if err != nil {
		return errors.New("initialize database pool")
	}
	defer database.Close()

	server := &http.Server{
		Addr:              cfg.Address,
		Handler:           httpapi.NewRouter(newRouterOptions(cfg, database, logger, dependencies)),
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

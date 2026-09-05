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
	"github.com/dngmeng/cloud-api/internal/historycrypto"
	httpapi "github.com/dngmeng/cloud-api/internal/http"
	"github.com/dngmeng/cloud-api/internal/store"
)

var version = "dev"

// newCaptchaService builds the captcha registration primitive from validated
// configuration. It is unconditional: captcha registration is the only
// registration path, so a missing CAPTCHA_SECRET stops startup in config
// validation rather than weakening the flow here.
func newCaptchaService(cfg config.Config) (*auth.CaptchaService, error) {
	service, err := auth.NewCaptchaService(auth.CaptchaService{
		AnswerPepper:       []byte(cfg.CaptchaSecret),
		RateLimitKeySecret: []byte(cfg.CaptchaSecret),
	})
	if err != nil {
		return nil, err
	}
	return &service, nil
}

func newRouterOptions(cfg config.Config, database *store.Postgres, logger *slog.Logger, captcha *auth.CaptchaService) (httpapi.RouterOptions, error) {
	var historyCipher *historycrypto.Cipher
	if cfg.HistoryEnabled {
		var err error
		historyCipher, err = historycrypto.NewCipher(cfg.HistoryRootKey, cfg.HistoryKeyVersion)
		if err != nil {
			return httpapi.RouterOptions{}, errors.New("initialize history encryption")
		}
	}
	return httpapi.RouterOptions{
		Config:        cfg,
		Database:      database,
		Logger:        logger,
		Version:       version,
		Store:         database,
		Tokens:        auth.TokenIssuer{Issuer: cfg.TokenIssuer, Audience: cfg.AccessAudience, SessionAudience: cfg.SessionAudience, AccessSecret: []byte(cfg.AccessSecret), SessionSecret: []byte(cfg.SessionSecret)},
		Captcha:       captcha,
		HistoryCipher: historyCipher,
	}, nil
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
	captcha, err := newCaptchaService(cfg)
	if err != nil {
		return errors.New("initialize captcha registration service")
	}

	rootContext, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	database, err := store.Open(rootContext, cfg.DatabaseURL)
	if err != nil {
		return errors.New("initialize database pool")
	}
	defer database.Close()

	routerOptions, err := newRouterOptions(cfg, database, logger, captcha)
	if err != nil {
		return err
	}
	server := &http.Server{
		Addr:              cfg.Address,
		Handler:           httpapi.NewRouter(routerOptions),
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

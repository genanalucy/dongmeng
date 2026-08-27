// Package httpapi defines the cloud-api HTTP boundary.
package httpapi

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
	"github.com/go-chi/chi/v5"
)

type Readiness interface{ Ping(context.Context) error }
type RouterOptions struct {
	Config   config.Config
	Database Readiness
	Store    businessStore
	Tokens   auth.TokenIssuer
	Logger   *slog.Logger
	Version  string
	Now      func() time.Time
}

func noStore(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

func NewRouter(options RouterOptions) http.Handler {
	logger := options.Logger
	if logger == nil {
		logger = slog.Default()
	}
	version := options.Version
	if version == "" {
		version = "dev"
	}
	now := options.Now
	if now == nil {
		now = time.Now
	}
	router := chi.NewRouter()
	router.Use(RequestID)
	router.Use(AccessLog(logger))
	router.Use(Recoverer(logger))
	router.Use(CORS(options.Config.AllowedOrigins))
	router.Use(NewLimiter(options.Config.RateLimitRPS, options.Config.RateLimitBurst).Middleware)
	router.Use(noStore)
	router.NotFound(func(w http.ResponseWriter, r *http.Request) { writeError(w, r, 404, "not_found") })
	router.MethodNotAllowed(func(w http.ResponseWriter, r *http.Request) { writeError(w, r, 405, "method_not_allowed") })
	health := func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]string{"status": "ok", "service": "cloud-api"})
	}
	ready := func(w http.ResponseWriter, r *http.Request) {
		if options.Database == nil {
			writeError(w, r, 503, "not_ready")
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), options.Config.DatabaseTimeout)
		defer cancel()
		if options.Database.Ping(ctx) != nil {
			writeError(w, r, 503, "not_ready")
			return
		}
		writeJSON(w, 200, map[string]string{"status": "ready", "service": "cloud-api"})
	}
	publicConfig := func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		writeJSON(w, 200, map[string]string{"environment": options.Config.Environment, "service": "cloud-api", "version": version})
	}
	router.Get("/healthz", health)
	router.Get("/readyz", ready)
	router.Get("/api/v1/health", health)
	router.Get("/api/v1/ready", ready)
	router.Get("/api/v1/config", publicConfig)
	if options.Store == nil {
		return router
	}
	service := auth.AuthorizationService{Store: options.Store, EntitlementLifecycle: options.Store, Tokens: options.Tokens, MaxConcurrentSessions: auth.SingleActiveTranslationSessionLimit}
	api := api{store: options.Store, tokens: options.Tokens, authorizer: service, now: now}
	router.Post("/api/v1/auth/register", api.register)
	router.Post("/api/v1/auth/login", api.login)
	router.Post("/api/v1/auth/refresh", api.refresh)
	router.Group(func(r chi.Router) {
		r.Use(api.require)
		r.Post("/api/v1/auth/logout", api.logout)
		r.Get("/api/v1/users/me", api.me)
		r.Get("/api/v1/users/me/devices", api.devices)
		r.Get("/api/v1/entitlements/current", api.entitlement)
		r.Post("/api/v1/redemptions", api.redeem)
		r.Post("/api/v1/translation-sessions", api.session)
		r.Get("/api/v1/translation-sessions", api.sessions)
		r.Post("/api/v1/translation-sessions/{sessionID}/end", api.sessionTerminal(false))
		r.Post("/api/v1/translation-sessions/{sessionID}/revoke", api.sessionTerminal(true))
		r.Post("/api/v1/usage-records", api.usage)
		r.Post("/api/v1/feedback/consents", api.consent)
		r.Post("/api/v1/feedback/artifacts", api.artifact)
		r.Get("/api/v1/feedback/artifacts/{artifactID}", api.getArtifact)
		r.Group(func(ad chi.Router) {
			ad.Use(api.admin)
			ad.Get("/api/v1/admin/users", api.users)
			ad.Get("/api/v1/admin/users/{userID}/translation-sessions", api.sessionsAdmin)
			ad.Get("/api/v1/admin/users/{userID}/usage-records", api.usageAdmin)
			ad.Post("/api/v1/admin/users/{userID}/disable", api.disableUser)
			ad.Post("/api/v1/admin/users/{userID}/entitlements", api.grantEntitlement)
			ad.Post("/api/v1/admin/users/{userID}/entitlements/{entitlementID}/revoke", api.revokeEntitlement)
			ad.Post("/api/v1/admin/code-batches", api.codeBatch)
			ad.Get("/api/v1/admin/audit-logs", api.auditLogs)
		})
	})
	return router
}

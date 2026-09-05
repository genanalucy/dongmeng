package httpapi

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/dngmeng/cloud-api/internal/historycrypto"
	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
)

const maxBodyBytes int64 = 1 << 20

type businessStore interface {
	domain.Store
	auth.AuthorizationStore
	auth.EntitlementLifecycleStore
	auth.ConcurrentTranslationSessionStore
	CreateSession(context.Context, domain.TranslationSession, time.Time) error
	UserEnabled(context.Context, uuid.UUID) (bool, error)
	DisableUser(context.Context, uuid.UUID, uuid.UUID, time.Time) error
	GrantEntitlementByAdmin(context.Context, uuid.UUID, uuid.UUID, time.Time) (domain.Entitlement, error)
	RevokeEntitlementByAdmin(context.Context, uuid.UUID, uuid.UUID, uuid.UUID, time.Time) error
}
type authService interface {
	Register(context.Context, string, string, string, string, time.Time) (auth.RegistrationResult, error)
	ActiveEntitlement(context.Context, uuid.UUID, time.Time) (domain.Entitlement, error)
	CreateTranslationSession(context.Context, uuid.UUID, string, time.Time) (auth.TranslationGrant, error)
	EndTranslationSession(context.Context, uuid.UUID, uuid.UUID, time.Time) error
	RevokeTranslationSession(context.Context, uuid.UUID, uuid.UUID, time.Time) error
}
type PhoneVerificationService interface {
	Verify(context.Context, string) error
}

type disabledPhoneVerificationService struct{}

func (disabledPhoneVerificationService) Verify(context.Context, string) error {
	return errVerificationNotEnabled
}

var errVerificationNotEnabled = errors.New("verification not enabled")

type api struct {
	store                    businessStore
	tokens                   auth.TokenIssuer
	authorizer               authService
	sessionAuthorizer        agentSessionAuthorizer
	agentServiceToken        string
	verification             PhoneVerificationService
	registrationVerification *auth.EmailRegistrationService
	captcha                  *auth.CaptchaService
	historyCipher            *historycrypto.Cipher
	logger                   *slog.Logger
	now                      func() time.Time
}
type principalKey struct{}
type principal struct {
	id   uuid.UUID
	role domain.Role
}

type publicUserResponse struct {
	ID        uuid.UUID `json:"id"`
	Username  string    `json:"username"`
	Role      string    `json:"role"`
	CreatedAt time.Time `json:"created_at"`
}

func publicUser(user domain.User) publicUserResponse {
	username := user.Username
	if username == "" {
		username = "旧版用户"
	}
	return publicUserResponse{ID: user.ID, Username: username, Role: user.Role, CreatedAt: user.CreatedAt}
}

func current(r *http.Request) (principal, bool) {
	p, ok := r.Context().Value(principalKey{}).(principal)
	return p, ok
}
func (a api) require(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, ok := bearer(r.Header.Values("Authorization"))
		if !ok {
			unauthorized(w, r)
			return
		}
		c, err := a.tokens.ParseAccessAt(raw, a.now())
		if err != nil {
			unauthorized(w, r)
			return
		}
		id, err := uuid.Parse(c.Subject)
		if err != nil || id == uuid.Nil {
			unauthorized(w, r)
			return
		}
		enabled, err := a.store.UserEnabled(r.Context(), id)
		if err != nil || !enabled {
			unauthorized(w, r)
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), principalKey{}, principal{id: id, role: c.Role})))
	})
}
func (a api) admin(next http.Handler) http.Handler {
	return a.require(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p, _ := current(r)
		if !p.role.IsAdmin() {
			writeError(w, r, http.StatusForbidden, "forbidden")
			return
		}
		next.ServeHTTP(w, r)
	}))
}
func bearer(v []string) (string, bool) {
	if len(v) != 1 {
		return "", false
	}
	parts := strings.Fields(v[0])
	return func() (string, bool) {
		if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") || len(parts[1]) > 4096 {
			return "", false
		}
		return parts[1], true
	}()
}
func unauthorized(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("WWW-Authenticate", `Bearer realm="cloud-api"`)
	writeError(w, r, http.StatusUnauthorized, "unauthorized")
}
func decode(w http.ResponseWriter, r *http.Request, d any) error {
	if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		return errors.New("content-type")
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	dec.DisallowUnknownFields()
	if err := dec.Decode(d); err != nil {
		return err
	}
	if err := dec.Decode(&struct{}{}); err != io.EOF {
		return errors.New("multiple values")
	}
	return nil
}
func inputError(w http.ResponseWriter, r *http.Request) {
	writeError(w, r, http.StatusBadRequest, "invalid_request")
}
func domainError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrInvalid):
		inputError(w, r)
	case errors.Is(err, domain.ErrUnauthorized):
		unauthorized(w, r)
	case errors.Is(err, domain.ErrForbidden), errors.Is(err, domain.ErrNoEntitlement):
		writeError(w, r, http.StatusForbidden, "forbidden")
	case errors.Is(err, domain.ErrNotFound):
		writeError(w, r, http.StatusNotFound, "not_found")
	case errors.Is(err, domain.ErrConflict):
		writeError(w, r, http.StatusConflict, "conflict")
	case errors.Is(err, domain.ErrHistoryLimitExceeded):
		writeError(w, r, http.StatusConflict, "history_limit_exceeded")
	default:
		writeError(w, r, http.StatusInternalServerError, "internal_error")
	}
}

// registrationVerificationUnavailable is the stable migration boundary for
// the email verification registration endpoints. They are unroutable by
// policy: captcha registration is the only registration path.
func registrationVerificationUnavailable(w http.ResponseWriter, r *http.Request) {
	writeError(w, r, http.StatusServiceUnavailable, "registration_verification_not_enabled")
}

func (a api) warnCaptcha(r *http.Request, stage string) {
	if a.logger != nil {
		// Only bounded metadata: never the challenge images, target
		// coordinate, captcha id, password, or email.
		a.logger.Warn("captcha registration did not complete", "request_id", RequestIDFromContext(r.Context()), "stage", stage)
	}
}

// rateLimitedRetryAfter derives the Retry-After header from the rejected
// window's remaining seconds when the store reports them, and otherwise falls
// back to the historical conservative default.
func rateLimitedRetryAfter(err error) string {
	var limited domain.RateLimitedError
	if errors.As(err, &limited) && limited.RetryAfterSeconds >= 1 {
		return strconv.Itoa(limited.RetryAfterSeconds)
	}
	return "60"
}

func writeRateLimited(w http.ResponseWriter, r *http.Request, err error) {
	w.Header().Set("Retry-After", rateLimitedRetryAfter(err))
	writeError(w, r, http.StatusTooManyRequests, "rate_limited")
}

func (a api) captchaIssue(w http.ResponseWriter, r *http.Request) {
	if a.captcha == nil {
		writeError(w, r, http.StatusServiceUnavailable, "captcha_not_enabled")
		return
	}
	clientIP := trustedClientIP(r)
	if !clientIP.IsValid() {
		inputError(w, r)
		return
	}
	// The issue window is charged in its own committed transaction before any
	// challenge material is produced, so internal failures cannot dodge it.
	if err := a.store.ChargeCaptchaIssueWindow(r.Context(), registrationRateLimitKey(a.captcha.RateLimitKeySecret, "captcha:issue:"+clientIP.String()), a.now().UTC()); err != nil {
		if errors.Is(err, domain.ErrRateLimited) {
			writeRateLimited(w, r, err)
			return
		}
		a.warnCaptcha(r, "rate_limit")
		writeError(w, r, http.StatusInternalServerError, "internal_error")
		return
	}
	draft, err := a.captcha.Issue()
	if err != nil {
		a.warnCaptcha(r, "issue")
		writeError(w, r, http.StatusInternalServerError, "internal_error")
		return
	}
	captcha, err := a.store.CreateRegistrationCaptcha(r.Context(), domain.CreateRegistrationCaptchaParams{
		AnswerHash: draft.AnswerHash,
		AnswerSalt: draft.AnswerSalt,
		Now:        a.now().UTC(),
		ExpiresAt:  draft.ExpiresAt,
	})
	if err != nil {
		a.warnCaptcha(r, "storage")
		writeError(w, r, http.StatusInternalServerError, "internal_error")
		return
	}
	// The Android-native contract: both rendered images arrive as standard
	// base64 payloads with explicit MIME types, pixel sizes, the tile's start
	// position, and the server tolerance, so a client renders the challenge
	// from this one JSON document without any JavaScript or extra requests.
	// The hidden target coordinate is never part of the response.
	writeJSON(w, http.StatusOK, map[string]any{
		"captcha_id":   captcha.ID,
		"expires_in":   int(auth.CaptchaTTL.Seconds()),
		"tolerance_px": auth.CaptchaTolerance,
		"challenge": map[string]any{
			"image_base64": base64.StdEncoding.EncodeToString(draft.MasterImage),
			"image_type":   "image/jpeg",
			"width":        draft.MasterWidth,
			"height":       draft.MasterHeight,
		},
		"tile": map[string]any{
			"image_base64": base64.StdEncoding.EncodeToString(draft.TileImage),
			"image_type":   "image/png",
			"width":        draft.TileWidth,
			"height":       draft.TileHeight,
			"start_x":      draft.TileStartX,
			"start_y":      draft.TileStartY,
		},
	})
}

func (a api) register(w http.ResponseWriter, r *http.Request) {
	var x struct {
		Username  string `json:"username"`
		Email     string `json:"email"`
		Password  string `json:"password"`
		CaptchaID string `json:"captcha_id"`
		// CaptchaX is the submitted final tile position in challenge pixels;
		// a pointer distinguishes a missing field from the valid value 0.
		CaptchaX *int `json:"captcha_x"`
	}
	if decode(w, r, &x) != nil || x.Username == "" || x.Email == "" || x.Password == "" || x.CaptchaID == "" || x.CaptchaX == nil {
		inputError(w, r)
		return
	}
	if !auth.ValidCaptchaCoordinate(*x.CaptchaX) {
		inputError(w, r)
		return
	}
	captchaX := *x.CaptchaX
	input, err := domain.ParseRegistrationVerificationInput(x.Username, x.Email, x.Password)
	if err != nil {
		inputError(w, r)
		return
	}
	captchaID, err := uuid.Parse(x.CaptchaID)
	if err != nil || captchaID == uuid.Nil || captchaID.String() != x.CaptchaID {
		inputError(w, r)
		return
	}
	if a.captcha == nil {
		writeError(w, r, http.StatusServiceUnavailable, "captcha_not_enabled")
		return
	}
	clientIP := trustedClientIP(r)
	if !clientIP.IsValid() {
		inputError(w, r)
		return
	}
	now := a.now().UTC()
	// Order is a security contract: the cheap committed per trusted client IP
	// registration window first (charged for every validly formatted request
	// regardless of the registration outcome), then the one-time captcha
	// reservation, and only afterwards the expensive Argon2id password hash.
	if err := a.store.ChargeCaptchaRegisterWindow(r.Context(), registrationRateLimitKey(a.captcha.RateLimitKeySecret, "captcha:register:"+clientIP.String()), now); err != nil {
		if errors.Is(err, domain.ErrRateLimited) {
			writeRateLimited(w, r, err)
			return
		}
		a.warnCaptcha(r, "rate_limit")
		writeError(w, r, http.StatusInternalServerError, "internal_error")
		return
	}
	if err := a.store.ReserveRegistrationCaptcha(r.Context(), domain.ReserveRegistrationCaptchaParams{
		CaptchaID:    captchaID,
		CaptchaX:     captchaX,
		AnswerPepper: a.captcha.AnswerPepper,
		Now:          now,
	}); err != nil {
		switch {
		case errors.Is(err, domain.ErrCaptchaFailed):
			writeError(w, r, http.StatusBadRequest, "captcha_failed")
		case errors.Is(err, domain.ErrInvalid):
			inputError(w, r)
		default:
			a.warnCaptcha(r, "reserve")
			writeError(w, r, http.StatusInternalServerError, "internal_error")
		}
		return
	}
	passwordHash, err := auth.HashPassword(input.Password.String())
	if err != nil {
		inputError(w, r)
		return
	}
	user, trial, err := a.store.RegisterWithCaptcha(r.Context(), domain.RegisterWithCaptchaParams{
		Username:     input.Username.String(),
		Email:        input.Email.String(),
		PasswordHash: passwordHash,
		CaptchaID:    captchaID,
		CaptchaX:     captchaX,
		AnswerPepper: a.captcha.AnswerPepper,
		Now:          now,
	})
	if err != nil {
		switch {
		case errors.Is(err, domain.ErrCaptchaFailed):
			writeError(w, r, http.StatusBadRequest, "captcha_failed")
		case errors.Is(err, domain.ErrRateLimited):
			writeRateLimited(w, r, err)
		case errors.Is(err, domain.ErrInvalid):
			inputError(w, r)
		case errors.Is(err, domain.ErrConflict):
			domainError(w, r, err)
		default:
			a.warnCaptcha(r, "storage")
			writeError(w, r, http.StatusInternalServerError, "internal_error")
		}
		return
	}
	a.writeRegistrationCreated(w, r, user, trial)
}

func (a api) writeRegistrationCreated(w http.ResponseWriter, r *http.Request, user domain.User, trial domain.Entitlement) {
	access, err := a.tokens.AccessToken(user.ID, user.Role, 15*time.Minute, a.now())
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, "internal_error")
		return
	}
	refresh, err := (auth.RefreshManager{Store: a.store}).Issue(r.Context(), user.ID, 30*24*time.Hour, a.now())
	if err != nil {
		domainError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"user": publicUser(user), "trial_entitlement": trial,
		"access_token": access, "refresh_token": refresh.Plaintext, "token_type": "Bearer", "expires_in": 900,
	})
}

func trustedClientIP(r *http.Request) netip.Addr {
	remoteHost, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return netip.Addr{}
	}
	remoteIP, err := netip.ParseAddr(remoteHost)
	if err != nil {
		return netip.Addr{}
	}
	if remoteIP.IsLoopback() {
		forwarded := r.Header.Values("X-Forwarded-For")
		if len(forwarded) == 1 && strings.TrimSpace(forwarded[0]) == forwarded[0] && !strings.Contains(forwarded[0], ",") {
			if clientIP, err := netip.ParseAddr(forwarded[0]); err == nil && clientIP.IsValid() {
				return clientIP.Unmap()
			}
		}
	}
	return remoteIP.Unmap()
}

func (a api) registrationVerificationRequest(w http.ResponseWriter, r *http.Request) {
	var x struct {
		Username string `json:"username"`
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if decode(w, r, &x) != nil || x.Username == "" || x.Email == "" || x.Password == "" {
		inputError(w, r)
		return
	}
	service := a.registrationVerification
	if service == nil {
		writeError(w, r, http.StatusServiceUnavailable, "registration_verification_not_enabled")
		return
	}
	result, err := a.requestRegistrationVerification(r.Context(), service, auth.RegistrationVerificationRequest{Username: x.Username, Email: x.Email, Password: x.Password, ClientIP: trustedClientIP(r)})
	if err == nil {
		writeJSON(w, http.StatusAccepted, map[string]int{"retry_after_seconds": result.RetryAfterSeconds})
		return
	}
	if errors.Is(err, domain.ErrInvalid) {
		inputError(w, r)
		return
	}
	if a.logger != nil {
		a.logger.Warn("registration verification request did not complete", "request_id", RequestIDFromContext(r.Context()), "reason", registrationVerificationReason(err))
	}
	writeJSON(w, http.StatusAccepted, map[string]int{"retry_after_seconds": int(auth.RegistrationVerificationResendDelay.Seconds())})
}

func (a api) requestRegistrationVerification(ctx context.Context, service *auth.EmailRegistrationService, request auth.RegistrationVerificationRequest) (auth.RegistrationVerificationResult, error) {
	configured := *service
	configured.Clock = a.now
	configured.WriteVerification = func(ctx context.Context, draft auth.RegistrationVerificationDraft) (uuid.UUID, error) {
		verification, err := a.store.RequestRegistrationVerification(ctx, domain.CreateRegistrationVerificationParams{
			Username: draft.Username, Email: draft.Email, PasswordHash: draft.PasswordHash, CodeHash: draft.CodeHash, CodeSalt: draft.Salt,
			EmailRateLimitKey: registrationRateLimitKey(configured.RateLimitKeySecret, "email:"+draft.Email), IPRateLimitKey: registrationRateLimitKey(configured.RateLimitKeySecret, "ip:"+request.ClientIP.String()),
			Now: a.now().UTC(), ExpiresAt: draft.ExpiresAt,
		})
		return verification.ReservationID, err
	}
	configured.InvalidateVerification = func(ctx context.Context, reservationID uuid.UUID, email string, now time.Time) error {
		return a.store.InvalidateRegistrationVerification(ctx, domain.InvalidateRegistrationVerificationParams{ReservationID: reservationID, Email: email, Now: now})
	}
	return configured.RequestVerification(ctx, request)
}

func (a api) confirmRegistrationVerification(ctx context.Context, service *auth.EmailRegistrationService, confirmation auth.RegistrationVerificationConfirmation) (domain.RegisterParams, error) {
	email, err := domain.ParseEmail(confirmation.Email)
	if err != nil {
		return domain.RegisterParams{}, auth.ErrRegistrationVerificationFailed
	}
	code, err := auth.ParseRegistrationVerificationCode(confirmation.Code)
	if err != nil {
		return domain.RegisterParams{}, auth.ErrRegistrationVerificationFailed
	}
	return a.store.ConfirmRegistrationVerification(ctx, domain.ConfirmRegistrationVerificationParams{Email: email.String(), Code: code, CodePepper: service.CodePepper, EmailRateLimitKey: registrationRateLimitKey(service.RateLimitKeySecret, "email:"+email.String()), Now: a.now().UTC()})
}

func registrationRateLimitKey(secret []byte, value string) []byte {
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write([]byte(value))
	return mac.Sum(nil)
}

func registrationVerificationReason(err error) string {
	switch {
	case errors.Is(err, domain.ErrConflict):
		return "conflict"
	case errors.Is(err, domain.ErrRegistrationVerificationFailed):
		return "limited"
	default:
		return "delivery_or_storage"
	}
}

func (a api) registrationVerificationConfirm(w http.ResponseWriter, r *http.Request) {
	var x struct {
		Email string `json:"email"`
		Code  string `json:"code"`
	}
	if decode(w, r, &x) != nil || x.Email == "" || x.Code == "" {
		inputError(w, r)
		return
	}
	service := a.registrationVerification
	if service == nil {
		writeError(w, r, http.StatusServiceUnavailable, "registration_verification_not_enabled")
		return
	}
	registration, err := a.confirmRegistrationVerification(r.Context(), service, auth.RegistrationVerificationConfirmation{Email: x.Email, Code: x.Code})
	if err != nil {
		if errors.Is(err, domain.ErrConflict) {
			domainError(w, r, err)
			return
		}
		writeError(w, r, http.StatusBadRequest, "verification_failed")
		return
	}
	user, _, err := a.store.UserByEmail(r.Context(), registration.Email)
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, "internal_error")
		return
	}
	trial, err := a.store.ActiveEntitlement(r.Context(), user.ID, a.now())
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, "internal_error")
		return
	}
	a.writeRegistrationCreated(w, r, user, trial)
}
func invalidCredentials(w http.ResponseWriter, r *http.Request) {
	writeError(w, r, http.StatusUnauthorized, "invalid_credentials")
}
func (a api) login(w http.ResponseWriter, r *http.Request) {
	var x struct {
		Identifier string `json:"identifier"`
		Password   string `json:"password"`
	}
	if decode(w, r, &x) != nil {
		inputError(w, r)
		return
	}
	identifier, e := domain.ParseLoginIdentifier(x.Identifier)
	if e != nil {
		invalidCredentials(w, r)
		return
	}
	var u domain.User
	var hash string
	switch identifier.Kind {
	case domain.LoginIdentifierPhone:
		u, hash, e = a.store.UserByPhone(r.Context(), identifier.Value)
	case domain.LoginIdentifierEmail:
		u, hash, e = a.store.UserByEmail(r.Context(), identifier.Value)
	case domain.LoginIdentifierUsername:
		u, hash, e = a.store.UserByUsername(r.Context(), identifier.Value)
	default:
		e = domain.ErrNotFound
	}
	if e != nil {
		invalidCredentials(w, r)
		return
	}
	ok, e := auth.VerifyPassword(hash, x.Password)
	if e != nil || !ok {
		invalidCredentials(w, r)
		return
	}
	a.issueTokens(w, r, u)
}
func (a api) phoneVerification(w http.ResponseWriter, r *http.Request) {
	var x struct {
		Phone string `json:"phone"`
	}
	if decode(w, r, &x) != nil {
		inputError(w, r)
		return
	}
	phone, err := domain.ParsePhone(x.Phone)
	if err != nil {
		inputError(w, r)
		return
	}
	service := a.verification
	if service == nil {
		service = disabledPhoneVerificationService{}
	}
	if err := service.Verify(r.Context(), phone.String()); errors.Is(err, errVerificationNotEnabled) {
		writeError(w, r, http.StatusServiceUnavailable, "verification_not_enabled")
		return
	}
	writeError(w, r, http.StatusInternalServerError, "internal_error")
}
func (a api) issueTokens(w http.ResponseWriter, r *http.Request, u domain.User) {
	now := a.now()
	access, e := a.tokens.AccessToken(u.ID, u.Role, 15*time.Minute, now)
	if e != nil {
		writeError(w, r, 500, "internal_error")
		return
	}
	refresh, e := (auth.RefreshManager{Store: a.store}).Issue(r.Context(), u.ID, 30*24*time.Hour, now)
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, 200, map[string]any{"access_token": access, "refresh_token": refresh.Plaintext, "token_type": "Bearer", "expires_in": 900})
}
func (a api) refresh(w http.ResponseWriter, r *http.Request) {
	var x struct {
		RefreshToken string `json:"refresh_token"`
	}
	if decode(w, r, &x) != nil {
		inputError(w, r)
		return
	}
	issue, e := (auth.RefreshManager{Store: a.store}).Rotate(r.Context(), x.RefreshToken, 30*24*time.Hour, a.now())
	if e != nil {
		unauthorized(w, r)
		return
	}
	u, e := a.store.UserByID(r.Context(), issue.Token.UserID)
	if e != nil {
		unauthorized(w, r)
		return
	}
	access, e := a.tokens.AccessToken(u.ID, u.Role, 15*time.Minute, a.now())
	if e != nil {
		writeError(w, r, 500, "internal_error")
		return
	}
	writeJSON(w, 200, map[string]any{"access_token": access, "refresh_token": issue.Plaintext, "token_type": "Bearer", "expires_in": 900})
}
func (a api) logout(w http.ResponseWriter, r *http.Request) {
	var x struct {
		RefreshToken string `json:"refresh_token"`
	}
	if decode(w, r, &x) != nil {
		inputError(w, r)
		return
	}
	if e := (auth.RefreshManager{Store: a.store}).Revoke(r.Context(), x.RefreshToken, a.now()); e != nil {
		inputError(w, r)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
func (a api) me(w http.ResponseWriter, r *http.Request) {
	p, _ := current(r)
	u, e := a.store.UserByID(r.Context(), p.id)
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, 200, publicUser(u))
}
func (a api) devices(w http.ResponseWriter, r *http.Request) {
	p, _ := current(r)
	v, e := a.store.ListDevices(r.Context(), p.id)
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": v})
}

type accountEntitlementResponse struct {
	Kind             string    `json:"kind,omitempty"`
	StartsAt         time.Time `json:"starts_at,omitempty"`
	ExpiresAt        time.Time `json:"expires_at,omitempty"`
	Active           bool      `json:"active"`
	RemainingSeconds int64     `json:"remaining_seconds"`
}

type accountUsageSummaryResponse struct {
	AudioSeconds int        `json:"audio_seconds"`
	SessionCount int        `json:"session_count"`
	LastUsedAt   *time.Time `json:"last_used_at"`
}

func (a api) accountOverview(w http.ResponseWriter, r *http.Request) {
	p, _ := current(r)
	value, err := a.store.AccountOverview(r.Context(), p.id)
	if err != nil {
		domainError(w, r, err)
		return
	}
	response := struct {
		Username    string                      `json:"username"`
		Entitlement accountEntitlementResponse  `json:"entitlement"`
		Usage       accountUsageSummaryResponse `json:"usage"`
	}{Username: publicUser(value.User).Username, Usage: accountUsageSummaryResponse{AudioSeconds: value.Usage.AudioSeconds, SessionCount: value.Usage.SessionCount, LastUsedAt: value.Usage.LastUsedAt}}
	if value.Entitlement != nil {
		response.Entitlement.Kind = value.Entitlement.Kind
		response.Entitlement.StartsAt = value.Entitlement.StartsAt
		response.Entitlement.ExpiresAt = value.Entitlement.ExpiresAt
		response.Entitlement.Active = value.Entitlement.ActiveAt(a.now())
		if response.Entitlement.Active {
			response.Entitlement.RemainingSeconds = int64(value.Entitlement.ExpiresAt.Sub(a.now()).Seconds())
		}
	}
	writeJSON(w, http.StatusOK, response)
}

func accountPage(r *http.Request) (int, int, error) {
	limitText, offsetText := r.URL.Query().Get("limit"), r.URL.Query().Get("offset")
	if limitText == "" || offsetText == "" {
		return 0, 0, domain.ErrInvalid
	}
	limit, err := strconv.Atoi(limitText)
	if err != nil || limit < 1 || limit > 50 {
		return 0, 0, domain.ErrInvalid
	}
	offset, err := strconv.Atoi(offsetText)
	if err != nil || offset < 0 {
		return 0, 0, domain.ErrInvalid
	}
	return limit, offset, nil
}

func (a api) accountUsage(w http.ResponseWriter, r *http.Request) {
	limit, offset, err := accountPage(r)
	if err != nil {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	items, err := a.store.ListAccountUsage(r.Context(), p.id, limit, offset)
	if err != nil {
		domainError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"usage": items})
}

func (a api) accountIdentityProfile(w http.ResponseWriter, r *http.Request) {
	p, _ := current(r)
	profile, err := a.store.AccountIdentity(r.Context(), p.id)
	if err != nil {
		domainError(w, r, err)
		return
	}
	response := struct {
		Username    string `json:"username"`
		Email       string `json:"email"`
		MaskedPhone string `json:"masked_phone,omitempty"`
	}{Username: profile.Username, Email: profile.Email, MaskedPhone: profile.MaskedPhone}
	writeJSON(w, http.StatusOK, response)
}

func (a api) accountIdentity(w http.ResponseWriter, r *http.Request) {
	var request struct {
		Username        string `json:"username"`
		Email           string `json:"email"`
		Phone           string `json:"phone"`
		CurrentPassword string `json:"current_password"`
	}
	if decode(w, r, &request) != nil || request.Username == "" || request.Email == "" || request.Phone == "" || request.CurrentPassword == "" {
		inputError(w, r)
		return
	}
	username, err := domain.ParseUsername(request.Username)
	if err != nil {
		inputError(w, r)
		return
	}
	email, err := domain.ParseEmail(request.Email)
	if err != nil {
		inputError(w, r)
		return
	}
	phone, err := domain.ParsePhone(request.Phone)
	if err != nil {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	user, err := a.store.UpdateIdentity(r.Context(), domain.UpdateIdentityParams{UserID: p.id, Username: username.String(), Email: email.String(), Phone: phone.String(), CurrentPassword: request.CurrentPassword})
	if errors.Is(err, domain.ErrUnauthorized) || errors.Is(err, domain.ErrNotFound) || errors.Is(err, domain.ErrForbidden) {
		invalidCredentials(w, r)
		return
	}
	if err != nil {
		domainError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, publicUser(user))
}

// deleteAccount implements DELETE /api/v1/account: authenticated
// self-service account deletion with exact-username confirmation. The
// confirmation is parsed and canonicalized like every other username input,
// then compared by the store only against the token subject's own current
// username; no client-supplied identifier can ever select a different
// account. Admin principals are rejected outright, and the store re-checks
// the persisted role inside the deletion transaction. Success returns 204
// with no body; the caller's access and refresh tokens are dead from this
// commit onward because the account row is disabled and every refresh family
// is revoked.
func (a api) deleteAccount(w http.ResponseWriter, r *http.Request) {
	var request struct {
		Username string `json:"username"`
	}
	if decode(w, r, &request) != nil {
		inputError(w, r)
		return
	}
	username, err := domain.ParseUsername(request.Username)
	if err != nil {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	if p.role.IsAdmin() {
		writeError(w, r, http.StatusForbidden, "forbidden")
		return
	}
	if err := a.store.DeleteAccount(r.Context(), domain.DeleteAccountParams{UserID: p.id, Username: username.String(), Now: a.now()}); err != nil {
		domainError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a api) entitlement(w http.ResponseWriter, r *http.Request) {
	p, _ := current(r)
	v, e := a.authorizer.ActiveEntitlement(r.Context(), p.id, a.now())
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, 200, v)
}
func (a api) redeem(w http.ResponseWriter, r *http.Request) {
	var x struct {
		Code string `json:"code"`
	}
	if decode(w, r, &x) != nil {
		inputError(w, r)
		return
	}
	h, e := auth.HashRedemptionCode(x.Code)
	if e != nil {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	v, e := a.store.RedeemCode(r.Context(), p.id, h, a.now())
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, 201, v)
}
func (a api) session(w http.ResponseWriter, r *http.Request) {
	var x struct {
		InstallID string `json:"install_id"`
	}
	if decode(w, r, &x) != nil {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	v, e := a.authorizer.CreateTranslationSession(r.Context(), p.id, x.InstallID, a.now())
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, 201, map[string]any{"session_id": v.Session.ID, "user_id": p.id, "install_id": v.Session.InstallID, "expires_at": v.Session.ExpiresAt, "token": v.Token})
}
func (a api) sessions(w http.ResponseWriter, r *http.Request) {
	p, _ := current(r)
	l, o := page(r)
	v, e := a.store.ListTranslationSessions(r.Context(), p.id, l, o)
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"translation_sessions": v})
}
func (a api) sessionTerminal(revoke bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, e := uuid.Parse(chi.URLParam(r, "sessionID"))
		if e != nil || id == uuid.Nil || id.String() != chi.URLParam(r, "sessionID") {
			inputError(w, r)
			return
		}
		p, _ := current(r)
		if revoke {
			e = a.authorizer.RevokeTranslationSession(r.Context(), p.id, id, a.now())
		} else {
			e = a.authorizer.EndTranslationSession(r.Context(), p.id, id, a.now())
		}
		if e != nil {
			domainError(w, r, e)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
func (a api) usage(w http.ResponseWriter, r *http.Request) {
	var x struct {
		SessionID    uuid.UUID `json:"session_id"`
		AudioSeconds int       `json:"audio_seconds"`
		Characters   int       `json:"characters"`
	}
	if decode(w, r, &x) != nil || x.SessionID == uuid.Nil || x.AudioSeconds < 0 || x.Characters < 0 {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	e := a.store.CreateUsageRecord(r.Context(), domain.CreateUsageParams{UserID: p.id, SessionID: x.SessionID, AudioSeconds: x.AudioSeconds, Characters: x.Characters, Now: a.now()})
	if e != nil {
		domainError(w, r, e)
		return
	}
	w.WriteHeader(201)
}
func (a api) consent(w http.ResponseWriter, r *http.Request) {
	var x struct {
		Granted bool `json:"granted"`
	}
	if decode(w, r, &x) != nil {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	v, e := a.store.CreateFeedbackConsent(r.Context(), p.id, x.Granted, a.now())
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, 201, v)
}
func (a api) artifact(w http.ResponseWriter, r *http.Request) {
	var x struct {
		ConsentID uuid.UUID `json:"consent_id"`
		ObjectKey string    `json:"object_key"`
	}
	if decode(w, r, &x) != nil || x.ConsentID == uuid.Nil || strings.TrimSpace(x.ObjectKey) != x.ObjectKey || x.ObjectKey == "" {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	now := a.now()
	v, e := a.store.CreateFeedbackArtifact(r.Context(), domain.CreateArtifactParams{UserID: p.id, ConsentID: x.ConsentID, ObjectKey: x.ObjectKey, Now: now, ExpiresAt: now.Add(14 * 24 * time.Hour)})
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, 201, v)
}
func (a api) getArtifact(w http.ResponseWriter, r *http.Request) {
	id, e := uuid.Parse(chi.URLParam(r, "artifactID"))
	if e != nil || id == uuid.Nil {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	v, e := a.store.FeedbackArtifact(r.Context(), p.id, id)
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, 200, v)
}
func page(r *http.Request) (int, int) {
	l, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	o, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	if l < 1 || l > 100 {
		l = 50
	}
	if o < 0 {
		o = 0
	}
	return l, o
}

type adminUserResponse struct {
	ID        uuid.UUID `json:"id"`
	Username  string    `json:"username,omitempty"`
	Email     string    `json:"email,omitempty"`
	Role      string    `json:"role"`
	CreatedAt time.Time `json:"created_at"`
}

func adminUser(user domain.User) adminUserResponse {
	email := user.Email
	if strings.HasPrefix(email, "phone-") && strings.HasSuffix(email, "@reserved.invalid") {
		email = ""
	}
	return adminUserResponse{ID: user.ID, Username: user.Username, Email: email, Role: user.Role, CreatedAt: user.CreatedAt}
}

func (a api) users(w http.ResponseWriter, r *http.Request) {
	l, o := page(r)
	search := strings.TrimSpace(r.URL.Query().Get("q"))
	if len(search) > 254 {
		inputError(w, r)
		return
	}
	v, e := a.store.ListUsers(r.Context(), search, l, o)
	if e != nil {
		domainError(w, r, e)
		return
	}
	users := make([]adminUserResponse, len(v))
	for i, user := range v {
		users[i] = adminUser(user)
	}
	writeJSON(w, 200, map[string]any{"users": users})
}
func (a api) sessionsAdmin(w http.ResponseWriter, r *http.Request) {
	user, ok := pathUUID(r, "userID")
	if !ok {
		inputError(w, r)
		return
	}
	l, o := page(r)
	v, e := a.store.ListTranslationSessions(r.Context(), user, l, o)
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"translation_sessions": v})
}
func (a api) usageAdmin(w http.ResponseWriter, r *http.Request) {
	user, ok := pathUUID(r, "userID")
	if !ok {
		inputError(w, r)
		return
	}
	l, o := page(r)
	v, e := a.store.ListUsageRecords(r.Context(), user, l, o)
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"usage_records": v})
}

package httpapi

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	_ "image/jpeg"
	_ "image/png"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
	"github.com/wenlng/go-captcha/v2/base/imagedata"
	"github.com/wenlng/go-captcha/v2/slide"
)

type captchaChargeCall struct {
	key []byte
	now time.Time
}

type captchaHTTPStore struct {
	businessStore
	chargeIssue     func(context.Context, []byte, time.Time) error
	create          func(context.Context, domain.CreateRegistrationCaptchaParams) (domain.RegistrationCaptcha, error)
	chargeRegister  func(context.Context, []byte, time.Time) error
	reserve         func(context.Context, domain.ReserveRegistrationCaptchaParams) error
	register        func(context.Context, domain.RegisterWithCaptchaParams) (domain.User, domain.Entitlement, error)
	calls           []string
	issueCharges    []captchaChargeCall
	registerCharges []captchaChargeCall
	created         []domain.CreateRegistrationCaptchaParams
	reservations    []domain.ReserveRegistrationCaptchaParams
	registrants     []domain.RegisterWithCaptchaParams
	refreshes       []domain.RefreshToken
}

func (s *captchaHTTPStore) ChargeCaptchaIssueWindow(ctx context.Context, key []byte, now time.Time) error {
	s.calls = append(s.calls, "charge_issue")
	s.issueCharges = append(s.issueCharges, captchaChargeCall{key: key, now: now})
	if s.chargeIssue == nil {
		return nil
	}
	return s.chargeIssue(ctx, key, now)
}

func (s *captchaHTTPStore) CreateRegistrationCaptcha(ctx context.Context, params domain.CreateRegistrationCaptchaParams) (domain.RegistrationCaptcha, error) {
	s.calls = append(s.calls, "create")
	s.created = append(s.created, params)
	if s.create == nil {
		return domain.RegistrationCaptcha{ID: uuid.New(), ExpiresAt: params.ExpiresAt}, nil
	}
	return s.create(ctx, params)
}

func (s *captchaHTTPStore) ChargeCaptchaRegisterWindow(ctx context.Context, key []byte, now time.Time) error {
	s.calls = append(s.calls, "charge_register")
	s.registerCharges = append(s.registerCharges, captchaChargeCall{key: key, now: now})
	if s.chargeRegister == nil {
		return nil
	}
	return s.chargeRegister(ctx, key, now)
}

func (s *captchaHTTPStore) ReserveRegistrationCaptcha(ctx context.Context, params domain.ReserveRegistrationCaptchaParams) error {
	s.calls = append(s.calls, "reserve")
	s.reservations = append(s.reservations, params)
	if s.reserve == nil {
		return nil
	}
	return s.reserve(ctx, params)
}

func (s *captchaHTTPStore) RegisterWithCaptcha(ctx context.Context, params domain.RegisterWithCaptchaParams) (domain.User, domain.Entitlement, error) {
	s.calls = append(s.calls, "register")
	s.registrants = append(s.registrants, params)
	if s.register == nil {
		return domain.User{}, domain.Entitlement{}, domain.ErrCaptchaFailed
	}
	return s.register(ctx, params)
}

func (s *captchaHTTPStore) CreateRefreshToken(_ context.Context, params domain.CreateRefreshParams) (domain.RefreshToken, error) {
	token := domain.RefreshToken{ID: uuid.New(), UserID: params.UserID, FamilyID: params.FamilyID, TokenHash: params.Hash, ExpiresAt: params.ExpiresAt}
	s.refreshes = append(s.refreshes, token)
	return token, nil
}

const (
	captchaTestNow      = "2026-09-05T06:07:08Z"
	captchaTestTargetX  = 137
	captchaTestPassword = "password1"
)

func captchaTestClock() time.Time {
	now, _ := time.Parse(time.RFC3339, captchaTestNow)
	return now
}

// pinnedSlideGenerator keeps the hidden target deterministic so HTTP tests
// can submit a correct drag coordinate, exactly like a client that solved
// the rendered challenge would.
type pinnedSlideGenerator struct{}

func (pinnedSlideGenerator) Generate() (slide.CaptchaData, error) {
	return pinnedSlideData{}, nil
}

type pinnedSlideData struct{}

func (pinnedSlideData) GetData() *slide.Block {
	return &slide.Block{X: captchaTestTargetX, Y: 96, Width: 64, Height: 64, DX: 7, DY: 96}
}
func (pinnedSlideData) GetMasterImage() imagedata.JPEGImageData {
	return imagedata.NewJPEGImageData(solidImage(auth.CaptchaImageWidth, auth.CaptchaImageHeight))
}
func (pinnedSlideData) GetTileImage() imagedata.PNGImageData {
	return imagedata.NewPNGImageData(solidImage(64, 64))
}

func solidImage(width, height int) image.Image {
	canvas := image.NewRGBA(image.Rect(0, 0, width, height))
	for x := 0; x < width; x++ {
		for y := 0; y < height; y++ {
			canvas.Set(x, y, color.RGBA{R: uint8(x % 256), G: uint8(y % 256), B: 0x7F, A: 0xFF})
		}
	}
	return canvas
}

func newCaptchaTestService(t *testing.T) *auth.CaptchaService {
	t.Helper()
	service, err := auth.NewCaptchaService(auth.CaptchaService{
		AnswerPepper:       bytes.Repeat([]byte("p"), auth.MinimumSecretBytes),
		RateLimitKeySecret: bytes.Repeat([]byte("k"), auth.MinimumSecretBytes),
		GenerateSlide:      pinnedSlideGenerator{},
		GenerateSalt:       func() ([]byte, error) { return bytes.Repeat([]byte("s"), 32), nil },
		Clock:              captchaTestClock,
	})
	if err != nil {
		t.Fatal(err)
	}
	return &service
}

func newCaptchaRouter(t *testing.T, store *captchaHTTPStore, options RouterOptions) http.Handler {
	t.Helper()
	if options.Now == nil {
		options.Now = captchaTestClock
	}
	options.Config = config.Config{Environment: "test", DatabaseTimeout: time.Second, RateLimitRPS: 1000, RateLimitBurst: 1000}
	options.Store = store
	if options.Tokens.AccessSecret == nil {
		options.Tokens = auth.TokenIssuer{
			Issuer: "test-cloud-api", Audience: "test-clients", SessionAudience: "test-agent",
			AccessSecret: bytes.Repeat([]byte("a"), auth.MinimumSecretBytes), SessionSecret: bytes.Repeat([]byte("s"), auth.MinimumSecretBytes),
		}
	}
	if options.Captcha == nil {
		options.Captcha = newCaptchaTestService(t)
	}
	return NewRouter(options)
}

type captchaImagePayload struct {
	ImageBase64 string `json:"image_base64"`
	ImageType   string `json:"image_type"`
	Width       int    `json:"width"`
	Height      int    `json:"height"`
}

type captchaIssuePayload struct {
	CaptchaID   string              `json:"captcha_id"`
	ExpiresIn   float64             `json:"expires_in"`
	TolerancePx int                 `json:"tolerance_px"`
	Challenge   captchaImagePayload `json:"challenge"`
	Tile        struct {
		captchaImagePayload
		StartX int `json:"start_x"`
		StartY int `json:"start_y"`
	} `json:"tile"`
}

func captchaGET(t *testing.T, router http.Handler) (payload captchaIssuePayload, response *httptest.ResponseRecorder) {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/captcha", nil)
	req.RemoteAddr = "127.0.0.1:12345"
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("captcha response is not JSON: %s", recorder.Body.String())
	}
	return payload, recorder
}

func TestCaptchaIssueReturnsAndroidSlideChallengeMaterial(t *testing.T) {
	store := &captchaHTTPStore{}
	router := newCaptchaRouter(t, store, RouterOptions{})

	payload, response := captchaGET(t, router)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d body = %s", response.Code, response.Body.String())
	}
	if _, err := uuid.Parse(payload.CaptchaID); err != nil {
		t.Fatalf("captcha_id = %q", payload.CaptchaID)
	}
	if payload.ExpiresIn != 300 {
		t.Fatalf("expires_in = %v, want 300", payload.ExpiresIn)
	}
	if payload.TolerancePx != auth.CaptchaTolerance {
		t.Fatalf("tolerance_px = %d, want %d", payload.TolerancePx, auth.CaptchaTolerance)
	}
	// The challenge image must be a decodable JPEG of the declared canvas so
	// an Android client can render it from base64 without any negotiation.
	master, format, err := decodeImage(payload.Challenge.ImageBase64)
	if err != nil || format != "jpeg" {
		t.Fatalf("challenge image is not a decodable JPEG: %v", err)
	}
	if payload.Challenge.ImageType != "image/jpeg" || payload.Challenge.Width != auth.CaptchaImageWidth || payload.Challenge.Height != auth.CaptchaImageHeight {
		t.Fatalf("challenge envelope = %+v", payload.Challenge)
	}
	if master.Bounds().Dx() != payload.Challenge.Width || master.Bounds().Dy() != payload.Challenge.Height {
		t.Fatalf("challenge image bounds %v disagree with %dx%d", master.Bounds(), payload.Challenge.Width, payload.Challenge.Height)
	}
	// The tile must be a decodable PNG with alpha and an in-canvas start.
	tile, format, err := decodeImage(payload.Tile.ImageBase64)
	if err != nil || format != "png" {
		t.Fatalf("tile image is not a decodable PNG: %v", err)
	}
	if payload.Tile.ImageType != "image/png" || payload.Tile.Width != tile.Bounds().Dx() || payload.Tile.Height != tile.Bounds().Dy() {
		t.Fatalf("tile envelope = %+v bounds = %v", payload.Tile, tile.Bounds())
	}
	if payload.Tile.StartX < 0 || payload.Tile.StartX+payload.Tile.Width > auth.CaptchaImageWidth || payload.Tile.StartY < 0 || payload.Tile.StartY+payload.Tile.Height > auth.CaptchaImageHeight {
		t.Fatalf("tile start %d,%d escapes the challenge canvas", payload.Tile.StartX, payload.Tile.StartY)
	}
	// The hidden target coordinate must not leak: the issue response exposes
	// exactly the client-rendering contract and nothing else.
	var envelope map[string]json.RawMessage
	if err := json.Unmarshal(response.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"captcha_id", "expires_in", "tolerance_px", "challenge", "tile"} {
		if _, ok := envelope[key]; !ok {
			t.Fatalf("issue response missing %q", key)
		}
	}
	if len(envelope) != 5 {
		t.Fatalf("issue response carries unexpected keys: %v", envelopeKeys(envelope))
	}
	assertImageKeys := func(name string, raw json.RawMessage, want []string) {
		t.Helper()
		var nested map[string]json.RawMessage
		if err := json.Unmarshal(raw, &nested); err != nil {
			t.Fatalf("%s is not an object: %v", name, err)
		}
		if len(nested) != len(want) {
			t.Fatalf("%s keys = %v, want exactly %v", name, envelopeKeys(nested), want)
		}
		for _, key := range want {
			if _, ok := nested[key]; !ok {
				t.Fatalf("%s missing %q", name, key)
			}
		}
	}
	assertImageKeys("challenge", envelope["challenge"], []string{"image_base64", "image_type", "width", "height"})
	assertImageKeys("tile", envelope["tile"], []string{"image_base64", "image_type", "width", "height", "start_x", "start_y"})
	if len(store.created) != 1 {
		t.Fatalf("create calls = %d", len(store.created))
	}
	// The issue window must be charged and committed before the challenge is
	// persisted.
	want := []string{"charge_issue", "create"}
	if strings.Join(store.calls, ",") != strings.Join(want, ",") {
		t.Fatalf("issue calls = %v, want %v", store.calls, want)
	}
	if len(store.issueCharges) != 1 {
		t.Fatalf("issue charges = %d", len(store.issueCharges))
	}
	wantKey := registrationRateLimitKey([]byte(strings.Repeat("k", 32)), "captcha:issue:127.0.0.1")
	if !bytes.Equal(store.issueCharges[0].key, wantKey) {
		t.Fatal("issue rate limit key did not use the trusted client IP")
	}
	if !store.issueCharges[0].now.Equal(captchaTestClock()) {
		t.Fatalf("issue charge now = %v", store.issueCharges[0].now)
	}
	params := store.created[0]
	if len(params.AnswerHash) != 32 || len(params.AnswerSalt) != 32 {
		t.Fatalf("persisted hash/salt lengths = %d/%d", len(params.AnswerHash), len(params.AnswerSalt))
	}
	if !params.ExpiresAt.Equal(captchaTestClock().Add(auth.CaptchaTTL)) {
		t.Fatalf("expiry = %v", params.ExpiresAt)
	}
	if !params.Now.Equal(captchaTestClock()) {
		t.Fatalf("now = %v", params.Now)
	}
	// Only hash material is persisted: neither the target coordinate nor the
	// rendered images are stored.
	if bytes.Contains(params.AnswerHash, []byte(fmt.Sprint(captchaTestTargetX))) {
		t.Fatal("persisted hash leaked the target coordinate")
	}
}

// The issue response must never be cacheable by any intermediary: it is
// single-use challenge material bound to one IP window.
func TestCaptchaIssueResponseIsNeverCacheable(t *testing.T) {
	router := newCaptchaRouter(t, &captchaHTTPStore{}, RouterOptions{})
	for range 2 {
		_, response := captchaGET(t, router)
		if response.Code != http.StatusOK {
			t.Fatalf("status = %d", response.Code)
		}
		if cache := response.Header().Get("Cache-Control"); cache != "no-store" {
			t.Fatalf("Cache-Control = %q, want no-store", cache)
		}
		for _, header := range []string{"ETag", "Last-Modified", "Expires"} {
			if value := response.Header().Get(header); value != "" {
				t.Fatalf("%s = %q on captcha material", header, value)
			}
		}
	}
}

func decodeImage(encoded string) (image.Image, string, error) {
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, "", err
	}
	return image.Decode(bytes.NewReader(raw))
}

func envelopeKeys(envelope map[string]json.RawMessage) []string {
	keys := make([]string, 0, len(envelope))
	for key := range envelope {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

func TestCaptchaIssueUsesForwardedIPOnlyFromLoopback(t *testing.T) {
	store := &captchaHTTPStore{}
	router := newCaptchaRouter(t, store, RouterOptions{})
	for _, test := range []struct{ name, remote, forwarded, wantIP string }{
		{name: "loopback proxy", remote: "127.0.0.1:12345", forwarded: "198.51.100.17", wantIP: "198.51.100.17"},
		{name: "direct request ignores spoofed header", remote: "203.0.113.4:12345", forwarded: "198.51.100.17", wantIP: "203.0.113.4"},
		{name: "malformed proxy header falls back", remote: "127.0.0.1:12345", forwarded: "198.51.100.17, 203.0.113.1", wantIP: "127.0.0.1"},
	} {
		t.Run(test.name, func(t *testing.T) {
			store.calls = nil
			store.issueCharges = nil
			store.created = nil
			req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/captcha", nil)
			req.RemoteAddr = test.remote
			req.Header.Set("X-Forwarded-For", test.forwarded)
			recorder := httptest.NewRecorder()
			router.ServeHTTP(recorder, req)
			if recorder.Code != http.StatusOK {
				t.Fatalf("status = %d body = %s", recorder.Code, recorder.Body.String())
			}
			want := registrationRateLimitKey([]byte(strings.Repeat("k", 32)), "captcha:issue:"+test.wantIP)
			if len(store.issueCharges) != 1 || !bytes.Equal(store.issueCharges[0].key, want) {
				t.Fatalf("issue key did not use %q", test.wantIP)
			}
		})
	}
}

func TestCaptchaIssueRateLimitIsStableAndAccurate(t *testing.T) {
	store := &captchaHTTPStore{chargeIssue: func(context.Context, []byte, time.Time) error {
		return domain.RateLimitedError{RetryAfterSeconds: 1859}
	}}
	router := newCaptchaRouter(t, store, RouterOptions{})
	req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/captcha", nil)
	req.RemoteAddr = "127.0.0.1:12345"
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)
	if response.Code != http.StatusTooManyRequests || !strings.Contains(response.Body.String(), "rate_limited") {
		t.Fatalf("response = %d %s", response.Code, response.Body.String())
	}
	if got := response.Header().Get("Retry-After"); got != "1859" {
		t.Fatalf("Retry-After = %q, want the rejected window's remaining seconds", got)
	}
	// A rejected charge must stop the flow before any challenge is issued.
	if len(store.created) != 0 {
		t.Fatal("rate limited issue still persisted a challenge")
	}
}

func TestCaptchaEndpointsAreFailClosedWithoutService(t *testing.T) {
	router := NewRouter(RouterOptions{
		Config: config.Config{Environment: "test", DatabaseTimeout: time.Second, RateLimitRPS: 1000, RateLimitBurst: 1000},
		Store:  &captchaHTTPStore{},
		Tokens: auth.TokenIssuer{
			Issuer: "test-cloud-api", Audience: "test-clients", SessionAudience: "test-agent",
			AccessSecret: bytes.Repeat([]byte("a"), auth.MinimumSecretBytes), SessionSecret: bytes.Repeat([]byte("s"), auth.MinimumSecretBytes),
		},
		Now: captchaTestClock,
	})
	response := httptest.NewRecorder()
	router.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/api/v1/auth/captcha", nil))
	if response.Code != http.StatusServiceUnavailable || !strings.Contains(response.Body.String(), "captcha_not_enabled") {
		t.Fatalf("issue response = %d %s", response.Code, response.Body.String())
	}

	body := `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + uuid.NewString() + `","captcha_x":137}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	response = httptest.NewRecorder()
	router.ServeHTTP(response, req)
	if response.Code != http.StatusServiceUnavailable || !strings.Contains(response.Body.String(), "captcha_not_enabled") {
		t.Fatalf("register response = %d %s", response.Code, response.Body.String())
	}
}

func TestRegisterCreatesFormalUserCredentialTrialAndTokens(t *testing.T) {
	user := domain.User{ID: uuid.New(), Username: "example_user", Email: "user@example.com", Role: string(domain.RoleUser), CreatedAt: captchaTestClock()}
	trial, err := domain.NewTrialEntitlement(uuid.New(), user.ID, captchaTestClock())
	if err != nil {
		t.Fatal(err)
	}
	captchaID := uuid.New()
	store := &captchaHTTPStore{register: func(context.Context, domain.RegisterWithCaptchaParams) (domain.User, domain.Entitlement, error) {
		return user, trial, nil
	}}
	router := newCaptchaRouter(t, store, RouterOptions{})

	// A coordinate inside the tolerance window of the pinned target solves
	// the challenge exactly like a real drag.
	body := `{"username":"Example_User","email":"User@Example.COM","password":"` + captchaTestPassword + `","captcha_id":"` + captchaID.String() + `","captcha_x":` + fmt.Sprint(captchaTestTargetX+auth.CaptchaTolerance) + `}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.RemoteAddr = "127.0.0.1:12345"
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)

	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d body = %s", response.Code, response.Body.String())
	}
	for _, fragment := range []string{`"user"`, `"trial_entitlement"`, `"access_token"`, `"refresh_token"`, `"token_type":"Bearer"`, `"expires_in":900`} {
		if !strings.Contains(response.Body.String(), fragment) {
			t.Fatalf("created response missing %q: %s", fragment, response.Body.String())
		}
	}
	if strings.Contains(response.Body.String(), captchaTestPassword) {
		t.Fatal("created response leaked the password")
	}
	if len(store.registrants) != 1 {
		t.Fatalf("register calls = %d", len(store.registrants))
	}
	// Security contract of the flow: committed window charge, captcha
	// reservation, and only then the expensive registration transaction.
	wantOrder := []string{"charge_register", "reserve", "register"}
	if strings.Join(store.calls, ",") != strings.Join(wantOrder, ",") {
		t.Fatalf("register calls = %v, want %v", store.calls, wantOrder)
	}
	wantKey := registrationRateLimitKey([]byte(strings.Repeat("k", 32)), "captcha:register:127.0.0.1")
	if len(store.registerCharges) != 1 || !bytes.Equal(store.registerCharges[0].key, wantKey) {
		t.Fatal("register rate limit key did not use the trusted client IP")
	}
	if len(store.reservations) != 1 {
		t.Fatalf("reservations = %d", len(store.reservations))
	}
	reservation := store.reservations[0]
	if reservation.CaptchaID != captchaID || reservation.CaptchaX != captchaTestTargetX+auth.CaptchaTolerance {
		t.Fatalf("reservation = %+v", reservation)
	}
	if len(reservation.AnswerPepper) != auth.MinimumSecretBytes || !reservation.Now.Equal(captchaTestClock()) {
		t.Fatalf("reservation pepper/now = %+v", reservation)
	}
	params := store.registrants[0]
	if params.Username != "example_user" || params.Email != "user@example.com" {
		t.Fatalf("identities were not canonicalized: %+v", params)
	}
	if params.CaptchaID != captchaID {
		t.Fatalf("captcha id = %v", params.CaptchaID)
	}
	if params.CaptchaX != captchaTestTargetX+auth.CaptchaTolerance {
		t.Fatalf("submitted coordinate = %d", params.CaptchaX)
	}
	if valid, err := auth.VerifyPassword(params.PasswordHash, captchaTestPassword); err != nil || !valid {
		t.Fatalf("stored password hash does not verify: %v", err)
	}
	if len(store.refreshes) != 1 {
		t.Fatalf("refresh tokens issued = %d", len(store.refreshes))
	}
}

// The expensive registration path (Argon2id hashing and the final
// transaction) must only run behind the committed per trusted client IP
// window charge and the one-time captcha reservation.
func TestRegisterGatesExpensiveWorkBehindChargeAndReservation(t *testing.T) {
	captchaID := uuid.New()
	body := func() string {
		return `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + captchaID.String() + `","captcha_x":` + fmt.Sprint(captchaTestTargetX) + `}`
	}

	t.Run("window charge rejection stops the flow", func(t *testing.T) {
		store := &captchaHTTPStore{chargeRegister: func(context.Context, []byte, time.Time) error {
			return domain.RateLimitedError{RetryAfterSeconds: 3599}
		}}
		router := newCaptchaRouter(t, store, RouterOptions{})
		req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body()))
		req.Header.Set("Content-Type", "application/json")
		req.RemoteAddr = "127.0.0.1:12345"
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		if response.Code != http.StatusTooManyRequests || response.Header().Get("Retry-After") != "3599" {
			t.Fatalf("response = %d retry-after=%q body = %s", response.Code, response.Header().Get("Retry-After"), response.Body.String())
		}
		if len(store.reservations) != 0 || len(store.registrants) != 0 {
			t.Fatal("rate limited register still reserved or registered")
		}
	})

	t.Run("reservation failure stops the flow before hashing", func(t *testing.T) {
		store := &captchaHTTPStore{reserve: func(context.Context, domain.ReserveRegistrationCaptchaParams) error {
			return domain.ErrCaptchaFailed
		}}
		router := newCaptchaRouter(t, store, RouterOptions{})
		req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body()))
		req.Header.Set("Content-Type", "application/json")
		req.RemoteAddr = "127.0.0.1:12345"
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "captcha_failed") {
			t.Fatalf("response = %d %s", response.Code, response.Body.String())
		}
		if len(store.registerCharges) != 1 || len(store.registrants) != 0 {
			t.Fatalf("charges = %d registrants = %d", len(store.registerCharges), len(store.registrants))
		}
	})

	t.Run("reservation storage failure is generic", func(t *testing.T) {
		store := &captchaHTTPStore{reserve: func(context.Context, domain.ReserveRegistrationCaptchaParams) error {
			return errors.New("boom")
		}}
		router := newCaptchaRouter(t, store, RouterOptions{})
		req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body()))
		req.Header.Set("Content-Type", "application/json")
		req.RemoteAddr = "127.0.0.1:12345"
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		if response.Code != http.StatusInternalServerError || !strings.Contains(response.Body.String(), "internal_error") {
			t.Fatalf("response = %d %s", response.Code, response.Body.String())
		}
		if len(store.registrants) != 0 {
			t.Fatal("storage failure still reached the registration transaction")
		}
	})
}

func TestRegisterErrorContractIsGenericAndStable(t *testing.T) {
	captchaID := uuid.New()
	for _, test := range []struct {
		name        string
		registerErr error
		wantStatus  int
		wantBody    string
	}{
		{name: "wrong coordinate", registerErr: domain.ErrCaptchaFailed, wantStatus: http.StatusBadRequest, wantBody: "captcha_failed"},
		{name: "expired or exhausted", registerErr: domain.ErrCaptchaFailed, wantStatus: http.StatusBadRequest, wantBody: "captcha_failed"},
		{name: "rate limited", registerErr: domain.ErrRateLimited, wantStatus: http.StatusTooManyRequests, wantBody: "rate_limited"},
		{name: "conflict", registerErr: domain.ErrConflict, wantStatus: http.StatusConflict, wantBody: "conflict"},
		{name: "storage failure", registerErr: errors.New("boom"), wantStatus: http.StatusInternalServerError, wantBody: "internal_error"},
	} {
		t.Run(test.name, func(t *testing.T) {
			store := &captchaHTTPStore{register: func(context.Context, domain.RegisterWithCaptchaParams) (domain.User, domain.Entitlement, error) {
				return domain.User{}, domain.Entitlement{}, test.registerErr
			}}
			router := newCaptchaRouter(t, store, RouterOptions{})
			body := `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + captchaID.String() + `","captcha_x":137}`
			req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body))
			req.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			router.ServeHTTP(response, req)
			if response.Code != test.wantStatus || !strings.Contains(response.Body.String(), test.wantBody) {
				t.Fatalf("response = %d %s, want %d containing %q", response.Code, response.Body.String(), test.wantStatus, test.wantBody)
			}
			if test.wantStatus == http.StatusTooManyRequests && response.Header().Get("Retry-After") == "" {
				t.Fatal("rate limited response must carry Retry-After")
			}
		})
	}
}

func TestRegisterIsStrictJSONWithCurrentBodyLimit(t *testing.T) {
	captchaID := uuid.New()
	store := &captchaHTTPStore{register: func(context.Context, domain.RegisterWithCaptchaParams) (domain.User, domain.Entitlement, error) {
		return domain.User{ID: uuid.New(), Role: string(domain.RoleUser)}, domain.Entitlement{}, nil
	}}
	router := newCaptchaRouter(t, store, RouterOptions{})
	valid := `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + captchaID.String() + `","captcha_x":137}`
	for _, test := range []struct {
		name string
		body string
	}{
		{name: "unknown field", body: `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + captchaID.String() + `","captcha_x":137,"extra":1}`},
		{name: "missing coordinate", body: `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + captchaID.String() + `"}`},
		{name: "null coordinate", body: `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + captchaID.String() + `","captcha_x":null}`},
		{name: "string coordinate", body: `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + captchaID.String() + `","captcha_x":"137"}`},
		{name: "fractional coordinate", body: `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + captchaID.String() + `","captcha_x":137.5}`},
		{name: "negative coordinate", body: `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + captchaID.String() + `","captcha_x":-1}`},
		{name: "coordinate beyond canvas", body: `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + captchaID.String() + `","captcha_x":` + fmt.Sprint(auth.CaptchaImageWidth+1) + `}`},
		{name: "missing captcha id", body: `{"username":"example_user","email":"user@example.com","password":"password1","captcha_x":137}`},
		{name: "multiple values", body: valid + valid},
		{name: "malformed json", body: valid[:20]},
	} {
		t.Run(test.name, func(t *testing.T) {
			store.calls = nil
			store.registerCharges = nil
			store.reservations = nil
			store.registrants = nil
			req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(test.body))
			req.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			router.ServeHTTP(response, req)
			if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "invalid_request") || len(store.registrants) != 0 {
				t.Fatalf("response = %d %s with %d store calls", response.Code, response.Body.String(), len(store.registrants))
			}
			// Malformed requests must be rejected before any rate window is
			// charged or any captcha work happens.
			if len(store.registerCharges) != 0 || len(store.reservations) != 0 {
				t.Fatalf("invalid request charged the window %d times and reserved %d captchas", len(store.registerCharges), len(store.reservations))
			}
		})
	}

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(`{"username":"x"}`))
	req.Header.Set("Content-Type", "text/plain")
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("non-JSON content type response = %d", response.Code)
	}

	oversized := `{"username":"example_user","email":"user@example.com","password":"` + strings.Repeat("A", int(maxBodyBytes)) + `","captcha_id":"` + captchaID.String() + `","captcha_x":137}`
	req = httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(oversized))
	req.Header.Set("Content-Type", "application/json")
	response = httptest.NewRecorder()
	router.ServeHTTP(response, req)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("oversized body response = %d", response.Code)
	}
}

func TestRegisterRejectsInvalidIdentityAndCaptchaInput(t *testing.T) {
	store := &captchaHTTPStore{register: func(context.Context, domain.RegisterWithCaptchaParams) (domain.User, domain.Entitlement, error) {
		return domain.User{ID: uuid.New(), Role: string(domain.RoleUser)}, domain.Entitlement{}, nil
	}}
	router := newCaptchaRouter(t, store, RouterOptions{})
	for _, test := range []struct {
		name, username, email, password, captchaID string
		captchaX                                   int
	}{
		{name: "bad username", username: "x", email: "user@example.com", password: "password1", captchaID: uuid.NewString(), captchaX: 137},
		{name: "bad email", username: "example_user", email: "not-an-email", password: "password1", captchaID: uuid.NewString(), captchaX: 137},
		{name: "short password", username: "example_user", email: "user@example.com", password: "short", captchaID: uuid.NewString(), captchaX: 137},
		{name: "malformed captcha id", username: "example_user", email: "user@example.com", password: "password1", captchaID: "not-a-uuid", captchaX: 137},
		{name: "non canonical captcha id", username: "example_user", email: "user@example.com", password: "password1", captchaID: strings.ToUpper(uuid.NewString()), captchaX: 137},
		{name: "nil captcha id", username: "example_user", email: "user@example.com", password: "password1", captchaID: uuid.Nil.String(), captchaX: 137},
	} {
		t.Run(test.name, func(t *testing.T) {
			store.calls = nil
			store.registerCharges = nil
			store.reservations = nil
			store.registrants = nil
			body := `{"username":"` + test.username + `","email":"` + test.email + `","password":"` + test.password + `","captcha_id":"` + test.captchaID + `","captcha_x":` + fmt.Sprint(test.captchaX) + `}`
			req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body))
			req.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			router.ServeHTTP(response, req)
			if response.Code != http.StatusBadRequest || len(store.registrants) != 0 {
				t.Fatalf("response = %d %s with %d store calls", response.Code, response.Body.String(), len(store.registrants))
			}
			if len(store.registerCharges) != 0 || len(store.reservations) != 0 {
				t.Fatalf("invalid identity charged the window %d times and reserved %d captchas", len(store.registerCharges), len(store.reservations))
			}
		})
	}
}

func TestCaptchaFlowNeverLogsSensitiveMaterial(t *testing.T) {
	var logs bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&logs, nil))
	user := domain.User{ID: uuid.New(), Username: "example_user", Email: "user@example.com", Role: string(domain.RoleUser), CreatedAt: captchaTestClock()}
	trial, err := domain.NewTrialEntitlement(uuid.New(), user.ID, captchaTestClock())
	if err != nil {
		t.Fatal(err)
	}
	store := &captchaHTTPStore{register: func(context.Context, domain.RegisterWithCaptchaParams) (domain.User, domain.Entitlement, error) {
		return user, trial, nil
	}}
	router := newCaptchaRouter(t, store, RouterOptions{Logger: logger})

	payload, issue := captchaGET(t, router)
	if issue.Code != http.StatusOK || payload.Challenge.ImageBase64 == "" {
		t.Fatal("issue failed before the log assertion")
	}
	body := `{"username":"example_user","email":"user@example.com","password":"` + captchaTestPassword + `","captcha_id":"` + payload.CaptchaID + `","captcha_x":` + fmt.Sprint(captchaTestTargetX) + `}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.RemoteAddr = "127.0.0.1:12345"
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)
	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d body = %s", response.Code, response.Body.String())
	}
	logOutput := logs.String()
	for _, secret := range []string{
		captchaTestPassword, "user@example.com", payload.CaptchaID,
		payload.Challenge.ImageBase64, payload.Tile.ImageBase64,
		payload.Challenge.ImageBase64[:64], `"target"`, `target_x`,
	} {
		if strings.Contains(logOutput, secret) {
			t.Fatalf("log leaked %q: %s", secret[:min(24, len(secret))], logOutput)
		}
	}
}

func TestRegisterUsesForwardedIPOnlyFromLoopback(t *testing.T) {
	for _, test := range []struct{ name, remote, forwarded, wantIP string }{
		{name: "loopback proxy", remote: "127.0.0.1:12345", forwarded: "198.51.100.17", wantIP: "198.51.100.17"},
		{name: "direct request ignores spoofed header", remote: "203.0.113.4:12345", forwarded: "198.51.100.17", wantIP: "203.0.113.4"},
	} {
		t.Run(test.name, func(t *testing.T) {
			store := &captchaHTTPStore{register: func(context.Context, domain.RegisterWithCaptchaParams) (domain.User, domain.Entitlement, error) {
				return domain.User{}, domain.Entitlement{}, domain.ErrCaptchaFailed
			}}
			router := newCaptchaRouter(t, store, RouterOptions{})
			body := `{"username":"example_user","email":"user@example.com","password":"password1","captcha_id":"` + uuid.NewString() + `","captcha_x":137}`
			req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body))
			req.Header.Set("Content-Type", "application/json")
			req.RemoteAddr = test.remote
			req.Header.Set("X-Forwarded-For", test.forwarded)
			response := httptest.NewRecorder()
			router.ServeHTTP(response, req)
			if response.Code != http.StatusBadRequest {
				t.Fatalf("status = %d", response.Code)
			}
			want := registrationRateLimitKey([]byte(strings.Repeat("k", 32)), "captcha:register:"+test.wantIP)
			if len(store.registerCharges) != 1 || !bytes.Equal(store.registerCharges[0].key, want) {
				t.Fatalf("register key did not use %q", test.wantIP)
			}
		})
	}
}

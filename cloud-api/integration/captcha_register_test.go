//go:build integration

package integration_test

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
	httpapi "github.com/dngmeng/cloud-api/internal/http"
	"github.com/dngmeng/cloud-api/internal/migrate"
	"github.com/dngmeng/cloud-api/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/wenlng/go-captcha/v2/base/imagedata"
	"github.com/wenlng/go-captcha/v2/slide"
)

// pinnedSlideCaptcha keeps the hidden target deterministic (137 px) so the
// integration flow can submit a correct drag coordinate exactly like a
// client that solved the rendered challenge; salts, hashes, persistence,
// windows, and image encoding stay on the production code paths.
type pinnedSlideCaptcha struct{}

func (pinnedSlideCaptcha) Generate() (slide.CaptchaData, error) {
	return pinnedSlideData{}, nil
}

type pinnedSlideData struct{}

func (pinnedSlideData) GetData() *slide.Block {
	return &slide.Block{X: 137, Y: 96, Width: 64, Height: 64, DX: 7, DY: 96}
}
func (pinnedSlideData) GetMasterImage() imagedata.JPEGImageData {
	return imagedata.NewJPEGImageData(image.NewRGBA(image.Rect(0, 0, auth.CaptchaImageWidth, auth.CaptchaImageHeight)))
}
func (pinnedSlideData) GetTileImage() imagedata.PNGImageData {
	return imagedata.NewPNGImageData(image.NewRGBA(image.Rect(0, 0, 64, 64)))
}

// This test is intentionally environment-gated by isolatedPostgresTestDSN. It
// never connects unless CLOUD_API_TEST_DATABASE_URL passes the repository's
// 127.0.0.1:15432 safety validation.
func TestCaptchaRegistrationLifecycleIntegration(t *testing.T) {
	url := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: url, Directory: repositoryMigrationDirectory(t), Schema: "public"}); err != nil {
		t.Fatal("apply captcha registration migrations")
	}
	db, err := store.Open(ctx, url)
	if err != nil {
		t.Fatal("open isolated test database")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open fixture pool")
	}
	t.Cleanup(raw.Close)
	t.Cleanup(func() {
		cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		_, _ = raw.Exec(cleanupContext, `DELETE FROM captcha_rate_limits`)
		_, _ = raw.Exec(cleanupContext, `DELETE FROM registration_captchas`)
	})

	service, err := auth.NewCaptchaService(auth.CaptchaService{
		AnswerPepper:       bytes.Repeat([]byte("i"), auth.MinimumSecretBytes),
		RateLimitKeySecret: bytes.Repeat([]byte("j"), auth.MinimumSecretBytes),
		GenerateSlide:      pinnedSlideCaptcha{},
	})
	if err != nil {
		t.Fatal(err)
	}
	router := httpapi.NewRouter(httpapi.RouterOptions{
		Config:   config.Config{Environment: "test", DatabaseTimeout: time.Second, RateLimitRPS: 1000, RateLimitBurst: 1000},
		Database: readyDatabase{},
		Store:    db,
		Tokens: auth.TokenIssuer{
			Issuer: "cloud-api-integration", Audience: "cloud-api-clients", SessionAudience: "translator-agent",
			AccessSecret: bytes.Repeat([]byte("a"), auth.MinimumSecretBytes), SessionSecret: bytes.Repeat([]byte("s"), auth.MinimumSecretBytes),
		},
		Captcha: &service,
	})

	// Every subtest uses a distinct trusted client IP (loopback remote plus
	// X-Forwarded-For) so the per-IP fixed windows stay isolated. Subtests run
	// sequentially, so a plain counter is sufficient.
	ipCounter := 0
	nextIP := func() string {
		ipCounter++
		return fmt.Sprintf("198.51.%d.%d", ipCounter/200, 10+ipCounter%200)
	}

	type issuePayload struct {
		CaptchaID   string `json:"captcha_id"`
		ExpiresIn   int    `json:"expires_in"`
		TolerancePx int    `json:"tolerance_px"`
		Challenge   struct {
			ImageBase64 string `json:"image_base64"`
			ImageType   string `json:"image_type"`
			Width       int    `json:"width"`
			Height      int    `json:"height"`
		} `json:"challenge"`
		Tile struct {
			ImageBase64 string `json:"image_base64"`
			ImageType   string `json:"image_type"`
			Width       int    `json:"width"`
			Height      int    `json:"height"`
			StartX      int    `json:"start_x"`
			StartY      int    `json:"start_y"`
		} `json:"tile"`
	}

	issue := func(t *testing.T, ip string) (string, issuePayload) {
		t.Helper()
		req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/captcha", nil)
		req.RemoteAddr = "127.0.0.1:12345"
		req.Header.Set("X-Forwarded-For", ip)
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		if response.Code != http.StatusOK {
			t.Fatalf("issue status = %d body = %s", response.Code, response.Body.String())
		}
		var payload issuePayload
		if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
			t.Fatalf("issue body = %s", response.Body.String())
		}
		if payload.Challenge.ImageType != "image/jpeg" || payload.Tile.ImageType != "image/png" || payload.ExpiresIn != 300 {
			t.Fatalf("issue payload = %+v", payload)
		}
		if payload.Challenge.Width != auth.CaptchaImageWidth || payload.Challenge.Height != auth.CaptchaImageHeight {
			t.Fatalf("challenge canvas = %dx%d", payload.Challenge.Width, payload.Challenge.Height)
		}
		masterRaw, err := base64.StdEncoding.DecodeString(payload.Challenge.ImageBase64)
		if err != nil {
			t.Fatalf("challenge image is not base64: %v", err)
		}
		master, format, err := image.Decode(bytes.NewReader(masterRaw))
		if err != nil || format != "jpeg" || master.Bounds().Dx() != auth.CaptchaImageWidth {
			t.Fatalf("challenge image = %s %v", format, err)
		}
		tileRaw, err := base64.StdEncoding.DecodeString(payload.Tile.ImageBase64)
		if err != nil {
			t.Fatalf("tile image is not base64: %v", err)
		}
		tile, format, err := image.Decode(bytes.NewReader(tileRaw))
		if err != nil || format != "png" || tile.Bounds().Dx() != payload.Tile.Width || tile.Bounds().Dy() != payload.Tile.Height {
			t.Fatalf("tile image = %s %v", format, err)
		}
		if cache := response.Header().Get("Cache-Control"); cache != "no-store" {
			t.Fatalf("issue Cache-Control = %q, want no-store", cache)
		}
		if response.Header().Get("ETag") != "" || response.Header().Get("Last-Modified") != "" {
			t.Fatal("issue response must not carry validator headers")
		}
		return payload.CaptchaID, payload
	}

	registerIdentity := func(t *testing.T, ip, captchaID, username, email string, x int) (int, string, *httptest.ResponseRecorder) {
		t.Helper()
		body := `{"username":"` + username + `","email":"` + email + `","password":"CaptchaPass1","captcha_id":"` + captchaID + `","captcha_x":` + strconv.Itoa(x) + `}`
		req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.RemoteAddr = "127.0.0.1:12345"
		req.Header.Set("X-Forwarded-For", ip)
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		return response.Code, response.Body.String(), response
	}

	register := func(t *testing.T, ip, captchaID string, x int) (int, string, *httptest.ResponseRecorder) {
		t.Helper()
		username := "captchae" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		return registerIdentity(t, ip, captchaID, username, integrationEmail(), x)
	}

	captchaRows := func(t *testing.T, id string) int {
		t.Helper()
		var count int
		if err := raw.QueryRow(context.Background(), `SELECT count(*) FROM registration_captchas WHERE id=$1`, id).Scan(&count); err != nil {
			t.Fatal(err)
		}
		return count
	}

	captchaAttempts := func(t *testing.T, id string) int {
		t.Helper()
		var attempts int
		if err := raw.QueryRow(context.Background(), `SELECT attempt_count FROM registration_captchas WHERE id=$1`, id).Scan(&attempts); err != nil {
			t.Fatal(err)
		}
		return attempts
	}

	// The fixed one-hour window just started in every subtest that asserts
	// it, so the reported remainder must be almost the full window.
	retryAfterWithinFreshWindow := func(t *testing.T, response *httptest.ResponseRecorder) {
		t.Helper()
		value, err := strconv.Atoi(response.Header().Get("Retry-After"))
		if err != nil || value < 3500 || value > 3600 {
			t.Fatalf("Retry-After = %q, want the one-hour window remainder", response.Header().Get("Retry-After"))
		}
	}

	t.Run("issue_register_success_consumes_captcha_and_issues_tokens", func(t *testing.T) {
		ip := nextIP()
		captchaID, _ := issue(t, ip)
		username := "captchaa" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		email := integrationEmail()
		body := `{"username":"` + username + `","email":"` + email + `","password":"CaptchaPass1","captcha_id":"` + captchaID + `","captcha_x":137}`
		req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.RemoteAddr = "127.0.0.1:12345"
		req.Header.Set("X-Forwarded-For", ip)
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		if response.Code != http.StatusCreated {
			t.Fatalf("register status = %d body = %s", response.Code, response.Body.String())
		}
		var created struct {
			User struct {
				ID       uuid.UUID `json:"id"`
				Username string    `json:"username"`
			} `json:"user"`
			Trial struct {
				Kind      string    `json:"kind"`
				StartsAt  time.Time `json:"starts_at"`
				ExpiresAt time.Time `json:"expires_at"`
			} `json:"trial_entitlement"`
			AccessToken  string `json:"access_token"`
			RefreshToken string `json:"refresh_token"`
		}
		if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil {
			t.Fatalf("created body = %s", response.Body.String())
		}
		t.Cleanup(func() {
			cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cleanupCancel()
			_, _ = raw.Exec(cleanupContext, `DELETE FROM users WHERE id=$1`, created.User.ID)
		})
		if created.User.Username != username || created.Trial.Kind != "trial" || created.Trial.ExpiresAt.Sub(created.Trial.StartsAt) != 72*time.Hour {
			t.Fatalf("created payload = %+v", created)
		}
		if created.AccessToken == "" || created.RefreshToken == "" {
			t.Fatal("created response missing tokens")
		}

		var passwordHash string
		if err := raw.QueryRow(context.Background(), `SELECT password_hash FROM users WHERE id=$1`, created.User.ID).Scan(&passwordHash); err != nil {
			t.Fatal(err)
		}
		if valid, err := auth.VerifyPassword(passwordHash, "CaptchaPass1"); err != nil || !valid {
			t.Fatalf("stored credential does not verify: %v", err)
		}
		var trials int
		if err := raw.QueryRow(context.Background(), `SELECT count(*) FROM entitlements WHERE user_id=$1 AND kind='trial'`, created.User.ID).Scan(&trials); err != nil || trials != 1 {
			t.Fatalf("trial entitlements = %d err = %v", trials, err)
		}
		if captchaRows(t, captchaID) != 0 {
			t.Fatal("successful registration did not consume the captcha")
		}

		me := httptest.NewRequest(http.MethodGet, "/api/v1/users/me", nil)
		me.Header.Set("Authorization", "Bearer "+created.AccessToken)
		me.RemoteAddr = "127.0.0.1:12345"
		meResponse := httptest.NewRecorder()
		router.ServeHTTP(meResponse, me)
		if meResponse.Code != http.StatusOK || !strings.Contains(meResponse.Body.String(), username) {
			t.Fatalf("issued access token failed /users/me: %d %s", meResponse.Code, meResponse.Body.String())
		}

		// Replay of the consumed captcha cannot register again, even with a
		// correct coordinate.
		status, failureBody, _ := register(t, ip, captchaID, 137)
		if status != http.StatusBadRequest || !strings.Contains(failureBody, "captcha_failed") {
			t.Fatalf("replay status = %d body = %s", status, failureBody)
		}
	})

	t.Run("coordinate_tolerance_boundary_passes_inside_fails_outside", func(t *testing.T) {
		// Exactly on the tolerance edge still solves the challenge.
		passIP := nextIP()
		passCaptcha, _ := issue(t, passIP)
		username := "captchat" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		status, body, _ := registerIdentity(t, passIP, passCaptcha, username, integrationEmail(), 137+auth.CaptchaTolerance)
		if status != http.StatusCreated {
			t.Fatalf("edge coordinate status = %d body = %s", status, body)
		}
		var created struct {
			User struct {
				ID uuid.UUID `json:"id"`
			} `json:"user"`
		}
		if err := json.Unmarshal([]byte(body), &created); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cleanupCancel()
			_, _ = raw.Exec(cleanupContext, `DELETE FROM users WHERE id=$1`, created.User.ID)
		})

		// One pixel beyond the tolerance never verifies, from both sides.
		for _, edge := range []int{137 - auth.CaptchaTolerance - 1, 137 + auth.CaptchaTolerance + 1} {
			ip := nextIP()
			captchaID, _ := issue(t, ip)
			status, body, _ := register(t, ip, captchaID, edge)
			if status != http.StatusBadRequest || !strings.Contains(body, "captcha_failed") {
				t.Fatalf("coordinate %d status = %d body = %s", edge, status, body)
			}
			if attempts := captchaAttempts(t, captchaID); attempts != 1 {
				t.Fatalf("coordinate %d burned %d attempts, want 1", edge, attempts)
			}
		}
	})

	t.Run("tampered_persisted_hash_or_salt_never_verifies", func(t *testing.T) {
		// Corrupting the persisted hash or its salt must make even the
		// correct drag coordinate fail: verification depends entirely on the
		// server-side salted hash, never on client-supplied material.
		for _, column := range []string{"answer_hash", "answer_salt"} {
			ip := nextIP()
			captchaID, _ := issue(t, ip)
			if _, err := raw.Exec(context.Background(), `UPDATE registration_captchas SET `+column+`=(('\x' || repeat('00', 32))::bytea) WHERE id=$1`, captchaID); err != nil {
				t.Fatal(err)
			}
			status, body, _ := register(t, ip, captchaID, 137)
			if status != http.StatusBadRequest || !strings.Contains(body, "captcha_failed") {
				t.Fatalf("tampered %s status = %d body = %s", column, status, body)
			}
			if attempts := captchaAttempts(t, captchaID); attempts != 1 {
				t.Fatalf("tampered %s burned %d attempts, want 1", column, attempts)
			}
		}
	})

	t.Run("wrong_answers_exhaust_and_consume_the_challenge", func(t *testing.T) {
		ip := nextIP()
		captchaID, _ := issue(t, ip)
		for attempt := 0; attempt < auth.CaptchaMaxAttempts; attempt++ {
			status, body, _ := register(t, ip, captchaID, 42)
			if status != http.StatusBadRequest || !strings.Contains(body, "captcha_failed") {
				t.Fatalf("attempt %d status = %d body = %s", attempt, status, body)
			}
		}
		if captchaRows(t, captchaID) != 0 {
			t.Fatal("exhausted captcha was not consumed")
		}
		status, body, _ := register(t, ip, captchaID, 137)
		if status != http.StatusBadRequest || !strings.Contains(body, "captcha_failed") {
			t.Fatalf("correct coordinate after exhaustion status = %d body = %s", status, body)
		}
	})

	t.Run("expired_challenge_is_rejected_and_consumed", func(t *testing.T) {
		ip := nextIP()
		draft, err := service.Issue()
		if err != nil {
			t.Fatal(err)
		}
		var captchaID string
		now := time.Now().UTC()
		if err := raw.QueryRow(context.Background(), `INSERT INTO registration_captchas(answer_hash,answer_salt,expires_at,attempt_count,created_at,updated_at)
			VALUES($1,$2,$3,0,$4,$4) RETURNING id`, draft.AnswerHash, draft.AnswerSalt, now.Add(-time.Minute), now).Scan(&captchaID); err != nil {
			t.Fatal(err)
		}
		status, body, _ := register(t, ip, captchaID, draft.TargetX)
		if status != http.StatusBadRequest || !strings.Contains(body, "captcha_failed") {
			t.Fatalf("expired status = %d body = %s", status, body)
		}
		if captchaRows(t, captchaID) != 0 {
			t.Fatal("expired captcha was not consumed")
		}
	})

	t.Run("duplicate_identity_conflict_rolls_back_without_consuming", func(t *testing.T) {
		ip := nextIP()
		username := "captchab" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		email := integrationEmail()
		hash, err := auth.HashPassword("CaptchaPass1")
		if err != nil {
			t.Fatal(err)
		}
		var existing uuid.UUID
		if err := raw.QueryRow(context.Background(), `INSERT INTO users(email,username,password_hash) VALUES($1,$2,$3) RETURNING id`, email, username, hash).Scan(&existing); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cleanupCancel()
			_, _ = raw.Exec(cleanupContext, `DELETE FROM users WHERE id=$1`, existing)
		})

		captchaID, _ := issue(t, ip)
		status, body, _ := registerIdentity(t, ip, captchaID, username, email, 137)
		if status != http.StatusConflict || !strings.Contains(body, "conflict") {
			t.Fatalf("conflict status = %d body = %s", status, body)
		}
		if captchaRows(t, captchaID) != 1 {
			t.Fatal("failed registration transaction consumed the captcha")
		}

		// The same captcha stays verifiable for a non-conflicting registration.
		freeUsername := "captchac" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		freeEmail := integrationEmail()
		status, body, _ = registerIdentity(t, nextIP(), captchaID, freeUsername, freeEmail, 137)
		if status != http.StatusCreated {
			t.Fatalf("reuse status = %d body = %s", status, body)
		}
		var created struct {
			User struct {
				ID uuid.UUID `json:"id"`
			} `json:"user"`
		}
		if err := json.Unmarshal([]byte(body), &created); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cleanupCancel()
			_, _ = raw.Exec(cleanupContext, `DELETE FROM users WHERE id=$1`, created.User.ID)
		})
		if captchaRows(t, captchaID) != 0 {
			t.Fatal("committed success did not consume the reused captcha")
		}
	})

	t.Run("reservation_only_burns_attempts_for_wrong_answers", func(t *testing.T) {
		ip := nextIP()
		captchaID, _ := issue(t, ip)
		// A wrong coordinate reserves nothing: the row stays with one burned
		// attempt and can still be solved afterwards.
		status, body, _ := register(t, ip, captchaID, 42)
		if status != http.StatusBadRequest || !strings.Contains(body, "captcha_failed") {
			t.Fatalf("wrong coordinate status = %d body = %s", status, body)
		}
		if attempts := captchaAttempts(t, captchaID); attempts != 1 {
			t.Fatalf("attempts = %d, want 1", attempts)
		}
		// A failing identity behind a correct reservation consumes nothing;
		// the correct coordinate then finishes the registration.
		username := "captchaz" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		email := integrationEmail()
		hash, err := auth.HashPassword("CaptchaPass1")
		if err != nil {
			t.Fatal(err)
		}
		var existing uuid.UUID
		if err := raw.QueryRow(context.Background(), `INSERT INTO users(email,username,password_hash) VALUES($1,$2,$3) RETURNING id`, email, username, hash).Scan(&existing); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cleanupCancel()
			_, _ = raw.Exec(cleanupContext, `DELETE FROM users WHERE id=$1`, existing)
		})
		status, body, _ = registerIdentity(t, ip, captchaID, username, email, 137)
		if status != http.StatusConflict {
			t.Fatalf("conflict status = %d body = %s", status, body)
		}
		if attempts := captchaAttempts(t, captchaID); attempts != 1 {
			t.Fatalf("conflict behind a correct reservation changed attempts: %d", attempts)
		}
		status, body, _ = register(t, nextIP(), captchaID, 137)
		if status != http.StatusCreated {
			t.Fatalf("post-reservation status = %d body = %s", status, body)
		}
		var created struct {
			User struct {
				ID uuid.UUID `json:"id"`
			} `json:"user"`
		}
		if err := json.Unmarshal([]byte(body), &created); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cleanupCancel()
			_, _ = raw.Exec(cleanupContext, `DELETE FROM users WHERE id=$1`, created.User.ID)
		})
		if captchaRows(t, captchaID) != 0 {
			t.Fatal("committed success did not consume the reserved captcha")
		}
	})

	t.Run("concurrent_registration_verifies_exactly_once", func(t *testing.T) {
		ip := nextIP()
		captchaID, _ := issue(t, ip)
		email := integrationEmail()
		username := "captchad" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		body := `{"username":"` + username + `","email":"` + email + `","password":"CaptchaPass1","captcha_id":"` + captchaID + `","captcha_x":137}`

		statuses := make(chan int, 2)
		for range 2 {
			go func() {
				req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body))
				req.Header.Set("Content-Type", "application/json")
				req.RemoteAddr = "127.0.0.1:12345"
				req.Header.Set("X-Forwarded-For", ip)
				response := httptest.NewRecorder()
				router.ServeHTTP(response, req)
				statuses <- response.Code
			}()
		}
		first, second := <-statuses, <-statuses
		if (first != http.StatusCreated || second != http.StatusBadRequest) && (second != http.StatusCreated || first != http.StatusBadRequest) {
			t.Fatalf("concurrent statuses = %d, %d", first, second)
		}
		var count int
		if err := raw.QueryRow(context.Background(), `SELECT count(*) FROM users WHERE email=$1`, email).Scan(&count); err != nil || count != 1 {
			t.Fatalf("users for %s = %d err = %v", email, count, err)
		}
		t.Cleanup(func() {
			cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cleanupCancel()
			_, _ = raw.Exec(cleanupContext, `DELETE FROM users WHERE email=$1`, email)
		})
	})

	t.Run("conflicts_charge_the_register_window_without_consuming_the_captcha", func(t *testing.T) {
		conflictIP := nextIP()
		username := "captchaf" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		email := integrationEmail()
		hash, err := auth.HashPassword("CaptchaPass1")
		if err != nil {
			t.Fatal(err)
		}
		var existing uuid.UUID
		if err := raw.QueryRow(context.Background(), `INSERT INTO users(email,username,password_hash) VALUES($1,$2,$3) RETURNING id`, email, username, hash).Scan(&existing); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cleanupCancel()
			_, _ = raw.Exec(cleanupContext, `DELETE FROM users WHERE id=$1`, existing)
		})

		// Issue from a separate IP so the conflict loop only exercises the
		// register window of conflictIP.
		captchaID, _ := issue(t, nextIP())

		// Repeated conflicts must keep charging the register window even
		// though every registration transaction rolls back, until the trusted
		// IP is persistently limited. The captcha must stay untouched: not
		// consumed and without burned attempts.
		limited := false
		for attempt := 0; attempt <= auth.CaptchaRegisterIPPerHour+1; attempt++ {
			status, body, limitedResponse := registerIdentity(t, conflictIP, captchaID, username, email, 137)
			switch {
			case status == http.StatusConflict:
			case status == http.StatusTooManyRequests:
				limited = true
				retryAfterWithinFreshWindow(t, limitedResponse)
				if !strings.Contains(body, "rate_limited") {
					t.Fatalf("limited body = %s", body)
				}
			default:
				t.Fatalf("attempt %d unexpected status %d body = %s", attempt, status, body)
			}
			if limited {
				break
			}
		}
		if !limited {
			t.Fatal("conflict probes never hit the per trusted IP register window")
		}
		if captchaRows(t, captchaID) != 1 {
			t.Fatal("conflict probing consumed the captcha")
		}
		if attempts := captchaAttempts(t, captchaID); attempts != 0 {
			t.Fatalf("conflict probing burned captcha attempts: %d", attempts)
		}

		// The unlimited trusted IP of a legitimate client can still consume
		// the same captcha for a non-conflicting registration.
		status, body, _ := register(t, nextIP(), captchaID, 137)
		if status != http.StatusCreated {
			t.Fatalf("post-conflict registration status = %d body = %s", status, body)
		}
		var created struct {
			User struct {
				ID uuid.UUID `json:"id"`
			} `json:"user"`
		}
		if err := json.Unmarshal([]byte(body), &created); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cleanupCancel()
			_, _ = raw.Exec(cleanupContext, `DELETE FROM users WHERE id=$1`, created.User.ID)
		})
		if captchaRows(t, captchaID) != 0 {
			t.Fatal("committed success did not consume the reused captcha")
		}
	})

	t.Run("register_window_rate_limits_per_trusted_ip", func(t *testing.T) {
		ip := nextIP()
		var limited int
		for attempt := 0; attempt <= auth.CaptchaRegisterIPPerHour+1; attempt++ {
			status, _, _ := register(t, ip, uuid.NewString(), 137)
			switch {
			case status == http.StatusBadRequest:
			case status == http.StatusTooManyRequests:
				limited++
			default:
				t.Fatalf("attempt %d unexpected status %d", attempt, status)
			}
		}
		if limited == 0 {
			t.Fatal("register window never rate limited the trusted IP")
		}
	})

	t.Run("issue_window_rate_limits_per_trusted_ip", func(t *testing.T) {
		ip := nextIP()
		for attempt := 1; attempt <= auth.CaptchaIssueIPPerHour; attempt++ {
			issue(t, ip)
		}
		req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/captcha", nil)
		req.RemoteAddr = "127.0.0.1:12345"
		req.Header.Set("X-Forwarded-For", ip)
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		if response.Code != http.StatusTooManyRequests || !strings.Contains(response.Body.String(), "rate_limited") {
			t.Fatalf("issue limit response = %d %s", response.Code, response.Body.String())
		}
		if value, err := strconv.Atoi(response.Header().Get("Retry-After")); err != nil || value < 3500 || value > 3600 {
			t.Fatalf("issue Retry-After = %q, want the one-hour window remainder", response.Header().Get("Retry-After"))
		}
	})
}

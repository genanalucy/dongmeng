//go:build integration

package integration_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
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
)

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

	// The service uses a deterministic challenge so the test can complete the
	// real HTTP flow the way a client that read the SVG would. Salts, hashes,
	// persistence, and windows are the production code paths.
	service, err := auth.NewCaptchaService(auth.CaptchaService{
		AnswerPepper:       bytes.Repeat([]byte("i"), auth.MinimumSecretBytes),
		RateLimitKeySecret: bytes.Repeat([]byte("j"), auth.MinimumSecretBytes),
		GenerateChallenge:  func() (string, error) { return "AB3CD", nil },
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

	issue := func(t *testing.T, ip string) (string, string) {
		t.Helper()
		req := httptest.NewRequest(http.MethodGet, "/api/v1/auth/captcha", nil)
		req.RemoteAddr = "127.0.0.1:12345"
		req.Header.Set("X-Forwarded-For", ip)
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		if response.Code != http.StatusOK {
			t.Fatalf("issue status = %d body = %s", response.Code, response.Body.String())
		}
		var payload struct {
			CaptchaID string  `json:"captcha_id"`
			Image     string  `json:"image"`
			ImageType string  `json:"image_type"`
			ExpiresIn float64 `json:"expires_in"`
		}
		if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
			t.Fatalf("issue body = %s", response.Body.String())
		}
		if payload.ImageType != "image/svg+xml" || !strings.HasPrefix(payload.Image, "<svg") || payload.ExpiresIn != 300 {
			t.Fatalf("issue payload = %+v", payload)
		}
		return payload.CaptchaID, payload.Image
	}

	register := func(t *testing.T, ip, captchaID, answer string) (int, string) {
		t.Helper()
		username := "captchae" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		body := `{"username":"` + username + `","email":"` + integrationEmail() + `","password":"CaptchaPass1","captcha_id":"` + captchaID + `","captcha_answer":"` + answer + `"}`
		req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.RemoteAddr = "127.0.0.1:12345"
		req.Header.Set("X-Forwarded-For", ip)
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		return response.Code, response.Body.String()
	}

	captchaRows := func(t *testing.T, id string) int {
		t.Helper()
		var count int
		if err := raw.QueryRow(context.Background(), `SELECT count(*) FROM registration_captchas WHERE id=$1`, id).Scan(&count); err != nil {
			t.Fatal(err)
		}
		return count
	}

	t.Run("issue_register_success_consumes_captcha_and_issues_tokens", func(t *testing.T) {
		ip := nextIP()
		captchaID, _ := issue(t, ip)
		username := "captchaa" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		email := integrationEmail()
		body := `{"username":"` + username + `","email":"` + email + `","password":"CaptchaPass1","captcha_id":"` + captchaID + `","captcha_answer":"ab3cd"}`
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

		// Replay of the consumed captcha cannot register again.
		status, failureBody := register(t, ip, captchaID, "AB3CD")
		if status != http.StatusBadRequest || !strings.Contains(failureBody, "captcha_failed") {
			t.Fatalf("replay status = %d body = %s", status, failureBody)
		}
	})

	t.Run("wrong_answers_exhaust_and_consume_the_challenge", func(t *testing.T) {
		ip := nextIP()
		captchaID, _ := issue(t, ip)
		for attempt := 0; attempt < auth.CaptchaMaxAttempts; attempt++ {
			status, body := register(t, ip, captchaID, "ZZ9ZZ")
			if status != http.StatusBadRequest || !strings.Contains(body, "captcha_failed") {
				t.Fatalf("attempt %d status = %d body = %s", attempt, status, body)
			}
		}
		if captchaRows(t, captchaID) != 0 {
			t.Fatal("exhausted captcha was not consumed")
		}
		status, body := register(t, ip, captchaID, "AB3CD")
		if status != http.StatusBadRequest || !strings.Contains(body, "captcha_failed") {
			t.Fatalf("correct answer after exhaustion status = %d body = %s", status, body)
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
		status, body := register(t, ip, captchaID, draft.Challenge)
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
		conflictBody := `{"username":"` + username + `","email":"` + email + `","password":"CaptchaPass1","captcha_id":"` + captchaID + `","captcha_answer":"AB3CD"}`
		req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(conflictBody))
		req.Header.Set("Content-Type", "application/json")
		req.RemoteAddr = "127.0.0.1:12345"
		req.Header.Set("X-Forwarded-For", ip)
		response := httptest.NewRecorder()
		router.ServeHTTP(response, req)
		if response.Code != http.StatusConflict || !strings.Contains(response.Body.String(), "conflict") {
			t.Fatalf("conflict status = %d body = %s", response.Code, response.Body.String())
		}
		if captchaRows(t, captchaID) != 1 {
			t.Fatal("failed registration transaction consumed the captcha")
		}

		// The same captcha stays verifiable for a non-conflicting registration.
		freeUsername := "captchac" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		freeEmail := integrationEmail()
		reuseBody := `{"username":"` + freeUsername + `","email":"` + freeEmail + `","password":"CaptchaPass1","captcha_id":"` + captchaID + `","captcha_answer":"AB3CD"}`
		req = httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", strings.NewReader(reuseBody))
		req.Header.Set("Content-Type", "application/json")
		req.RemoteAddr = "127.0.0.1:12345"
		req.Header.Set("X-Forwarded-For", ip)
		response = httptest.NewRecorder()
		router.ServeHTTP(response, req)
		if response.Code != http.StatusCreated {
			t.Fatalf("reuse status = %d body = %s", response.Code, response.Body.String())
		}
		var created struct {
			User struct {
				ID uuid.UUID `json:"id"`
			} `json:"user"`
		}
		if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil {
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

	t.Run("concurrent_registration_verifies_exactly_once", func(t *testing.T) {
		ip := nextIP()
		captchaID, _ := issue(t, ip)
		email := integrationEmail()
		username := "captchad" + strings.ReplaceAll(uuid.NewString()[:8], "-", "x")
		body := `{"username":"` + username + `","email":"` + email + `","password":"CaptchaPass1","captcha_id":"` + captchaID + `","captcha_answer":"AB3CD"}`

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

	t.Run("register_window_rate_limits_per_trusted_ip", func(t *testing.T) {
		ip := nextIP()
		var limited int
		for attempt := 0; attempt <= auth.CaptchaRegisterIPPerHour+1; attempt++ {
			status, _ := register(t, ip, uuid.NewString(), "AB3CD")
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
		if response.Code != http.StatusTooManyRequests || !strings.Contains(response.Body.String(), "rate_limited") || response.Header().Get("Retry-After") == "" {
			t.Fatalf("issue limit response = %d %s", response.Code, response.Body.String())
		}
	})
}

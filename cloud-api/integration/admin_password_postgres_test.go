//go:build integration

package integration_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
	"github.com/dngmeng/cloud-api/internal/domain"
	httpapi "github.com/dngmeng/cloud-api/internal/http"
	"github.com/dngmeng/cloud-api/internal/migrate"
	"github.com/dngmeng/cloud-api/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// TestAdminPasswordChangeIntegration exercises the full password-change
// lifecycle against a fresh PostgreSQL schema: the authenticated mutation,
// immediate old-access-token invalidation through the bumped auth_version,
// refresh-token revocation, audit persistence, and re-login with the new
// credential. It never runs unless CLOUD_API_TEST_DATABASE_URL passes the
// repository's loopback safety gate.
func TestAdminPasswordChangeIntegration(t *testing.T) {
	t.Run("change invalidates sessions atomically and admits the new credential", func(t *testing.T) {
		db, raw, router := openAdminPasswordTestRouter(t)
		ctx := context.Background()
		now := time.Now().UTC()

		currentPassword := "integration-current-password-1"
		newPassword := "integration-replacement-2"
		hash, err := auth.HashPassword(currentPassword)
		if err != nil {
			t.Fatal(err)
		}
		admin, err := db.BootstrapAdmin(ctx, "password_admin_01", "password-admin-01@example.test", hash, now)
		if err != nil {
			t.Fatalf("bootstrap administrator: %v", err)
		}

		// A first login yields the session whose tokens must all die at the
		// password-change commit.
		login := adminPasswordJSON(t, router, http.MethodPost, "/api/v1/auth/login", "", map[string]any{"identifier": "password-admin-01@example.test", "password": currentPassword})
		firstAccess := login["access_token"].(string)
		firstRefresh := login["refresh_token"].(string)
		if code := adminPasswordStatus(t, router, http.MethodGet, "/api/v1/users/me", firstAccess, nil); code != http.StatusOK {
			t.Fatalf("pre-change access token status = %d, want 200", code)
		}

		changeCode := adminPasswordStatus(t, router, http.MethodPost, "/api/v1/admin/password", firstAccess, map[string]any{"current_password": currentPassword, "new_password": newPassword})
		if changeCode != http.StatusNoContent {
			t.Fatalf("password change status = %d, want 204", changeCode)
		}

		// Old access tokens must fail the per-request auth_version check
		// immediately, not at natural expiry.
		if code := adminPasswordStatus(t, router, http.MethodGet, "/api/v1/users/me", firstAccess, nil); code != http.StatusUnauthorized {
			t.Fatalf("post-change old access token status = %d, want 401", code)
		}
		// Old refresh tokens are revoked in the same transaction.
		if code := adminPasswordStatus(t, router, http.MethodPost, "/api/v1/auth/refresh", "", map[string]any{"refresh_token": firstRefresh}); code != http.StatusUnauthorized {
			t.Fatalf("post-change old refresh token status = %d, want 401", code)
		}
		// The old credential no longer authenticates.
		if code := adminPasswordStatus(t, router, http.MethodPost, "/api/v1/auth/login", "", map[string]any{"identifier": "password-admin-01@example.test", "password": currentPassword}); code != http.StatusUnauthorized {
			t.Fatalf("old credential login status = %d, want 401", code)
		}

		// The new credential logs in and the fresh access token works.
		relogin := adminPasswordJSON(t, router, http.MethodPost, "/api/v1/auth/login", "", map[string]any{"identifier": "password-admin-01@example.test", "password": newPassword})
		secondAccess := relogin["access_token"].(string)
		secondRefresh := relogin["refresh_token"].(string)
		if code := adminPasswordStatus(t, router, http.MethodGet, "/api/v1/users/me", secondAccess, nil); code != http.StatusOK {
			t.Fatalf("new access token status = %d, want 200", code)
		}
		if code := adminPasswordStatus(t, router, http.MethodPost, "/api/v1/auth/refresh", "", map[string]any{"refresh_token": secondRefresh}); code != http.StatusOK {
			t.Fatalf("new refresh token status = %d, want 200", code)
		}

		var version int
		var storedHash string
		if err := raw.QueryRow(ctx, `SELECT auth_version,password_hash FROM users WHERE id=$1`, admin.ID).Scan(&version, &storedHash); err != nil {
			t.Fatal("read administrator auth state")
		}
		if version != 1 {
			t.Fatalf("auth_version = %d, want 1 after one password change", version)
		}
		if ok, err := auth.VerifyPassword(storedHash, newPassword); err != nil || !ok {
			t.Fatal("persisted credential is not the new password hash")
		}
		var revoked bool
		if err := raw.QueryRow(ctx, `SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE token_hash=$1`, auth.HashSecret(firstRefresh)).Scan(&revoked); err != nil {
			t.Fatal("read first refresh token")
		}
		if !revoked {
			t.Fatal("first refresh token was not revoked")
		}
		var action, metadata string
		if err := raw.QueryRow(ctx, `SELECT action,metadata::text FROM audit_logs WHERE admin_id=$1 ORDER BY created_at DESC LIMIT 1`, admin.ID).Scan(&action, &metadata); err != nil {
			t.Fatal("read password change audit record")
		}
		if action != "admin.password.change" || metadata != "{}" || strings.Contains(metadata, currentPassword) || strings.Contains(metadata, newPassword) {
			t.Fatalf("audit action=%q metadata=%q, want admin.password.change with empty metadata and no credential material", action, metadata)
		}
	})

	t.Run("store rejects stale hashes, disabled accounts, and repeats", func(t *testing.T) {
		db, raw, _ := openAdminPasswordTestRouter(t)
		ctx := context.Background()
		now := time.Now().UTC()

		firstHash, err := auth.HashPassword("stale-current-password-1")
		if err != nil {
			t.Fatal(err)
		}
		admin, err := db.BootstrapAdmin(ctx, "password_admin_02", "password-admin-02@example.test", firstHash, now)
		if err != nil {
			t.Fatal(err)
		}

		secondHash, err := auth.HashPassword("stale-replacement-2")
		if err != nil {
			t.Fatal(err)
		}
		if err := db.ChangeAdminPassword(ctx, adminPasswordParams(admin.ID, firstHash, secondHash, now)); err != nil {
			t.Fatalf("first change: %v", err)
		}
		// A concurrent writer that verified the pre-change hash must lose
		// the compare-and-swap instead of overwriting the newer credential.
		if err := db.ChangeAdminPassword(ctx, adminPasswordParams(admin.ID, firstHash, secondHash, now)); !errors.Is(err, domain.ErrConflict) {
			t.Fatalf("stale CAS error = %v, want ErrConflict", err)
		}
		// The parameter contract itself refuses identical hashes.
		if err := db.ChangeAdminPassword(ctx, adminPasswordParams(admin.ID, secondHash, secondHash, now)); !errors.Is(err, domain.ErrInvalid) {
			t.Fatalf("identical hash error = %v, want ErrInvalid", err)
		}
		// Reusing the verified current hash with a third credential succeeds.
		thirdHash, err := auth.HashPassword("stale-replacement-3")
		if err != nil {
			t.Fatal(err)
		}
		if err := db.ChangeAdminPassword(ctx, adminPasswordParams(admin.ID, secondHash, thirdHash, now)); err != nil {
			t.Fatalf("sequential change with current hash: %v", err)
		}

		if _, err := raw.Exec(ctx, `UPDATE users SET disabled_at=$2 WHERE id=$1`, admin.ID, now); err != nil {
			t.Fatal("disable administrator fixture")
		}
		fourthHash, err := auth.HashPassword("stale-replacement-4")
		if err != nil {
			t.Fatal(err)
		}
		if err := db.ChangeAdminPassword(ctx, adminPasswordParams(admin.ID, thirdHash, fourthHash, now)); !errors.Is(err, domain.ErrForbidden) {
			t.Fatalf("disabled administrator error = %v, want ErrForbidden", err)
		}

		missingHash, _ := auth.HashPassword("missing-account-password")
		if err := db.ChangeAdminPassword(ctx, adminPasswordParams(uuid.New(), missingHash, fourthHash, now)); !errors.Is(err, domain.ErrForbidden) {
			t.Fatalf("non-administrator error = %v, want ErrForbidden", err)
		}
		var version int
		if err := raw.QueryRow(ctx, `SELECT auth_version FROM users WHERE id=$1`, admin.ID).Scan(&version); err != nil {
			t.Fatal("read final auth version")
		}
		if version != 2 {
			t.Fatalf("auth_version = %d, want 2 (failed attempts must not bump)", version)
		}
	})

	t.Run("user auth state reports enabled and version together", func(t *testing.T) {
		db, raw, _ := openAdminPasswordTestRouter(t)
		ctx := context.Background()
		now := time.Now().UTC()
		hash, err := auth.HashPassword("state-current-password-1")
		if err != nil {
			t.Fatal(err)
		}
		admin, err := db.BootstrapAdmin(ctx, "password_admin_03", "password-admin-03@example.test", hash, now)
		if err != nil {
			t.Fatal(err)
		}

		state, err := db.UserAuthState(ctx, admin.ID)
		if err != nil || !state.Enabled || state.AuthVersion != 0 {
			t.Fatalf("initial state = %+v err = %v, want enabled version 0", state, err)
		}
		replacement, err := auth.HashPassword("state-replacement-2")
		if err != nil {
			t.Fatal(err)
		}
		if err := db.ChangeAdminPassword(ctx, adminPasswordParams(admin.ID, hash, replacement, now)); err != nil {
			t.Fatal(err)
		}
		state, err = db.UserAuthState(ctx, admin.ID)
		if err != nil || !state.Enabled || state.AuthVersion != 1 {
			t.Fatalf("post-change state = %+v err = %v, want enabled version 1", state, err)
		}
		if _, err := raw.Exec(ctx, `UPDATE users SET disabled_at=$2 WHERE id=$1`, admin.ID, now); err != nil {
			t.Fatal("disable administrator fixture")
		}
		state, err = db.UserAuthState(ctx, admin.ID)
		if err != nil || state.Enabled {
			t.Fatalf("disabled state = %+v err = %v, want disabled", state, err)
		}
		if _, err := db.UserAuthState(ctx, uuid.New()); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("missing account error = %v, want ErrNotFound", err)
		}
		if _, err := db.UserPasswordHash(ctx, uuid.New()); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("missing hash error = %v, want ErrNotFound", err)
		}
	})
}

// TestAdminAuthVersionMigrationIsMinimal verifies migration 000014 adds the
// users.auth_version column without touching runtime-role privileges: the
// table already carries the runtime grants, and a new column inherits them.
func TestAdminAuthVersionMigrationIsMinimal(t *testing.T) {
	directory := repositoryMigrationDirectory(t)
	up, err := os.ReadFile(filepath.Join(directory, "000014_admin_auth_version.up.sql"))
	if err != nil {
		t.Fatal("read auth version migration")
	}
	upSQL := string(up)
	for _, fragment := range []string{"ALTER TABLE users ADD COLUMN auth_version integer NOT NULL DEFAULT 0"} {
		if !strings.Contains(upSQL, fragment) {
			t.Fatalf("migration missing %q", fragment)
		}
	}
	for _, forbidden := range []string{"GRANT", "REVOKE", "CREATE", "DROP"} {
		for _, line := range strings.Split(upSQL, "\n") {
			trimmed := strings.TrimSpace(line)
			if trimmed == "" || strings.HasPrefix(trimmed, "--") {
				continue
			}
			if strings.Contains(strings.ToUpper(trimmed), forbidden+" ") || strings.Contains(strings.ToUpper(trimmed), forbidden+"\t") {
				t.Fatalf("migration must not execute %q statements; got %q", forbidden, trimmed)
			}
		}
	}
	down, err := os.ReadFile(filepath.Join(directory, "000014_admin_auth_version.down.sql"))
	if err != nil {
		t.Fatal("read auth version down migration")
	}
	if !strings.Contains(string(down), "ALTER TABLE users DROP COLUMN auth_version") {
		t.Fatal("down migration must drop the auth_version column")
	}
}

func adminPasswordParams(adminID uuid.UUID, currentHash, newHash string, now time.Time) domain.AdminPasswordChangeParams {
	return domain.AdminPasswordChangeParams{AdminID: adminID, CurrentHash: currentHash, NewHash: newHash, Now: now}
}

func openAdminPasswordTestRouter(t *testing.T) (*store.Postgres, *pgxpool.Pool, http.Handler) {
	t.Helper()
	baseDSN := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	basePool, err := pgxpool.New(ctx, baseDSN)
	if err != nil {
		t.Fatal("open isolated schema fixture pool")
	}
	t.Cleanup(basePool.Close)
	schema := "admin_password_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if _, err := basePool.Exec(ctx, `CREATE SCHEMA `+schema); err != nil {
		t.Fatal("create admin password schema")
	}
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = basePool.Exec(cleanup, `DROP SCHEMA IF EXISTS `+schema+` CASCADE`)
	})
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: baseDSN, Directory: repositoryMigrationDirectory(t), Schema: schema}); err != nil {
		t.Fatal("apply admin password migrations")
	}
	db, err := store.Open(ctx, postgresSchemaDSN(t, baseDSN, schema))
	if err != nil {
		t.Fatal("open admin password store")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, postgresSchemaDSN(t, baseDSN, schema))
	if err != nil {
		t.Fatal("open admin password raw pool")
	}
	t.Cleanup(raw.Close)
	router := httpapi.NewRouter(httpapi.RouterOptions{
		Config: config.Config{
			Environment:     "test",
			DatabaseTimeout: 5 * time.Second,
			RateLimitRPS:    1000,
			RateLimitBurst:  1000,
		},
		Database: readyDatabase{},
		Store:    db,
		Tokens: auth.TokenIssuer{
			Issuer:          "cloud-api-integration",
			Audience:        "cloud-api-clients",
			SessionAudience: "translator-agent",
			AccessSecret:    bytes.Repeat([]byte("a"), auth.MinimumSecretBytes),
			SessionSecret:   bytes.Repeat([]byte("s"), auth.MinimumSecretBytes),
		},
		Logger:  slog.New(slog.NewTextHandler(io.Discard, nil)),
		Version: "admin-password-integration-test",
		Now:     time.Now,
	})
	return db, raw, router
}

func adminPasswordDo(t *testing.T, router http.Handler, method, path, token string, body map[string]any) *httptest.ResponseRecorder {
	t.Helper()
	var payload io.Reader
	if body != nil {
		encoded, err := jsonMarshal(body)
		if err != nil {
			t.Fatalf("marshal %s %s body: %v", method, path, err)
		}
		payload = bytes.NewReader(encoded)
	}
	req := httptest.NewRequest(method, path, payload)
	req.RemoteAddr = "127.0.0.1:12345"
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)
	return response
}

func jsonUnmarshalBody(response *httptest.ResponseRecorder, target any) error {
	return json.NewDecoder(strings.NewReader(response.Body.String())).Decode(target)
}

func jsonMarshal(value any) ([]byte, error) {
	return json.Marshal(value)
}

func adminPasswordStatus(t *testing.T, router http.Handler, method, path, token string, body map[string]any) int {
	t.Helper()
	response := adminPasswordDo(t, router, method, path, token, body)
	return response.Code
}

func adminPasswordJSON(t *testing.T, router http.Handler, method, path, token string, body map[string]any) map[string]any {
	t.Helper()
	response := adminPasswordDo(t, router, method, path, token, body)
	if response.Code != http.StatusOK {
		t.Fatalf("%s %s status = %d body = %s", method, path, response.Code, response.Body.String())
	}
	decoded := map[string]any{}
	if err := jsonUnmarshalBody(response, &decoded); err != nil {
		t.Fatalf("%s %s body = %s", method, path, response.Body.String())
	}
	return decoded
}

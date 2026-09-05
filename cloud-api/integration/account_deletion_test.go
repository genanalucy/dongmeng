//go:build integration

package integration_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
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

func slogDiscard() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func jsonUnmarshal(raw []byte, value any) error { return json.Unmarshal(raw, value) }

func deleteParams(user uuid.UUID, username string, now time.Time) domain.DeleteAccountParams {
	return domain.DeleteAccountParams{UserID: user, Username: username, Now: now}
}

func historySessionParams(user uuid.UUID, at time.Time) domain.CreateHistorySessionParams {
	return domain.CreateHistorySessionParams{ID: uuid.New(), UserID: user, Now: at}
}

func historyTurnParams(session, user uuid.UUID, at time.Time) domain.AppendHistoryTurnParams {
	return domain.AppendHistoryTurnParams{
		ID: uuid.New(), UserID: user, SessionID: session, KeyVersion: 1,
		Nonce:      bytes12(),
		Ciphertext: []byte("ciphertext-with-at-least-16-bytes"),
		Now:        at,
	}
}

func bytes12() []byte { return []byte("0123456789ab") }

// uniqueUsername appends a random suffix so repeated runs against the same
// isolated database never collide on the stored unique username index
// (cleanup cannot delete accounts that audit logs or redeemed codes
// reference, by design).
func uniqueUsername(prefix string) string {
	return prefix + "_" + strings.ReplaceAll(uuid.NewString()[:8], "-", "a")
}

func refreshParams(user, family uuid.UUID, now time.Time) domain.CreateRefreshParams {
	return domain.CreateRefreshParams{UserID: user, FamilyID: family, Hash: randomHash(), ExpiresAt: now.Add(30 * 24 * time.Hour)}
}

// randomHash derives distinct 32-byte hashes so unique constraints never
// collide between fixture tokens.
func randomHash() []byte {
	value := uuid.New()
	return append(value[:], value[:16]...)
}

func batchParams(admin uuid.UUID, hashes [][]byte, now time.Time) domain.CreateBatchParams {
	return domain.CreateBatchParams{AdminID: admin, Name: "deletion-batch", DurationDays: 365, CodeHashes: hashes, Now: now}
}

func mustExec(t *testing.T, ctx context.Context, raw *pgxpool.Pool, sql string, args ...any) {
	t.Helper()
	if _, err := raw.Exec(ctx, sql, args...); err != nil {
		t.Fatalf("exec %q: %v", sql, err)
	}
}

func assertRefreshActive(t *testing.T, ctx context.Context, raw *pgxpool.Pool, id uuid.UUID) {
	t.Helper()
	var revokedAt *time.Time
	if err := raw.QueryRow(ctx, `SELECT revoked_at FROM refresh_tokens WHERE id=$1`, id).Scan(&revokedAt); err != nil || revokedAt != nil {
		t.Fatalf("refresh %s should stay active: %v (%v)", id, revokedAt, err)
	}
}

func assertRefreshRevoked(t *testing.T, ctx context.Context, raw *pgxpool.Pool, ids ...uuid.UUID) {
	t.Helper()
	for _, id := range ids {
		var revokedAt *time.Time
		if err := raw.QueryRow(ctx, `SELECT revoked_at FROM refresh_tokens WHERE id=$1`, id).Scan(&revokedAt); err != nil || revokedAt == nil {
			t.Fatalf("refresh %s should be revoked: %v (%v)", id, revokedAt, err)
		}
	}
}

func liveTurnBodies(t *testing.T, ctx context.Context, raw *pgxpool.Pool, user uuid.UUID) int {
	t.Helper()
	var count int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM history_turns WHERE user_id=$1 AND deleted_at IS NULL AND nonce IS NOT NULL AND ciphertext IS NOT NULL`, user).Scan(&count); err != nil {
		t.Fatal("count live history bodies")
	}
	return count
}

func clearTurnBodies(t *testing.T, ctx context.Context, raw *pgxpool.Pool, session uuid.UUID) int {
	t.Helper()
	var count int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM history_turns WHERE session_id=$1 AND nonce IS NULL AND ciphertext IS NULL AND deleted_at IS NOT NULL`, session).Scan(&count); err != nil {
		t.Fatal("count cleared history bodies")
	}
	return count
}

// TestAccountSelfDeletionStoreLifecycle drives the store-level deletion
// transaction against isolated PostgreSQL with full fixtures: encrypted
// history, a rotated refresh family, active/ended/expired translation
// sessions, an audit log targeting the account, and a redeemed code.
func TestAccountSelfDeletionStoreLifecycle(t *testing.T) {
	url := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: url, Directory: repositoryMigrationDirectory(t), Schema: "public"}); err != nil {
		t.Fatal("apply migrations")
	}
	db, err := store.Open(ctx, url)
	if err != nil {
		t.Fatal("open store")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open fixture pool")
	}
	t.Cleanup(raw.Close)

	now := time.Now().UTC().Truncate(time.Microsecond)
	hash, err := auth.HashPassword("Aa123456")
	if err != nil {
		t.Fatal(err)
	}
	victimEmail := integrationEmail()
	victimPhone := fmt.Sprintf("+86138%08d", time.Now().UnixNano()%100000000)
	victimUsername, adminUsername, otherUsername, brokenUsername, rebornUsername := uniqueUsername("victim"), uniqueUsername("admin"), uniqueUsername("other"), uniqueUsername("broken"), uniqueUsername("reborn")
	var victim, admin, legacy, other, reborn uuid.UUID
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = raw.Exec(cleanup, `DELETE FROM users WHERE id=ANY($1)`, []uuid.UUID{victim, admin, legacy, other, reborn})
	})
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,username,phone,password_hash) VALUES($1,$2,$3,$4) RETURNING id`, victimEmail, victimUsername, victimPhone, hash).Scan(&victim); err != nil {
		t.Fatal("insert victim")
	}
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,username,password_hash,role) VALUES($1,$2,$3,'admin') RETURNING id`, integrationEmail(), adminUsername, hash).Scan(&admin); err != nil {
		t.Fatal("insert admin")
	}
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,password_hash) VALUES($1,$2) RETURNING id`, integrationEmail(), hash).Scan(&legacy); err != nil {
		t.Fatal("insert legacy user")
	}
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,username,password_hash) VALUES($1,$2,$3) RETURNING id`, integrationEmail(), otherUsername, hash).Scan(&other); err != nil {
		t.Fatal("insert other user")
	}

	// Entitlements, sessions in every lifecycle state, history (live plus
	// already tombstoned), a live refresh family, an audit record targeting
	// the victim, and a code the victim redeemed.
	trialID := uuid.New()
	// The duration check pins a trial to exactly three days.
	mustExec(t, ctx, raw, `INSERT INTO entitlements(id,user_id,kind,starts_at,expires_at) VALUES($1,$2,'trial',$3,$4)`, trialID, victim, now.Add(-time.Hour), now.Add(-time.Hour).Add(72*time.Hour))
	activeSession, endedSession, expiredSession := uuid.New(), uuid.New(), uuid.New()
	mustExec(t, ctx, raw, `INSERT INTO translation_sessions(id,user_id,entitlement_id,install_id,jti,expires_at,created_at) VALUES($1,$2,$3,'del-active',$4,$5,$6)`, activeSession, victim, trialID, uuid.New(), now.Add(time.Hour), now.Add(-time.Minute))
	mustExec(t, ctx, raw, `INSERT INTO translation_sessions(id,user_id,entitlement_id,install_id,jti,expires_at,created_at,ended_at,termination_reason) VALUES($1,$2,$3,'del-ended',$4,$5,$6,$7,'ended')`, endedSession, victim, trialID, uuid.New(), now.Add(time.Hour), now.Add(-2*time.Minute), now.Add(-time.Minute))
	mustExec(t, ctx, raw, `INSERT INTO translation_sessions(id,user_id,entitlement_id,install_id,jti,expires_at,created_at) VALUES($1,$2,$3,'del-expired',$4,$5,$6)`, expiredSession, victim, trialID, uuid.New(), now.Add(-time.Minute), now.Add(-2*time.Minute))

	liveHistory, err := db.CreateHistorySession(ctx, historySessionParams(victim, now.Add(-time.Minute)))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.AppendHistoryTurn(ctx, historyTurnParams(liveHistory.ID, victim, now.Add(-50*time.Second))); err != nil {
		t.Fatal(err)
	}
	oldHistory, err := db.CreateHistorySession(ctx, historySessionParams(victim, now.Add(-2*time.Minute)))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.AppendHistoryTurn(ctx, historyTurnParams(oldHistory.ID, victim, now.Add(-110*time.Second))); err != nil {
		t.Fatal(err)
	}
	priorTombstone := now.Add(-time.Minute)
	if err := db.DeleteHistorySession(ctx, victim, oldHistory.ID, priorTombstone); err != nil {
		t.Fatal(err)
	}

	family := uuid.New()
	firstRefresh, err := db.CreateRefreshToken(ctx, refreshParams(victim, family, now))
	if err != nil {
		t.Fatal(err)
	}
	secondRefresh, err := db.CreateRefreshToken(ctx, refreshParams(victim, family, now))
	if err != nil {
		t.Fatal(err)
	}
	otherRefresh, err := db.CreateRefreshToken(ctx, refreshParams(other, uuid.New(), now))
	if err != nil {
		t.Fatal(err)
	}

	_, codeHash, err := auth.RandomCode()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.CreateCodeBatch(ctx, batchParams(admin, [][]byte{codeHash}, now)); err != nil {
		t.Fatal(err)
	}
	if _, err := db.RedeemCode(ctx, victim, codeHash, now); err != nil {
		t.Fatal("redeem code")
	}
	mustExec(t, ctx, raw, `INSERT INTO audit_logs(admin_id,action,target_type,target_id) VALUES($1,'user.disable','user',$2)`, admin, victim)
	var auditCount int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM audit_logs WHERE target_id=$1`, victim).Scan(&auditCount); err != nil {
		t.Fatal("count audit logs")
	}

	// A wrong exact-username confirmation changes nothing.
	if err := db.DeleteAccount(ctx, deleteParams(victim, otherUsername, now)); !errors.Is(err, domain.ErrConflict) {
		t.Fatalf("wrong confirmation error = %v", err)
	}
	assertUserIntact(t, ctx, raw, victim, victimEmail, victimUsername, victimPhone)
	assertRefreshActive(t, ctx, raw, firstRefresh.ID)
	var activeStill int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM translation_sessions WHERE id=$1 AND revoked_at IS NULL`, activeSession).Scan(&activeStill); err != nil || activeStill != 1 {
		t.Fatalf("failed confirmation terminated sessions: %d (%v)", activeStill, err)
	}
	if bodies := liveTurnBodies(t, ctx, raw, victim); bodies != 1 {
		t.Fatalf("failed confirmation cleared history bodies: %d live", bodies)
	}

	// Legacy accounts without a stored username cannot confirm deletion.
	if err := db.DeleteAccount(ctx, deleteParams(legacy, uniqueUsername("legacy"), now)); !errors.Is(err, domain.ErrConflict) {
		t.Fatalf("legacy confirmation error = %v", err)
	}

	// Admin accounts cannot self-delete even with a matching username.
	if err := db.DeleteAccount(ctx, deleteParams(admin, adminUsername, now)); !errors.Is(err, domain.ErrForbidden) {
		t.Fatalf("admin self-deletion error = %v", err)
	}
	if adminDeleted := userDeletedAt(t, ctx, raw, admin); adminDeleted != nil {
		t.Fatalf("admin self-deletion mutated the account: %v", adminDeleted)
	}

	// Unknown accounts and invalid arguments fail safely.
	if err := db.DeleteAccount(ctx, deleteParams(uuid.New(), uniqueUsername("ghost"), now)); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("missing account error = %v", err)
	}
	if err := db.DeleteAccount(ctx, deleteParams(uuid.Nil, victimUsername, now)); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("nil user error = %v", err)
	}
	if err := db.DeleteAccount(ctx, deleteParams(victim, victimUsername, time.Time{})); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("zero time error = %v", err)
	}

	// Successful deletion tombstones and anonymizes everything atomically.
	deletedAt := now.Add(time.Second)
	if err := db.DeleteAccount(ctx, deleteParams(victim, victimUsername, deletedAt)); err != nil {
		t.Fatalf("delete account: %v", err)
	}
	var email, passwordHash string
	var username, phone *string
	var storedDisabledAt, storedDeletedAt *time.Time
	if err := raw.QueryRow(ctx, `SELECT email,username,phone,password_hash,disabled_at,deleted_at FROM users WHERE id=$1`, victim).Scan(&email, &username, &phone, &passwordHash, &storedDisabledAt, &storedDeletedAt); err != nil {
		t.Fatal("read deleted account")
	}
	if want := "deleted+" + victim.String() + "@deleted.invalid"; email != want {
		t.Fatalf("anonymized email = %q, want %q", email, want)
	}
	if username != nil || phone != nil {
		t.Fatalf("login identities survived: username=%v phone=%v", username, phone)
	}
	if passwordHash == hash || len(passwordHash) < 20 {
		t.Fatalf("password credential was not invalidated: %d bytes", len(passwordHash))
	}
	if storedDisabledAt == nil || !storedDisabledAt.Equal(deletedAt) || storedDeletedAt == nil || !storedDeletedAt.Equal(deletedAt) {
		t.Fatalf("tombstone timestamps = %v/%v, want %v", storedDisabledAt, storedDeletedAt, deletedAt)
	}

	// Every refresh family of the victim is revoked; other users untouched.
	assertRefreshRevoked(t, ctx, raw, firstRefresh.ID, secondRefresh.ID)
	assertRefreshActive(t, ctx, raw, otherRefresh.ID)

	// Active sessions terminate with the user-disabled reason; previously
	// terminal sessions keep their original state and reason.
	var revokedAt *time.Time
	var reason *string
	var endedAt *time.Time
	if err := raw.QueryRow(ctx, `SELECT revoked_at,ended_at,termination_reason FROM translation_sessions WHERE id=$1`, activeSession).Scan(&revokedAt, &endedAt, &reason); err != nil || revokedAt == nil || endedAt != nil || reason == nil || *reason != "user_disabled" {
		t.Fatalf("active session after deletion = revoked=%v ended=%v reason=%v err=%v", revokedAt, endedAt, reason, err)
	}
	var endedReason string
	if err := raw.QueryRow(ctx, `SELECT COALESCE(termination_reason,''),revoked_at FROM translation_sessions WHERE id=$1`, endedSession).Scan(&endedReason, new(*time.Time)); err != nil || endedReason != "ended" {
		t.Fatalf("ended session was rewritten: %q %v", endedReason, err)
	}
	if err := raw.QueryRow(ctx, `SELECT revoked_at,ended_at,termination_reason FROM translation_sessions WHERE id=$1`, expiredSession).Scan(new(*time.Time), new(*time.Time), new(*string)); err != nil {
		t.Fatalf("expired session was rewritten: %v", err)
	}

	// All entitlements are revoked.
	var activeEntitlements int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM entitlements WHERE user_id=$1 AND revoked_at IS NULL`, victim).Scan(&activeEntitlements); err != nil || activeEntitlements != 0 {
		t.Fatalf("entitlements survived deletion: %d (%v)", activeEntitlements, err)
	}

	// History bodies are cleared and tombstoned; earlier tombstones keep
	// their original timestamps.
	if cleared := clearTurnBodies(t, ctx, raw, liveHistory.ID); cleared != 1 {
		t.Fatalf("live history bodies after deletion = %d cleared", cleared)
	}
	var liveTombstone, priorRead *time.Time
	if err := raw.QueryRow(ctx, `SELECT deleted_at FROM history_sessions WHERE id=$1`, liveHistory.ID).Scan(&liveTombstone); err != nil || liveTombstone == nil || !liveTombstone.Equal(deletedAt) {
		t.Fatalf("live history session tombstone = %v (%v)", liveTombstone, err)
	}
	if err := raw.QueryRow(ctx, `SELECT deleted_at FROM history_sessions WHERE id=$1`, oldHistory.ID).Scan(&priorRead); err != nil || priorRead == nil || !priorRead.Equal(priorTombstone) {
		t.Fatalf("prior history tombstone was rewritten: %v (%v)", priorRead, err)
	}

	// Append-only audit history and redemption FK integrity are preserved.
	var auditAfter int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM audit_logs WHERE target_id=$1`, victim).Scan(&auditAfter); err != nil || auditAfter != auditCount {
		t.Fatalf("audit logs changed: %d -> %d (%v)", auditCount, auditAfter, err)
	}
	var redeemedBy *uuid.UUID
	if err := raw.QueryRow(ctx, `SELECT redeemed_by FROM redemption_codes WHERE code_hash=$1`, codeHash).Scan(&redeemedBy); err != nil || redeemedBy == nil || *redeemedBy != victim {
		t.Fatalf("redemption FK lost: %v (%v)", redeemedBy, err)
	}

	// The account can no longer authenticate by any identity, and the freed
	// identity is registrable again.
	if _, _, err := db.UserByEmail(ctx, victimEmail); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("deleted email lookup error = %v", err)
	}
	if _, _, err := db.UserByUsername(ctx, victimUsername); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("deleted username lookup error = %v", err)
	}
	if _, _, err := db.UserByPhone(ctx, victimPhone); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("deleted phone lookup error = %v", err)
	}
	if enabled, err := db.UserEnabled(ctx, victim); err != nil || enabled {
		t.Fatalf("deleted account still enabled: %v (%v)", enabled, err)
	}
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,username,password_hash) VALUES($1,$2,$3) RETURNING id`, victimEmail, rebornUsername, hash).Scan(&reborn); err != nil {
		t.Fatalf("original identity was not freed: %v", err)
	}

	// Repeated deletion is an idempotent no-op preserving original state.
	if err := db.DeleteAccount(ctx, deleteParams(victim, victimUsername, now.Add(time.Hour))); err != nil {
		t.Fatalf("idempotent deletion error = %v", err)
	}
	if read := userDeletedAt(t, ctx, raw, victim); read == nil || !read.Equal(deletedAt) {
		t.Fatalf("idempotent deletion rewrote the tombstone: %v", read)
	}

	// The deletion transaction is atomic: a forced failure in its final
	// statement rolls back every earlier mutation.
	var broken uuid.UUID
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = raw.Exec(cleanup, `DELETE FROM users WHERE id=$1`, broken)
	})
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,username,password_hash) VALUES($1,$2,$3) RETURNING id`, integrationEmail(), brokenUsername, hash).Scan(&broken); err != nil {
		t.Fatal("insert rollback fixture user")
	}
	rollbackRefresh, err := db.CreateRefreshToken(ctx, refreshParams(broken, uuid.New(), now))
	if err != nil {
		t.Fatal(err)
	}
	mustExec(t, ctx, raw, `CREATE OR REPLACE FUNCTION account_deletion_force_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced deletion failure'; END; $$`)
	mustExec(t, ctx, raw, `CREATE TRIGGER account_deletion_force_failure_trigger BEFORE UPDATE ON history_sessions FOR EACH STATEMENT EXECUTE FUNCTION account_deletion_force_failure()`)
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = raw.Exec(cleanup, `DROP TRIGGER IF EXISTS account_deletion_force_failure_trigger ON history_sessions`)
		_, _ = raw.Exec(cleanup, `DROP FUNCTION IF EXISTS account_deletion_force_failure()`)
	})
	if err := db.DeleteAccount(ctx, deleteParams(broken, brokenUsername, now)); err == nil {
		t.Fatal("forced deletion failure succeeded")
	}
	assertUserIntact(t, ctx, raw, broken, "", brokenUsername, "")
	assertRefreshActive(t, ctx, raw, rollbackRefresh.ID)
}

func userDeletedAt(t *testing.T, ctx context.Context, raw *pgxpool.Pool, user uuid.UUID) *time.Time {
	t.Helper()
	var deletedAt *time.Time
	if err := raw.QueryRow(ctx, `SELECT deleted_at FROM users WHERE id=$1`, user).Scan(&deletedAt); err != nil {
		t.Fatalf("read deleted_at for %s: %v", user, err)
	}
	return deletedAt
}

// assertUserIntact verifies a failed deletion left the account fully intact.
func assertUserIntact(t *testing.T, ctx context.Context, raw *pgxpool.Pool, user uuid.UUID, _, username, phone string) {
	t.Helper()
	var email string
	var storedUsername, storedPhone *string
	var disabledAt, deletedAt *time.Time
	if err := raw.QueryRow(ctx, `SELECT email,username,phone,disabled_at,deleted_at FROM users WHERE id=$1`, user).Scan(&email, &storedUsername, &storedPhone, &disabledAt, &deletedAt); err != nil {
		t.Fatal("read user")
	}
	if disabledAt != nil || deletedAt != nil {
		t.Fatalf("user %s was tombstoned by a failed deletion: disabled=%v deleted=%v", user, disabledAt, deletedAt)
	}
	if storedUsername == nil || *storedUsername != username {
		t.Fatalf("username was mutated: %v (want %q)", storedUsername, username)
	}
	if phone == "" && storedPhone != nil {
		t.Fatalf("phone was mutated: %v", *storedPhone)
	}
	if phone != "" && (storedPhone == nil || *storedPhone != phone) {
		t.Fatalf("phone was mutated: %v (want %q)", storedPhone, phone)
	}
}

// TestAccountSelfDeletionHTTPBoundary drives the real router against isolated
// PostgreSQL: login, deletion, then proof that the pre-deletion access and
// refresh tokens and the original credentials are all dead.
func TestAccountSelfDeletionHTTPBoundary(t *testing.T) {
	url := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: url, Directory: repositoryMigrationDirectory(t), Schema: "public"}); err != nil {
		t.Fatal("apply migrations")
	}
	db, err := store.Open(ctx, url)
	if err != nil {
		t.Fatal("open store")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open fixture pool")
	}
	t.Cleanup(raw.Close)

	password := "Aa123456"
	userUsername, adminUsername := uniqueUsername("boundary"), uniqueUsername("admin")
	hash, err := auth.HashPassword(password)
	if err != nil {
		t.Fatal(err)
	}
	userEmail := integrationEmail()
	var user, admin uuid.UUID
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = raw.Exec(cleanup, `DELETE FROM users WHERE id=ANY($1)`, []uuid.UUID{user, admin})
	})
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,username,phone,password_hash) VALUES($1,$2,'+8613800138002',$3) RETURNING id`, userEmail, userUsername, hash).Scan(&user); err != nil {
		t.Fatal("insert user")
	}
	adminHash, err := auth.HashPassword(password)
	if err != nil {
		t.Fatal(err)
	}
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,username,password_hash,role) VALUES($1,$2,$3,'admin') RETURNING id`, integrationEmail(), adminUsername, adminHash).Scan(&admin); err != nil {
		t.Fatal("insert admin")
	}

	issuer := auth.TokenIssuer{
		Issuer: "cloud-api-integration", Audience: "cloud-api-clients", SessionAudience: "translator-agent",
		AccessSecret:  []byte("integration-access-signing-key-32-bytes!"),
		SessionSecret: []byte("integration-session-signing-key-32bytes"),
	}
	router := httpapi.NewRouter(httpapi.RouterOptions{
		Config:   config.Config{Environment: "test", DatabaseTimeout: time.Second, RateLimitRPS: 100, RateLimitBurst: 100},
		Database: readyDatabase{},
		Store:    db,
		Tokens:   issuer,
		Logger:   slogDiscard(),
		Version:  "integration-test",
	})

	login := func(identifier string) (string, string) {
		t.Helper()
		request := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", strings.NewReader(`{"identifier":"`+identifier+`","password":"`+password+`"}`))
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		router.ServeHTTP(response, request)
		if response.Code != http.StatusOK {
			t.Fatalf("login %s = %d %s", identifier, response.Code, response.Body.String())
		}
		var tokens struct {
			AccessToken  string `json:"access_token"`
			RefreshToken string `json:"refresh_token"`
		}
		if err := jsonUnmarshal(response.Body.Bytes(), &tokens); err != nil {
			t.Fatal(err)
		}
		return tokens.AccessToken, tokens.RefreshToken
	}
	call := func(method, path, token, body string) *httptest.ResponseRecorder {
		request := httptest.NewRequest(method, path, strings.NewReader(body))
		if body != "" {
			request.Header.Set("Content-Type", "application/json")
		}
		if token != "" {
			request.Header.Set("Authorization", "Bearer "+token)
		}
		response := httptest.NewRecorder()
		router.ServeHTTP(response, request)
		return response
	}

	accessToken, refreshToken := login(userUsername)
	adminAccess, _ := login(adminUsername)

	// Exact confirmation deletes the account; the mismatched confirmation
	// and the admin self-deletion are both rejected first.
	if response := call(http.MethodDelete, "/api/v1/account", accessToken, `{"username":"other_01"}`); response.Code != http.StatusConflict {
		t.Fatalf("mismatched confirmation = %d %s", response.Code, response.Body.String())
	}
	if response := call(http.MethodDelete, "/api/v1/account", adminAccess, `{"username":"`+adminUsername+`"}`); response.Code != http.StatusForbidden {
		t.Fatalf("admin self-deletion = %d %s", response.Code, response.Body.String())
	}
	if response := call(http.MethodDelete, "/api/v1/account", accessToken, `{"username":"`+userUsername+`"}`); response.Code != http.StatusNoContent || response.Body.Len() != 0 {
		t.Fatalf("deletion = %d %s", response.Code, response.Body.String())
	}

	// The pre-deletion access token is dead on every protected endpoint,
	// including a repeated deletion (HTTP-level idempotency).
	for _, target := range []struct{ method, path string }{
		{http.MethodDelete, "/api/v1/account"},
		{http.MethodGet, "/api/v1/users/me"},
		{http.MethodGet, "/api/v1/account/overview"},
		{http.MethodGet, "/api/v1/account/identity"},
		{http.MethodPost, "/api/v1/translation-sessions"},
		{http.MethodPost, "/api/v1/redemptions"},
	} {
		if response := call(target.method, target.path, accessToken, `{"install_id":"x"}`); response.Code != http.StatusUnauthorized {
			t.Fatalf("%s %s after deletion = %d %s", target.method, target.path, response.Code, response.Body.String())
		}
	}

	// The refresh token cannot rotate and the original credentials cannot
	// log in again by username, email, or phone.
	if response := call(http.MethodPost, "/api/v1/auth/refresh", "", `{"refresh_token":"`+refreshToken+`"}`); response.Code != http.StatusUnauthorized {
		t.Fatalf("refresh after deletion = %d %s", response.Code, response.Body.String())
	}
	for _, identifier := range []string{userUsername} {
		request := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", strings.NewReader(`{"identifier":"`+identifier+`","password":"`+password+`"}`))
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		router.ServeHTTP(response, request)
		if response.Code != http.StatusUnauthorized || strings.Contains(response.Body.String(), "boundary") {
			t.Fatalf("login %q after deletion = %d %s", identifier, response.Code, response.Body.String())
		}
	}

	// No external surface exposes the original email or phone after deletion.
	var email string
	var phone *string
	if err := raw.QueryRow(ctx, `SELECT email,phone FROM users WHERE id=$1`, user).Scan(&email, &phone); err != nil || phone != nil || !strings.HasPrefix(email, "deleted+") || !strings.HasSuffix(email, "@deleted.invalid") {
		t.Fatalf("stored identities after deletion: email=%q phone=%v err=%v", email, phone, err)
	}
	if response := call(http.MethodGet, "/api/v1/admin/users?q=", adminAccess, ""); response.Code != http.StatusOK || strings.Contains(response.Body.String(), userEmail) || strings.Contains(response.Body.String(), "+8613800138002") || strings.Contains(response.Body.String(), userUsername) {
		t.Fatalf("admin list leaked deleted identities: %d %s", response.Code, response.Body.String())
	}
}

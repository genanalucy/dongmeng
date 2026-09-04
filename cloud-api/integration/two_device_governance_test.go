//go:build integration

package integration_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
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

var governancePhoneCounter atomic.Uint64

// governancePhoneBase seeds a random window per test-binary run inside the
// +86138xxxxxxxx space. Fixture users referenced by immutable audit logs
// cannot be deleted from the shared test database, so cross-run isolation
// comes from unique fixture identities instead of cleanup.
var governancePhoneBase = randomGovernancePhoneBase()

func randomGovernancePhoneBase() uint64 {
	value, err := rand.Int(rand.Reader, big.NewInt(80_000_000))
	if err != nil {
		panic("seed governance phone fixture base: " + err.Error())
	}
	// Staying within 10,000,000..89,999,999 keeps base+counter an 8-digit
	// suffix for every fixture this binary can register.
	return 10_000_000 + value.Uint64()
}

func governancePhone() string {
	return fmt.Sprintf("+86138%08d", governancePhoneBase+governancePhoneCounter.Add(1)-1)
}

// distinctCreatedAtSleep separates governed creations so the stable
// (created_at, id) arbitration order is deterministic in assertions.
func distinctCreatedAtSleep() error {
	time.Sleep(10 * time.Millisecond)
	return nil
}

// TestPostgresTwoDeviceSessionGovernance proves the product decision against
// a real isolated PostgreSQL: at most two active translation sessions per
// user, a third creation atomically ending the oldest one with the
// device-replacement reason, every in-scope terminal path recording its
// reason, and no usable token surviving a persisted failure.
func TestPostgresTwoDeviceSessionGovernance(t *testing.T) {
	url := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: url, Directory: repositoryMigrationDirectory(t), Schema: "public"}); err != nil {
		t.Fatal("apply governance test migrations")
	}
	db, err := store.Open(ctx, url)
	if err != nil {
		t.Fatal("open isolated governance test database")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open isolated governance fixture pool")
	}
	t.Cleanup(raw.Close)

	now := time.Now().UTC().Truncate(time.Microsecond)
	hash, err := auth.HashPassword("integration-password")
	if err != nil {
		t.Fatal("hash governance fixture password")
	}
	issuer := auth.TokenIssuer{
		Issuer: "governance-integration", Audience: "governance-integration",
		SessionAudience: "translator-agent",
		AccessSecret:    bytes.Repeat([]byte("a"), auth.MinimumSecretBytes),
		SessionSecret:   bytes.Repeat([]byte("s"), auth.MinimumSecretBytes),
	}
	service := auth.AuthorizationService{
		Store: db, EntitlementLifecycle: db, Tokens: issuer,
		MaxConcurrentSessions: auth.TwoActiveTranslationSessionLimit,
	}

	registerUser := func(t *testing.T) (domain.User, domain.Entitlement) {
		t.Helper()
		user, trial, err := db.Register(ctx, domain.RegisterParams{
			Username: "gov_" + uuid.NewString()[:8], Email: integrationEmail(), Phone: governancePhone(), PasswordHash: hash, Now: now,
		})
		if err != nil {
			t.Fatalf("register governance fixture user: %v", err)
		}
		t.Cleanup(func() {
			// Deletion is best-effort hygiene only: fixture users referenced by
			// immutable audit logs deliberately remain, and repeated runs stay
			// isolated through run-unique identity rather than deletion.
			cleanupContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_, _ = raw.Exec(cleanupContext, `DELETE FROM users WHERE id=$1`, user.ID)
		})
		return user, trial
	}

	activeCount := func(t *testing.T, user uuid.UUID, at time.Time) int {
		t.Helper()
		var count int
		if err := raw.QueryRow(ctx, `SELECT count(*) FROM translation_sessions WHERE user_id=$1 AND expires_at>$2 AND ended_at IS NULL AND revoked_at IS NULL`, user, at).Scan(&count); err != nil {
			t.Fatalf("count active sessions: %v", err)
		}
		return count
	}

	t.Run("third creation replaces exactly the oldest", func(t *testing.T) {
		user, _ := registerUser(t)
		// The governance order is (created_at, id), and created_at is the
		// application clock passed at creation, so the fixture advances its
		// clock per creation instead of sleeping; every post-creation check
		// evaluates at the latest creation time (tokens carry nbf = created_at).
		firstAt := now
		first, err := service.CreateTranslationSession(ctx, user.ID, "gov-install-1", firstAt)
		if err != nil {
			t.Fatal(err)
		}
		secondAt := now.Add(10 * time.Millisecond)
		second, err := service.CreateTranslationSession(ctx, user.ID, "gov-install-2", secondAt)
		if err != nil {
			t.Fatalf("second device session: %v", err)
		}
		thirdAt := now.Add(20 * time.Millisecond)
		third, err := service.CreateTranslationSession(ctx, user.ID, "gov-install-3", thirdAt)
		if err != nil {
			t.Fatalf("third device creation must replace the oldest: %v", err)
		}

		replaced, err := db.TranslationSessionState(ctx, user.ID, first.Session.ID, first.Session.EntitlementID, first.Session.JTI, thirdAt)
		if err != nil || replaced.Active || replaced.TerminationReason != domain.TerminationReplacedByDevice {
			t.Fatalf("oldest session state = %+v err = %v, want inactive replaced_by_device", replaced, err)
		}
		if replaced.InstallID != first.Session.InstallID || replaced.JTI != first.Session.JTI || replaced.SessionID != first.Session.ID || replaced.UserID != user.ID {
			t.Fatalf("authorization state lost identity data: %+v", replaced)
		}
		for _, kept := range []auth.TranslationGrant{second, third} {
			state, err := db.TranslationSessionState(ctx, user.ID, kept.Session.ID, kept.Session.EntitlementID, kept.Session.JTI, thirdAt)
			if err != nil || !state.Active {
				t.Fatalf("retained session state = %+v err = %v, want active", state, err)
			}
		}
		if count := activeCount(t, user.ID, thirdAt); count != 2 {
			t.Fatalf("active sessions = %d, want exactly 2", count)
		}
		if _, err := service.AuthorizeTranslationSession(ctx, first.Token, thirdAt); !errors.Is(err, domain.ErrUnauthorized) {
			t.Fatalf("replaced device authorization error = %v", err)
		}
		for _, kept := range []auth.TranslationGrant{second, third} {
			if _, err := service.AuthorizeTranslationSession(ctx, kept.Token, thirdAt); err != nil {
				t.Fatalf("retained device authorization error = %v", err)
			}
		}
	})

	t.Run("concurrent creations keep exactly two active", func(t *testing.T) {
		user, _ := registerUser(t)
		const attempts = 16
		type result struct {
			grant auth.TranslationGrant
			err   error
		}
		start := make(chan struct{})
		results := make(chan result, attempts)
		var wait sync.WaitGroup
		for i := range attempts {
			wait.Add(1)
			go func() {
				defer wait.Done()
				<-start
				grant, err := service.CreateTranslationSession(ctx, user.ID, fmt.Sprintf("gov-install-concurrent-%d", i), now)
				results <- result{grant: grant, err: err}
			}()
		}
		close(start)
		wait.Wait()
		close(results)
		grants := make([]auth.TranslationGrant, 0, attempts)
		for outcome := range results {
			if outcome.err != nil {
				t.Fatalf("concurrent creation failed: %v", outcome.err)
			}
			grants = append(grants, outcome.grant)
		}

		var total, replaced int
		if err := raw.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE termination_reason=$2) FROM translation_sessions WHERE user_id=$1`, user.ID, string(domain.TerminationReplacedByDevice)).Scan(&total, &replaced); err != nil {
			t.Fatal("count concurrent arbitration outcomes")
		}
		if total != attempts || replaced != attempts-2 {
			t.Fatalf("arbitration totals = %d replaced = %d, want %d and %d", total, replaced, attempts, attempts-2)
		}
		if count := activeCount(t, user.ID, now); count != 2 {
			t.Fatalf("active sessions = %d, want exactly 2", count)
		}
		var unexplained int
		if err := raw.QueryRow(ctx, `SELECT count(*) FROM translation_sessions WHERE user_id=$1 AND (ended_at IS NOT NULL OR revoked_at IS NOT NULL) AND termination_reason IS DISTINCT FROM $2`, user.ID, string(domain.TerminationReplacedByDevice)).Scan(&unexplained); err != nil || unexplained != 0 {
			t.Fatalf("terminated sessions without a replacement reason = %d err = %v", unexplained, err)
		}
		for _, grant := range grants {
			state, err := db.TranslationSessionState(ctx, user.ID, grant.Session.ID, grant.Session.EntitlementID, grant.Session.JTI, now)
			if err != nil {
				t.Fatalf("arbitrated grant lookup: %v", err)
			}
			_, authErr := service.AuthorizeTranslationSession(ctx, grant.Token, now)
			if state.Active != (authErr == nil) {
				t.Fatalf("grant authorization disagrees with persisted state: active=%v err=%v", state.Active, authErr)
			}
		}
	})

	t.Run("persisted creation failure leaves no token and no termination", func(t *testing.T) {
		user, _ := registerUser(t)
		first, err := service.CreateTranslationSession(ctx, user.ID, "gov-install-rollback-1", now)
		if err != nil {
			t.Fatal(err)
		}
		if err := distinctCreatedAtSleep(); err != nil {
			t.Fatal(err)
		}
		second, err := service.CreateTranslationSession(ctx, user.ID, "gov-install-rollback-2", now)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := raw.Exec(ctx, `ALTER TABLE translation_sessions ADD CONSTRAINT gov_force_insert_failure CHECK (install_id <> 'gov-force-fail') NOT VALID`); err != nil {
			t.Fatal("add forced insert failure fixture")
		}
		t.Cleanup(func() {
			cleanupContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if _, err := raw.Exec(cleanupContext, `ALTER TABLE translation_sessions DROP CONSTRAINT IF EXISTS gov_force_insert_failure`); err != nil {
				t.Error("remove forced insert failure fixture")
			}
		})

		grant, err := service.CreateTranslationSession(ctx, user.ID, "gov-force-fail", now)
		if err == nil || grant != (auth.TranslationGrant{}) {
			t.Fatalf("forced creation returned grant=%+v err=%v, want failure without a grant", grant, err)
		}
		for _, kept := range []auth.TranslationGrant{first, second} {
			state, stateErr := db.TranslationSessionState(ctx, user.ID, kept.Session.ID, kept.Session.EntitlementID, kept.Session.JTI, now)
			if stateErr != nil || !state.Active {
				t.Fatalf("persisted failure ended a live session: state=%+v err=%v", state, stateErr)
			}
		}
		if count := activeCount(t, user.ID, now); count != 2 {
			t.Fatalf("active sessions after persisted failure = %d, want 2", count)
		}
		var prematureTerminals int
		if err := raw.QueryRow(ctx, `SELECT count(*) FROM translation_sessions WHERE user_id=$1 AND (ended_at IS NOT NULL OR revoked_at IS NOT NULL OR termination_reason IS NOT NULL)`, user.ID).Scan(&prematureTerminals); err != nil || prematureTerminals != 0 {
			t.Fatalf("persisted failure left terminal state: %d err = %v", prematureTerminals, err)
		}
		var failedDeviceRows int
		if err := raw.QueryRow(ctx, `SELECT count(*) FROM user_devices WHERE user_id=$1 AND install_id='gov-force-fail'`, user.ID).Scan(&failedDeviceRows); err != nil || failedDeviceRows != 0 {
			t.Fatalf("rolled back creation persisted its device row: %d err = %v", failedDeviceRows, err)
		}
	})

	t.Run("disabling user terminates sessions with reason", func(t *testing.T) {
		user, _ := registerUser(t)
		admin, _ := registerUser(t)
		if _, err := raw.Exec(ctx, `UPDATE users SET role='admin' WHERE id=$1`, admin.ID); err != nil {
			t.Fatal("make governance admin fixture")
		}
		grant, err := service.CreateTranslationSession(ctx, user.ID, "gov-install-disable", now)
		if err != nil {
			t.Fatal(err)
		}
		if err := db.DisableUser(ctx, admin.ID, user.ID, now.Add(time.Second)); err != nil {
			t.Fatalf("disable user: %v", err)
		}
		state, err := db.TranslationSessionState(ctx, user.ID, grant.Session.ID, grant.Session.EntitlementID, grant.Session.JTI, now.Add(time.Second))
		if err != nil || state.Active || state.TerminationReason != domain.TerminationUserDisabled {
			t.Fatalf("disabled user session state = %+v err = %v, want inactive user_disabled", state, err)
		}
		if _, err := service.AuthorizeTranslationSession(ctx, grant.Token, now.Add(time.Second)); !errors.Is(err, domain.ErrUnauthorized) {
			t.Fatalf("disabled user authorization error = %v", err)
		}
	})

	t.Run("entitlement revocation terminates sessions and blocks creation", func(t *testing.T) {
		user, trial := registerUser(t)
		admin, _ := registerUser(t)
		if _, err := raw.Exec(ctx, `UPDATE users SET role='admin' WHERE id=$1`, admin.ID); err != nil {
			t.Fatal("make governance admin fixture")
		}
		grant, err := service.CreateTranslationSession(ctx, user.ID, "gov-install-entitlement", now)
		if err != nil {
			t.Fatal(err)
		}
		if err := db.RevokeEntitlementByAdmin(ctx, admin.ID, user.ID, trial.ID, now.Add(time.Second)); err != nil {
			t.Fatalf("revoke entitlement: %v", err)
		}
		state, err := db.TranslationSessionState(ctx, user.ID, grant.Session.ID, grant.Session.EntitlementID, grant.Session.JTI, now.Add(time.Second))
		if err != nil || state.Active || state.TerminationReason != domain.TerminationEntitlementRevoked {
			t.Fatalf("revoked entitlement session state = %+v err = %v, want inactive entitlement_revoked", state, err)
		}
		if _, err := service.AuthorizeTranslationSession(ctx, grant.Token, now.Add(time.Second)); !errors.Is(err, domain.ErrUnauthorized) {
			t.Fatalf("revoked entitlement authorization error = %v", err)
		}
		blocked, err := service.CreateTranslationSession(ctx, user.ID, "gov-install-blocked", now.Add(2*time.Second))
		if !errors.Is(err, domain.ErrNoEntitlement) || blocked != (auth.TranslationGrant{}) {
			t.Fatalf("creation without a valid entitlement = %+v err = %v, want no-entitlement failure", blocked, err)
		}
	})

	t.Run("third HTTP creation succeeds without conflict", func(t *testing.T) {
		user, _ := registerUser(t)
		router := httpapi.NewRouter(httpapi.RouterOptions{
			Config:   config.Config{Environment: "test", DatabaseTimeout: time.Second, RateLimitRPS: 1000, RateLimitBurst: 1000},
			Database: readyDatabase{}, Store: db, Tokens: issuer, Now: func() time.Time { return now },
		})
		access, err := issuer.AccessToken(user.ID, user.Role, 15*time.Minute, now)
		if err != nil {
			t.Fatal("mint governance access token")
		}
		create := func(installID string) map[string]any {
			t.Helper()
			request := httptest.NewRequest(http.MethodPost, "/api/v1/translation-sessions", strings.NewReader(`{"install_id":"`+installID+`"}`))
			request.Header.Set("Content-Type", "application/json")
			request.Header.Set("Authorization", "Bearer "+access)
			response := httptest.NewRecorder()
			router.ServeHTTP(response, request)
			if response.Code != http.StatusCreated {
				t.Fatalf("session creation %s status = %d body = %s", installID, response.Code, response.Body.String())
			}
			var body map[string]any
			if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
				t.Fatalf("decode creation response: %v", err)
			}
			return body
		}
		if body := create("gov-http-1"); body["token"] == "" || body["session_id"] == "" {
			t.Fatalf("creation response omitted grant fields: %v", body)
		}
		time.Sleep(10 * time.Millisecond)
		if body := create("gov-http-2"); body["token"] == "" {
			t.Fatalf("second creation response omitted token: %v", body)
		}
		time.Sleep(10 * time.Millisecond)
		if body := create("gov-http-3"); body["token"] == "" {
			t.Fatalf("third creation response omitted token: %v", body)
		}

		listRequest := httptest.NewRequest(http.MethodGet, "/api/v1/translation-sessions?limit=10&offset=0", nil)
		listRequest.Header.Set("Authorization", "Bearer "+access)
		listResponse := httptest.NewRecorder()
		router.ServeHTTP(listResponse, listRequest)
		if listResponse.Code != http.StatusOK || !strings.Contains(listResponse.Body.String(), "replaced_by_device") {
			t.Fatalf("session listing status = %d body = %s", listResponse.Code, listResponse.Body.String())
		}
		if count := activeCount(t, user.ID, now); count != 2 {
			t.Fatalf("active sessions after HTTP flow = %d, want 2", count)
		}
	})
}

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

	// raceRound runs fnCreate and fnTerminate concurrently behind a barrier
	// and returns both outcomes after both committed. The favored side is
	// released a few milliseconds early so alternating rounds deterministically
	// exercise both arbitration orders: a committed grant terminated by the
	// later scan, and a creation rejected by the already-committed terminal
	// operation.
	raceRound := func(t *testing.T, favorCreate bool, fnCreate func() error, fnTerminate func() error) (createErr, terminateErr error) {
		t.Helper()
		start := make(chan struct{})
		var done sync.WaitGroup
		done.Add(2)
		go func() {
			defer done.Done()
			<-start
			if !favorCreate {
				time.Sleep(5 * time.Millisecond)
			}
			createErr = fnCreate()
		}()
		go func() {
			defer done.Done()
			<-start
			if favorCreate {
				time.Sleep(5 * time.Millisecond)
			}
			terminateErr = fnTerminate()
		}()
		close(start)
		done.Wait()
		return createErr, terminateErr
	}

	t.Run("concurrent creation and disablement never return a doomed grant", func(t *testing.T) {
		admin, _ := registerUser(t)
		if _, err := raw.Exec(ctx, `UPDATE users SET role='admin' WHERE id=$1`, admin.ID); err != nil {
			t.Fatal("make governance admin fixture")
		}
		const rounds = 10
		var succeeded int
		for round := 0; round < rounds; round++ {
			user, _ := registerUser(t)
			var grant auth.TranslationGrant
			createErr, disableErr := raceRound(t, round%2 == 0,
				func() error {
					var err error
					grant, err = service.CreateTranslationSession(ctx, user.ID, fmt.Sprintf("gov-race-disable-%d", round), now.Add(time.Duration(round)*time.Second))
					return err
				},
				func() error {
					// A full second past the fixture base keeps disabled_at after
					// the row's DB-side created_at, which the old millisecond
					// margin could violate.
					return db.DisableUser(ctx, admin.ID, user.ID, now.Add(time.Duration(round+1)*time.Second))
				},
			)
			if disableErr != nil {
				t.Fatalf("round %d concurrent disable: %v", round, disableErr)
			}
			// A disablement that committed must terminate every session the user
			// has, including one a concurrent creation was about to insert; no
			// session may be left looking active for a disabled owner.
			var doomed int
			if err := raw.QueryRow(ctx, `SELECT count(*) FROM translation_sessions s JOIN users u ON u.id=s.user_id WHERE u.id=$1 AND u.disabled_at IS NOT NULL AND s.ended_at IS NULL AND s.revoked_at IS NULL`, user.ID).Scan(&doomed); err != nil || doomed != 0 {
				t.Fatalf("round %d left %d active-looking sessions for a disabled user (err = %v)", round, doomed, err)
			}
			if createErr == nil {
				succeeded++
				// The creation committed before the disablement, so the
				// disablement scan must have caught and terminated it.
				var revokedAt *time.Time
				var reason string
				if err := raw.QueryRow(ctx, `SELECT revoked_at, COALESCE(termination_reason,'') FROM translation_sessions WHERE id=$1`, grant.Session.ID).Scan(&revokedAt, &reason); err != nil || revokedAt == nil || reason != string(domain.TerminationUserDisabled) {
					t.Fatalf("round %d committed grant was not terminated by the disablement scan: revoked_at=%v reason=%q err=%v", round, revokedAt, reason, err)
				}
				// Evaluate past the token's fixture-shifted not-before claim
				// while staying inside its expiry.
				_, authErr := service.AuthorizeTranslationSession(ctx, grant.Token, now.Add(time.Duration(round+1)*time.Second))
				var terminal domain.TerminatedTranslationSessionError
				if !errors.As(authErr, &terminal) || terminal.Reason != domain.TerminationUserDisabled {
					t.Fatalf("round %d grant authorization error = %v, want typed user_disabled terminal reason", round, authErr)
				}
				if !errors.Is(authErr, domain.ErrUnauthorized) {
					t.Fatalf("round %d typed terminal reason is not unauthorized: %v", round, authErr)
				}
			} else if !errors.Is(createErr, domain.ErrUnauthorized) {
				t.Fatalf("round %d creation error = %v, want unauthorized for a disabled owner", round, createErr)
			}
		}
		if succeeded == 0 {
			t.Fatal("race fixture never exercised the committed-grant path")
		}
	})

	t.Run("concurrent creation and entitlement revocation never return a doomed grant", func(t *testing.T) {
		admin, _ := registerUser(t)
		if _, err := raw.Exec(ctx, `UPDATE users SET role='admin' WHERE id=$1`, admin.ID); err != nil {
			t.Fatal("make governance admin fixture")
		}
		const rounds = 10
		var succeeded int
		for round := 0; round < rounds; round++ {
			user, trial := registerUser(t)
			revoke := func() error {
				// Alternate between the self-service and admin revocation paths;
				// both must share the same per-user arbitration lock. The full
				// second of margin keeps revoked_at past the entitlement's
				// DB-side created_at instead of violating the revocation CHECK.
				if round%2 == 0 {
					return db.RevokeEntitlement(ctx, user.ID, trial.ID, now.Add(time.Duration(round+1)*time.Second))
				}
				return db.RevokeEntitlementByAdmin(ctx, admin.ID, user.ID, trial.ID, now.Add(time.Duration(round+1)*time.Second))
			}
			var grant auth.TranslationGrant
			createErr, revokeErr := raceRound(t, round%2 == 0,
				func() error {
					var err error
					grant, err = service.CreateTranslationSession(ctx, user.ID, fmt.Sprintf("gov-race-revoke-%d", round), now.Add(time.Duration(round)*time.Second))
					return err
				},
				revoke,
			)
			if revokeErr != nil {
				t.Fatalf("round %d concurrent revocation: %v", round, revokeErr)
			}
			// A committed revocation must leave no session looking active on
			// the revoked entitlement, including one a concurrent creation was
			// about to insert.
			var doomed int
			if err := raw.QueryRow(ctx, `SELECT count(*) FROM translation_sessions s JOIN entitlements e ON e.id=s.entitlement_id WHERE s.user_id=$1 AND e.revoked_at IS NOT NULL AND s.ended_at IS NULL AND s.revoked_at IS NULL`, user.ID).Scan(&doomed); err != nil || doomed != 0 {
				t.Fatalf("round %d left %d active-looking sessions on a revoked entitlement (err = %v)", round, doomed, err)
			}
			if createErr == nil {
				succeeded++
				var revokedAt *time.Time
				var reason string
				if err := raw.QueryRow(ctx, `SELECT revoked_at, COALESCE(termination_reason,'') FROM translation_sessions WHERE id=$1`, grant.Session.ID).Scan(&revokedAt, &reason); err != nil || revokedAt == nil || reason != string(domain.TerminationEntitlementRevoked) {
					t.Fatalf("round %d committed grant was not terminated by the revocation scan: revoked_at=%v reason=%q err=%v", round, revokedAt, reason, err)
				}
				// Evaluate past the token's fixture-shifted not-before claim
				// while staying inside its expiry.
				_, authErr := service.AuthorizeTranslationSession(ctx, grant.Token, now.Add(time.Duration(round+1)*time.Second))
				var terminal domain.TerminatedTranslationSessionError
				if !errors.As(authErr, &terminal) || terminal.Reason != domain.TerminationEntitlementRevoked {
					t.Fatalf("round %d grant authorization error = %v, want typed entitlement_revoked terminal reason", round, authErr)
				}
			} else if !errors.Is(createErr, domain.ErrNoEntitlement) {
				t.Fatalf("round %d creation error = %v, want no-entitlement for a revoked entitlement", round, createErr)
			}
		}
		if succeeded == 0 {
			t.Fatal("race fixture never exercised the committed-grant path")
		}
	})

	t.Run("creation, disablement, and revocation share one lock order", func(t *testing.T) {
		admin, _ := registerUser(t)
		if _, err := raw.Exec(ctx, `UPDATE users SET role='admin' WHERE id=$1`, admin.ID); err != nil {
			t.Fatal("make governance admin fixture")
		}
		const rounds = 8
		for round := 0; round < rounds; round++ {
			user, trial := registerUser(t)
			start := make(chan struct{})
			var grant auth.TranslationGrant
			var createErr, disableErr, revokeErr error
			var done sync.WaitGroup
			done.Add(3)
			go func() {
				defer done.Done()
				<-start
				createErr = func() error {
					var err error
					grant, err = service.CreateTranslationSession(ctx, user.ID, fmt.Sprintf("gov-race-three-way-%d", round), now.Add(time.Duration(round)*time.Second))
					return err
				}()
			}()
			go func() {
				defer done.Done()
				<-start
				// The terminal operations get a small head start for the
				// creation so the committed-grant path is exercised while they
				// still contend for the arbitration lock behind it.
				time.Sleep(5 * time.Millisecond)
				disableErr = db.DisableUser(ctx, admin.ID, user.ID, now.Add(time.Duration(round+1)*time.Second))
			}()
			go func() {
				defer done.Done()
				<-start
				time.Sleep(5 * time.Millisecond)
				revokeErr = db.RevokeEntitlementByAdmin(ctx, admin.ID, user.ID, trial.ID, now.Add(time.Duration(round+1)*time.Second))
			}()
			close(start)
			done.Wait()
			// All three paths take the same per-user arbitration lock before
			// any row lock, so any ordering regression surfaces here as a
			// deadlock-detected or timed-out error instead of passing silently.
			if disableErr != nil {
				t.Fatalf("round %d disable: %v", round, disableErr)
			}
			if revokeErr != nil {
				t.Fatalf("round %d revoke: %v", round, revokeErr)
			}
			var doomed int
			if err := raw.QueryRow(ctx, `SELECT count(*) FROM translation_sessions WHERE user_id=$1 AND ended_at IS NULL AND revoked_at IS NULL`, user.ID).Scan(&doomed); err != nil || doomed != 0 {
				t.Fatalf("round %d left %d active-looking sessions after terminal governance (err = %v)", round, doomed, err)
			}
			if createErr == nil {
				var revokedAt *time.Time
				var reason string
				if err := raw.QueryRow(ctx, `SELECT revoked_at, COALESCE(termination_reason,'') FROM translation_sessions WHERE id=$1`, grant.Session.ID).Scan(&revokedAt, &reason); err != nil || revokedAt == nil {
					t.Fatalf("round %d committed grant was not terminated: err=%v", round, err)
				}
				if reason != string(domain.TerminationUserDisabled) && reason != string(domain.TerminationEntitlementRevoked) {
					t.Fatalf("round %d committed grant reason = %q, want the first terminal scan to win", round, reason)
				}
			} else if !errors.Is(createErr, domain.ErrUnauthorized) && !errors.Is(createErr, domain.ErrNoEntitlement) {
				t.Fatalf("round %d creation error = %v, want unauthorized or no-entitlement", round, createErr)
			}
		}
	})

	t.Run("cross-user governance stays isolated", func(t *testing.T) {
		owner, _ := registerUser(t)
		other, _ := registerUser(t)
		const perUser = 5
		start := make(chan struct{})
		grants := make(chan auth.TranslationGrant, 2*perUser)
		failures := make(chan error, 2*perUser)
		var wait sync.WaitGroup
		for _, fixture := range []struct {
			user uuid.UUID
			tag  string
		}{{owner.ID, "gov-iso-owner"}, {other.ID, "gov-iso-other"}} {
			for i := range perUser {
				wait.Add(1)
				go func() {
					defer wait.Done()
					<-start
					grant, err := service.CreateTranslationSession(ctx, fixture.user, fmt.Sprintf("%s-%d", fixture.tag, i), now)
					if err != nil {
						failures <- err
						return
					}
					grants <- grant
				}()
			}
		}
		close(start)
		wait.Wait()
		close(grants)
		close(failures)
		for err := range failures {
			t.Fatalf("cross-user concurrent creation failed: %v", err)
		}
		// Each user's arbitration must only ever terminate that user's own
		// sessions: exactly perUser-limit replacements per user and exactly
		// limit still-active sessions per user. A cross-user termination bug
		// surfaces here as the wrong replacement or active count.
		limit := auth.TwoActiveTranslationSessionLimit
		for _, governedUser := range []uuid.UUID{owner.ID, other.ID} {
			var total, replaced int
			if err := raw.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE termination_reason=$2) FROM translation_sessions WHERE user_id=$1`, governedUser, string(domain.TerminationReplacedByDevice)).Scan(&total, &replaced); err != nil {
				t.Fatal("count cross-user arbitration outcomes")
			}
			if total != perUser || replaced != perUser-limit {
				t.Fatalf("user arbitration total=%d replaced=%d, want %d and %d", total, replaced, perUser, perUser-limit)
			}
			if count := activeCount(t, governedUser, now); count != limit {
				t.Fatalf("user active sessions = %d, want %d", count, limit)
			}
		}

		// A different user must not end or revoke the owner's session.
		var ownerGrant auth.TranslationGrant
		for grant := range grants {
			if grant.Session.UserID == owner.ID {
				ownerGrant = grant
			}
		}
		if err := service.EndTranslationSession(ctx, other.ID, ownerGrant.Session.ID, now.Add(time.Second)); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("cross-user end error = %v, want not_found", err)
		}
		if err := service.RevokeTranslationSession(ctx, other.ID, ownerGrant.Session.ID, now.Add(time.Second)); !errors.Is(err, domain.ErrNotFound) {
			t.Fatalf("cross-user revoke error = %v, want not_found", err)
		}
		if _, err := db.TranslationSessionState(ctx, owner.ID, ownerGrant.Session.ID, ownerGrant.Session.EntitlementID, ownerGrant.Session.JTI, now.Add(time.Second)); err != nil {
			t.Fatalf("owner session state after cross-user terminal attempts: %v", err)
		}

		// Forged identity combinations must fail closed with the bare
		// unauthorized sentinel: no lifecycle detail may leak.
		if _, err := db.TranslationSessionState(ctx, other.ID, ownerGrant.Session.ID, ownerGrant.Session.EntitlementID, ownerGrant.Session.JTI, now.Add(time.Second)); !errors.Is(err, domain.ErrUnauthorized) {
			t.Fatalf("forged-owner state error = %v, want unauthorized", err)
		}
		forged, err := issuer.TranslationTokenForInstall(ownerGrant.Session.ID, ownerGrant.Session.EntitlementID, other.ID, ownerGrant.Session.JTI, "gov-iso-forged", time.Minute, now)
		if err != nil {
			t.Fatal("mint forged-owner token")
		}
		claims, authErr := service.AuthorizeTranslationSession(ctx, forged, now.Add(time.Second))
		if !errors.Is(authErr, domain.ErrUnauthorized) {
			t.Fatalf("forged-owner authorization error = %v", authErr)
		}
		var terminal domain.TerminatedTranslationSessionError
		if errors.As(authErr, &terminal) {
			t.Fatalf("forged-owner token leaked terminal reason %q", terminal.Reason)
		}
		assertNoLeakedClaims(t, claims)
		mismatched, err := issuer.TranslationTokenForInstall(ownerGrant.Session.ID, ownerGrant.Session.EntitlementID, owner.ID, uuid.New(), "gov-iso-forged", time.Minute, now)
		if err != nil {
			t.Fatal("mint mismatched-JTI token")
		}
		claims, authErr = service.AuthorizeTranslationSession(ctx, mismatched, now.Add(time.Second))
		if !errors.Is(authErr, domain.ErrUnauthorized) || errors.As(authErr, &terminal) {
			t.Fatalf("mismatched-JTI authorization error = %v, want bare unauthorized", authErr)
		}
		assertNoLeakedClaims(t, claims)

		// The owner's genuine token still authorizes.
		if _, err := service.AuthorizeTranslationSession(ctx, ownerGrant.Token, now.Add(time.Second)); err != nil {
			t.Fatalf("owner authorization after isolation checks: %v", err)
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

// assertNoLeakedClaims pins that an authorization failure never returns any
// claim material to the caller.
func assertNoLeakedClaims(t *testing.T, claims auth.Claims) {
	t.Helper()
	if claims.Role != "" || claims.UserID != "" || claims.SessionID != "" || claims.InstallID != "" || claims.EntitlementID != "" || claims.Scope != "" || claims.Subject != "" || claims.ID != "" || len(claims.Audience) != 0 || claims.ExpiresAt != nil || claims.IssuedAt != nil || claims.NotBefore != nil {
		t.Fatalf("authorization failure leaked claims: %+v", claims)
	}
}

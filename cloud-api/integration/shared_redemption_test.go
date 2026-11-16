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
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
	"github.com/dngmeng/cloud-api/internal/domain"
	httpapi "github.com/dngmeng/cloud-api/internal/http"
	"github.com/dngmeng/cloud-api/internal/store"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// All database work uses a fresh schema behind the existing loopback-only
// CLOUD_API_TEST_DATABASE_URL gate. Codes are generated at runtime; failures
// deliberately omit SQL errors, response bodies, credentials and code material.
func TestSharedRedemptionDifferentAccountsHTTP(t *testing.T) {
	f := openSharedRedemptionFixture(t)
	code := f.code(t, true, f.now.Add(24*time.Hour))
	users := []uuid.UUID{f.user(t), f.user(t)}
	requests := make([]*http.Request, len(users))
	for i, user := range users {
		// Retain the existing endpoint's whitespace/case canonicalization.
		requests[i] = f.request(t, user, "  "+strings.ToLower(code.plain)+"  ", f.now)
	}
	responses := sharedRedemptionRace(f.router(f.now), requests)
	for i, response := range responses {
		entitlement := requireSharedRedemptionCreated(t, response, users[i], f.now)
		f.requireCounts(t, code.id, users[i], 1, 1)
		f.requireClaim(t, code.id, entitlement, f.now)
		active, err := f.db.ActiveEntitlement(context.Background(), users[i], f.now)
		if err != nil || active.ID != entitlement.ID {
			t.Fatal("shared redemption did not grant immediate active access")
		}
	}
	var untouched bool
	if err := f.raw.QueryRow(context.Background(), `SELECT redeemed_by IS NULL AND redeemed_at IS NULL FROM redemption_codes WHERE id=$1`, code.id).Scan(&untouched); err != nil || !untouched {
		t.Fatal("shared redemption consumed the global single-use marker")
	}
}

func TestSharedRedemptionSameAccountHTTP(t *testing.T) {
	t.Run("sequential duplicate is rejected without another grant", func(t *testing.T) {
		f := openSharedRedemptionFixture(t)
		user := f.user(t)
		code := f.code(t, true, f.now.Add(24*time.Hour))
		router := f.router(f.now)
		entitlement := requireSharedRedemptionCreated(t, serveSharedRedemption(router, f.request(t, user, code.plain, f.now)), user, f.now)
		requireSharedRedemptionConflict(t, serveSharedRedemption(router, f.request(t, user, code.plain, f.now)))
		f.requireCounts(t, code.id, user, 1, 1)
		f.requireClaim(t, code.id, entitlement, f.now)
	})

	t.Run("ten simultaneous requests create exactly one entitlement and claim", func(t *testing.T) {
		f := openSharedRedemptionFixture(t)
		user := f.user(t)
		code := f.code(t, true, f.now.Add(24*time.Hour))
		requests := make([]*http.Request, 10)
		for i := range requests {
			requests[i] = f.request(t, user, code.plain, f.now)
		}
		var winner domain.Entitlement
		successes := 0
		for _, response := range sharedRedemptionRace(f.router(f.now), requests) {
			if response.Code == http.StatusCreated {
				winner = requireSharedRedemptionCreated(t, response, user, f.now)
				successes++
			} else {
				requireSharedRedemptionConflict(t, response)
			}
		}
		if successes != 1 {
			t.Fatalf("successful concurrent shared redemptions = %d, want 1", successes)
		}
		f.requireCounts(t, code.id, user, 1, 1)
		f.requireClaim(t, code.id, winner, f.now)
	})
}

func TestSharedRedemptionClaimDeadlineHTTP(t *testing.T) {
	f := openSharedRedemptionFixture(t)
	// Beijing 2027-01-01 00:00 is UTC 2026-12-31 16:00. Use the
	// explicit UTC instant, not the host timezone or wall-clock sleeps.
	cutoff := time.Date(2026, time.December, 31, 16, 0, 0, 0, time.UTC)
	code := f.code(t, true, cutoff)
	for _, test := range []struct {
		name string
		at   time.Time
		want int
	}{
		{name: "one microsecond before Beijing new year", at: cutoff.Add(-time.Microsecond), want: http.StatusCreated},
		{name: "exactly Beijing new year", at: cutoff, want: http.StatusConflict},
		{name: "one microsecond after Beijing new year", at: cutoff.Add(time.Microsecond), want: http.StatusConflict},
	} {
		t.Run(test.name, func(t *testing.T) {
			// A fresh account avoids a duplicate-claim rejection masking expiry.
			user := f.user(t)
			response := serveSharedRedemption(f.router(test.at), f.request(t, user, code.plain, test.at))
			if test.want == http.StatusCreated {
				entitlement := requireSharedRedemptionCreated(t, response, user, test.at)
				f.requireCounts(t, code.id, user, 1, 1)
				f.requireClaim(t, code.id, entitlement, test.at)
			} else {
				requireSharedRedemptionConflict(t, response)
				f.requireCounts(t, code.id, user, 0, 0)
			}
		})
	}
}

func TestSharedRedemptionDisabledBatchPreservesExistingAccessHTTP(t *testing.T) {
	f := openSharedRedemptionFixture(t)
	owner, other := f.user(t), f.user(t)
	code := f.code(t, true, f.now.Add(24*time.Hour))
	router := f.router(f.now)
	granted := requireSharedRedemptionCreated(t, serveSharedRedemption(router, f.request(t, owner, code.plain, f.now)), owner, f.now)

	// Exercise the real admin operation instead of simulating disablement SQL.
	adminToken, err := f.tokens.AccessToken(f.admin, "admin", time.Hour, f.now)
	if err != nil {
		t.Fatal("issue batch administrator access token")
	}
	request := httptest.NewRequest(http.MethodPost, "/api/v1/admin/code-batches/"+code.batch.String()+"/disable", nil)
	request.Header.Set("Authorization", "Bearer "+adminToken)
	response := serveSharedRedemption(router, request)
	if response.Code != http.StatusNoContent {
		t.Fatalf("disable batch HTTP status = %d, want 204", response.Code)
	}
	requireSharedRedemptionConflict(t, serveSharedRedemption(router, f.request(t, other, code.plain, f.now)))
	f.requireCounts(t, code.id, other, 0, 0)
	f.requireCounts(t, code.id, owner, 1, 1)
	f.requireClaim(t, code.id, granted, f.now)
	active, err := f.db.ActiveEntitlement(context.Background(), owner, f.now.Add(time.Second))
	if err != nil || active.ID != granted.ID || !active.StartsAt.Equal(granted.StartsAt) || !active.ExpiresAt.Equal(granted.ExpiresAt) {
		t.Fatal("disabling a batch revoked or changed an already granted entitlement")
	}
}

func TestSharedRedemptionLegacySingleUseStillStacksHTTP(t *testing.T) {
	f := openSharedRedemptionFixture(t)
	// The legacy fixture omits the new columns, testing shared's false default.
	code := f.code(t, false, time.Time{})
	users := make([]uuid.UUID, 10)
	requests := make([]*http.Request, len(users))
	trialEnd := f.now.Add(72 * time.Hour)
	for i := range users {
		users[i] = f.user(t)
		f.trial(t, users[i], f.now)
		requests[i] = f.request(t, users[i], code.plain, f.now)
	}
	successes := 0
	var winner uuid.UUID
	for i, response := range sharedRedemptionRace(f.router(f.now), requests) {
		if response.Code == http.StatusCreated {
			// Unlike shared claims, the original global code starts after trial.
			requireSharedRedemptionCreated(t, response, users[i], trialEnd)
			winner = users[i]
			successes++
			f.requireCounts(t, code.id, users[i], 2, 0)
		} else {
			requireSharedRedemptionConflict(t, response)
			f.requireCounts(t, code.id, users[i], 1, 0)
		}
	}
	if successes != 1 {
		t.Fatalf("successful concurrent legacy redemptions = %d, want 1", successes)
	}
	var redeemedBy uuid.UUID
	var shared, redeemed bool
	if err := f.raw.QueryRow(context.Background(), `SELECT shared,redeemed_by,redeemed_at IS NOT NULL FROM redemption_codes WHERE id=$1`, code.id).Scan(&shared, &redeemedBy, &redeemed); err != nil || shared || !redeemed || redeemedBy != winner {
		t.Fatal("legacy code did not retain global single-use ownership")
	}
}

func TestSharedRedemptionWrongHashGrantsNothing(t *testing.T) {
	f := openSharedRedemptionFixture(t)
	user := f.user(t)
	code := f.code(t, true, f.now.Add(24*time.Hour))
	wrongCode, wrongHash, err := auth.RandomCode()
	if err != nil {
		t.Fatal("generate unmatched redemption code")
	}
	if _, err := f.db.RedeemCode(context.Background(), user, wrongHash, f.now); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("unmatched redemption hash did not return a conflict")
	}
	f.requireCounts(t, code.id, user, 0, 0)
	requireSharedRedemptionConflict(t, serveSharedRedemption(f.router(f.now), f.request(t, user, wrongCode, f.now)))
	f.requireCounts(t, code.id, user, 0, 0)
	// Failed attempts must not consume the actual campaign for this account.
	granted := requireSharedRedemptionCreated(t, serveSharedRedemption(f.router(f.now), f.request(t, user, code.plain, f.now)), user, f.now)
	f.requireCounts(t, code.id, user, 1, 1)
	f.requireClaim(t, code.id, granted, f.now)
}

func TestSharedRedemptionExistingEntitlementDoesNotPostponeStartHTTP(t *testing.T) {
	for _, kind := range []string{"trial", "package"} {
		t.Run(kind, func(t *testing.T) {
			f := openSharedRedemptionFixture(t)
			user := f.user(t)
			var existing domain.Entitlement
			if kind == "trial" {
				existing = f.trial(t, user, f.now)
			} else {
				// An already stacked, future package also must not delay shared access.
				if err := f.raw.QueryRow(context.Background(), `INSERT INTO entitlements(user_id,kind,starts_at,expires_at) VALUES($1,'package',$2,$3) RETURNING id,user_id,kind,starts_at,expires_at`, user, f.now.Add(72*time.Hour), f.now.Add(8832*time.Hour)).Scan(&existing.ID, &existing.UserID, &existing.Kind, &existing.StartsAt, &existing.ExpiresAt); err != nil {
					t.Fatal("insert existing future package")
				}
			}
			code := f.code(t, true, f.now.Add(24*time.Hour))
			granted := requireSharedRedemptionCreated(t, serveSharedRedemption(f.router(f.now), f.request(t, user, code.plain, f.now)), user, f.now)
			f.requireCounts(t, code.id, user, 2, 1)
			f.requireClaim(t, code.id, granted, f.now)
			var starts, expires time.Time
			var revoked bool
			if err := f.raw.QueryRow(context.Background(), `SELECT starts_at,expires_at,revoked_at IS NOT NULL FROM entitlements WHERE id=$1`, existing.ID).Scan(&starts, &expires, &revoked); err != nil || revoked || !starts.Equal(existing.StartsAt) || !expires.Equal(existing.ExpiresAt) {
				t.Fatal("shared redemption changed or revoked the existing entitlement")
			}
			active, err := f.db.ActiveEntitlement(context.Background(), user, f.now)
			if err != nil || active.ID != granted.ID {
				t.Fatal("existing entitlement prevented immediate shared access")
			}
		})
	}
}

type sharedRedemptionFixture struct {
	db     *store.Postgres
	raw    *pgxpool.Pool
	now    time.Time
	admin  uuid.UUID
	tokens auth.TokenIssuer
}

type sharedRedemptionCode struct {
	id, batch uuid.UUID
	plain     string
}

func openSharedRedemptionFixture(t *testing.T) sharedRedemptionFixture {
	t.Helper()
	db, raw, now := openAdminSetupTestStore(t)
	admin := insertSetupAdmin(t, raw, now, "shared_codes")
	return sharedRedemptionFixture{
		db: db, raw: raw, now: now, admin: admin.id,
		tokens: auth.TokenIssuer{
			Issuer: "shared-redemption-integration", Audience: "shared-redemption-integration", SessionAudience: "shared-redemption-agent",
			AccessSecret: bytes.Repeat([]byte("a"), auth.MinimumSecretBytes), SessionSecret: bytes.Repeat([]byte("s"), auth.MinimumSecretBytes),
		},
	}
}

func (f sharedRedemptionFixture) user(t *testing.T) uuid.UUID {
	t.Helper()
	var id uuid.UUID
	// These users have no password login or initial entitlement; counts below
	// therefore observe every accidental grant, including a wrong entitlement kind.
	if err := f.raw.QueryRow(context.Background(), `INSERT INTO users(email,password_hash) VALUES($1,$2) RETURNING id`, integrationEmail(), strings.Repeat("x", 60)).Scan(&id); err != nil {
		t.Fatal("insert redemption account")
	}
	return id
}

func (f sharedRedemptionFixture) code(t *testing.T, shared bool, expires time.Time) sharedRedemptionCode {
	t.Helper()
	var code sharedRedemptionCode
	plain, hash, err := auth.RandomCode()
	if err != nil {
		t.Fatal("generate redemption fixture")
	}
	code.plain = plain
	if err := f.raw.QueryRow(context.Background(), `INSERT INTO code_batches(name,duration_days,created_by,created_by_role,created_at) VALUES('shared integration',365,$1,'admin',$2) RETURNING id`, f.admin, f.now).Scan(&code.batch); err != nil {
		t.Fatal("insert redemption batch")
	}
	if shared {
		err = f.raw.QueryRow(context.Background(), `INSERT INTO redemption_codes(batch_id,code_hash,shared,claim_expires_at) VALUES($1,$2,true,$3) RETURNING id`, code.batch, hash, expires).Scan(&code.id)
	} else {
		err = f.raw.QueryRow(context.Background(), `INSERT INTO redemption_codes(batch_id,code_hash) VALUES($1,$2) RETURNING id`, code.batch, hash).Scan(&code.id)
	}
	if err != nil {
		t.Fatal("insert redemption fixture")
	}
	return code
}

func (f sharedRedemptionFixture) trial(t *testing.T, user uuid.UUID, at time.Time) domain.Entitlement {
	t.Helper()
	var entitlement domain.Entitlement
	if err := f.raw.QueryRow(context.Background(), `INSERT INTO entitlements(user_id,kind,starts_at,expires_at) VALUES($1,'trial',$2,$3) RETURNING id,user_id,kind,starts_at,expires_at`, user, at, at.Add(72*time.Hour)).Scan(&entitlement.ID, &entitlement.UserID, &entitlement.Kind, &entitlement.StartsAt, &entitlement.ExpiresAt); err != nil {
		t.Fatal("insert existing trial")
	}
	return entitlement
}

func (f sharedRedemptionFixture) router(at time.Time) http.Handler {
	return httpapi.NewRouter(httpapi.RouterOptions{
		Config: config.Config{Environment: "test", DatabaseTimeout: 5 * time.Second, RateLimitRPS: 1000, RateLimitBurst: 1000},
		Store:  f.db, Tokens: f.tokens, Now: func() time.Time { return at },
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
}

func (f sharedRedemptionFixture) request(t *testing.T, user uuid.UUID, code string, at time.Time) *http.Request {
	t.Helper()
	token, err := f.tokens.AccessToken(user, "user", time.Hour, at.Add(-time.Minute))
	if err != nil {
		t.Fatal("issue redemption account access token")
	}
	body, err := json.Marshal(struct {
		Code string `json:"code"`
	}{Code: code})
	if err != nil {
		t.Fatal("encode redemption request")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	t.Cleanup(cancel)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/redemptions", bytes.NewReader(body)).WithContext(ctx)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+token)
	return request
}

func serveSharedRedemption(router http.Handler, request *http.Request) *httptest.ResponseRecorder {
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)
	return response
}

func sharedRedemptionRace(router http.Handler, requests []*http.Request) []*httptest.ResponseRecorder {
	responses := make([]*httptest.ResponseRecorder, len(requests))
	start := make(chan struct{})
	var ready, done sync.WaitGroup
	ready.Add(len(requests))
	done.Add(len(requests))
	for i, request := range requests {
		go func() {
			defer done.Done()
			ready.Done()
			<-start
			responses[i] = serveSharedRedemption(router, request)
		}()
	}
	ready.Wait()
	close(start)
	done.Wait()
	return responses
}

func requireSharedRedemptionCreated(t *testing.T, response *httptest.ResponseRecorder, user uuid.UUID, starts time.Time) domain.Entitlement {
	t.Helper()
	if response.Code != http.StatusCreated {
		t.Fatalf("redemption HTTP status = %d, want 201", response.Code)
	}
	var entitlement domain.Entitlement
	if err := json.Unmarshal(response.Body.Bytes(), &entitlement); err != nil {
		t.Fatal("decode redemption entitlement response")
	}
	if entitlement.ID == uuid.Nil || entitlement.UserID != user || entitlement.Kind != string(domain.EntitlementPackage) || !entitlement.StartsAt.Equal(starts) || !entitlement.ExpiresAt.Equal(starts.Add(8760*time.Hour)) {
		t.Fatal("redemption response is not the expected fixed 365-day package")
	}
	return entitlement
}

func requireSharedRedemptionConflict(t *testing.T, response *httptest.ResponseRecorder) {
	t.Helper()
	if response.Code != http.StatusConflict {
		t.Fatalf("rejected redemption HTTP status = %d, want 409", response.Code)
	}
	var body struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil || body.Error != "conflict" {
		t.Fatal("rejected redemption did not retain the generic conflict response")
	}
}

func (f sharedRedemptionFixture) requireCounts(t *testing.T, code, user uuid.UUID, wantEntitlements, wantClaims int) {
	t.Helper()
	var entitlements, claims int
	if err := f.raw.QueryRow(context.Background(), `SELECT (SELECT count(*) FROM entitlements WHERE user_id=$1),(SELECT count(*) FROM redemption_claims WHERE code_id=$2 AND user_id=$1)`, user, code).Scan(&entitlements, &claims); err != nil {
		t.Fatal("count redemption grants and claims")
	}
	if entitlements != wantEntitlements || claims != wantClaims {
		t.Fatalf("redemption entitlements/claims = %d/%d, want %d/%d", entitlements, claims, wantEntitlements, wantClaims)
	}
}

func (f sharedRedemptionFixture) requireClaim(t *testing.T, code uuid.UUID, entitlement domain.Entitlement, at time.Time) {
	t.Helper()
	var matches bool
	if err := f.raw.QueryRow(context.Background(), `SELECT EXISTS(SELECT 1 FROM redemption_claims c JOIN entitlements e ON e.id=c.entitlement_id AND e.user_id=c.user_id WHERE c.code_id=$1 AND c.user_id=$2 AND c.entitlement_id=$3 AND c.claimed_at=$4 AND e.kind='package' AND e.starts_at=$4 AND e.expires_at=$5 AND e.revoked_at IS NULL)`, code, entitlement.UserID, entitlement.ID, at, at.Add(8760*time.Hour)).Scan(&matches); err != nil || !matches {
		t.Fatal("claim is not linked to the returned unrevoked immediate entitlement")
	}
}

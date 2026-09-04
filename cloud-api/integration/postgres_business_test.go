//go:build integration

package integration_test

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
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

// Run with CLOUD_API_TEST_DATABASE_URL against an isolated database after
// migrations. The test never loads .env or prints the supplied URL.
func TestPostgresBusinessLifecycle(t *testing.T) {
	url := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: url, Directory: repositoryMigrationDirectory(t), Schema: "public"}); err != nil {
		t.Fatal("apply lifecycle test migrations")
	}
	db, err := store.Open(ctx, url)
	if err != nil {
		t.Fatal("open isolated test database")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open isolated fixture pool")
	}
	t.Cleanup(raw.Close)

	now := time.Now().UTC().Truncate(time.Microsecond)
	hash, err := auth.HashPassword("integration-password")
	if err != nil {
		t.Fatal("hash integration fixture password")
	}
	var userID, adminID, otherID, legacyID, batchID uuid.UUID
	t.Cleanup(func() {
		cleanupContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if batchID != uuid.Nil {
			if _, err := raw.Exec(cleanupContext, `DELETE FROM redemption_codes WHERE batch_id=$1`, batchID); err != nil {
				t.Error("cleanup fixture redemption codes")
			}
			if _, err := raw.Exec(cleanupContext, `DELETE FROM code_batches WHERE id=$1`, batchID); err != nil {
				t.Error("cleanup fixture code batch")
			}
		}
		userIDs := make([]uuid.UUID, 0, 3)
		for _, id := range []uuid.UUID{userID, adminID, otherID, legacyID} {
			if id != uuid.Nil {
				userIDs = append(userIDs, id)
			}
		}
		if len(userIDs) > 0 {
			if _, err := raw.Exec(cleanupContext, `DELETE FROM users WHERE id = ANY($1)`, userIDs); err != nil {
				t.Error("cleanup fixture users")
			}
		}
	})

	user, trial, err := db.Register(ctx, domain.RegisterParams{Username: "phoneuser_" + uuid.NewString()[:8], Phone: "+8613800138000", PasswordHash: hash, Now: now})
	if err != nil {
		t.Fatal("register fixture user")
	}
	userID = user.ID
	if trial.UserID != user.ID || !trial.StartsAt.Equal(now) || !trial.ExpiresAt.Equal(now.Add(3*24*time.Hour)) {
		t.Fatal("registration did not create a fixed three-day trial")
	}
	var trialCount int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM entitlements WHERE user_id=$1 AND kind='trial'`, user.ID).Scan(&trialCount); err != nil {
		t.Fatal("count registration trials")
	}
	if trialCount != 1 {
		t.Fatalf("registration trial count = %d, want 1", trialCount)
	}
	var storedReservedEmail string
	if err := raw.QueryRow(ctx, `SELECT email FROM users WHERE id=$1`, user.ID).Scan(&storedReservedEmail); err != nil || !strings.HasPrefix(storedReservedEmail, "phone-") || !strings.HasSuffix(storedReservedEmail, "@reserved.invalid") {
		t.Fatal("phone registration did not retain reserved email")
	}
	issuer := auth.TokenIssuer{Issuer: "integration", Audience: "integration", AccessSecret: bytes.Repeat([]byte("a"), auth.MinimumSecretBytes), SessionSecret: bytes.Repeat([]byte("s"), auth.MinimumSecretBytes)}
	router := httpapi.NewRouter(httpapi.RouterOptions{Config: config.Config{Environment: "test", DatabaseTimeout: time.Second, RateLimitRPS: 1000, RateLimitBurst: 1000}, Store: db, Tokens: issuer, Now: func() time.Time { return now }})
	loginRequest := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", strings.NewReader(`{"phone":"`+storedReservedEmail+`","password":"integration-password"}`))
	loginRequest.Header.Set("Content-Type", "application/json")
	loginResponse := httptest.NewRecorder()
	router.ServeHTTP(loginResponse, loginRequest)
	if loginResponse.Code != http.StatusUnauthorized || strings.Contains(loginResponse.Body.String(), "access_token") || strings.Contains(loginResponse.Body.String(), "refresh_token") {
		t.Fatal("reserved email login was not rejected safely")
	}
	var reservedRefreshCount int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM refresh_tokens WHERE user_id=$1`, user.ID).Scan(&reservedRefreshCount); err != nil || reservedRefreshCount != 0 {
		t.Fatal("reserved email login persisted a refresh token")
	}
	loginRequest = httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", strings.NewReader(`{"phone":"+8613800138000","password":"integration-password"}`))
	loginRequest.Header.Set("Content-Type", "application/json")
	loginResponse = httptest.NewRecorder()
	router.ServeHTTP(loginResponse, loginRequest)
	if loginResponse.Code != http.StatusOK || !strings.Contains(loginResponse.Body.String(), `"token_type":"Bearer"`) {
		t.Fatal("phone login did not issue the expected authentication response")
	}
	legacyEmail := integrationEmail()
	if err := raw.QueryRow(ctx, `INSERT INTO users(email,password_hash) VALUES($1,$2) RETURNING id`, legacyEmail, hash).Scan(&legacyID); err != nil {
		t.Fatal("insert legacy email fixture")
	}
	legacyByEmail, _, err := db.UserByEmail(ctx, legacyEmail)
	if err != nil || legacyByEmail.ID != legacyID {
		t.Fatal("legacy email user was not readable")
	}
	legacyByID, err := db.UserByID(ctx, legacyID)
	if err != nil || legacyByID.Email != legacyEmail {
		t.Fatal("legacy user id read was not compatible")
	}
	refresh := auth.RefreshManager{Store: db}
	issued, err := refresh.Issue(ctx, legacyID, time.Hour, now)
	if err != nil {
		t.Fatal("issue legacy refresh")
	}
	if _, err := refresh.Rotate(ctx, issued.Plaintext, time.Hour, now.Add(time.Minute)); err != nil {
		t.Fatal("rotate legacy refresh")
	}
	if _, _, err := db.Register(ctx, domain.RegisterParams{Username: user.Username, Phone: "+8613900138000", PasswordHash: hash, Now: now}); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("duplicate username was not rejected generically")
	}
	if _, _, err := db.Register(ctx, domain.RegisterParams{Username: "phoneuser_" + uuid.NewString()[:8], Phone: "+8613800138000", PasswordHash: hash, Now: now}); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("duplicate phone was not rejected generically")
	}

	admin, _, err := db.Register(ctx, domain.RegisterParams{Username: "phoneuser_" + uuid.NewString()[:8], Phone: "+8613700138000", PasswordHash: hash, Now: now})
	if err != nil {
		t.Fatal("register fixture admin")
	}
	adminID = admin.ID
	if _, err := raw.Exec(ctx, `UPDATE users SET role='admin' WHERE id=$1`, admin.ID); err != nil {
		t.Fatal("make fixture admin")
	}
	code, codeHash, err := auth.RandomCode()
	if err != nil {
		t.Fatal("generate fixture redemption code")
	}
	if err := raw.QueryRow(ctx, `INSERT INTO code_batches(name,duration_days,created_by,created_by_role,created_at) VALUES('integration',365,$1,'admin',$2) RETURNING id`, admin.ID, now).Scan(&batchID); err != nil {
		t.Fatal("create fixture code batch")
	}
	if _, err := raw.Exec(ctx, `INSERT INTO redemption_codes(batch_id,code_hash) VALUES($1,$2)`, batchID, codeHash); err != nil {
		t.Fatal("create fixture redemption code")
	}
	canonicalHash, err := auth.HashRedemptionCode("  " + strings.ToLower(code) + "  ")
	if err != nil {
		t.Fatal("canonicalize fixture redemption code")
	}
	packageEntitlement, err := db.RedeemCode(ctx, user.ID, canonicalHash, now)
	if err != nil {
		t.Fatal("redeem canonical code")
	}
	if packageEntitlement.Kind != string(domain.EntitlementPackage) ||
		!packageEntitlement.StartsAt.Equal(trial.ExpiresAt) ||
		!packageEntitlement.ExpiresAt.Equal(trial.ExpiresAt.Add(365*24*time.Hour)) {
		t.Fatal("redemption did not create a stacked fixed 365-day entitlement")
	}
	if _, err := db.RedeemCode(ctx, user.ID, canonicalHash, now); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("second redemption was not rejected")
	}

	active, err := db.ActiveEntitlement(ctx, user.ID, now.Add(time.Second))
	if err != nil || active.ID != trial.ID {
		t.Fatal("trial was not the active entitlement before its stacked package")
	}
	first := integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute))
	if err := db.CreateAuthorizedTranslationSessionWithLimit(ctx, first, now, 2); err != nil {
		t.Fatal("create first active session")
	}
	// created_at is the application clock passed at creation, so each session
	// is created at a strictly later time to keep the (created_at, id)
	// governance order deterministic for the replacement assertions below.
	second := integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute))
	if err := db.CreateAuthorizedTranslationSessionWithLimit(ctx, second, now.Add(10*time.Millisecond), 2); err != nil {
		t.Fatal("second active session was not admitted")
	}
	third := integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute))
	if err := db.CreateAuthorizedTranslationSessionWithLimit(ctx, third, now.Add(time.Second), 2); err != nil {
		t.Fatalf("third device creation did not replace the oldest session: %v", err)
	}
	var firstEnded bool
	var firstReason string
	if err := raw.QueryRow(ctx, `SELECT ended_at IS NOT NULL, COALESCE(termination_reason,'') FROM translation_sessions WHERE id=$1`, first.ID).Scan(&firstEnded, &firstReason); err != nil {
		t.Fatal("read replaced session terminal state")
	}
	if !firstEnded || firstReason != string(domain.TerminationReplacedByDevice) {
		t.Fatalf("replaced session ended=%v reason=%q, want ended replaced_by_device", firstEnded, firstReason)
	}
	replacedState, err := db.TranslationSessionState(ctx, user.ID, first.ID, first.EntitlementID, first.JTI, now.Add(time.Second))
	if err != nil || replacedState.Active || replacedState.TerminationReason != domain.TerminationReplacedByDevice {
		t.Fatalf("replaced session state = %+v err = %v, want inactive replaced_by_device", replacedState, err)
	}
	secondState, err := db.TranslationSessionState(ctx, user.ID, second.ID, second.EntitlementID, second.JTI, now.Add(time.Second))
	if err != nil || !secondState.Active {
		t.Fatalf("second session state = %+v err = %v, want active", secondState, err)
	}
	var activeCount int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM translation_sessions WHERE user_id=$1 AND expires_at>$2 AND ended_at IS NULL AND revoked_at IS NULL`, user.ID, now.Add(time.Second)).Scan(&activeCount); err != nil || activeCount != 2 {
		t.Fatalf("active session count = %d err = %v, want exactly 2", activeCount, err)
	}

	other, _, err := db.Register(ctx, domain.RegisterParams{Username: "phoneuser_" + uuid.NewString()[:8], Phone: "+8613600138000", PasswordHash: hash, Now: now})
	if err != nil {
		t.Fatal("register cross-user fixture")
	}
	otherID = other.ID
	if err := db.CreateUsageRecord(ctx, domain.CreateUsageParams{UserID: other.ID, SessionID: third.ID, AudioSeconds: 1, Characters: 1, Now: now}); err == nil {
		t.Fatal("cross-user usage/session association was accepted")
	}
	usage := domain.CreateUsageParams{UserID: user.ID, SessionID: third.ID, AudioSeconds: 1, Characters: 1, Now: now}
	if err := db.CreateUsageRecord(ctx, usage); err != nil {
		t.Fatal("write first session usage")
	}
	if err := db.CreateUsageRecord(ctx, usage); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("second usage record for one session was not rejected")
	}

	if err := db.EndTranslationSession(ctx, user.ID, third.ID, now.Add(2*time.Second)); err != nil {
		t.Fatal("end third session")
	}
	if err := raw.QueryRow(ctx, `SELECT COALESCE(termination_reason,'') FROM translation_sessions WHERE id=$1`, third.ID).Scan(&firstReason); err != nil || firstReason != string(domain.TerminationEnded) {
		t.Fatalf("ended session reason = %q err = %v, want ended", firstReason, err)
	}
	fourth := integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute))
	if err := db.CreateAuthorizedTranslationSessionWithLimit(ctx, fourth, now.Add(2*time.Second), 2); err != nil {
		t.Fatal("ended session did not release active-session slot")
	}
	if err := db.RevokeTranslationSession(ctx, user.ID, fourth.ID, now.Add(3*time.Second)); err != nil {
		t.Fatal("revoke fourth session")
	}
	if err := raw.QueryRow(ctx, `SELECT COALESCE(termination_reason,'') FROM translation_sessions WHERE id=$1`, fourth.ID).Scan(&firstReason); err != nil || firstReason != string(domain.TerminationRevoked) {
		t.Fatalf("revoked session reason = %q err = %v, want revoked", firstReason, err)
	}
	if err := db.CreateAuthorizedTranslationSessionWithLimit(ctx, integrationSession(user.ID, active.ID, "install-"+uuid.NewString(), now.Add(time.Minute)), now.Add(3*time.Second), 2); err != nil {
		t.Fatal("revoked session did not release active-session slot")
	}

	// These constraints are intentionally added only after the lifecycle cases:
	// PostgreSQL checks them in the real Register transaction, so each failure
	// proves the production rollback boundary without a fake transaction.
	if _, err := raw.Exec(ctx, `ALTER TABLE users DROP CONSTRAINT IF EXISTS phone_auth_force_user_failure`); err != nil {
		t.Fatal("reset user rollback fixture")
	}
	if _, err := raw.Exec(ctx, `ALTER TABLE entitlements DROP CONSTRAINT IF EXISTS phone_auth_force_trial_failure`); err != nil {
		t.Fatal("reset trial rollback fixture")
	}
	t.Cleanup(func() {
		cleanupContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = raw.Exec(cleanupContext, `ALTER TABLE users DROP CONSTRAINT IF EXISTS phone_auth_force_user_failure`)
		_, _ = raw.Exec(cleanupContext, `ALTER TABLE entitlements DROP CONSTRAINT IF EXISTS phone_auth_force_trial_failure`)
	})
	blockedUsername := "phoneuser_" + uuid.NewString()[:8]
	if _, err := raw.Exec(ctx, `ALTER TABLE users ADD CONSTRAINT phone_auth_force_user_failure CHECK (username <> '`+blockedUsername+`') NOT VALID`); err != nil {
		t.Fatal("add user rollback fixture")
	}
	if _, _, err := db.Register(ctx, domain.RegisterParams{Username: blockedUsername, Phone: "+8613500138000", PasswordHash: hash, Now: now}); err == nil {
		t.Fatal("forced user insert failure succeeded")
	}
	var failedUserCount, failedUserEntitlements int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM users WHERE username=$1`, blockedUsername).Scan(&failedUserCount); err != nil || failedUserCount != 0 {
		t.Fatal("user insert failure left a user")
	}
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM entitlements e JOIN users u ON u.id=e.user_id WHERE u.username=$1`, blockedUsername).Scan(&failedUserEntitlements); err != nil || failedUserEntitlements != 0 {
		t.Fatal("user insert failure left an entitlement")
	}
	if _, err := raw.Exec(ctx, `ALTER TABLE users DROP CONSTRAINT phone_auth_force_user_failure`); err != nil {
		t.Fatal("remove user rollback fixture")
	}

	blockedTrialUsername := "phoneuser_" + uuid.NewString()[:8]
	if _, err := raw.Exec(ctx, `ALTER TABLE entitlements ADD CONSTRAINT phone_auth_force_trial_failure CHECK (kind <> 'trial') NOT VALID`); err != nil {
		t.Fatal("add trial rollback fixture")
	}
	if _, _, err := db.Register(ctx, domain.RegisterParams{Username: blockedTrialUsername, Phone: "+8613400138000", PasswordHash: hash, Now: now}); err == nil {
		t.Fatal("forced trial insert failure succeeded")
	}
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM users WHERE username=$1`, blockedTrialUsername).Scan(&failedUserCount); err != nil || failedUserCount != 0 {
		t.Fatal("trial insert failure did not roll back user")
	}
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM entitlements e JOIN users u ON u.id=e.user_id WHERE u.username=$1`, blockedTrialUsername).Scan(&failedUserEntitlements); err != nil || failedUserEntitlements != 0 {
		t.Fatal("trial insert failure left an entitlement")
	}
}

func TestPostgresRegistrationVerification(t *testing.T) {
	url := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: url, Directory: repositoryMigrationDirectory(t), Schema: "public"}); err != nil {
		t.Fatal("apply registration verification migrations")
	}
	db, err := store.Open(ctx, url)
	if err != nil {
		t.Fatal("open isolated registration verification database")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal("open isolated registration verification fixture pool")
	}
	t.Cleanup(raw.Close)

	now := time.Now().UTC().Truncate(time.Microsecond)
	pepper := []byte("registration-verification-integration-pepper")
	passwordHash, err := auth.HashPassword("integration-password")
	if err != nil {
		t.Fatal("hash registration verification fixture password")
	}
	createdEmails := make([]string, 0, 32)
	createdUsernames := make([]string, 0, 32)
	rateLimitKeys := make([][]byte, 0, 64)
	t.Cleanup(func() {
		cleanupContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if len(createdEmails) > 0 {
			_, _ = raw.Exec(cleanupContext, `DELETE FROM registration_verifications WHERE email = ANY($1)`, createdEmails)
		}
		if len(createdUsernames) > 0 {
			_, _ = raw.Exec(cleanupContext, `DELETE FROM users WHERE username = ANY($1)`, createdUsernames)
		}
		for _, key := range rateLimitKeys {
			_, _ = raw.Exec(cleanupContext, `DELETE FROM email_verification_rate_limits WHERE key_hash=$1`, key)
		}
	})

	request := func(username, email string, emailKey, ipKey []byte, at time.Time) domain.CreateRegistrationVerificationParams {
		createdEmails = append(createdEmails, email)
		createdUsernames = append(createdUsernames, username)
		rateLimitKeys = append(rateLimitKeys, emailKey, ipKey)
		return domain.CreateRegistrationVerificationParams{
			Username: username, Email: email, PasswordHash: passwordHash,
			CodeHash: registrationVerificationCodeHash(pepper, []byte("salt"), "012345"), CodeSalt: []byte("salt"),
			EmailRateLimitKey: emailKey, IPRateLimitKey: ipKey, Now: at, ExpiresAt: at.Add(10 * time.Minute),
		}
	}

	staleKey := []byte("stale-ip")
	if _, err := raw.Exec(ctx, `INSERT INTO email_verification_rate_limits(key_type,key_hash,window_started_at,request_count,updated_at) VALUES('ip',$1,$2,1,$2)`, staleKey, now.Add(-time.Hour)); err != nil {
		t.Fatal("create expired rate limit fixture")
	}
	rateLimitKeys = append(rateLimitKeys, staleKey)

	cooldownUsername, cooldownEmail := registrationVerificationIdentity()
	cooldown := request(cooldownUsername, cooldownEmail, []byte("cooldown-email"), []byte("cooldown-ip"), now)
	if _, err := db.RequestRegistrationVerification(ctx, cooldown); err != nil {
		t.Fatal("create cooldown verification")
	}
	var staleBuckets int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM email_verification_rate_limits WHERE key_hash=$1`, staleKey).Scan(&staleBuckets); err != nil || staleBuckets != 0 {
		t.Fatalf("request cleanup stale buckets = %d, err = %v, want 0", staleBuckets, err)
	}
	cooldown.Now = now.Add(59 * time.Second)
	cooldown.ExpiresAt = cooldown.Now.Add(10 * time.Minute)
	cooldown.Username = " OTHER_USER " // Case variants must share the canonical email record.
	cooldown.Email = strings.ToUpper(cooldown.Email)
	if _, err := db.RequestRegistrationVerification(ctx, cooldown); !errors.Is(err, domain.ErrRegistrationVerificationFailed) {
		t.Fatalf("resend before 60 seconds error = %v, want generic verification failure", err)
	}

	emailLimitUsername, emailLimitEmail := registrationVerificationIdentity()
	emailLimit := request(emailLimitUsername, emailLimitEmail, []byte("email-limit"), []byte("email-limit-ip"), now)
	for attempt := 0; attempt < 5; attempt++ {
		emailLimit.Now = now.Add(time.Duration(attempt) * 61 * time.Second)
		emailLimit.ExpiresAt = emailLimit.Now.Add(10 * time.Minute)
		if _, err := db.RequestRegistrationVerification(ctx, emailLimit); err != nil {
			t.Fatalf("email rate limit request %d: %v", attempt+1, err)
		}
	}
	emailLimit.Now = now.Add(5 * 61 * time.Second)
	emailLimit.ExpiresAt = emailLimit.Now.Add(10 * time.Minute)
	if _, err := db.RequestRegistrationVerification(ctx, emailLimit); !errors.Is(err, domain.ErrRegistrationVerificationFailed) {
		t.Fatalf("sixth email request error = %v, want generic verification failure", err)
	}

	sharedIPKey := []byte("ip-limit")
	for attempt := 0; attempt < 20; attempt++ {
		username, email := registrationVerificationIdentity()
		params := request(username, email, []byte("ip-email-"+uuid.NewString()), sharedIPKey, now)
		if _, err := db.RequestRegistrationVerification(ctx, params); err != nil {
			t.Fatalf("IP rate limit request %d: %v", attempt+1, err)
		}
	}
	username, email := registrationVerificationIdentity()
	if _, err := db.RequestRegistrationVerification(ctx, request(username, email, []byte("ip-email-over"), sharedIPKey, now)); !errors.Is(err, domain.ErrRegistrationVerificationFailed) {
		t.Fatalf("twenty-first IP request error = %v, want generic verification failure", err)
	}

	attemptUsername, attemptEmail := registrationVerificationIdentity()
	attemptParams := request(attemptUsername, attemptEmail, []byte("attempt-email"), []byte("attempt-ip"), now)
	if _, err := db.RequestRegistrationVerification(ctx, attemptParams); err != nil {
		t.Fatal("create attempt limit verification")
	}
	for attempt := 0; attempt < 5; attempt++ {
		if _, err := db.ConfirmRegistrationVerification(ctx, domain.ConfirmRegistrationVerificationParams{Email: attemptEmail, Code: "000000", CodePepper: pepper, EmailRateLimitKey: []byte("attempt-email"), Now: now.Add(time.Duration(attempt) * time.Second)}); !errors.Is(err, domain.ErrRegistrationVerificationFailed) {
			t.Fatalf("incorrect attempt %d error = %v, want generic verification failure", attempt+1, err)
		}
	}
	if _, err := db.ConfirmRegistrationVerification(ctx, domain.ConfirmRegistrationVerificationParams{Email: attemptEmail, Code: "012345", CodePepper: pepper, EmailRateLimitKey: []byte("attempt-email"), Now: now.Add(6 * time.Second)}); !errors.Is(err, domain.ErrRegistrationVerificationFailed) {
		t.Fatalf("invalidated verification confirmation error = %v, want generic verification failure", err)
	}

	confirmUsername, confirmEmail := registrationVerificationIdentity()
	confirmParams := request(confirmUsername, confirmEmail, []byte("confirm-email"), []byte("confirm-ip"), now)
	if _, err := db.RequestRegistrationVerification(ctx, confirmParams); err != nil {
		t.Fatal("create concurrent confirmation verification")
	}
	var confirmations sync.WaitGroup
	confirmationErrors := make(chan error, 2)
	for range 2 {
		confirmations.Add(1)
		go func() {
			defer confirmations.Done()
			_, err := db.ConfirmRegistrationVerification(context.Background(), domain.ConfirmRegistrationVerificationParams{Email: strings.ToUpper(confirmEmail), Code: "012345", CodePepper: pepper, EmailRateLimitKey: []byte("confirm-email"), Now: now.Add(time.Second)})
			confirmationErrors <- err
		}()
	}
	confirmations.Wait()
	close(confirmationErrors)
	successes := 0
	for err := range confirmationErrors {
		if err == nil {
			successes++
		} else if !errors.Is(err, domain.ErrRegistrationVerificationFailed) {
			t.Fatalf("concurrent confirmation error = %v", err)
		}
	}
	if successes != 1 {
		t.Fatalf("successful concurrent confirmations = %d, want 1", successes)
	}
	var users, entitlements int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM users WHERE username=$1`, confirmUsername).Scan(&users); err != nil || users != 1 {
		t.Fatalf("confirmed users = %d, err = %v, want 1", users, err)
	}
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM entitlements e JOIN users u ON u.id=e.user_id WHERE u.username=$1 AND e.kind='trial'`, confirmUsername).Scan(&entitlements); err != nil || entitlements != 1 {
		t.Fatalf("confirmed trial entitlements = %d, err = %v, want 1", entitlements, err)
	}
	var emailBuckets, ipBuckets int
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM email_verification_rate_limits WHERE key_type='email' AND key_hash=$1`, []byte("confirm-email")).Scan(&emailBuckets); err != nil || emailBuckets != 0 {
		t.Fatalf("completed email buckets = %d, err = %v, want 0", emailBuckets, err)
	}
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM email_verification_rate_limits WHERE key_type='ip' AND key_hash=$1`, []byte("confirm-ip")).Scan(&ipBuckets); err != nil || ipBuckets != 1 {
		t.Fatalf("completed IP buckets = %d, err = %v, want 1", ipBuckets, err)
	}
	if err := db.CleanupRegistrationVerificationRateLimits(ctx, now.Add(time.Hour)); err != nil {
		t.Fatalf("clean up expired verification rate limits: %v", err)
	}
	if err := raw.QueryRow(ctx, `SELECT count(*) FROM email_verification_rate_limits WHERE key_hash=$1`, []byte("confirm-ip")).Scan(&ipBuckets); err != nil || ipBuckets != 0 {
		t.Fatalf("expired IP buckets after cleanup = %d, err = %v, want 0", ipBuckets, err)
	}
}

func TestPostgresRegistrationVerificationLateSenderFailurePreservesNewerReservation(t *testing.T) {
	url := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: url, Directory: repositoryMigrationDirectory(t), Schema: "public"}); err != nil {
		t.Fatal("apply reservation migration")
	}
	db, err := store.Open(ctx, url)
	if err != nil {
		t.Fatal("open reservation database")
	}
	defer db.Close()
	pepper := []byte("late-sender-failure-pepper")
	now := time.Now().UTC().Truncate(time.Microsecond)
	username, email := registrationVerificationIdentity()
	passwordHash, err := auth.HashPassword("integration-password")
	if err != nil {
		t.Fatal("hash password")
	}
	var mu sync.Mutex
	current := now
	write := func(ctx context.Context, draft auth.RegistrationVerificationDraft) (uuid.UUID, error) {
		mu.Lock()
		at := current
		mu.Unlock()
		verification, err := db.RequestRegistrationVerification(ctx, domain.CreateRegistrationVerificationParams{Username: draft.Username, Email: draft.Email, PasswordHash: draft.PasswordHash, CodeHash: draft.CodeHash, CodeSalt: draft.Salt, EmailRateLimitKey: []byte("late-email-" + email), IPRateLimitKey: []byte("late-ip-" + email), Now: at, ExpiresAt: draft.ExpiresAt})
		return verification.ReservationID, err
	}
	firstStarted := make(chan struct{})
	releaseFirst := make(chan struct{})
	calls := 0
	service, err := auth.NewEmailRegistrationService(auth.EmailRegistrationService{
		HashPasswordValue:  func(string) (string, error) { return passwordHash, nil },
		GenerateCode:       func() (string, error) { return "012345", nil },
		GenerateSalt:       func() ([]byte, error) { return []byte("late-salt"), nil },
		CodePepper:         pepper,
		RateLimitKeySecret: []byte("late-rate-key-secret"),
		WriteVerification:  write,
		Clock:              func() time.Time { mu.Lock(); defer mu.Unlock(); return current },
		Sender: registrationCodeSenderFunc(func(context.Context, string, string, time.Time) error {
			mu.Lock()
			calls++
			call := calls
			mu.Unlock()
			if call == 1 {
				close(firstStarted)
				<-releaseFirst
				return errors.New("sender failed")
			}
			return nil
		}),
		InvalidateVerification: func(ctx context.Context, reservationID uuid.UUID, email string, at time.Time) error {
			return db.InvalidateRegistrationVerification(ctx, domain.InvalidateRegistrationVerificationParams{ReservationID: reservationID, Email: email, Now: at})
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	request := auth.RegistrationVerificationRequest{Username: username, Email: email, Password: "integration-password", ClientIP: netip.MustParseAddr("203.0.113.10")}
	firstDone := make(chan error, 1)
	go func() { _, err := service.RequestVerification(context.Background(), request); firstDone <- err }()
	<-firstStarted
	mu.Lock()
	current = current.Add(auth.RegistrationVerificationResendDelay)
	mu.Unlock()
	if _, err := service.RequestVerification(ctx, request); err != nil {
		t.Fatalf("newer reservation request: %v", err)
	}
	close(releaseFirst)
	if err := <-firstDone; err == nil {
		t.Fatal("first request unexpectedly succeeded")
	}
	mu.Lock()
	confirmNow := current.Add(time.Second)
	mu.Unlock()
	if _, err := db.ConfirmRegistrationVerification(ctx, domain.ConfirmRegistrationVerificationParams{Email: email, Code: "012345", CodePepper: pepper, EmailRateLimitKey: []byte("late-email-" + email), Now: confirmNow}); err != nil {
		t.Fatalf("newer reservation confirmation: %v", err)
	}
}

type registrationCodeSenderFunc func(context.Context, string, string, time.Time) error

func (f registrationCodeSenderFunc) SendRegistrationCode(ctx context.Context, email, code string, expiresAt time.Time) error {
	return f(ctx, email, code, expiresAt)
}

func registrationVerificationCodeHash(pepper, salt []byte, code string) []byte {
	mac := hmac.New(sha256.New, pepper)
	_, _ = mac.Write(salt)
	_, _ = mac.Write([]byte(code))
	return mac.Sum(nil)
}

func registrationVerificationIdentity() (string, string) {
	value := strings.ReplaceAll(uuid.NewString(), "-", "")
	return "verify_" + value[:20], "verify-" + value + "@example.test"
}

func TestIsolatedPostgresTestDSNRejectsUnsafeFallbackAndOverridesBeforeConnecting(t *testing.T) {
	for _, test := range []struct {
		name string
		dsn  string
	}{
		{name: "multi host fallback", dsn: "postgres://cloud:top-secret@127.0.0.1:15432,127.0.0.1:5432/cloud"},
		{name: "query host override", dsn: "postgres://cloud:top-secret@127.0.0.1:15432/cloud?host=127.0.0.1"},
		{name: "query service override", dsn: "postgres://cloud:top-secret@127.0.0.1:15432/cloud?service=unsafe"},
		{name: "standard conforming strings override", dsn: "postgres://cloud:top-secret@127.0.0.1:15432/cloud?sslmode=disable&standard_conforming_strings=off"},
		{name: "options override", dsn: "postgres://cloud:top-secret@127.0.0.1:15432/cloud?sslmode=disable&options=-c%20standard_conforming_strings%3Doff"},
		{name: "unknown runtime parameter", dsn: "postgres://cloud:top-secret@127.0.0.1:15432/cloud?sslmode=disable&application_name=unsafe"},
	} {
		t.Run(test.name, func(t *testing.T) {
			if _, err := validateIsolatedPostgresTestDSN(test.dsn); !errors.Is(err, migrate.ErrUnsafeDatabaseTarget) {
				t.Fatalf("validateIsolatedPostgresTestDSN() error = %v, want unsafe target", err)
			}
		})
	}
}

func isolatedPostgresTestDSN(t *testing.T) string {
	t.Helper()
	dsn := os.Getenv("CLOUD_API_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("CLOUD_API_TEST_DATABASE_URL is not set; skipping isolated PostgreSQL business lifecycle test")
	}
	if _, err := validateIsolatedPostgresTestDSN(dsn); err != nil {
		t.Fatal("CLOUD_API_TEST_DATABASE_URL must target the isolated 127.0.0.1:15432 test database")
	}
	return dsn
}

func validateIsolatedPostgresTestDSN(dsn string) (string, error) {
	if err := migrate.ValidateDatabaseURL(dsn, false); err != nil {
		return "", err
	}
	return dsn, nil
}

func integrationSession(userID, entitlementID uuid.UUID, installID string, expiresAt time.Time) domain.TranslationSession {
	return domain.TranslationSession{
		ID:            uuid.New(),
		UserID:        userID,
		EntitlementID: entitlementID,
		InstallID:     installID,
		JTI:           uuid.New(),
		ExpiresAt:     expiresAt,
	}
}

func repositoryMigrationDirectory(t *testing.T) string {
	t.Helper()
	directory, err := filepath.Abs(filepath.Join("..", "..", "migrations"))
	if err != nil {
		t.Fatal("resolve lifecycle migration directory")
	}
	if _, err := os.Stat(directory); err != nil {
		t.Fatal("inspect lifecycle migration directory")
	}
	return directory
}

func integrationEmail() string {
	return "integration-" + strings.ReplaceAll(uuid.NewString(), "-", "") + "@example.test"
}

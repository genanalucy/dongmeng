//go:build integration

package integration_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
)

func TestSharedInviteProvisionAndDisable(t *testing.T) {
	f := openSharedRedemptionFixture(t)
	_, hash, err := auth.RandomCode()
	if err != nil {
		t.Fatal("generate fixture")
	}
	cutoff := time.Date(2026, 12, 31, 16, 0, 0, 0, time.UTC)
	batch, err := f.db.CreateSharedRedemptionCode(context.Background(), f.admin, "internal test", hash, f.now, cutoff)
	if err != nil {
		t.Fatal("provision shared invitation")
	}
	var shared bool
	var deadline time.Time
	if err := f.raw.QueryRow(context.Background(), `SELECT shared,claim_expires_at FROM redemption_codes WHERE batch_id=$1`, batch).Scan(&shared, &deadline); err != nil || !shared || !deadline.Equal(cutoff) {
		t.Fatal("provisioned campaign does not match requested deadline")
	}
	user := f.user(t)
	e, err := f.db.RedeemCode(context.Background(), user, hash, f.now)
	if err != nil || !e.StartsAt.Equal(f.now) || !e.ExpiresAt.Equal(f.now.Add(365*24*time.Hour)) {
		t.Fatal("provisioned invitation not redeemable")
	}
	if err := f.db.DisableCodeBatch(context.Background(), f.admin, batch, f.now); err != nil {
		t.Fatal("disable provisioned campaign")
	}
	if _, err := f.db.RedeemCode(context.Background(), f.user(t), hash, f.now); !errors.Is(err, domain.ErrConflict) {
		t.Fatal("disabled campaign accepted")
	}
	if _, err := f.db.CreateSharedRedemptionCode(context.Background(), uuid.New(), "unauthorized", hash, f.now, cutoff); !errors.Is(err, domain.ErrForbidden) {
		t.Fatal("non-admin provisioning accepted")
	}
}

func TestSharedInviteDeadlineRecheckedAfterUserLock(t *testing.T) {
	f := openSharedRedemptionFixture(t)
	user := f.user(t)
	cutoff := time.Now().UTC().Add(1200 * time.Millisecond)
	code := f.code(t, true, cutoff)
	tx, err := f.raw.Begin(context.Background())
	if err != nil {
		t.Fatal("begin lock fixture")
	}
	defer tx.Rollback(context.Background())
	if _, err := tx.Exec(context.Background(), `SELECT id FROM users WHERE id=$1 FOR UPDATE`, user); err != nil {
		t.Fatal("lock user fixture")
	}
	result := make(chan error, 1)
	hash := mustHashCode(t, code.plain)
	go func() { _, err := f.db.RedeemCode(context.Background(), user, hash, f.now); result <- err }()
	// The caller's supplied time remains before expiry, while the DB wall clock passes it.
	time.Sleep(time.Until(cutoff) + 100*time.Millisecond)
	if err := tx.Commit(context.Background()); err != nil {
		t.Fatal("release user fixture")
	}
	select {
	case err := <-result:
		if !errors.Is(err, domain.ErrConflict) {
			t.Fatal("claim accepted after lock wait crossed deadline")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("claim stuck after releasing lock")
	}
	f.requireCounts(t, code.id, user, 0, 0)
}

func mustHashCode(t *testing.T, code string) []byte {
	t.Helper()
	hash, err := auth.HashRedemptionCode(code)
	if err != nil {
		t.Fatal("hash fixture code")
	}
	return hash
}

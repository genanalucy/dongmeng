package store

import (
	"context"
	"errors"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// UserAuthState resolves the per-request authorization truth for one
// access-token subject in a single round trip. It replaces the former
// enabled-only lookup: the persisted auth_version is compared against the
// token's claim so a committed password change invalidates every outstanding
// access token immediately, not just at natural expiry.
func (p *Postgres) UserAuthState(ctx context.Context, user uuid.UUID) (domain.UserAuthState, error) {
	var state domain.UserAuthState
	err := p.pool.QueryRow(ctx, `SELECT disabled_at IS NULL,auth_version FROM users WHERE id=$1`, user).Scan(&state.Enabled, &state.AuthVersion)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.UserAuthState{}, domain.ErrNotFound
	}
	return state, storeErr(err)
}

// UserPasswordHash reads only the persisted password credential of one
// account. The HTTP password-change boundary verifies the presented current
// password against this hash before any transaction opens, so the expensive
// argon2 verification never runs while holding row locks.
func (p *Postgres) UserPasswordHash(ctx context.Context, user uuid.UUID) (string, error) {
	var hash string
	err := p.pool.QueryRow(ctx, `SELECT password_hash FROM users WHERE id=$1`, user).Scan(&hash)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", domain.ErrNotFound
	}
	return hash, storeErr(err)
}

// ChangeAdminPassword atomically replaces one administrator's password
// credential, bumps the account's auth_version so every previously issued
// access token fails its next per-request version check, revokes all of the
// administrator's refresh tokens, and appends the immutable audit event.
//
// The caller verifies the presented current password against CurrentHash
// outside the transaction. Inside, the row is locked FOR UPDATE and the
// enabled/role guards are re-checked, then the update is still guarded by a
// compare-and-swap on the exact hash that was verified (RowsAffected must be
// 1). This closes the verify-then-write race: a concurrent password change
// that commits in between fails the CAS here instead of silently overwriting
// the newer credential. domain.ErrConflict reports that race, and
// domain.ErrForbidden reports an account that stopped being an enabled admin
// between the read and the lock.
func (p *Postgres) ChangeAdminPassword(ctx context.Context, x domain.AdminPasswordChangeParams) error {
	if x.AdminID == uuid.Nil || x.CurrentHash == "" || x.NewHash == "" || x.Now.IsZero() || x.CurrentHash == x.NewHash {
		return domain.ErrInvalid
	}
	return p.tx(ctx, func(t pgx.Tx) error {
		var enabled bool
		err := t.QueryRow(ctx, `SELECT disabled_at IS NULL FROM users WHERE id=$1 AND role='admin' FOR UPDATE`, x.AdminID).Scan(&enabled)
		if errors.Is(err, pgx.ErrNoRows) {
			return domain.ErrForbidden
		}
		if err != nil {
			return storeErr(err)
		}
		if !enabled {
			return domain.ErrForbidden
		}
		tag, err := t.Exec(ctx, `UPDATE users SET password_hash=$3,auth_version=auth_version+1 WHERE id=$1 AND role='admin' AND disabled_at IS NULL AND password_hash=$2`, x.AdminID, x.CurrentHash, x.NewHash)
		if err != nil {
			return storeErr(err)
		}
		if tag.RowsAffected() == 0 {
			return domain.ErrConflict
		}
		if _, err := t.Exec(ctx, `UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,$2) WHERE user_id=$1 AND revoked_at IS NULL`, x.AdminID, x.Now.UTC()); err != nil {
			return storeErr(err)
		}
		// The audit record deliberately carries no metadata: no password
		// material, hash, or credential detail may ever reach storage here.
		_, err = t.Exec(ctx, `INSERT INTO audit_logs(admin_id,action,target_type,target_id,metadata) VALUES($1,'admin.password.change','admin',$1,'{}'::jsonb)`, x.AdminID)
		return storeErr(err)
	})
}

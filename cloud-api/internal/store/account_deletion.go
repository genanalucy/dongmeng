package store

import (
	"context"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// Self-service deletion never stores a usable credential again. The sentinel
// satisfies the stored password_hash length constraint while being
// syntactically impossible for the password verifier to accept.
const deletedAccountPasswordHash = "deleted-account-password-hash-sentinel"

// deletedAccountEmail replaces the login email with a per-account, format
// valid, unique value. Because it is derived from the immutable user id, the
// original address is freed for future registration and can never collide
// with another deleted account. The value is lowercase and within the stored
// email length bounds, and it intentionally does not use the phone-reserved
// prefix convention.
func deletedAccountEmail(user uuid.UUID) string {
	return "deleted+" + user.String() + "@deleted.invalid"
}

// DeleteAccount tombstones and anonymizes one authenticated user's own
// account. Everything happens in a single transaction serialized with session
// governance and history writes by the shared per-user advisory lock, so a
// committed deletion can never interleave with a concurrent session creation,
// refresh rotation path is already fail-closed by the family revocation below,
// and history tombstones observe a consistent snapshot. The users row itself
// is never removed: audit_logs and redeemed redemption_codes reference it, so
// append-only audit history and redemption FK integrity are preserved.
func (p *Postgres) DeleteAccount(ctx context.Context, x domain.DeleteAccountParams) error {
	if x.UserID == uuid.Nil || x.Username == "" || x.Now.IsZero() {
		return domain.ErrInvalid
	}
	now := x.Now.UTC()
	return p.tx(ctx, func(t pgx.Tx) error {
		// Same per-user advisory key as session arbitration and history
		// writes: deletion, creation, disablement, and tombstoning share one
		// serialization domain and a single fixed lock order.
		if err := lockUserSessionArbitration(ctx, t, x.UserID); err != nil {
			return err
		}
		var role, username string
		var deletedAt *time.Time
		if err := t.QueryRow(ctx, `SELECT role,COALESCE(username,''),deleted_at FROM users WHERE id=$1 FOR UPDATE`, x.UserID).Scan(&role, &username, &deletedAt); err != nil {
			return storeErr(err)
		}
		// Admin accounts administer deletion; they must not be able to remove
		// their own credentials. The row-level check is authoritative even if
		// a token claim disagrees.
		if domain.Role(role) != domain.RoleUser {
			return domain.ErrForbidden
		}
		// Idempotent no-op: an already deleted account keeps its original
		// tombstone timestamps and anonymized identities.
		if deletedAt != nil {
			return nil
		}
		// The confirmation is compared only against this account's current
		// username. A legacy account without a username can never match a
		// valid parsed username and must complete identity first.
		if username != x.Username {
			return domain.ErrConflict
		}
		tag, err := t.Exec(ctx, `UPDATE users SET email=$2,username=NULL,phone=NULL,password_hash=$3,disabled_at=COALESCE(disabled_at,$4),deleted_at=COALESCE(deleted_at,$4) WHERE id=$1 AND deleted_at IS NULL`, x.UserID, deletedAccountEmail(x.UserID), deletedAccountPasswordHash, now)
		if err != nil {
			return storeErr(err)
		}
		if tag.RowsAffected() != 1 {
			return domain.ErrConflict
		}
		// Every refresh token family is revoked immediately, so no presented
		// refresh token can rotate again (replays already fail closed).
		if _, err := t.Exec(ctx, `UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,$2) WHERE user_id=$1 AND revoked_at IS NULL`, x.UserID, now); err != nil {
			return storeErr(err)
		}
		// Active translation sessions terminate with the existing
		// user-disabled reason, which Agent clients already understand;
		// naturally expired or previously terminated sessions keep their
		// original terminal state and reason.
		if _, err := t.Exec(ctx, `UPDATE translation_sessions SET revoked_at=COALESCE(revoked_at,$2), termination_reason=COALESCE(termination_reason,$3) WHERE user_id=$1 AND expires_at>$2 AND ended_at IS NULL AND revoked_at IS NULL`, x.UserID, now, string(domain.TerminationUserDisabled)); err != nil {
			return storeErr(err)
		}
		// Entitlements are revoked so no paid benefit outlives the account.
		if _, err := t.Exec(ctx, `UPDATE entitlements SET revoked_at=COALESCE(revoked_at,$2) WHERE user_id=$1 AND revoked_at IS NULL`, x.UserID, now); err != nil {
			return storeErr(err)
		}
		// Every encrypted history body is cleared and tombstoned; already
		// tombstoned rows keep their original timestamps.
		if _, err := t.Exec(ctx, `UPDATE history_turns SET nonce=NULL,ciphertext=NULL,deleted_at=COALESCE(deleted_at,$2) WHERE user_id=$1 AND deleted_at IS NULL`, x.UserID, now); err != nil {
			return storeErr(err)
		}
		_, err = t.Exec(ctx, `UPDATE history_sessions SET deleted_at=COALESCE(deleted_at,$2) WHERE user_id=$1 AND deleted_at IS NULL`, x.UserID, now)
		return storeErr(err)
	})
}

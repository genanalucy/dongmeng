package store

import (
	"context"
	"errors"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

const adminSetupAdvisoryLock int64 = 3947182401

// CreateAdminSetupChallenge persists a one-shot setup-token digest. The
// caller owns plaintext disclosure and must print it only once.
func (p *Postgres) CreateAdminSetupChallenge(ctx context.Context, x domain.CreateAdminSetupChallengeParams) error {
	if p == nil || p.pool == nil || len(x.TokenHash) != 32 || x.Now.IsZero() || !x.ExpiresAt.After(x.Now) {
		return domain.ErrInvalid
	}
	_, err := p.pool.Exec(ctx, `INSERT INTO admin_setup_challenges(token_hash,expires_at,created_at) VALUES($1,$2,$3)`, x.TokenHash, x.ExpiresAt.UTC(), x.Now.UTC())
	return storeErr(err)
}

// AdminSetupEnabled reports only whether at least one setup challenge can be
// redeemed now. It intentionally reveals no challenge identifiers or timings.
func (p *Postgres) AdminSetupEnabled(ctx context.Context, now time.Time) (bool, error) {
	if p == nil || p.pool == nil || now.IsZero() {
		return false, domain.ErrInvalid
	}
	var enabled bool
	err := p.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM admin_setup_challenges WHERE used_at IS NULL AND expires_at > $1)`, now.UTC()).Scan(&enabled)
	return enabled, storeErr(err)
}

// CompleteAdminSetup atomically consumes a valid offline challenge, installs
// the new administrator, disables every prior admin, revokes their refresh
// tokens, and records an immutable audit event. The shared advisory lock
// serializes the no-row case as well as competing redemptions.
func (p *Postgres) CompleteAdminSetup(ctx context.Context, x domain.AdminSetupParams) (domain.User, error) {
	var user domain.User
	if len(x.TokenHash) != 32 || x.Username == "" || x.Email == "" || x.PasswordHash == "" || x.Now.IsZero() {
		return user, domain.ErrInvalid
	}
	err := p.tx(ctx, func(t pgx.Tx) error {
		if _, err := t.Exec(ctx, `SELECT pg_advisory_xact_lock($1)`, adminSetupAdvisoryLock); err != nil {
			return err
		}
		var challengeID uuid.UUID
		err := t.QueryRow(ctx, `SELECT id FROM admin_setup_challenges WHERE token_hash=$1 AND used_at IS NULL AND expires_at>$2 FOR UPDATE`, x.TokenHash, x.Now.UTC()).Scan(&challengeID)
		if errors.Is(err, pgx.ErrNoRows) {
			return domain.ErrSetupUnavailable
		}
		if err != nil {
			return storeErr(err)
		}
		// Select prior admins before inserting the replacement, so the new
		// account cannot be disabled or have its future refresh sessions revoked.
		if _, err := t.Exec(ctx, `UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,$1) WHERE user_id IN (SELECT id FROM users WHERE role='admin') AND revoked_at IS NULL`, x.Now.UTC()); err != nil {
			return err
		}
		// Release every old administrator identity before inserting the new
		// account. These values are deterministic from the immutable user ID,
		// valid under the normalized email/username constraints, unique across
		// old admins, and intentionally retain no recoverable original identity.
		if _, err := t.Exec(ctx, `UPDATE users
			SET disabled_at=COALESCE(disabled_at,$1),
				email=lower('admin-' || replace(id::text,'-','') || '@tombstone.invalid'),
				username='retired_' || substr(replace(id::text,'-',''),1,24),
				phone=NULL
			WHERE role='admin'`, x.Now.UTC()); err != nil {
			return err
		}
		err = t.QueryRow(ctx, `INSERT INTO users(email,username,password_hash,role,created_at) VALUES($1,$2,$3,'admin',$4) RETURNING id,username,'',email,role,created_at`, x.Email, x.Username, x.PasswordHash, x.Now.UTC()).Scan(&user.ID, &user.Username, &user.Phone, &user.Email, &user.Role, &user.CreatedAt)
		if err != nil {
			return storeErr(err)
		}
		tag, err := t.Exec(ctx, `UPDATE admin_setup_challenges SET used_at=$2 WHERE id=$1 AND used_at IS NULL`, challengeID, x.Now.UTC())
		if err != nil || tag.RowsAffected() != 1 {
			if err != nil {
				return err
			}
			return domain.ErrSetupUnavailable
		}
		_, err = t.Exec(ctx, `INSERT INTO audit_logs(admin_id,action,target_type,target_id,metadata) VALUES($1,'admin.setup.replace','admin',$1,jsonb_build_object('challenge_id',$2::uuid))`, user.ID, challengeID)
		return err
	})
	return user, storeErr(err)
}

package store

import (
	"context"
	"errors"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// BootstrapAdmin creates the first administrator without opening a public bootstrap route.
// The transaction-wide advisory lock makes concurrent bootstrap attempts fail closed.
func (p *Postgres) BootstrapAdmin(ctx context.Context, username, email, passwordHash string, now time.Time) (domain.User, error) {
	var user domain.User
	err := p.tx(ctx, func(t pgx.Tx) error {
		if _, err := t.Exec(ctx, `SELECT pg_advisory_xact_lock(1684957579)`); err != nil {
			return err
		}
		err := t.QueryRow(ctx, `SELECT id,COALESCE(username,''),'',email,role,created_at FROM users WHERE role='admin' ORDER BY created_at,id LIMIT 1`).Scan(&user.ID, &user.Username, &user.Phone, &user.Email, &user.Role, &user.CreatedAt)
		if err == nil {
			if user.Username == username && user.Email == email {
				return nil
			}
			return domain.ErrConflict
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		return t.QueryRow(ctx, `INSERT INTO users(email,username,password_hash,role,created_at) VALUES($1,$2,$3,'admin',$4) RETURNING id,username,'',email,role,created_at`, email, username, passwordHash, now.UTC()).Scan(&user.ID, &user.Username, &user.Phone, &user.Email, &user.Role, &user.CreatedAt)
	})
	return user, storeErr(err)
}

func (p *Postgres) RevokeTranslationSessionByAdmin(ctx context.Context, admin, user, sessionID uuid.UUID, now time.Time) error {
	return p.tx(ctx, func(t pgx.Tx) error {
		if err := lockUserSessionArbitration(ctx, t, user); err != nil {
			return err
		}
		tag, err := t.Exec(ctx, `UPDATE translation_sessions SET revoked_at=$3,termination_reason=$4 WHERE id=$1 AND user_id=$2 AND ended_at IS NULL AND revoked_at IS NULL`, sessionID, user, now.UTC(), string(domain.TerminationRevoked))
		if err != nil {
			return storeErr(err)
		}
		if tag.RowsAffected() == 0 {
			return domain.ErrNotFound
		}
		_, err = t.Exec(ctx, `INSERT INTO audit_logs(admin_id,action,target_type,target_id,metadata) VALUES($1,'translation_session.revoke','translation_session',$2,jsonb_build_object('user_id',$3::uuid))`, admin, sessionID, user)
		return storeErr(err)
	})
}

func (p *Postgres) GrantEntitlementByAdmin(ctx context.Context, admin, user uuid.UUID, now time.Time) (domain.Entitlement, error) {
	var e domain.Entitlement
	err := p.tx(ctx, func(t pgx.Tx) error {
		if err := t.QueryRow(ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, user).Scan(new(uuid.UUID)); err != nil {
			return storeErr(err)
		}
		err := t.QueryRow(ctx, `WITH start AS (SELECT GREATEST($2::timestamptz,COALESCE((SELECT max(expires_at) FROM entitlements WHERE user_id=$1 AND revoked_at IS NULL),$2::timestamptz)) AS at) INSERT INTO entitlements(user_id,kind,starts_at,expires_at) SELECT $1,'package',at,at+interval '365 days' FROM start RETURNING id,user_id,kind,starts_at,expires_at`, user, now.UTC()).Scan(&e.ID, &e.UserID, &e.Kind, &e.StartsAt, &e.ExpiresAt)
		if err != nil {
			return err
		}
		_, err = t.Exec(ctx, `INSERT INTO audit_logs(admin_id,action,target_type,target_id,metadata) VALUES($1,'entitlement.grant','entitlement',$2,jsonb_build_object('user_id',$3::uuid))`, admin, e.ID, user)
		return err
	})
	return e, storeErr(err)
}
func (p *Postgres) RevokeEntitlementByAdmin(ctx context.Context, admin, user, id uuid.UUID, now time.Time) error {
	return p.tx(ctx, func(t pgx.Tx) error {
		// Admin revocation shares the per-user arbitration lock with session
		// creation (same lock, taken first) for the same reason as the
		// self-service revocation path.
		if err := lockUserSessionArbitration(ctx, t, user); err != nil {
			return err
		}
		tag, err := t.Exec(ctx, `UPDATE entitlements SET revoked_at=COALESCE(revoked_at,$3) WHERE id=$1 AND user_id=$2`, id, user, now.UTC())
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return domain.ErrNotFound
		}
		// The revoked entitlement's still-active sessions terminate in the same
		// transaction with the entitlement-revocation reason.
		if _, err := t.Exec(ctx, `UPDATE translation_sessions SET revoked_at=COALESCE(revoked_at,$4), termination_reason=COALESCE(termination_reason,$5) WHERE user_id=$1 AND entitlement_id=$2 AND expires_at>$3 AND ended_at IS NULL AND revoked_at IS NULL`, user, id, now.UTC(), now.UTC(), string(domain.TerminationEntitlementRevoked)); err != nil {
			return err
		}
		_, err = t.Exec(ctx, `INSERT INTO audit_logs(admin_id,action,target_type,target_id,metadata) VALUES($1,'entitlement.revoke','entitlement',$2,jsonb_build_object('user_id',$3::uuid))`, admin, id, user)
		return err
	})
}

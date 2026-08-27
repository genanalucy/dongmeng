package store

import (
	"context"
	"errors"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

func (p *Postgres) CreateAuthorizedTranslationSession(ctx context.Context, s domain.TranslationSession, now time.Time) error {
	return p.CreateSession(ctx, s, now)
}
func (p *Postgres) CreateAuthorizedTranslationSessionWithLimit(ctx context.Context, s domain.TranslationSession, now time.Time, limit int) error {
	if limit != 1 {
		return domain.ErrInvalid
	}
	return p.CreateSession(ctx, s, now)
}
func (p *Postgres) EndTranslationSession(ctx context.Context, user, id uuid.UUID, now time.Time) error {
	return p.setSessionTerminal(ctx, user, id, "ended_at", now)
}
func (p *Postgres) RevokeTranslationSession(ctx context.Context, user, id uuid.UUID, now time.Time) error {
	return p.setSessionTerminal(ctx, user, id, "revoked_at", now)
}
func (p *Postgres) setSessionTerminal(ctx context.Context, user, id uuid.UUID, column string, now time.Time) error {
	if column != "ended_at" && column != "revoked_at" {
		return domain.ErrInvalid
	}
	tag, err := p.pool.Exec(ctx, "UPDATE translation_sessions SET "+column+"=COALESCE("+column+",$3) WHERE id=$1 AND user_id=$2", id, user, now.UTC())
	if err != nil {
		return storeErr(err)
	}
	if tag.RowsAffected() == 0 {
		return domain.ErrNotFound
	}
	return nil
}
func (p *Postgres) AuthorizeTranslationSession(ctx context.Context, user, session, entitlement, jti uuid.UUID, now time.Time) error {
	var one int
	err := p.pool.QueryRow(ctx, `SELECT 1 FROM translation_sessions s JOIN entitlements e ON e.id=s.entitlement_id AND e.user_id=s.user_id WHERE s.id=$1 AND s.user_id=$2 AND s.entitlement_id=$3 AND s.jti=$4 AND s.expires_at>$5 AND s.ended_at IS NULL AND s.revoked_at IS NULL AND e.revoked_at IS NULL AND e.starts_at<=$5 AND e.expires_at>$5`, session, user, entitlement, jti, now.UTC()).Scan(&one)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.ErrUnauthorized
	}
	return storeErr(err)
}
func (p *Postgres) StackAnnualEntitlement(ctx context.Context, user uuid.UUID, now time.Time) (domain.Entitlement, error) {
	var e domain.Entitlement
	err := p.tx(ctx, func(t pgx.Tx) error {
		if _, err := t.Exec(ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, user); err != nil {
			return err
		}
		return t.QueryRow(ctx, `WITH start AS (SELECT GREATEST($2::timestamptz,COALESCE((SELECT max(expires_at) FROM entitlements WHERE user_id=$1 AND revoked_at IS NULL),$2::timestamptz)) AS at) INSERT INTO entitlements(user_id,kind,starts_at,expires_at) SELECT $1,'package',at,at+interval '365 days' FROM start RETURNING id,user_id,kind,starts_at,expires_at`, user, now.UTC()).Scan(&e.ID, &e.UserID, &e.Kind, &e.StartsAt, &e.ExpiresAt)
	})
	return e, storeErr(err)
}
func (p *Postgres) RevokeEntitlement(ctx context.Context, user, id uuid.UUID, now time.Time) error {
	tag, err := p.pool.Exec(ctx, `UPDATE entitlements SET revoked_at=COALESCE(revoked_at,$3) WHERE id=$1 AND user_id=$2`, id, user, now.UTC())
	if err != nil {
		return storeErr(err)
	}
	if tag.RowsAffected() == 0 {
		return domain.ErrNotFound
	}
	return nil
}
func (p *Postgres) UserEnabled(ctx context.Context, user uuid.UUID) (bool, error) {
	var enabled bool
	err := p.pool.QueryRow(ctx, `SELECT disabled_at IS NULL FROM users WHERE id=$1`, user).Scan(&enabled)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, domain.ErrNotFound
	}
	return enabled, storeErr(err)
}

func (p *Postgres) DisableUser(ctx context.Context, admin, user uuid.UUID, now time.Time) error {
	return p.tx(ctx, func(t pgx.Tx) error {
		tag, err := t.Exec(ctx, `UPDATE users SET disabled_at=COALESCE(disabled_at,$2) WHERE id=$1`, user, now.UTC())
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return domain.ErrNotFound
		}
		_, err = t.Exec(ctx, `INSERT INTO audit_logs(admin_id,action,target_type,target_id,metadata) VALUES($1,'user.disable','user',$2,'{}')`, admin, user)
		return err
	})
}
func (p *Postgres) CreateCodeBatch(ctx context.Context, x domain.CreateBatchParams) (domain.CodeBatch, error) {
	var b domain.CodeBatch
	err := p.tx(ctx, func(t pgx.Tx) error {
		err := t.QueryRow(ctx, `INSERT INTO code_batches(name,duration_days,created_by,created_by_role,created_at) VALUES($1,$2,$3,'admin',$4) RETURNING id,name,duration_days,created_by,created_at`, x.Name, x.DurationDays, x.AdminID, x.Now.UTC()).Scan(&b.ID, &b.Name, &b.DurationDays, &b.CreatedBy, &b.CreatedAt)
		if err != nil {
			return storeErr(err)
		}
		for _, h := range x.CodeHashes {
			if _, err = t.Exec(ctx, `INSERT INTO redemption_codes(batch_id,code_hash) VALUES($1,$2)`, b.ID, h); err != nil {
				return storeErr(err)
			}
		}
		_, err = t.Exec(ctx, `INSERT INTO audit_logs(admin_id,action,target_type,target_id,metadata) VALUES($1,'code_batch.create','code_batch',$2,jsonb_build_object('count',$3::int))`, x.AdminID, b.ID, len(x.CodeHashes))
		return err
	})
	return b, err
}

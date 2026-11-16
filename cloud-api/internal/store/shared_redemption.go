package store

import (
	"context"
	"crypto/sha256"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// CreateSharedRedemptionCode is an operator-only provisioning boundary. The
// existing admin batch-disable API remains the emergency stop mechanism.
func (p *Postgres) CreateSharedRedemptionCode(ctx context.Context, admin uuid.UUID, name string, hash []byte, now, deadline time.Time) (uuid.UUID, error) {
	if admin == uuid.Nil || len(hash) != sha256.Size || !deadline.After(now) {
		return uuid.Nil, domain.ErrInvalid
	}
	input, err := domain.ParseCreateBatchInput(name, 1)
	if err != nil {
		return uuid.Nil, err
	}
	var batch uuid.UUID
	err = p.tx(ctx, func(tx pgx.Tx) error {
		var enabled bool
		if err := tx.QueryRow(ctx, `SELECT disabled_at IS NULL FROM users WHERE id=$1 AND role='admin' FOR SHARE`, admin).Scan(&enabled); err != nil {
			return domain.ErrForbidden
		}
		if !enabled {
			return domain.ErrForbidden
		}
		if err := tx.QueryRow(ctx, `INSERT INTO code_batches(name,duration_days,created_by,created_at) VALUES($1,365,$2,$3) RETURNING id`, input.Name, admin, now.UTC()).Scan(&batch); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO redemption_codes(batch_id,code_hash,shared,claim_expires_at,created_at) VALUES($1,$2,true,$3,$4)`, batch, hash, deadline.UTC(), now.UTC()); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO audit_logs(admin_id,action,target_type,target_id,metadata) VALUES($1,'code_batch.create_shared','code_batch',$2,jsonb_build_object('duration_days',365,'claim_expires_at',$3::timestamptz))`, admin, batch, deadline.UTC())
		return err
	})
	return batch, storeErr(err)
}

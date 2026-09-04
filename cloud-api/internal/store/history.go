package store

import (
	"context"
	"errors"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// scanHistorySession reads a history session row. Tombstone timestamps are
// metadata, never secret material.
func scanHistorySession(row pgx.Row) (domain.HistorySession, error) {
	var s domain.HistorySession
	err := row.Scan(&s.ID, &s.UserID, &s.CreatedAt, &s.DeletedAt)
	return s, storeErr(err)
}

// scanHistoryTurn reads one encrypted turn row. Ciphertext and nonce are
// handled as opaque bytes and must never be logged.
func scanHistoryTurn(row pgx.Row) (domain.EncryptedTurn, error) {
	var t domain.EncryptedTurn
	err := row.Scan(&t.ID, &t.UserID, &t.SessionID, &t.KeyVersion, &t.Nonce, &t.Ciphertext, &t.CreatedAt, &t.DeletedAt)
	return t, storeErr(err)
}

const historySessionColumns = `id,user_id,created_at,deleted_at`
const historyTurnColumns = `id,user_id,session_id,key_version,nonce,ciphertext,created_at,deleted_at`

// lockHistoryUser serializes history writes per user so cap checks and
// tombstones observe a consistent snapshot without a global lock.
func lockHistoryUser(ctx context.Context, t pgx.Tx, user uuid.UUID) error {
	_, err := t.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))`, user)
	return storeErr(err)
}

// CreateHistorySession persists an owner-scoped history session. Retrying the
// same UUID is idempotent while the session is live; a tombstoned session ID
// can never be resurrected, and foreign IDs are reported as missing.
func (p *Postgres) CreateHistorySession(ctx context.Context, x domain.CreateHistorySessionParams) (domain.HistorySession, error) {
	if err := x.Validate(); err != nil {
		return domain.HistorySession{}, err
	}
	var session domain.HistorySession
	err := p.tx(ctx, func(t pgx.Tx) error {
		if err := lockHistoryUser(ctx, t, x.UserID); err != nil {
			return err
		}
		existing, err := scanHistorySession(t.QueryRow(ctx, `SELECT `+historySessionColumns+` FROM history_sessions WHERE id=$1`, x.ID))
		switch {
		case err == nil:
			switch {
			case existing.UserID != x.UserID:
				return domain.ErrNotFound
			case existing.DeletedAt != nil:
				// Tombstone wins: deleted sessions cannot be re-created.
				return domain.ErrConflict
			default:
				session = existing
				return nil
			}
		case !errors.Is(err, domain.ErrNotFound):
			return err
		}
		var live int
		if err := t.QueryRow(ctx, `SELECT count(*) FROM history_sessions WHERE user_id=$1 AND deleted_at IS NULL`, x.UserID).Scan(&live); err != nil {
			return storeErr(err)
		}
		if live >= domain.HistoryMaxLiveSessions {
			return domain.ErrHistoryLimitExceeded
		}
		created, err := scanHistorySession(t.QueryRow(ctx, `INSERT INTO history_sessions(id,user_id,created_at) VALUES($1,$2,$3) RETURNING `+historySessionColumns, x.ID, x.UserID, x.Now.UTC()))
		session = created
		return err
	})
	return session, err
}

// AppendHistoryTurn stores one completed, already-encrypted turn inside an
// owner-scoped live session. Retrying the same turn UUID is idempotent while
// the turn is live and in the same session; tombstoned turns or sessions
// always reject the append.
func (p *Postgres) AppendHistoryTurn(ctx context.Context, x domain.AppendHistoryTurnParams) (domain.EncryptedTurn, error) {
	if err := x.Validate(); err != nil {
		return domain.EncryptedTurn{}, err
	}
	var turn domain.EncryptedTurn
	err := p.tx(ctx, func(t pgx.Tx) error {
		if err := lockHistoryUser(ctx, t, x.UserID); err != nil {
			return err
		}
		existing, err := scanHistoryTurn(t.QueryRow(ctx, `SELECT `+historyTurnColumns+` FROM history_turns WHERE id=$1`, x.ID))
		switch {
		case err == nil:
			switch {
			case existing.UserID != x.UserID:
				return domain.ErrNotFound
			case existing.DeletedAt != nil:
				// Tombstone wins: cleared turns cannot be rewritten.
				return domain.ErrConflict
			case existing.SessionID != x.SessionID:
				return domain.ErrConflict
			default:
				turn = existing
				return nil
			}
		case !errors.Is(err, domain.ErrNotFound):
			return err
		}
		if _, err := scanHistorySession(t.QueryRow(ctx, `SELECT `+historySessionColumns+` FROM history_sessions WHERE id=$1 AND user_id=$2 AND deleted_at IS NULL`, x.SessionID, x.UserID)); err != nil {
			if errors.Is(err, domain.ErrNotFound) {
				return domain.ErrNotFound
			}
			return err
		}
		var live int
		if err := t.QueryRow(ctx, `SELECT count(*) FROM history_turns WHERE user_id=$1 AND deleted_at IS NULL`, x.UserID).Scan(&live); err != nil {
			return storeErr(err)
		}
		if live >= domain.HistoryMaxLiveTurns {
			return domain.ErrHistoryLimitExceeded
		}
		created, err := scanHistoryTurn(t.QueryRow(ctx, `INSERT INTO history_turns(id,user_id,session_id,key_version,nonce,ciphertext,created_at) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING `+historyTurnColumns, x.ID, x.UserID, x.SessionID, x.KeyVersion, x.Nonce, x.Ciphertext, x.Now.UTC()))
		turn = created
		return err
	})
	return turn, err
}

// HistorySessionByID returns one owned, live history session. Tombstoned and
// foreign sessions are reported as missing.
func (p *Postgres) HistorySessionByID(ctx context.Context, user, id uuid.UUID) (domain.HistorySession, error) {
	if user == uuid.Nil || id == uuid.Nil {
		return domain.HistorySession{}, domain.ErrInvalid
	}
	return scanHistorySession(p.pool.QueryRow(ctx, `SELECT `+historySessionColumns+` FROM history_sessions WHERE id=$1 AND user_id=$2 AND deleted_at IS NULL`, id, user))
}

// ListHistorySessions returns owned live sessions, newest first.
func (p *Postgres) ListHistorySessions(ctx context.Context, user uuid.UUID, limit, offset int) ([]domain.HistorySession, error) {
	if user == uuid.Nil || limit < 1 || limit > 1000 || offset < 0 {
		return nil, domain.ErrInvalid
	}
	rows, err := p.pool.Query(ctx, `SELECT `+historySessionColumns+` FROM history_sessions WHERE user_id=$1 AND deleted_at IS NULL ORDER BY created_at DESC,id DESC LIMIT $2 OFFSET $3`, user, limit, offset)
	if err != nil {
		return nil, storeErr(err)
	}
	defer rows.Close()
	out := []domain.HistorySession{}
	for rows.Next() {
		s, err := scanHistorySession(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, storeErr(rows.Err())
}

// ListHistoryTurns returns the live encrypted turns of one owned live
// session, newest first. Ciphertext is returned for owner or admin
// decryption and must never be logged.
func (p *Postgres) ListHistoryTurns(ctx context.Context, user, session uuid.UUID, limit, offset int) ([]domain.EncryptedTurn, error) {
	if user == uuid.Nil || session == uuid.Nil || limit < 1 || limit > 1000 || offset < 0 {
		return nil, domain.ErrInvalid
	}
	if _, err := p.HistorySessionByID(ctx, user, session); err != nil {
		return nil, err
	}
	rows, err := p.pool.Query(ctx, `SELECT `+historyTurnColumns+` FROM history_turns WHERE user_id=$1 AND session_id=$2 AND deleted_at IS NULL ORDER BY created_at DESC,id DESC LIMIT $3 OFFSET $4`, user, session, limit, offset)
	if err != nil {
		return nil, storeErr(err)
	}
	defer rows.Close()
	out := []domain.EncryptedTurn{}
	for rows.Next() {
		turn, err := scanHistoryTurn(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, turn)
	}
	return out, storeErr(rows.Err())
}

// DeleteHistorySession tombstones an owned session and clears the stored
// ciphertext of every live turn in it. Deleting is idempotent: an already
// tombstoned session stays deleted with its original timestamp, and the
// tombstone always wins over concurrent appends serialized by the per-user
// lock.
func (p *Postgres) DeleteHistorySession(ctx context.Context, user, id uuid.UUID, now time.Time) error {
	if user == uuid.Nil || id == uuid.Nil || now.IsZero() {
		return domain.ErrInvalid
	}
	return p.tx(ctx, func(t pgx.Tx) error {
		if err := lockHistoryUser(ctx, t, user); err != nil {
			return err
		}
		session, err := scanHistorySession(t.QueryRow(ctx, `SELECT `+historySessionColumns+` FROM history_sessions WHERE id=$1 AND user_id=$2 FOR UPDATE`, id, user))
		if err != nil {
			return err
		}
		if session.DeletedAt != nil {
			return nil
		}
		deletedAt := now.UTC()
		if _, err := t.Exec(ctx, `UPDATE history_turns SET nonce=NULL,ciphertext=NULL,deleted_at=$3 WHERE session_id=$1 AND user_id=$2 AND deleted_at IS NULL`, id, user, deletedAt); err != nil {
			return storeErr(err)
		}
		_, err = t.Exec(ctx, `UPDATE history_sessions SET deleted_at=$3 WHERE id=$1 AND user_id=$2`, id, user, deletedAt)
		return storeErr(err)
	})
}

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
	err := row.Scan(&s.ID, &s.UserID, &s.TitleKeyVersion, &s.TitleNonce, &s.TitleCiphertext, &s.TitleUpdatedAt, &s.CreatedAt, &s.DeletedAt)
	return s, storeErr(err)
}

// scanHistoryTurn reads one encrypted turn row. Ciphertext and nonce are
// handled as opaque bytes and must never be logged.
func scanHistoryTurn(row pgx.Row) (domain.EncryptedTurn, error) {
	var t domain.EncryptedTurn
	err := row.Scan(&t.ID, &t.UserID, &t.SessionID, &t.KeyVersion, &t.Nonce, &t.Ciphertext, &t.CreatedAt, &t.DeletedAt)
	return t, storeErr(err)
}

const historySessionColumns = `id,user_id,title_key_version,title_nonce,title_ciphertext,title_updated_at,created_at,deleted_at`
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

// ApplyHistoryOperation atomically applies one owner mutation, writes its
// ordered change record, and remembers the operation UUID. Tombstones are
// retained so an offline upsert can never resurrect a deleted session.
func (p *Postgres) ApplyHistoryOperation(ctx context.Context, x domain.HistoryOperationParams) (int64, error) {
	if x.OperationID == uuid.Nil || x.UserID == uuid.Nil || x.SessionID == uuid.Nil || x.Now.IsZero() {
		return 0, domain.ErrInvalid
	}
	if x.Kind != "turn.upsert" && x.Kind != "session.delete" && x.Kind != "title.patch" {
		return 0, domain.ErrInvalid
	}
	if x.Kind == "turn.upsert" && (x.TurnID == nil || *x.TurnID == uuid.Nil || x.KeyVersion < 1 || domain.ValidateHistoryCiphertext(x.Nonce, x.Ciphertext) != nil) {
		return 0, domain.ErrInvalid
	}
	if x.Kind == "title.patch" && (x.KeyVersion < 1 || domain.ValidateHistoryCiphertext(x.TitleNonce, x.TitleCiphertext) != nil) {
		return 0, domain.ErrInvalid
	}
	var cursor int64
	err := p.tx(ctx, func(t pgx.Tx) error {
		if err := lockHistoryUser(ctx, t, x.UserID); err != nil {
			return err
		}
		var existingUser uuid.UUID
		err := t.QueryRow(ctx, `SELECT user_id FROM history_operations WHERE operation_id=$1`, x.OperationID).Scan(&existingUser)
		if err == nil {
			if existingUser != x.UserID {
				return domain.ErrNotFound
			}
			return t.QueryRow(ctx, `SELECT cursor FROM history_operations WHERE operation_id=$1`, x.OperationID).Scan(&cursor)
		}
		if !errors.Is(storeErr(err), domain.ErrNotFound) {
			return storeErr(err)
		}
		now := x.Now.UTC()
		var actionTurn any
		if x.TurnID != nil {
			actionTurn = *x.TurnID
		}
		switch x.Kind {
		case "turn.upsert":
			var owner uuid.UUID
			err := t.QueryRow(ctx, `SELECT user_id FROM history_sessions WHERE id=$1 FOR UPDATE`, x.SessionID).Scan(&owner)
			if err != nil && !errors.Is(storeErr(err), domain.ErrNotFound) {
				return storeErr(err)
			}
			if err == nil && owner != x.UserID {
				return domain.ErrNotFound
			}
			if err == nil {
				var deleted *time.Time
				if err := t.QueryRow(ctx, `SELECT deleted_at FROM history_sessions WHERE id=$1`, x.SessionID).Scan(&deleted); err != nil {
					return storeErr(err)
				}
				if deleted != nil {
					return domain.ErrConflict
				}
			} else {
				var live int
				if err := t.QueryRow(ctx, `SELECT count(*) FROM history_sessions WHERE user_id=$1 AND deleted_at IS NULL`, x.UserID).Scan(&live); err != nil {
					return storeErr(err)
				}
				if live >= domain.HistoryMaxLiveSessions {
					return domain.ErrHistoryLimitExceeded
				}
				if _, err := t.Exec(ctx, `INSERT INTO history_sessions(id,user_id,created_at) VALUES($1,$2,$3)`, x.SessionID, x.UserID, now); err != nil {
					return storeErr(err)
				}
			}
			var existingSession uuid.UUID
			err = t.QueryRow(ctx, `SELECT session_id FROM history_turns WHERE id=$1`, *x.TurnID).Scan(&existingSession)
			if err == nil {
				if existingSession != x.SessionID {
					return domain.ErrConflict
				}
			} else if errors.Is(storeErr(err), domain.ErrNotFound) {
				var live int
				if err := t.QueryRow(ctx, `SELECT count(*) FROM history_turns WHERE user_id=$1 AND deleted_at IS NULL`, x.UserID).Scan(&live); err != nil {
					return storeErr(err)
				}
				if live >= domain.HistoryMaxLiveTurns {
					return domain.ErrHistoryLimitExceeded
				}
				if _, err := t.Exec(ctx, `INSERT INTO history_turns(id,user_id,session_id,key_version,nonce,ciphertext,created_at) VALUES($1,$2,$3,$4,$5,$6,$7)`, *x.TurnID, x.UserID, x.SessionID, x.KeyVersion, x.Nonce, x.Ciphertext, now); err != nil {
					return storeErr(err)
				}
			} else {
				return storeErr(err)
			}
		case "session.delete":
			var owner uuid.UUID
			err := t.QueryRow(ctx, `SELECT user_id FROM history_sessions WHERE id=$1 FOR UPDATE`, x.SessionID).Scan(&owner)
			if err == nil && owner != x.UserID {
				return domain.ErrNotFound
			}
			if err != nil && !errors.Is(storeErr(err), domain.ErrNotFound) {
				return storeErr(err)
			}
			if errors.Is(storeErr(err), domain.ErrNotFound) {
				if _, err := t.Exec(ctx, `INSERT INTO history_sessions(id,user_id,created_at,deleted_at) VALUES($1,$2,$3,$3)`, x.SessionID, x.UserID, now); err != nil {
					return storeErr(err)
				}
			} else {
				if _, err := t.Exec(ctx, `UPDATE history_turns SET nonce=NULL,ciphertext=NULL,deleted_at=COALESCE(deleted_at,$3) WHERE session_id=$1 AND user_id=$2 AND deleted_at IS NULL`, x.SessionID, x.UserID, now); err != nil {
					return storeErr(err)
				}
				if _, err := t.Exec(ctx, `UPDATE history_sessions SET title_key_version=NULL,title_nonce=NULL,title_ciphertext=NULL,title_updated_at=NULL,deleted_at=COALESCE(deleted_at,$3) WHERE id=$1 AND user_id=$2`, x.SessionID, x.UserID, now); err != nil {
					return storeErr(err)
				}
			}
		case "title.patch":
			var deleted *time.Time
			err := t.QueryRow(ctx, `SELECT deleted_at FROM history_sessions WHERE id=$1 AND user_id=$2 FOR UPDATE`, x.SessionID, x.UserID).Scan(&deleted)
			if err != nil {
				return storeErr(err)
			}
			if deleted != nil {
				return domain.ErrConflict
			}
			if _, err := t.Exec(ctx, `UPDATE history_sessions SET title_key_version=$3,title_nonce=$4,title_ciphertext=$5,title_updated_at=$6 WHERE id=$1 AND user_id=$2`, x.SessionID, x.UserID, x.KeyVersion, x.TitleNonce, x.TitleCiphertext, now); err != nil {
				return storeErr(err)
			}
		}
		if err := t.QueryRow(ctx, `INSERT INTO history_changes(user_id,session_id,turn_id,action,created_at) VALUES($1,$2,$3,$4,$5) RETURNING cursor`, x.UserID, x.SessionID, actionTurn, x.Kind, now).Scan(&cursor); err != nil {
			return storeErr(err)
		}
		_, err = t.Exec(ctx, `INSERT INTO history_operations(operation_id,user_id,cursor,created_at) VALUES($1,$2,$3,$4)`, x.OperationID, x.UserID, cursor, now)
		return storeErr(err)
	})
	return cursor, err
}

func (p *Postgres) ListHistoryChanges(ctx context.Context, user uuid.UUID, after int64, limit int) ([]domain.HistoryChange, error) {
	if user == uuid.Nil || after < 0 || limit < 1 || limit > 100 {
		return nil, domain.ErrInvalid
	}
	rows, err := p.pool.Query(ctx, `SELECT cursor,session_id,turn_id FROM history_changes WHERE user_id=$1 AND cursor>$2 ORDER BY cursor ASC LIMIT $3`, user, after, limit)
	if err != nil {
		return nil, storeErr(err)
	}
	defer rows.Close()
	out := []domain.HistoryChange{}
	for rows.Next() {
		var value domain.HistoryChange
		if err := rows.Scan(&value.Cursor, &value.SessionID, &value.TurnID); err != nil {
			return nil, storeErr(err)
		}
		out = append(out, value)
	}
	return out, storeErr(rows.Err())
}

func (p *Postgres) HistorySessionIncludingDeleted(ctx context.Context, user, id uuid.UUID) (domain.HistorySession, error) {
	if user == uuid.Nil || id == uuid.Nil {
		return domain.HistorySession{}, domain.ErrInvalid
	}
	return scanHistorySession(p.pool.QueryRow(ctx, `SELECT `+historySessionColumns+` FROM history_sessions WHERE id=$1 AND user_id=$2`, id, user))
}
func (p *Postgres) HistoryTurnIncludingDeleted(ctx context.Context, user, id uuid.UUID) (domain.EncryptedTurn, error) {
	if user == uuid.Nil || id == uuid.Nil {
		return domain.EncryptedTurn{}, domain.ErrInvalid
	}
	return scanHistoryTurn(p.pool.QueryRow(ctx, `SELECT `+historyTurnColumns+` FROM history_turns WHERE id=$1 AND user_id=$2`, id, user))
}

// AdminHistory records the explicit full-history access before returning any
// encrypted payload. The audit row and read share one transaction.
func (p *Postgres) AdminHistory(ctx context.Context, admin, user uuid.UUID) ([]domain.HistorySession, []domain.EncryptedTurn, error) {
	if admin == uuid.Nil || user == uuid.Nil {
		return nil, nil, domain.ErrInvalid
	}
	var sessions []domain.HistorySession
	var turns []domain.EncryptedTurn
	err := p.tx(ctx, func(t pgx.Tx) error {
		if _, err := t.Exec(ctx, `INSERT INTO audit_logs(admin_id,action,target_type,target_id,metadata) VALUES($1,'history.read','history',$2,'{}'::jsonb)`, admin, user); err != nil {
			return storeErr(err)
		}
		rows, err := t.Query(ctx, `SELECT `+historySessionColumns+` FROM history_sessions WHERE user_id=$1 ORDER BY created_at DESC,id DESC`, user)
		if err != nil {
			return storeErr(err)
		}
		for rows.Next() {
			value, err := scanHistorySession(rows)
			if err != nil {
				rows.Close()
				return err
			}
			sessions = append(sessions, value)
		}
		if err := rows.Err(); err != nil {
			rows.Close()
			return storeErr(err)
		}
		rows.Close()
		rows, err = t.Query(ctx, `SELECT `+historyTurnColumns+` FROM history_turns WHERE user_id=$1 ORDER BY created_at ASC,id ASC`, user)
		if err != nil {
			return storeErr(err)
		}
		defer rows.Close()
		for rows.Next() {
			value, err := scanHistoryTurn(rows)
			if err != nil {
				return err
			}
			turns = append(turns, value)
		}
		return storeErr(rows.Err())
	})
	return sessions, turns, err
}

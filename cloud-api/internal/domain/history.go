package domain

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Per-user caps on live (non-tombstoned) translation-history records. They
// bound ciphertext storage per account and are enforced transactionally when
// sessions or turns are created.
const (
	HistoryMaxLiveSessions = 1000
	HistoryMaxLiveTurns    = 10000
)

// ErrHistoryLimitExceeded means the caller hit a live-session or live-turn cap.
var ErrHistoryLimitExceeded = errors.New("history limit exceeded")

// HistorySession is a tombstone-capable container for a user's completed
// translation turns. It intentionally carries no plaintext metadata.
type HistorySession struct {
	ID        uuid.UUID  `json:"id"`
	UserID    uuid.UUID  `json:"user_id"`
	CreatedAt time.Time  `json:"created_at"`
	DeletedAt *time.Time `json:"deleted_at,omitempty"`
}

// Live reports whether the session is readable and accepts new turns.
func (s HistorySession) Live() bool { return s.DeletedAt == nil }

// EncryptedTurn is one completed text turn persisted as AEAD output. The
// plaintext exists only transiently in process memory; the store never sees
// it. Nonce and Ciphertext are nil for tombstoned turns.
type EncryptedTurn struct {
	ID         uuid.UUID  `json:"id"`
	UserID     uuid.UUID  `json:"user_id"`
	SessionID  uuid.UUID  `json:"session_id"`
	KeyVersion int        `json:"key_version"`
	Nonce      []byte     `json:"nonce,omitempty"`
	Ciphertext []byte     `json:"ciphertext,omitempty"`
	CreatedAt  time.Time  `json:"created_at"`
	DeletedAt  *time.Time `json:"deleted_at,omitempty"`
}

// Live reports whether the turn still carries decryptable ciphertext.
func (t EncryptedTurn) Live() bool {
	return t.DeletedAt == nil && len(t.Nonce) > 0 && len(t.Ciphertext) > 0
}

// ValidateHistoryCiphertext enforces the persisted AEAD shape shared with the
// 000007 migration checks without inspecting plaintext.
func ValidateHistoryCiphertext(nonce, ciphertext []byte) error {
	if len(nonce) != 12 {
		return fmt.Errorf("%w: turn nonce must be 12 bytes", ErrInvalid)
	}
	if len(ciphertext) < 16 || len(ciphertext) > 262144 {
		return fmt.Errorf("%w: turn ciphertext length must be between 16 and 262144 bytes", ErrInvalid)
	}
	return nil
}

type CreateHistorySessionParams struct {
	ID     uuid.UUID
	UserID uuid.UUID
	Now    time.Time
}

func (p CreateHistorySessionParams) Validate() error {
	if p.ID == uuid.Nil || p.UserID == uuid.Nil || p.Now.IsZero() {
		return fmt.Errorf("%w: history session requires id, user, and time", ErrInvalid)
	}
	return nil
}

type AppendHistoryTurnParams struct {
	ID         uuid.UUID
	UserID     uuid.UUID
	SessionID  uuid.UUID
	KeyVersion int
	Nonce      []byte
	Ciphertext []byte
	Now        time.Time
}

func (p AppendHistoryTurnParams) Validate() error {
	if p.ID == uuid.Nil || p.UserID == uuid.Nil || p.SessionID == uuid.Nil || p.KeyVersion < 1 || p.Now.IsZero() {
		return fmt.Errorf("%w: history turn requires ids, positive key version, and time", ErrInvalid)
	}
	return ValidateHistoryCiphertext(p.Nonce, p.Ciphertext)
}

// HistoryStore persists owner-scoped, encrypted-at-rest translation history.
// All methods must enforce user ownership; CreateHistorySession and
// AppendHistoryTurn must be transactional, enforce the live caps, and treat a
// deletion tombstone as final: a tombstoned session or turn ID can never be
// resurrected, and deleting clears stored ciphertext.
type HistoryStore interface {
	CreateHistorySession(context.Context, CreateHistorySessionParams) (HistorySession, error)
	AppendHistoryTurn(context.Context, AppendHistoryTurnParams) (EncryptedTurn, error)
	HistorySessionByID(context.Context, uuid.UUID, uuid.UUID) (HistorySession, error)
	ListHistorySessions(context.Context, uuid.UUID, int, int) ([]HistorySession, error)
	ListHistoryTurns(context.Context, uuid.UUID, uuid.UUID, int, int) ([]EncryptedTurn, error)
	DeleteHistorySession(context.Context, uuid.UUID, uuid.UUID, time.Time) error
}

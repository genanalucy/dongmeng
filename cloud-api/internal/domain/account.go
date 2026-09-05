package domain

import (
	"context"
	"time"

	"github.com/google/uuid"
)

type UsageSummary struct {
	AudioSeconds int
	SessionCount int
	LastUsedAt   *time.Time
}

type AccountOverview struct {
	User        User
	Entitlement *Entitlement
	Usage       UsageSummary
}

type AccountIdentity struct {
	Username    string
	Email       string
	MaskedPhone string
}

type AccountUsage struct {
	SessionID       uuid.UUID  `json:"session_id"`
	StartedAt       time.Time  `json:"started_at"`
	EndedAt         *time.Time `json:"ended_at,omitempty"`
	DurationSeconds int        `json:"duration_seconds"`
	SourceLanguage  string     `json:"source_language,omitempty"`
	TargetLanguage  string     `json:"target_language,omitempty"`
}

type UpdateIdentityParams struct {
	UserID          uuid.UUID
	Username        string
	Email           string
	Phone           string
	CurrentPassword string
}

// DeleteAccountParams carries the exact-username confirmation for one
// authenticated self-service deletion. Username must equal the currently
// stored username of exactly the account identified by UserID; it is never
// matched against any other account.
type DeleteAccountParams struct {
	UserID   uuid.UUID
	Username string
	Now      time.Time
}

type AccountStore interface {
	AccountOverview(context.Context, uuid.UUID) (AccountOverview, error)
	ListAccountUsage(context.Context, uuid.UUID, int, int) ([]AccountUsage, error)
	UpdateIdentity(context.Context, UpdateIdentityParams) (User, error)
	AccountIdentity(context.Context, uuid.UUID) (AccountIdentity, error)
	// DeleteAccount tombstones and anonymizes the caller's own account in one
	// transaction: it removes every login identity (email, username, phone),
	// invalidates the stored password credential, disables the account, revokes
	// all refresh token families and entitlements, terminates active
	// translation sessions, and clears/tombstones all encrypted history bodies.
	// It must never remove the users row itself, because audit_logs and redeemed
	// redemption_codes reference it. It reports domain.ErrForbidden for admin
	// accounts, domain.ErrConflict when the confirmed username does not match
	// the account's current username (including legacy empty usernames), and
	// domain.ErrNotFound for a missing account. Calling it again after a
	// committed deletion is an idempotent no-op that preserves all original
	// tombstone timestamps.
	DeleteAccount(context.Context, DeleteAccountParams) error
}

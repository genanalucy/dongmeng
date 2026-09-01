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

type AccountStore interface {
	AccountOverview(context.Context, uuid.UUID) (AccountOverview, error)
	ListAccountUsage(context.Context, uuid.UUID, int, int) ([]AccountUsage, error)
	UpdateIdentity(context.Context, UpdateIdentityParams) (User, error)
	AccountIdentity(context.Context, uuid.UUID) (AccountIdentity, error)
}

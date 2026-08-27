package store

import (
	"context"
	"errors"
	"fmt"
	"time"
)

const (
	maxExpiryBatch = 1000

	expiredFeedbackArtifactsSQL = `
SELECT id::text, user_id::text, object_key, expires_at
FROM feedback_artifacts
WHERE expires_at <= $1
ORDER BY expires_at, id
LIMIT $2`

	expiredTranslationSessionsSQL = `
SELECT id::text, user_id::text, expires_at
FROM translation_sessions
WHERE expires_at <= $1
ORDER BY expires_at, id
LIMIT $2`

	expiredRefreshTokensSQL = `
SELECT id::text, user_id::text, family_id::text, expires_at
FROM refresh_tokens
WHERE expires_at <= $1
ORDER BY expires_at, id
LIMIT $2`
)

// ExpiredFeedbackArtifact identifies an object that is eligible for removal
// from object storage. Callers should delete the object before deleting its
// database row so a transient object-store failure remains retryable.
type ExpiredFeedbackArtifact struct {
	ID        string
	UserID    string
	ObjectKey string
	ExpiresAt time.Time
}

// ExpiredTranslationSession identifies an expired session without deleting its
// usage record. Session and usage retention policies can therefore be applied
// independently.
type ExpiredTranslationSession struct {
	ID        string
	UserID    string
	ExpiresAt time.Time
}

// ExpiredRefreshToken identifies authentication state that is past its
// validity window. Revoked tokens remain queryable until expiry so replay
// detection is not weakened while a token could otherwise still be used.
type ExpiredRefreshToken struct {
	ID        string
	UserID    string
	FamilyID  string
	ExpiresAt time.Time
}

// ExpiredFeedbackArtifacts returns a deterministic, bounded expiry batch.
func (p *Postgres) ExpiredFeedbackArtifacts(ctx context.Context, cutoff time.Time, limit int) ([]ExpiredFeedbackArtifact, error) {
	cutoff, err := validateExpiryQuery(p, ctx, cutoff, limit)
	if err != nil {
		return nil, err
	}
	rows, err := p.query(ctx, expiredFeedbackArtifactsSQL, cutoff, limit)
	if err != nil {
		return nil, fmt.Errorf("query expired feedback artifacts: %w", err)
	}
	defer rows.Close()

	artifacts := make([]ExpiredFeedbackArtifact, 0, limit)
	for rows.Next() {
		var artifact ExpiredFeedbackArtifact
		if err := rows.Scan(&artifact.ID, &artifact.UserID, &artifact.ObjectKey, &artifact.ExpiresAt); err != nil {
			return nil, fmt.Errorf("scan expired feedback artifact: %w", err)
		}
		artifact.ExpiresAt = artifact.ExpiresAt.UTC()
		artifacts = append(artifacts, artifact)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate expired feedback artifacts: %w", err)
	}
	return artifacts, nil
}

// ExpiredTranslationSessions returns a deterministic, bounded expiry batch.
func (p *Postgres) ExpiredTranslationSessions(ctx context.Context, cutoff time.Time, limit int) ([]ExpiredTranslationSession, error) {
	cutoff, err := validateExpiryQuery(p, ctx, cutoff, limit)
	if err != nil {
		return nil, err
	}
	rows, err := p.query(ctx, expiredTranslationSessionsSQL, cutoff, limit)
	if err != nil {
		return nil, fmt.Errorf("query expired translation sessions: %w", err)
	}
	defer rows.Close()

	sessions := make([]ExpiredTranslationSession, 0, limit)
	for rows.Next() {
		var session ExpiredTranslationSession
		if err := rows.Scan(&session.ID, &session.UserID, &session.ExpiresAt); err != nil {
			return nil, fmt.Errorf("scan expired translation session: %w", err)
		}
		session.ExpiresAt = session.ExpiresAt.UTC()
		sessions = append(sessions, session)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate expired translation sessions: %w", err)
	}
	return sessions, nil
}

// ExpiredRefreshTokens returns a deterministic, bounded expiry batch. It does
// not delete rows because family lineage and replay-retention policy must be
// handled atomically by the eventual cleanup worker.
func (p *Postgres) ExpiredRefreshTokens(ctx context.Context, cutoff time.Time, limit int) ([]ExpiredRefreshToken, error) {
	cutoff, err := validateExpiryQuery(p, ctx, cutoff, limit)
	if err != nil {
		return nil, err
	}
	rows, err := p.query(ctx, expiredRefreshTokensSQL, cutoff, limit)
	if err != nil {
		return nil, fmt.Errorf("query expired refresh tokens: %w", err)
	}
	defer rows.Close()

	tokens := make([]ExpiredRefreshToken, 0, limit)
	for rows.Next() {
		var token ExpiredRefreshToken
		if err := rows.Scan(&token.ID, &token.UserID, &token.FamilyID, &token.ExpiresAt); err != nil {
			return nil, fmt.Errorf("scan expired refresh token: %w", err)
		}
		token.ExpiresAt = token.ExpiresAt.UTC()
		tokens = append(tokens, token)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate expired refresh tokens: %w", err)
	}
	return tokens, nil
}

func validateExpiryQuery(p *Postgres, ctx context.Context, cutoff time.Time, limit int) (time.Time, error) {
	if p == nil || p.query == nil {
		return time.Time{}, errors.New("postgres pool is not initialized")
	}
	if ctx == nil {
		return time.Time{}, errors.New("expiry query context is required")
	}
	if cutoff.IsZero() {
		return time.Time{}, errors.New("expiry cutoff is required")
	}
	if limit < 1 || limit > maxExpiryBatch {
		return time.Time{}, fmt.Errorf("expiry batch limit must be between 1 and %d", maxExpiryBatch)
	}
	return cutoff.UTC(), nil
}

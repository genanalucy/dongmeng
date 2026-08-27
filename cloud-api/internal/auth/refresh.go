package auth

import (
	"context"
	"crypto/rand"
	"fmt"
	"io"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
)

type RefreshManager struct {
	Store  domain.RefreshTokenStore
	Random io.Reader
}

type RefreshIssue struct {
	Plaintext string
	Token     domain.RefreshToken
}

func (m RefreshManager) Issue(ctx context.Context, userID uuid.UUID, ttl time.Duration, now time.Time) (RefreshIssue, error) {
	return m.IssueInFamily(ctx, userID, uuid.New(), ttl, now)
}

func (m RefreshManager) IssueInFamily(ctx context.Context, userID, familyID uuid.UUID, ttl time.Duration, now time.Time) (RefreshIssue, error) {
	if m.Store == nil || userID == uuid.Nil || familyID == uuid.Nil || ttl <= 0 || now.IsZero() {
		return RefreshIssue{}, fmt.Errorf("%w: invalid refresh token arguments", domain.ErrInvalid)
	}
	plaintext, hash, err := randomSecret(m.random(), refreshSecretBytes)
	if err != nil {
		return RefreshIssue{}, err
	}
	token, err := m.Store.CreateRefreshToken(ctx, domain.CreateRefreshParams{
		UserID:    userID,
		FamilyID:  familyID,
		Hash:      hash,
		ExpiresAt: now.UTC().Add(ttl),
	})
	if err != nil {
		return RefreshIssue{}, fmt.Errorf("store refresh token: %w", err)
	}
	return RefreshIssue{Plaintext: plaintext, Token: token}, nil
}

// Rotate relies on RefreshTokenStore.RotateRefreshToken to detect reuse and
// atomically revoke the entire token family. Every store error, including a
// replay rejection, returns an empty RefreshIssue. A successful result is also
// rejected unless it identifies the requested lineage and replacement.
func (m RefreshManager) Rotate(ctx context.Context, current string, ttl time.Duration, now time.Time) (RefreshIssue, error) {
	if m.Store == nil || ttl <= 0 || now.IsZero() {
		return RefreshIssue{}, fmt.Errorf("%w: invalid refresh rotation arguments", domain.ErrInvalid)
	}
	parsed, err := domain.ParseRefreshToken(current)
	if err != nil {
		return RefreshIssue{}, err
	}
	now = now.UTC()
	expiresAt := now.Add(ttl)
	oldHash := HashSecret(parsed.String())
	plain, newHash, err := randomSecret(m.random(), refreshSecretBytes)
	if err != nil {
		return RefreshIssue{}, err
	}
	old, next, err := m.Store.RotateRefreshToken(ctx, oldHash, newHash, now, expiresAt)
	if err != nil {
		return RefreshIssue{}, fmt.Errorf("rotate refresh token: %w", err)
	}
	if !validRotation(old, next, oldHash, newHash, now, expiresAt) {
		m.revokeFailedReplacement(ctx, newHash, now)
		return RefreshIssue{}, fmt.Errorf("%w: invalid refresh rotation result", domain.ErrUnauthorized)
	}
	return RefreshIssue{Plaintext: plain, Token: next}, nil
}

func (m RefreshManager) revokeFailedReplacement(ctx context.Context, hash []byte, now time.Time) {
	// Preserve the validation error. Revocation is compensating cleanup for a
	// store that reported success with an invalid replacement result.
	_ = m.Store.RevokeRefreshToken(ctx, hash, now)
}

func validRotation(old, next domain.RefreshToken, oldHash, newHash []byte, now, expiresAt time.Time) bool {
	if !old.Valid() || !SecretHashEqual(old.TokenHash, oldHash) || old.ID == next.ID {
		return false
	}
	if (old.RevokedAt == nil) != (old.ReplacedByID == nil) {
		return false
	}
	if old.RevokedAt != nil && (!timesEqualAtStoragePrecision(*old.RevokedAt, now) || *old.ReplacedByID != next.ID) {
		return false
	}
	if !next.Valid() || next.UserID != old.UserID || next.FamilyID != old.FamilyID || !SecretHashEqual(next.TokenHash, newHash) {
		return false
	}
	return next.RevokedAt == nil && next.ReplacedByID == nil && timesEqualAtStoragePrecision(next.ExpiresAt, expiresAt)
}

func timesEqualAtStoragePrecision(actual, expected time.Time) bool {
	delta := actual.Sub(expected)
	return delta > -time.Microsecond && delta < time.Microsecond
}

func (m RefreshManager) Revoke(ctx context.Context, current string, now time.Time) error {
	if m.Store == nil || now.IsZero() {
		return fmt.Errorf("%w: invalid refresh revocation arguments", domain.ErrInvalid)
	}
	parsed, err := domain.ParseRefreshToken(current)
	if err != nil {
		return err
	}
	if err := m.Store.RevokeRefreshToken(ctx, HashSecret(parsed.String()), now.UTC()); err != nil {
		return fmt.Errorf("revoke refresh token: %w", err)
	}
	return nil
}

func (m RefreshManager) random() io.Reader {
	if m.Random != nil {
		return m.Random
	}
	return rand.Reader
}

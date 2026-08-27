package auth

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
)

type refreshStoreStub struct {
	tokens map[string]domain.RefreshToken
}

type refreshStoreOverride struct {
	*refreshStoreStub
	mutate func(*domain.RefreshToken, *domain.RefreshToken)
	err    error
}

func (s refreshStoreOverride) RotateRefreshToken(ctx context.Context, oldHash, newHash []byte, now, expiresAt time.Time) (domain.RefreshToken, domain.RefreshToken, error) {
	old, next, err := s.refreshStoreStub.RotateRefreshToken(ctx, oldHash, newHash, now, expiresAt)
	if err != nil {
		return old, next, err
	}
	if s.mutate != nil {
		s.mutate(&old, &next)
	}
	if s.err != nil {
		return old, next, s.err
	}
	return old, next, nil
}

func newRefreshStoreStub() *refreshStoreStub {
	return &refreshStoreStub{tokens: make(map[string]domain.RefreshToken)}
}

func (s *refreshStoreStub) CreateRefreshToken(_ context.Context, params domain.CreateRefreshParams) (domain.RefreshToken, error) {
	token := domain.RefreshToken{
		ID:        uuid.New(),
		UserID:    params.UserID,
		FamilyID:  params.FamilyID,
		TokenHash: append([]byte(nil), params.Hash...),
		ExpiresAt: params.ExpiresAt,
	}
	s.tokens[string(params.Hash)] = token
	return token, nil
}

func (s *refreshStoreStub) RotateRefreshToken(_ context.Context, oldHash, newHash []byte, now, expiresAt time.Time) (domain.RefreshToken, domain.RefreshToken, error) {
	old, ok := s.tokens[string(oldHash)]
	if !ok {
		return domain.RefreshToken{}, domain.RefreshToken{}, domain.ErrUnauthorized
	}
	if old.RevokedAt != nil {
		for hash, token := range s.tokens {
			if token.FamilyID == old.FamilyID {
				revokedAt := now
				token.RevokedAt = &revokedAt
				s.tokens[hash] = token
			}
		}
		return domain.RefreshToken{}, domain.RefreshToken{}, domain.ErrUnauthorized
	}
	if !old.ActiveAt(now) {
		return domain.RefreshToken{}, domain.RefreshToken{}, domain.ErrUnauthorized
	}
	next := domain.RefreshToken{
		ID:        uuid.New(),
		UserID:    old.UserID,
		FamilyID:  old.FamilyID,
		TokenHash: append([]byte(nil), newHash...),
		ExpiresAt: expiresAt,
	}
	revokedAt := now
	old.RevokedAt = &revokedAt
	old.ReplacedByID = &next.ID
	s.tokens[string(oldHash)] = old
	s.tokens[string(newHash)] = next
	return old, next, nil
}

func (s *refreshStoreStub) RevokeRefreshToken(_ context.Context, hash []byte, now time.Time) error {
	token, ok := s.tokens[string(hash)]
	if !ok {
		return domain.ErrNotFound
	}
	if token.RevokedAt == nil {
		token.RevokedAt = &now
		s.tokens[string(hash)] = token
	}
	return nil
}

func TestRefreshManagerIssueRotateAndRevoke(t *testing.T) {
	ctx := context.Background()
	store := newRefreshStoreStub()
	manager := RefreshManager{Store: store}
	now := time.Date(2026, 4, 5, 6, 7, 8, 0, time.UTC)
	userID := uuid.New()

	issued, err := manager.Issue(ctx, userID, 30*24*time.Hour, now)
	if err != nil {
		t.Fatal(err)
	}
	if issued.Plaintext == "" || !issued.Token.Valid() || !SecretHashEqual(issued.Token.TokenHash, HashSecret(issued.Plaintext)) {
		t.Fatalf("invalid issued token: %+v", issued.Token)
	}

	rotated, err := manager.Rotate(ctx, issued.Plaintext, 30*24*time.Hour, now.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if rotated.Plaintext == issued.Plaintext || rotated.Token.FamilyID != issued.Token.FamilyID || !SecretHashEqual(rotated.Token.TokenHash, HashSecret(rotated.Plaintext)) {
		t.Fatalf("invalid rotated token: %+v", rotated.Token)
	}
	if _, err := manager.Rotate(ctx, issued.Plaintext, 30*24*time.Hour, now.Add(2*time.Hour)); !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("rotated token reuse error = %v", err)
	}
	if store.tokens[string(HashSecret(rotated.Plaintext))].ActiveAt(now.Add(2 * time.Hour)) {
		t.Fatal("rotated token reuse did not revoke the family")
	}
}

func TestRefreshManagerRevokesCurrentToken(t *testing.T) {
	ctx := context.Background()
	store := newRefreshStoreStub()
	manager := RefreshManager{Store: store}
	now := time.Date(2026, 4, 5, 6, 7, 8, 0, time.UTC)
	issued, err := manager.Issue(ctx, uuid.New(), time.Hour, now)
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.Revoke(ctx, issued.Plaintext, now.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if store.tokens[string(HashSecret(issued.Plaintext))].ActiveAt(now.Add(2 * time.Minute)) {
		t.Fatal("revoked token remains active")
	}
}

func TestRefreshManagerRejectsInvalidArguments(t *testing.T) {
	manager := RefreshManager{Store: newRefreshStoreStub()}
	now := time.Now()

	if _, err := manager.Issue(context.Background(), uuid.Nil, time.Hour, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("nil user error = %v", err)
	}
	if _, err := manager.Rotate(context.Background(), " short ", time.Hour, now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("invalid token error = %v", err)
	}
	if err := (RefreshManager{}).Revoke(context.Background(), stringsOfLength(43), now); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("nil store error = %v", err)
	}
}

func TestRefreshManagerFailsClosedOnInvalidRotationResult(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*domain.RefreshToken, *domain.RefreshToken)
	}{
		{name: "old hash mismatch", mutate: func(old, _ *domain.RefreshToken) { old.TokenHash = HashSecret("other-old") }},
		{name: "same token id", mutate: func(old, next *domain.RefreshToken) { next.ID = old.ID }},
		{name: "revoked without replacement link", mutate: func(old, _ *domain.RefreshToken) { old.ReplacedByID = nil }},
		{name: "replacement link without revocation", mutate: func(old, _ *domain.RefreshToken) { old.RevokedAt = nil }},
		{name: "replacement link mismatch", mutate: func(old, _ *domain.RefreshToken) { replacementID := uuid.New(); old.ReplacedByID = &replacementID }},
		{name: "revocation time mismatch", mutate: func(old, _ *domain.RefreshToken) {
			revokedAt := old.RevokedAt.Add(time.Second)
			old.RevokedAt = &revokedAt
		}},
		{name: "user mismatch", mutate: func(_ *domain.RefreshToken, next *domain.RefreshToken) { next.UserID = uuid.New() }},
		{name: "family mismatch", mutate: func(_ *domain.RefreshToken, next *domain.RefreshToken) { next.FamilyID = uuid.New() }},
		{name: "new hash mismatch", mutate: func(_ *domain.RefreshToken, next *domain.RefreshToken) { next.TokenHash = HashSecret("other-new") }},
		{name: "wrong expiry", mutate: func(_ *domain.RefreshToken, next *domain.RefreshToken) {
			next.ExpiresAt = next.ExpiresAt.Add(time.Second)
		}},
		{name: "replacement already revoked", mutate: func(_ *domain.RefreshToken, next *domain.RefreshToken) {
			revokedAt := next.ExpiresAt.Add(-time.Minute)
			next.RevokedAt = &revokedAt
		}},
		{name: "replacement already replaced", mutate: func(_ *domain.RefreshToken, next *domain.RefreshToken) {
			replacementID := uuid.New()
			next.ReplacedByID = &replacementID
		}},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			ctx := context.Background()
			store := refreshStoreOverride{refreshStoreStub: newRefreshStoreStub(), mutate: test.mutate}
			manager := RefreshManager{Store: store}
			now := time.Date(2026, 10, 1, 2, 3, 4, 0, time.UTC)
			issued, err := manager.Issue(ctx, uuid.New(), time.Hour, now)
			if err != nil {
				t.Fatal(err)
			}

			rotated, err := manager.Rotate(ctx, issued.Plaintext, time.Hour, now.Add(time.Minute))
			if !errors.Is(err, domain.ErrUnauthorized) {
				t.Fatalf("invalid rotation error = %v", err)
			}
			assertEmptyRefreshIssue(t, rotated)
			assertNoActiveReplacement(t, store.tokens, issued.Token.TokenHash, now.Add(time.Minute))
		})
	}
}

func TestRefreshManagerNeverReturnsReplacementOnStoreError(t *testing.T) {
	ctx := context.Background()
	store := refreshStoreOverride{refreshStoreStub: newRefreshStoreStub(), err: domain.ErrUnauthorized}
	manager := RefreshManager{Store: store}
	now := time.Date(2026, 10, 2, 3, 4, 5, 0, time.UTC)
	issued, err := manager.Issue(ctx, uuid.New(), time.Hour, now)
	if err != nil {
		t.Fatal(err)
	}

	rotated, err := manager.Rotate(ctx, issued.Plaintext, time.Hour, now.Add(time.Minute))
	if !errors.Is(err, domain.ErrUnauthorized) {
		t.Fatalf("store error = %v", err)
	}
	assertEmptyRefreshIssue(t, rotated)
}

func assertNoActiveReplacement(t *testing.T, tokens map[string]domain.RefreshToken, originalHash []byte, now time.Time) {
	t.Helper()
	for _, token := range tokens {
		if SecretHashEqual(token.TokenHash, originalHash) {
			continue
		}
		if token.ActiveAt(now) {
			t.Fatalf("failed rotation left an active replacement: %+v", token)
		}
	}
}

func assertEmptyRefreshIssue(t *testing.T, issue RefreshIssue) {
	t.Helper()
	if issue.Plaintext != "" || issue.Token.ID != uuid.Nil || issue.Token.TokenHash != nil {
		t.Fatalf("replacement leaked on failed rotation: %+v", issue)
	}
}

func stringsOfLength(length int) string {
	value := make([]byte, length)
	for i := range value {
		value[i] = 'x'
	}
	return string(value)
}

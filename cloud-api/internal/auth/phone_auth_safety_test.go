package auth

import (
	"context"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
)

type phoneRegistrationStore struct {
	users        map[string]domain.User
	trials       map[uuid.UUID]domain.Entitlement
	failUser     bool
	failTrial    bool
	legacyUser   domain.User
	refreshStore *refreshStoreStub
}

func newPhoneRegistrationStore() *phoneRegistrationStore {
	return &phoneRegistrationStore{users: map[string]domain.User{}, trials: map[uuid.UUID]domain.Entitlement{}, refreshStore: newRefreshStoreStub()}
}

func (s *phoneRegistrationStore) Register(_ context.Context, params domain.RegisterParams) (domain.User, domain.Entitlement, error) {
	if s.failUser {
		return domain.User{}, domain.Entitlement{}, domain.ErrConflict
	}
	if _, exists := s.users["username:"+params.Username]; exists {
		return domain.User{}, domain.Entitlement{}, domain.ErrConflict
	}
	if _, exists := s.users["phone:"+params.Phone]; exists {
		return domain.User{}, domain.Entitlement{}, domain.ErrConflict
	}
	if s.failTrial {
		return domain.User{}, domain.Entitlement{}, domain.ErrConflict
	}
	user := domain.User{ID: uuid.New(), Username: params.Username, Phone: params.Phone, Role: string(domain.RoleUser), CreatedAt: params.Now}
	trial, _ := domain.NewTrialEntitlement(uuid.New(), user.ID, params.Now)
	s.users["username:"+params.Username], s.users["phone:"+params.Phone] = user, user
	s.trials[user.ID] = trial
	return user, trial, nil
}

func (s *phoneRegistrationStore) CreateRefreshToken(ctx context.Context, p domain.CreateRefreshParams) (domain.RefreshToken, error) {
	return s.refreshStore.CreateRefreshToken(ctx, p)
}
func (s *phoneRegistrationStore) RotateRefreshToken(ctx context.Context, old, next []byte, now, expires time.Time) (domain.RefreshToken, domain.RefreshToken, error) {
	return s.refreshStore.RotateRefreshToken(ctx, old, next, now, expires)
}
func (s *phoneRegistrationStore) RevokeRefreshToken(ctx context.Context, hash []byte, now time.Time) error {
	return s.refreshStore.RevokeRefreshToken(ctx, hash, now)
}

func TestPhoneRegistrationStoreContractConflictsAndRollsBack(t *testing.T) {
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	for _, failure := range []string{"user", "trial"} {
		t.Run(failure+" failure leaves no records", func(t *testing.T) {
			store := newPhoneRegistrationStore()
			store.failUser = failure == "user"
			store.failTrial = failure == "trial"
			_, _, err := store.Register(context.Background(), domain.RegisterParams{Username: "alice_01", Phone: "+8613800138000", PasswordHash: "hash", Now: now})
			if err == nil || len(store.users) != 0 || len(store.trials) != 0 {
				t.Fatal("failed registration was not atomic")
			}
		})
	}
	store := newPhoneRegistrationStore()
	if _, _, err := store.Register(context.Background(), domain.RegisterParams{Username: "alice_01", Phone: "+8613800138000", PasswordHash: "hash", Now: now}); err != nil {
		t.Fatal(err)
	}
	for _, params := range []domain.RegisterParams{{Username: "alice_01", Phone: "+8613900138000", PasswordHash: "hash", Now: now}, {Username: "bob_01", Phone: "+8613800138000", PasswordHash: "hash", Now: now}} {
		if _, _, err := store.Register(context.Background(), params); err != domain.ErrConflict {
			t.Fatal("duplicate identity did not map to generic conflict")
		}
	}
}

func TestLegacyUserRefreshRotationRemainsUsable(t *testing.T) {
	store := newPhoneRegistrationStore()
	store.legacyUser = domain.User{ID: uuid.New(), Email: "legacy@example.test", Role: string(domain.RoleUser)}
	if store.legacyUser.Email != "legacy@example.test" {
		t.Fatal("legacy email user was not readable")
	}
	manager := RefreshManager{Store: store}
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	issued, err := manager.Issue(context.Background(), store.legacyUser.ID, time.Hour, now)
	if err != nil {
		t.Fatal(err)
	}
	rotated, err := manager.Rotate(context.Background(), issued.Plaintext, time.Hour, now.Add(time.Minute))
	if err != nil || rotated.Token.UserID != store.legacyUser.ID {
		t.Fatal("legacy refresh rotation failed")
	}
}

package store

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
)

type listUsersRows struct {
	values [][]any
	index  int
}

func (r *listUsersRows) Close()     {}
func (r *listUsersRows) Next() bool { return r.index < len(r.values) }
func (r *listUsersRows) Scan(dest ...any) error {
	if len(dest) != 6 {
		return errors.New("expected six scan destinations")
	}
	row := r.values[r.index]
	r.index++
	if len(row) != len(dest) {
		return errors.New("query columns do not match scan destinations")
	}
	for i := range dest {
		switch target := dest[i].(type) {
		case *uuid.UUID:
			*target = row[i].(uuid.UUID)
		case *string:
			*target = row[i].(string)
		case *time.Time:
			*target = row[i].(time.Time)
		default:
			return errors.New("unexpected scan destination")
		}
	}
	return nil
}
func (r *listUsersRows) Err() error { return nil }

func TestListUsersExecutesSixColumnQueryAndHidesReservedEmail(t *testing.T) {
	legacyID, phoneID := uuid.New(), uuid.New()
	createdAt := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)
	var query string
	store := &Postgres{query: func(_ context.Context, sql string, _ ...any) (rows, error) {
		query = sql
		return &listUsersRows{values: [][]any{
			{legacyID, "", "", "legacy@example.test", "user", createdAt},
			{phoneID, "alice_01", "+8613800138000", "phone-internal@reserved.invalid", "user", createdAt},
		}}, nil
	}}

	users, err := store.ListUsers(context.Background(), "", 50, 0)

	if err != nil || len(users) != 2 {
		t.Fatalf("ListUsers() = %v, %v", users, err)
	}
	if !strings.Contains(query, "COALESCE(username,'')") || !strings.Contains(query, "COALESCE(phone,'')") {
		t.Fatalf("ListUsers query does not provide nullable columns: %q", query)
	}
	if users[0].Email != "legacy@example.test" || users[1].Email != "" || users[1].Phone != "+8613800138000" {
		t.Fatalf("ListUsers public values = %#v", users)
	}
}

func TestReservedEmailIsUniqueAndNotAPublicIdentity(t *testing.T) {
	first, second := reservedEmail(), reservedEmail()
	if first == second || !strings.HasPrefix(first, "phone-") || !strings.HasSuffix(first, "@reserved.invalid") {
		t.Fatal("reserved emails are not unique internal values")
	}
	if publicEmail(first) != "" {
		t.Fatal("reserved email escaped the public store read boundary")
	}
	legacy := "legacy@example.test"
	if publicEmail(legacy) != legacy {
		t.Fatal("legacy email was not preserved")
	}
	if domain.ErrConflict.Error() != "conflict" {
		t.Fatal("collision mapping is not generic")
	}
}

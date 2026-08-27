package store

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

type fakeRows struct {
	values [][]any
	index  int
	err    error
	closed bool
}

func (rows *fakeRows) Close() { rows.closed = true }

func (rows *fakeRows) Next() bool {
	if rows.index >= len(rows.values) {
		return false
	}
	rows.index++
	return true
}

func (rows *fakeRows) Scan(destinations ...any) error {
	if rows.index < 1 || rows.index > len(rows.values) {
		return errors.New("scan called without current row")
	}
	values := rows.values[rows.index-1]
	if len(values) != len(destinations) {
		return errors.New("unexpected destination count")
	}
	for index, destination := range destinations {
		switch pointer := destination.(type) {
		case *string:
			value, ok := values[index].(string)
			if !ok {
				return errors.New("expected string value")
			}
			*pointer = value
		case *time.Time:
			value, ok := values[index].(time.Time)
			if !ok {
				return errors.New("expected time value")
			}
			*pointer = value
		default:
			return errors.New("unsupported destination")
		}
	}
	return nil
}

func (rows *fakeRows) Err() error { return rows.err }

func TestExpiredFeedbackArtifactsUsesBoundedParameterizedUTCQuery(t *testing.T) {
	cutoff := time.Date(2026, 3, 1, 12, 30, 0, 0, time.FixedZone("UTC+8", 8*60*60))
	expiresAt := cutoff.Add(-time.Hour)
	returnedRows := &fakeRows{values: [][]any{{"artifact-id", "user-id", "feedback/object", expiresAt}}}
	var query string
	var arguments []any
	postgres := &Postgres{query: func(_ context.Context, sql string, args ...any) (rows, error) {
		query, arguments = sql, args
		return returnedRows, nil
	}}

	artifacts, err := postgres.ExpiredFeedbackArtifacts(context.Background(), cutoff, 25)
	if err != nil {
		t.Fatalf("ExpiredFeedbackArtifacts() error = %v", err)
	}
	if query != expiredFeedbackArtifactsSQL || len(arguments) != 2 || arguments[0] != cutoff.UTC() || arguments[1] != 25 {
		t.Fatalf("query = %q, arguments = %#v", query, arguments)
	}
	if len(artifacts) != 1 || artifacts[0].ObjectKey != "feedback/object" || artifacts[0].ExpiresAt.Location() != time.UTC {
		t.Fatalf("artifacts = %#v", artifacts)
	}
	if !returnedRows.closed {
		t.Fatal("rows were not closed")
	}
}

func TestExpiredTranslationSessionsReturnsStableExpiryBatch(t *testing.T) {
	cutoff := time.Date(2026, 3, 1, 12, 30, 0, 0, time.UTC)
	returnedRows := &fakeRows{values: [][]any{{"session-id", "user-id", cutoff.Add(-time.Minute)}}}
	postgres := &Postgres{query: func(_ context.Context, sql string, args ...any) (rows, error) {
		if sql != expiredTranslationSessionsSQL || len(args) != 2 {
			t.Fatalf("unexpected query = %q, args = %#v", sql, args)
		}
		return returnedRows, nil
	}}

	sessions, err := postgres.ExpiredTranslationSessions(context.Background(), cutoff, 10)
	if err != nil {
		t.Fatalf("ExpiredTranslationSessions() error = %v", err)
	}
	if len(sessions) != 1 || sessions[0].ID != "session-id" || sessions[0].UserID != "user-id" {
		t.Fatalf("sessions = %#v", sessions)
	}
}

func TestExpiredRefreshTokensReturnsStableExpiryBatch(t *testing.T) {
	cutoff := time.Date(2026, 3, 1, 12, 30, 0, 0, time.FixedZone("UTC-5", -5*60*60))
	returnedRows := &fakeRows{values: [][]any{{"token-id", "user-id", "family-id", cutoff.Add(-time.Minute)}}}
	var arguments []any
	postgres := &Postgres{query: func(_ context.Context, sql string, args ...any) (rows, error) {
		if sql != expiredRefreshTokensSQL {
			t.Fatalf("unexpected query = %q", sql)
		}
		arguments = args
		return returnedRows, nil
	}}

	tokens, err := postgres.ExpiredRefreshTokens(context.Background(), cutoff, 10)
	if err != nil {
		t.Fatalf("ExpiredRefreshTokens() error = %v", err)
	}
	if len(arguments) != 2 || arguments[0] != cutoff.UTC() || arguments[1] != 10 {
		t.Fatalf("arguments = %#v", arguments)
	}
	if len(tokens) != 1 || tokens[0].ID != "token-id" || tokens[0].UserID != "user-id" || tokens[0].FamilyID != "family-id" || tokens[0].ExpiresAt.Location() != time.UTC {
		t.Fatalf("tokens = %#v", tokens)
	}
	if !returnedRows.closed {
		t.Fatal("rows were not closed")
	}
}

func TestExpiryQueriesValidateInputs(t *testing.T) {
	validPostgres := &Postgres{query: func(context.Context, string, ...any) (rows, error) {
		return &fakeRows{}, nil
	}}
	tests := []struct {
		name     string
		postgres *Postgres
		ctx      context.Context
		cutoff   time.Time
		limit    int
		want     string
	}{
		{name: "nil store", postgres: nil, ctx: context.Background(), cutoff: time.Now(), limit: 1, want: "not initialized"},
		{name: "nil context", postgres: validPostgres, cutoff: time.Now(), limit: 1, want: "context"},
		{name: "zero cutoff", postgres: validPostgres, ctx: context.Background(), limit: 1, want: "cutoff"},
		{name: "zero limit", postgres: validPostgres, ctx: context.Background(), cutoff: time.Now(), want: "limit"},
		{name: "oversized limit", postgres: validPostgres, ctx: context.Background(), cutoff: time.Now(), limit: maxExpiryBatch + 1, want: "limit"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			_, err := test.postgres.ExpiredTranslationSessions(test.ctx, test.cutoff, test.limit)
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("error = %v, want substring %q", err, test.want)
			}
		})
	}
}

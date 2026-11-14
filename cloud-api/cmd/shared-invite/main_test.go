package main

import (
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestDeadlineUsesExclusiveBeijingMidnight(t *testing.T) {
	now := time.Date(2026, 11, 11, 0, 0, 0, 0, time.UTC)
	args := []string{"-admin-id", uuid.NewString(), "-name", "internal test", "-claim-until", "2027-01-01T00:00:00+08:00", "-output", "/private/invite"}
	opts, err := parse(args, now)
	if err != nil {
		t.Fatal(err)
	}
	want := time.Date(2026, 12, 31, 16, 0, 0, 0, time.UTC)
	if !opts.deadline.Equal(want) {
		t.Fatal("incorrect exclusive UTC deadline")
	}
	if _, err := parse(args, want); err == nil {
		t.Fatal("already expired invitation accepted")
	}
}

func TestRejectsUnsafeDatabaseTargetsWithoutEchoingCredentials(t *testing.T) {
	for _, value := range []string{
		"postgres://cloud:secret@127.0.0.1:5432/cloud",
		"postgres://cloud:secret@external:15432/cloud",
		"postgres://cloud:secret@127.0.0.1:15432/cloud?host=external",
		"postgres://cloud:secret@127.0.0.1:15432/cloud?options=-c%20search_path%3Dother",
	} {
		err := validateDatabaseURL(value)
		if err == nil {
			t.Fatal("unsafe database target accepted")
		}
		if strings.Contains(err.Error(), "secret") {
			t.Fatal("database credential echoed")
		}
	}
	if err := validateDatabaseURL("postgres://cloud:secret@127.0.0.1:15432/cloud?sslmode=disable"); err != nil {
		t.Fatal(err)
	}
}

package main

import (
	"context"
	"strings"
	"testing"
)

func TestRunRejectsInvalidInputBeforeDatabaseAccess(t *testing.T) {
	tests := []struct {
		name  string
		args  []string
		input string
	}{
		{name: "missing args", args: nil, input: "admin@example.test\nStrongPass1\n"},
		{name: "invalid username", args: []string{"12"}, input: "admin@example.test\nStrongPass1\n"},
		{name: "invalid email", args: []string{"admin_user"}, input: "invalid\nStrongPass1\n"},
		{name: "short password", args: []string{"admin_user"}, input: "admin@example.test\nshort\n"},
		{name: "missing database", args: []string{"admin_user"}, input: "admin@example.test\nStrongPass1\n"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if err := run(context.Background(), test.args, strings.NewReader(test.input), ""); err == nil {
				t.Fatal("invalid bootstrap input accepted")
			}
		})
	}
}

func TestValidateDatabaseURLAllowsOnlyDedicatedLoopbackDatabase(t *testing.T) {
	if err := validateDatabaseURL("postgres://user:password@127.0.0.1:15432/cloud"); err != nil {
		t.Fatalf("dedicated database rejected: %v", err)
	}
	for _, value := range []string{
		"postgres://user:password@127.0.0.1:5432/cloud",
		"postgres://user:password@database:15432/cloud",
		"https://127.0.0.1:15432/cloud",
	} {
		if err := validateDatabaseURL(value); err == nil {
			t.Fatalf("unsafe database URL accepted: %s", value)
		}
	}
}

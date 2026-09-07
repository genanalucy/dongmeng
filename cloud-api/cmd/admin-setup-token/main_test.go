package main

import (
	"context"
	"strings"
	"testing"
)

func TestRunRejectsArgumentsAndUnsafeDatabaseBeforeTokenOutput(t *testing.T) {
	var output strings.Builder
	if err := run(context.Background(), []string{"secret"}, "postgres://user:password@127.0.0.1:15432/cloud", &output); err == nil {
		t.Fatal("accepted positional token argument")
	}
	if output.Len() != 0 {
		t.Fatal("wrote plaintext before rejecting input")
	}
	if err := run(context.Background(), nil, "postgres://user:password@postgres:15432/cloud", &output); err == nil {
		t.Fatal("accepted non-loopback database")
	}
}

func TestValidateDatabaseURLAllowsOnlyDedicatedLoopbackDatabase(t *testing.T) {
	if err := validateDatabaseURL("postgres://user:password@127.0.0.1:15432/cloud"); err != nil {
		t.Fatalf("dedicated database rejected: %v", err)
	}
	for _, value := range []string{"postgres://user:password@127.0.0.1:5432/cloud", "postgres://user:password@localhost:15432/cloud", "https://127.0.0.1:15432/cloud"} {
		if err := validateDatabaseURL(value); err == nil {
			t.Fatalf("unsafe database URL accepted: %s", value)
		}
	}
}

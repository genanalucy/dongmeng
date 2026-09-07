// Command admin-setup-token creates a one-shot, time-limited administrator
// setup token for local recovery. It never accepts or persists plaintext token
// material outside its single stdout write.
package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/dngmeng/cloud-api/internal/store"
)

const (
	setupTokenTTL       = 15 * time.Minute
	setupDatabaseURLEnv = "CLOUD_API_ADMIN_SETUP_DATABASE_URL"
)

func main() {
	if err := run(context.Background(), os.Args[1:], os.Getenv(setupDatabaseURLEnv), os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "admin setup token failed:", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string, databaseURL string, output io.Writer) error {
	if len(args) != 0 {
		return errors.New("usage: admin-setup-token; no token or password arguments are accepted")
	}
	if err := validateDatabaseURL(databaseURL); err != nil {
		return err
	}
	plaintext, tokenHash, err := auth.RandomSecret(auth.MinimumSecretBytes)
	if err != nil {
		return errors.New("generate setup token")
	}
	database, err := store.Open(ctx, databaseURL)
	if err != nil {
		return errors.New("open database")
	}
	defer database.Close()
	if err := database.Ping(ctx); err != nil {
		return errors.New("database unavailable")
	}
	now := time.Now().UTC()
	if err := database.CreateAdminSetupChallenge(ctx, domain.CreateAdminSetupChallengeParams{TokenHash: tokenHash, Now: now, ExpiresAt: now.Add(setupTokenTTL)}); err != nil {
		return errors.New("create setup challenge")
	}
	// This is intentionally the only plaintext token sink. Stdout is exactly
	// one token plus a newline, so operators can use `umask 077 &&
	// admin-setup-token > setup-token` to create a mode-600 delivery file
	// without copying surrounding prose or exposing another plaintext sink.
	_, err = fmt.Fprintln(output, plaintext)
	return err
}

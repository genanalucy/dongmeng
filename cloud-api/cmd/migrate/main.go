// Command migrate applies Cloud API up migrations from an explicit database URL.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/dngmeng/cloud-api/internal/migrate"
)

const migrationDatabaseURLEnv = "CLOUD_API_MIGRATE_DATABASE_URL"

func main() {
	config, err := migrationConfig(os.Args[1:])
	if err != nil {
		fmt.Fprintln(os.Stderr, "migration configuration error:", err)
		os.Exit(2)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	if err := migrate.Run(ctx, config); err != nil {
		// migrate.Run intentionally never includes a DSN in errors.
		fmt.Fprintln(os.Stderr, "migration failed:", err)
		os.Exit(1)
	}
}

func migrationConfig(arguments []string) (migrate.Config, error) {
	if len(arguments) == 0 || arguments[0] != "up" {
		return migrate.Config{}, errors.New("only the explicit 'up' command is supported; down migrations are refused")
	}
	flags := flag.NewFlagSet("migrate up", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	databaseURL := flags.String("database-url", "", "explicit PostgreSQL URL")
	migrationDirectory := flags.String("migrations-dir", filepath.Join("..", "migrations"), "repository migration directory")
	schema := flags.String("schema", "public", "migration schema")
	if err := flags.Parse(arguments[1:]); err != nil {
		return migrate.Config{}, errors.New("invalid migrate up flags")
	}
	if flags.NArg() != 0 {
		return migrate.Config{}, errors.New("migrate up accepts no positional arguments")
	}
	if strings.TrimSpace(*databaseURL) == "" {
		*databaseURL = os.Getenv(migrationDatabaseURLEnv)
	}
	if strings.TrimSpace(*databaseURL) == "" {
		return migrate.Config{}, errors.New("an explicit --database-url or CLOUD_API_MIGRATE_DATABASE_URL is required")
	}
	return migrate.Config{
		DatabaseURL: *databaseURL,
		Directory:   *migrationDirectory,
		Schema:      *schema,
	}, nil
}

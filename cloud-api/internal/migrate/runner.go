// Package migrate applies Cloud API up migrations without an ORM or migration framework.
package migrate

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"unicode"

	"github.com/jackc/pgx/v5/pgxpool"
)

const ledgerTable = "schema_migrations"

var upMigrationName = regexp.MustCompile(`^(\d+)_[-a-zA-Z0-9_]+\.up\.sql$`)

// Config identifies an explicit database and a directory containing only the
// repository's migration files. Run applies up migrations only.
type Config struct {
	DatabaseURL string
	Directory   string
	Schema      string
}

type migration struct {
	Version            string
	Name               string
	SQL                string
	Checksum           string
	OutsideTransaction bool
}

// Run applies every pending up migration in lexical numeric order. It never
// executes down migrations and it never logs the database URL.
func Run(ctx context.Context, config Config) error {
	if ctx == nil {
		return errors.New("migration context is required")
	}
	if strings.TrimSpace(config.DatabaseURL) == "" {
		return errors.New("migration database URL is required")
	}
	if strings.TrimSpace(config.Directory) == "" {
		return errors.New("migration directory is required")
	}
	if strings.TrimSpace(config.Schema) == "" {
		config.Schema = "public"
	}
	migrations, err := discoverMigrations(config.Directory)
	if err != nil {
		return err
	}

	poolConfig, err := pgxpool.ParseConfig(config.DatabaseURL)
	if err != nil {
		return errors.New("migration database URL is invalid")
	}
	poolConfig.ConnConfig.RuntimeParams["search_path"] = quoteIdentifier(config.Schema)
	pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
	if err != nil {
		return errors.New("open migration database")
	}
	defer pool.Close()

	if _, err := pool.Exec(ctx, "CREATE SCHEMA IF NOT EXISTS "+quoteIdentifier(config.Schema)); err != nil {
		return errors.New("create migration schema")
	}
	ledger := quoteIdentifier(config.Schema) + "." + ledgerTable
	if _, err := pool.Exec(ctx, "CREATE TABLE IF NOT EXISTS "+ledger+" (version text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())"); err != nil {
		return errors.New("create migration ledger")
	}
	applied, err := loadApplied(ctx, pool, ledger)
	if err != nil {
		return errors.New("read migration ledger")
	}
	for version, checksum := range applied {
		found := false
		for _, candidate := range migrations {
			if candidate.Version == version {
				found = true
				if candidate.Checksum != checksum {
					return fmt.Errorf("migration checksum mismatch for version %s", version)
				}
				break
			}
		}
		if !found {
			return fmt.Errorf("migration ledger contains unknown version %s", version)
		}
	}
	for _, candidate := range migrations {
		if _, ok := applied[candidate.Version]; ok {
			continue
		}
		if err := apply(ctx, pool, ledger, candidate); err != nil {
			return fmt.Errorf("apply migration %s", candidate.Version)
		}
	}
	return nil
}

func loadApplied(ctx context.Context, pool *pgxpool.Pool, ledger string) (map[string]string, error) {
	rows, err := pool.Query(ctx, "SELECT version, checksum FROM "+ledger)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	applied := make(map[string]string)
	for rows.Next() {
		var version, checksum string
		if err := rows.Scan(&version, &checksum); err != nil {
			return nil, err
		}
		applied[version] = checksum
	}
	return applied, rows.Err()
}

func apply(ctx context.Context, pool *pgxpool.Pool, ledger string, candidate migration) error {
	if candidate.OutsideTransaction {
		if _, err := pool.Exec(ctx, candidate.SQL); err != nil {
			return err
		}
		_, err := pool.Exec(ctx, "INSERT INTO "+ledger+" (version, checksum) VALUES ($1, $2)", candidate.Version, candidate.Checksum)
		return err
	}
	transaction, err := pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = transaction.Rollback(ctx) }()
	if _, err := transaction.Exec(ctx, transactionalSQL(candidate.SQL)); err != nil {
		return err
	}
	if _, err := transaction.Exec(ctx, "INSERT INTO "+ledger+" (version, checksum) VALUES ($1, $2)", candidate.Version, candidate.Checksum); err != nil {
		return err
	}
	return transaction.Commit(ctx)
}

func discoverMigrations(directory string) ([]migration, error) {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return nil, errors.New("read migration directory")
	}
	migrations := make([]migration, 0, len(entries))
	versions := make(map[string]struct{})
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".up.sql") {
			continue
		}
		match := upMigrationName.FindStringSubmatch(entry.Name())
		if match == nil {
			return nil, fmt.Errorf("invalid up migration filename %q", entry.Name())
		}
		if _, duplicate := versions[match[1]]; duplicate {
			return nil, fmt.Errorf("duplicate migration version %s", match[1])
		}
		contents, err := os.ReadFile(filepath.Join(directory, entry.Name()))
		if err != nil {
			return nil, fmt.Errorf("read migration %s", entry.Name())
		}
		digest := sha256.Sum256(contents)
		migrations = append(migrations, migration{
			Version:            match[1],
			Name:               entry.Name(),
			SQL:                string(contents),
			Checksum:           fmt.Sprintf("%x", digest),
			OutsideTransaction: createsIndexConcurrently(string(contents)),
		})
		versions[match[1]] = struct{}{}
	}
	if len(migrations) == 0 {
		return nil, errors.New("no up migrations found")
	}
	sort.Slice(migrations, func(left, right int) bool {
		return migrations[left].Name < migrations[right].Name
	})
	return migrations, nil
}

var transactionEnvelope = regexp.MustCompile(`(?is)^\s*BEGIN\s*;\s*(.*?)\s*COMMIT\s*;?\s*$`)

// transactionalSQL preserves the migration checksum while removing only an
// enclosing BEGIN/COMMIT pair: the runner itself owns the migration's tx.
func transactionalSQL(sql string) string {
	match := transactionEnvelope.FindStringSubmatch(sql)
	if match == nil {
		return sql
	}
	return match[1]
}

func quoteIdentifier(value string) string {
	return `"` + strings.ReplaceAll(value, `"`, `""`) + `"`
}

func createsIndexConcurrently(sql string) bool {
	words := sqlWords(sql)
	for index := 0; index < len(words); index++ {
		if words[index] != "CREATE" || index+2 >= len(words) {
			continue
		}
		next := index + 1
		if words[next] == "UNIQUE" {
			next++
		}
		if next+1 < len(words) && words[next] == "INDEX" && words[next+1] == "CONCURRENTLY" {
			return true
		}
	}
	return false
}

func sqlWords(sql string) []string {
	words := make([]string, 0)
	for index := 0; index < len(sql); {
		switch {
		case strings.HasPrefix(sql[index:], "--"):
			if newline := strings.IndexByte(sql[index:], '\n'); newline >= 0 {
				index += newline + 1
			} else {
				return words
			}
		case strings.HasPrefix(sql[index:], "/*"):
			if end := strings.Index(sql[index+2:], "*/"); end >= 0 {
				index += end + 4
			} else {
				return words
			}
		case sql[index] == '\'' || sql[index] == '"':
			quote := sql[index]
			index++
			for index < len(sql) {
				if sql[index] == quote {
					index++
					if quote == '\'' && index < len(sql) && sql[index] == '\'' {
						index++
						continue
					}
					break
				}
				index++
			}
		case unicode.IsLetter(rune(sql[index])):
			start := index
			for index < len(sql) && (unicode.IsLetter(rune(sql[index])) || sql[index] == '_') {
				index++
			}
			words = append(words, strings.ToUpper(sql[start:index]))
		default:
			index++
		}
	}
	return words
}

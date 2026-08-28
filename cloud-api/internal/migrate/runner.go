// Package migrate applies Cloud API up migrations without an ORM or migration framework.
package migrate

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"unicode"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	ledgerTable           = "schema_migrations"
	migrationAdvisoryLock = int64(402860951630942811)
)

var (
	upMigrationName          = regexp.MustCompile(`^(\d+)_[-a-zA-Z0-9_]+\.up\.sql$`)
	transactionEnvelope      = regexp.MustCompile(`(?is)^\s*(?:(?:--[^\n]*(?:\n|$))|(?:/\*.*?\*/\s*))*BEGIN(?:\s+(?:WORK|TRANSACTION))?\s*;\s*(.*?)\s*COMMIT(?:\s+(?:WORK|TRANSACTION))?\s*;\s*(?:(?:--[^\n]*(?:\n|$))|(?:/\*.*?\*/\s*))*$`)
	concurrentIndexStatement = regexp.MustCompile(`(?is)^\s*CREATE\s+(?:(UNIQUE)\s+)?INDEX\s+CONCURRENTLY\s+(?:IF\s+NOT\s+EXISTS\s+)?(` + identifierPattern + `)\s+ON\s+(?:(` + identifierPattern + `)\.)?(` + identifierPattern + `)(?:\s+USING\s+(` + identifierPattern + `))?\s*\(\s*(` + identifierPattern + `(?:\s*,\s*` + identifierPattern + `)*)\s*\)\s*;\s*$`)
	sqlStatePattern          = regexp.MustCompile(`^[0-9A-Z]{5}$`)
)

const (
	identifierPattern = `[a-z_][a-z0-9_]*`
)

// ErrUnsafeDatabaseTarget means the supplied DSN was rejected before any
// network connection or migration-file access. Its text intentionally excludes
// the DSN and its credentials.
var ErrUnsafeDatabaseTarget = errors.New("migration database target is not approved")

var errUnsupportedSQLQuoting = errors.New("migration contains unsupported SQL quoting")

type databaseFailure struct {
	Context   string
	Migration string
	Category  string
	SQLState  string
}

func (failure *databaseFailure) Error() string {
	if failure.Migration != "" {
		return fmt.Sprintf("context=%s migration=%s category=%s sqlstate=%s", failure.Context, failure.Migration, failure.Category, failure.SQLState)
	}
	return fmt.Sprintf("context=%s category=%s sqlstate=%s", failure.Context, failure.Category, failure.SQLState)
}

func databaseFailureFor(operation string, err error) *databaseFailure {
	failure := &databaseFailure{Context: operation, Category: "driver", SQLState: "none"}
	switch {
	case errors.Is(err, context.Canceled):
		failure.Category = "context-canceled"
	case errors.Is(err, context.DeadlineExceeded):
		failure.Category = "context-deadline"
	case pgconn.Timeout(err):
		failure.Category = "timeout"
	default:
		var postgresError *pgconn.PgError
		var scanError pgx.ScanArgError
		var connectError *pgconn.ConnectError
		var networkError net.Error
		switch {
		case errors.As(err, &postgresError):
			failure.Category = "postgres-server"
			if sqlStatePattern.MatchString(postgresError.Code) {
				failure.SQLState = postgresError.Code
			}
		case errors.As(err, &scanError):
			failure.Category = "result-decode"
		case errors.As(err, &connectError):
			failure.Category = "connect"
		case errors.As(err, &networkError):
			failure.Category = "network"
		case errors.Is(err, pgx.ErrNoRows):
			failure.Category = "no-rows"
		}
	}
	return failure
}

func unsupportedServerVersionFailure() *databaseFailure {
	return &databaseFailure{Context: "runner.check-server-version", Category: "unsupported-server-version", SQLState: "none"}
}

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
	ConcurrentIndex    concurrentIndexDefinition
}

type concurrentIndexDefinition struct {
	Schema                string
	Name                  string
	Table                 string
	Method                string
	Unique                bool
	Columns               []string
	KeyCount              int
	IncludeCount          int
	Valid                 bool
	DefaultBtreeOpclasses bool
	DefaultCollations     bool
	DefaultSortOrder      bool
	NoPredicate           bool
	NullsNotDistinct      bool
	Immediate             bool
}

const concurrentIndexDefinitionQuery = `
SELECT i.indisvalid,
       table_namespace.nspname,
       table_class.relname,
       access_method.amname,
       i.indisunique,
       i.indnullsnotdistinct,
       i.indimmediate,
       i.indnkeyatts,
       i.indnatts - i.indnkeyatts,
       COALESCE(array_agg(attribute.attname ORDER BY key_column.ordinality) FILTER (WHERE key_column.ordinality <= i.indnkeyatts), ARRAY[]::text[]),
       COALESCE(bool_and((attribute.attname IS NOT NULL AND opclass.opcdefault) IS TRUE) FILTER (WHERE key_column.ordinality <= i.indnkeyatts), false),
       COALESCE(bool_and((key_column.collation_oid = 0 OR key_column.collation_oid = attribute.attcollation) IS TRUE) FILTER (WHERE key_column.ordinality <= i.indnkeyatts), false),
       COALESCE(bool_and((key_column.option = 0) IS TRUE) FILTER (WHERE key_column.ordinality <= i.indnkeyatts), false),
       i.indpred IS NULL
FROM pg_catalog.pg_index i
JOIN pg_catalog.pg_class index_class ON index_class.oid = i.indexrelid
JOIN pg_catalog.pg_namespace index_namespace ON index_namespace.oid = index_class.relnamespace
JOIN pg_catalog.pg_class table_class ON table_class.oid = i.indrelid
JOIN pg_catalog.pg_namespace table_namespace ON table_namespace.oid = table_class.relnamespace
JOIN pg_catalog.pg_am access_method ON access_method.oid = index_class.relam
LEFT JOIN LATERAL unnest(i.indkey::smallint[], i.indclass::oid[], i.indcollation::oid[], i.indoption::smallint[]) WITH ORDINALITY AS key_column(attnum, opclass_oid, collation_oid, option, ordinality) ON true
LEFT JOIN pg_catalog.pg_attribute attribute ON attribute.attrelid = table_class.oid AND attribute.attnum = key_column.attnum
LEFT JOIN pg_catalog.pg_opclass opclass ON opclass.oid = key_column.opclass_oid
WHERE index_namespace.nspname = $1 AND index_class.relname = $2
GROUP BY i.indisvalid, table_namespace.nspname, table_class.relname, access_method.amname, i.indisunique, i.indnullsnotdistinct, i.indimmediate, i.indnkeyatts, i.indnatts, (i.indpred IS NULL)`

var migrationDSNAllowedKeys = []string{"host", "port", "database", "user", "password", "sslmode"}

// ValidateDatabaseURL permits exactly the host test listener or, when called
// by the controlled Compose migration service, the exact Compose service DNS
// target. Only sslmode=disable is permitted as a URL query parameter. It
// rejects aliases, IPv6, socket/keyword connection strings, query overrides,
// runtime parameters, and multi-host forms before a connection is opened.
func ValidateDatabaseURL(databaseURL string, allowComposeServiceTarget bool) error {
	parsed, err := url.ParseRequestURI(databaseURL)
	if err != nil || (parsed.Scheme != "postgres" && parsed.Scheme != "postgresql") || parsed.User == nil || parsed.User.Username() == "" || parsed.Host == "" || parsed.RawQuery == "" && parsed.ForceQuery {
		return ErrUnsafeDatabaseTarget
	}
	if parsed.Host != parsed.Hostname()+":"+parsed.Port() || parsed.Port() == "" || parsed.Path == "" || parsed.Path == "/" {
		return ErrUnsafeDatabaseTarget
	}
	for key, values := range parsed.Query() {
		if key != "sslmode" || len(values) != 1 || values[0] != "disable" {
			return ErrUnsafeDatabaseTarget
		}
	}
	connectionConfig, err := pgx.ParseConfigWithOptions(databaseURL, pgx.ParseConfigOptions{
		ParseConfigOptions: pgconn.ParseConfigOptions{ConnStringAllowedKeys: migrationDSNAllowedKeys},
	})
	if err != nil || !validMigrationConnectionConfig(connectionConfig, allowComposeServiceTarget) {
		return ErrUnsafeDatabaseTarget
	}
	return nil
}

func validMigrationConnectionConfig(connectionConfig *pgx.ConnConfig, allowComposeServiceTarget bool) bool {
	if connectionConfig == nil || len(connectionConfig.Config.Fallbacks) != 0 || len(connectionConfig.Config.RuntimeParams) != 0 {
		return false
	}
	if connectionConfig.Config.Host == "127.0.0.1" && connectionConfig.Config.Port == 15432 {
		return true
	}
	return allowComposeServiceTarget && connectionConfig.Config.Host == "postgres" && connectionConfig.Config.Port == 5432
}

// Run applies every pending up migration in lexical numeric order. It never
// executes down migrations, never logs the database URL, and serializes all
// ledger validation and application through a PostgreSQL advisory lock.
func Run(ctx context.Context, config Config) error {
	if ctx == nil {
		return errors.New("migration context is required")
	}
	if strings.TrimSpace(config.DatabaseURL) == "" {
		return errors.New("migration database URL is required")
	}
	if err := ValidateDatabaseURL(config.DatabaseURL, isComposeMigrationService()); err != nil {
		return err
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
	if err != nil || !validMigrationConnectionConfig(poolConfig.ConnConfig, isComposeMigrationService()) {
		return ErrUnsafeDatabaseTarget
	}
	poolConfig.ConnConfig.RuntimeParams["search_path"] = quoteIdentifier(config.Schema)
	pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
	if err != nil {
		return databaseFailureFor("runner.open-database", err)
	}
	defer pool.Close()
	connection, err := pool.Acquire(ctx)
	if err != nil {
		return databaseFailureFor("runner.acquire-connection", err)
	}
	defer connection.Release()
	var serverVersion int
	if err := connection.QueryRow(ctx, "SELECT current_setting('server_version_num')::integer").Scan(&serverVersion); err != nil {
		return databaseFailureFor("runner.read-server-version", err)
	}
	if serverVersion < 150000 {
		return unsupportedServerVersionFailure()
	}
	if _, err := connection.Exec(ctx, "SELECT pg_advisory_lock($1)", migrationAdvisoryLock); err != nil {
		return databaseFailureFor("runner.acquire-advisory-lock", err)
	}
	defer func() {
		_, _ = connection.Exec(context.Background(), "SELECT pg_advisory_unlock($1)", migrationAdvisoryLock)
	}()

	if _, err := connection.Exec(ctx, "CREATE SCHEMA IF NOT EXISTS "+quoteIdentifier(config.Schema)); err != nil {
		return databaseFailureFor("runner.create-schema", err)
	}
	ledger := quoteIdentifier(config.Schema) + "." + ledgerTable
	if _, err := connection.Exec(ctx, "CREATE TABLE IF NOT EXISTS "+ledger+" (version text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())"); err != nil {
		return databaseFailureFor("runner.create-ledger", err)
	}
	applied, err := loadApplied(ctx, connection, ledger)
	if err != nil {
		return databaseFailureFor("runner.read-ledger", err)
	}
	if len(applied) == 0 {
		existing, err := hasExistingCloudSchema(ctx, connection, config.Schema)
		if err != nil {
			return databaseFailureFor("runner.inspect-existing-schema", err)
		}
		if existing {
			return errors.New("existing Cloud schema has an empty migration ledger; do not replay 000001: restore the matching ledger from backup or rebuild the dedicated development volume")
		}
	}
	if err := verifyApplied(migrations, applied); err != nil {
		return err
	}
	for _, candidate := range migrations {
		if _, ok := applied[candidate.Version]; ok {
			continue
		}
		if err := apply(ctx, connection, ledger, config.Schema, candidate); err != nil {
			var failure *databaseFailure
			if errors.As(err, &failure) {
				failure.Migration = candidate.Version
				return failure
			}
			return fmt.Errorf("apply migration %s", candidate.Version)
		}
	}
	return nil
}

type queryer interface {
	Query(context.Context, string, ...any) (pgx.Rows, error)
}

func loadApplied(ctx context.Context, database queryer, ledger string) (map[string]string, error) {
	rows, err := database.Query(ctx, "SELECT version, checksum FROM "+ledger)
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

func verifyApplied(migrations []migration, applied map[string]string) error {
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
	return nil
}

func hasExistingCloudSchema(ctx context.Context, connection *pgxpool.Conn, schema string) (bool, error) {
	const cloudSchemaRelation = `
SELECT EXISTS (
  SELECT 1 FROM pg_catalog.pg_class c
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = $1
    AND c.relkind IN ('r', 'p')
    AND c.relname = ANY($2)
)`
	var exists bool
	err := connection.QueryRow(ctx, cloudSchemaRelation, schema, []string{
		"users", "entitlements", "refresh_tokens", "code_batches", "redemption_codes",
		"translation_sessions", "usage_records", "feedback_consents", "feedback_artifacts", "audit_logs",
	}).Scan(&exists)
	return exists, err
}

func apply(ctx context.Context, connection *pgxpool.Conn, ledger, schema string, candidate migration) error {
	if candidate.OutsideTransaction {
		if _, err := connection.Exec(ctx, candidate.SQL); err != nil {
			return databaseFailureFor("runner.execute-concurrent-index", err)
		}
		expected := candidate.ConcurrentIndex
		if expected.Schema == "" {
			expected.Schema = schema
		}
		valid, err := concurrentIndexMatches(ctx, connection, expected, schema)
		if err != nil {
			return databaseFailureFor("runner.verify-concurrent-index", err)
		}
		if !valid {
			return &databaseFailure{Context: "runner.verify-concurrent-index", Category: "invalid-concurrent-index", SQLState: "none"}
		}
		if _, err := connection.Exec(ctx, "INSERT INTO "+ledger+" (version, checksum) VALUES ($1, $2)", candidate.Version, candidate.Checksum); err != nil {
			return databaseFailureFor("runner.insert-concurrent-ledger", err)
		}
		return nil
	}
	sql, err := transactionalSQL(candidate.SQL)
	if err != nil {
		return err
	}
	transaction, err := connection.Begin(ctx)
	if err != nil {
		return databaseFailureFor("runner.begin-transactional-migration", err)
	}
	defer func() { _ = transaction.Rollback(ctx) }()
	if _, err := transaction.Exec(ctx, sql); err != nil {
		return databaseFailureFor("runner.execute-transactional-migration", err)
	}
	if _, err := transaction.Exec(ctx, "INSERT INTO "+ledger+" (version, checksum) VALUES ($1, $2)", candidate.Version, candidate.Checksum); err != nil {
		return databaseFailureFor("runner.insert-transactional-ledger", err)
	}
	if err := transaction.Commit(ctx); err != nil {
		return databaseFailureFor("runner.commit-transactional-migration", err)
	}
	return nil
}

func concurrentIndexMatches(ctx context.Context, connection *pgxpool.Conn, expected concurrentIndexDefinition, defaultSchema string) (bool, error) {
	if expected.Schema == "" {
		expected.Schema = defaultSchema
	}
	var actual concurrentIndexDefinition
	if err := connection.QueryRow(ctx, concurrentIndexDefinitionQuery, expected.Schema, expected.Name).Scan(
		&actual.Valid, &actual.Schema, &actual.Table, &actual.Method, &actual.Unique, &actual.NullsNotDistinct, &actual.Immediate, &actual.KeyCount, &actual.IncludeCount, &actual.Columns,
		&actual.DefaultBtreeOpclasses, &actual.DefaultCollations, &actual.DefaultSortOrder, &actual.NoPredicate,
	); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return false, nil
		}
		return false, err
	}
	actual.Name = expected.Name
	return sameConcurrentIndexDefinition(expected, actual), nil
}

func sameConcurrentIndexDefinition(expected, actual concurrentIndexDefinition) bool {
	if !actual.Valid || actual.KeyCount != len(expected.Columns) || actual.IncludeCount != 0 || !actual.DefaultBtreeOpclasses || !actual.DefaultCollations || !actual.DefaultSortOrder || !actual.NoPredicate || expected.Schema != actual.Schema || expected.Name != actual.Name || expected.Table != actual.Table || expected.Method != "btree" || actual.Method != "btree" || expected.Unique != actual.Unique || expected.NullsNotDistinct != actual.NullsNotDistinct || expected.Immediate != actual.Immediate || len(expected.Columns) != len(actual.Columns) {
		return false
	}
	for index := range expected.Columns {
		if expected.Columns[index] != actual.Columns[index] {
			return false
		}
	}
	return true
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
		concurrentIndex, outsideTransaction, err := concurrentIndexTarget(string(contents))
		if err != nil {
			return nil, fmt.Errorf("invalid concurrent migration %s", entry.Name())
		}
		digest := sha256.Sum256(contents)
		migrations = append(migrations, migration{
			Version:            match[1],
			Name:               entry.Name(),
			SQL:                string(contents),
			Checksum:           fmt.Sprintf("%x", digest),
			OutsideTransaction: outsideTransaction,
			ConcurrentIndex:    concurrentIndex,
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

// transactionalSQL preserves the migration checksum while removing a complete
// outer transaction envelope. Any other transaction-control statement is
// refused, preventing runner-managed transactions from being committed early.
func transactionalSQL(sql string) (string, error) {
	words, err := sqlWords(sql)
	if err != nil {
		return "", err
	}
	if !hasTransactionControl(words) {
		return sql, nil
	}
	match := transactionEnvelope.FindStringSubmatch(sql)
	if match == nil {
		return "", errors.New("migration contains unsupported transaction control")
	}
	bodyWords, err := sqlWords(match[1])
	if err != nil || hasTransactionControl(bodyWords) {
		return "", errors.New("migration contains unsupported transaction control")
	}
	return match[1], nil
}

func hasTransactionControl(words []string) bool {
	for index, word := range words {
		switch word {
		case "BEGIN", "COMMIT", "END", "ROLLBACK", "ABORT", "START", "SAVEPOINT", "RELEASE", "PREPARE":
			return true
		case "SET":
			if index+1 < len(words) && words[index+1] == "TRANSACTION" {
				return true
			}
			if index+4 < len(words) && words[index+1] == "SESSION" && words[index+2] == "CHARACTERISTICS" && words[index+3] == "AS" && words[index+4] == "TRANSACTION" {
				return true
			}
		}
	}
	return false
}

// isComposeMigrationService makes postgres:5432 available only inside the
// containerized one-shot service. The environment opt-in alone is insufficient
// on a host process, so it cannot turn the service-network exception into a
// host-network bypass.
func isComposeMigrationService() bool {
	if os.Getenv("CLOUD_API_MIGRATE_COMPOSE_SERVICE") != "1" {
		return false
	}
	_, err := os.Stat("/.dockerenv")
	return err == nil
}

func quoteIdentifier(value string) string {
	return `"` + strings.ReplaceAll(value, `"`, `""`) + `"`
}

func createsIndexConcurrently(sql string) bool {
	_, found, _ := concurrentIndexTarget(sql)
	return found
}

func concurrentIndexTarget(sql string) (concurrentIndexDefinition, bool, error) {
	words, err := sqlWords(sql)
	if err != nil {
		return concurrentIndexDefinition{}, false, err
	}
	found := false
	for index := 0; index < len(words); index++ {
		if words[index] != "CREATE" || index+2 >= len(words) {
			continue
		}
		next := index + 1
		if words[next] == "UNIQUE" {
			next++
		}
		if next+1 < len(words) && words[next] == "INDEX" && words[next+1] == "CONCURRENTLY" {
			found = true
			break
		}
	}
	if !found {
		return concurrentIndexDefinition{}, false, nil
	}
	match := concurrentIndexStatement.FindStringSubmatch(sql)
	if match == nil {
		return concurrentIndexDefinition{}, true, errors.New("concurrent migration must contain exactly one supported unquoted CREATE INDEX CONCURRENTLY statement")
	}
	method := strings.ToLower(match[5])
	if method == "" {
		method = "btree"
	}
	if method != "btree" {
		return concurrentIndexDefinition{}, true, errors.New("concurrent migration must use btree with default index options")
	}
	return concurrentIndexDefinition{
		Schema:           strings.ToLower(match[3]),
		Name:             strings.ToLower(match[2]),
		Table:            strings.ToLower(match[4]),
		Method:           method,
		Unique:           match[1] != "",
		Columns:          strings.FieldsFunc(strings.ToLower(match[6]), func(r rune) bool { return r == ',' || unicode.IsSpace(r) }),
		NullsNotDistinct: false,
		Immediate:        true,
	}, true, nil
}

// sqlWords returns executable SQL words while skipping lexical forms that this
// restricted migration grammar permits. Escape and Unicode-escape strings are
// rejected instead of partially emulating PostgreSQL escape semantics.
func sqlWords(sql string) ([]string, error) {
	words := make([]string, 0)
	for index := 0; index < len(sql); {
		switch {
		case strings.HasPrefix(sql[index:], "--"):
			if newline := strings.IndexByte(sql[index:], '\n'); newline >= 0 {
				index += newline + 1
			} else {
				return words, nil
			}
		case strings.HasPrefix(sql[index:], "/*"):
			next, ok := blockCommentEnd(sql, index)
			if !ok {
				return nil, errUnsupportedSQLQuoting
			}
			index = next
		case isEscapeStringPrefix(sql, index) || isUnicodeEscapePrefix(sql, index):
			return nil, errUnsupportedSQLQuoting
		case sql[index] == '\'' || sql[index] == '"':
			next, ok := quotedEnd(sql, index)
			if !ok {
				return nil, errUnsupportedSQLQuoting
			}
			index = next
		case sql[index] == '$':
			delimiter := dollarQuoteEnd(sql[index:])
			if (index == 0 || !sqlIdentifierByte(sql[index-1])) && delimiter != "" {
				start := index + len(delimiter)
				close := strings.Index(sql[start:], delimiter)
				if close < 0 {
					return nil, errUnsupportedSQLQuoting
				}
				index = start + close + len(delimiter)
				continue
			}
			index++
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
	return words, nil
}

func blockCommentEnd(sql string, start int) (int, bool) {
	depth := 1
	for index := start + 2; index < len(sql)-1; index++ {
		switch sql[index : index+2] {
		case "/*":
			depth++
			index++
		case "*/":
			depth--
			index++
			if depth == 0 {
				return index + 1, true
			}
		}
	}
	return 0, false
}

func quotedEnd(sql string, start int) (int, bool) {
	quote := sql[start]
	for index := start + 1; index < len(sql); index++ {
		if sql[index] != quote {
			continue
		}
		if index+1 < len(sql) && sql[index+1] == quote {
			index++
			continue
		}
		return index + 1, true
	}
	return 0, false
}

func isEscapeStringPrefix(sql string, index int) bool {
	return index+1 < len(sql) && (sql[index] == 'E' || sql[index] == 'e') && sql[index+1] == '\'' && (index == 0 || !sqlIdentifierByte(sql[index-1]))
}

func isUnicodeEscapePrefix(sql string, index int) bool {
	return index+2 < len(sql) && (sql[index] == 'U' || sql[index] == 'u') && sql[index+1] == '&' && (sql[index+2] == '\'' || sql[index+2] == '"') && (index == 0 || !sqlIdentifierByte(sql[index-1]))
}

func sqlIdentifierByte(value byte) bool {
	return value == '_' || value == '$' || value >= 0x80 || (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9')
}

func dollarQuoteEnd(sql string) string {
	if len(sql) < 2 || sql[0] != '$' {
		return ""
	}
	if sql[1] == '$' {
		return "$$"
	}
	if !isDollarQuoteTagStart(sql[1]) {
		return ""
	}
	for index := 2; index < len(sql); index++ {
		if sql[index] == '$' {
			return sql[:index+1]
		}
		if !isDollarQuoteTagByte(sql[index]) {
			return ""
		}
	}
	return ""
}

func isDollarQuoteTagStart(value byte) bool {
	return value == '_' || (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
}

func isDollarQuoteTagByte(value byte) bool {
	return isDollarQuoteTagStart(value) || (value >= '0' && value <= '9')
}

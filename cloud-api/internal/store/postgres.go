// Package store owns PostgreSQL connectivity for cloud-api.
package store

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

const applicationName = "dngmeng-cloud-api"

// Postgres wraps the application connection pool. Connection strings and
// driver errors must not be returned directly to HTTP clients or logged.
type rows interface {
	Close()
	Next() bool
	Scan(...any) error
	Err() error
}

type queryFunc func(context.Context, string, ...any) (rows, error)

type Postgres struct {
	pool  *pgxpool.Pool
	query queryFunc
}

// Open parses the PostgreSQL URL and creates a connection pool. The caller
// decides when to Ping so startup and readiness can use their own deadlines.
func Open(ctx context.Context, databaseURL string) (*Postgres, error) {
	if ctx == nil {
		return nil, errors.New("open postgres: context is required")
	}
	poolConfig, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, errors.New("open postgres: invalid configuration")
	}
	poolConfig.ConnConfig.RuntimeParams["application_name"] = applicationName
	poolConfig.ConnConfig.RuntimeParams["timezone"] = "UTC"

	pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
	if err != nil {
		return nil, fmt.Errorf("open postgres pool: %w", err)
	}
	return &Postgres{
		pool: pool,
		query: func(ctx context.Context, sql string, arguments ...any) (rows, error) {
			return pool.Query(ctx, sql, arguments...)
		},
	}, nil
}

// Ping verifies that PostgreSQL can accept a query.
func (p *Postgres) Ping(ctx context.Context) error {
	if p == nil || p.pool == nil {
		return errors.New("postgres pool is not initialized")
	}
	return p.pool.Ping(ctx)
}

// Close releases all pooled connections.
func (p *Postgres) Close() {
	if p != nil && p.pool != nil {
		p.pool.Close()
	}
}

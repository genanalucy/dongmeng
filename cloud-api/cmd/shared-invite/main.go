// Command shared-invite provisions a shared annual code. Only a mode-600
// operator delivery file receives plaintext; stdout reports non-secret metadata.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/dngmeng/cloud-api/internal/store"
	"github.com/google/uuid"
)

type options struct {
	admin    uuid.UUID
	name     string
	deadline time.Time
	output   string
}

func parse(args []string, now time.Time) (options, error) {
	fs := flag.NewFlagSet("shared-invite", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	admin := fs.String("admin-id", "", "administrator UUID")
	name := fs.String("name", "", "campaign label")
	until := fs.String("claim-until", "", "exclusive RFC3339 deadline")
	output := fs.String("output", "", "new private delivery file")
	if fs.Parse(args) != nil || fs.NArg() != 0 {
		return options{}, errors.New("invalid arguments")
	}
	id, err := uuid.Parse(*admin)
	if err != nil || id == uuid.Nil {
		return options{}, errors.New("administrator UUID required")
	}
	deadline, err := time.Parse(time.RFC3339, *until)
	if err != nil || !deadline.After(now) {
		return options{}, errors.New("future RFC3339 claim deadline required")
	}
	input, err := domain.ParseCreateBatchInput(*name, 1)
	if err != nil {
		return options{}, errors.New("campaign name required")
	}
	if *output == "" {
		return options{}, errors.New("new private output path required")
	}
	return options{id, input.Name, deadline.UTC(), *output}, nil
}

func validateDatabaseURL(value string) error {
	u, err := url.Parse(value)
	if err != nil || (u.Scheme != "postgres" && u.Scheme != "postgresql") {
		return errors.New("SHARED_INVITE_DATABASE_URL must be a PostgreSQL URL")
	}
	host, port, err := net.SplitHostPort(u.Host)
	if err != nil || host != "127.0.0.1" || port != "15432" {
		return errors.New("SHARED_INVITE_DATABASE_URL must target 127.0.0.1:15432")
	}
	for _, key := range []string{"host", "hostaddr", "port", "service", "servicefile", "options"} {
		if u.Query().Has(key) {
			return errors.New("database target overrides are not accepted")
		}
	}
	return nil
}

func run(ctx context.Context, args []string, databaseURL string, output io.Writer) error {
	now := time.Now().UTC()
	opts, err := parse(args, now)
	if err != nil {
		return err
	}
	if err = validateDatabaseURL(databaseURL); err != nil {
		return err
	}
	// Reserve the destination before provisioning; never overwrite a prior code.
	file, err := os.OpenFile(opts.output, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return errors.New("cannot reserve new private delivery file")
	}
	defer file.Close()
	code, hash, err := auth.RandomCode()
	if err != nil {
		return errors.New("generate invitation")
	}
	if _, err = fmt.Fprintln(file, code); err != nil {
		return errors.New("write private delivery file")
	}
	if err = file.Sync(); err != nil {
		return errors.New("persist private delivery file")
	}
	db, err := store.Open(ctx, databaseURL)
	if err != nil {
		return errors.New("open invitation database")
	}
	defer db.Close()
	batch, err := db.CreateSharedRedemptionCode(ctx, opts.admin, opts.name, hash, now, opts.deadline)
	if err != nil {
		return errors.New("provision invitation failed; verify batch state before retrying or distributing delivery file")
	}
	_, err = fmt.Fprintf(output, "batch_id=%s\nclaim_until=%s\nduration_days=365\n", batch, opts.deadline.Format(time.RFC3339))
	return err
}

func main() {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := run(ctx, os.Args[1:], os.Getenv("SHARED_INVITE_DATABASE_URL"), os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "shared invitation:", err)
		os.Exit(1)
	}
}

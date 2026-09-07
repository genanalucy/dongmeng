package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/dngmeng/cloud-api/internal/store"
)

func main() {
	if err := run(context.Background(), os.Args[1:], os.Stdin, os.Getenv("DATABASE_URL")); err != nil {
		fmt.Fprintln(os.Stderr, "bootstrap admin failed:", err)
		os.Exit(1)
	}
	fmt.Println("first administrator created")
}

func run(ctx context.Context, args []string, input io.Reader, databaseURL string) error {
	if len(args) != 1 {
		return errors.New("usage: bootstrap-admin <username>; email and password are read from standard input")
	}
	username, err := domain.ParseUsername(args[0])
	if err != nil {
		return errors.New("invalid username")
	}
	reader := bufio.NewReader(io.LimitReader(input, domain.MaxPasswordBytes+258))
	emailValue, err := reader.ReadString('\n')
	if err != nil {
		return errors.New("read email")
	}
	email, err := domain.ParseEmail(strings.TrimSuffix(strings.TrimSuffix(emailValue, "\n"), "\r"))
	if err != nil {
		return errors.New("invalid email")
	}
	password, err := reader.ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return errors.New("read password")
	}
	password = strings.TrimSuffix(strings.TrimSuffix(password, "\n"), "\r")
	parsedPassword, err := domain.ParsePassword(password)
	if err != nil {
		return errors.New("invalid password")
	}
	if err := validateDatabaseURL(databaseURL); err != nil {
		return err
	}
	passwordHash, err := auth.HashPassword(parsedPassword.String())
	if err != nil {
		return errors.New("hash password")
	}
	database, err := store.Open(ctx, databaseURL)
	if err != nil {
		return errors.New("open database")
	}
	defer database.Close()
	if err := database.Ping(ctx); err != nil {
		return errors.New("database unavailable")
	}
	_, err = database.BootstrapAdmin(ctx, username.String(), email.String(), passwordHash, time.Now().UTC())
	if errors.Is(err, domain.ErrConflict) {
		return errors.New("a different administrator already exists")
	}
	if err != nil {
		return errors.New("create administrator")
	}
	return nil
}

func validateDatabaseURL(value string) error {
	parsed, err := url.Parse(value)
	if err != nil || parsed.Scheme != "postgres" && parsed.Scheme != "postgresql" {
		return errors.New("DATABASE_URL must be a PostgreSQL URL")
	}
	host, port, err := net.SplitHostPort(parsed.Host)
	if err != nil || host != "127.0.0.1" || port != "15432" {
		return errors.New("DATABASE_URL must target 127.0.0.1:15432")
	}
	return nil
}

package main

import (
	"errors"
	"net"
	"net/url"
)

func validateDatabaseURL(value string) error {
	parsed, err := url.Parse(value)
	if err != nil || parsed.Scheme != "postgres" && parsed.Scheme != "postgresql" {
		return errors.New(setupDatabaseURLEnv + " must be a PostgreSQL URL")
	}
	host, port, err := net.SplitHostPort(parsed.Host)
	if err != nil || host != "127.0.0.1" || port != "15432" {
		return errors.New(setupDatabaseURLEnv + " must target 127.0.0.1:15432")
	}
	return nil
}

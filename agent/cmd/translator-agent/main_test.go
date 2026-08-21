package main

import (
	"testing"

	"translator-agent/internal/server"
)

func TestAllowedOriginsKeepsLoopbackAndAddsConfiguredValues(t *testing.T) {
	origins := allowedOrigins(" http://114.132.83.144:15173 , http://example.test ")

	for origin := range server.DefaultOrigins {
		if _, ok := origins[origin]; !ok {
			t.Fatalf("missing default origin %q", origin)
		}
	}
	for _, origin := range []string{"http://114.132.83.144:15173", "http://example.test"} {
		if _, ok := origins[origin]; !ok {
			t.Fatalf("missing configured origin %q", origin)
		}
	}
}

package cloudauth

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const testServiceToken = "cloud-service-token-0123456789abcdef"

func decisionServer(t *testing.T, body string) (*Client, *httptest.Server) {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(server.Close)
	client, err := NewClient(server.URL, testServiceToken, server.Client())
	if err != nil {
		t.Fatal(err)
	}
	return client, server
}

func TestAuthorizeSendsOnlyTheToken(t *testing.T) {
	var receivedMethod, receivedAuth, contentType, body string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedMethod = r.Method
		receivedAuth = r.Header.Get("Authorization")
		contentType = r.Header.Get("Content-Type")
		buffer := make([]byte, 512)
		n, _ := r.Body.Read(buffer)
		body = string(buffer[:n])
		_, _ = w.Write([]byte(`{"active":true}`))
	}))
	defer server.Close()

	client, err := NewClient(server.URL, testServiceToken, server.Client())
	if err != nil {
		t.Fatal(err)
	}
	decision, err := client.Authorize(context.Background(), "header.payload.signature")
	if err != nil || !decision.Active {
		t.Fatalf("Authorize() = %#v, %v", decision, err)
	}
	// The request carries the token and nothing that could assert identity.
	if receivedMethod != http.MethodPost || receivedAuth != "Bearer "+testServiceToken ||
		!strings.Contains(contentType, "application/json") ||
		strings.Contains(body, "user_id") || strings.Contains(body, "session_id") ||
		!strings.Contains(body, "header.payload.signature") {
		t.Fatalf("request = %s %s %s %s", receivedMethod, receivedAuth, contentType, body)
	}
}

func TestAuthorizeMapsTerminalReasons(t *testing.T) {
	tests := []struct {
		responseBody string
		active       bool
		reason       string
	}{
		{responseBody: `{"active":true}`, active: true},
		{responseBody: `{"active":false,"reason":"replaced_by_device"}`, reason: ReasonReplacedByDevice},
		{responseBody: `{"active":false,"reason":"ended"}`, reason: ReasonEnded},
		{responseBody: `{"active":false,"reason":"revoked"}`, reason: ReasonRevoked},
		{responseBody: `{"active":false,"reason":"expired"}`, reason: ReasonExpired},
		{responseBody: `{"active":false,"reason":"user_disabled"}`, reason: ReasonUserDisabled},
		{responseBody: `{"active":false,"reason":"entitlement_revoked"}`, reason: ReasonEntitlementRevoked},
		// Unknown reasons collapse to a definitive generic denial.
		{responseBody: `{"active":false,"reason":"freeform attacker text"}`},
		{responseBody: `{"active":false}`},
	}
	for _, test := range tests {
		client, _ := decisionServer(t, test.responseBody)
		decision, err := client.Authorize(context.Background(), "a.b.c")
		if err != nil || decision.Active != test.active || decision.Reason != test.reason {
			t.Fatalf("Authorize(%s) = %#v, %v", test.responseBody, decision, err)
		}
	}
}

func TestAuthorizeTreatsEndpointProblemsAsUnavailable(t *testing.T) {
	tests := []struct {
		name    string
		handler http.HandlerFunc
	}{
		{name: "service credential rejected", handler: func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusUnauthorized)
		}},
		{name: "forbidden", handler: func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusForbidden)
		}},
		{name: "server error", handler: func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusInternalServerError)
		}},
		{name: "malformed response", handler: func(w http.ResponseWriter, _ *http.Request) {
			_, _ = w.Write([]byte("not-json"))
		}},
		{name: "wrong shape", handler: func(w http.ResponseWriter, _ *http.Request) {
			_, _ = w.Write([]byte(`{"unexpected":true}`))
		}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			client, _ := decisionServer(t, "")
			server := httptest.NewServer(test.handler)
			defer server.Close()
			client.endpoint = server.URL
			decision, err := client.Authorize(context.Background(), "a.b.c")
			if decision.Active || decision.Reason != "" || !errors.Is(err, ErrUnavailable) {
				t.Fatalf("Authorize() = %#v, %v", decision, err)
			}
		})
	}
}

func TestAuthorizeDeadlineIsBoundedAndUnavailable(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		time.Sleep(300 * time.Millisecond)
		_, _ = w.Write([]byte(`{"active":true}`))
	}))
	defer server.Close()
	client, err := NewClient(server.URL, testServiceToken, server.Client())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Millisecond)
	defer cancel()
	start := time.Now()
	decision, err := client.Authorize(ctx, "a.b.c")
	if decision.Active || !errors.Is(err, ErrUnavailable) {
		t.Fatalf("Authorize() = %#v, %v", decision, err)
	}
	if elapsed := time.Since(start); elapsed > 150*time.Millisecond {
		t.Fatalf("deadline not honored: %s", elapsed)
	}
}

func TestAuthorizeRejectsUnreachableEndpointWithoutDenying(t *testing.T) {
	client, err := NewClient("http://127.0.0.1:1/authorize", testServiceToken, &http.Client{Timeout: 100 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	decision, err := client.Authorize(context.Background(), "a.b.c")
	if decision.Active || !errors.Is(err, ErrUnavailable) {
		t.Fatalf("Authorize() = %#v, %v", decision, err)
	}
}

func TestNewClientValidatesInputs(t *testing.T) {
	validURL := "http://127.0.0.1:8080/internal/v1/agent/translation-sessions/authorize"
	if _, err := NewClient("", testServiceToken, nil); err == nil {
		t.Fatal("empty endpoint accepted")
	}
	for _, endpoint := range []string{
		"ftp://example.invalid/x",
		"http://user:pass@127.0.0.1:8080/x",
		"http://127.0.0.1:8080/x?query=1",
		"http://127.0.0.1:8080/x#fragment",
		" http://127.0.0.1:8080/x",
	} {
		if _, err := NewClient(endpoint, testServiceToken, nil); err == nil {
			t.Fatalf("endpoint %q accepted", endpoint)
		}
	}
	if _, err := NewClient(validURL, "short", nil); err == nil {
		t.Fatal("short service token accepted")
	}
	if _, err := NewClient(validURL, testServiceToken, nil); err != nil {
		t.Fatalf("valid configuration rejected: %v", err)
	}
}

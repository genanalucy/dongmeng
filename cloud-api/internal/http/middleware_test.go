package httpapi

import (
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

func TestLimiterRefillsAndDoesNotTrustForwardingHeaders(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	limiter := NewLimiter(1, 1)
	limiter.now = func() time.Time { return now }
	handler := RequestID(limiter.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})))

	makeRequest := func(forwarded string) int {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", nil)
		req.RemoteAddr = "192.0.2.10:1234"
		req.Header.Set("X-Forwarded-For", forwarded)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, req)
		return response.Code
	}

	if status := makeRequest("198.51.100.1"); status != http.StatusNoContent {
		t.Fatalf("first status = %d", status)
	}
	if status := makeRequest("198.51.100.2"); status != http.StatusTooManyRequests {
		t.Fatalf("forwarded header bypassed limiter, status = %d", status)
	}
	now = now.Add(time.Second)
	if status := makeRequest("198.51.100.3"); status != http.StatusNoContent {
		t.Fatalf("refilled status = %d", status)
	}
}

func TestLimiterCapsVisitorMemory(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	limiter := NewLimiter(1, 1)
	limiter.now = func() time.Time { return now }
	for index := 0; index < maxVisitors+500; index++ {
		limiter.allow("visitor-" + strconv.Itoa(index))
	}
	if count := len(limiter.visitors); count > maxVisitors {
		t.Fatalf("visitor count = %d, max = %d", count, maxVisitors)
	}
}

func TestClientIPRejectsMalformedRemoteAddress(t *testing.T) {
	if got := clientIP("203.0.113.10"); got != "unknown" {
		t.Fatalf("clientIP() = %q", got)
	}
	if got := clientIP("[2001:db8::1]:443"); got != "2001:db8::1" {
		t.Fatalf("clientIP() = %q", got)
	}
}

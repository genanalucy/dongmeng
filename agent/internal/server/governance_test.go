package server

import (
	"context"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"translator-agent/internal/ast"
	"translator-agent/internal/cloudauth"
	"translator-agent/internal/sessionauth"
)

// governanceDeadline is the product-level bound: a Cloud termination decision
// must reach and end the live connection within five seconds.
const governanceDeadline = 5 * time.Second

var testGovernanceTimings = GovernanceTimings{
	Interval: 15 * time.Millisecond, Timeout: 250 * time.Millisecond, Tolerance: 400 * time.Millisecond,
}

// scriptedAuthorizer returns a scripted Cloud answer; tests flip it while a
// connection is live.
type scriptedAuthorizer struct {
	mu       sync.Mutex
	decision cloudauth.Decision
	err      error
	calls    int
}

func (a *scriptedAuthorizer) Authorize(context.Context, string) (cloudauth.Decision, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.calls++
	return a.decision, a.err
}

func (a *scriptedAuthorizer) set(decision cloudauth.Decision, err error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.decision = decision
	a.err = err
}

func (a *scriptedAuthorizer) callCount() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.calls
}

func testGovernedHTTPServer(t *testing.T, client ast.Client, authorizer CloudAuthorizer, timings GovernanceTimings) *httptest.Server {
	t.Helper()
	verifier, err := sessionauth.NewVerifier(sessionauth.Config{
		HMACKey: testSessionKey, Issuer: testIssuer, Audience: testAudience,
		ClockSkew: 30 * time.Second, MaxLifetime: 5 * time.Minute,
	})
	if err != nil {
		t.Fatalf("NewVerifier() error = %v", err)
	}
	return httptest.NewServer(New(Options{
		ASTClient: client, SessionVerifier: verifier, CloudAuthorizer: authorizer, Governance: timings,
	}).Handler())
}

func dialGoverned(t *testing.T, ts *httptest.Server) *websocket.Conn {
	t.Helper()
	token := signSessionToken(t, testSessionKey, testUserID, testSessionID, testInstallID)
	return dialWithProtocols(t, ts.URL, "http://localhost:5173", []string{SessionSubprotocol, SessionTokenProtocolPrefix + token})
}

// expectConnectionClosed asserts the server closes the socket after the
// terminal event instead of leaving the connection half-open.
func expectConnectionClosed(t *testing.T, conn *websocket.Conn) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
		_, _, err := conn.Read(ctx)
		cancel()
		if err != nil {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("connection remained open after the terminal event")
		}
	}
}

func expectSessionClosed(t *testing.T, session *fakeSession) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		session.mu.Lock()
		closed := session.closed
		session.mu.Unlock()
		if closed {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("provider session was not closed after termination")
		}
		time.Sleep(2 * time.Millisecond)
	}
}

func TestInitialCloudDenialNeverStartsProvider(t *testing.T) {
	tests := []struct {
		name       string
		decision   cloudauth.Decision
		err        error
		code       string
		message    string
		wantLogEvt string
	}{
		{
			name:       "replaced by device",
			decision:   cloudauth.Decision{Reason: cloudauth.ReasonReplacedByDevice},
			code:       SessionReplacedCode,
			message:    sessionReplacedMessage,
			wantLogEvt: "session_governance_denied",
		},
		{name: "ended", decision: cloudauth.Decision{Reason: cloudauth.ReasonEnded}, code: SessionEndedCode, message: sessionEndedMessage},
		{name: "revoked", decision: cloudauth.Decision{Reason: cloudauth.ReasonRevoked}, code: SessionEndedCode, message: sessionEndedMessage},
		{name: "expired", decision: cloudauth.Decision{Reason: cloudauth.ReasonExpired}, code: SessionEndedCode, message: sessionEndedMessage},
		{name: "user disabled", decision: cloudauth.Decision{Reason: cloudauth.ReasonUserDisabled}, code: SessionEndedCode, message: sessionEndedMessage},
		{name: "entitlement revoked", decision: cloudauth.Decision{Reason: cloudauth.ReasonEntitlementRevoked}, code: SessionEndedCode, message: sessionEndedMessage},
		// An unknown reason must collapse to the generic ended code.
		{name: "unknown reason collapses", decision: cloudauth.Decision{Reason: "freeform"}, code: SessionEndedCode, message: sessionEndedMessage},
		{name: "no reason", decision: cloudauth.Decision{}, code: SessionEndedCode, message: sessionEndedMessage},
		// Initial authorization with an unreachable Cloud fails closed before
		// the provider starts.
		{name: "cloud unavailable", err: cloudauth.ErrUnavailable, code: AuthUnavailableCode, message: authUnavailableMessage},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			fake := &fakeClient{}
			authorizer := &scriptedAuthorizer{decision: tt.decision, err: tt.err}
			ts := testGovernedHTTPServer(t, fake, authorizer, testGovernanceTimings)
			defer ts.Close()

			conn := dialGoverned(t, ts)
			defer conn.CloseNow()
			startAuthorized(t, conn, nil)

			event := readEvent(t, conn)
			if event.Type != "error" || event.Code != tt.code || event.Message != tt.message {
				t.Fatalf("event = %#v, want error %q", event, tt.code)
			}
			if fake.starts() != 0 {
				t.Fatalf("provider started %d times after an initial denial", fake.starts())
			}
			if authorizer.callCount() != 1 {
				t.Fatalf("initial denial made %d authorize calls, want 1", authorizer.callCount())
			}
			expectConnectionClosed(t, conn)
		})
	}
}

func TestPeriodicRevocationTerminatesLiveConnectionInsideDeadline(t *testing.T) {
	for _, tt := range []struct {
		name     string
		decision cloudauth.Decision
		code     string
		message  string
	}{
		{name: "replaced by device", decision: cloudauth.Decision{Reason: cloudauth.ReasonReplacedByDevice}, code: SessionReplacedCode, message: sessionReplacedMessage},
		{name: "ended", decision: cloudauth.Decision{Reason: cloudauth.ReasonEnded}, code: SessionEndedCode, message: sessionEndedMessage},
		{name: "user disabled", decision: cloudauth.Decision{Reason: cloudauth.ReasonUserDisabled}, code: SessionEndedCode, message: sessionEndedMessage},
	} {
		t.Run(tt.name, func(t *testing.T) {
			fake := &fakeClient{}
			authorizer := &scriptedAuthorizer{decision: cloudauth.Decision{Active: true}}
			ts := testGovernedHTTPServer(t, fake, authorizer, testGovernanceTimings)
			defer ts.Close()

			conn := dialGoverned(t, ts)
			defer conn.CloseNow()
			startAuthorized(t, conn, nil)
			if event := readEvent(t, conn); event.Type != "ready" {
				t.Fatalf("event = %#v, want ready", event)
			}
			if fake.starts() != 1 {
				t.Fatalf("provider started %d times, want 1", fake.starts())
			}

			revokedAt := time.Now()
			authorizer.set(tt.decision, nil)
			event := readEvent(t, conn)
			if elapsed := time.Since(revokedAt); elapsed >= governanceDeadline {
				t.Fatalf("termination took %s, want < %s", elapsed, governanceDeadline)
			}
			if event.Type != "error" || event.Code != tt.code || event.Message != tt.message {
				t.Fatalf("event = %#v, want error %q", event, tt.code)
			}
			// The provider session and the socket both close safely.
			expectSessionClosed(t, fake.session)
			expectConnectionClosed(t, conn)
		})
	}
}

func TestUnreachableCloudBlipInsideToleranceDoesNotTerminate(t *testing.T) {
	fake := &fakeClient{}
	authorizer := &scriptedAuthorizer{decision: cloudauth.Decision{Active: true}}
	ts := testGovernedHTTPServer(t, fake, authorizer, testGovernanceTimings)
	defer ts.Close()

	conn := dialGoverned(t, ts)
	defer conn.CloseNow()
	startAuthorized(t, conn, nil)
	if event := readEvent(t, conn); event.Type != "ready" {
		t.Fatalf("event = %#v, want ready", event)
	}

	// A brief unreachable blip inside the tolerance window must not terminate
	// a running session, and recovery must reset the tolerated window.
	authorizer.set(cloudauth.Decision{}, cloudauth.ErrUnavailable)
	time.Sleep(60 * time.Millisecond)
	authorizer.set(cloudauth.Decision{Active: true}, nil)
	// A quiet period well past several intervals proves no delayed
	// fail-closed termination fired after recovery.
	time.Sleep(200 * time.Millisecond)

	// Prove survival non-destructively: a later definitive decision still
	// reaches the live connection with its safe code instead of EOF.
	authorizer.set(cloudauth.Decision{Reason: cloudauth.ReasonReplacedByDevice}, nil)
	event := readEvent(t, conn)
	if event.Type != "error" || event.Code != SessionReplacedCode || event.Message != sessionReplacedMessage {
		t.Fatalf("event = %#v, want error %q proving the connection survived the blip", event, SessionReplacedCode)
	}
	expectSessionClosed(t, fake.session)
}

func TestSustainedUnreachableCloudFailsClosedInsideDeadline(t *testing.T) {
	fake := &fakeClient{}
	authorizer := &scriptedAuthorizer{decision: cloudauth.Decision{Active: true}}
	ts := testGovernedHTTPServer(t, fake, authorizer, testGovernanceTimings)
	defer ts.Close()

	conn := dialGoverned(t, ts)
	defer conn.CloseNow()
	startAuthorized(t, conn, nil)
	if event := readEvent(t, conn); event.Type != "ready" {
		t.Fatalf("event = %#v, want ready", event)
	}

	// A sustained outage must fail closed with the unavailable code, inside
	// the governance deadline and not before the tolerance window elapses.
	unreachableAt := time.Now()
	authorizer.set(cloudauth.Decision{}, cloudauth.ErrUnavailable)
	event := readEvent(t, conn)
	elapsed := time.Since(unreachableAt)
	if event.Type != "error" || event.Code != AuthUnavailableCode || event.Message != authUnavailableMessage {
		t.Fatalf("event = %#v, want error %q", event, AuthUnavailableCode)
	}
	if elapsed >= governanceDeadline {
		t.Fatalf("fail-closed termination took %s, want < %s", elapsed, governanceDeadline)
	}
	if elapsed < testGovernanceTimings.Tolerance-20*time.Millisecond {
		t.Fatalf("fail-closed termination after %s cut the tolerance window %s short", elapsed, testGovernanceTimings.Tolerance)
	}
	expectSessionClosed(t, fake.session)
	expectConnectionClosed(t, conn)
}

func TestDuplicateIdentityConnectionSupersedesTheOlderConnection(t *testing.T) {
	fake := &fakeClient{}
	authorizer := &scriptedAuthorizer{decision: cloudauth.Decision{Active: true}}
	ts := testGovernedHTTPServer(t, fake, authorizer, testGovernanceTimings)
	defer ts.Close()

	first := dialGoverned(t, ts)
	defer first.CloseNow()
	startAuthorized(t, first, nil)
	if event := readEvent(t, first); event.Type != "ready" {
		t.Fatalf("first event = %#v, want ready", event)
	}

	second := dialGoverned(t, ts)
	defer second.CloseNow()
	startAuthorized(t, second, nil)
	if event := readEvent(t, second); event.Type != "ready" {
		t.Fatalf("second event = %#v, want ready", event)
	}

	// The older connection for the same verified identity is cancelled and
	// its socket closed inside the deadline.
	expectConnectionClosed(t, first)
	// The newer connection keeps running.
	writeCtx, writeCancel := context.WithTimeout(context.Background(), time.Second)
	if err := second.Write(writeCtx, websocket.MessageBinary, make([]byte, PCMFrameBytes-1)); err != nil {
		t.Fatal(err)
	}
	writeCancel()
	if event := readEvent(t, second); event.Code != "INVALID_PCM_FRAME" {
		t.Fatalf("event = %#v, want INVALID_PCM_FRAME proving the replacement still runs", event)
	}
}

func TestGovernanceKeepsRunningForAnActiveSession(t *testing.T) {
	fake := &fakeClient{}
	authorizer := &scriptedAuthorizer{decision: cloudauth.Decision{Active: true}}
	ts := testGovernedHTTPServer(t, fake, authorizer, testGovernanceTimings)
	defer ts.Close()

	conn := dialGoverned(t, ts)
	defer conn.CloseNow()
	startAuthorized(t, conn, nil)
	if event := readEvent(t, conn); event.Type != "ready" {
		t.Fatalf("event = %#v, want ready", event)
	}

	// The periodic loop keeps re-authorizing at the configured interval; a
	// quiet connection is never terminated while the Cloud says active.
	time.Sleep(80 * time.Millisecond)
	if calls := authorizer.callCount(); calls < 3 {
		t.Fatalf("only %d authorize calls after start, periodic loop not running", calls)
	}
	writeCtx, writeCancel := context.WithTimeout(context.Background(), time.Second)
	if err := conn.Write(writeCtx, websocket.MessageBinary, make([]byte, PCMFrameBytes-1)); err != nil {
		t.Fatal(err)
	}
	writeCancel()
	if event := readEvent(t, conn); event.Code != "INVALID_PCM_FRAME" {
		t.Fatalf("event = %#v, want INVALID_PCM_FRAME proving the session still runs", event)
	}
}

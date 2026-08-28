//go:build integration

package integration_test

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
	httpapi "github.com/dngmeng/cloud-api/internal/http"
	"github.com/dngmeng/cloud-api/internal/migrate"
	"github.com/dngmeng/cloud-api/internal/store"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	e2eIssuer   = "cloud-agent-e2e"
	e2eAudience = "translator-agent"
	e2eOrigin   = "http://localhost:5173"
)

var e2eSessionKey = []byte("e2e-session-signing-key-is-at-least-32-bytes")
var e2eAccessKey = []byte("e2e-access-signing-key-is-at-least-32-bytes-")

func TestAgentSessionTestHarnessStartsOnEphemeralLoopback(t *testing.T) {
	harness := startAgentSessionHarness(t)
	response, err := harness.client.Get(harness.baseURL + "/api/health")
	if err != nil {
		t.Fatal("request test-only agent health")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("test-only agent health status = %d, want 200", response.StatusCode)
	}
	if got := harness.providerStarts(t); got != 0 {
		t.Fatalf("fresh test-only provider starts = %d, want 0", got)
	}
}

// TestCloudAgentSessionAuthorization drives the real Cloud login and session
// routes, then the real Agent HTTP/WebSocket server in a separate module. It
// intentionally runs only with an explicit isolated PostgreSQL DSN.
func TestCloudAgentSessionAuthorization(t *testing.T) {
	dsn := isolatedPostgresTestDSN(t)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := migrate.Run(ctx, migrate.Config{DatabaseURL: dsn, Directory: repositoryMigrationDirectory(t), Schema: "public"}); err != nil {
		t.Fatal("apply E2E test migrations")
	}
	db, err := store.Open(ctx, dsn)
	if err != nil {
		t.Fatal("open isolated E2E database")
	}
	t.Cleanup(db.Close)
	raw, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal("open isolated E2E fixture pool")
	}
	t.Cleanup(raw.Close)

	now := time.Now().UTC().Truncate(time.Second)
	issuer := auth.TokenIssuer{
		Issuer: e2eIssuer, Audience: "cloud-api-clients", SessionAudience: e2eAudience,
		AccessSecret: e2eAccessKey, SessionSecret: e2eSessionKey,
	}
	cloud := newE2ECloud(t, db, raw, issuer, now)
	grant := cloud.createGrant(t)

	t.Run("accepts Cloud-issued grant exactly once", func(t *testing.T) {
		harness := startAgentSessionHarness(t)
		conn, response := dialE2E(t, harness.baseURL, []string{"translation.v1", "translation.jwt." + grant.Token}, nil)
		defer conn.CloseNow()
		if got := conn.Subprotocol(); got != "translation.v1" || response.Header.Get("Sec-WebSocket-Protocol") != "translation.v1" {
			t.Fatal("Agent did not negotiate only translation.v1")
		}
		sendE2EStart(t, conn, grant, nil)
		assertE2EAuthorizationEvent(t, conn, "ready")
		assertNoSecondReady(t, conn)
		if got := harness.providerStarts(t); got != 1 {
			t.Fatalf("provider starts = %d, want 1", got)
		}
	})

	invalidCases := []struct {
		name       string
		mutate     func(t *testing.T, token string) string
		startPatch map[string]string
	}{
		{name: "scope api", mutate: func(t *testing.T, _ string) string {
			return signCloudShapedToken(t, issuer, grant, now, claimPatch{"scope": "api"}, e2eSessionKey)
		}},
		{name: "missing scope", mutate: func(t *testing.T, _ string) string {
			return signCloudShapedToken(t, issuer, grant, now, claimPatch{"scope": nil}, e2eSessionKey)
		}},
		{name: "wrong audience", mutate: func(t *testing.T, _ string) string {
			return signCloudShapedToken(t, issuer, grant, now, claimPatch{"aud": "other-agent"}, e2eSessionKey)
		}},
		{name: "wrong issuer", mutate: func(t *testing.T, _ string) string {
			return signCloudShapedToken(t, issuer, grant, now, claimPatch{"iss": "other-cloud"}, e2eSessionKey)
		}},
		{name: "wrong signature", mutate: func(t *testing.T, _ string) string {
			return signCloudShapedToken(t, issuer, grant, now, nil, []byte("different-e2e-signing-key-at-least-32-bytes"))
		}},
		{name: "expired", mutate: func(t *testing.T, _ string) string {
			return signCloudShapedToken(t, issuer, grant, now, claimPatch{
				"iat": now.Add(-2 * time.Minute).Unix(),
				"nbf": now.Add(-2 * time.Minute).Unix(),
				"exp": now.Add(-time.Minute).Unix(),
			}, e2eSessionKey)
		}},
		{name: "wrong user binding", startPatch: map[string]string{"userId": uuid.NewString()}},
		{name: "wrong session binding", startPatch: map[string]string{"sessionId": uuid.NewString()}},
		{name: "wrong install binding", startPatch: map[string]string{"installId": "other-install"}},
	}
	for _, testCase := range invalidCases {
		t.Run(testCase.name, func(t *testing.T) {
			harness := startAgentSessionHarness(t)
			token := grant.Token
			if testCase.mutate != nil {
				token = testCase.mutate(t, token)
			}
			conn, _ := dialE2E(t, harness.baseURL, []string{"translation.v1", "translation.jwt." + token}, nil)
			defer conn.CloseNow()
			sendE2EStart(t, conn, grant, testCase.startPatch)
			assertE2EAuthorizationEvent(t, conn, "error")
			if got := harness.providerStarts(t); got != 0 {
				t.Fatalf("%s provider starts = %d, want 0", testCase.name, got)
			}
		})
	}

	for _, protocols := range [][]string{
		{"translation.v1", "bearer." + grant.Token},
		{"translation.jwt." + grant.Token},
	} {
		t.Run("rejects prohibited subprotocol", func(t *testing.T) {
			harness := startAgentSessionHarness(t)
			assertE2EUpgradeRejected(t, harness, protocols, nil, "")
		})
	}
	credentialTransportCases := []struct {
		name     string
		headers  http.Header
		rawQuery string
	}{
		{name: "Authorization", headers: http.Header{"Authorization": {"Bearer " + grant.Token}}},
		{name: "Cookie", headers: http.Header{"Cookie": {"translation_token=" + grant.Token}}},
		{name: "URL", rawQuery: "?session_token=" + url.QueryEscape(grant.Token)},
	}
	for _, testCase := range credentialTransportCases {
		t.Run("rejects credential in "+testCase.name, func(t *testing.T) {
			harness := startAgentSessionHarness(t)
			assertE2EUpgradeRejected(t, harness, []string{"translation.v1"}, testCase.headers, testCase.rawQuery)
		})
	}
}

type e2eHarness struct {
	baseURL string
	client  *http.Client
}

func startAgentSessionHarness(t *testing.T) e2eHarness {
	t.Helper()
	root := repositoryRoot(t)
	binary := filepath.Join(t.TempDir(), "session-test-harness")
	buildContext, cancelBuild := context.WithTimeout(context.Background(), 30*time.Second)
	build := exec.CommandContext(buildContext, "go", "build", "-tags", "integrationtest", "-o", binary, "./cmd/session-test-harness")
	build.Dir = filepath.Join(root, "agent")
	if err := build.Run(); err != nil {
		cancelBuild()
		t.Fatal("build Agent test-only harness")
	}
	cancelBuild()

	ctx, cancel := context.WithCancel(context.Background())
	command := exec.CommandContext(ctx, binary)
	command.Env = append(os.Environ(),
		"E2E_AGENT_SESSION_KEY="+string(e2eSessionKey),
		"E2E_AGENT_SESSION_ISSUER="+e2eIssuer,
		"E2E_AGENT_SESSION_AUDIENCE="+e2eAudience,
		"E2E_AGENT_ORIGIN="+e2eOrigin,
	)
	stdout, err := command.StdoutPipe()
	if err != nil {
		cancel()
		t.Fatal("create Agent harness stdout pipe")
	}
	if err := command.Start(); err != nil {
		cancel()
		t.Fatal("start Agent test-only harness")
	}
	done := make(chan error, 1)
	go func() { done <- command.Wait() }()

	addressResult := make(chan struct {
		address string
		err     error
	}, 1)
	go func() {
		address, readErr := readHarnessAddress(bufio.NewReader(stdout))
		addressResult <- struct {
			address string
			err     error
		}{address: address, err: readErr}
	}()
	select {
	case result := <-addressResult:
		if result.err != nil {
			stopAgentSessionHarness(t, command, cancel, done, "")
			t.Fatal("Agent test-only harness did not report a loopback address")
		}
		baseURL := "http://" + result.address
		t.Cleanup(func() { stopAgentSessionHarness(t, command, cancel, done, result.address) })
		return e2eHarness{baseURL: baseURL, client: &http.Client{Timeout: time.Second}}
	case <-time.After(3 * time.Second):
		stopAgentSessionHarness(t, command, cancel, done, "")
		t.Fatal("Agent test-only harness did not report a loopback address")
	}
	return e2eHarness{}
}

func stopAgentSessionHarness(t *testing.T, command *exec.Cmd, cancel context.CancelFunc, done <-chan error, address string) {
	t.Helper()
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		if command.Process != nil {
			if err := command.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
				t.Error("kill Agent test-only harness")
			}
		}
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("Agent test-only harness did not exit after kill")
			return
		}
	}
	if address == "" {
		return
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		t.Error("Agent test-only harness listener was not released")
		return
	}
	if err := listener.Close(); err != nil {
		t.Error("close released Agent test-only harness listener")
	}
}

func readHarnessAddress(reader *bufio.Reader) (string, error) {
	line, err := reader.ReadString('\n')
	if err != nil {
		return "", err
	}
	address := strings.TrimSpace(line)
	host, port, found := strings.Cut(address, ":")
	if !found || host != "127.0.0.1" || port == "" {
		return "", errors.New("invalid loopback harness address")
	}
	return address, nil
}

func (h e2eHarness) providerStarts(t *testing.T) int {
	t.Helper()
	response, err := h.client.Get(h.baseURL + "/test/provider-starts")
	if err != nil {
		t.Fatal("request fake Provider start count")
	}
	defer response.Body.Close()
	var body struct {
		Starts int `json:"starts"`
	}
	if response.StatusCode != http.StatusOK || json.NewDecoder(response.Body).Decode(&body) != nil {
		t.Fatal("decode fake Provider start count")
	}
	return body.Starts
}

type e2eCloud struct {
	server   *httptest.Server
	client   *http.Client
	fixtures *pgxpool.Pool
	email    string
	pass     string
}

type e2eGrant struct {
	SessionID string `json:"session_id"`
	UserID    string `json:"user_id"`
	InstallID string `json:"install_id"`
	Token     string `json:"token"`
}

func newE2ECloud(t *testing.T, db *store.Postgres, fixtures *pgxpool.Pool, issuer auth.TokenIssuer, now time.Time) e2eCloud {
	t.Helper()
	router := httpapi.NewRouter(httpapi.RouterOptions{
		Config:   config.Config{Environment: "test", AllowedOrigins: []string{e2eOrigin}, DatabaseTimeout: time.Second, RateLimitRPS: 100, RateLimitBurst: 100},
		Database: readyDatabase{}, Store: db, Tokens: issuer, Now: func() time.Time { return now },
	})
	server := httptest.NewServer(router)
	t.Cleanup(server.Close)
	return e2eCloud{server: server, client: server.Client(), fixtures: fixtures, email: integrationEmail(), pass: "integration-password"}
}

func (c e2eCloud) createGrant(t *testing.T) e2eGrant {
	t.Helper()
	status, register := c.json(t, http.MethodPost, "/api/v1/auth/register", nil, map[string]string{"email": c.email, "password": c.pass})
	if status != http.StatusCreated || register == nil {
		t.Fatal("create Cloud E2E user")
	}
	userID := registeredE2EUserID(t, register)
	t.Cleanup(func() {
		cleanupContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := c.fixtures.Exec(cleanupContext, `DELETE FROM users WHERE id=$1`, userID); err != nil {
			t.Error("cleanup Cloud E2E fixture user")
		}
	})

	status, login := c.json(t, http.MethodPost, "/api/v1/auth/login", nil, map[string]string{"email": c.email, "password": c.pass})
	if status != http.StatusOK {
		t.Fatal("Cloud E2E login")
	}
	access, _ := login["access_token"].(string)
	if access == "" {
		t.Fatal("Cloud E2E login response omitted access token")
	}
	status, session := c.json(t, http.MethodPost, "/api/v1/translation-sessions", http.Header{"Authorization": {"Bearer " + access}}, map[string]string{"install_id": "e2e-install-" + uuid.NewString()})
	if status != http.StatusCreated {
		t.Fatal("create Cloud translation session")
	}
	var grant e2eGrant
	encoded, err := json.Marshal(session)
	if err != nil || json.Unmarshal(encoded, &grant) != nil || grant.SessionID == "" || grant.UserID == "" || grant.InstallID == "" || grant.Token == "" {
		t.Fatal("Cloud translation session response omitted required grant fields")
	}
	if grant.UserID != userID.String() {
		t.Fatal("Cloud translation session response did not retain registered user")
	}
	return grant
}

func registeredE2EUserID(t *testing.T, response map[string]any) uuid.UUID {
	t.Helper()
	user, ok := response["user"].(map[string]any)
	if !ok {
		t.Fatal("Cloud E2E register response omitted user")
	}
	value, ok := user["id"].(string)
	userID, err := uuid.Parse(value)
	if !ok || err != nil || userID == uuid.Nil {
		t.Fatal("Cloud E2E register response contained an invalid user identifier")
	}
	return userID
}

func (c e2eCloud) json(t *testing.T, method, path string, headers http.Header, body any) (int, map[string]any) {
	t.Helper()
	encoded, err := json.Marshal(body)
	if err != nil {
		t.Fatal("encode Cloud E2E request")
	}
	request, err := http.NewRequest(method, c.server.URL+path, bytes.NewReader(encoded))
	if err != nil {
		t.Fatal("create Cloud E2E request")
	}
	request.Header.Set("Content-Type", "application/json")
	for name, values := range headers {
		request.Header[name] = append([]string(nil), values...)
	}
	response, err := c.client.Do(request)
	if err != nil {
		t.Fatal("send Cloud E2E request")
	}
	defer response.Body.Close()
	var decoded map[string]any
	if err := json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&decoded); err != nil {
		t.Fatal("decode Cloud E2E response")
	}
	return response.StatusCode, decoded
}

func dialE2E(t *testing.T, baseURL string, protocols []string, extra http.Header) (*websocket.Conn, *http.Response) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	headers := http.Header{"Origin": {e2eOrigin}}
	for name, values := range extra {
		headers[name] = append([]string(nil), values...)
	}
	conn, response, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(baseURL, "http")+"/ws/translate", &websocket.DialOptions{HTTPHeader: headers, Subprotocols: protocols})
	if err != nil {
		status := 0
		if response != nil {
			status = response.StatusCode
			response.Body.Close()
		}
		t.Fatalf("Agent WebSocket dial failed (status=%d)", status)
	}
	return conn, response
}

func sendE2EStart(t *testing.T, conn *websocket.Conn, grant e2eGrant, patch map[string]string) {
	t.Helper()
	payload := map[string]any{
		"type": "start", "sessionId": grant.SessionID, "userId": grant.UserID, "installId": grant.InstallID,
		"mode": "s2s", "sourceLanguage": "zh", "targetLanguage": "en", "targetAudioFormat": "pcm", "targetAudioRate": 16000,
	}
	for key, value := range patch {
		payload[key] = value
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := wsjson.Write(ctx, conn, payload); err != nil {
		t.Fatal("send Android-shaped start")
	}
}

func assertE2EAuthorizationEvent(t *testing.T, conn *websocket.Conn, wantType string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	var event struct {
		Type string `json:"type"`
		Code string `json:"code"`
	}
	if err := wsjson.Read(ctx, conn, &event); err != nil {
		t.Fatal("read Agent authorization event")
	}
	if event.Type != wantType || (wantType == "error" && event.Code != "TRANSLATION_AUTH_INVALID") {
		t.Fatalf("Agent authorization event type=%q code=%q", event.Type, event.Code)
	}
}

func assertNoSecondReady(t *testing.T, conn *websocket.Conn) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	var event struct {
		Type string `json:"type"`
	}
	err := wsjson.Read(ctx, conn, &event)
	if err == nil && event.Type == "ready" {
		t.Fatal("Agent emitted a second ready event")
	}
	if err == nil {
		t.Fatalf("Agent emitted unexpected event type=%q after ready", event.Type)
	}
}

func assertE2EUpgradeRejected(t *testing.T, harness e2eHarness, protocols []string, headers http.Header, rawQuery string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	requestHeaders := http.Header{"Origin": {e2eOrigin}}
	for key, values := range headers {
		requestHeaders[key] = append([]string(nil), values...)
	}
	_, response, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(harness.baseURL, "http")+"/ws/translate"+rawQuery, &websocket.DialOptions{HTTPHeader: requestHeaders, Subprotocols: protocols})
	if err == nil || response == nil || response.StatusCode != http.StatusUnauthorized || response.Header.Get("Sec-WebSocket-Protocol") != "" {
		if response != nil {
			response.Body.Close()
		}
		t.Fatal("Agent accepted prohibited credential transport")
	}
	response.Body.Close()
	if got := harness.providerStarts(t); got != 0 {
		t.Fatalf("prohibited credential transport provider starts = %d, want 0", got)
	}
}

type claimPatch map[string]any

func signCloudShapedToken(t *testing.T, issuer auth.TokenIssuer, grant e2eGrant, now time.Time, patch claimPatch, key []byte) string {
	t.Helper()
	claims := jwt.MapClaims{
		"iss": issuer.Issuer, "aud": issuer.SessionAudience, "sub": grant.UserID,
		"user_id": grant.UserID, "session_id": grant.SessionID, "install_id": grant.InstallID,
		"entitlement_id": uuid.NewString(), "scope": "translation", "jti": uuid.NewString(),
		"iat": now.Unix(), "nbf": now.Unix(), "exp": now.Add(time.Minute).Unix(),
	}
	for name, value := range patch {
		if value == nil {
			delete(claims, name)
		} else {
			claims[name] = value
		}
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	token.Header["typ"] = "translation_session"
	value, err := token.SignedString(key)
	if err != nil {
		t.Fatal("sign Cloud-shaped invalid token")
	}
	return value
}

func repositoryRoot(t *testing.T) string {
	t.Helper()
	root, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal("resolve repository root")
	}
	return root
}

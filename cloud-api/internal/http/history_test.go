package httpapi

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/config"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/dngmeng/cloud-api/internal/historycrypto"
	"github.com/google/uuid"
)

type historyContractStore struct {
	*adminContractStore
	applied  []domain.HistoryOperationParams
	cursor   int64
	changes  []domain.HistoryChange
	sessions map[uuid.UUID]domain.HistorySession
	turns    map[uuid.UUID]domain.EncryptedTurn
	err      error
}

func (s *historyContractStore) ApplyHistoryOperation(_ context.Context, value domain.HistoryOperationParams) (int64, error) {
	if s.err != nil {
		return 0, s.err
	}
	for index, prior := range s.applied {
		if prior.OperationID == value.OperationID {
			return int64(index + 1), nil
		}
	}
	s.applied = append(s.applied, value)
	s.cursor++
	if value.Kind == "turn.upsert" {
		s.sessions[value.SessionID] = domain.HistorySession{ID: value.SessionID, UserID: value.UserID, CreatedAt: value.Now}
		s.turns[*value.TurnID] = domain.EncryptedTurn{ID: *value.TurnID, UserID: value.UserID, SessionID: value.SessionID, KeyVersion: value.KeyVersion, Nonce: value.Nonce, Ciphertext: value.Ciphertext, CreatedAt: value.Now}
		s.changes = append(s.changes, domain.HistoryChange{Cursor: s.cursor, SessionID: value.SessionID, TurnID: value.TurnID})
	}
	return s.cursor, nil
}
func (s *historyContractStore) ListHistoryChanges(_ context.Context, user uuid.UUID, after int64, _ int) ([]domain.HistoryChange, error) {
	out := []domain.HistoryChange{}
	for _, value := range s.changes {
		if value.Cursor > after && s.sessions[value.SessionID].UserID == user {
			out = append(out, value)
		}
	}
	return out, nil
}
func (s *historyContractStore) HistorySessionIncludingDeleted(_ context.Context, user, id uuid.UUID) (domain.HistorySession, error) {
	value, ok := s.sessions[id]
	if !ok || value.UserID != user {
		return domain.HistorySession{}, domain.ErrNotFound
	}
	return value, nil
}
func (s *historyContractStore) HistoryTurnIncludingDeleted(_ context.Context, user, id uuid.UUID) (domain.EncryptedTurn, error) {
	value, ok := s.turns[id]
	if !ok || value.UserID != user {
		return domain.EncryptedTurn{}, domain.ErrNotFound
	}
	return value, nil
}
func (s *historyContractStore) AdminHistory(context.Context, uuid.UUID, uuid.UUID) ([]domain.HistorySession, []domain.EncryptedTurn, error) {
	return nil, nil, errors.New("not implemented")
}

func historyRouter(t *testing.T, store *historyContractStore) (http.Handler, auth.TokenIssuer, time.Time) {
	t.Helper()
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		t.Fatal(err)
	}
	cipher, err := historycrypto.NewCipher(raw, 1)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)
	issuer := auth.TokenIssuer{Issuer: "history-test", Audience: "history-client", SessionAudience: "history-agent", AccessSecret: bytes.Repeat([]byte("a"), 32), SessionSecret: bytes.Repeat([]byte("s"), 32)}
	return NewRouter(RouterOptions{Config: config.Config{Environment: "test", DatabaseTimeout: time.Second, RateLimitRPS: 1000, RateLimitBurst: 1000, HistoryEnabled: true}, Store: store, Tokens: issuer, HistoryCipher: cipher, Now: func() time.Time { return now }}), issuer, now
}
func historyToken(t *testing.T, issuer auth.TokenIssuer, user uuid.UUID, now time.Time) string {
	t.Helper()
	token, err := issuer.AccessToken(user, string(domain.RoleUser), time.Hour, now)
	if err != nil {
		t.Fatal(err)
	}
	return token
}
func TestHistorySyncOwnerIsolationCursorAndIdempotency(t *testing.T) {
	store := &historyContractStore{adminContractStore: &adminContractStore{enabled: true}, sessions: map[uuid.UUID]domain.HistorySession{}, turns: map[uuid.UUID]domain.EncryptedTurn{}}
	router, issuer, now := historyRouter(t, store)
	owner, other, session, turn, operation := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	body := `{"operations":[{"operation_id":"` + operation.String() + `","kind":"turn.upsert","session_id":"` + session.String() + `","turn_id":"` + turn.String() + `","payload":"` + base64.StdEncoding.EncodeToString([]byte("completed-only")) + `"}]}`
	push := func(token string) *httptest.ResponseRecorder {
		request := httptest.NewRequest(http.MethodPost, "/api/v1/history/sync/push", bytes.NewBufferString(body))
		request.Header.Set("Authorization", "Bearer "+token)
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		router.ServeHTTP(response, request)
		return response
	}
	if response := push(historyToken(t, issuer, owner, now)); response.Code != http.StatusOK {
		t.Fatalf("owner push = %d %s", response.Code, response.Body.String())
	}
	if len(store.applied) != 1 || store.applied[0].UserID != owner || bytes.Equal(store.applied[0].Ciphertext, []byte("completed-only")) {
		t.Fatal("push did not encrypt and owner-scope payload")
	}
	if response := push(historyToken(t, issuer, owner, now)); response.Code != http.StatusOK || len(store.applied) != 1 {
		t.Fatalf("idempotent push = %d, operations=%d", response.Code, len(store.applied))
	}
	pull := func(token string) *httptest.ResponseRecorder {
		request := httptest.NewRequest(http.MethodGet, "/api/v1/history/sync/pull?cursor=0", nil)
		request.Header.Set("Authorization", "Bearer "+token)
		response := httptest.NewRecorder()
		router.ServeHTTP(response, request)
		return response
	}
	if response := pull(historyToken(t, issuer, other, now)); response.Code != http.StatusOK || string(response.Body.Bytes()) != "{\"changes\":[],\"has_more\":false,\"next_cursor\":0}\n" {
		t.Fatalf("foreign pull = %d %s", response.Code, response.Body.String())
	}
	response := pull(historyToken(t, issuer, owner, now))
	if response.Code != http.StatusOK || !bytes.Contains(response.Body.Bytes(), []byte(base64.StdEncoding.EncodeToString([]byte("completed-only")))) {
		t.Fatalf("owner pull = %d %s", response.Code, response.Body.String())
	}
	bad := httptest.NewRequest(http.MethodGet, "/api/v1/history/sync/pull?cursor=-1", nil)
	bad.Header.Set("Authorization", "Bearer "+historyToken(t, issuer, owner, now))
	badResponse := httptest.NewRecorder()
	router.ServeHTTP(badResponse, bad)
	if badResponse.Code != http.StatusBadRequest {
		t.Fatalf("negative cursor = %d", badResponse.Code)
	}
}
func TestHistoryRoutesAreAbsentWhenDisabled(t *testing.T) {
	router, _, _ := newAdminContractRouter(t, &adminContractStore{enabled: true})
	response := request(router, http.MethodGet, "/api/v1/history/sync/pull", "")
	if response.Code != http.StatusNotFound {
		t.Fatalf("disabled history route = %d", response.Code)
	}
}
func TestHistoryLimitHasStableCloudError(t *testing.T) {
	store := &historyContractStore{adminContractStore: &adminContractStore{enabled: true}, sessions: map[uuid.UUID]domain.HistorySession{}, turns: map[uuid.UUID]domain.EncryptedTurn{}, err: domain.ErrHistoryLimitExceeded}
	router, issuer, now := historyRouter(t, store)
	body, _ := json.Marshal(map[string]any{"operations": []map[string]string{{"operation_id": uuid.NewString(), "kind": "session.delete", "session_id": uuid.NewString()}}})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/history/sync/push", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+historyToken(t, issuer, uuid.New(), now))
	response := httptest.NewRecorder()
	router.ServeHTTP(response, req)
	if response.Code != http.StatusConflict || !bytes.Contains(response.Body.Bytes(), []byte("history_limit_exceeded")) {
		t.Fatalf("limit response = %d %s", response.Code, response.Body.String())
	}
}

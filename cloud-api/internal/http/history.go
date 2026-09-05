package httpapi

import (
	"encoding/base64"
	"net/http"
	"strconv"
	"strings"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
)

const historySyncPageSize = 100

type historyOperationRequest struct {
	OperationID string `json:"operation_id"`
	Kind        string `json:"kind"`
	SessionID   string `json:"session_id"`
	TurnID      string `json:"turn_id,omitempty"`
	Payload     string `json:"payload,omitempty"`
}

func strictUUID(value string) (uuid.UUID, bool) {
	id, err := uuid.Parse(value)
	return id, err == nil && id != uuid.Nil && id.String() == value
}

func historyPayload(value string) ([]byte, bool) {
	decoded, err := base64.StdEncoding.DecodeString(value)
	return decoded, err == nil && len(decoded) > 0 && base64.StdEncoding.EncodeToString(decoded) == value
}

func (a api) historyPush(w http.ResponseWriter, r *http.Request) {
	if a.historyCipher == nil {
		writeError(w, r, http.StatusNotFound, "not_found")
		return
	}
	var request struct {
		Operations []historyOperationRequest `json:"operations"`
	}
	if decode(w, r, &request) != nil || len(request.Operations) < 1 || len(request.Operations) > historySyncPageSize {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	cursors := make([]int64, 0, len(request.Operations))
	for _, operation := range request.Operations {
		operationID, ok := strictUUID(operation.OperationID)
		if !ok {
			inputError(w, r)
			return
		}
		sessionID, ok := strictUUID(operation.SessionID)
		if !ok {
			inputError(w, r)
			return
		}
		params := domain.HistoryOperationParams{OperationID: operationID, UserID: p.id, Kind: operation.Kind, SessionID: sessionID, Now: a.now().UTC()}
		switch operation.Kind {
		case "turn.upsert":
			turnID, ok := strictUUID(operation.TurnID)
			if !ok {
				inputError(w, r)
				return
			}
			plaintext, ok := historyPayload(operation.Payload)
			if !ok {
				inputError(w, r)
				return
			}
			nonce, ciphertext, err := a.historyCipher.SealTurn(p.id, sessionID, turnID, plaintext)
			if err != nil {
				inputError(w, r)
				return
			}
			params.TurnID, params.KeyVersion, params.Nonce, params.Ciphertext = &turnID, a.historyCipher.KeyVersion(), nonce, ciphertext
		case "session.delete":
			if operation.TurnID != "" || operation.Payload != "" {
				inputError(w, r)
				return
			}
		case "title.patch":
			if operation.TurnID != "" {
				inputError(w, r)
				return
			}
			plaintext, ok := historyPayload(operation.Payload)
			if !ok {
				inputError(w, r)
				return
			}
			// A deterministic UUID domain-separates title ciphertext from every
			// turn while retaining the same authenticated encryption primitive.
			titleID := uuid.NewSHA1(uuid.NameSpaceURL, []byte("dngmeng-history-title:"+sessionID.String()))
			nonce, ciphertext, err := a.historyCipher.SealTurn(p.id, sessionID, titleID, plaintext)
			if err != nil {
				inputError(w, r)
				return
			}
			params.KeyVersion, params.TitleNonce, params.TitleCiphertext = a.historyCipher.KeyVersion(), nonce, ciphertext
		default:
			inputError(w, r)
			return
		}
		cursor, err := a.store.ApplyHistoryOperation(r.Context(), params)
		if err != nil {
			domainError(w, r, err)
			return
		}
		cursors = append(cursors, cursor)
	}
	writeJSON(w, http.StatusOK, map[string]any{"cursors": cursors})
}

func historyCursor(r *http.Request) (int64, error) {
	value := r.URL.Query().Get("cursor")
	if value == "" {
		return 0, nil
	}
	if strings.TrimSpace(value) != value {
		return 0, domain.ErrInvalid
	}
	for _, character := range value {
		if character < '0' || character > '9' {
			return 0, domain.ErrInvalid
		}
	}
	return strconv.ParseInt(value, 10, 64)
}

func (a api) historyPull(w http.ResponseWriter, r *http.Request) {
	if a.historyCipher == nil {
		writeError(w, r, http.StatusNotFound, "not_found")
		return
	}
	query := r.URL.Query()
	for key, values := range query {
		if (key != "cursor" && key != "limit") || len(values) != 1 {
			inputError(w, r)
			return
		}
	}
	cursor, err := historyCursor(r)
	if err != nil || cursor < 0 {
		inputError(w, r)
		return
	}
	if raw := query.Get("limit"); raw != "" && raw != strconv.Itoa(historySyncPageSize) {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	changes, err := a.store.ListHistoryChanges(r.Context(), p.id, cursor, historySyncPageSize)
	if err != nil {
		domainError(w, r, err)
		return
	}
	type changeResponse struct {
		Cursor  int64 `json:"cursor"`
		Session any   `json:"session"`
		Turn    any   `json:"turn,omitempty"`
	}
	response := make([]changeResponse, 0, len(changes))
	next := cursor
	for _, change := range changes {
		session, err := a.store.HistorySessionIncludingDeleted(r.Context(), p.id, change.SessionID)
		if err != nil {
			domainError(w, r, err)
			return
		}
		sessionResponse, err := historySessionResponse(a, session, p.id)
		if err != nil {
			writeError(w, r, http.StatusInternalServerError, "internal_error")
			return
		}
		item := changeResponse{Cursor: change.Cursor, Session: sessionResponse}
		if change.TurnID != nil {
			turn, err := a.store.HistoryTurnIncludingDeleted(r.Context(), p.id, *change.TurnID)
			if err != nil {
				domainError(w, r, err)
				return
			}
			turnResponse, err := historyTurnResponse(a, turn, p.id)
			if err != nil {
				writeError(w, r, http.StatusInternalServerError, "internal_error")
				return
			}
			item.Turn = turnResponse
		}
		response = append(response, item)
		next = change.Cursor
	}
	writeJSON(w, http.StatusOK, map[string]any{"changes": response, "next_cursor": next, "has_more": len(changes) == historySyncPageSize})
}

func historySessionResponse(a api, value domain.HistorySession, user uuid.UUID) (map[string]any, error) {
	out := map[string]any{"id": value.ID, "created_at": value.CreatedAt, "deleted_at": value.DeletedAt}
	if value.TitleUpdatedAt != nil && value.TitleKeyVersion != nil {
		titleID := uuid.NewSHA1(uuid.NameSpaceURL, []byte("dngmeng-history-title:"+value.ID.String()))
		plaintext, err := a.historyCipher.OpenTurn(user, value.ID, titleID, *value.TitleKeyVersion, value.TitleNonce, value.TitleCiphertext)
		if err != nil {
			return nil, err
		}
		out["title"] = string(plaintext)
		out["title_updated_at"] = value.TitleUpdatedAt
	}
	return out, nil
}
func historyTurnResponse(a api, value domain.EncryptedTurn, user uuid.UUID) (map[string]any, error) {
	out := map[string]any{"id": value.ID, "session_id": value.SessionID, "created_at": value.CreatedAt, "deleted_at": value.DeletedAt}
	if value.Live() {
		plaintext, err := a.historyCipher.OpenTurn(user, value.SessionID, value.ID, value.KeyVersion, value.Nonce, value.Ciphertext)
		if err != nil {
			return nil, err
		}
		out["payload"] = base64.StdEncoding.EncodeToString(plaintext)
	}
	return out, nil
}

func (a api) historyDelete(w http.ResponseWriter, r *http.Request) {
	p, _ := current(r)
	sessionID, ok := strictUUID(chi.URLParam(r, "sessionID"))
	if !ok {
		inputError(w, r)
		return
	}
	operationID, ok := strictUUID(r.Header.Get("Idempotency-Key"))
	if !ok {
		inputError(w, r)
		return
	}
	cursor, err := a.store.ApplyHistoryOperation(r.Context(), domain.HistoryOperationParams{OperationID: operationID, UserID: p.id, Kind: "session.delete", SessionID: sessionID, Now: a.now().UTC()})
	if err != nil {
		domainError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int64{"cursor": cursor})
}
func (a api) historyTitle(w http.ResponseWriter, r *http.Request) {
	p, _ := current(r)
	sessionID, ok := strictUUID(chi.URLParam(r, "sessionID"))
	if !ok {
		inputError(w, r)
		return
	}
	var request struct {
		OperationID string `json:"operation_id"`
		Title       string `json:"title"`
	}
	if decode(w, r, &request) != nil || request.Title == "" {
		inputError(w, r)
		return
	}
	operationID, ok := strictUUID(request.OperationID)
	if !ok {
		inputError(w, r)
		return
	}
	titleID := uuid.NewSHA1(uuid.NameSpaceURL, []byte("dngmeng-history-title:"+sessionID.String()))
	nonce, ciphertext, err := a.historyCipher.SealTurn(p.id, sessionID, titleID, []byte(request.Title))
	if err != nil {
		inputError(w, r)
		return
	}
	cursor, err := a.store.ApplyHistoryOperation(r.Context(), domain.HistoryOperationParams{OperationID: operationID, UserID: p.id, Kind: "title.patch", SessionID: sessionID, KeyVersion: a.historyCipher.KeyVersion(), TitleNonce: nonce, TitleCiphertext: ciphertext, Now: a.now().UTC()})
	if err != nil {
		domainError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int64{"cursor": cursor})
}
func (a api) adminHistory(w http.ResponseWriter, r *http.Request) {
	p, _ := current(r)
	userID, ok := strictUUID(chi.URLParam(r, "userID"))
	if !ok {
		inputError(w, r)
		return
	}
	sessions, turns, err := a.store.AdminHistory(r.Context(), p.id, userID)
	if err != nil {
		domainError(w, r, err)
		return
	}
	outSessions := make([]map[string]any, 0, len(sessions))
	for _, session := range sessions {
		response, err := historySessionResponse(a, session, userID)
		if err != nil {
			writeError(w, r, http.StatusInternalServerError, "internal_error")
			return
		}
		outSessions = append(outSessions, response)
	}
	outTurns := make([]map[string]any, 0, len(turns))
	for _, turn := range turns {
		response, err := historyTurnResponse(a, turn, userID)
		if err != nil {
			writeError(w, r, http.StatusInternalServerError, "internal_error")
			return
		}
		outTurns = append(outTurns, response)
	}
	writeJSON(w, http.StatusOK, map[string]any{"sessions": outSessions, "turns": outTurns})
}

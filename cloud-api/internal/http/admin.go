package httpapi

import (
	"net/http"
	"strings"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
)

func pathUUID(r *http.Request, key string) (uuid.UUID, bool) {
	v, e := uuid.Parse(chi.URLParam(r, key))
	return v, e == nil && v != uuid.Nil && v.String() == chi.URLParam(r, key)
}
func (a api) disableUser(w http.ResponseWriter, r *http.Request) {
	id, ok := pathUUID(r, "userID")
	if !ok {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	if e := a.store.DisableUser(r.Context(), p.id, id, a.now()); e != nil {
		domainError(w, r, e)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
func (a api) grantEntitlement(w http.ResponseWriter, r *http.Request) {
	id, ok := pathUUID(r, "userID")
	if !ok {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	v, e := a.store.GrantEntitlementByAdmin(r.Context(), p.id, id, a.now())
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, http.StatusCreated, v)
}
func (a api) revokeEntitlement(w http.ResponseWriter, r *http.Request) {
	user, ok := pathUUID(r, "userID")
	if !ok {
		inputError(w, r)
		return
	}
	ent, ok := pathUUID(r, "entitlementID")
	if !ok {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	if e := a.store.RevokeEntitlementByAdmin(r.Context(), p.id, user, ent, a.now()); e != nil {
		domainError(w, r, e)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
func (a api) codeBatch(w http.ResponseWriter, r *http.Request) {
	var x struct {
		Name  string `json:"name"`
		Count int    `json:"count"`
	}
	if decode(w, r, &x) != nil {
		inputError(w, r)
		return
	}
	in, e := domain.ParseCreateBatchInput(x.Name, x.Count)
	if e != nil {
		inputError(w, r)
		return
	}
	hashes := make([][]byte, 0, in.Count)
	codes := make([]string, 0, in.Count)
	for range in.Count {
		code, h, e := auth.RandomCode()
		if e != nil {
			writeError(w, r, 500, "internal_error")
			return
		}
		codes = append(codes, code)
		hashes = append(hashes, h)
	}
	p, _ := current(r)
	v, e := a.store.CreateCodeBatch(r.Context(), domain.CreateBatchParams{AdminID: p.id, Name: in.Name, DurationDays: 365, CodeHashes: hashes, Now: a.now()})
	if e != nil {
		domainError(w, r, e)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"batch": v, "codes": codes})
}

type adminAuditLogResponse struct {
	ID         uuid.UUID      `json:"id"`
	AdminID    uuid.UUID      `json:"admin_id"`
	Action     string         `json:"action"`
	TargetType string         `json:"target_type"`
	TargetID   *uuid.UUID     `json:"target_id,omitempty"`
	Metadata   map[string]any `json:"metadata"`
	CreatedAt  time.Time      `json:"created_at"`
}

func safeAuditLogResponse(log domain.AuditLog) adminAuditLogResponse {
	return adminAuditLogResponse{
		ID: log.ID, AdminID: log.AdminID, Action: log.Action, TargetType: log.TargetType,
		TargetID: log.TargetID, Metadata: map[string]any{}, CreatedAt: log.CreatedAt,
	}
}

func (a api) auditLogs(w http.ResponseWriter, r *http.Request) {
	l, o := page(r)
	v, e := a.store.ListAuditLogs(r.Context(), l, o)
	if e != nil {
		domainError(w, r, e)
		return
	}
	response := make([]adminAuditLogResponse, len(v))
	for i, log := range v {
		response[i] = safeAuditLogResponse(log)
	}
	writeJSON(w, http.StatusOK, map[string]any{"audit_logs": response})
}

var _ = strings.TrimSpace

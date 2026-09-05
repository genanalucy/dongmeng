package httpapi

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
)

// AgentInternalAuthorizePath is the internal, service-to-service endpoint the
// Agent calls to authorize a presented translation-session JWT against the
// persisted session lifecycle. It intentionally lives outside the public
// /api/v1 group: it must never be routed by the public reverse proxy, and it
// is mounted only when the deployment configures a shared service token.
const AgentInternalAuthorizePath = "/internal/v1/agent/translation-sessions/authorize"

// maxAgentTokenBytes bounds the submitted translation-session JWT; issued
// tokens are far smaller, so anything beyond this is malformed input.
const maxAgentTokenBytes = 4096

// agentSessionAuthorizer is the persisted-lifecycle authorization the internal
// boundary reuses. It is the same facade the public session endpoints use.
type agentSessionAuthorizer interface {
	AuthorizeTranslationSession(ctx context.Context, token string, now time.Time) (auth.Claims, error)
}

type agentAuthorizeRequest struct {
	// Token is the compact translation-session JWT exactly as the Agent
	// received it from its client. No caller-supplied user, session, or
	// install identifiers are trusted: identity comes only from the verified
	// token claims and the persisted session record.
	Token string `json:"token"`
}

type agentAuthorizeResponse struct {
	Active bool   `json:"active"`
	Reason string `json:"reason,omitempty"`
}

// agentServiceAuth rejects every request that does not present the exact
// shared Agent service token in a Bearer authorization header. Comparison is
// constant-time over SHA-256 digests so neither length nor prefix leaks.
func (a api) agentServiceAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		expected := sha256.Sum256([]byte(a.agentServiceToken))
		raw, ok := bearer(r.Header.Values("Authorization"))
		if ok {
			presented := sha256.Sum256([]byte(raw))
			if subtle.ConstantTimeCompare(expected[:], presented[:]) == 1 {
				next.ServeHTTP(w, r)
				return
			}
		}
		unauthorized(w, r)
	})
}

// agentSessionAuthorize answers whether the translation-session JWT the Agent
// holds is still authorized by the persisted lifecycle. The Agent is the only
// intended caller; the response vocabulary is deliberately tiny:
//
//   - 200 {"active":true}                       session may run
//   - 200 {"active":false,"reason":<safe>}      identity-matched terminal state
//   - 200 {"active":false}                      definitive denial without reason
//   - 400 invalid_request                       malformed request body
//
// Unknown, unparseable, or store-failed tokens never carry a reason, so the
// endpoint leaks no lifecycle detail to probing. Store failures collapse to a
// generic inactive answer: authorization fails closed.
func (a api) agentSessionAuthorize(w http.ResponseWriter, r *http.Request) {
	var request agentAuthorizeRequest
	if err := decode(w, r, &request); err != nil {
		writeError(w, r, http.StatusBadRequest, "invalid_request")
		return
	}
	request.Token = strings.TrimSpace(request.Token)
	if request.Token == "" || len(request.Token) > maxAgentTokenBytes {
		writeError(w, r, http.StatusBadRequest, "invalid_request")
		return
	}
	_, err := a.sessionAuthorizer.AuthorizeTranslationSession(r.Context(), request.Token, a.now())
	if err == nil {
		writeJSON(w, http.StatusOK, agentAuthorizeResponse{Active: true})
		return
	}
	var terminated domain.TerminatedTranslationSessionError
	if errors.As(err, &terminated) && domain.SafeTranslationTerminationReason(terminated.Reason) {
		writeJSON(w, http.StatusOK, agentAuthorizeResponse{Active: false, Reason: string(terminated.Reason)})
		return
	}
	writeJSON(w, http.StatusOK, agentAuthorizeResponse{Active: false})
}

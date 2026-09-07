package httpapi

import (
	"net/http"

	"github.com/dngmeng/cloud-api/internal/auth"
	"github.com/dngmeng/cloud-api/internal/domain"
)

// changeAdminPassword implements POST /api/v1/admin/password for the
// authenticated, enabled administrator behind the api.require + api.admin
// chain. The request body carries only current_password and new_password;
// unknown fields, non-JSON content types, and bodies above the dedicated
// 16 KiB ceiling are rejected by decodeLimit.
//
// Sequencing is deliberate: the current password is verified against the
// persisted hash before any policy evaluation or hashing of the new value,
// so the endpoint can never be used as a strength oracle without proving
// knowledge of the current credential, and argon2 work never runs inside the
// store transaction. The new password is validated by domain.ParsePassword
// (via auth.HashPassword), and a new password identical to the current one is
// rejected by constant-time hash comparison before the store is called.
//
// Errors never reveal which internal stage failed beyond the stable public
// codes: 400 invalid_request (malformed body or weak new password), 400
// password_unchanged, 403 invalid_current_password, 403/409 for an account
// that stopped being an enabled admin or a concurrent credential change, and
// 500 for storage failures.
func (a api) changeAdminPassword(w http.ResponseWriter, r *http.Request) {
	// Do not log from this handler: its body carries passwords. AccessLog
	// intentionally records metadata only.
	var x struct {
		CurrentPassword string `json:"current_password"`
		NewPassword     string `json:"new_password"`
	}
	if decodeLimit(w, r, &x, maxAdminPasswordBodyBytes) != nil || x.CurrentPassword == "" || x.NewPassword == "" {
		inputError(w, r)
		return
	}
	p, _ := current(r)
	hash, err := a.store.UserPasswordHash(r.Context(), p.id)
	if err != nil {
		domainError(w, r, err)
		return
	}
	valid, err := auth.VerifyPassword(hash, x.CurrentPassword)
	if err != nil || !valid {
		writeError(w, r, http.StatusForbidden, "invalid_current_password")
		return
	}
	newHash, err := auth.HashPassword(x.NewPassword)
	if err != nil || newHash == "" {
		inputError(w, r)
		return
	}
	unchanged, err := auth.VerifyPassword(hash, x.NewPassword)
	if err != nil || unchanged {
		writeError(w, r, http.StatusBadRequest, "password_unchanged")
		return
	}
	if err := a.store.ChangeAdminPassword(r.Context(), domain.AdminPasswordChangeParams{AdminID: p.id, CurrentHash: hash, NewHash: newHash, Now: a.now().UTC()}); err != nil {
		domainError(w, r, err)
		return
	}
	// From this commit onward the presented access token is stale (auth
	// version bumped) and every refresh token family is revoked; the client
	// must authenticate again with the new credential.
	w.WriteHeader(http.StatusNoContent)
}

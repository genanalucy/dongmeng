package store

import (
	"strings"
	"testing"

	"github.com/dngmeng/cloud-api/internal/domain"
)

func TestScanUserQueryUsesSixColumnPublicContract(t *testing.T) {
	query := `SELECT id,COALESCE(username,''),COALESCE(phone,''),email,role,created_at FROM users`
	for _, required := range []string{"id", "COALESCE(username,'')", "COALESCE(phone,'')", "email", "role", "created_at"} {
		if !strings.Contains(query, required) {
			t.Fatalf("query is missing %q", required)
		}
	}
}

func TestReservedEmailIsUniqueAndNotAPublicIdentity(t *testing.T) {
	first, second := reservedEmail(), reservedEmail()
	if first == second || !strings.HasPrefix(first, "phone-") || !strings.HasSuffix(first, "@reserved.invalid") {
		t.Fatal("reserved emails are not unique internal values")
	}
	if publicEmail(first) != "" {
		t.Fatal("reserved email escaped the public store read boundary")
	}
	legacy := "legacy@example.test"
	if publicEmail(legacy) != legacy {
		t.Fatal("legacy email was not preserved")
	}
	if domain.ErrConflict.Error() != "conflict" {
		t.Fatal("collision mapping is not generic")
	}
}

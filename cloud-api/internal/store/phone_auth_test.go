package store

import (
	"strings"
	"testing"

	"github.com/dngmeng/cloud-api/internal/domain"
)

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

package ast

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestEventDoesNotSerializeInternalDiagnostics(t *testing.T) {
	payload, err := json.Marshal(Event{
		Type:           "error",
		Code:           "VOLCENGINE_SESSION_FAILED",
		Message:        "translation session failed",
		Binary:         []byte("private audio"),
		UpstreamStatus: 11200,
	})
	if err != nil {
		t.Fatal(err)
	}
	serialized := string(payload)
	for _, forbidden := range []string{"UpstreamStatus", "upstream_status", "11200", "private audio", "Binary"} {
		if strings.Contains(serialized, forbidden) {
			t.Fatalf("serialized event contains internal diagnostic %q: %s", forbidden, serialized)
		}
	}
}

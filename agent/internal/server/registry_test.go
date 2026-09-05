package server

import (
	"context"
	"testing"
	"time"
)

func TestRegistryReplacementSupersedesAndStaleCleanupCannotRemoveIt(t *testing.T) {
	registry := newConnectionRegistry()
	cancelled := make([]string, 0, 2)
	cancels := map[string]chan struct{}{
		"first":  make(chan struct{}),
		"second": make(chan struct{}),
	}
	makeCancel := func(name string) context.CancelFunc {
		return func() {
			cancelled = append(cancelled, name)
			close(cancels[name])
		}
	}

	first := registry.register("identity", makeCancel("first"))
	if !registry.active(first) || registry.len() != 1 {
		t.Fatalf("first registration not active: active=%v len=%d", registry.active(first), registry.len())
	}

	// A newer connection with the same identity supersedes the older one: the
	// older context is cancelled and the newer registration replaces it.
	second := registry.register("identity", makeCancel("second"))
	select {
	case <-cancels["first"]:
	case <-time.After(time.Second):
		t.Fatal("superseded connection was not cancelled")
	}
	if registry.active(first) || !registry.active(second) || registry.len() != 1 {
		t.Fatalf("replacement not active: first=%v second=%v len=%d",
			registry.active(first), registry.active(second), registry.len())
	}

	// The superseded connection's stale cleanup must never erase the newer
	// registration.
	registry.unregister(first)
	if !registry.active(second) || registry.len() != 1 {
		t.Fatal("stale cleanup removed the replacement registration")
	}

	// The live connection's own cleanup is the only one that removes it.
	registry.unregister(second)
	if registry.active(second) || registry.len() != 0 {
		t.Fatal("live registration was not removed by its own cleanup")
	}

	// A later registration for the freed identity is not affected by the
	// retired handles.
	third := registry.register("identity", makeCancel("second"))
	if !registry.active(third) || registry.len() != 1 {
		t.Fatal("identity could not be re-registered after cleanup")
	}
	third.cancel()
}

func TestRegistryUnregisterNilAndDistinctKeysAreSafe(t *testing.T) {
	registry := newConnectionRegistry()
	registry.unregister(nil)
	one := registry.register("one", func() {})
	two := registry.register("two", func() {})
	registry.unregister(one)
	if registry.len() != 1 || !registry.active(two) {
		t.Fatalf("unrelated key was removed: len=%d two=%v", registry.len(), registry.active(two))
	}
	registry.unregister(two)
	if registry.len() != 0 {
		t.Fatal("registry not empty")
	}
}

func TestRegistryActiveNilHandleIsFalse(t *testing.T) {
	if newConnectionRegistry().active(nil) {
		t.Fatal("nil handle reported active")
	}
}

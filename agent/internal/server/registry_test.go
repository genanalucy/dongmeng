package server

import "testing"

func TestRegistryAllowsOverlappingConnectionsForSameIdentity(t *testing.T) {
	registry := newConnectionRegistry()
	firstCancelled := false
	secondCancelled := false
	first := registry.register("identity", func() { firstCancelled = true })
	second := registry.register("identity", func() { secondCancelled = true })

	if firstCancelled || secondCancelled {
		t.Fatal("registering an overlapping turn must not cancel either live connection")
	}
	if !registry.active(first) || !registry.active(second) || registry.len() != 2 {
		t.Fatalf("overlapping registrations not active: first=%v second=%v len=%d", registry.active(first), registry.active(second), registry.len())
	}

	registry.unregister(first)
	if registry.active(first) || !registry.active(second) || registry.len() != 1 {
		t.Fatal("first cleanup removed the overlapping second turn")
	}
	registry.unregister(second)
	if registry.len() != 0 {
		t.Fatal("registry retained a released connection")
	}
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

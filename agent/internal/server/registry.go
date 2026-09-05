package server

import (
	"context"
	"sync"
)

// registeredConnection is one live authorized connection recorded under the
// session identity derived from its verified token claims.
type registeredConnection struct {
	key    string
	cancel context.CancelFunc
}

// connectionRegistry tracks the active connection for each session token
// identity so a newer connection for the same token supersedes an older one,
// and so no still-running connection can be erased by a stale cleanup. It is
// safe for concurrent use.
type connectionRegistry struct {
	mu      sync.Mutex
	entries map[string]*registeredConnection
}

func newConnectionRegistry() *connectionRegistry {
	return &connectionRegistry{entries: make(map[string]*registeredConnection)}
}

// register records the connection for key. If an older connection is still
// registered under the same key it is superseded: its context is cancelled so
// its provider work and socket wind down, and the newer connection replaces
// it. The returned handle must be released by exactly the connection that
// obtained it, via unregister.
func (r *connectionRegistry) register(key string, cancel context.CancelFunc) *registeredConnection {
	handle := &registeredConnection{key: key, cancel: cancel}
	r.mu.Lock()
	defer r.mu.Unlock()
	if previous, exists := r.entries[key]; exists && previous != handle {
		// Cancel outside the lock is unnecessary here: cancel is safe to call
		// while holding the registry lock because it never blocks.
		previous.cancel()
	}
	r.entries[key] = handle
	return handle
}

// unregister removes the registration only when it still belongs to handle.
// A superseded connection's stale cleanup therefore can never erase the newer
// connection registered under the same key.
func (r *connectionRegistry) unregister(handle *registeredConnection) {
	if handle == nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if current, exists := r.entries[handle.key]; exists && current == handle {
		delete(r.entries, handle.key)
	}
}

// active reports whether handle is still the registered connection for its
// key. It exists for tests and operational inspection.
func (r *connectionRegistry) active(handle *registeredConnection) bool {
	if handle == nil {
		return false
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.entries[handle.key] == handle
}

// len reports the number of registered connections.
func (r *connectionRegistry) len() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.entries)
}

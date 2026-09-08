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

// connectionRegistry tracks all live connections for a verified session identity.
// A face-to-face AUTO turn may overlap the previous turn while it drains TTS, so
// legitimate connections sharing one token must coexist. It is safe for concurrent use.
type connectionRegistry struct {
	mu      sync.Mutex
	entries map[string]map[*registeredConnection]struct{}
}

func newConnectionRegistry() *connectionRegistry {
	return &connectionRegistry{entries: make(map[string]map[*registeredConnection]struct{})}
}

// register records one live connection. The Cloud session token has already been verified;
// registering a new turn under that identity deliberately does not cancel an older draining
// turn. Each returned handle must be released by that exact connection via unregister.
func (r *connectionRegistry) register(key string, cancel context.CancelFunc) *registeredConnection {
	handle := &registeredConnection{key: key, cancel: cancel}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.entries[key] == nil {
		r.entries[key] = make(map[*registeredConnection]struct{})
	}
	r.entries[key][handle] = struct{}{}
	return handle
}

// unregister removes only handle. A stale connection cleanup can therefore never remove
// another overlapping turn sharing the same authenticated session identity.
func (r *connectionRegistry) unregister(handle *registeredConnection) {
	if handle == nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	connections := r.entries[handle.key]
	delete(connections, handle)
	if len(connections) == 0 {
		delete(r.entries, handle.key)
	}
}

// active reports whether this exact handle remains registered.
func (r *connectionRegistry) active(handle *registeredConnection) bool {
	if handle == nil {
		return false
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	_, active := r.entries[handle.key][handle]
	return active
}

// len reports the number of live connections, including overlapping turns.
func (r *connectionRegistry) len() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	count := 0
	for _, connections := range r.entries {
		count += len(connections)
	}
	return count
}

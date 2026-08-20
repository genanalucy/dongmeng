//go:build officialast

package ast

import "translator-agent/internal/config"

// NewConfiguredClient enables the real Volcengine AST transport in tagged builds.
func NewConfiguredClient(cfg config.Config) Client { return newOfficialClient(cfg) }

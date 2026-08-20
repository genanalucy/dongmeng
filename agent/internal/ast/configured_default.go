//go:build !officialast

package ast

import "translator-agent/internal/config"

// NewConfiguredClient keeps normal builds independent of locally prepared
// official protobuf sources.
func NewConfiguredClient(config.Config) Client { return UnavailableClient{} }

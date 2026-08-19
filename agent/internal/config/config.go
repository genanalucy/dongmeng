// Package config loads local-agent configuration without exposing credentials.
package config

import (
	"errors"
	"fmt"
	"os"
)

const DefaultResourceID = "volc.service_type.10053"

// Config contains only the credential form selected for the AST client.
// Secret Key is intentionally not supported or read.
type Config struct {
	APIKey      string
	AppID       string
	AccessToken string
	ResourceID  string
}

// Load reads a current API key or the legacy App ID plus access-token pair.
func Load(getenv func(string) string) (Config, error) {
	if getenv == nil {
		getenv = os.Getenv
	}

	cfg := Config{
		APIKey:      getenv("VOLCENGINE_API_KEY"),
		AppID:       getenv("VOLCENGINE_APP_ID"),
		AccessToken: getenv("VOLCENGINE_ACCESS_TOKEN"),
		ResourceID:  getenv("VOLCENGINE_RESOURCE_ID"),
	}
	if cfg.ResourceID == "" {
		cfg.ResourceID = DefaultResourceID
	}

	hasAPIKey := cfg.APIKey != ""
	hasLegacyPart := cfg.AppID != "" || cfg.AccessToken != ""
	hasLegacyPair := cfg.AppID != "" && cfg.AccessToken != ""

	switch {
	case hasAPIKey && hasLegacyPart:
		return Config{}, errors.New("invalid Volcengine authentication configuration: choose API key or legacy credentials")
	case hasAPIKey:
		return cfg, nil
	case hasLegacyPair:
		return cfg, nil
	case hasLegacyPart:
		return Config{}, errors.New("invalid legacy Volcengine authentication configuration: both App ID and access token are required")
	default:
		return Config{}, fmt.Errorf("missing Volcengine authentication configuration")
	}
}

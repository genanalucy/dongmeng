// Package cloudauth consults the Cloud API's internal service endpoint to
// authorize a locally verified translation-session JWT against the persisted
// session lifecycle. It reuses the standard library HTTP stack and adds no
// new dependencies.
package cloudauth

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// Safe termination reasons mirrored from the Cloud allowlist. They are the
// only reason strings this client will surface; anything else collapses to a
// generic denial so a foreign or buggy endpoint cannot smuggle arbitrary
// detail into the Agent protocol.
const (
	ReasonEnded              = "ended"
	ReasonRevoked            = "revoked"
	ReasonReplacedByDevice   = "replaced_by_device"
	ReasonEntitlementRevoked = "entitlement_revoked"
	ReasonUserDisabled       = "user_disabled"
	ReasonExpired            = "expired"
)

// MinimumServiceTokenBytes matches the Cloud deployment contract for the
// shared Agent service token.
const MinimumServiceTokenBytes = 32

// ErrUnavailable reports that the authorization endpoint could not answer:
// network failure, timeout, service-credential rejection, or a malformed
// response. It never means the session was denied; callers must tolerate it
// for a bounded window and then fail closed.
var ErrUnavailable = errors.New("cloud translation session authorization unavailable")

// Decision is the endpoint's definitive answer for one translation-session
// token. Active reports whether the session may run. When the token matched a
// persisted session identity that is no longer usable, Reason carries one of
// the safe allowlisted termination reasons; an empty reason with Active false
// is a definitive generic denial.
type Decision struct {
	Active bool
	Reason string
}

// Client is an immutable HTTP client for the internal Cloud authorization
// endpoint. It is safe for concurrent use.
type Client struct {
	endpoint     string
	serviceToken string
	httpClient   *http.Client
}

// NewClient validates the endpoint URL and shared service token and returns
// an immutable client. A nil httpClient selects the default transport; every
// call is additionally bounded by its context deadline.
func NewClient(endpoint, serviceToken string, httpClient *http.Client) (*Client, error) {
	if strings.TrimSpace(endpoint) != endpoint || endpoint == "" {
		return nil, errors.New("invalid cloud authorization endpoint")
	}
	parsed, err := url.Parse(endpoint)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" ||
		parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return nil, errors.New("invalid cloud authorization endpoint")
	}
	if len(serviceToken) < MinimumServiceTokenBytes {
		return nil, errors.New("invalid cloud authorization service token")
	}
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	return &Client{endpoint: endpoint, serviceToken: serviceToken, httpClient: httpClient}, nil
}

type authorizeRequest struct {
	// Token is the compact translation-session JWT exactly as presented by
	// the Agent's client. Identity is never supplied or trusted separately.
	Token string `json:"token"`
}

type authorizeResponse struct {
	// Active is a pointer so a response that omits the decision field is
	// detected as malformed instead of silently read as a denial.
	Active *bool  `json:"active"`
	Reason string `json:"reason"`
}

// Authorize asks the Cloud whether sessionToken is still authorized. The
// token itself is the only identity input. The caller bounds the call with
// ctx; exceeding the deadline returns an error wrapping ErrUnavailable.
func (c *Client) Authorize(ctx context.Context, sessionToken string) (Decision, error) {
	if c == nil || strings.TrimSpace(sessionToken) == "" {
		return Decision{}, fmt.Errorf("%w: no session token to authorize", ErrUnavailable)
	}
	body, err := json.Marshal(authorizeRequest{Token: sessionToken})
	if err != nil {
		return Decision{}, fmt.Errorf("%w: encode request", ErrUnavailable)
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint, bytes.NewReader(body))
	if err != nil {
		return Decision{}, fmt.Errorf("%w: build request", ErrUnavailable)
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer "+c.serviceToken)
	response, err := c.httpClient.Do(request)
	if err != nil {
		if ctx.Err() != nil {
			return Decision{}, fmt.Errorf("%w: deadline exceeded", ErrUnavailable)
		}
		return Decision{}, fmt.Errorf("%w: request failed", ErrUnavailable)
	}
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode != http.StatusOK {
		// 401/403 mean the service credential was rejected, and any other
		// status is an endpoint malfunction; neither denies the session.
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 1<<10))
		return Decision{}, fmt.Errorf("%w: endpoint returned status %d", ErrUnavailable, response.StatusCode)
	}
	var answer authorizeResponse
	decoder := json.NewDecoder(io.LimitReader(response.Body, 1<<12))
	if err := decoder.Decode(&answer); err != nil {
		return Decision{}, fmt.Errorf("%w: malformed response", ErrUnavailable)
	}
	if answer.Active == nil {
		return Decision{}, fmt.Errorf("%w: malformed response", ErrUnavailable)
	}
	if *answer.Active {
		return Decision{Active: true}, nil
	}
	if safeTerminationReason(answer.Reason) {
		return Decision{Active: false, Reason: answer.Reason}, nil
	}
	return Decision{Active: false}, nil
}

func safeTerminationReason(reason string) bool {
	switch reason {
	case ReasonEnded, ReasonRevoked, ReasonReplacedByDevice, ReasonEntitlementRevoked, ReasonUserDisabled, ReasonExpired:
		return true
	default:
		return false
	}
}

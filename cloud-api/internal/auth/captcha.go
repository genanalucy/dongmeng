package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"fmt"
	"math/big"
	"strings"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
)

const (
	// CaptchaTTL bounds how long an issued challenge stays verifiable.
	CaptchaTTL = 5 * time.Minute
	// CaptchaMaxAttempts bounds wrong answers before the challenge is consumed.
	CaptchaMaxAttempts = 5
	// CaptchaIssueIPPerHour and CaptchaRegisterIPPerHour bound per trusted
	// client IP fixed-window usage of the captcha and registration endpoints.
	CaptchaIssueIPPerHour    = 30
	CaptchaRegisterIPPerHour = 10

	CaptchaChallengeLength = 5
	captchaSaltLength      = 32
	// CaptchaAnswerMaxBytes bounds submitted answers before any hashing; the
	// challenge itself is only CaptchaChallengeLength characters.
	CaptchaAnswerMaxBytes = 64
)

// captchaAlphabet is the unambiguous uppercase alphanumeric set: I, O, 0, and
// 1 are excluded so rendered challenges stay human-readable. Matching is
// case-insensitive because answers are normalized before hashing.
const captchaAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

var captchaAlphabetMembership [256]bool

func init() {
	for index := 0; index < len(captchaAlphabet); index++ {
		captchaAlphabetMembership[captchaAlphabet[index]] = true
	}
}

// CaptchaDraft is the issue-time material for one challenge. The challenge
// text exists only here and in the rendered SVG response; persistence receives
// only AnswerHash and AnswerSalt.
type CaptchaDraft struct {
	Challenge  string
	AnswerHash []byte
	AnswerSalt []byte
	ExpiresAt  time.Time
}

// CaptchaService dependencies are injected to keep challenge randomness,
// hashing, and time testable without persistence or HTTP wiring.
type CaptchaService struct {
	AnswerPepper       []byte
	RateLimitKeySecret []byte
	GenerateChallenge  func() (string, error)
	GenerateSalt       func() ([]byte, error)
	Clock              func() time.Time
}

// NewCaptchaService validates the fail-closed secrets and installs
// cryptographic defaults for the generator and clock.
func NewCaptchaService(service CaptchaService) (CaptchaService, error) {
	if len(service.AnswerPepper) < MinimumSecretBytes || len(service.RateLimitKeySecret) < MinimumSecretBytes {
		return CaptchaService{}, fmt.Errorf("%w: captcha secrets must be at least %d bytes", domain.ErrInvalid, MinimumSecretBytes)
	}
	if service.GenerateChallenge == nil {
		service.GenerateChallenge = generateCaptchaChallenge
	}
	if service.GenerateSalt == nil {
		service.GenerateSalt = generateCaptchaSalt
	}
	if service.Clock == nil {
		service.Clock = time.Now
	}
	return service, nil
}

// Issue creates one challenge with its salted answer hash and expiry.
func (s CaptchaService) Issue() (CaptchaDraft, error) {
	challenge, err := s.GenerateChallenge()
	if err != nil {
		return CaptchaDraft{}, fmt.Errorf("generate captcha challenge: %w", err)
	}
	if _, err := ParseCaptchaAnswer(challenge); err != nil {
		return CaptchaDraft{}, err
	}
	salt, err := s.GenerateSalt()
	if err != nil {
		return CaptchaDraft{}, fmt.Errorf("generate captcha salt: %w", err)
	}
	if len(salt) < 16 || len(salt) > 64 {
		return CaptchaDraft{}, fmt.Errorf("%w: captcha salt length is out of range", domain.ErrInvalid)
	}
	answerHash, err := captchaAnswerHash(s.AnswerPepper, salt, challenge)
	if err != nil {
		return CaptchaDraft{}, err
	}
	now := s.Clock().UTC()
	if now.IsZero() {
		return CaptchaDraft{}, fmt.Errorf("%w: captcha clock is required", domain.ErrInvalid)
	}
	return CaptchaDraft{Challenge: challenge, AnswerHash: answerHash, AnswerSalt: salt, ExpiresAt: now.Add(CaptchaTTL)}, nil
}

// ParseCaptchaAnswer validates and canonicalizes a challenge-shaped value.
func ParseCaptchaAnswer(value string) (string, error) {
	normalized := NormalizeCaptchaAnswer(value)
	if len(normalized) != CaptchaChallengeLength {
		return "", fmt.Errorf("%w: captcha answer must be %d characters from the unambiguous alphabet", domain.ErrInvalid, CaptchaChallengeLength)
	}
	for index := 0; index < len(normalized); index++ {
		if !captchaAlphabetMembership[normalized[index]] {
			return "", fmt.Errorf("%w: captcha answer contains a character outside the unambiguous alphabet", domain.ErrInvalid)
		}
	}
	return normalized, nil
}

// NormalizeCaptchaAnswer canonicalizes user input before hashing or storage
// comparison: surrounding whitespace is trimmed and letters are uppercased so
// matching is case-insensitive.
func NormalizeCaptchaAnswer(value string) string {
	return strings.ToUpper(strings.TrimSpace(value))
}

// CaptchaAnswerMatches reports whether the raw submitted answer verifies
// against the persisted salted hash in constant time.
func CaptchaAnswerMatches(pepper, salt, expectedHash []byte, rawAnswer string) bool {
	if len(pepper) == 0 || len(salt) == 0 || len(expectedHash) != sha256.Size {
		return false
	}
	actualHash, err := captchaAnswerHash(pepper, salt, NormalizeCaptchaAnswer(rawAnswer))
	return err == nil && subtle.ConstantTimeCompare(expectedHash, actualHash) == 1
}

func captchaAnswerHash(pepper, salt []byte, normalizedAnswer string) ([]byte, error) {
	if len(pepper) == 0 {
		return nil, fmt.Errorf("%w: captcha answer pepper is required", domain.ErrInvalid)
	}
	mac := hmac.New(sha256.New, pepper)
	_, _ = mac.Write(salt)
	_, _ = mac.Write([]byte(normalizedAnswer))
	return mac.Sum(nil), nil
}

func generateCaptchaChallenge() (string, error) {
	challenge := make([]byte, CaptchaChallengeLength)
	alphabetSize := big.NewInt(int64(len(captchaAlphabet)))
	for index := range challenge {
		value, err := rand.Int(rand.Reader, alphabetSize)
		if err != nil {
			return "", fmt.Errorf("generate captcha challenge: %w", err)
		}
		challenge[index] = captchaAlphabet[value.Int64()]
	}
	return string(challenge), nil
}

func generateCaptchaSalt() ([]byte, error) {
	salt := make([]byte, captchaSaltLength)
	if _, err := rand.Read(salt); err != nil {
		return nil, fmt.Errorf("generate captcha salt: %w", err)
	}
	return salt, nil
}

// captchaJitter returns a uniformly random offset in [-limit, limit] sourced
// from the cryptographic reader; failures collapse to zero, never to panic.
func captchaJitter(limit int64) int64 {
	value, err := rand.Int(rand.Reader, big.NewInt(2*limit+1))
	if err != nil {
		return 0
	}
	return value.Int64() - limit
}

const (
	captchaSVGWidth   = 160
	captchaSVGHeight  = 56
	captchaSVGNS      = "http://www.w3.org/2000/svg"
	captchaCharFormat = `<text x="%d" y="%d" font-family="monospace" font-size="%d" font-weight="bold" fill="#%06x" text-anchor="middle" transform="rotate(%d %d %d)">%c</text>`
)

// captchaForegroundPalette holds dark, high-contrast render colors.
var captchaForegroundPalette = []uint32{0x2B3A55, 0x3F3F46, 0x1F3B26, 0x4A2B2B, 0x243B53}

// RenderCaptchaSVG renders one challenge locally as a self-contained SVG with
// per-character position, size, rotation, and foreground jitter plus noise
// lines. It references no external resources and returns the empty string for
// malformed challenges instead of emitting attacker-controlled text.
func RenderCaptchaSVG(challenge string) string {
	if _, err := ParseCaptchaAnswer(challenge); err != nil {
		return ""
	}
	var svg strings.Builder
	fmt.Fprintf(&svg, `<svg xmlns=%q width="%d" height="%d" viewBox="0 0 %d %d" role="img" aria-label="captcha">`, captchaSVGNS, captchaSVGWidth, captchaSVGHeight, captchaSVGWidth, captchaSVGHeight)
	fmt.Fprintf(&svg, `<rect width="%d" height="%d" rx="4" fill="#F2F4F7"/>`, captchaSVGWidth, captchaSVGHeight)
	for line := 0; line < 3; line++ {
		fmt.Fprintf(&svg, `<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#C8CED6" stroke-width="1"/>`,
			captchaSVGCoord(captchaSVGWidth), captchaSVGCoord(captchaSVGHeight),
			captchaSVGCoord(captchaSVGWidth), captchaSVGCoord(captchaSVGHeight))
	}
	const margin = 12
	step := (captchaSVGWidth - 2*margin) / len(challenge)
	for index := 0; index < len(challenge); index++ {
		x := margin + step*index + step/2 + int(captchaJitter(4))
		y := 36 + int(captchaJitter(5))
		size := 25 + int(captchaJitter(3))
		rotation := int(captchaJitter(18))
		color := captchaForegroundPalette[captchaPaletteIndex()]
		fmt.Fprintf(&svg, captchaCharFormat, x, y, size, color, rotation, x, y, challenge[index])
	}
	svg.WriteString(`</svg>`)
	return svg.String()
}

func captchaSVGCoord(limit int) int {
	return int(captchaJitter(int64(limit/2)) + int64(limit/2))
}

func captchaPaletteIndex() int {
	value, err := rand.Int(rand.Reader, big.NewInt(int64(len(captchaForegroundPalette))))
	if err != nil {
		return 0
	}
	return int(value.Int64())
}

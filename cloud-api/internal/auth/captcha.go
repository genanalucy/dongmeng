package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"fmt"
	"math"
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
	captchaSVGWidth  = 160
	captchaSVGHeight = 56
	captchaSVGNS     = "http://www.w3.org/2000/svg"
	// captchaGlyphGridW/H define the design grid every stroke glyph is
	// authored on; rendering scales and rotates that grid per character.
	captchaGlyphGridW = 9.0
	captchaGlyphGridH = 12.0
	// captchaStrokeWidth is the stroke weight in rendered pixels.
	captchaStrokeWidth = 2.3
)

// captchaForegroundPalette holds dark, high-contrast render colors.
var captchaForegroundPalette = []uint32{0x2B3A55, 0x3F3F46, 0x1F3B26, 0x4A2B2B, 0x243B53}

// captchaPoint is one authoring-grid vertex of a glyph stroke.
type captchaPoint struct{ X, Y float64 }

// captchaGlyph is one character expressed as polyline strokes. Coordinates
// live on the captchaGlyphGridW x captchaGlyphGridH grid with the baseline at
// Y = captchaGlyphGridH.
type captchaGlyph [][]captchaPoint

// captchaGlyphs carries stroke outlines for every character of
// captchaAlphabet. Glyphs are pure geometry: the SVG never carries the
// answer as text, title, description, or metadata, so parsing the XML yields
// no machine-readable characters of the challenge.
var captchaGlyphs = map[byte]captchaGlyph{
	'A': {{{0, 12}, {4.5, 0}, {9, 12}}, {{1.8, 7.6}, {7.2, 7.6}}},
	'B': {{{0, 0}, {0, 12}}, {{0, 0}, {6, 0}, {8.5, 2}, {8.5, 4}, {6, 6}, {0, 6}}, {{0, 6}, {6.6, 6}, {9, 8}, {9, 10}, {6.6, 12}, {0, 12}}},
	'C': {{{9, 2.4}, {6.2, 0}, {3, 0}, {0, 3}, {0, 9}, {3, 12}, {6.2, 12}, {9, 9.6}}},
	'D': {{{0, 0}, {0, 12}}, {{0, 0}, {5.5, 0}, {9, 3.6}, {9, 8.4}, {5.5, 12}, {0, 12}}},
	'E': {{{9, 0}, {0, 0}, {0, 12}, {9, 12}}, {{0, 6}, {6.6, 6}}},
	'F': {{{9, 0}, {0, 0}, {0, 12}}, {{0, 6}, {6.6, 6}}},
	'G': {{{9, 2.4}, {6.2, 0}, {3, 0}, {0, 3}, {0, 9}, {3, 12}, {6.2, 12}, {9, 9.6}, {9, 6}, {5.2, 6}}},
	'H': {{{0, 0}, {0, 12}}, {{9, 0}, {9, 12}}, {{0, 6}, {9, 6}}},
	'J': {{{9, 0}, {9, 9}, {6.6, 12}, {3.2, 12}, {0.8, 9.9}}},
	'K': {{{0, 0}, {0, 12}}, {{9, 0}, {0, 6.6}}, {{2.4, 4.9}, {9, 12}}},
	'L': {{{0, 0}, {0, 12}, {9, 12}}},
	'M': {{{0, 12}, {0, 0}, {4.5, 6.5}, {9, 0}, {9, 12}}},
	'N': {{{0, 12}, {0, 0}, {9, 12}, {9, 0}}},
	'P': {{{0, 12}, {0, 0}}, {{0, 0}, {6, 0}, {9, 2.6}, {9, 4.6}, {6, 7}, {0, 7}}},
	'Q': {{{4.5, 0}, {6.9, 0.9}, {8.2, 3}, {8.2, 4.8}, {6.9, 6.9}, {4.5, 7.8}, {2.1, 6.9}, {0.8, 4.8}, {0.8, 3}, {2.1, 0.9}, {4.5, 0}}, {{5.6, 6.4}, {8.8, 11.4}}},
	'R': {{{0, 12}, {0, 0}, {6, 0}, {9, 2.6}, {9, 4.6}, {6, 7}, {0, 7}}, {{2.6, 4.9}, {9, 12}}},
	'S': {{{8.6, 2.1}, {6, 0}, {3, 0}, {0.6, 2}, {0.6, 4}, {3, 5.9}, {6, 5.9}, {8.6, 7.9}, {8.6, 10}, {6, 12}, {3, 12}, {0.6, 9.9}}},
	'T': {{{0, 0}, {9, 0}}, {{4.5, 0}, {4.5, 12}}},
	'U': {{{0, 0}, {0, 9}, {2.6, 11.6}, {6.4, 11.6}, {9, 9}, {9, 0}}},
	'V': {{{0, 0}, {4.5, 12}, {9, 0}}},
	'W': {{{0, 0}, {1.9, 12}, {4.5, 5.2}, {7.1, 12}, {9, 0}}},
	'X': {{{0, 0}, {9, 12}}, {{0, 12}, {9, 0}}},
	'Y': {{{0, 0}, {4.5, 6.2}, {9, 0}}, {{4.5, 6.2}, {4.5, 12}}},
	'Z': {{{0.4, 0}, {8.6, 0}, {0.4, 12}, {8.6, 12}}},
	'2': {{{0.4, 2.6}, {2.8, 0}, {6.2, 0}, {8.6, 2.6}, {8.6, 4.8}, {0.4, 9.4}, {0.4, 12}, {8.6, 12}}},
	'3': {{{0.5, 1.8}, {3.2, 0}, {5.8, 0}, {8.5, 2.4}, {8.5, 4.4}, {6.2, 6.1}, {8.5, 7.9}, {8.5, 9.7}, {5.8, 12}, {3.2, 12}, {0.5, 10.2}}},
	'4': {{{6.8, 0}, {0.4, 7.6}, {8.8, 7.6}}, {{6.8, 3.4}, {6.8, 12}}},
	'5': {{{8.6, 0}, {0.6, 0}, {0.6, 5.4}, {5.4, 5.4}, {8.6, 7.8}, {8.6, 9.9}, {5.8, 12}, {2.6, 12}, {0.6, 10.4}}},
	'6': {{{8.4, 1.6}, {5.2, 0}, {2.2, 2.2}, {2.2, 8.8}, {4.6, 11.8}, {7, 11.8}, {8.8, 9.7}, {8.8, 8}, {7, 6.4}, {4.4, 6.4}, {2.2, 8.2}}},
	'7': {{{0.4, 0}, {8.6, 0}, {3.6, 12}}},
	'8': {{{4.5, 0}, {6.6, 0.8}, {7.6, 2.5}, {6.9, 4.4}, {4.5, 5.9}, {2.1, 4.4}, {1.4, 2.5}, {2.4, 0.8}, {4.5, 0}}, {{4.5, 5.9}, {7.2, 7.1}, {8.2, 9.2}, {7.2, 11.2}, {4.5, 12}, {1.8, 11.2}, {0.8, 9.2}, {1.8, 7.1}, {4.5, 5.9}}},
	'9': {{{1.6, 10.4}, {4.8, 12}, {7.8, 9.8}, {7.8, 3.2}, {5.4, 0.2}, {3, 0.2}, {1.2, 2}, {1.2, 3.8}, {3, 5.4}, {5.6, 5.4}, {7.8, 3.6}}},
}

// RenderCaptchaSVG renders one challenge locally as a self-contained SVG.
// Characters are emitted as rotated, jittered stroke outlines (path data),
// never as text nodes: the answer is not recoverable by parsing the XML or by
// reading accessibility metadata. The accessibility label stays generic so
// assistive technology announces the challenge's purpose without disclosing
// its answer. It references no external resources and returns the empty
// string for malformed challenges instead of emitting attacker-controlled
// content.
func RenderCaptchaSVG(challenge string) string {
	if _, err := ParseCaptchaAnswer(challenge); err != nil {
		return ""
	}
	var svg strings.Builder
	fmt.Fprintf(&svg, `<svg xmlns=%q width="%d" height="%d" viewBox="0 0 %d %d" role="img" aria-label="captcha image">`, captchaSVGNS, captchaSVGWidth, captchaSVGHeight, captchaSVGWidth, captchaSVGHeight)
	fmt.Fprintf(&svg, `<rect width="%d" height="%d" rx="4" fill="#F2F4F7"/>`, captchaSVGWidth, captchaSVGHeight)
	for line := 0; line < 3; line++ {
		fmt.Fprintf(&svg, `<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#C8CED6" stroke-width="1"/>`,
			captchaSVGCoord(captchaSVGWidth), captchaSVGCoord(captchaSVGHeight),
			captchaSVGCoord(captchaSVGWidth), captchaSVGCoord(captchaSVGHeight))
	}
	const margin = 12
	step := (captchaSVGWidth - 2*margin) / len(challenge)
	for index := 0; index < len(challenge); index++ {
		glyph, ok := captchaGlyphs[challenge[index]]
		if !ok {
			// ParseCaptchaAnswer already bounds the alphabet, so a missing
			// glyph is a programming error that must fail closed.
			return ""
		}
		x := float64(margin + step*index + step/2 + int(captchaJitter(4)))
		baseline := float64(36 + int(captchaJitter(5)))
		size := float64(25 + int(captchaJitter(3)))
		rotation := float64(captchaJitter(18))
		color := captchaForegroundPalette[captchaPaletteIndex()]
		scale := size / captchaGlyphGridH
		// The glyph origin maps the design grid's baseline onto the jittered
		// baseline; rotation pivots on the glyph's visual center.
		originX := x - captchaGlyphGridW*scale/2
		originY := baseline - captchaGlyphGridH*scale
		centerX := x
		centerY := baseline - size/2
		radians := rotation * math.Pi / 180
		cos, sin := math.Cos(radians), math.Sin(radians)
		var path strings.Builder
		for _, stroke := range glyph {
			for pointIndex, point := range stroke {
				sx := originX + point.X*scale
				sy := originY + point.Y*scale
				dx, dy := sx-centerX, sy-centerY
				rx := centerX + dx*cos - dy*sin
				ry := centerY + dx*sin + dy*cos
				if pointIndex == 0 {
					fmt.Fprintf(&path, "M%.1f %.1f", rx, ry)
					continue
				}
				fmt.Fprintf(&path, "L%.1f %.1f", rx, ry)
			}
		}
		fmt.Fprintf(&svg, `<path d="%s" fill="none" stroke="#%06x" stroke-width="%.1f" stroke-linecap="round" stroke-linejoin="round"/>`, path.String(), color, captchaStrokeWidth)
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

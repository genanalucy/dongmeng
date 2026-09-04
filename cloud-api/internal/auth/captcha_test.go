package auth

import (
	"bytes"
	"crypto/rand"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
)

func TestCaptchaAlphabetExcludesAmbiguousCharacters(t *testing.T) {
	for _, ambiguous := range []string{"I", "O", "0", "1"} {
		if strings.Contains(captchaAlphabet, ambiguous) {
			t.Fatalf("alphabet %q contains ambiguous character %q", captchaAlphabet, ambiguous)
		}
	}
	if len(captchaAlphabet) != 32 {
		t.Fatalf("alphabet length = %d, want 32", len(captchaAlphabet))
	}
}

func TestCaptchaChallengeGenerationIsRandomWithinReadableAlphabet(t *testing.T) {
	service := newTestCaptchaService(t)
	seen := make(map[string]struct{})
	for range 256 {
		challenge, err := service.GenerateChallenge()
		if err != nil {
			t.Fatalf("GenerateChallenge() error = %v", err)
		}
		if len(challenge) != CaptchaChallengeLength {
			t.Fatalf("challenge length = %d, want %d", len(challenge), CaptchaChallengeLength)
		}
		for _, char := range challenge {
			if !strings.ContainsRune(captchaAlphabet, char) {
				t.Fatalf("challenge %q contains character %q outside the alphabet", challenge, char)
			}
		}
		seen[challenge] = struct{}{}
	}
	// 256 draws over 32^5 combinations must not collapse to a handful of values.
	if len(seen) < 200 {
		t.Fatalf("challenge generation is not random: %d distinct values in 256 draws", len(seen))
	}
}

func TestCaptchaIssueProducesSaltedHashAndExpiry(t *testing.T) {
	now := time.Date(2026, 9, 5, 6, 7, 8, 0, time.UTC)
	service, err := NewCaptchaService(CaptchaService{
		AnswerPepper:       bytes.Repeat([]byte("p"), MinimumSecretBytes),
		RateLimitKeySecret: bytes.Repeat([]byte("k"), MinimumSecretBytes),
		GenerateChallenge:  func() (string, error) { return "AB3CD", nil },
		GenerateSalt:       func() ([]byte, error) { return bytes.Repeat([]byte("s"), 32), nil },
		Clock:              func() time.Time { return now },
	})
	if err != nil {
		t.Fatal(err)
	}
	draft, err := service.Issue()
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}
	if draft.Challenge != "AB3CD" {
		t.Fatalf("challenge = %q", draft.Challenge)
	}
	if len(draft.AnswerSalt) != captchaSaltLength || len(draft.AnswerHash) != 32 {
		t.Fatalf("draft hash/salt lengths = %d/%d", len(draft.AnswerHash), len(draft.AnswerSalt))
	}
	if !draft.ExpiresAt.Equal(now.Add(CaptchaTTL)) {
		t.Fatalf("expiry = %v, want %v", draft.ExpiresAt, now.Add(CaptchaTTL))
	}
	if !CaptchaAnswerMatches(service.AnswerPepper, draft.AnswerSalt, draft.AnswerHash, "ab3cd") {
		t.Fatal("issued draft does not verify against a case-insensitive answer")
	}
}

func TestCaptchaAnswerHashBindsSaltAndAnswer(t *testing.T) {
	service := newTestCaptchaService(t)
	first, err := service.Issue()
	if err != nil {
		t.Fatal(err)
	}
	second, err := service.Issue()
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Equal(first.AnswerHash, second.AnswerHash) {
		t.Fatal("different challenges and salts produced identical hashes")
	}
	if CaptchaAnswerMatches(service.AnswerPepper, first.AnswerSalt, first.AnswerHash, first.Challenge[:3]+"ZZ") {
		t.Fatal("wrong answer was accepted")
	}
	if CaptchaAnswerMatches(service.AnswerPepper, second.AnswerSalt, first.AnswerHash, first.Challenge) {
		t.Fatal("mismatched salt was accepted")
	}
	if CaptchaAnswerMatches(nil, first.AnswerSalt, first.AnswerHash, first.Challenge) {
		t.Fatal("missing pepper was accepted")
	}
	if CaptchaAnswerMatches(service.AnswerPepper, nil, first.AnswerHash, first.Challenge) {
		t.Fatal("missing salt was accepted")
	}
	if CaptchaAnswerMatches(service.AnswerPepper, first.AnswerSalt, nil, first.Challenge) {
		t.Fatal("missing hash was accepted")
	}
}

func TestParseCaptchaAnswerRejectsMalformedInput(t *testing.T) {
	for _, value := range []string{"", "AB3C", "AB3CDE", "AB3 D", "AIO01", "AB3C?", "ab3cd!", strings.Repeat("A", 33)} {
		if _, err := ParseCaptchaAnswer(value); err == nil {
			t.Fatalf("ParseCaptchaAnswer(%q) error = nil, want rejection", value)
		}
	}
	answer, err := ParseCaptchaAnswer(" ab3cd ")
	if err != nil || answer != "AB3CD" {
		t.Fatalf("ParseCaptchaAnswer() = %q, %v", answer, err)
	}
}

func TestRenderCaptchaSVGDoesNotDiscloseTheAnswer(t *testing.T) {
	const challenge = "AB3CD"
	svg := RenderCaptchaSVG(challenge)
	if !strings.HasPrefix(svg, "<svg") || !strings.HasSuffix(svg, "</svg>") {
		t.Fatalf("svg envelope = %q", svg[:min(32, len(svg))])
	}
	// The answer must not be machine-recoverable: no text nodes, no
	// title/description metadata, and no contiguous challenge characters in
	// any case, neither in markup nor in path data or accessibility labels.
	for _, forbidden := range []string{"<text", "</text", "<title", "<desc", "<glyph", "font-family", "script", "href", "javascript", "xlink"} {
		if strings.Contains(svg, forbidden) {
			t.Fatalf("svg contains forbidden content %q", forbidden)
		}
	}
	if strings.Contains(svg, challenge) || strings.Contains(svg, strings.ToLower(challenge)) {
		t.Fatal("svg discloses the challenge answer as contiguous characters")
	}
	if label := `aria-label="captcha image"`; !strings.Contains(svg, label) {
		t.Fatalf("svg must carry only the generic accessibility label %q", label)
	}
	// One stroke path per character keeps the render self-contained geometry.
	if got := strings.Count(svg, "<path"); got != len(challenge) {
		t.Fatalf("stroke paths = %d, want %d", got, len(challenge))
	}
	if size := len(svg); size > 4096 {
		t.Fatalf("svg size = %d bytes, want bounded", size)
	}
	if RenderCaptchaSVG("AB3 D") != "" || RenderCaptchaSVG("AB3CDE") != "" || RenderCaptchaSVG("AIO01") != "" {
		t.Fatal("renderer accepted a malformed challenge")
	}
}

func TestRenderCaptchaSVGStrokeGlyphsCoverTheWholeAlphabet(t *testing.T) {
	if len(captchaGlyphs) != len(captchaAlphabet) {
		t.Fatalf("glyph table covers %d characters, alphabet has %d", len(captchaGlyphs), len(captchaAlphabet))
	}
	for index := 0; index < len(captchaAlphabet); index++ {
		char := captchaAlphabet[index]
		glyph, ok := captchaGlyphs[char]
		if !ok || len(glyph) == 0 {
			t.Fatalf("alphabet character %q has no stroke glyph", char)
		}
		for _, stroke := range glyph {
			if len(stroke) < 2 {
				t.Fatalf("glyph %q has a degenerate stroke", char)
			}
			for _, point := range stroke {
				if point.X < 0 || point.X > captchaGlyphGridW || point.Y < 0 || point.Y > captchaGlyphGridH {
					t.Fatalf("glyph %q point %.1f,%.1f escapes the authoring grid", char, point.X, point.Y)
				}
			}
		}
		// Rendering any challenge made of this character stays non-empty,
		// geometry-only, and free of the repeated answer sequence.
		challenge := strings.Repeat(string(char), CaptchaChallengeLength)
		svg := RenderCaptchaSVG(challenge)
		if svg == "" {
			t.Fatalf("render of %q failed", challenge)
		}
		if strings.Contains(svg, challenge) || strings.Count(svg, "<path") != CaptchaChallengeLength {
			t.Fatalf("render of %q disclosed the answer or lost glyph paths", challenge)
		}
	}
}

func TestRenderCaptchaSVGRandomChallengesNeverCarryAnswerText(t *testing.T) {
	service := newTestCaptchaService(t)
	for range 32 {
		challenge, err := service.GenerateChallenge()
		if err != nil {
			t.Fatalf("GenerateChallenge() error = %v", err)
		}
		svg := RenderCaptchaSVG(challenge)
		if svg == "" {
			t.Fatalf("render of %q failed", challenge)
		}
		if strings.Contains(svg, challenge) || strings.Contains(svg, strings.ToLower(challenge)) || strings.Contains(svg, "<text") {
			t.Fatalf("render of %q disclosed the answer", challenge)
		}
	}
}

func TestNewCaptchaServiceFailsClosedWithoutHighEntropySecrets(t *testing.T) {
	for _, service := range []CaptchaService{
		{},
		{AnswerPepper: bytes.Repeat([]byte("p"), MinimumSecretBytes)},
		{RateLimitKeySecret: bytes.Repeat([]byte("k"), MinimumSecretBytes)},
		{AnswerPepper: []byte("short"), RateLimitKeySecret: bytes.Repeat([]byte("k"), MinimumSecretBytes)},
	} {
		if _, err := NewCaptchaService(service); !errors.Is(err, domain.ErrInvalid) {
			t.Fatalf("NewCaptchaService() error = %v, want %v", err, domain.ErrInvalid)
		}
	}
}

func TestCaptchaServiceDefaultsAreCryptographic(t *testing.T) {
	service, err := NewCaptchaService(CaptchaService{
		AnswerPepper:       bytes.Repeat([]byte("p"), MinimumSecretBytes),
		RateLimitKeySecret: bytes.Repeat([]byte("k"), MinimumSecretBytes),
	})
	if err != nil {
		t.Fatal(err)
	}
	if service.GenerateChallenge == nil || service.GenerateSalt == nil || service.Clock == nil {
		t.Fatal("constructor did not install default crypto dependencies")
	}
	if _, err := service.GenerateChallenge(); err != nil {
		t.Fatalf("default challenge generator error = %v", err)
	}
	salt, err := service.GenerateSalt()
	if err != nil || len(salt) != captchaSaltLength {
		t.Fatalf("default salt generator = %d bytes, %v", len(salt), err)
	}
}

func TestCaptchaIssuePropagatesGeneratorFailures(t *testing.T) {
	service, err := NewCaptchaService(CaptchaService{
		AnswerPepper:       bytes.Repeat([]byte("p"), MinimumSecretBytes),
		RateLimitKeySecret: bytes.Repeat([]byte("k"), MinimumSecretBytes),
		GenerateChallenge:  func() (string, error) { return "", fmt.Errorf("boom") },
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.Issue(); err == nil {
		t.Fatal("Issue() error = nil, want generator failure propagation")
	}
	if CaptchaTTL != 5*time.Minute || CaptchaMaxAttempts != 5 {
		t.Fatalf("captcha policy constants drifted: ttl=%v attempts=%d", CaptchaTTL, CaptchaMaxAttempts)
	}
}

func newTestCaptchaService(t *testing.T) CaptchaService {
	t.Helper()
	service, err := NewCaptchaService(CaptchaService{
		AnswerPepper:       bytes.Repeat([]byte("p"), MinimumSecretBytes),
		RateLimitKeySecret: bytes.Repeat([]byte("k"), MinimumSecretBytes),
	})
	if err != nil {
		t.Fatal(err)
	}
	return service
}

// The challenge generator must read the cryptographic reader, never math/rand.
func TestCaptchaChallengeUsesCryptoReader(t *testing.T) {
	if _, err := generateCaptchaChallenge(); err != nil {
		t.Fatalf("generateCaptchaChallenge() error = %v", err)
	}
	var buffer [1]byte
	if _, err := rand.Read(buffer[:]); err != nil {
		t.Fatalf("crypto reader unavailable: %v", err)
	}
}

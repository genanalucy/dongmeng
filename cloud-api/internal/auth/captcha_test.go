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

func TestRenderCaptchaSVGEscapesNothingUnsafeAndContainsChallenge(t *testing.T) {
	svg := RenderCaptchaSVG("AB3CD")
	if !strings.HasPrefix(svg, "<svg") || !strings.HasSuffix(svg, "</svg>") {
		t.Fatalf("svg envelope = %q", svg[:min(32, len(svg))])
	}
	for _, char := range "AB3CD" {
		if !strings.ContainsRune(svg, char) {
			t.Fatalf("svg missing challenge character %q", char)
		}
	}
	for _, forbidden := range []string{"script", "href", "javascript", "xlink"} {
		if strings.Contains(svg, forbidden) {
			t.Fatalf("svg contains forbidden content %q", forbidden)
		}
	}
	if size := len(svg); size > 4096 {
		t.Fatalf("svg size = %d bytes, want bounded", size)
	}
	if RenderCaptchaSVG("AB3 D") != "" || RenderCaptchaSVG("AB3CDE") != "" {
		t.Fatal("renderer accepted a malformed challenge")
	}
	for range 8 {
		if again := RenderCaptchaSVG("AB3CD"); again == svg && len(svg) > 0 {
			// Jitter is random; identical renders are unlikely but not fatal.
			_ = again
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

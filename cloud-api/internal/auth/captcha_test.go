package auth

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"testing"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/wenlng/go-captcha/v2/base/imagedata"
	"github.com/wenlng/go-captcha/v2/slide"
)

// fixedSlideGenerator pins the hidden target coordinate while exercising the
// production image encoding path: it returns library-produced CaptchaData
// built from tiny in-memory images, so hashing, tolerance, and HTTP encoding
// stay deterministic under test.
type fixedSlideGenerator struct {
	targetX int
	calls   int
}

func (g *fixedSlideGenerator) Generate() (slide.CaptchaData, error) {
	g.calls++
	return stubSlideData(g.targetX), nil
}

type stubCaptchaData struct {
	block *slide.Block
}

func (d stubCaptchaData) GetData() *slide.Block { return d.block }
func (d stubCaptchaData) GetMasterImage() imagedata.JPEGImageData {
	return imagedata.NewJPEGImageData(image.NewRGBA(image.Rect(0, 0, CaptchaImageWidth, CaptchaImageHeight)))
}
func (d stubCaptchaData) GetTileImage() imagedata.PNGImageData {
	return imagedata.NewPNGImageData(image.NewRGBA(image.Rect(0, 0, d.block.Width, d.block.Height)))
}

func stubSlideData(targetX int) slide.CaptchaData {
	return stubCaptchaData{block: &slide.Block{X: targetX, Y: 100, Width: 64, Height: 64, DX: 7, DY: 100}}
}

func newTestCaptchaService(t *testing.T) CaptchaService {
	t.Helper()
	// Distinct salts per issue mirror production randomness so drafts never
	// share a hash by construction.
	issue := 0
	service, err := NewCaptchaService(CaptchaService{
		AnswerPepper:       bytes.Repeat([]byte("p"), MinimumSecretBytes),
		RateLimitKeySecret: bytes.Repeat([]byte("k"), MinimumSecretBytes),
		GenerateSlide:      &fixedSlideGenerator{targetX: 137},
		GenerateSalt: func() ([]byte, error) {
			issue++
			salt := bytes.Repeat([]byte("s"), captchaSaltLength)
			salt[0] = byte(issue)
			return salt, nil
		},
		Clock: func() time.Time { return time.Date(2026, 9, 5, 6, 7, 8, 0, time.UTC) },
	})
	if err != nil {
		t.Fatal(err)
	}
	return service
}

// The default generator must be the official open-source library pipeline
// with the companion assets: every issue produces a decodable JPEG master of
// the configured canvas and a decodable PNG tile with a bounded target.
func TestDefaultSlideGeneratorUsesLibraryAssets(t *testing.T) {
	generator, err := NewDefaultSlideGenerator()
	if err != nil {
		t.Fatalf("NewDefaultSlideGenerator() error = %v", err)
	}
	service, err := NewCaptchaService(CaptchaService{
		AnswerPepper:       bytes.Repeat([]byte("p"), MinimumSecretBytes),
		RateLimitKeySecret: bytes.Repeat([]byte("k"), MinimumSecretBytes),
		GenerateSlide:      generator,
		GenerateSalt:       func() ([]byte, error) { return bytes.Repeat([]byte("s"), captchaSaltLength), nil },
		Clock:              func() time.Time { return time.Date(2026, 9, 5, 6, 7, 8, 0, time.UTC) },
	})
	if err != nil {
		t.Fatal(err)
	}
	seenTargets := make(map[int]struct{})
	seenImages := make(map[string]struct{})
	for range 8 {
		draft, err := service.Issue()
		if err != nil {
			t.Fatalf("Issue() error = %v", err)
		}
		master, format, err := image.Decode(bytes.NewReader(draft.MasterImage))
		if err != nil || format != "jpeg" {
			t.Fatalf("master image is not a decodable JPEG: %v (%d bytes)", err, len(draft.MasterImage))
		}
		if master.Bounds().Dx() != CaptchaImageWidth || master.Bounds().Dy() != CaptchaImageHeight {
			t.Fatalf("master bounds = %v, want %dx%d", master.Bounds(), CaptchaImageWidth, CaptchaImageHeight)
		}
		if draft.MasterWidth != CaptchaImageWidth || draft.MasterHeight != CaptchaImageHeight {
			t.Fatalf("draft master size = %dx%d", draft.MasterWidth, draft.MasterHeight)
		}
		tile, format, err := image.Decode(bytes.NewReader(draft.TileImage))
		if err != nil || format != "png" {
			t.Fatalf("tile image is not a decodable PNG: %v (%d bytes)", err, len(draft.TileImage))
		}
		if tile.Bounds().Dx() != draft.TileWidth || tile.Bounds().Dy() != draft.TileHeight {
			t.Fatalf("tile bounds = %v, declared %dx%d", tile.Bounds(), draft.TileWidth, draft.TileHeight)
		}
		if draft.TileWidth <= 0 || draft.TileWidth > draft.MasterWidth || draft.TileHeight <= 0 || draft.TileHeight > draft.MasterHeight {
			t.Fatalf("tile size %dx%d escapes the master canvas", draft.TileWidth, draft.TileHeight)
		}
		if draft.TileStartX < 0 || draft.TileStartX+draft.TileWidth > draft.MasterWidth || draft.TileStartY < 0 || draft.TileStartY+draft.TileHeight > draft.MasterHeight {
			t.Fatalf("tile start %d,%d escapes the master canvas", draft.TileStartX, draft.TileStartY)
		}
		if draft.TargetX < CaptchaTolerance || draft.TargetX > draft.MasterWidth-draft.TileWidth {
			t.Fatalf("target %d escapes the draggable canvas", draft.TargetX)
		}
		if !CaptchaCoordinateMatches(service.AnswerPepper, draft.AnswerSalt, draft.AnswerHash, draft.TargetX) {
			t.Fatal("issued draft does not verify against its own target")
		}
		seenTargets[draft.TargetX] = struct{}{}
		seenImages[string(draft.MasterImage)] = struct{}{}
	}
	// The library randomizes both the photographic background and the
	// target; repeated issues must not collapse to a single challenge.
	if len(seenTargets) < 2 || len(seenImages) < 2 {
		t.Fatalf("default generation is deterministic: %d targets, %d distinct masters", len(seenTargets), len(seenImages))
	}
}

func TestCaptchaIssueProducesLibraryImagesSaltedHashAndExpiry(t *testing.T) {
	service := newTestCaptchaService(t)
	draft, err := service.Issue()
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}
	if draft.TargetX != 137 {
		t.Fatalf("target = %d, want the generator's pinned coordinate", draft.TargetX)
	}
	if len(draft.AnswerSalt) != captchaSaltLength || len(draft.AnswerHash) != 32 {
		t.Fatalf("draft hash/salt lengths = %d/%d", len(draft.AnswerHash), len(draft.AnswerSalt))
	}
	if !draft.ExpiresAt.Equal(time.Date(2026, 9, 5, 6, 7, 8, 0, time.UTC).Add(CaptchaTTL)) {
		t.Fatalf("expiry = %v", draft.ExpiresAt)
	}
	master, format, err := image.Decode(bytes.NewReader(draft.MasterImage))
	if err != nil || format != "jpeg" || master.Bounds().Dx() != CaptchaImageWidth {
		t.Fatalf("master = %v %v", format, err)
	}
	tile, format, err := image.Decode(bytes.NewReader(draft.TileImage))
	if err != nil || format != "png" || tile.Bounds().Dx() != draft.TileWidth {
		t.Fatalf("tile = %v %v", format, err)
	}
	if !CaptchaCoordinateMatches(service.AnswerPepper, draft.AnswerSalt, draft.AnswerHash, 137) {
		t.Fatal("issued draft does not verify against its own target coordinate")
	}
}

func TestCaptchaCoordinateToleranceWindow(t *testing.T) {
	service := newTestCaptchaService(t)
	draft, err := service.Issue()
	if err != nil {
		t.Fatal(err)
	}
	verify := func(submitted int) bool {
		return CaptchaCoordinateMatches(service.AnswerPepper, draft.AnswerSalt, draft.AnswerHash, submitted)
	}
	for offset := -CaptchaTolerance; offset <= CaptchaTolerance; offset++ {
		if !verify(137 + offset) {
			t.Fatalf("submitted %d (offset %d) was rejected inside the tolerance window", 137+offset, offset)
		}
	}
	for _, submitted := range []int{137 - CaptchaTolerance - 1, 137 + CaptchaTolerance + 1, 0, 1, CaptchaImageWidth - 1, CaptchaImageWidth, 13, 290} {
		if verify(submitted) {
			t.Fatalf("submitted %d was accepted outside the tolerance window", submitted)
		}
	}
}

func TestCaptchaAnswerHashBindsSaltAndCoordinate(t *testing.T) {
	service := newTestCaptchaService(t)
	first, err := service.Issue()
	if err != nil {
		t.Fatal(err)
	}
	second, err := service.Issue()
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Equal(first.AnswerSalt, second.AnswerSalt) {
		t.Fatal("salt generator collapsed to one salt")
	}
	if bytes.Equal(first.AnswerHash, second.AnswerHash) {
		t.Fatal("identical targets with distinct salts produced identical hashes")
	}
	// A hash minted for one draft never verifies under another draft's salt.
	if CaptchaCoordinateMatches(service.AnswerPepper, second.AnswerSalt, first.AnswerHash, 137) {
		t.Fatal("mismatched salt was accepted")
	}
	if CaptchaCoordinateMatches(nil, first.AnswerSalt, first.AnswerHash, 137) {
		t.Fatal("missing pepper was accepted")
	}
	if CaptchaCoordinateMatches(service.AnswerPepper, nil, first.AnswerHash, 137) {
		t.Fatal("missing salt was accepted")
	}
	if CaptchaCoordinateMatches(service.AnswerPepper, first.AnswerSalt, nil, 137) {
		t.Fatal("missing hash was accepted")
	}
	if CaptchaCoordinateMatches(service.AnswerPepper, first.AnswerSalt, first.AnswerHash, -1) || CaptchaCoordinateMatches(service.AnswerPepper, first.AnswerSalt, first.AnswerHash, CaptchaImageWidth+1) {
		t.Fatal("coordinate outside the challenge canvas was accepted")
	}
	// The persisted material never carries the cleartext coordinate.
	if bytes.Contains(first.AnswerHash, []byte(fmt.Sprintf("%d", first.TargetX))) {
		t.Fatal("persisted hash leaked the target coordinate")
	}
	if bytes.Contains(first.AnswerSalt, []byte(fmt.Sprintf("%d", first.TargetX))) {
		t.Fatal("persisted salt leaked the target coordinate")
	}
}

func TestCaptchaIssuePropagatesGeneratorFailures(t *testing.T) {
	service, err := NewCaptchaService(CaptchaService{
		AnswerPepper:       bytes.Repeat([]byte("p"), MinimumSecretBytes),
		RateLimitKeySecret: bytes.Repeat([]byte("k"), MinimumSecretBytes),
		GenerateSlide:      failingSlideGenerator{},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.Issue(); err == nil {
		t.Fatal("Issue() error = nil, want generator failure propagation")
	}
	if CaptchaTTL != 5*time.Minute || CaptchaMaxAttempts != 5 || CaptchaTolerance != 6 || CaptchaImageWidth != 300 || CaptchaImageHeight != 220 {
		t.Fatalf("captcha policy constants drifted: ttl=%v attempts=%d tolerance=%d canvas=%dx%d", CaptchaTTL, CaptchaMaxAttempts, CaptchaTolerance, CaptchaImageWidth, CaptchaImageHeight)
	}
}

type failingSlideGenerator struct{}

func (failingSlideGenerator) Generate() (slide.CaptchaData, error) {
	return nil, fmt.Errorf("boom")
}

// A generator that returns block data escaping the fixed canvas must be
// rejected before any material is returned to callers.
func TestCaptchaIssueRejectsBlocksOutsideTheCanvas(t *testing.T) {
	for _, block := range []*slide.Block{
		{X: 1, Y: 100, Width: 64, Height: 64, DX: 7, DY: 100},
		{X: CaptchaImageWidth, Y: 100, Width: 64, Height: 64, DX: 7, DY: 100},
		{X: 137, Y: CaptchaImageHeight, Width: 64, Height: 64, DX: 7, DY: CaptchaImageHeight},
		{X: 137, Y: 200, Width: 64, Height: 64, DX: 7, DY: 201},
		{X: 137, Y: 100, Width: 0, Height: 64, DX: 7, DY: 100},
		{X: 137, Y: 100, Width: 64, Height: 64, DX: CaptchaImageWidth, DY: 100},
	} {
		service, err := NewCaptchaService(CaptchaService{
			AnswerPepper:       bytes.Repeat([]byte("p"), MinimumSecretBytes),
			RateLimitKeySecret: bytes.Repeat([]byte("k"), MinimumSecretBytes),
			GenerateSlide:      fixedBlockGenerator{block: block},
			GenerateSalt:       func() ([]byte, error) { return bytes.Repeat([]byte("s"), captchaSaltLength), nil },
			Clock:              func() time.Time { return time.Unix(0, 0).UTC() },
		})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := service.Issue(); !errors.Is(err, domain.ErrInvalid) {
			t.Fatalf("Issue() accepted an out-of-canvas block %+v: %v", block, err)
		}
	}
}

type fixedBlockGenerator struct {
	block *slide.Block
}

func (g fixedBlockGenerator) Generate() (slide.CaptchaData, error) {
	return stubCaptchaData{block: g.block}, nil
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
	if service.GenerateSlide == nil || service.GenerateSalt == nil || service.Clock == nil {
		t.Fatal("constructor did not install default crypto dependencies")
	}
	if _, err := service.GenerateSlide.Generate(); err != nil {
		t.Fatalf("default slide generator error = %v", err)
	}
	salt, err := service.GenerateSalt()
	if err != nil || len(salt) != captchaSaltLength {
		t.Fatalf("default salt generator = %d bytes, %v", len(salt), err)
	}
	draft, err := service.Issue()
	if err != nil {
		t.Fatal(err)
	}
	if len(draft.AnswerHash) != 32 || len(draft.AnswerSalt) != captchaSaltLength {
		t.Fatalf("draft hash/salt lengths = %d/%d", len(draft.AnswerHash), len(draft.AnswerSalt))
	}
	if bytes.Contains(draft.AnswerHash, []byte(fmt.Sprintf("%d", draft.TargetX))) {
		t.Fatal("persisted hash leaked the target coordinate")
	}
}

package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"fmt"
	"strconv"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/wenlng/go-captcha-assets/resources/imagesv2"
	"github.com/wenlng/go-captcha-assets/resources/tiles"
	"github.com/wenlng/go-captcha/v2/base/option"
	"github.com/wenlng/go-captcha/v2/slide"
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

	captchaSaltLength = 32
	// CaptchaTolerance is the pixel window on either side of the target
	// coordinate in which a submitted drag position still verifies. It
	// mirrors the padding parameter of the library's slide.Validate: a
	// submitted coordinate passes exactly when
	// |submitted - target| <= CaptchaTolerance.
	CaptchaTolerance = 6
	// CaptchaImageWidth and CaptchaImageHeight fix the rendered slide
	// challenge canvas. The client submits coordinates in this pixel space,
	// so validation bounds and generation must share one size.
	CaptchaImageWidth  = 300
	CaptchaImageHeight = 220
)

// CaptchaDraft is the issue-time material for one slide challenge. The
// target coordinate exists only in memory here; persistence receives only
// AnswerHash and AnswerSalt, never the coordinate itself.
type CaptchaDraft struct {
	// MasterImage is the rendered background with the target notch as a
	// self-contained JPEG of MasterWidth x MasterHeight pixels.
	MasterImage []byte
	// TileImage is the draggable puzzle piece as a transparent PNG of
	// TileWidth x TileHeight pixels.
	TileImage []byte
	// MasterWidth/MasterHeight is the pixel space of MasterImage and of the
	// submitted answer coordinate.
	MasterWidth, MasterHeight int
	// TileWidth/TileHeight is the size of TileImage.
	TileWidth, TileHeight int
	// TileStartX/TileStartY is where the client initially renders the tile:
	// the drag moves it horizontally toward the hidden target.
	TileStartX, TileStartY int
	// TargetX is the notch coordinate the client must match. It never
	// reaches persistence in cleartext.
	TargetX    int
	AnswerHash []byte
	AnswerSalt []byte
	ExpiresAt  time.Time
}

// SlideGenerator produces one go-captcha slide challenge per call. It is an
// interface so tests can pin the target coordinate while production uses the
// official library generator.
type SlideGenerator interface {
	Generate() (slide.CaptchaData, error)
}

// CaptchaService dependencies are injected to keep challenge generation,
// hashing, and time testable without persistence or HTTP wiring.
type CaptchaService struct {
	AnswerPepper       []byte
	RateLimitKeySecret []byte
	GenerateSlide      SlideGenerator
	GenerateSalt       func() ([]byte, error)
	Clock              func() time.Time
}

// NewCaptchaService validates the fail-closed secrets and installs the
// official go-captcha slide generator plus cryptographic salt and clock
// defaults.
func NewCaptchaService(service CaptchaService) (CaptchaService, error) {
	if len(service.AnswerPepper) < MinimumSecretBytes || len(service.RateLimitKeySecret) < MinimumSecretBytes {
		return CaptchaService{}, fmt.Errorf("%w: captcha secrets must be at least %d bytes", domain.ErrInvalid, MinimumSecretBytes)
	}
	if service.GenerateSlide == nil {
		generator, err := NewDefaultSlideGenerator()
		if err != nil {
			return CaptchaService{}, err
		}
		service.GenerateSlide = generator
	}
	if service.GenerateSalt == nil {
		service.GenerateSalt = generateCaptchaSalt
	}
	if service.Clock == nil {
		service.Clock = time.Now
	}
	return service, nil
}

// NewDefaultSlideGenerator assembles the official open-source slide captcha
// (github.com/wenlng/go-captcha/v2) with the companion asset pack published
// by the same author: photographed backgrounds and the tile overlay, shadow,
// and mask triple. Make() selects the horizontal slide mode, so the client
// only drags along the x axis and the verifiable answer stays one
// dimensional.
func NewDefaultSlideGenerator() (SlideGenerator, error) {
	backgrounds, err := imagesv2.GetImages()
	if err != nil {
		return nil, fmt.Errorf("load go-captcha background assets: %w", err)
	}
	tileAssets, err := tiles.GetTiles()
	if err != nil {
		return nil, fmt.Errorf("load go-captcha tile assets: %w", err)
	}
	graphs := make([]*slide.GraphImage, 0, len(tileAssets))
	for _, tileAsset := range tileAssets {
		graphs = append(graphs, &slide.GraphImage{
			OverlayImage: tileAsset.OverlayImage,
			ShadowImage:  tileAsset.ShadowImage,
			MaskImage:    tileAsset.MaskImage,
		})
	}
	builder := slide.NewBuilder(slide.WithImageSize(option.Size{Width: CaptchaImageWidth, Height: CaptchaImageHeight}))
	builder.SetResources(slide.WithBackgrounds(backgrounds), slide.WithGraphImages(graphs))
	return builder.Make(), nil
}

// Issue generates one slide challenge, encodes its images, and derives the
// salted hash of the target coordinate plus expiry. Image assets are
// produced entirely by the go-captcha library; the answer is persisted only
// through captchaAnswerHash.
func (s CaptchaService) Issue() (CaptchaDraft, error) {
	data, err := s.GenerateSlide.Generate()
	if err != nil {
		return CaptchaDraft{}, fmt.Errorf("generate slide captcha: %w", err)
	}
	block := data.GetData()
	if block == nil {
		return CaptchaDraft{}, fmt.Errorf("%w: slide captcha produced no block data", domain.ErrInvalid)
	}
	if block.X < CaptchaTolerance || block.X > CaptchaImageWidth-CaptchaTolerance ||
		block.Width <= 0 || block.Height <= 0 ||
		block.Y < 0 || block.Y+block.Height > CaptchaImageHeight ||
		block.DX < 0 || block.DX+block.Width > CaptchaImageWidth || block.DY != block.Y {
		return CaptchaDraft{}, fmt.Errorf("%w: slide captcha block escapes the challenge canvas", domain.ErrInvalid)
	}
	master, err := data.GetMasterImage().ToBytes()
	if err != nil {
		return CaptchaDraft{}, fmt.Errorf("encode slide master image: %w", err)
	}
	tile, err := data.GetTileImage().ToBytes()
	if err != nil {
		return CaptchaDraft{}, fmt.Errorf("encode slide tile image: %w", err)
	}
	salt, err := s.GenerateSalt()
	if err != nil {
		return CaptchaDraft{}, fmt.Errorf("generate captcha salt: %w", err)
	}
	if len(salt) < 16 || len(salt) > 64 {
		return CaptchaDraft{}, fmt.Errorf("%w: captcha salt length is out of range", domain.ErrInvalid)
	}
	answerHash, err := captchaAnswerHash(s.AnswerPepper, salt, canonicalSlideAnswer(block.X))
	if err != nil {
		return CaptchaDraft{}, err
	}
	now := s.Clock().UTC()
	if now.IsZero() {
		return CaptchaDraft{}, fmt.Errorf("%w: captcha clock is required", domain.ErrInvalid)
	}
	return CaptchaDraft{
		MasterImage:  master,
		MasterWidth:  CaptchaImageWidth,
		MasterHeight: CaptchaImageHeight,
		TileImage:    tile,
		TileWidth:    block.Width,
		TileHeight:   block.Height,
		TileStartX:   block.DX,
		TileStartY:   block.DY,
		TargetX:      block.X,
		AnswerHash:   answerHash,
		AnswerSalt:   salt,
		ExpiresAt:    now.Add(CaptchaTTL),
	}, nil
}

// ValidCaptchaCoordinate reports whether a submitted drag coordinate lies in
// the challenge's pixel space. It bounds client input before any hashing or
// persistence work runs.
func ValidCaptchaCoordinate(x int) bool {
	return x >= 0 && x <= CaptchaImageWidth
}

// CaptchaCoordinateMatches reports whether the submitted final tile position
// verifies against the persisted salted hash of the hidden target. Because
// persistence stores only the hash, verification expands the submitted
// coordinate to every position the tolerance admits and compares each
// candidate's hash in constant time; the match rule is exactly the library's
// slide.Validate padding comparison |submitted - target| <= tolerance on the
// drag axis.
func CaptchaCoordinateMatches(pepper, salt, expectedHash []byte, submittedX int) bool {
	if len(pepper) == 0 || len(salt) == 0 || len(expectedHash) != sha256.Size || !ValidCaptchaCoordinate(submittedX) {
		return false
	}
	for candidate := submittedX - CaptchaTolerance; candidate <= submittedX+CaptchaTolerance; candidate++ {
		if !ValidCaptchaCoordinate(candidate) {
			continue
		}
		actualHash, err := captchaAnswerHash(pepper, salt, canonicalSlideAnswer(candidate))
		if err == nil && subtle.ConstantTimeCompare(expectedHash, actualHash) == 1 {
			return true
		}
	}
	return false
}

// canonicalSlideAnswer domain-separates the hashed answer so slide targets
// can never collide with other HMAC uses of the shared captcha secret.
func canonicalSlideAnswer(x int) string {
	return "slide:x:" + strconv.Itoa(x)
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

func generateCaptchaSalt() ([]byte, error) {
	salt := make([]byte, captchaSaltLength)
	if _, err := rand.Read(salt); err != nil {
		return nil, fmt.Errorf("generate captcha salt: %w", err)
	}
	return salt, nil
}

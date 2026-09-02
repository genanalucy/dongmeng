package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"fmt"
	"math/big"
	"net/netip"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
)

const (
	RegistrationVerificationCodeTTL      = 10 * time.Minute
	RegistrationVerificationResendDelay  = time.Minute
	RegistrationVerificationEmailPerHour = 5
	RegistrationVerificationIPPerHour    = 20
	RegistrationVerificationMaxAttempts  = 5
	registrationVerificationCodeLength   = 6
	registrationVerificationSaltLength   = 32
)

type RegistrationVerificationRequest struct {
	Username string
	Email    string
	Password string
	ClientIP netip.Addr
}

type RegistrationVerificationConfirmation struct {
	Email string
	Code  string
}

type RegistrationVerificationResult struct {
	RetryAfterSeconds int
}

type RegistrationCodeSender interface {
	SendRegistrationCode(context.Context, string, string, time.Time) error
}

// EmailRegistrationService holds dependencies for the email-verification
// registration flow. Its Store implementation is introduced with the
// persistence layer so no verification material is retained in memory.
type EmailRegistrationService struct {
	Store              domain.Store
	HashPasswordValue  func(string) (string, error)
	GenerateCode       func() (string, error)
	GenerateSalt       func() ([]byte, error)
	RateLimitKeySecret []byte
	Sender             RegistrationCodeSender
	Clock              func() time.Time
}

func ParseRegistrationVerificationCode(value string) (string, error) {
	if len(value) != registrationVerificationCodeLength {
		return "", fmt.Errorf("%w: registration verification code must be six ASCII digits", domain.ErrInvalid)
	}
	for i := 0; i < len(value); i++ {
		if value[i] < '0' || value[i] > '9' {
			return "", fmt.Errorf("%w: registration verification code must be six ASCII digits", domain.ErrInvalid)
		}
	}
	return value, nil
}

func generateSixDigitCode() (string, error) {
	var digits [registrationVerificationCodeLength]byte
	for i := range digits {
		value, err := rand.Int(rand.Reader, bigTen)
		if err != nil {
			return "", fmt.Errorf("generate registration verification code: %w", err)
		}
		digits[i] = byte('0' + value.Int64())
	}
	return string(digits[:]), nil
}

var bigTen = big.NewInt(10)

func generateRegistrationVerificationSalt() ([]byte, error) {
	salt := make([]byte, registrationVerificationSaltLength)
	if _, err := rand.Read(salt); err != nil {
		return nil, fmt.Errorf("generate registration verification salt: %w", err)
	}
	return salt, nil
}

func hashCode(salt []byte, code string) []byte {
	hash := sha256.New()
	_, _ = hash.Write(salt)
	_, _ = hash.Write([]byte(code))
	return hash.Sum(nil)
}

func constantTimeCodeMatch(salt, expectedHash []byte, code string) bool {
	actualHash := hashCode(salt, code)
	return subtle.ConstantTimeCompare(expectedHash, actualHash) == 1
}

func registrationVerificationExpired(expiresAt, now time.Time) bool {
	return !expiresAt.After(now)
}

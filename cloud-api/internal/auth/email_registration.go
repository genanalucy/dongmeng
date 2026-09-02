package auth

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"fmt"
	"math/big"
	"net/netip"
	"time"

	"github.com/dngmeng/cloud-api/internal/domain"
	"github.com/google/uuid"
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

var ErrRegistrationVerificationFailed = errors.New("registration verification failed")

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

// RegistrationVerificationRecord is the minimum record shape required by the
// confirmation primitive. Persistence is supplied by a later task.
type RegistrationVerificationRecord struct {
	Salt      []byte
	CodeHash  []byte
	ExpiresAt time.Time
}

type RegistrationVerificationLookup func(context.Context, string) (RegistrationVerificationRecord, error)

type RegistrationVerificationDraft struct {
	Username     string
	Email        string
	PasswordHash string
	Salt         []byte
	CodeHash     []byte
	ExpiresAt    time.Time
}

type RegistrationVerificationWriter func(context.Context, RegistrationVerificationDraft) (uuid.UUID, error)

// EmailRegistrationService dependencies are injected to keep crypto, time,
// delivery, and lookup behavior testable without SMTP or persistence wiring.
type EmailRegistrationService struct {
	HashPasswordValue      func(string) (string, error)
	GenerateCode           func() (string, error)
	GenerateSalt           func() ([]byte, error)
	CodePepper             []byte
	RateLimitKeySecret     []byte
	Sender                 RegistrationCodeSender
	Clock                  func() time.Time
	WriteVerification      RegistrationVerificationWriter
	InvalidateVerification func(context.Context, uuid.UUID, string, time.Time) error
	LookupVerification     RegistrationVerificationLookup
}

func NewEmailRegistrationService(service EmailRegistrationService) (EmailRegistrationService, error) {
	if len(service.CodePepper) == 0 {
		return EmailRegistrationService{}, fmt.Errorf("%w: registration verification code pepper is required", domain.ErrInvalid)
	}
	if service.HashPasswordValue == nil || service.Sender == nil || service.WriteVerification == nil {
		return EmailRegistrationService{}, fmt.Errorf("%w: registration verification service dependencies are required", domain.ErrInvalid)
	}
	if service.GenerateCode == nil {
		service.GenerateCode = generateSixDigitCode
	}
	if service.GenerateSalt == nil {
		service.GenerateSalt = generateRegistrationVerificationSalt
	}
	if service.Clock == nil {
		service.Clock = time.Now
	}
	return service, nil
}

func (s EmailRegistrationService) RequestVerification(ctx context.Context, request RegistrationVerificationRequest) (RegistrationVerificationResult, error) {
	input, err := domain.ParseRegistrationVerificationInput(request.Username, request.Email, request.Password)
	if err != nil {
		return RegistrationVerificationResult{}, err
	}
	if !request.ClientIP.IsValid() {
		return RegistrationVerificationResult{}, fmt.Errorf("%w: client IP is required", domain.ErrInvalid)
	}
	passwordHash, err := s.HashPasswordValue(input.Password.String())
	if err != nil || passwordHash == "" {
		if err != nil {
			return RegistrationVerificationResult{}, fmt.Errorf("hash registration password: %w", err)
		}
		return RegistrationVerificationResult{}, errors.New("password hasher returned an empty value")
	}
	code, err := s.GenerateCode()
	if err != nil {
		return RegistrationVerificationResult{}, fmt.Errorf("generate registration verification code: %w", err)
	}
	if _, err := ParseRegistrationVerificationCode(code); err != nil {
		return RegistrationVerificationResult{}, err
	}
	salt, err := s.GenerateSalt()
	if err != nil {
		return RegistrationVerificationResult{}, fmt.Errorf("generate registration verification salt: %w", err)
	}
	codeHash, err := hashCode(s.CodePepper, salt, code)
	if err != nil {
		return RegistrationVerificationResult{}, err
	}
	now := s.Clock().UTC()
	expiresAt := now.Add(RegistrationVerificationCodeTTL)
	reservationID, err := s.WriteVerification(ctx, RegistrationVerificationDraft{Username: input.Username.String(), Email: input.Email.String(), PasswordHash: passwordHash, Salt: salt, CodeHash: codeHash, ExpiresAt: expiresAt})
	if err != nil {
		return RegistrationVerificationResult{}, fmt.Errorf("persist registration verification: %w", err)
	}
	if reservationID == uuid.Nil {
		return RegistrationVerificationResult{}, errors.New("persist registration verification returned an empty ID")
	}
	if err := s.Sender.SendRegistrationCode(ctx, input.Email.String(), code, expiresAt); err != nil {
		if s.InvalidateVerification != nil {
			if invalidateErr := s.InvalidateVerification(ctx, reservationID, input.Email.String(), now); invalidateErr != nil {
				return RegistrationVerificationResult{}, fmt.Errorf("send registration verification code: %w; invalidate registration verification: %v", err, invalidateErr)
			}
		}
		return RegistrationVerificationResult{}, fmt.Errorf("send registration verification code: %w", err)
	}
	return RegistrationVerificationResult{RetryAfterSeconds: int(RegistrationVerificationResendDelay.Seconds())}, nil
}

func (s EmailRegistrationService) ConfirmVerification(ctx context.Context, confirmation RegistrationVerificationConfirmation) error {
	email, err := domain.ParseEmail(confirmation.Email)
	if err != nil {
		return ErrRegistrationVerificationFailed
	}
	code, err := ParseRegistrationVerificationCode(confirmation.Code)
	if err != nil {
		return ErrRegistrationVerificationFailed
	}
	if s.LookupVerification == nil {
		return ErrRegistrationVerificationFailed
	}
	record, err := s.LookupVerification(ctx, email.String())
	if err != nil || registrationVerificationExpired(record.ExpiresAt, s.Clock().UTC()) || !constantTimeCodeMatch(s.CodePepper, record.Salt, record.CodeHash, code) {
		return ErrRegistrationVerificationFailed
	}
	return nil
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

func hashCode(pepper, salt []byte, code string) ([]byte, error) {
	if len(pepper) == 0 {
		return nil, fmt.Errorf("%w: registration verification code pepper is required", domain.ErrInvalid)
	}
	mac := hmac.New(sha256.New, pepper)
	_, _ = mac.Write(salt)
	_, _ = mac.Write([]byte(code))
	return mac.Sum(nil), nil
}

func constantTimeCodeMatch(pepper, salt, expectedHash []byte, code string) bool {
	actualHash, err := hashCode(pepper, salt, code)
	return err == nil && subtle.ConstantTimeCompare(expectedHash, actualHash) == 1
}

func registrationVerificationExpired(expiresAt, now time.Time) bool {
	return !expiresAt.After(now)
}

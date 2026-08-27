package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/dngmeng/cloud-api/internal/domain"
	"golang.org/x/crypto/argon2"
)

const (
	argonMemory      uint32 = 64 * 1024
	argonIterations  uint32 = 3
	argonParallelism uint8  = 2
	argonSaltLength         = 16
	argonKeyLength   uint32 = 32
	maxArgonMemory   uint32 = 128 * 1024
	maxArgonTime     uint32 = 6
	maxArgonThreads  uint8  = 4
)

func HashPassword(password string) (string, error) {
	validated, err := domain.ParsePassword(password)
	if err != nil {
		return "", err
	}

	salt := make([]byte, argonSaltLength)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("generate password salt: %w", err)
	}
	hash := argon2.IDKey([]byte(validated), salt, argonIterations, argonMemory, argonParallelism, argonKeyLength)
	return fmt.Sprintf(
		"$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s",
		argonMemory,
		argonIterations,
		argonParallelism,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(hash),
	), nil
}

func VerifyPassword(encoded, password string) (bool, error) {
	if !utf8.ValidString(password) || len(password) > domain.MaxPasswordBytes {
		return false, fmt.Errorf("%w: password is too long or is not valid UTF-8", domain.ErrInvalid)
	}

	memory, iterations, parallelism, salt, expected, err := decodePasswordHash(encoded)
	if err != nil {
		return false, err
	}
	actual := argon2.IDKey([]byte(password), salt, iterations, memory, parallelism, uint32(len(expected)))
	return subtle.ConstantTimeCompare(expected, actual) == 1, nil
}

func decodePasswordHash(encoded string) (uint32, uint32, uint8, []byte, []byte, error) {
	if len(encoded) > 512 {
		return 0, 0, 0, nil, nil, errors.New("invalid password hash encoding")
	}
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[0] != "" || parts[1] != "argon2id" || parts[2] != "v=19" {
		return 0, 0, 0, nil, nil, errors.New("invalid password hash encoding")
	}

	memory, iterations, parallelism, err := decodeArgonParameters(parts[3])
	if err != nil {
		return 0, 0, 0, nil, nil, err
	}
	if memory < 8*1024 || memory > maxArgonMemory || iterations == 0 || iterations > maxArgonTime || parallelism == 0 || parallelism > maxArgonThreads {
		return 0, 0, 0, nil, nil, errors.New("unsafe password hash parameters")
	}

	salt, err := base64.RawStdEncoding.Strict().DecodeString(parts[4])
	if err != nil || len(salt) < 8 || len(salt) > 64 {
		return 0, 0, 0, nil, nil, errors.New("invalid password hash salt")
	}
	expected, err := base64.RawStdEncoding.Strict().DecodeString(parts[5])
	if err != nil || len(expected) < 16 || len(expected) > 64 {
		return 0, 0, 0, nil, nil, errors.New("invalid password hash value")
	}
	return memory, iterations, parallelism, salt, expected, nil
}

func decodeArgonParameters(encoded string) (uint32, uint32, uint8, error) {
	parts := strings.Split(encoded, ",")
	if len(parts) != 3 || !strings.HasPrefix(parts[0], "m=") || !strings.HasPrefix(parts[1], "t=") || !strings.HasPrefix(parts[2], "p=") {
		return 0, 0, 0, errors.New("invalid password hash parameters")
	}
	memory, err := strconv.ParseUint(strings.TrimPrefix(parts[0], "m="), 10, 32)
	if err != nil {
		return 0, 0, 0, errors.New("invalid password hash parameters")
	}
	iterations, err := strconv.ParseUint(strings.TrimPrefix(parts[1], "t="), 10, 32)
	if err != nil {
		return 0, 0, 0, errors.New("invalid password hash parameters")
	}
	parallelism, err := strconv.ParseUint(strings.TrimPrefix(parts[2], "p="), 10, 8)
	if err != nil {
		return 0, 0, 0, errors.New("invalid password hash parameters")
	}
	return uint32(memory), uint32(iterations), uint8(parallelism), nil
}

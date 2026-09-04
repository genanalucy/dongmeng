// Package historycrypto derives per-user, per-version data keys from the
// configured translation-history root key and seals completed text turns with
// AES-256-GCM. Plaintext, ciphertext, and key material are never logged and
// never appear in error messages.
package historycrypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"io"

	"github.com/google/uuid"
	"golang.org/x/crypto/hkdf"
)

const (
	// MinRootKeyBytes is the minimum decoded HISTORY_ROOT_KEY length.
	MinRootKeyBytes = 32
	// nonceSizeBytes is the standard GCM nonce length persisted with each turn.
	nonceSizeBytes = 12
	// keySizeBytes selects AES-256 for derived data keys.
	keySizeBytes = 32
	// gcmTagSizeBytes is the authentication tag appended to every ciphertext.
	gcmTagSizeBytes = 16
	// MaxPlaintextBytes keeps sealed output within the persisted ciphertext
	// cap enforced by the 000007 migration.
	MaxPlaintextBytes = 262144 - gcmTagSizeBytes

	// hkdfSalt is a fixed domain separator; the root key is the HKDF input
	// keying material, so randomness comes from the root key itself.
	hkdfSalt = "dngmeng.cloud-api.history.hkdf.v1"
	// infoPrefix domain-separates turn data keys from any other HKDF use of
	// the same root key.
	infoPrefix = "dngmeng.cloud-api.history.turn.v1"
	// aadPrefix domain-separates turn AAD and binds it to the record shape.
	aadPrefix = "dngmeng.cloud-api.history.aad.v1"
	// minDistinctRootKeyBytes rejects degenerate repeated-byte root keys.
	minDistinctRootKeyBytes = 16
)

// ErrCrypto reports a construction, sealing, or authentication failure
// without revealing key material, ciphertext, or plaintext.
var ErrCrypto = errors.New("history crypto failure")

// Cipher seals and opens completed translation-history turns under data keys
// derived with HKDF-SHA256 from the root key for one user and key version.
// A Cipher is safe for concurrent use.
type Cipher struct {
	rootKey []byte
	version int
}

// ValidateRootKey enforces the root-key policy shared by configuration
// validation and Cipher construction. It never inspects or returns contents.
func ValidateRootKey(rootKey []byte) error {
	if len(rootKey) < MinRootKeyBytes {
		return fmt.Errorf("%w: root key must be at least %d bytes", ErrCrypto, MinRootKeyBytes)
	}
	distinct := make(map[byte]struct{}, 256)
	for _, value := range rootKey {
		distinct[value] = struct{}{}
	}
	if len(distinct) < minDistinctRootKeyBytes {
		return fmt.Errorf("%w: root key entropy is too low", ErrCrypto)
	}
	return nil
}

// NewCipher validates the root key and key version and returns a sealing
// cipher that writes turns under the given version.
func NewCipher(rootKey []byte, version int) (*Cipher, error) {
	if version < 1 {
		return nil, fmt.Errorf("%w: key version must be positive", ErrCrypto)
	}
	if err := ValidateRootKey(rootKey); err != nil {
		return nil, err
	}
	sealed := &Cipher{rootKey: make([]byte, len(rootKey)), version: version}
	copy(sealed.rootKey, rootKey)
	return sealed, nil
}

// KeyVersion reports the version new turns are sealed under.
func (c *Cipher) KeyVersion() int { return c.version }

// SealTurn encrypts one completed turn with a fresh random nonce and AAD bound
// to the user, session, turn, and key version. The same binding is required to
// open the ciphertext.
func (c *Cipher) SealTurn(userID, sessionID, turnID uuid.UUID, plaintext []byte) (nonce, ciphertext []byte, err error) {
	if len(plaintext) == 0 {
		return nil, nil, fmt.Errorf("%w: turn plaintext must not be empty", ErrCrypto)
	}
	if len(plaintext) > MaxPlaintextBytes {
		return nil, nil, fmt.Errorf("%w: turn plaintext exceeds %d bytes", ErrCrypto, MaxPlaintextBytes)
	}
	key, err := c.dataKey(userID, c.version)
	if err != nil {
		return nil, nil, err
	}
	aead, err := newGCM(key)
	if err != nil {
		return nil, nil, err
	}
	nonce = make([]byte, nonceSizeBytes)
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, nil, fmt.Errorf("%w: nonce generation failed", ErrCrypto)
	}
	return nonce, aead.Seal(nil, nonce, plaintext, turnAAD(userID, sessionID, turnID, c.version)), nil
}

// OpenTurn authenticates and decrypts a turn sealed under keyVersion, which is
// the version persisted with the record and may differ from the cipher's
// current version so older turns stay readable after rotation.
func (c *Cipher) OpenTurn(userID, sessionID, turnID uuid.UUID, keyVersion int, nonce, ciphertext []byte) ([]byte, error) {
	if keyVersion < 1 {
		return nil, fmt.Errorf("%w: key version must be positive", ErrCrypto)
	}
	if len(nonce) != nonceSizeBytes {
		return nil, fmt.Errorf("%w: turn nonce must be %d bytes", ErrCrypto, nonceSizeBytes)
	}
	if len(ciphertext) < gcmTagSizeBytes || len(ciphertext) > MaxPlaintextBytes+gcmTagSizeBytes {
		return nil, fmt.Errorf("%w: turn ciphertext length is out of range", ErrCrypto)
	}
	key, err := c.dataKey(userID, keyVersion)
	if err != nil {
		return nil, err
	}
	aead, err := newGCM(key)
	if err != nil {
		return nil, err
	}
	plaintext, err := aead.Open(nil, nonce, ciphertext, turnAAD(userID, sessionID, turnID, keyVersion))
	if err != nil {
		return nil, fmt.Errorf("%w: turn authentication failed", ErrCrypto)
	}
	return plaintext, nil
}

// dataKey derives the AES-256 data key for one user and key version.
func (c *Cipher) dataKey(userID uuid.UUID, version int) ([]byte, error) {
	reader := newHKDF(c.rootKey, info(userID, version))
	key := make([]byte, keySizeBytes)
	if _, err := io.ReadFull(reader, key); err != nil {
		return nil, fmt.Errorf("%w: key derivation failed", ErrCrypto)
	}
	return key, nil
}

func newHKDF(secret, info []byte) io.Reader {
	return hkdf.New(sha256.New, secret, []byte(hkdfSalt), info)
}

func newGCM(key []byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("%w: cipher initialization failed", ErrCrypto)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("%w: GCM initialization failed", ErrCrypto)
	}
	return aead, nil
}

// info binds each derived key to its label, user, and key version.
func info(userID uuid.UUID, version int) []byte {
	buffer := make([]byte, 0, len(infoPrefix)+16+4)
	buffer = append(buffer, infoPrefix...)
	buffer = append(buffer, userID[:]...)
	return binary.BigEndian.AppendUint32(buffer, uint32(version))
}

// turnAAD binds every sealed turn to its owner, session, turn ID, and key
// version so ciphertext cannot be relocated between records or versions.
func turnAAD(userID, sessionID, turnID uuid.UUID, version int) []byte {
	buffer := make([]byte, 0, len(aadPrefix)+16*3+4)
	buffer = append(buffer, aadPrefix...)
	buffer = append(buffer, userID[:]...)
	buffer = append(buffer, sessionID[:]...)
	buffer = append(buffer, turnID[:]...)
	return binary.BigEndian.AppendUint32(buffer, uint32(version))
}

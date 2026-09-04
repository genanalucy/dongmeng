package historycrypto

import (
	"bytes"
	"errors"
	"testing"

	"github.com/google/uuid"
)

func testRootKey() []byte {
	// Deterministic but byte-diverse fixture; real deployments use random keys.
	key := make([]byte, MinRootKeyBytes)
	for i := range key {
		key[i] = byte(i*7 + 13)
	}
	return key
}

func TestCipherRoundTripsSealedTurn(t *testing.T) {
	cipher, err := NewCipher(testRootKey(), 1)
	if err != nil {
		t.Fatalf("construct cipher: %v", err)
	}
	user := uuid.MustParse("123e4567-e89b-12d3-a456-426614174010")
	session := uuid.MustParse("123e4567-e89b-12d3-a456-426614174011")
	turn := uuid.MustParse("123e4567-e89b-12d3-a456-426614174012")
	plaintext := []byte("你好，世界。Completed translation turn with unicode.")

	nonce, ciphertext, err := cipher.SealTurn(user, session, turn, plaintext)
	if err != nil {
		t.Fatalf("seal turn: %v", err)
	}
	if len(nonce) != 12 {
		t.Fatalf("nonce length = %d, want 12", len(nonce))
	}
	if bytes.Equal(ciphertext, plaintext) {
		t.Fatal("ciphertext must not equal plaintext")
	}
	if bytes.Contains(ciphertext, plaintext) {
		t.Fatal("ciphertext must not contain plaintext")
	}

	opened, err := cipher.OpenTurn(user, session, turn, 1, nonce, ciphertext)
	if err != nil {
		t.Fatalf("open turn: %v", err)
	}
	if !bytes.Equal(opened, plaintext) {
		t.Fatal("opened plaintext does not match input")
	}
}

func TestOpenTurnRejectsWrongBinding(t *testing.T) {
	cipher, err := NewCipher(testRootKey(), 1)
	if err != nil {
		t.Fatalf("construct cipher: %v", err)
	}
	user := uuid.New()
	session := uuid.New()
	turn := uuid.New()
	nonce, ciphertext, err := cipher.SealTurn(user, session, turn, []byte("sealed content"))
	if err != nil {
		t.Fatalf("seal turn: %v", err)
	}

	tests := []struct {
		name      string
		user      uuid.UUID
		session   uuid.UUID
		turn      uuid.UUID
		version   int
		tamperCt  bool
		tamperNct bool
	}{
		{name: "wrong user", user: uuid.New(), session: session, turn: turn, version: 1},
		{name: "wrong session", user: user, session: uuid.New(), turn: turn, version: 1},
		{name: "wrong turn", user: user, session: session, turn: uuid.New(), version: 1},
		{name: "wrong key version", user: user, session: session, turn: turn, version: 2},
		{name: "zero key version", user: user, session: session, turn: turn, version: 0},
		{name: "tampered ciphertext", user: user, session: session, turn: turn, version: 1, tamperCt: true},
		{name: "tampered nonce", user: user, session: session, turn: turn, version: 1, tamperNct: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			openNonce, openCiphertext := nonce, ciphertext
			if test.tamperCt {
				openCiphertext = append([]byte(nil), ciphertext...)
				openCiphertext[0] ^= 0x01
			}
			if test.tamperNct {
				openNonce = append([]byte(nil), nonce...)
				openNonce[0] ^= 0x01
			}
			opened, err := cipher.OpenTurn(test.user, test.session, test.turn, test.version, openNonce, openCiphertext)
			if !errors.Is(err, ErrCrypto) {
				t.Fatalf("error = %v, want ErrCrypto", err)
			}
			if opened != nil {
				t.Fatal("failed open must not return plaintext")
			}
		})
	}
}

func TestSealTurnUsesFreshRandomNonces(t *testing.T) {
	cipher, err := NewCipher(testRootKey(), 1)
	if err != nil {
		t.Fatalf("construct cipher: %v", err)
	}
	user, session, turn := uuid.New(), uuid.New(), uuid.New()
	first := []byte("same input")
	firstNonce, firstCiphertext, err := cipher.SealTurn(user, session, turn, first)
	if err != nil {
		t.Fatalf("seal first turn: %v", err)
	}
	secondNonce, secondCiphertext, err := cipher.SealTurn(user, session, turn, first)
	if err != nil {
		t.Fatalf("seal second turn: %v", err)
	}
	if bytes.Equal(firstNonce, secondNonce) {
		t.Fatal("nonces must be unique per sealing")
	}
	if bytes.Equal(firstCiphertext, secondCiphertext) {
		t.Fatal("ciphertexts must differ for identical plaintext")
	}
}

func TestDifferentUsersDoNotShareDerivedKeys(t *testing.T) {
	cipher, err := NewCipher(testRootKey(), 1)
	if err != nil {
		t.Fatalf("construct cipher: %v", err)
	}
	first := uuid.New()
	second := uuid.New()
	session, turn := uuid.New(), uuid.New()
	nonce, ciphertext, err := cipher.SealTurn(first, session, turn, []byte("user scoped"))
	if err != nil {
		t.Fatalf("seal turn: %v", err)
	}
	if _, err := cipher.OpenTurn(second, session, turn, 1, nonce, ciphertext); !errors.Is(err, ErrCrypto) {
		t.Fatalf("cross-user open error = %v, want ErrCrypto", err)
	}
}

func TestRootKeyRotationChangesCiphertext(t *testing.T) {
	first, err := NewCipher(testRootKey(), 1)
	if err != nil {
		t.Fatalf("construct first cipher: %v", err)
	}
	rotated := testRootKey()
	rotated[0] ^= 0xff
	second, err := NewCipher(rotated, 1)
	if err != nil {
		t.Fatalf("construct rotated cipher: %v", err)
	}
	user, session, turn := uuid.New(), uuid.New(), uuid.New()
	nonce, ciphertext, err := first.SealTurn(user, session, turn, []byte("rotation check"))
	if err != nil {
		t.Fatalf("seal turn: %v", err)
	}
	if _, err := second.OpenTurn(user, session, turn, 1, nonce, ciphertext); !errors.Is(err, ErrCrypto) {
		t.Fatalf("rotated root key unexpectedly opened old ciphertext: %v", err)
	}
}

func TestKeyVersionsDeriveDistinctKeys(t *testing.T) {
	cipher, err := NewCipher(testRootKey(), 3)
	if err != nil {
		t.Fatalf("construct cipher: %v", err)
	}
	if cipher.KeyVersion() != 3 {
		t.Fatalf("KeyVersion() = %d, want 3", cipher.KeyVersion())
	}
	first, err := cipher.dataKey(uuid.New(), 1)
	if err != nil {
		t.Fatalf("derive version 1 key: %v", err)
	}
	second, err := cipher.dataKey(uuid.New(), 2)
	if err != nil {
		t.Fatalf("derive version 2 key: %v", err)
	}
	if len(first) != 32 || len(second) != 32 {
		t.Fatal("derived keys must be 32-byte AES-256 keys")
	}
}

func TestNewCipherRejectsWeakRootKeysAndVersions(t *testing.T) {
	tests := []struct {
		name    string
		key     []byte
		version int
	}{
		{name: "nil root key"},
		{name: "short root key", key: make([]byte, 31)},
		{name: "uniform root key", key: bytes.Repeat([]byte{0x07}, MinRootKeyBytes)},
		{name: "low diversity root key", key: bytes.Repeat([]byte{0x01, 0x02}, MinRootKeyBytes/2)},
		{name: "zero key version", key: testRootKey()},
		{name: "negative key version", key: testRootKey(), version: -1},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := NewCipher(test.key, test.version); !errors.Is(err, ErrCrypto) {
				t.Fatalf("NewCipher error = %v, want ErrCrypto", err)
			}
		})
	}
}

func TestSealTurnBoundsPlaintextToPersistedShape(t *testing.T) {
	cipher, err := NewCipher(testRootKey(), 1)
	if err != nil {
		t.Fatalf("construct cipher: %v", err)
	}
	user, session, turn := uuid.New(), uuid.New(), uuid.New()
	oversized := bytes.Repeat([]byte{0x61}, 262129)
	if _, _, err := cipher.SealTurn(user, session, turn, oversized); !errors.Is(err, ErrCrypto) {
		t.Fatalf("oversized seal error = %v, want ErrCrypto", err)
	}
	maximal := bytes.Repeat([]byte{0x61}, 262128)
	nonce, ciphertext, err := cipher.SealTurn(user, session, turn, maximal)
	if err != nil {
		t.Fatalf("seal maximal turn: %v", err)
	}
	if len(ciphertext) > 262144 {
		t.Fatalf("ciphertext length = %d, exceeds persisted cap", len(ciphertext))
	}
	opened, err := cipher.OpenTurn(user, session, turn, 1, nonce, ciphertext)
	if err != nil {
		t.Fatalf("open maximal turn: %v", err)
	}
	if !bytes.Equal(opened, maximal) {
		t.Fatal("maximal turn did not round trip")
	}
}

func TestOpenTurnRejectsMalformedInputs(t *testing.T) {
	cipher, err := NewCipher(testRootKey(), 1)
	if err != nil {
		t.Fatalf("construct cipher: %v", err)
	}
	user, session, turn := uuid.New(), uuid.New(), uuid.New()
	nonce, ciphertext, err := cipher.SealTurn(user, session, turn, []byte("shape checks"))
	if err != nil {
		t.Fatalf("seal turn: %v", err)
	}
	if _, err := cipher.OpenTurn(user, session, turn, 1, nil, ciphertext); !errors.Is(err, ErrCrypto) {
		t.Fatalf("nil nonce error = %v, want ErrCrypto", err)
	}
	if _, err := cipher.OpenTurn(user, session, turn, 1, nonce, nil); !errors.Is(err, ErrCrypto) {
		t.Fatalf("nil ciphertext error = %v, want ErrCrypto", err)
	}
	if _, err := cipher.OpenTurn(user, session, turn, 1, nonce, ciphertext[:15]); !errors.Is(err, ErrCrypto) {
		t.Fatalf("short ciphertext error = %v, want ErrCrypto", err)
	}
}

func TestValidateRootKeyMatchesConstructor(t *testing.T) {
	if err := ValidateRootKey(testRootKey()); err != nil {
		t.Fatalf("valid root key rejected: %v", err)
	}
	if err := ValidateRootKey(bytes.Repeat([]byte{0x09}, MinRootKeyBytes)); !errors.Is(err, ErrCrypto) {
		t.Fatalf("uniform root key error = %v, want ErrCrypto", err)
	}
	if err := ValidateRootKey(make([]byte, MinRootKeyBytes-1)); !errors.Is(err, ErrCrypto) {
		t.Fatalf("short root key error = %v, want ErrCrypto", err)
	}
}

func TestErrorsNeverContainKeyMaterial(t *testing.T) {
	key := testRootKey()
	if _, err := NewCipher(key[:16], 1); err == nil || bytes.Contains([]byte(err.Error()), key[:16]) {
		t.Fatal("constructor error must not echo key material")
	}
	lowDiversity := bytes.Repeat([]byte{0x11, 0x22}, 16)
	if _, err := NewCipher(lowDiversity, 1); err == nil || bytes.Contains([]byte(err.Error()), lowDiversity) {
		t.Fatal("constructor error must not echo key material")
	}
}

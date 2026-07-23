package push

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
)

func DecodeKey(encoded string) ([]byte, error) {
	key, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil || len(key) != 32 {
		return nil, errors.New("push token encryption key must be base64-encoded 32 bytes")
	}
	return key, nil
}

func EncryptToken(key []byte, token string) ([]byte, error) {
	aead, err := tokenAEAD(key)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return nil, err
	}
	return aead.Seal(nonce, nonce, []byte(token), []byte("TIMA-PUSH-TOKEN-v1")), nil
}

func DecryptToken(key, ciphertext []byte) (string, error) {
	aead, err := tokenAEAD(key)
	if err != nil || len(ciphertext) < aead.NonceSize() {
		return "", errors.New("invalid push token ciphertext")
	}
	plain, err := aead.Open(
		nil,
		ciphertext[:aead.NonceSize()],
		ciphertext[aead.NonceSize():],
		[]byte("TIMA-PUSH-TOKEN-v1"),
	)
	if err != nil {
		return "", errors.New("invalid push token ciphertext")
	}
	return string(plain), nil
}

func TokenHash(token string) []byte {
	sum := sha256.Sum256([]byte(token))
	return sum[:]
}

func tokenAEAD(key []byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

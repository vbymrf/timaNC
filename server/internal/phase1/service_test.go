package phase1

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"strings"
	"testing"

	"tima-server/internal/config"
)

func TestArgon2IDPasswordEncoding(t *testing.T) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		t.Fatal(err)
	}
	encoded := passwordHash("correct horse battery staple", salt)
	if !strings.HasPrefix(string(encoded), "$argon2id$v=19$m=65536,t=3,p=2$") {
		t.Fatalf("unexpected encoding: %q", encoded)
	}
	if !verifyPassword(encoded, "correct horse battery staple") {
		t.Fatal("correct password rejected")
	}
	if verifyPassword(encoded, "wrong password") {
		t.Fatal("wrong password accepted")
	}
}

func TestDevelopmentMLKEMFixtureIsValid(t *testing.T) {
	public, err := decodeMLKEM768Public(strings.TrimSpace(devMLKEM768PublicKey))
	if err != nil {
		t.Fatal(err)
	}
	if len(public) != 1184 {
		t.Fatalf("public key length = %d", len(public))
	}
}

func TestDevelopmentEscrowSigningRootIsPinned(t *testing.T) {
	cfg := config.Config{Environment: "test"}
	first, err := New(nil, cfg)
	if err != nil {
		t.Fatal(err)
	}
	second, err := New(nil, cfg)
	if err != nil {
		t.Fatal(err)
	}
	firstPublic := first.EscrowSigner.Public().(ed25519.PublicKey)
	secondPublic := second.EscrowSigner.Public().(ed25519.PublicKey)
	if first.EscrowKeyID != "dev-ed25519-1" || second.EscrowKeyID != first.EscrowKeyID {
		t.Fatalf("unstable key IDs: %q, %q", first.EscrowKeyID, second.EscrowKeyID)
	}
	if !bytes.Equal(firstPublic, secondPublic) {
		t.Fatal("development signing roots differ")
	}
	pinned, err := base64.StdEncoding.Strict().DecodeString(strings.TrimSpace(devEd25519PublicKey))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(firstPublic, pinned) {
		t.Fatal("derived signing root does not match pinned public fixture")
	}
	message := []byte("test-only escrow config signature")
	for _, service := range []*Service{first, second} {
		signature := ed25519.Sign(service.EscrowSigner, message)
		if !ed25519.Verify(ed25519.PublicKey(pinned), message, signature) {
			t.Fatal("signature did not verify with pinned public fixture")
		}
	}
}

func TestProductionEscrowSigningKeyIsRequired(t *testing.T) {
	if _, err := New(nil, config.Config{Environment: "production"}); err == nil {
		t.Fatal("production accepted the test-only signing root")
	}
}

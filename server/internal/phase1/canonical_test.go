package phase1

import (
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"testing"
)

func TestCanonicalMetadataRejectsExtensionsAndWrongVersion(t *testing.T) {
	for _, raw := range []string{
		`{"content_mode":"private","format_version":1,"revision_number":1}`,
		`{"content_mode":"private","format_version":2,"revision_number":1,"unexpected":true}`,
		`{"content_mode":"public","format_version":2,"revision_number":1}`,
	} {
		if _, _, err := canonicalMetadata(json.RawMessage(raw)); err == nil {
			t.Fatalf("canonicalMetadata(%s) unexpectedly succeeded", raw)
		}
	}
}

func TestEscrowCommitmentExtraction(t *testing.T) {
	commitment := make([]byte, 32)
	for i := range commitment {
		commitment[i] = byte(i)
	}
	w := &canonicalWriter{}
	w.WriteString("tima/escrow-blob/v1")
	w.WriteByte(0)
	w.u32(1)
	w.blob([]byte("2026Q3"))
	w.u32(0)
	w.blob([]byte("RU-2026Q3-0"))
	w.Write(commitment)
	w.Write(make([]byte, 32))
	w.blob(make([]byte, 1088))
	w.blob([]byte{2})

	got, err := parseEscrowBlob(w.Bytes())
	if err != nil {
		t.Fatal(err)
	}
	if got.Region != 1 || got.EpochID != "2026Q3" || got.ShardID != 0 ||
		got.KeyID != "RU-2026Q3-0" || !subtleEqual(got.KeyCommitment, commitment) {
		t.Fatal("commitment differs")
	}
}

func TestPersonalInputUsesBigEndianMessageID(t *testing.T) {
	zeroUUID := "00000000-0000-4000-8000-000000000000"
	escrow := &canonicalWriter{}
	escrow.WriteString("tima/escrow-blob/v1")
	escrow.WriteByte(0)
	escrow.u32(1)
	escrow.blob([]byte("2026Q3"))
	escrow.u32(0)
	escrow.blob([]byte("key"))
	escrow.Write(make([]byte, 32+32))
	escrow.blob([]byte{1})
	escrow.blob([]byte{2})
	d := PrivateDocument{
		EncryptedNodes:  []string{base64.StdEncoding.EncodeToString([]byte{3})},
		Metadata:        json.RawMessage(`{"revision_number":1,"format_version":2,"content_mode":"private"}`),
		ProtocolVersion: 2, PresenceBitmap: 1,
		KeyCommitment: base64.StdEncoding.EncodeToString(make([]byte, 32)),
		EscrowBlob:    base64.StdEncoding.EncodeToString(escrow.Bytes()),
	}
	got, err := personalSignatureInput(d, 7, zeroUUID, nil, 1, zeroUUID, zeroUUID, zeroUUID)
	if err != nil {
		t.Fatal(err)
	}
	offset := len("tima/personal-envelope/signature/v2") + 1 + 4
	if binary.BigEndian.Uint64(got[offset:offset+8]) != 7 {
		t.Fatal("message ID not encoded as canonical U64")
	}
}

func TestCanonicalEd25519S(t *testing.T) {
	signature := make([]byte, 64)
	signature[32] = 1
	if !canonicalEd25519Signature(signature) {
		t.Fatal("S=1 must be canonical")
	}
	copy(signature[32:], []byte{
		0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58,
		0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
		0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
	})
	if canonicalEd25519Signature(signature) {
		t.Fatal("S=L must be rejected")
	}
	signature[63] = 0x20
	if canonicalEd25519Signature(signature) {
		t.Fatal("S>L must be rejected")
	}
}

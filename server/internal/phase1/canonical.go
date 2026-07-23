package phase1

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math"
	"strconv"
	"strings"
	"unicode/utf8"
)

type canonicalWriter struct{ bytes.Buffer }

func (w *canonicalWriter) u32(v uint32) { _ = binary.Write(&w.Buffer, binary.BigEndian, v) }
func (w *canonicalWriter) u64(v uint64) { _ = binary.Write(&w.Buffer, binary.BigEndian, v) }
func (w *canonicalWriter) blob(v []byte) {
	w.u32(uint32(len(v)))
	w.Write(v)
}
func (w *canonicalWriter) optionalBlob(v []byte) {
	if len(v) == 0 {
		w.WriteByte(0)
		return
	}
	w.WriteByte(1)
	w.blob(v)
}

func uuidBytes(v string) ([]byte, error) {
	if len(v) != 36 || v[8] != '-' || v[13] != '-' || v[18] != '-' || v[23] != '-' {
		return nil, ErrInvalid
	}
	b, err := hex.DecodeString(strings.ReplaceAll(v, "-", ""))
	if err != nil || len(b) != 16 {
		return nil, ErrInvalid
	}
	return b, nil
}

type Metadata struct {
	ContentMode   string `json:"content_mode"`
	FormatVersion int    `json:"format_version"`
	Revision      int64  `json:"revision_number"`
}

func canonicalMetadata(raw json.RawMessage) ([]byte, Metadata, error) {
	var fields map[string]json.RawMessage
	dec := json.NewDecoder(bytes.NewReader(raw))
	if err := dec.Decode(&fields); err != nil || len(fields) != 3 {
		return nil, Metadata{}, ErrInvalid
	}
	if err := rejectDuplicateMetadataKeys(raw); err != nil {
		return nil, Metadata{}, err
	}
	for _, k := range []string{"content_mode", "format_version", "revision_number"} {
		if _, ok := fields[k]; !ok {
			return nil, Metadata{}, ErrInvalid
		}
	}
	var m Metadata
	if err := json.Unmarshal(raw, &m); err != nil || m.ContentMode != "private" ||
		m.FormatVersion != 2 || m.Revision < 1 {
		return nil, Metadata{}, ErrInvalid
	}
	// This restricted metadata shape has exactly the same bytes under
	// encoding/json's sorted map keys and RFC 8785.
	out, _ := json.Marshal(map[string]any{
		"content_mode": m.ContentMode, "format_version": m.FormatVersion, "revision_number": m.Revision,
	})
	return out, m, nil
}

func rejectDuplicateMetadataKeys(raw []byte) error {
	dec := json.NewDecoder(bytes.NewReader(raw))
	token, err := dec.Token()
	if err != nil || token != json.Delim('{') {
		return ErrInvalid
	}
	seen := map[string]bool{}
	for dec.More() {
		token, err = dec.Token()
		key, ok := token.(string)
		if err != nil || !ok || seen[key] {
			return ErrInvalid
		}
		seen[key] = true
		var value json.RawMessage
		if err = dec.Decode(&value); err != nil {
			return ErrInvalid
		}
	}
	if token, err = dec.Token(); err != nil || token != json.Delim('}') {
		return ErrInvalid
	}
	if token, err = dec.Token(); err != io.EOF {
		return ErrInvalid
	}
	return nil
}

func personalSignatureInput(
	d PrivateDocument,
	messageID int64,
	revisionID string,
	parentRevisionID *string,
	messageKeyID int64,
	senderID, deviceID, chatID string,
) ([]byte, error) {
	if messageID <= 0 || messageKeyID < 0 {
		return nil, ErrInvalid
	}
	revision, err := uuidBytes(revisionID)
	if err != nil {
		return nil, err
	}
	chat, err := uuidBytes(chatID)
	if err != nil {
		return nil, err
	}
	sender, err := uuidBytes(senderID)
	if err != nil {
		return nil, err
	}
	device, err := uuidBytes(deviceID)
	if err != nil {
		return nil, err
	}
	metadata, metadataFields, err := canonicalMetadata(d.Metadata)
	if err != nil {
		return nil, err
	}
	commitment, err := decodeExact(d.KeyCommitment, 32)
	if err != nil {
		return nil, ErrInvalid
	}
	escrow, err := decodeNonEmpty(d.EscrowBlob, 1<<20)
	if err != nil {
		return nil, err
	}
	ratchet, err := decodeOptional(d.RatchetEnvelope, 1<<20)
	if err != nil {
		return nil, err
	}
	w := &canonicalWriter{}
	w.WriteString("tima/personal-envelope/signature/v2")
	w.WriteByte(0)
	w.u32(2)
	w.u64(uint64(messageID))
	w.Write(revision)
	if parentRevisionID == nil {
		w.WriteByte(0)
	} else {
		parent, parentErr := uuidBytes(*parentRevisionID)
		if parentErr != nil {
			return nil, parentErr
		}
		w.WriteByte(1)
		w.Write(parent)
	}
	w.u64(uint64(metadataFields.Revision))
	w.Write(chat)
	w.Write(sender)
	w.Write(device)
	w.u32(d.PresenceBitmap)
	if len(d.EncryptedNodes) == 0 {
		w.WriteByte(0)
	} else {
		w.WriteByte(1)
		w.u32(uint32(len(d.EncryptedNodes)))
		for _, encoded := range d.EncryptedNodes {
			node, err := decodeNonEmpty(encoded, 1<<20)
			if err != nil {
				return nil, err
			}
			w.blob(node)
		}
	}
	w.WriteByte(0) // plaintext nodes absent
	markup, _, err := validateAndCanonicalizeMarkup(d.Markup, len(d.EncryptedNodes))
	if err != nil {
		return nil, err
	}
	if len(markup) == 0 {
		w.WriteByte(0)
	} else {
		w.WriteByte(1)
		w.blob(markup)
	}
	w.blob(metadata)
	if d.EncryptedMetadata == "" {
		w.WriteByte(0)
	} else {
		encryptedMetadata, err := decodeNonEmpty(d.EncryptedMetadata, 1<<20)
		if err != nil {
			return nil, err
		}
		w.WriteByte(1)
		w.blob(encryptedMetadata)
	}
	w.u32(uint32(messageKeyID))
	w.Write(commitment)
	w.blob(escrow)
	w.optionalBlob(ratchet)
	w.u32(0) // media bindings: text-only
	return w.Bytes(), nil
}

type escrowBlobHeaders struct {
	Region        uint32
	EpochID       string
	ShardID       uint32
	KeyID         string
	KeyCommitment []byte
}

func parseEscrowBlob(blob []byte) (escrowBlobHeaders, error) {
	domain := append([]byte("tima/escrow-blob/v1"), 0)
	if !bytes.HasPrefix(blob, domain) {
		return escrowBlobHeaders{}, ErrInvalid
	}
	i := len(domain)
	if len(blob) < i+4 {
		return escrowBlobHeaders{}, ErrInvalid
	}
	region := binary.BigEndian.Uint32(blob[i : i+4])
	if region != 1 && region != 2 {
		return escrowBlobHeaders{}, ErrInvalid
	}
	i += 4
	epoch, next, ok := readCanonicalBlob(blob, i)
	if !ok || !utf8.Valid(epoch) {
		return escrowBlobHeaders{}, ErrInvalid
	}
	i = next
	if i+4 > len(blob) {
		return escrowBlobHeaders{}, ErrInvalid
	}
	shard := binary.BigEndian.Uint32(blob[i : i+4])
	i += 4
	keyID, next, ok := readCanonicalBlob(blob, i)
	if !ok || !utf8.Valid(keyID) {
		return escrowBlobHeaders{}, ErrInvalid
	}
	i = next
	if i+32 > len(blob) {
		return escrowBlobHeaders{}, ErrInvalid
	}
	commitment := blob[i : i+32]
	i += 32
	if i+32 > len(blob) {
		return escrowBlobHeaders{}, ErrInvalid
	}
	i += 32 // ephemeral X25519 key
	for n := 0; n < 2; n++ {
		value, next, ok := readCanonicalBlob(blob, i)
		if !ok || (n == 0 && len(value) != 1088) {
			return escrowBlobHeaders{}, ErrInvalid
		}
		i = next
	}
	if i != len(blob) {
		return escrowBlobHeaders{}, ErrInvalid
	}
	return escrowBlobHeaders{Region: region, EpochID: string(epoch), ShardID: shard,
		KeyID: string(keyID), KeyCommitment: commitment}, nil
}

func readCanonicalBlob(input []byte, offset int) ([]byte, int, bool) {
	if offset+4 > len(input) {
		return nil, offset, false
	}
	length := int(binary.BigEndian.Uint32(input[offset : offset+4]))
	offset += 4
	if length < 1 || offset+length > len(input) {
		return nil, offset, false
	}
	return input[offset : offset+length], offset + length, true
}

func parseMessageID(v string) (int64, error) {
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n <= 0 || v != strconv.FormatInt(n, 10) {
		return 0, ErrInvalid
	}
	return n, nil
}

func checkedUint32(v int64) (uint32, error) {
	if v < 0 || v > math.MaxUint32 {
		return 0, errors.New("outside uint32")
	}
	return uint32(v), nil
}

func canonicalEd25519Signature(signature []byte) bool {
	if len(signature) != 64 {
		return false
	}
	// Group order L in little-endian form. RFC 8032 requires S < L.
	order := [32]byte{
		0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58,
		0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10,
	}
	s := signature[32:]
	for i := len(order) - 1; i >= 0; i-- {
		if s[i] < order[i] {
			return true
		}
		if s[i] > order[i] {
			return false
		}
	}
	return false
}

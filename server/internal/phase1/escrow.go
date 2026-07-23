package phase1

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"fmt"
	"time"
)

type EscrowPublicKeys struct {
	X25519Threshold string `json:"x25519_threshold"`
	MLKEM768        string `json:"mlkem768"`
}

type EscrowNextKeys struct {
	EscrowPublicKeys
	EpochID string `json:"epoch_id"`
	KeyID   string `json:"key_id"`
}

type EscrowConfig struct {
	ConfigVersion     int              `json:"config_version"`
	Region            string           `json:"region"`
	EpochID           string           `json:"epoch_id"`
	ShardID           int              `json:"shard_id"`
	KeyID             string           `json:"key_id"`
	ValidFrom         time.Time        `json:"valid_from"`
	ValidUntil        time.Time        `json:"valid_until"`
	CurrentPublicKeys EscrowPublicKeys `json:"current_public_keys"`
	NextPublicKeys    EscrowNextKeys   `json:"next_public_keys"`
	SignerKeyID       string           `json:"signer_key_id"`
	Signature         string           `json:"signature"`
}

func (s *Service) EscrowConfig(ctx context.Context, p Principal, conversationType, conversationID, epoch string, shard int) (EscrowConfig, error) {
	if conversationType != "chat" || shard != 0 || s.EscrowSigner == nil {
		return EscrowConfig{}, ErrInvalid
	}
	var region string
	err := s.DB.QueryRow(ctx, `SELECT conversation_home_region FROM chats
		WHERE chat_id=$1 AND ($2=user_a OR $2=user_b)`, conversationID, p.UserID).Scan(&region)
	if err != nil {
		return EscrowConfig{}, ErrForbidden
	}
	from, until, nextEpoch, err := quarter(epoch)
	if err != nil || epoch != currentQuarter(s.Now().UTC()) {
		return EscrowConfig{}, ErrInvalid
	}
	keyID := fmt.Sprintf("%s-%s-%d", region, epoch, shard)
	nextKeyID := fmt.Sprintf("%s-%s-%d", region, nextEpoch, shard)
	keys := EscrowPublicKeys{X25519Threshold: base64.StdEncoding.EncodeToString(s.EscrowX25519),
		MLKEM768: base64.StdEncoding.EncodeToString(s.EscrowMLKEM768)}
	out := EscrowConfig{ConfigVersion: 1, Region: region, EpochID: epoch, ShardID: shard,
		KeyID: keyID, ValidFrom: from, ValidUntil: until, CurrentPublicKeys: keys,
		NextPublicKeys: EscrowNextKeys{EscrowPublicKeys: keys, EpochID: nextEpoch, KeyID: nextKeyID},
		SignerKeyID:    s.EscrowKeyID}
	input := escrowConfigInput(out)
	out.Signature = base64.StdEncoding.EncodeToString(ed25519.Sign(s.EscrowSigner, input))
	return out, nil
}

func currentQuarter(now time.Time) string {
	return fmt.Sprintf("%04dQ%d", now.Year(), (int(now.Month())-1)/3+1)
}

func escrowConfigInput(c EscrowConfig) []byte {
	w := &canonicalWriter{}
	w.WriteString("tima/escrow-config/signature/v1")
	w.WriteByte(0)
	w.u32(1)
	if c.Region == "RU" {
		w.u32(1)
	} else {
		w.u32(2)
	}
	w.blob([]byte(c.EpochID))
	w.u32(uint32(c.ShardID))
	writeTime(w, c.ValidFrom)
	writeTime(w, c.ValidUntil)
	writeEscrowKeySet(w, c.KeyID, c.CurrentPublicKeys)
	w.WriteByte(1)
	writeEscrowKeySet(w, c.NextPublicKeys.KeyID, c.NextPublicKeys.EscrowPublicKeys)
	w.blob([]byte(c.SignerKeyID))
	return w.Bytes()
}

func writeTime(w *canonicalWriter, t time.Time) {
	w.u64(uint64(t.Unix()))
	w.u32(uint32(t.Nanosecond()))
}

func writeEscrowKeySet(w *canonicalWriter, id string, keys EscrowPublicKeys) {
	x, _ := base64.StdEncoding.DecodeString(keys.X25519Threshold)
	m, _ := base64.StdEncoding.DecodeString(keys.MLKEM768)
	w.blob([]byte(id))
	w.Write(x)
	w.blob(m)
}

func quarter(value string) (time.Time, time.Time, string, error) {
	if len(value) != 6 || value[4] != 'Q' || value[5] < '1' || value[5] > '4' {
		return time.Time{}, time.Time{}, "", ErrInvalid
	}
	var year int
	if _, err := fmt.Sscanf(value[:4], "%d", &year); err != nil || year < 2000 || year > 9999 {
		return time.Time{}, time.Time{}, "", ErrInvalid
	}
	q := int(value[5] - '0')
	from := time.Date(year, time.Month(1+(q-1)*3), 1, 0, 0, 0, 0, time.UTC)
	until := from.AddDate(0, 3, 0)
	nextQ, nextYear := q+1, year
	if nextQ == 5 {
		nextQ, nextYear = 1, year+1
	}
	return from, until, fmt.Sprintf("%04dQ%d", nextYear, nextQ), nil
}

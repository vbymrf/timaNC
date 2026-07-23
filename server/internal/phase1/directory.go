package phase1

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"time"

	"github.com/jackc/pgx/v5"
)

type SignedPrekey struct {
	ID        int       `json:"id"`
	PublicKey string    `json:"public_key"`
	Signature string    `json:"signature"`
	ExpiresAt time.Time `json:"expires_at"`
}

type OneTimePrekey struct {
	ID        int    `json:"id"`
	PublicKey string `json:"public_key"`
}

type KeyBundle struct {
	DeviceID       string          `json:"device_id"`
	IdentityKey    string          `json:"identity_key"`
	SignedPrekey   *SignedPrekey   `json:"signed_prekey,omitempty"`
	OneTimePrekeys []OneTimePrekey `json:"one_time_prekeys,omitempty"`
	UpdatedAt      time.Time       `json:"updated_at"`
}

func (s *Service) PutKeyBundle(ctx context.Context, p Principal, in KeyBundle) (KeyBundle, error) {
	if in.DeviceID != p.DeviceID || len(in.OneTimePrekeys) > 100 ||
		(in.SignedPrekey == nil && len(in.OneTimePrekeys) > 0) {
		return KeyBundle{}, ErrInvalid
	}
	identity, err := decodeExact(in.IdentityKey, 32)
	if err != nil {
		return KeyBundle{}, ErrInvalid
	}
	var signed, signature []byte
	if in.SignedPrekey != nil {
		if in.SignedPrekey.ID < 1 || !in.SignedPrekey.ExpiresAt.After(s.Now()) {
			return KeyBundle{}, ErrInvalid
		}
		signed, err = decodeExact(in.SignedPrekey.PublicKey, 32)
		if err != nil {
			return KeyBundle{}, ErrInvalid
		}
		signature, err = decodeExact(in.SignedPrekey.Signature, ed25519.SignatureSize)
		if err != nil {
			return KeyBundle{}, ErrInvalid
		}
		var signing []byte
		if err = s.DB.QueryRow(ctx, `SELECT signing_pubkey FROM devices
			WHERE device_id=$1 AND user_id=$2 AND revoked_at IS NULL`, p.DeviceID, p.UserID).Scan(&signing); err != nil {
			return KeyBundle{}, ErrForbidden
		}
		if !canonicalEd25519Signature(signature) || !ed25519.Verify(ed25519.PublicKey(signing), signed, signature) {
			return KeyBundle{}, ErrInvalid
		}
	}
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return KeyBundle{}, err
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `UPDATE devices SET identity_pubkey=$2 WHERE device_id=$1`, p.DeviceID, identity); err != nil {
		return KeyBundle{}, err
	}
	if _, err = tx.Exec(ctx, `DELETE FROM prekeys WHERE device_id=$1 AND consumed_at IS NULL`, p.DeviceID); err != nil {
		return KeyBundle{}, err
	}
	if in.SignedPrekey != nil {
		if _, err = tx.Exec(ctx, `INSERT INTO prekeys(device_id,key_id,kind,public_key,signature,expires_at)
			VALUES($1,$2,'signed',$3,$4,$5)`, p.DeviceID, in.SignedPrekey.ID, signed,
			signature, in.SignedPrekey.ExpiresAt); err != nil {
			return KeyBundle{}, err
		}
	}
	seen := map[int]bool{}
	for _, key := range in.OneTimePrekeys {
		public, decodeErr := decodeExact(key.PublicKey, 32)
		if decodeErr != nil || key.ID < 1 || seen[key.ID] {
			return KeyBundle{}, ErrInvalid
		}
		seen[key.ID] = true
		if _, err = tx.Exec(ctx, `INSERT INTO prekeys(device_id,key_id,kind,public_key)
			VALUES($1,$2,'onetime',$3)`, p.DeviceID, key.ID, public); err != nil {
			return KeyBundle{}, err
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return KeyBundle{}, err
	}
	in.UpdatedAt = s.Now().UTC()
	return in, nil
}

func (s *Service) GetKeyBundles(ctx context.Context, p Principal, userID string) ([]KeyBundle, error) {
	rows, err := s.DB.Query(ctx, `SELECT d.device_id,d.identity_pubkey,sp.key_id,sp.public_key,sp.signature,
		sp.expires_at,coalesce(sp.created_at,d.created_at)
		FROM devices d LEFT JOIN prekeys sp ON sp.device_id=d.device_id AND sp.kind='signed'
		  AND sp.expires_at>now()
		WHERE d.user_id=$1 AND d.revoked_at IS NULL ORDER BY d.device_id`, userID)
	if err != nil {
		return nil, err
	}
	var out []KeyBundle
	for rows.Next() {
		var b KeyBundle
		var identity, signed, signature []byte
		var signedID *int
		var signedExpiry *time.Time
		if err = rows.Scan(&b.DeviceID, &identity, &signedID, &signed, &signature,
			&signedExpiry, &b.UpdatedAt); err != nil {
			rows.Close()
			return nil, err
		}
		b.IdentityKey = base64.StdEncoding.EncodeToString(identity)
		if signedID != nil && signedExpiry != nil {
			b.SignedPrekey = &SignedPrekey{ID: *signedID, PublicKey: base64.StdEncoding.EncodeToString(signed),
				Signature: base64.StdEncoding.EncodeToString(signature), ExpiresAt: *signedExpiry}
		}
		out = append(out, b)
	}
	rows.Close()
	if len(out) == 0 {
		return nil, ErrNotFound
	}
	return out, nil
}

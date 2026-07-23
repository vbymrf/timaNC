package phase1

import (
	"context"
	"strings"

	"tima-server/internal/push"
)

type PushRegistration struct {
	Provider string `json:"provider"`
	Token    string `json:"token"`
}

func (s *Service) RegisterPush(
	ctx context.Context,
	p Principal,
	in PushRegistration,
) error {
	token := strings.TrimSpace(in.Token)
	if len(token) < 16 || len(token) > 4096 || len(s.PushTokenKey) != 32 {
		return ErrInvalid
	}
	var platform string
	if err := s.DB.QueryRow(ctx, `SELECT platform FROM devices
		WHERE device_id=$1 AND user_id=$2 AND revoked_at IS NULL`,
		p.DeviceID, p.UserID).Scan(&platform); err != nil {
		return ErrForbidden
	}
	expectedProvider := map[string]string{"android": "fcm", "ios": "apns", "windows": "wns"}[platform]
	if in.Provider != expectedProvider {
		return ErrInvalid
	}
	encrypted, err := push.EncryptToken(s.PushTokenKey, token)
	if err != nil {
		return err
	}
	_, err = s.DB.Exec(ctx, `INSERT INTO device_push_registrations
		(device_id,provider,token_ciphertext,token_hash,updated_at)
		VALUES($1,$2,$3,$4,now())
		ON CONFLICT(device_id) DO UPDATE SET provider=excluded.provider,
		  token_ciphertext=excluded.token_ciphertext,token_hash=excluded.token_hash,updated_at=now()`,
		p.DeviceID, in.Provider, encrypted, push.TokenHash(token))
	return err
}

func (s *Service) DeletePush(ctx context.Context, p Principal) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM device_push_registrations
		WHERE device_id=$1 AND EXISTS(SELECT 1 FROM devices
		  WHERE device_id=$1 AND user_id=$2)`, p.DeviceID, p.UserID)
	return err
}

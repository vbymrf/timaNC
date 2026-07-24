package phase1

import (
	"context"
	"net"
	"net/url"
	"strings"

	"tima-server/internal/push"
)

type PushRegistration struct {
	Provider string `json:"provider"`
	Token    string `json:"token"`
	Priority *int   `json:"priority,omitempty"`
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
	priority := 100
	if in.Priority != nil {
		priority = *in.Priority
	}
	if priority < 0 || priority > 100 {
		return ErrInvalid
	}
	var platform string
	if err := s.DB.QueryRow(ctx, `SELECT platform FROM devices
		WHERE device_id=$1 AND user_id=$2 AND revoked_at IS NULL`,
		p.DeviceID, p.UserID).Scan(&platform); err != nil {
		return ErrForbidden
	}
	allowed := map[string]map[string]bool{
		"android": {"fcm": true, "unifiedpush": true},
		"ios":     {"apns": true},
		"windows": {"wns": true},
	}
	if !allowed[platform][in.Provider] {
		return ErrInvalid
	}
	if in.Provider == "unifiedpush" {
		endpoint, err := url.ParseRequestURI(token)
		if err != nil || endpoint.Scheme != "https" || endpoint.Host == "" || endpoint.User != nil {
			return ErrInvalid
		}
		hostname := strings.ToLower(endpoint.Hostname())
		if hostname == "localhost" {
			return ErrInvalid
		}
		if ip := net.ParseIP(hostname); ip != nil &&
			(ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() ||
				ip.IsLinkLocalMulticast() || ip.IsUnspecified()) {
			return ErrInvalid
		}
	}
	if (in.Provider == "fcm" || in.Provider == "apns" || in.Provider == "wns") &&
		strings.ContainsAny(token, "\r\n") {
		return ErrInvalid
	}
	encrypted, err := push.EncryptToken(s.PushTokenKey, token)
	if err != nil {
		return err
	}
	_, err = s.DB.Exec(ctx, `INSERT INTO device_push_registrations
		(device_id,provider,token_ciphertext,token_hash,priority,updated_at)
		VALUES($1,$2,$3,$4,$5,now())
		ON CONFLICT(device_id,provider) DO UPDATE SET
		  token_ciphertext=excluded.token_ciphertext,token_hash=excluded.token_hash,
		  priority=excluded.priority,updated_at=now()`,
		p.DeviceID, in.Provider, encrypted, push.TokenHash(token), priority)
	return err
}

func (s *Service) DeletePush(ctx context.Context, p Principal, providers ...string) error {
	provider := ""
	if len(providers) > 0 {
		provider = providers[0]
	}
	if provider != "" && provider != "fcm" && provider != "apns" &&
		provider != "wns" && provider != "unifiedpush" {
		return ErrInvalid
	}
	_, err := s.DB.Exec(ctx, `DELETE FROM device_push_registrations
		WHERE device_id=$1 AND ($3='' OR provider=$3)
		  AND EXISTS(SELECT 1 FROM devices
		    WHERE device_id=$1 AND user_id=$2)`, p.DeviceID, p.UserID, provider)
	return err
}

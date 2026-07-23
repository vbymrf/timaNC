package phase1

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

type LinkSession struct {
	DesktopPublicKey string `json:"desktop_public_key"`
	SigningPublicKey string `json:"signing_public_key"`
	DesktopName      string `json:"desktop_name"`
}

type LinkChallenge struct {
	SessionID  string    `json:"session_id"`
	QRPayload  string    `json:"qr_payload"`
	ClaimToken string    `json:"claim_token"`
	ExpiresAt  time.Time `json:"expires_at"`
}

type LinkConfirm struct {
	SessionID           string `json:"session_id"`
	QRSecret            string `json:"qr_secret"`
	Confirmation        string `json:"confirmation"`
	WrappedDeviceSecret string `json:"wrapped_device_secret"`
}

type LinkClaim struct {
	SessionID  string `json:"session_id"`
	ClaimToken string `json:"claim_token"`
}

type LinkedSession struct {
	AuthSession
	WrappedDeviceSecret string `json:"wrapped_device_secret"`
}

func (s *Service) StartDeviceLink(ctx context.Context, in LinkSession) (LinkChallenge, error) {
	identityKey, err := decodeExact(in.DesktopPublicKey, 32)
	if err != nil {
		return LinkChallenge{}, ErrInvalid
	}
	signingKey, err := decodeExact(in.SigningPublicKey, ed25519.PublicKeySize)
	if err != nil || in.DesktopName == "" || len(in.DesktopName) > 100 {
		return LinkChallenge{}, ErrInvalid
	}
	sessionID, err := NewUUID()
	if err != nil {
		return LinkChallenge{}, err
	}
	qrSecret, err := randomToken()
	if err != nil {
		return LinkChallenge{}, err
	}
	claimToken, err := randomToken()
	if err != nil {
		return LinkChallenge{}, err
	}
	expires := s.Now().UTC().Add(5 * time.Minute)
	if _, err = s.DB.Exec(ctx, `INSERT INTO device_link_sessions
		(session_id,desktop_identity_key,desktop_signing_key,desktop_name,qr_secret_hash,claim_token_hash,expires_at)
		VALUES($1,$2,$3,$4,$5,$6,$7)`, sessionID, identityKey, signingKey, in.DesktopName,
		s.tokenHash(qrSecret), s.tokenHash(claimToken), expires); err != nil {
		return LinkChallenge{}, err
	}
	qrPayload := fmt.Sprintf("tima://link/v1?session_id=%s&secret=%s", sessionID, qrSecret)
	return LinkChallenge{
		SessionID: sessionID, QRPayload: qrPayload, ClaimToken: claimToken, ExpiresAt: expires,
	}, nil
}

func (s *Service) ConfirmDeviceLink(
	ctx context.Context,
	p Principal,
	in LinkConfirm,
) (Device, error) {
	qrSecret, err := base64.RawURLEncoding.DecodeString(in.QRSecret)
	if err != nil || len(qrSecret) < 32 {
		return Device{}, ErrInvalid
	}
	signature, err := decodeExact(in.Confirmation, ed25519.SignatureSize)
	if err != nil {
		return Device{}, ErrInvalid
	}
	wrapped, err := base64.StdEncoding.DecodeString(in.WrappedDeviceSecret)
	if err != nil || len(wrapped) < 32 {
		return Device{}, ErrInvalid
	}
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return Device{}, err
	}
	defer tx.Rollback(ctx)
	var identityKey, desktopSigningKey, phoneSigningKey, qrHash []byte
	var desktopName, platform string
	err = tx.QueryRow(ctx, `SELECT l.desktop_identity_key,l.desktop_signing_key,l.desktop_name,
		l.qr_secret_hash,d.signing_pubkey,d.platform
		FROM device_link_sessions l JOIN devices d ON d.device_id=$2
		WHERE l.session_id=$1 AND l.expires_at>now() AND l.confirmed_at IS NULL
		  AND d.user_id=$3 AND d.revoked_at IS NULL AND d.attestation_ok=true
		  AND d.attested_at>now()-interval '24 hours'
		FOR UPDATE`, in.SessionID, p.DeviceID, p.UserID).
		Scan(&identityKey, &desktopSigningKey, &desktopName, &qrHash, &phoneSigningKey, &platform)
	if err != nil || (platform != "ios" && platform != "android") ||
		!subtleEqual(qrHash, s.tokenHash(in.QRSecret)) {
		return Device{}, ErrForbidden
	}
	signingInput := deviceLinkSigningInput(in.SessionID, identityKey, desktopSigningKey, wrapped)
	if !ed25519.Verify(ed25519.PublicKey(phoneSigningKey), signingInput, signature) {
		return Device{}, ErrForbidden
	}
	deviceID, err := NewUUID()
	if err != nil {
		return Device{}, err
	}
	now := s.Now().UTC()
	if _, err = tx.Exec(ctx, `INSERT INTO devices
		(device_id,user_id,platform,identity_pubkey,signing_pubkey,name,attestation_ok,trust_via_phone,attested_at,last_seen,created_at)
		VALUES($1,$2,'windows',$3,$4,$5,true,true,$6,$6,$6)`,
		deviceID, p.UserID, identityKey, desktopSigningKey, desktopName, now); err != nil {
		return Device{}, err
	}
	if _, err = tx.Exec(ctx, `UPDATE device_link_sessions SET user_id=$2,confirmed_by_device=$3,
		linked_device_id=$4,wrapped_device_secret=$5,confirmed_at=$6 WHERE session_id=$1`,
		in.SessionID, p.UserID, p.DeviceID, deviceID, wrapped, now); err != nil {
		return Device{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return Device{}, err
	}
	return Device{
		ID: deviceID, Name: desktopName, Platform: "windows",
		CreatedAt: now, LastSeenAt: now, Current: false,
	}, nil
}

func (s *Service) ClaimDeviceLink(ctx context.Context, in LinkClaim) (LinkedSession, error) {
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return LinkedSession{}, err
	}
	defer tx.Rollback(ctx)
	var user User
	var device Device
	var wrapped []byte
	err = tx.QueryRow(ctx, `SELECT u.user_id,u.account_type,u.display_name,u.account_home_region,u.created_at,
		d.device_id,d.name,d.platform,d.created_at,coalesce(d.last_seen,d.created_at),l.wrapped_device_secret
		FROM device_link_sessions l JOIN devices d ON d.device_id=l.linked_device_id
		JOIN users u ON u.user_id=l.user_id
		WHERE l.session_id=$1 AND l.claim_token_hash=$2 AND l.expires_at>now()
		  AND l.confirmed_at IS NOT NULL AND l.claimed_at IS NULL FOR UPDATE`,
		in.SessionID, s.tokenHash(in.ClaimToken)).Scan(
		&user.ID, &user.AccountType, &user.DisplayName, &user.AccountHomeRegion, &user.CreatedAt,
		&device.ID, &device.Name, &device.Platform, &device.CreatedAt, &device.LastSeenAt, &wrapped,
	)
	if err != nil {
		return LinkedSession{}, ErrForbidden
	}
	now := s.Now().UTC()
	auth, err := s.createSession(ctx, tx, user.ID, device.ID, "", now)
	if err != nil {
		return LinkedSession{}, err
	}
	if _, err = tx.Exec(ctx, `UPDATE device_link_sessions SET claimed_at=$2 WHERE session_id=$1`,
		in.SessionID, now); err != nil {
		return LinkedSession{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return LinkedSession{}, err
	}
	device.Current = true
	auth.User, auth.Device = user, device
	return LinkedSession{
		AuthSession: auth, WrappedDeviceSecret: base64.StdEncoding.EncodeToString(wrapped),
	}, nil
}

func deviceLinkSigningInput(
	sessionID string,
	identityKey, signingKey, wrappedSecret []byte,
) []byte {
	h := sha256.New()
	h.Write([]byte("TIMA-WINDOWS-LINK-v1"))
	h.Write([]byte{0})
	h.Write([]byte(sessionID))
	h.Write([]byte{0})
	h.Write(identityKey)
	h.Write(signingKey)
	h.Write(wrappedSecret)
	return h.Sum(nil)
}

package phase1

import (
	"context"
	"crypto/rand"
	"errors"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

type OTPChallenge struct {
	ChallengeID string    `json:"challenge_id"`
	ExpiresAt   time.Time `json:"expires_at"`
	ResendAfter int       `json:"resend_after"`
}

func (s *Service) StartRegistration(ctx context.Context, phone, locale string) (OTPChallenge, error) {
	if !validPhone(phone) || !validLocale(locale) {
		return OTPChallenge{}, ErrInvalid
	}
	if s.Environment != "development" && s.Environment != "test" {
		return OTPChallenge{}, errors.New("OTP delivery provider is not configured")
	}
	id, err := NewUUID()
	if err != nil {
		return OTPChallenge{}, err
	}
	now := s.Now().UTC()
	expires := now.Add(10 * time.Minute)
	ph := phoneHash(phone)
	_, err = s.DB.Exec(ctx, `INSERT INTO otp_challenges
		(challenge_id, phone_hash, otp_hash, locale, expires_at, created_at)
		VALUES($1,$2,$3,$4,$5,$6)`, id, ph, s.otpHash(ph, s.DevOTP), locale, expires, now)
	if err != nil {
		return OTPChallenge{}, err
	}
	return OTPChallenge{ChallengeID: id, ExpiresAt: expires, ResendAfter: 30}, nil
}

func (s *Service) VerifyRegistration(
	ctx context.Context,
	challengeID, otp, password, displayName string,
	in DeviceInput,
	attestationToken string,
	requestBody []byte,
) (AuthSession, error) {
	identityKey, signingKey, err := validDevice(in)
	if err != nil || len(password) < 12 || len(password) > 128 || len(otp) != 6 {
		return AuthSession{}, ErrInvalid
	}
	if err = s.requireAttestation(ctx, attestationToken, in.Platform, requestBody); err != nil {
		return AuthSession{}, err
	}
	if displayName == "" {
		displayName = "Tima user"
	}
	if len(displayName) > 100 {
		return AuthSession{}, ErrInvalid
	}
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return AuthSession{}, err
	}
	defer tx.Rollback(ctx)
	var ph, expected []byte
	var attempts int
	err = tx.QueryRow(ctx, `SELECT phone_hash, otp_hash, attempts FROM otp_challenges
		WHERE challenge_id=$1 AND consumed_at IS NULL AND expires_at>now() FOR UPDATE`, challengeID).
		Scan(&ph, &expected, &attempts)
	if err != nil || attempts >= 5 || !subtleEqual(expected, s.otpHash(ph, otp)) {
		if err == nil {
			_, _ = tx.Exec(ctx, `UPDATE otp_challenges SET attempts=attempts+1 WHERE challenge_id=$1`, challengeID)
			_ = tx.Commit(ctx)
		}
		return AuthSession{}, ErrUnauthorized
	}
	var exists bool
	if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM auth_credentials WHERE phone_hash=$1)`, ph).Scan(&exists); err != nil {
		return AuthSession{}, err
	}
	if exists {
		return AuthSession{}, ErrConflict
	}
	userID, _ := NewUUID()
	deviceID, _ := NewUUID()
	salt := make([]byte, 16)
	if _, err = rand.Read(salt); err != nil {
		return AuthSession{}, err
	}
	now := s.Now().UTC()
	if _, err = tx.Exec(ctx, `INSERT INTO users(user_id,account_home_region,phone_hash,display_name,created_at)
		VALUES($1,'RU',$2,$3,$4)`, userID, ph, displayName, now); err != nil {
		return AuthSession{}, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO auth_credentials(user_id,phone_hash,password_hash)
		VALUES($1,$2,$3)`, userID, ph, passwordHash(password, salt)); err != nil {
		return AuthSession{}, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO devices
		(device_id,user_id,platform,identity_pubkey,signing_pubkey,name,app_version,attestation_ok,is_trust_anchor,last_seen,created_at)
		VALUES($1,$2,$3,$4,$5,$6,$7,true,true,$8,$8)`,
		deviceID, userID, in.Platform, identityKey, signingKey, in.Name, in.AppVersion, now); err != nil {
		return AuthSession{}, err
	}
	if _, err = tx.Exec(ctx, `UPDATE otp_challenges SET consumed_at=$2 WHERE challenge_id=$1`, challengeID, now); err != nil {
		return AuthSession{}, err
	}
	session, err := s.createSession(ctx, tx, userID, deviceID, "", now)
	if err != nil {
		return AuthSession{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return AuthSession{}, err
	}
	session.User = User{ID: userID, AccountType: "human", DisplayName: displayName, AccountHomeRegion: "RU", CreatedAt: now}
	session.Device = Device{ID: deviceID, Name: in.Name, Platform: in.Platform, CreatedAt: now, LastSeenAt: now, Current: true}
	return session, nil
}

func (s *Service) Login(
	ctx context.Context,
	phone, password string,
	in DeviceInput,
	attestationToken string,
	requestBody []byte,
) (AuthSession, error) {
	identityKey, signingKey, err := validDevice(in)
	if err != nil || !validPhone(phone) {
		return AuthSession{}, ErrInvalid
	}
	if err = s.requireAttestation(ctx, attestationToken, in.Platform, requestBody); err != nil {
		return AuthSession{}, err
	}
	var user User
	var encoded []byte
	err = s.DB.QueryRow(ctx, `SELECT u.user_id,u.account_type,u.display_name,u.account_home_region,u.created_at,c.password_hash
		FROM auth_credentials c JOIN users u ON u.user_id=c.user_id
		WHERE c.phone_hash=$1 AND u.deleted_at IS NULL AND u.blocked_at IS NULL`, phoneHash(phone)).
		Scan(&user.ID, &user.AccountType, &user.DisplayName, &user.AccountHomeRegion, &user.CreatedAt, &encoded)
	if err != nil || !verifyPassword(encoded, password) {
		return AuthSession{}, ErrUnauthorized
	}
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return AuthSession{}, err
	}
	defer tx.Rollback(ctx)
	deviceID, _ := NewUUID()
	now := s.Now().UTC()
	if _, err = tx.Exec(ctx, `INSERT INTO devices
		(device_id,user_id,platform,identity_pubkey,signing_pubkey,name,app_version,attestation_ok,is_trust_anchor,last_seen,created_at)
		VALUES($1,$2,$3,$4,$5,$6,$7,true,true,$8,$8)`, deviceID, user.ID, in.Platform,
		identityKey, signingKey, in.Name, in.AppVersion, now); err != nil {
		return AuthSession{}, err
	}
	session, err := s.createSession(ctx, tx, user.ID, deviceID, "", now)
	if err != nil {
		return AuthSession{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return AuthSession{}, err
	}
	session.User = user
	session.Device = Device{ID: deviceID, Name: in.Name, Platform: in.Platform, CreatedAt: now, LastSeenAt: now, Current: true}
	return session, nil
}

func (s *Service) Refresh(ctx context.Context, refresh, deviceID string) (AuthSession, error) {
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return AuthSession{}, err
	}
	defer tx.Rollback(ctx)
	var oldID string
	var user User
	var device Device
	err = tx.QueryRow(ctx, `SELECT se.session_id,u.user_id,u.account_type,u.display_name,u.account_home_region,u.created_at,
		d.device_id,d.name,d.platform,d.created_at,coalesce(d.last_seen,d.created_at)
		FROM sessions se JOIN devices d ON d.device_id=se.device_id JOIN users u ON u.user_id=d.user_id
		WHERE se.refresh_hash=$1 AND d.device_id=$2 AND se.revoked_at IS NULL
		  AND d.revoked_at IS NULL AND se.expires_at>now() FOR UPDATE`,
		s.tokenHash(refresh), deviceID).Scan(&oldID, &user.ID, &user.AccountType, &user.DisplayName,
		&user.AccountHomeRegion, &user.CreatedAt, &device.ID, &device.Name, &device.Platform,
		&device.CreatedAt, &device.LastSeenAt)
	if err != nil {
		return AuthSession{}, ErrUnauthorized
	}
	now := s.Now().UTC()
	if _, err = tx.Exec(ctx, `UPDATE sessions SET revoked_at=$2,last_used_at=$2 WHERE session_id=$1`, oldID, now); err != nil {
		return AuthSession{}, err
	}
	session, err := s.createSession(ctx, tx, user.ID, deviceID, oldID, now)
	if err != nil {
		return AuthSession{}, err
	}
	if err = tx.Commit(ctx); err != nil {
		return AuthSession{}, err
	}
	device.Current = true
	session.User, session.Device = user, device
	return session, nil
}

func (s *Service) Logout(ctx context.Context, p Principal) error {
	_, err := s.DB.Exec(ctx, `UPDATE sessions SET revoked_at=now() WHERE session_id=$1 AND revoked_at IS NULL`, p.SessionID)
	return err
}

func (s *Service) createSession(ctx context.Context, tx pgx.Tx, userID, deviceID, rotatedFrom string, now time.Time) (AuthSession, error) {
	access, err := randomToken()
	if err != nil {
		return AuthSession{}, err
	}
	refresh, err := randomToken()
	if err != nil {
		return AuthSession{}, err
	}
	id, _ := NewUUID()
	var parent any
	if rotatedFrom != "" {
		parent = rotatedFrom
	}
	_, err = tx.Exec(ctx, `INSERT INTO sessions
		(session_id,device_id,refresh_hash,access_hash,access_expires_at,expires_at,rotated_from,created_at)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, id, deviceID, s.tokenHash(refresh), s.tokenHash(access),
		now.Add(15*time.Minute), now.Add(30*24*time.Hour), parent, now)
	if err != nil {
		return AuthSession{}, err
	}
	return AuthSession{AccessToken: access, RefreshToken: refresh, TokenType: "Bearer", ExpiresIn: 900}, nil
}

func validPhone(v string) bool {
	if len(v) < 9 || len(v) > 16 || v[0] != '+' || v[1] == '0' {
		return false
	}
	for _, c := range v[1:] {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func validLocale(v string) bool {
	if len(v) != 2 && len(v) != 5 {
		return false
	}
	if v[0] < 'a' || v[0] > 'z' || v[1] < 'a' || v[1] > 'z' {
		return false
	}
	return len(v) == 2 || (v[2] == '-' && strings.ToUpper(v[3:]) == v[3:])
}

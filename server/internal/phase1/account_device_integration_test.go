//go:build integration

package phase1

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func TestAccountDeviceTrustAndDirectoryIntegration(t *testing.T) {
	databaseURL := os.Getenv("DATABASE_URL")
	if databaseURL == "" {
		t.Skip("DATABASE_URL is not set")
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()

	now := time.Now().UTC()
	const attestationSecret = "integration-attestation-secret"
	service := &Service{
		DB: pool, Environment: "test", TokenPepper: []byte("integration-token-pepper"),
		OTPPepper: []byte("integration-otp-pepper"), PushTokenKey: make([]byte, 32),
		AttestationVerifier: NewDevelopmentAttestationVerifier(attestationSecret),
		Now:                 func() time.Time { return now },
	}
	userID, _ := NewUUID()
	phone := fmt.Sprintf("+7%014d", now.UnixNano()%100_000_000_000_000)
	password := "integration password"
	salt := make([]byte, 16)
	if _, err = rand.Read(salt); err != nil {
		t.Fatal(err)
	}
	if _, err = pool.Exec(ctx, `INSERT INTO users(user_id,account_home_region,phone_hash,display_name)
		VALUES($1,'RU',$2,'integration account')`, userID, phoneHash(phone)); err != nil {
		t.Fatal(err)
	}
	if _, err = pool.Exec(ctx, `INSERT INTO auth_credentials(user_id,phone_hash,password_hash)
		VALUES($1,$2,$3)`, userID, phoneHash(phone), passwordHash(password, salt)); err != nil {
		t.Fatal(err)
	}

	identity := make([]byte, 32)
	if _, err = rand.Read(identity); err != nil {
		t.Fatal(err)
	}
	signingPublic, signingPrivate, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	loginBody := []byte(`{"integration":"login"}`)
	challenge := sha256.Sum256(loginBody)
	attestation, err := service.VerifyIOSAttestation(ctx, IOSAttestation{
		KeyID:     "integration-key",
		Challenge: base64.StdEncoding.EncodeToString(challenge[:]),
		Assertion: developmentProof(attestationSecret, "ios", "integration-key", challenge[:]),
	})
	if err != nil || attestation.Verdict != "trusted" || attestation.AttestationToken == "" {
		t.Fatalf("iOS attestation = %#v, %v", attestation, err)
	}
	login, err := service.Login(ctx, phone, password, DeviceInput{
		Name: "integration iPhone", Platform: "ios", AppVersion: "1.0",
		IdentityPublicKey: base64.StdEncoding.EncodeToString(identity),
		SigningPublicKey:  base64.StdEncoding.EncodeToString(signingPublic),
	}, attestation.AttestationToken, loginBody)
	if err != nil || login.User.ID != userID || login.AccessToken == "" || login.Device.Platform != "ios" {
		t.Fatalf("login = %#v, %v", login, err)
	}
	if _, err = service.Login(ctx, phone, "wrong password", DeviceInput{
		Name: "integration iPhone", Platform: "ios",
		IdentityPublicKey: base64.StdEncoding.EncodeToString(identity),
		SigningPublicKey:  base64.StdEncoding.EncodeToString(signingPublic),
	}, attestation.AttestationToken, loginBody); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("wrong-password login error = %v", err)
	}

	principal := Principal{UserID: userID, DeviceID: login.Device.ID}
	username := "u_" + strings.ReplaceAll(userID, "-", "")[:12]
	displayName := "Updated integration account"
	bio := "Phase 1 integration profile"
	profile, err := service.UpdateCurrentUser(ctx, principal, UserPatch{
		Username: &username, DisplayName: &displayName, Bio: &bio,
	})
	if err != nil || profile.Username == nil || *profile.Username != username ||
		profile.DisplayName != displayName || profile.Bio == nil || *profile.Bio != bio {
		t.Fatalf("updated profile = %#v, %v", profile, err)
	}

	const pushToken = "integration-apns-token-123456"
	if err = service.RegisterPush(ctx, principal, PushRegistration{Provider: "apns", Token: pushToken}); err != nil {
		t.Fatal(err)
	}
	var ciphertext []byte
	if err = pool.QueryRow(ctx, `SELECT token_ciphertext FROM device_push_registrations
		WHERE device_id=$1`, principal.DeviceID).Scan(&ciphertext); err != nil {
		t.Fatal(err)
	}
	if string(ciphertext) == pushToken {
		t.Fatal("push token was stored in plaintext")
	}
	if err = service.DeletePush(ctx, principal); err != nil {
		t.Fatal(err)
	}
	var pushCount int
	if err = pool.QueryRow(ctx, `SELECT count(*) FROM device_push_registrations
		WHERE device_id=$1`, principal.DeviceID).Scan(&pushCount); err != nil || pushCount != 0 {
		t.Fatalf("push registrations after delete = %d, %v", pushCount, err)
	}

	signedPublic := make([]byte, 32)
	oneTimePublic := make([]byte, 32)
	if _, err = rand.Read(signedPublic); err != nil {
		t.Fatal(err)
	}
	if _, err = rand.Read(oneTimePublic); err != nil {
		t.Fatal(err)
	}
	bundle, err := service.PutKeyBundle(ctx, principal, KeyBundleWrite{
		DeviceID:    principal.DeviceID,
		IdentityKey: base64.StdEncoding.EncodeToString(identity),
		SignedPrekey: &SignedPrekey{
			ID: 7, PublicKey: base64.StdEncoding.EncodeToString(signedPublic),
			Signature: base64.StdEncoding.EncodeToString(ed25519.Sign(signingPrivate, signedPublic)),
			ExpiresAt: now.Add(time.Hour),
		},
		OneTimePrekeys: []OneTimePrekey{{
			ID: 8, PublicKey: base64.StdEncoding.EncodeToString(oneTimePublic),
		}},
	})
	if err != nil || bundle.DeviceID != principal.DeviceID || len(bundle.OneTimePrekeys) != 1 {
		t.Fatalf("key bundle = %#v, %v", bundle, err)
	}
	var prekeyCount int
	if err = pool.QueryRow(ctx, `SELECT count(*) FROM prekeys WHERE device_id=$1`,
		principal.DeviceID).Scan(&prekeyCount); err != nil || prekeyCount != 2 {
		t.Fatalf("stored prekeys = %d, %v", prekeyCount, err)
	}

	desktopIdentityKey := make([]byte, 32)
	desktopSigningKey := make([]byte, 32)
	link, err := service.StartDeviceLink(ctx, LinkSession{
		DesktopPublicKey: base64.StdEncoding.EncodeToString(desktopIdentityKey),
		SigningPublicKey: base64.StdEncoding.EncodeToString(desktopSigningKey),
		DesktopName:      "integration desktop",
	})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(link.QRPayload, "identity_key="+base64.RawURLEncoding.EncodeToString(desktopIdentityKey)) ||
		!strings.Contains(link.QRPayload, "signing_key="+base64.RawURLEncoding.EncodeToString(desktopSigningKey)) {
		t.Fatalf("link QR does not bind the desktop public keys: %q", link.QRPayload)
	}
	if _, err = service.ClaimDeviceLink(ctx, LinkClaim{
		SessionID: link.SessionID, ClaimToken: link.ClaimToken,
	}); !errors.Is(err, ErrForbidden) {
		t.Fatalf("unconfirmed link claim error = %v", err)
	}
	wrongSecret := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	if _, err = service.ConfirmDeviceLink(ctx, principal, LinkConfirm{
		SessionID: link.SessionID, QRSecret: wrongSecret,
		Confirmation:        base64.StdEncoding.EncodeToString(make([]byte, ed25519.SignatureSize)),
		WrappedDeviceSecret: base64.StdEncoding.EncodeToString(make([]byte, 32)),
	}); !errors.Is(err, ErrForbidden) {
		t.Fatalf("wrong-secret link confirmation error = %v", err)
	}
	if _, err = pool.Exec(ctx, `UPDATE device_link_sessions SET expires_at=now()-interval '1 second'
		WHERE session_id=$1`, link.SessionID); err != nil {
		t.Fatal(err)
	}
	if _, err = service.ClaimDeviceLink(ctx, LinkClaim{
		SessionID: link.SessionID, ClaimToken: link.ClaimToken,
	}); !errors.Is(err, ErrForbidden) {
		t.Fatalf("expired link claim error = %v", err)
	}

	revokedDeviceID, _ := NewUUID()
	revokedSessionID, _ := NewUUID()
	accessHash := sha256.Sum256([]byte("access-" + revokedSessionID))
	refreshHash := sha256.Sum256([]byte("refresh-" + revokedSessionID))
	if _, err = pool.Exec(ctx, `INSERT INTO devices
		(device_id,user_id,platform,identity_pubkey,signing_pubkey,name,attestation_ok,attested_at)
		VALUES($1,$2,'ios',$3,$4,'revoked target',true,now())`,
		revokedDeviceID, userID, make([]byte, 32), make([]byte, 32)); err != nil {
		t.Fatal(err)
	}
	if _, err = pool.Exec(ctx, `INSERT INTO sessions
		(session_id,device_id,refresh_hash,access_hash,access_expires_at,expires_at)
		VALUES($1,$2,$3,$4,now()+interval '15 minutes',now()+interval '1 day')`,
		revokedSessionID, revokedDeviceID, refreshHash[:], accessHash[:]); err != nil {
		t.Fatal(err)
	}
	if err = service.RevokeDevice(ctx, principal, revokedDeviceID); err != nil {
		t.Fatal(err)
	}
	var deviceRevoked, sessionRevoked bool
	if err = pool.QueryRow(ctx, `SELECT d.revoked_at IS NOT NULL,se.revoked_at IS NOT NULL
		FROM devices d JOIN sessions se ON se.device_id=d.device_id
		WHERE d.device_id=$1`, revokedDeviceID).Scan(&deviceRevoked, &sessionRevoked); err != nil {
		t.Fatal(err)
	}
	if !deviceRevoked || !sessionRevoked {
		t.Fatalf("revoke state: device=%t session=%t", deviceRevoked, sessionRevoked)
	}
}

package phase1

import (
	"context"
	"crypto/ed25519"
	"crypto/mlkem"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/argon2"

	"tima-server/internal/config"
	"tima-server/internal/media"
	"tima-server/internal/push"
)

// devMLKEM768PublicKey is a public-only FIPS 203 ML-KEM-768 fixture generated
// with Go 1.24 crypto/mlkem. No corresponding seed or decapsulation key is
// stored in the repository.
//
//go:embed testdata/dev_mlkem768_public.b64
var devMLKEM768PublicKey string

// testOnlyEscrowSigningSeedLabel is deliberately public, deterministic test
// material. It MUST NEVER be used outside APP_ENV=development/test.
const testOnlyEscrowSigningSeedLabel = "TIMA TEST ONLY escrow signing root v1 - NEVER USE IN PRODUCTION"

// devEd25519PublicKey is the separately pinnable public root derived from the
// test-only seed above.
//
//go:embed testdata/dev_ed25519_public.b64
var devEd25519PublicKey string

var (
	ErrInvalid      = errors.New("invalid request")
	ErrUnauthorized = errors.New("unauthorized")
	ErrForbidden    = errors.New("forbidden")
	ErrNotFound     = errors.New("not found")
	ErrConflict     = errors.New("conflict")
	ErrUnavailable  = errors.New("service unavailable")
)

type Service struct {
	DB                  *pgxpool.Pool
	Environment         string
	TokenPepper         []byte
	OTPPepper           []byte
	DevOTP              string
	EscrowSigner        ed25519.PrivateKey
	EscrowKeyID         string
	EscrowX25519        []byte
	EscrowMLKEM768      []byte
	MediaStore          *media.Store
	MediaPrivateBucket  string
	MediaPublicBucket   string
	MediaStagingBucket  string
	AttestationVerifier AttestationVerifier
	PushTokenKey        []byte
	Now                 func() time.Time
}

func New(db *pgxpool.Pool, cfg config.Config) (*Service, error) {
	var err error
	s := &Service{
		DB: db, Environment: cfg.Environment, TokenPepper: []byte(cfg.TokenPepper),
		OTPPepper: []byte(cfg.OTPPepper), DevOTP: cfg.DevOTP, EscrowKeyID: cfg.EscrowKeyID,
		MediaPrivateBucket: cfg.MediaPrivateBucket,
		MediaPublicBucket:  cfg.MediaPublicBucket,
		MediaStagingBucket: cfg.MediaStagingBucket,
		Now:                time.Now,
	}
	if cfg.Environment == "development" || cfg.Environment == "test" {
		s.AttestationVerifier = NewDevelopmentAttestationVerifier(cfg.DevAttestationKey)
		if cfg.PushTokenKey == "" {
			cfg.PushTokenKey = "ZGV2LXB1c2gtdG9rZW4ta2V5LTMyLWJ5dGVzISEhISE="
		}
	} else {
		s.AttestationVerifier = &HTTPAttestationVerifier{
			URL: cfg.AttestationGatewayURL, BearerToken: cfg.AttestationGatewayToken,
			Client: &http.Client{Timeout: 10 * time.Second},
		}
	}
	s.PushTokenKey, err = push.DecodeKey(cfg.PushTokenKey)
	if err != nil {
		return nil, fmt.Errorf("PUSH_TOKEN_ENCRYPTION_KEY: %w", err)
	}
	if cfg.EscrowSigningKey != "" {
		s.EscrowSigner, err = decodeEd25519Private(cfg.EscrowSigningKey)
		if err != nil {
			return nil, fmt.Errorf("ESCROW_SIGNING_PRIVATE_KEY: %w", err)
		}
		s.EscrowX25519, err = decodeExact(cfg.EscrowX25519Key, 32)
		if err != nil {
			return nil, fmt.Errorf("ESCROW_X25519_PUBLIC_KEY: %w", err)
		}
		s.EscrowMLKEM768, err = decodeMLKEM768Public(cfg.EscrowMLKEMKey)
		if err != nil {
			return nil, errors.New("ESCROW_MLKEM768_PUBLIC_KEY must encode 1184 bytes")
		}
	} else if cfg.Environment == "development" || cfg.Environment == "test" {
		testSeed := sha256.Sum256([]byte(testOnlyEscrowSigningSeedLabel))
		s.EscrowSigner = ed25519.NewKeyFromSeed(testSeed[:])
		pinnedPublic, decodeErr := decodeExact(strings.TrimSpace(devEd25519PublicKey), ed25519.PublicKeySize)
		if decodeErr != nil || !subtleEqual(s.EscrowSigner.Public().(ed25519.PublicKey), pinnedPublic) {
			return nil, errors.New("embedded development Ed25519 public fixture does not match test seed")
		}
		if s.EscrowKeyID == "" {
			s.EscrowKeyID = "dev-ed25519-1"
		}
		x := sha256.Sum256([]byte("tima deterministic development x25519 key"))
		s.EscrowX25519 = x[:]
		s.EscrowMLKEM768, err = decodeMLKEM768Public(strings.TrimSpace(devMLKEM768PublicKey))
		if err != nil {
			return nil, fmt.Errorf("embedded development ML-KEM fixture: %w", err)
		}
	} else {
		return nil, errors.New("escrow configuration unavailable")
	}
	return s, nil
}

type Principal struct {
	UserID, DeviceID, SessionID string
}

type DeviceInput struct {
	Name              string `json:"name"`
	Platform          string `json:"platform"`
	AppVersion        string `json:"app_version,omitempty"`
	IdentityPublicKey string `json:"identity_public_key"`
	SigningPublicKey  string `json:"signing_public_key"`
}

type User struct {
	ID                string    `json:"id"`
	AccountType       string    `json:"account_type"`
	DisplayName       string    `json:"display_name"`
	AccountHomeRegion string    `json:"account_home_region"`
	CreatedAt         time.Time `json:"created_at"`
}

type Device struct {
	ID         string     `json:"id"`
	Name       string     `json:"name"`
	Platform   string     `json:"platform"`
	CreatedAt  time.Time  `json:"created_at"`
	LastSeenAt time.Time  `json:"last_seen_at"`
	Current    bool       `json:"current"`
	RevokedAt  *time.Time `json:"revoked_at,omitempty"`
}

type AuthSession struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	TokenType    string `json:"token_type"`
	ExpiresIn    int64  `json:"expires_in"`
	User         User   `json:"user"`
	Device       Device `json:"device"`
}

func (s *Service) tokenHash(token string) []byte {
	h := sha256.New()
	h.Write(s.TokenPepper)
	h.Write([]byte{0})
	h.Write([]byte(token))
	return h.Sum(nil)
}

func (s *Service) otpHash(phoneHash []byte, otp string) []byte {
	h := sha256.New()
	h.Write(s.OTPPepper)
	h.Write([]byte{0})
	h.Write(phoneHash)
	h.Write([]byte(otp))
	return h.Sum(nil)
}

func phoneHash(phone string) []byte {
	h := sha256.Sum256([]byte(phone))
	return h[:]
}

func passwordHash(password string, salt []byte) []byte {
	const memory, iterations, parallelism = 64 * 1024, 3, 2
	hash := argon2.IDKey([]byte(password), salt, iterations, memory, parallelism, 32)
	return []byte(fmt.Sprintf("$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s", memory, iterations,
		parallelism, base64.RawStdEncoding.EncodeToString(salt), base64.RawStdEncoding.EncodeToString(hash)))
}

func verifyPassword(encoded []byte, password string) bool {
	parts := strings.Split(string(encoded), "$")
	if len(parts) != 6 || parts[1] != "argon2id" || parts[2] != "v=19" {
		return false
	}
	var memory, iterations uint32
	var parallelism uint8
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &memory, &iterations, &parallelism); err != nil ||
		memory < 8*1024 || memory > 256*1024 || iterations < 1 || iterations > 10 ||
		parallelism < 1 || parallelism > 8 {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil || len(salt) != 16 {
		return false
	}
	expected, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil || len(expected) != 32 {
		return false
	}
	got := argon2.IDKey([]byte(password), salt, iterations, memory, parallelism, uint32(len(expected)))
	return subtle.ConstantTimeCompare(got, expected) == 1
}

func subtleEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	var x byte
	for i := range a {
		x |= a[i] ^ b[i]
	}
	return x == 0
}

func randomToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func NewUUID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%s-%s-%s-%s-%s", hex.EncodeToString(b[:4]), hex.EncodeToString(b[4:6]),
		hex.EncodeToString(b[6:8]), hex.EncodeToString(b[8:10]), hex.EncodeToString(b[10:])), nil
}

func decodeExact(value string, n int) ([]byte, error) {
	b, err := base64.StdEncoding.Strict().DecodeString(value)
	if err != nil || len(b) != n {
		return nil, fmt.Errorf("expected base64 encoding of %d bytes", n)
	}
	return b, nil
}

func decodeEd25519Private(value string) (ed25519.PrivateKey, error) {
	b, err := base64.StdEncoding.Strict().DecodeString(value)
	if err != nil {
		return nil, err
	}
	if len(b) == ed25519.SeedSize {
		return ed25519.NewKeyFromSeed(b), nil
	}
	if len(b) != ed25519.PrivateKeySize {
		return nil, errors.New("expected 32-byte seed or 64-byte private key")
	}
	return ed25519.PrivateKey(b), nil
}

func validDevice(d DeviceInput) ([]byte, []byte, error) {
	if strings.TrimSpace(d.Name) == "" || len(d.Name) > 100 ||
		(d.Platform != "android" && d.Platform != "ios" && d.Platform != "windows") {
		return nil, nil, ErrInvalid
	}
	identity, err := decodeExact(d.IdentityPublicKey, 32)
	if err != nil {
		return nil, nil, ErrInvalid
	}
	signing, err := decodeExact(d.SigningPublicKey, ed25519.PublicKeySize)
	if err != nil {
		return nil, nil, ErrInvalid
	}
	return identity, signing, nil
}

func decodeMLKEM768Public(value string) ([]byte, error) {
	public, err := decodeExact(value, 1184)
	if err != nil {
		return nil, err
	}
	if _, err = mlkem.NewEncapsulationKey768(public); err != nil {
		return nil, errors.New("invalid ML-KEM-768 public key")
	}
	return public, nil
}

func (s *Service) Authenticate(ctx context.Context, accessToken, deviceID string) (Principal, error) {
	if accessToken == "" || deviceID == "" {
		return Principal{}, ErrUnauthorized
	}
	var p Principal
	err := s.DB.QueryRow(ctx, `
		SELECT u.user_id, d.device_id, se.session_id
		FROM sessions se JOIN devices d ON d.device_id=se.device_id
		JOIN users u ON u.user_id=d.user_id
		WHERE se.access_hash=$1 AND d.device_id=$2 AND se.revoked_at IS NULL
		  AND d.revoked_at IS NULL AND se.access_expires_at>now()`,
		s.tokenHash(accessToken), deviceID).Scan(&p.UserID, &p.DeviceID, &p.SessionID)
	if err != nil {
		return Principal{}, ErrUnauthorized
	}
	_, _ = s.DB.Exec(ctx, `UPDATE devices SET last_seen=now() WHERE device_id=$1`, deviceID)
	return p, nil
}

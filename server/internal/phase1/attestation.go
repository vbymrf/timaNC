package phase1

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type IOSAttestation struct {
	KeyID     string `json:"key_id"`
	Assertion string `json:"assertion"`
	Challenge string `json:"challenge"`
}

type AndroidIntegrity struct {
	IntegrityToken string `json:"integrity_token"`
	Nonce          string `json:"nonce"`
}

type AttestationResult struct {
	AttestationToken string    `json:"attestation_token"`
	ExpiresAt        time.Time `json:"expires_at"`
	Verdict          string    `json:"verdict"`
}

type AttestationVerifier interface {
	VerifyIOS(context.Context, IOSAttestation, []byte) (string, error)
	VerifyAndroid(context.Context, AndroidIntegrity, []byte) (string, error)
}

type developmentAttestationVerifier struct {
	secret []byte
}

type HTTPAttestationVerifier struct {
	URL         string
	BearerToken string
	Client      *http.Client
}

func (v *HTTPAttestationVerifier) VerifyIOS(
	ctx context.Context,
	in IOSAttestation,
	challenge []byte,
) (string, error) {
	return v.verify(ctx, "ios", in, challenge)
}

func (v *HTTPAttestationVerifier) VerifyAndroid(
	ctx context.Context,
	in AndroidIntegrity,
	challenge []byte,
) (string, error) {
	return v.verify(ctx, "android", in, challenge)
}

func (v *HTTPAttestationVerifier) verify(
	ctx context.Context,
	platform string,
	proof any,
	challenge []byte,
) (string, error) {
	if v.URL == "" || v.BearerToken == "" {
		return "", ErrUnavailable
	}
	body, err := json.Marshal(map[string]any{
		"platform":           platform,
		"proof":              proof,
		"expected_challenge": base64.StdEncoding.EncodeToString(challenge),
	})
	if err != nil {
		return "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, v.URL, bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	request.Header.Set("Authorization", "Bearer "+v.BearerToken)
	request.Header.Set("Content-Type", "application/json")
	client := v.Client
	if client == nil {
		client = http.DefaultClient
	}
	response, err := client.Do(request)
	if err != nil {
		return "", fmt.Errorf("%w: attestation vendor unavailable", ErrUnavailable)
	}
	defer response.Body.Close()
	if response.StatusCode >= 500 {
		_, _ = io.Copy(io.Discard, response.Body)
		return "", fmt.Errorf("%w: attestation vendor unavailable", ErrUnavailable)
	}
	if response.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, response.Body)
		return "", ErrForbidden
	}
	var result struct {
		Verdict string `json:"verdict"`
	}
	decoder := json.NewDecoder(io.LimitReader(response.Body, 4097))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&result); err != nil ||
		(result.Verdict != "trusted" && result.Verdict != "limited") {
		return "", errors.New("invalid attestation gateway response")
	}
	if strings.TrimSpace(result.Verdict) != result.Verdict {
		return "", errors.New("invalid attestation gateway verdict")
	}
	return result.Verdict, nil
}

func NewDevelopmentAttestationVerifier(secret string) AttestationVerifier {
	return &developmentAttestationVerifier{secret: []byte(secret)}
}

func (v *developmentAttestationVerifier) VerifyIOS(
	_ context.Context,
	in IOSAttestation,
	challenge []byte,
) (string, error) {
	if in.KeyID == "" || !v.valid("ios", in.KeyID, challenge, in.Assertion) {
		return "", ErrForbidden
	}
	return "trusted", nil
}

func (v *developmentAttestationVerifier) VerifyAndroid(
	_ context.Context,
	in AndroidIntegrity,
	challenge []byte,
) (string, error) {
	if !v.valid("android", "", challenge, in.IntegrityToken) {
		return "", ErrForbidden
	}
	return "trusted", nil
}

func (v *developmentAttestationVerifier) valid(platform, keyID string, challenge []byte, proof string) bool {
	decoded, err := base64.StdEncoding.DecodeString(proof)
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, v.secret)
	mac.Write([]byte(platform))
	mac.Write([]byte{0})
	mac.Write([]byte(keyID))
	mac.Write([]byte{0})
	mac.Write(challenge)
	return hmac.Equal(decoded, mac.Sum(nil))
}

func (s *Service) VerifyIOSAttestation(ctx context.Context, in IOSAttestation) (AttestationResult, error) {
	challenge, err := decodeExact(in.Challenge, sha256.Size)
	if err != nil || s.AttestationVerifier == nil {
		if s.AttestationVerifier == nil {
			return AttestationResult{}, ErrUnavailable
		}
		return AttestationResult{}, ErrInvalid
	}
	verdict, err := s.AttestationVerifier.VerifyIOS(ctx, in, challenge)
	if err != nil {
		return AttestationResult{}, err
	}
	return s.storeAttestation(ctx, "ios", challenge, verdict)
}

func (s *Service) VerifyAndroidAttestation(
	ctx context.Context,
	in AndroidIntegrity,
) (AttestationResult, error) {
	challenge, err := decodeExact(in.Nonce, sha256.Size)
	if err != nil || s.AttestationVerifier == nil {
		if s.AttestationVerifier == nil {
			return AttestationResult{}, ErrUnavailable
		}
		return AttestationResult{}, ErrInvalid
	}
	verdict, err := s.AttestationVerifier.VerifyAndroid(ctx, in, challenge)
	if err != nil {
		return AttestationResult{}, err
	}
	return s.storeAttestation(ctx, "android", challenge, verdict)
}

func (s *Service) storeAttestation(
	ctx context.Context,
	platform string,
	challenge []byte,
	verdict string,
) (AttestationResult, error) {
	if verdict != "trusted" && verdict != "limited" {
		return AttestationResult{}, ErrForbidden
	}
	token, err := randomToken()
	if err != nil {
		return AttestationResult{}, err
	}
	expires := s.Now().UTC().Add(24 * time.Hour)
	challengeHash := sha256.Sum256(challenge)
	if _, err = s.DB.Exec(ctx, `INSERT INTO device_attestation_tokens
		(token_hash,platform,challenge_hash,verdict,expires_at) VALUES($1,$2,$3,$4,$5)`,
		s.tokenHash(token), platform, challengeHash[:], verdict, expires); err != nil {
		return AttestationResult{}, err
	}
	return AttestationResult{AttestationToken: token, ExpiresAt: expires, Verdict: verdict}, nil
}

func (s *Service) requireAttestation(
	ctx context.Context,
	token, platform string,
	requestBody []byte,
) error {
	if platform == "windows" {
		return ErrForbidden
	}
	challenge := sha256.Sum256(requestBody)
	expectedChallengeHash := sha256.Sum256(challenge[:])
	var verdict string
	var storedChallengeHash []byte
	err := s.DB.QueryRow(ctx, `SELECT verdict,challenge_hash FROM device_attestation_tokens
		WHERE token_hash=$1 AND platform=$2 AND expires_at>now()`,
		s.tokenHash(token), platform).Scan(&verdict, &storedChallengeHash)
	if err != nil || verdict != "trusted" || !subtleEqual(storedChallengeHash, expectedChallengeHash[:]) {
		return ErrForbidden
	}
	return nil
}

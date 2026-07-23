package phase1

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestDevelopmentAttestationVerifierBindsProofToPlatformKeyAndChallenge(t *testing.T) {
	verifier := NewDevelopmentAttestationVerifier("test-secret")
	challenge := sha256.Sum256([]byte("registration request"))
	proof := developmentProof("test-secret", "ios", "key-1", challenge[:])

	verdict, err := verifier.VerifyIOS(context.Background(), IOSAttestation{
		KeyID: "key-1", Assertion: proof,
	}, challenge[:])
	if err != nil || verdict != "trusted" {
		t.Fatalf("valid proof: verdict=%q err=%v", verdict, err)
	}
	_, err = verifier.VerifyIOS(context.Background(), IOSAttestation{
		KeyID: "key-2", Assertion: proof,
	}, challenge[:])
	if !errors.Is(err, ErrForbidden) {
		t.Fatalf("proof replayed for another key: %v", err)
	}
}

func TestDevelopmentAndroidProofCannotBeReplayedAsIOS(t *testing.T) {
	verifier := NewDevelopmentAttestationVerifier("test-secret")
	challenge := sha256.Sum256([]byte("login request"))
	proof := developmentProof("test-secret", "android", "", challenge[:])

	verdict, err := verifier.VerifyAndroid(context.Background(), AndroidIntegrity{
		IntegrityToken: proof,
	}, challenge[:])
	if err != nil || verdict != "trusted" {
		t.Fatalf("valid proof: verdict=%q err=%v", verdict, err)
	}
	_, err = verifier.VerifyIOS(context.Background(), IOSAttestation{
		KeyID: "key-1", Assertion: proof,
	}, challenge[:])
	if !errors.Is(err, ErrForbidden) {
		t.Fatalf("cross-platform proof replay: %v", err)
	}
}

func TestHTTPAttestationVerifierBindsGatewayRequestAndVerdict(t *testing.T) {
	challenge := sha256.Sum256([]byte("request"))
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer gateway-secret" {
			t.Error("missing gateway authorization")
		}
		var body struct {
			Platform          string `json:"platform"`
			ExpectedChallenge string `json:"expected_challenge"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		if body.Platform != "android" ||
			body.ExpectedChallenge != base64.StdEncoding.EncodeToString(challenge[:]) {
			t.Errorf("gateway body = %#v", body)
		}
		_, _ = w.Write([]byte(`{"verdict":"trusted"}`))
	}))
	defer server.Close()
	verifier := &HTTPAttestationVerifier{
		URL: server.URL, BearerToken: "gateway-secret", Client: server.Client(),
	}
	verdict, err := verifier.VerifyAndroid(
		context.Background(),
		AndroidIntegrity{IntegrityToken: "vendor-proof"},
		challenge[:],
	)
	if err != nil || verdict != "trusted" {
		t.Fatalf("verdict=%q err=%v", verdict, err)
	}
}

func developmentProof(secret, platform, keyID string, challenge []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(platform))
	mac.Write([]byte{0})
	mac.Write([]byte(keyID))
	mac.Write([]byte{0})
	mac.Write(challenge)
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

package push

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestTokenEncryptionRoundTrip(t *testing.T) {
	key, err := DecodeKey(base64.StdEncoding.EncodeToString(make([]byte, 32)))
	if err != nil {
		t.Fatal(err)
	}
	ciphertext, err := EncryptToken(key, "private-device-token")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(ciphertext), "private-device-token") {
		t.Fatal("ciphertext contains plaintext token")
	}
	token, err := DecryptToken(key, ciphertext)
	if err != nil || token != "private-device-token" {
		t.Fatalf("decrypt = %q, %v", token, err)
	}
}

func TestGenericMessagePushContainsNoPrivateContent(t *testing.T) {
	var received map[string]any
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer gateway-secret" {
			t.Error("missing gateway authorization")
		}
		if err := json.NewDecoder(r.Body).Decode(&received); err != nil {
			t.Error(err)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	sender := &HTTPSender{
		URL: server.URL, BearerToken: "gateway-secret", Client: server.Client(),
	}
	err := sender.Send(context.Background(), "fcm", "device-token", Message{
		Type: "message", ChatID: "chat-id", Preview: "Новое сообщение",
		Encrypted: true, CollapseKey: "chat:chat-id",
	})
	if err != nil {
		t.Fatal(err)
	}
	encoded, _ := json.Marshal(received["payload"])
	text := string(encoded)
	for _, forbidden := range []string{"sender", "message_id", "caption", "body", "plaintext"} {
		if strings.Contains(text, forbidden) {
			t.Fatalf("generic payload leaks forbidden field %q: %s", forbidden, text)
		}
	}
}

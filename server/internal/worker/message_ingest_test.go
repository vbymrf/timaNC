package worker

import (
	"context"
	"encoding/base64"
	"errors"
	"testing"

	"tima-server/internal/push"
)

type fallbackSender struct {
	providers []string
	failAll   bool
}

func (s *fallbackSender) Send(_ context.Context, provider, _ string, _ push.Message) error {
	s.providers = append(s.providers, provider)
	if s.failAll || provider == "fcm" {
		return errors.New("vendor unavailable")
	}
	return nil
}

func TestSendPushRoutesUsesFallback(t *testing.T) {
	key, err := push.DecodeKey(base64.StdEncoding.EncodeToString(make([]byte, 32)))
	if err != nil {
		t.Fatal(err)
	}
	primary, _ := push.EncryptToken(key, "fcm-device-token")
	fallback, _ := push.EncryptToken(key, "https://push.example/endpoint")
	sender := &fallbackSender{}

	delivered := sendPushRoutes(context.Background(), sender, key, "device-id", []pushRoute{
		{provider: "fcm", tokenCiphertext: primary},
		{provider: "unifiedpush", tokenCiphertext: fallback},
	}, push.Message{})

	if !delivered {
		t.Fatal("fallback route was not delivered")
	}
	if len(sender.providers) != 2 ||
		sender.providers[0] != "fcm" ||
		sender.providers[1] != "unifiedpush" {
		t.Fatalf("provider attempts = %#v", sender.providers)
	}
}

func TestSendPushRoutesSoftFails(t *testing.T) {
	key, err := push.DecodeKey(base64.StdEncoding.EncodeToString(make([]byte, 32)))
	if err != nil {
		t.Fatal(err)
	}
	primary, _ := push.EncryptToken(key, "fcm-device-token")
	fallback, _ := push.EncryptToken(key, "https://push.example/endpoint")
	sender := &fallbackSender{failAll: true}

	if sendPushRoutes(context.Background(), sender, key, "device-id", []pushRoute{
		{provider: "fcm", tokenCiphertext: primary},
		{provider: "unifiedpush", tokenCiphertext: fallback},
	}, push.Message{}) {
		t.Fatal("failed routes unexpectedly delivered")
	}
	if len(sender.providers) != 2 {
		t.Fatalf("provider attempts = %#v, want both routes attempted", sender.providers)
	}
}

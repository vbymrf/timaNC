package pushgateway

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"tima-server/internal/push"
)

const testChatID = "7b03dcdc-2570-4b24-94c8-33408efaf726"

type recordingAdapter struct {
	called bool
}

func (a *recordingAdapter) Send(_ context.Context, _ string, _ push.Message) error {
	a.called = true
	return nil
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return f(request)
}

type resolverFunc func(context.Context, string) ([]net.IPAddr, error)

func (f resolverFunc) LookupIPAddr(ctx context.Context, hostname string) ([]net.IPAddr, error) {
	return f(ctx, hostname)
}

func publicResolver(ipAddresses ...string) Resolver {
	return resolverFunc(func(_ context.Context, _ string) ([]net.IPAddr, error) {
		addresses := make([]net.IPAddr, 0, len(ipAddresses))
		for _, value := range ipAddresses {
			addresses = append(addresses, net.IPAddr{IP: net.ParseIP(value)})
		}
		return addresses, nil
	})
}

func TestGatewayAuthenticatesAndAcceptsGenericPayload(t *testing.T) {
	adapter := &recordingAdapter{}
	handler := (&Gateway{
		BearerToken: "secret",
		Adapters:    map[string]Adapter{"unifiedpush": adapter},
	}).Handler()
	body := `{"provider":"unifiedpush","token":"https://push.example/endpoint",` +
		`"payload":{"type":"message","chat_id":"` + testChatID + `","preview":"Новое сообщение",` +
		`"encrypted":true,"collapse_key":"chat:` + testChatID + `"}}`
	request := httptest.NewRequest(http.MethodPost, "/v1/send", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer secret")
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusNoContent || !adapter.called {
		t.Fatalf("status = %d, adapter called = %v", response.Code, adapter.called)
	}
}

func TestGatewayRejectsPrivatePayloadFields(t *testing.T) {
	handler := (&Gateway{BearerToken: "secret", Adapters: map[string]Adapter{}}).Handler()
	body := `{"provider":"unifiedpush","token":"https://push.example/endpoint",` +
		`"payload":{"type":"message","chat_id":"` + testChatID + `","preview":"Новое сообщение",` +
		`"encrypted":true,"collapse_key":"chat:` + testChatID + `","body":"secret"}}`
	request := httptest.NewRequest(http.MethodPost, "/v1/send", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer secret")
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", response.Code)
	}
}

func TestGatewayFailsClosedForUnconfiguredVendor(t *testing.T) {
	handler := (&Gateway{BearerToken: "secret", Adapters: map[string]Adapter{}}).Handler()
	payload, _ := json.Marshal(sendRequest{
		Provider: "fcm",
		Token:    "vendor-device-token",
		Payload: push.Message{
			Type: "message", ChatID: testChatID, Preview: "Новое сообщение",
			Encrypted: true, CollapseKey: "chat:" + testChatID,
		},
	})
	request := httptest.NewRequest(http.MethodPost, "/v1/send", strings.NewReader(string(payload)))
	request.Header.Set("Authorization", "Bearer secret")
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", response.Code)
	}
}

func TestGatewayRequiresAuthentication(t *testing.T) {
	handler := (&Gateway{BearerToken: "secret"}).Handler()
	request := httptest.NewRequest(http.MethodPost, "/v1/send", strings.NewReader(`{}`))
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", response.Code)
	}
}

func TestUnifiedPushAdapterUsesEndpointWithoutVendorCredentials(t *testing.T) {
	called := false
	client := &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		called = true
		if request.URL.String() != "https://push.example/endpoint" {
			t.Fatalf("URL = %s", request.URL)
		}
		if request.Header.Get("Authorization") != "" {
			t.Fatal("unexpected vendor authorization")
		}
		return &http.Response{
			StatusCode: http.StatusNoContent,
			Body:       http.NoBody,
			Header:     make(http.Header),
		}, nil
	})}
	adapter := &HTTPAdapter{
		Client: client, Resolver: publicResolver("93.184.216.34"),
		Transport: client.Transport, EndpointFromToken: true, Provider: "unifiedpush",
	}

	if err := adapter.Send(context.Background(), "https://push.example/endpoint", push.Message{}); err != nil {
		t.Fatal(err)
	}
	if !called {
		t.Fatal("UnifiedPush endpoint was not called")
	}
}

func TestUnifiedPushAdapterRejectsPrivateEndpoint(t *testing.T) {
	adapter := &HTTPAdapter{EndpointFromToken: true, Provider: "unifiedpush"}
	if err := adapter.Send(context.Background(), "https://127.0.0.1/push", push.Message{}); !errors.Is(err, ErrInvalid) {
		t.Fatalf("error = %v, want ErrInvalid", err)
	}
}

func TestGatewayRejectsNonUUIDChatID(t *testing.T) {
	adapter := &recordingAdapter{}
	handler := (&Gateway{
		BearerToken: "secret",
		Adapters:    map[string]Adapter{"unifiedpush": adapter},
	}).Handler()
	body := `{"provider":"unifiedpush","token":"https://push.example/endpoint",` +
		`"payload":{"type":"message","chat_id":"chat-id","preview":"Новое сообщение",` +
		`"encrypted":true,"collapse_key":"chat:chat-id"}}`
	request := httptest.NewRequest(http.MethodPost, "/v1/send", strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer secret")
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusBadRequest || adapter.called {
		t.Fatalf("status = %d, adapter called = %v", response.Code, adapter.called)
	}
}

func TestUnifiedPushRejectsDNSResolvingPrivateOrMixedTargets(t *testing.T) {
	for name, addresses := range map[string][]string{
		"private": {"10.0.0.7"},
		"mixed":   {"93.184.216.34", "127.0.0.1"},
	} {
		t.Run(name, func(t *testing.T) {
			called := false
			adapter := &HTTPAdapter{
				Resolver: publicResolver(addresses...),
				Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
					called = true
					return nil, errors.New("must not send")
				}),
				EndpointFromToken: true,
				Provider:          "unifiedpush",
			}
			err := adapter.Send(context.Background(), "https://push.example/endpoint", push.Message{})
			if !errors.Is(err, ErrInvalid) || called {
				t.Fatalf("error = %v, transport called = %v", err, called)
			}
		})
	}
}

func TestUnifiedPushPinsResolvedPublicAddress(t *testing.T) {
	var dialed string
	adapter := &HTTPAdapter{
		Resolver: publicResolver("93.184.216.34"),
		DialContext: func(_ context.Context, _, address string) (net.Conn, error) {
			dialed = address
			return nil, errors.New("test dial stopped")
		},
		EndpointFromToken: true,
		Provider:          "unifiedpush",
	}

	err := adapter.Send(context.Background(), "https://push.example/endpoint", push.Message{})

	if err == nil {
		t.Fatal("send unexpectedly succeeded")
	}
	if dialed != "93.184.216.34:443" {
		t.Fatalf("dialed = %q, want pinned public address", dialed)
	}
}

func TestUnifiedPushDoesNotFollowRedirects(t *testing.T) {
	calls := 0
	adapter := &HTTPAdapter{
		Resolver: publicResolver("93.184.216.34"),
		Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
			calls++
			return &http.Response{
				StatusCode: http.StatusFound,
				Header:     http.Header{"Location": []string{"https://redirect.example/private"}},
				Body:       http.NoBody,
			}, nil
		}),
		EndpointFromToken: true,
		Provider:          "unifiedpush",
	}

	err := adapter.Send(context.Background(), "https://push.example/endpoint", push.Message{})

	if err == nil || calls != 1 {
		t.Fatalf("error = %v, transport calls = %d", err, calls)
	}
	if !strings.Contains(err.Error(), fmt.Sprint(http.StatusFound)) {
		t.Fatalf("error = %v, want redirect status", err)
	}
}

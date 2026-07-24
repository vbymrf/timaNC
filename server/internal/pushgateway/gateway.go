package pushgateway

import (
	"context"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"tima-server/internal/push"
)

const maxRequestBytes = 16 << 10

var (
	ErrUnavailable = errors.New("push provider is not configured")
	ErrInvalid     = errors.New("invalid push request")
)

type Adapter interface {
	Send(context.Context, string, push.Message) error
}

type Gateway struct {
	BearerToken string
	Adapters    map[string]Adapter
}

type sendRequest struct {
	Provider string       `json:"provider"`
	Token    string       `json:"token"`
	Payload  push.Message `json:"payload"`
}

func (g *Gateway) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("POST /v1/send", g.send)
	return mux
}

func (g *Gateway) send(w http.ResponseWriter, r *http.Request) {
	if !validBearer(r.Header.Get("Authorization"), g.BearerToken) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxRequestBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	var request sendRequest
	if err := decoder.Decode(&request); err != nil {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	if !validRequest(request) {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	adapter := g.Adapters[request.Provider]
	if adapter == nil {
		http.Error(w, ErrUnavailable.Error(), http.StatusServiceUnavailable)
		return
	}
	if err := adapter.Send(r.Context(), request.Token, request.Payload); err != nil {
		status := http.StatusBadGateway
		if errors.Is(err, ErrUnavailable) {
			status = http.StatusServiceUnavailable
		} else if errors.Is(err, ErrInvalid) {
			status = http.StatusBadRequest
		}
		http.Error(w, "push delivery failed", status)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func validBearer(header, expected string) bool {
	if expected == "" || !strings.HasPrefix(header, "Bearer ") {
		return false
	}
	got := strings.TrimPrefix(header, "Bearer ")
	return len(got) == len(expected) &&
		subtle.ConstantTimeCompare([]byte(got), []byte(expected)) == 1
}

func validRequest(request sendRequest) bool {
	if request.Provider != "unifiedpush" && request.Provider != "fcm" &&
		request.Provider != "apns" && request.Provider != "wns" {
		return false
	}
	message := request.Payload
	return strings.TrimSpace(request.Token) != "" &&
		len(request.Token) <= 4096 &&
		message.Type == "message" &&
		validUUID(message.ChatID) &&
		message.Preview == "Новое сообщение" &&
		message.Encrypted &&
		message.CollapseKey == "chat:"+message.ChatID
}

func validUUID(value string) bool {
	if len(value) != 36 ||
		value[8] != '-' || value[13] != '-' || value[18] != '-' || value[23] != '-' {
		return false
	}
	compact := strings.ReplaceAll(value, "-", "")
	decoded := make([]byte, 16)
	if _, err := hex.Decode(decoded, []byte(compact)); err != nil {
		return false
	}
	version := decoded[6] >> 4
	return version >= 1 && version <= 8 && decoded[8]&0xc0 == 0x80
}

type Resolver interface {
	LookupIPAddr(context.Context, string) ([]net.IPAddr, error)
}

type DialContextFunc func(context.Context, string, string) (net.Conn, error)

type HTTPAdapter struct {
	Client            *http.Client
	Resolver          Resolver
	DialContext       DialContextFunc
	Transport         http.RoundTripper
	URL               string
	BearerToken       string
	EndpointFromToken bool
	Provider          string
	MaxAttempts       int
}

func (a *HTTPAdapter) Send(ctx context.Context, token string, message push.Message) error {
	endpoint := a.URL
	if a.EndpointFromToken {
		endpoint = token
	}
	parsed, err := url.ParseRequestURI(endpoint)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil {
		return ErrInvalid
	}
	pinnedIP, err := resolvePublic(ctx, parsed.Hostname(), a.Resolver)
	if err != nil {
		return ErrInvalid
	}
	if !a.EndpointFromToken && (a.URL == "" || a.BearerToken == "") {
		return ErrUnavailable
	}

	var body any = message
	switch a.Provider {
	case "fcm":
		body = map[string]any{"token": token, "data": message}
	case "apns":
		parsed.Path = strings.TrimRight(parsed.Path, "/") + "/3/device/" + url.PathEscape(token)
		endpoint = parsed.String()
		body = map[string]any{
			"aps":  map[string]int{"content-available": 1},
			"data": message,
		}
	}
	encoded, err := json.Marshal(body)
	if err != nil {
		return err
	}
	client := outboundClient(a.Client, a.Transport, a.DialContext, parsed, pinnedIP)
	defer client.CloseIdleConnections()
	attempts := a.MaxAttempts
	if attempts < 1 {
		attempts = 1
	}
	var lastErr error
	for attempt := 0; attempt < attempts; attempt++ {
		request, requestErr := http.NewRequestWithContext(
			ctx, http.MethodPost, endpoint, strings.NewReader(string(encoded)),
		)
		if requestErr != nil {
			return requestErr
		}
		request.Header.Set("Content-Type", "application/json")
		if a.BearerToken != "" {
			request.Header.Set("Authorization", "Bearer "+a.BearerToken)
		}
		response, sendErr := client.Do(request)
		if sendErr == nil {
			_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
			_ = response.Body.Close()
			if response.StatusCode >= 200 && response.StatusCode <= 299 {
				return nil
			}
			lastErr = fmt.Errorf("provider returned status %d", response.StatusCode)
			if response.StatusCode != http.StatusRequestTimeout &&
				response.StatusCode != http.StatusTooManyRequests &&
				response.StatusCode < 500 {
				return lastErr
			}
		} else {
			lastErr = sendErr
		}
		if attempt+1 < attempts {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(100 * time.Millisecond):
			}
		}
	}
	return lastErr
}

func resolvePublic(ctx context.Context, hostname string, resolver Resolver) (net.IP, error) {
	hostname = strings.TrimSpace(strings.ToLower(hostname))
	if hostname == "" || hostname == "localhost" {
		return nil, ErrInvalid
	}
	if ip := net.ParseIP(hostname); ip != nil {
		if !publicIP(ip) {
			return nil, ErrInvalid
		}
		return ip, nil
	}
	if resolver == nil {
		resolver = net.DefaultResolver
	}
	addresses, err := resolver.LookupIPAddr(ctx, hostname)
	if err != nil || len(addresses) == 0 {
		return nil, ErrInvalid
	}
	for _, address := range addresses {
		if !publicIP(address.IP) {
			return nil, ErrInvalid
		}
	}
	return addresses[0].IP, nil
}

func publicIP(ip net.IP) bool {
	return ip != nil && ip.IsGlobalUnicast() && !ip.IsPrivate() &&
		!ip.IsLoopback() && !ip.IsLinkLocalUnicast() &&
		!ip.IsLinkLocalMulticast() && !ip.IsUnspecified() &&
		!inCIDR(ip, "100.64.0.0/10") &&
		!inCIDR(ip, "192.0.0.0/24") &&
		!inCIDR(ip, "192.0.2.0/24") &&
		!inCIDR(ip, "198.18.0.0/15") &&
		!inCIDR(ip, "198.51.100.0/24") &&
		!inCIDR(ip, "203.0.113.0/24") &&
		!inCIDR(ip, "2001:db8::/32")
}

func inCIDR(ip net.IP, block string) bool {
	_, network, err := net.ParseCIDR(block)
	return err == nil && network.Contains(ip)
}

func outboundClient(
	base *http.Client,
	transport http.RoundTripper,
	dial DialContextFunc,
	endpoint *url.URL,
	pinnedIP net.IP,
) *http.Client {
	client := http.Client{Timeout: 5 * time.Second}
	if base != nil {
		client = *base
	}
	client.CheckRedirect = func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}
	if transport != nil {
		client.Transport = transport
		return &client
	}
	if dial == nil {
		dialer := &net.Dialer{Timeout: 5 * time.Second, KeepAlive: 30 * time.Second}
		dial = dialer.DialContext
	}
	port := endpoint.Port()
	if port == "" {
		port = strconv.Itoa(443)
	}
	pinnedAddress := net.JoinHostPort(pinnedIP.String(), port)
	baseTransport := http.DefaultTransport.(*http.Transport).Clone()
	baseTransport.Proxy = nil
	baseTransport.DialContext = func(ctx context.Context, network, _ string) (net.Conn, error) {
		return dial(ctx, network, pinnedAddress)
	}
	client.Transport = baseTransport
	return &client
}

package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"tima-server/internal/phase1"
	"tima-server/internal/readiness"
)

type readinessStub struct {
	ready   bool
	results []readiness.Result
}

func (s readinessStub) Check(context.Context) (bool, []readiness.Result) {
	return s.ready, s.results
}

func TestHealthIsIndependentFromReadiness(t *testing.T) {
	handler := New(readinessStub{ready: false})
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d", response.Code)
	}
	if !strings.Contains(response.Body.String(), `"alive"`) {
		t.Fatalf("body = %q", response.Body.String())
	}
}

func TestReadinessFailure(t *testing.T) {
	handler := New(readinessStub{
		ready: false,
		results: []readiness.Result{
			{Name: "redis", Ready: false, Error: "connection refused"},
		},
	})
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/readyz", nil))

	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d", response.Code)
	}
	if !strings.Contains(response.Body.String(), `"not_ready"`) {
		t.Fatalf("body = %q", response.Body.String())
	}
}

func TestMetrics(t *testing.T) {
	handler := New(readinessStub{ready: true})
	handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/readyz", nil))
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/metrics", nil))

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d", response.Code)
	}
	for _, metric := range []string{
		"tima_http_requests_total 2",
		"tima_ready 1",
		"tima_process_uptime_seconds",
		"tima_message_send_ack_seconds_bucket{le=\"0.8\"}",
	} {
		if !strings.Contains(response.Body.String(), metric) {
			t.Errorf("metrics do not contain %q:\n%s", metric, response.Body.String())
		}
	}
}

func TestMessageSendMetricPathClassification(t *testing.T) {
	if !isMessageSendPath("/v1/chats/chat-id/messages") {
		t.Fatal("canonical send route was not classified")
	}
	for _, path := range []string{
		"/v1/chats/chat-id/messages/1/revisions",
		"/v1/groups/group-id/messages",
		"/v1/chats/chat-id/message-reservations",
	} {
		if isMessageSendPath(path) {
			t.Fatalf("non-send route %q was classified as message send", path)
		}
	}
}

func TestReservationRouteMatchesContract(t *testing.T) {
	handler := New(readinessStub{ready: true}, &phase1.Service{})

	exact := httptest.NewRecorder()
	handler.ServeHTTP(exact, httptest.NewRequest(http.MethodPost,
		"/v1/chats/00000000-0000-4000-8000-000000000000/message-reservations", nil))
	if exact.Code != http.StatusUnauthorized {
		t.Fatalf("contract route status = %d, want authentication challenge", exact.Code)
	}

	old := httptest.NewRecorder()
	handler.ServeHTTP(old, httptest.NewRequest(http.MethodPost,
		"/v1/chats/00000000-0000-4000-8000-000000000000/messages/reservations", nil))
	if old.Code != http.StatusNotFound {
		t.Fatalf("obsolete route status = %d, want 404", old.Code)
	}
}

func TestPhase1PublicRoutesRejectUnknownJSONFields(t *testing.T) {
	handler := New(readinessStub{ready: true}, &phase1.Service{})
	for _, test := range []struct {
		name string
		path string
		body string
	}{
		{
			name: "login",
			path: "/v1/auth/login",
			body: `{"phone":"+79990000000","password":"password","device":{},"unexpected":true}`,
		},
		{
			name: "ios attestation",
			path: "/v1/verify/attestation/ios",
			body: `{"key_id":"key","assertion":"proof","challenge":"challenge","unexpected":true}`,
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			response := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodPost, test.path, strings.NewReader(test.body))
			request.Header.Set("Content-Type", "application/json")

			handler.ServeHTTP(response, request)

			if response.Code != http.StatusBadRequest {
				t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
			}
			if !strings.Contains(response.Body.String(), `"INVALID_REQUEST"`) {
				t.Fatalf("body = %q", response.Body.String())
			}
		})
	}
}

func TestCriticalAuthenticatedPhase1RoutesRequireBearerToken(t *testing.T) {
	handler := New(readinessStub{ready: true}, &phase1.Service{})
	for _, test := range []struct {
		method string
		path   string
	}{
		{http.MethodPatch, "/v1/users/me"},
		{http.MethodDelete, "/v1/devices/00000000-0000-4000-8000-000000000000"},
		{http.MethodPut, "/v1/devices/push"},
		{http.MethodDelete, "/v1/devices/push"},
		{http.MethodPut, "/v1/keys/bundle"},
		{http.MethodGet, "/v1/chats"},
		{http.MethodPost, "/v1/chats/00000000-0000-4000-8000-000000000000/read"},
	} {
		response := httptest.NewRecorder()
		request := httptest.NewRequest(test.method, test.path, strings.NewReader(`{}`))

		handler.ServeHTTP(response, request)

		if response.Code != http.StatusUnauthorized {
			t.Errorf("%s %s: status = %d, body = %s",
				test.method, test.path, response.Code, response.Body.String())
		}
	}
}

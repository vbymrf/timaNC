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
	for _, metric := range []string{"tima_http_requests_total 2", "tima_ready 1", "tima_process_uptime_seconds"} {
		if !strings.Contains(response.Body.String(), metric) {
			t.Errorf("metrics do not contain %q:\n%s", metric, response.Body.String())
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

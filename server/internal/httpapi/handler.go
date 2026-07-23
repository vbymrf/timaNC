package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"sync/atomic"
	"time"

	"tima-server/internal/readiness"
)

type ReadinessChecker interface {
	Check(context.Context) (bool, []readiness.Result)
}

type Handler struct {
	mux      http.Handler
	requests atomic.Uint64
	ready    atomic.Bool
	started  time.Time
}

func New(checker ReadinessChecker) *Handler {
	h := &Handler{started: time.Now()}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", h.health)
	mux.HandleFunc("GET /readyz", h.readiness(checker))
	mux.HandleFunc("GET /metrics", h.metrics)
	h.mux = mux
	return h
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h.requests.Add(1)
	h.mux.ServeHTTP(w, r)
}

func (h *Handler) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "alive"})
}

func (h *Handler) readiness(checker ReadinessChecker) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ready, dependencies := checker.Check(r.Context())
		h.ready.Store(ready)
		status := http.StatusOK
		state := "ready"
		if !ready {
			status = http.StatusServiceUnavailable
			state = "not_ready"
		}
		writeJSON(w, status, struct {
			Status       string             `json:"status"`
			Dependencies []readiness.Result `json:"dependencies"`
		}{Status: state, Dependencies: dependencies})
	}
}

func (h *Handler) metrics(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	ready := 0
	if h.ready.Load() {
		ready = 1
	}
	fmt.Fprintf(w, "# HELP tima_http_requests_total Total HTTP requests received.\n")
	fmt.Fprintf(w, "# TYPE tima_http_requests_total counter\n")
	fmt.Fprintf(w, "tima_http_requests_total %d\n", h.requests.Load())
	fmt.Fprintf(w, "# HELP tima_ready Result of the latest readiness check.\n")
	fmt.Fprintf(w, "# TYPE tima_ready gauge\n")
	fmt.Fprintf(w, "tima_ready %d\n", ready)
	fmt.Fprintf(w, "# HELP tima_process_uptime_seconds Process uptime in seconds.\n")
	fmt.Fprintf(w, "# TYPE tima_process_uptime_seconds gauge\n")
	fmt.Fprintf(w, "tima_process_uptime_seconds %s\n", strconv.FormatFloat(time.Since(h.started).Seconds(), 'f', 3, 64))
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

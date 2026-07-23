package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"tima-server/internal/phase1"
	"tima-server/internal/readiness"
)

type ReadinessChecker interface {
	Check(context.Context) (bool, []readiness.Result)
}

type Handler struct {
	mux                http.Handler
	requests           atomic.Uint64
	ready              atomic.Bool
	started            time.Time
	phase1             *phase1.Service
	messageSendCount   atomic.Uint64
	messageSendMicros  atomic.Uint64
	messageSendBuckets [4]atomic.Uint64
}

func New(checker ReadinessChecker, services ...*phase1.Service) *Handler {
	h := &Handler{started: time.Now()}
	if len(services) > 0 {
		h.phase1 = services[0]
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", h.health)
	mux.HandleFunc("GET /readyz", h.readiness(checker))
	mux.HandleFunc("GET /metrics", h.metrics)
	if h.phase1 != nil {
		h.registerPhase1(mux)
	}
	h.mux = h.requestID(mux)
	return h
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h.requests.Add(1)
	if r.Method != http.MethodPost || !isMessageSendPath(r.URL.Path) {
		h.mux.ServeHTTP(w, r)
		return
	}
	started := time.Now()
	status := &statusWriter{ResponseWriter: w, status: http.StatusOK}
	h.mux.ServeHTTP(status, r)
	if status.status < 200 || status.status > 299 {
		return
	}
	micros := uint64(time.Since(started).Microseconds())
	h.messageSendCount.Add(1)
	h.messageSendMicros.Add(micros)
	for index, threshold := range [...]uint64{100_000, 250_000, 500_000, 800_000} {
		if micros <= threshold {
			h.messageSendBuckets[index].Add(1)
		}
	}
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func isMessageSendPath(path string) bool {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	return len(parts) == 5 && parts[0] == "v1" && parts[1] == "chats" &&
		parts[3] == "messages"
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
	fmt.Fprintf(w, "# HELP tima_message_send_ack_seconds Successful private message send latency.\n")
	fmt.Fprintf(w, "# TYPE tima_message_send_ack_seconds histogram\n")
	for index, label := range [...]string{"0.1", "0.25", "0.5", "0.8"} {
		fmt.Fprintf(w, "tima_message_send_ack_seconds_bucket{le=%q} %d\n",
			label, h.messageSendBuckets[index].Load())
	}
	fmt.Fprintf(w, "tima_message_send_ack_seconds_bucket{le=\"+Inf\"} %d\n", h.messageSendCount.Load())
	fmt.Fprintf(w, "tima_message_send_ack_seconds_sum %s\n",
		strconv.FormatFloat(float64(h.messageSendMicros.Load())/1_000_000, 'f', 6, 64))
	fmt.Fprintf(w, "tima_message_send_ack_seconds_count %d\n", h.messageSendCount.Load())
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	if w.Header().Get("Content-Type") == "" {
		w.Header().Set("Content-Type", "application/json")
	}
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

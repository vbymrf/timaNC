package readiness

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestCheckerDistinguishesFailedDependency(t *testing.T) {
	checker := New(time.Second, map[string]Check{
		"postgresql": func(context.Context) error { return nil },
		"redis":      func(context.Context) error { return errors.New("connection refused") },
	})

	ready, results := checker.Check(context.Background())
	if ready {
		t.Fatal("Check() ready = true, want false")
	}
	if len(results) != 2 {
		t.Fatalf("len(results) = %d", len(results))
	}
}

func TestCheckerAppliesTimeout(t *testing.T) {
	checker := New(10*time.Millisecond, map[string]Check{
		"slow": func(ctx context.Context) error {
			<-ctx.Done()
			return ctx.Err()
		},
	})

	ready, _ := checker.Check(context.Background())
	if ready {
		t.Fatal("Check() ready = true, want false")
	}
}

func TestMinIO(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/minio/health/ready" {
			t.Errorf("path = %q", r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	check, err := MinIO(server.Client(), server.URL)
	if err != nil {
		t.Fatalf("MinIO() error = %v", err)
	}
	if err := check(context.Background()); err != nil {
		t.Fatalf("check() error = %v", err)
	}
}

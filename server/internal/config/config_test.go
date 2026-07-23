package config

import (
	"strings"
	"testing"
	"time"
)

func TestLoad(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://user:pass@db:5432/tima")
	t.Setenv("REDIS_URL", "redis://cache:6379/0")
	t.Setenv("MINIO_ENDPOINT", "http://minio:9000")
	t.Setenv("HTTP_ADDR", "127.0.0.1:9090")
	t.Setenv("READINESS_TIMEOUT", "750ms")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.HTTPAddr != "127.0.0.1:9090" {
		t.Fatalf("HTTPAddr = %q", cfg.HTTPAddr)
	}
	if cfg.ReadinessTimeout != 750*time.Millisecond {
		t.Fatalf("ReadinessTimeout = %s", cfg.ReadinessTimeout)
	}
}

func TestLoadRequiresDatabaseURL(t *testing.T) {
	t.Setenv("DATABASE_URL", "")
	t.Setenv("POSTGRES_DSN", "")

	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "DATABASE_URL") {
		t.Fatalf("Load() error = %v, want DATABASE_URL error", err)
	}
}

func TestLoadRejectsInvalidTimeout(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://db/tima")
	t.Setenv("READINESS_TIMEOUT", "soon")

	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "READINESS_TIMEOUT") {
		t.Fatalf("Load() error = %v, want READINESS_TIMEOUT error", err)
	}
}

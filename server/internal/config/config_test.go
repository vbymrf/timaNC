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
	if cfg.MediaURLTTL != 15*time.Minute {
		t.Fatalf("MediaURLTTL = %s", cfg.MediaURLTTL)
	}
}

func TestLoadRejectsMediaURLTTLAboveFifteenMinutes(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://db/tima")
	t.Setenv("MEDIA_URL_TTL", "16m")

	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "MEDIA_URL_TTL") {
		t.Fatalf("Load() error = %v, want MEDIA_URL_TTL error", err)
	}
}

func TestLoadRejectsMediaURLTTLBelowFifteenMinutes(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://db/tima")
	t.Setenv("MEDIA_URL_TTL", "14m")

	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "MEDIA_URL_TTL") {
		t.Fatalf("Load() error = %v, want fixed MEDIA_URL_TTL error", err)
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

func TestLoadRequiresStrictEscrowOutsideDevelopment(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://db/tima")
	t.Setenv("APP_ENV", "beta")
	t.Setenv("ESCROW_STRICT", "false")

	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "ESCROW_STRICT=true") {
		t.Fatalf("Load() error = %v, want strict escrow error", err)
	}
}

func TestLoadRejectsInvalidEscrowStrictValue(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://db/tima")
	t.Setenv("ESCROW_STRICT", "sometimes")

	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "ESCROW_STRICT must be true or false") {
		t.Fatalf("Load() error = %v, want ESCROW_STRICT syntax error", err)
	}
}

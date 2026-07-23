package config

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"
	"time"
)

type Config struct {
	HTTPAddr         string
	DatabaseURL      string
	RedisURL         string
	MinIOEndpoint    string
	ReadTimeout      time.Duration
	WriteTimeout     time.Duration
	IdleTimeout      time.Duration
	ShutdownTimeout  time.Duration
	ReadinessTimeout time.Duration
}

func Load() (Config, error) {
	cfg := Config{
		HTTPAddr:         env("HTTP_ADDR", ":8080"),
		DatabaseURL:      firstNonEmpty(os.Getenv("DATABASE_URL"), os.Getenv("POSTGRES_DSN")),
		RedisURL:         env("REDIS_URL", "redis://localhost:6379"),
		MinIOEndpoint:    env("MINIO_ENDPOINT", "http://localhost:9000"),
		ReadTimeout:      duration("HTTP_READ_TIMEOUT", 5*time.Second),
		WriteTimeout:     duration("HTTP_WRITE_TIMEOUT", 10*time.Second),
		IdleTimeout:      duration("HTTP_IDLE_TIMEOUT", 60*time.Second),
		ShutdownTimeout:  duration("SHUTDOWN_TIMEOUT", 10*time.Second),
		ReadinessTimeout: duration("READINESS_TIMEOUT", 2*time.Second),
	}

	var errs []error
	if strings.TrimSpace(cfg.DatabaseURL) == "" {
		errs = append(errs, errors.New("DATABASE_URL (or POSTGRES_DSN) is required"))
	}
	if u, err := url.ParseRequestURI(cfg.RedisURL); err != nil ||
		(u.Scheme != "redis" && u.Scheme != "rediss") || u.Host == "" {
		errs = append(errs, errors.New("REDIS_URL must use redis:// or rediss://"))
	}
	if u, err := url.ParseRequestURI(cfg.MinIOEndpoint); err != nil ||
		(u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		errs = append(errs, errors.New("MINIO_ENDPOINT must be an absolute HTTP URL"))
	}
	for name, value := range map[string]time.Duration{
		"HTTP_READ_TIMEOUT":  cfg.ReadTimeout,
		"HTTP_WRITE_TIMEOUT": cfg.WriteTimeout,
		"HTTP_IDLE_TIMEOUT":  cfg.IdleTimeout,
		"SHUTDOWN_TIMEOUT":   cfg.ShutdownTimeout,
		"READINESS_TIMEOUT":  cfg.ReadinessTimeout,
	} {
		if value <= 0 {
			errs = append(errs, fmt.Errorf("%s must be positive", name))
		}
	}
	return cfg, errors.Join(errs...)
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value = strings.TrimSpace(value); value != "" {
			return value
		}
	}
	return ""
}

func duration(name string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err == nil {
		return parsed
	}
	// Load reports non-positive values as invalid.
	return -1
}

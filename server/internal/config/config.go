package config

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"
	"time"

	"tima-server/internal/push"
)

type Config struct {
	HTTPAddr                string
	DatabaseURL             string
	RedisURL                string
	MinIOEndpoint           string
	MinIOPublicURL          string
	MinIOAccessKey          string
	MinIOSecretKey          string
	MediaPrivateBucket      string
	MediaPublicBucket       string
	MediaStagingBucket      string
	MediaURLTTL             time.Duration
	Environment             string
	TokenPepper             string
	OTPPepper               string
	DevOTP                  string
	DevAttestationKey       string
	AttestationGatewayURL   string
	AttestationGatewayToken string
	PushTokenKey            string
	PushGatewayURL          string
	PushGatewayToken        string
	EscrowSigningKey        string
	EscrowKeyID             string
	EscrowX25519Key         string
	EscrowMLKEMKey          string
	EscrowStrict            bool
	ReadTimeout             time.Duration
	WriteTimeout            time.Duration
	IdleTimeout             time.Duration
	ShutdownTimeout         time.Duration
	ReadinessTimeout        time.Duration
}

func Load() (Config, error) {
	cfg := Config{
		HTTPAddr:                env("HTTP_ADDR", ":8080"),
		DatabaseURL:             firstNonEmpty(os.Getenv("DATABASE_URL"), os.Getenv("POSTGRES_DSN")),
		RedisURL:                env("REDIS_URL", "redis://localhost:6379"),
		MinIOEndpoint:           env("MINIO_ENDPOINT", "http://localhost:9000"),
		MinIOPublicURL:          env("MINIO_PUBLIC_ENDPOINT", env("MINIO_ENDPOINT", "http://localhost:9000")),
		MinIOAccessKey:          os.Getenv("MINIO_ACCESS_KEY"),
		MinIOSecretKey:          os.Getenv("MINIO_SECRET_KEY"),
		MediaPrivateBucket:      env("MINIO_PRIVATE_BUCKET", "tima-media-e2e"),
		MediaPublicBucket:       env("MINIO_PUBLIC_BUCKET", "tima-media-public"),
		MediaStagingBucket:      env("MINIO_STAGING_BUCKET", "tima-public-staging"),
		MediaURLTTL:             duration("MEDIA_URL_TTL", 15*time.Minute),
		Environment:             env("APP_ENV", "development"),
		TokenPepper:             os.Getenv("TOKEN_PEPPER"),
		OTPPepper:               os.Getenv("OTP_PEPPER"),
		DevOTP:                  env("DEV_OTP", "000000"),
		DevAttestationKey:       os.Getenv("DEV_ATTESTATION_KEY"),
		AttestationGatewayURL:   os.Getenv("ATTESTATION_GATEWAY_URL"),
		AttestationGatewayToken: os.Getenv("ATTESTATION_GATEWAY_TOKEN"),
		PushTokenKey:            os.Getenv("PUSH_TOKEN_ENCRYPTION_KEY"),
		PushGatewayURL:          os.Getenv("PUSH_GATEWAY_URL"),
		PushGatewayToken:        os.Getenv("PUSH_GATEWAY_TOKEN"),
		EscrowSigningKey:        os.Getenv("ESCROW_SIGNING_PRIVATE_KEY"),
		EscrowKeyID:             env("ESCROW_SIGNING_KEY_ID", "dev-ed25519-1"),
		EscrowX25519Key:         os.Getenv("ESCROW_X25519_PUBLIC_KEY"),
		EscrowMLKEMKey:          os.Getenv("ESCROW_MLKEM768_PUBLIC_KEY"),
		EscrowStrict:            strings.EqualFold(env("ESCROW_STRICT", "false"), "true"),
		ReadTimeout:             duration("HTTP_READ_TIMEOUT", 5*time.Second),
		WriteTimeout:            duration("HTTP_WRITE_TIMEOUT", 10*time.Second),
		IdleTimeout:             duration("HTTP_IDLE_TIMEOUT", 60*time.Second),
		ShutdownTimeout:         duration("SHUTDOWN_TIMEOUT", 10*time.Second),
		ReadinessTimeout:        duration("READINESS_TIMEOUT", 2*time.Second),
	}

	var errs []error
	if strings.TrimSpace(cfg.DatabaseURL) == "" {
		errs = append(errs, errors.New("DATABASE_URL (or POSTGRES_DSN) is required"))
	}
	dev := cfg.Environment == "development" || cfg.Environment == "test"
	if !dev && (cfg.TokenPepper == "" || cfg.OTPPepper == "" || cfg.EscrowSigningKey == "" ||
		cfg.EscrowX25519Key == "" || cfg.EscrowMLKEMKey == "" ||
		cfg.MinIOAccessKey == "" || cfg.MinIOSecretKey == "" || cfg.PushTokenKey == "" ||
		cfg.PushGatewayURL == "" || cfg.PushGatewayToken == "" ||
		cfg.AttestationGatewayURL == "" || cfg.AttestationGatewayToken == "") {
		errs = append(errs, errors.New(
			"token, OTP, escrow, media, push, and attestation configuration is required outside development/test",
		))
	}
	escrowStrictValue := strings.ToLower(strings.TrimSpace(env("ESCROW_STRICT", "false")))
	if escrowStrictValue != "true" && escrowStrictValue != "false" {
		errs = append(errs, errors.New("ESCROW_STRICT must be true or false"))
	}
	if !dev && !cfg.EscrowStrict {
		errs = append(errs, errors.New("ESCROW_STRICT=true is required outside development/test"))
	}
	if dev {
		if cfg.TokenPepper == "" {
			cfg.TokenPepper = "development-token-pepper"
		}
		if cfg.OTPPepper == "" {
			cfg.OTPPepper = "development-otp-pepper"
		}
		if cfg.MinIOAccessKey == "" {
			cfg.MinIOAccessKey = "tima_dev"
		}
		if cfg.MinIOSecretKey == "" {
			cfg.MinIOSecretKey = "dev_minio_change_me"
		}
		if cfg.DevAttestationKey == "" {
			cfg.DevAttestationKey = "development-attestation-key"
		}
		if cfg.PushTokenKey == "" {
			cfg.PushTokenKey = "ZGV2LXB1c2gtdG9rZW4ta2V5LTMyLWJ5dGVzISEhISE="
		}
	}
	if u, err := url.ParseRequestURI(cfg.RedisURL); err != nil ||
		(u.Scheme != "redis" && u.Scheme != "rediss") || u.Host == "" {
		errs = append(errs, errors.New("REDIS_URL must use redis:// or rediss://"))
	}
	if u, err := url.ParseRequestURI(cfg.MinIOEndpoint); err != nil ||
		(u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		errs = append(errs, errors.New("MINIO_ENDPOINT must be an absolute HTTP URL"))
	}
	if u, err := url.ParseRequestURI(cfg.MinIOPublicURL); err != nil ||
		(u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		errs = append(errs, errors.New("MINIO_PUBLIC_ENDPOINT must be an absolute HTTP URL"))
	}
	if cfg.PushGatewayURL != "" {
		if u, err := url.ParseRequestURI(cfg.PushGatewayURL); err != nil ||
			u.Scheme != "https" || u.Host == "" {
			errs = append(errs, errors.New("PUSH_GATEWAY_URL must be an absolute HTTPS URL"))
		}
	}
	if cfg.AttestationGatewayURL != "" {
		if u, err := url.ParseRequestURI(cfg.AttestationGatewayURL); err != nil ||
			u.Scheme != "https" || u.Host == "" {
			errs = append(errs, errors.New("ATTESTATION_GATEWAY_URL must be an absolute HTTPS URL"))
		}
	}
	if _, err := push.DecodeKey(cfg.PushTokenKey); err != nil {
		errs = append(errs, fmt.Errorf("PUSH_TOKEN_ENCRYPTION_KEY: %w", err))
	}
	for name, value := range map[string]time.Duration{
		"HTTP_READ_TIMEOUT":  cfg.ReadTimeout,
		"HTTP_WRITE_TIMEOUT": cfg.WriteTimeout,
		"HTTP_IDLE_TIMEOUT":  cfg.IdleTimeout,
		"SHUTDOWN_TIMEOUT":   cfg.ShutdownTimeout,
		"READINESS_TIMEOUT":  cfg.ReadinessTimeout,
		"MEDIA_URL_TTL":      cfg.MediaURLTTL,
	} {
		if value <= 0 {
			errs = append(errs, fmt.Errorf("%s must be positive", name))
		}
	}
	if cfg.MediaURLTTL != 15*time.Minute {
		errs = append(errs, errors.New("MEDIA_URL_TTL must be exactly 15 minutes"))
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

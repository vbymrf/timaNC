package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"tima-server/internal/pushgateway"
)

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--healthcheck" {
		client := &http.Client{Timeout: 2 * time.Second}
		response, err := client.Get("http://127.0.0.1:8082/healthz")
		if err != nil || response.StatusCode != http.StatusNoContent {
			os.Exit(1)
		}
		_ = response.Body.Close()
		return
	}
	if err := run(); err != nil {
		slog.Error("push gateway stopped", "error", err)
		os.Exit(1)
	}
}

func run() error {
	addr := env("PUSH_GATEWAY_ADDR", ":8082")
	token := os.Getenv("PUSH_GATEWAY_TOKEN")
	if token == "" {
		return errors.New("PUSH_GATEWAY_TOKEN is required")
	}
	client := &http.Client{Timeout: 5 * time.Second}
	adapters := map[string]pushgateway.Adapter{
		"unifiedpush": &pushgateway.HTTPAdapter{
			Client: client, EndpointFromToken: true, Provider: "unifiedpush", MaxAttempts: 2,
		},
	}
	if endpoint, credential := os.Getenv("FCM_ENDPOINT"), os.Getenv("FCM_BEARER_TOKEN"); endpoint != "" && credential != "" {
		adapters["fcm"] = &pushgateway.HTTPAdapter{
			Client: client, URL: endpoint, BearerToken: credential, Provider: "fcm", MaxAttempts: 2,
		}
	}
	if endpoint, credential := os.Getenv("APNS_ENDPOINT"), os.Getenv("APNS_BEARER_TOKEN"); endpoint != "" && credential != "" {
		adapters["apns"] = &pushgateway.HTTPAdapter{
			Client: client, URL: endpoint, BearerToken: credential, Provider: "apns", MaxAttempts: 2,
		}
	}

	server := &http.Server{
		Addr: addr,
		Handler: (&pushgateway.Gateway{
			BearerToken: token,
			Adapters:    adapters,
		}).Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	errCh := make(chan error, 1)
	go func() {
		slog.Info("push gateway listening", "address", addr)
		errCh <- server.ListenAndServe()
	}()
	select {
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			_ = server.Close()
			return err
		}
		err := <-errCh
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

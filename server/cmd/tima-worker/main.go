package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"tima-server/internal/config"
	"tima-server/internal/eventbus"
	"tima-server/internal/outbox"
	"tima-server/internal/push"
	"tima-server/internal/worker"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatal(err)
	}
	defer pool.Close()
	redisClient, err := eventbus.NewRedisClient(cfg.RedisURL)
	if err != nil {
		log.Fatal(err)
	}
	defer redisClient.Close()
	if err = redisClient.Ping(ctx).Err(); err != nil {
		log.Fatal(err)
	}
	pushTokenKey, err := push.DecodeKey(cfg.PushTokenKey)
	if err != nil {
		log.Fatal(err)
	}
	var pushSender push.Sender
	if cfg.PushGatewayURL != "" {
		pushSender = &push.HTTPSender{
			URL: cfg.PushGatewayURL, BearerToken: cfg.PushGatewayToken,
			Client: &http.Client{Timeout: 5 * time.Second},
		}
	}

	failures := make(chan error, 2)
	go func() {
		failures <- (&outbox.Relay{
			DB: pool, Redis: redisClient, PollInterval: 100 * time.Millisecond,
		}).Run(ctx)
	}()
	go func() {
		failures <- (&worker.MessageConsumer{
			DB: pool, Redis: redisClient, ConsumerName: hostname(),
			PushSender: pushSender, PushTokenKey: pushTokenKey,
		}).Run(ctx)
	}()
	select {
	case <-ctx.Done():
	case err = <-failures:
		if !errors.Is(err, context.Canceled) {
			log.Printf("worker stopped: %v", err)
			os.Exit(1)
		}
	}
}

func hostname() string {
	name, err := os.Hostname()
	if err != nil || name == "" {
		return "worker-1"
	}
	return name
}

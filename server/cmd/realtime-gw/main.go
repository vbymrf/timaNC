package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/jackc/pgx/v5/pgxpool"

	"tima-server/internal/config"
	"tima-server/internal/eventbus"
	"tima-server/internal/phase1"
	"tima-server/internal/realtime"
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
	service, err := phase1.New(pool, cfg)
	if err != nil {
		log.Fatal(err)
	}
	gateway := &realtime.Gateway{DB: pool, Redis: redisClient, Auth: service}
	server := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           gateway.Handler(),
		ReadHeaderTimeout: cfg.ReadTimeout,
		IdleTimeout:       cfg.IdleTimeout,
	}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()
	log.Printf("realtime-gw listening on %s", cfg.HTTPAddr)
	if err = server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

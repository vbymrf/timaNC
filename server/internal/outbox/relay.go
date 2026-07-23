package outbox

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"tima-server/internal/eventbus"
)

type Relay struct {
	DB           *pgxpool.Pool
	Redis        *redis.Client
	PollInterval time.Duration
}

var publishScript = redis.NewScript(`
if redis.call('SET', KEYS[2], '1', 'NX', 'EX', 604800) then
  return redis.call('XADD', KEYS[1], '*',
    'event_id', ARGV[1],
    'topic', ARGV[2],
    'aggregate_id', ARGV[3],
    'payload', ARGV[4])
end
return ''
`)

func (r *Relay) Run(ctx context.Context) error {
	interval := r.PollInterval
	if interval <= 0 {
		interval = 100 * time.Millisecond
	}
	timer := time.NewTimer(0)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
			processed, err := r.RelayOne(ctx)
			if err != nil {
				return err
			}
			if processed {
				timer.Reset(0)
			} else {
				timer.Reset(interval)
			}
		}
	}
}

func (r *Relay) RelayOne(ctx context.Context) (bool, error) {
	tx, err := r.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)

	var event eventbus.StreamEvent
	err = tx.QueryRow(ctx, `SELECT event_id::text,aggregate_id,topic,payload
		FROM outbox_events WHERE published_at IS NULL
		ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED`).
		Scan(&event.EventID, &event.AggregateID, &event.Topic, &event.Payload)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, tx.Commit(ctx)
	}
	if err != nil {
		return false, err
	}
	if event.Topic != "personal_message.created" {
		return false, fmt.Errorf("unsupported outbox topic %q", event.Topic)
	}
	if err = publishScript.Run(
		ctx,
		r.Redis,
		[]string{
			eventbus.MessageIngestStream,
			"tima.outbox.published." + event.EventID,
		},
		event.EventID,
		event.Topic,
		event.AggregateID,
		string(event.Payload),
	).Err(); err != nil {
		return false, err
	}
	if _, err = tx.Exec(ctx, `UPDATE outbox_events SET published_at=now()
		WHERE event_id=$1 AND published_at IS NULL`, event.EventID); err != nil {
		return false, err
	}
	return true, tx.Commit(ctx)
}

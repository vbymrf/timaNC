package worker

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"tima-server/internal/eventbus"
	"tima-server/internal/push"
)

type MessageConsumer struct {
	DB           *pgxpool.Pool
	Redis        *redis.Client
	ConsumerName string
	PushSender   push.Sender
	PushTokenKey []byte
}

type outboxPayload struct {
	ChatID         string `json:"chat_id"`
	MessageID      string `json:"message_id"`
	SenderID       string `json:"sender_id"`
	SenderDeviceID string `json:"sender_device_id"`
}

func (c *MessageConsumer) Run(ctx context.Context) error {
	if err := c.Redis.XGroupCreateMkStream(
		ctx, eventbus.MessageIngestStream, eventbus.MessageWorkerGroup, "0",
	).Err(); err != nil && !strings.Contains(err.Error(), "BUSYGROUP") {
		return err
	}
	name := c.ConsumerName
	if name == "" {
		name = "worker-1"
	}
	for {
		if err := c.reclaimPending(ctx, name); err != nil {
			return err
		}
		streams, err := c.Redis.XReadGroup(ctx, &redis.XReadGroupArgs{
			Group:    eventbus.MessageWorkerGroup,
			Consumer: name,
			Streams: []string{
				eventbus.MessageIngestStream, ">",
			},
			Count: 10,
			Block: time.Second,
		}).Result()
		if errors.Is(err, redis.Nil) {
			continue
		}
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return err
		}
		for _, stream := range streams {
			if err = c.process(ctx, stream.Messages); err != nil {
				return err
			}
		}
	}
}

func (c *MessageConsumer) reclaimPending(ctx context.Context, consumer string) error {
	messages, _, err := c.Redis.XAutoClaim(ctx, &redis.XAutoClaimArgs{
		Stream:   eventbus.MessageIngestStream,
		Group:    eventbus.MessageWorkerGroup,
		Consumer: consumer,
		MinIdle:  30 * time.Second,
		Start:    "0-0",
		Count:    10,
	}).Result()
	if errors.Is(err, redis.Nil) {
		return nil
	}
	if err != nil {
		return err
	}
	return c.process(ctx, messages)
}

func (c *MessageConsumer) process(ctx context.Context, messages []redis.XMessage) error {
	for _, message := range messages {
		if err := c.handle(ctx, message); err != nil {
			return err
		}
		if err := c.Redis.XAck(
			ctx, eventbus.MessageIngestStream, eventbus.MessageWorkerGroup, message.ID,
		).Err(); err != nil {
			return err
		}
	}
	return nil
}

func (c *MessageConsumer) handle(ctx context.Context, message redis.XMessage) error {
	eventID, err := field(message, "event_id")
	if err != nil {
		return err
	}
	topic, err := field(message, "topic")
	if err != nil {
		return err
	}
	if topic != "personal_message.created" && topic != "personal_message.edited" {
		return fmt.Errorf("unsupported message ingest topic %q", topic)
	}
	rawPayload, err := field(message, "payload")
	if err != nil {
		return err
	}
	var payload outboxPayload
	if err = json.Unmarshal([]byte(rawPayload), &payload); err != nil {
		return err
	}
	messageID, err := strconv.ParseUint(payload.MessageID, 10, 64)
	if err != nil || messageID == 0 {
		return fmt.Errorf("invalid message id %q", payload.MessageID)
	}

	notification := eventbus.DeviceNotification{
		EventID:         eventID,
		Topic:           topic,
		ChatID:          payload.ChatID,
		MessageID:       messageID,
		SenderID:        payload.SenderID,
		ProtocolVersion: 2,
		FormatVersion:   2,
	}
	err = c.DB.QueryRow(ctx, `SELECT m.current_revision_id,r.parent_revision_id,
		r.revision_number,m.presence_bitmap,m.key_commitment,m.created_at
		FROM personal_messages m JOIN personal_message_revisions r
		  ON r.chat_id=m.chat_id AND r.message_id=m.message_id
		  AND r.revision_id=m.current_revision_id
		WHERE m.chat_id=$1 AND m.message_id=$2`,
		payload.ChatID, int64(messageID)).Scan(
		&notification.RevisionID,
		&notification.ParentRevisionID,
		&notification.RevisionNumber,
		&notification.PresenceBitmap,
		&notification.KeyCommitment,
		&notification.CreatedAt,
	)
	if err != nil {
		return err
	}

	rows, err := c.DB.Query(ctx, `SELECT d.device_id,d.user_id,
		EXISTS(SELECT 1 FROM personal_message_keys k
		  WHERE k.chat_id=$1 AND k.message_id=$2 AND k.recipient_key=d.device_id
		    AND k.revision_id=$4),p.provider,p.token_ciphertext
		FROM chats c JOIN devices d ON d.user_id IN(c.user_a,c.user_b)
		LEFT JOIN device_push_registrations p ON p.device_id=d.device_id
		WHERE c.chat_id=$1 AND d.revoked_at IS NULL AND d.device_id<>$3`,
		payload.ChatID, int64(messageID), payload.SenderDeviceID, notification.RevisionID)
	if err != nil {
		return err
	}
	defer rows.Close()
	pushAllowed := map[string]bool{}
	for rows.Next() {
		var deviceID, userID string
		var provider *string
		var tokenCiphertext []byte
		if err = rows.Scan(&deviceID, &userID, &notification.HasWrappedKey, &provider, &tokenCiphertext); err != nil {
			return err
		}
		encoded, marshalErr := json.Marshal(notification)
		if marshalErr != nil {
			return marshalErr
		}
		if err = c.Redis.Publish(ctx, eventbus.NotifyChannel(deviceID), encoded).Err(); err != nil {
			return err
		}
		if c.PushSender != nil && provider != nil && len(tokenCiphertext) > 0 {
			online, presenceErr := c.Redis.Exists(ctx, eventbus.PresenceKey(deviceID)).Result()
			if presenceErr != nil {
				return presenceErr
			}
			if online == 0 {
				allowed, known := pushAllowed[userID]
				if !known {
					collapseKey := fmt.Sprintf("push:collapse:%s:%s", userID, payload.ChatID)
					allowed, err = c.Redis.SetNX(ctx, collapseKey, eventID, 5*time.Minute).Result()
					if err != nil {
						return err
					}
					rateKey := fmt.Sprintf("push:private:%s:%s", userID, time.Now().UTC().Format("2006010215"))
					var count int64
					var rateErr error
					if allowed {
						count, rateErr = c.Redis.Incr(ctx, rateKey).Result()
					}
					if rateErr != nil {
						return rateErr
					}
					if allowed && count == 1 {
						_ = c.Redis.Expire(ctx, rateKey, 2*time.Hour).Err()
					}
					allowed = allowed && count <= 12
					pushAllowed[userID] = allowed
				}
				if !allowed {
					continue
				}
				token, decryptErr := push.DecryptToken(c.PushTokenKey, tokenCiphertext)
				if decryptErr != nil {
					return decryptErr
				}
				if err = c.PushSender.Send(ctx, *provider, token, push.Message{
					Type: "message", ChatID: payload.ChatID, Preview: "Новое сообщение",
					Encrypted: true, CollapseKey: "chat:" + payload.ChatID,
				}); err != nil {
					return err
				}
			}
		}
	}
	return rows.Err()
}

func field(message redis.XMessage, name string) (string, error) {
	value, ok := message.Values[name]
	if !ok {
		return "", fmt.Errorf("stream entry %s is missing %s", message.ID, name)
	}
	text, ok := value.(string)
	if !ok || text == "" {
		return "", fmt.Errorf("stream entry %s has invalid %s", message.ID, name)
	}
	return text, nil
}

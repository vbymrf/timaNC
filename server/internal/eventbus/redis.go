package eventbus

import (
	"encoding/json"
	"time"

	"github.com/redis/go-redis/v9"
)

const (
	MessageIngestStream = "message.ingest"
	MessageWorkerGroup  = "tima-message-workers"
	NotifyChannelPrefix = "tima.notify."
)

type StreamEvent struct {
	EventID     string
	Topic       string
	AggregateID string
	Payload     json.RawMessage
}

type DeviceNotification struct {
	EventID          string    `json:"event_id"`
	ChatID           string    `json:"chat_id"`
	MessageID        uint64    `json:"message_id"`
	SenderID         string    `json:"sender_id"`
	RevisionID       string    `json:"revision_id"`
	ParentRevisionID *string   `json:"parent_revision_id"`
	RevisionNumber   uint64    `json:"revision_number"`
	ProtocolVersion  uint32    `json:"protocol_version"`
	FormatVersion    uint32    `json:"format_version"`
	PresenceBitmap   uint32    `json:"presence_bitmap"`
	KeyCommitment    []byte    `json:"key_commitment"`
	CreatedAt        time.Time `json:"created_at"`
	HasWrappedKey    bool      `json:"has_wrapped_key"`
}

func NewRedisClient(rawURL string) (*redis.Client, error) {
	options, err := redis.ParseURL(rawURL)
	if err != nil {
		return nil, err
	}
	return redis.NewClient(options), nil
}

func NotifyChannel(deviceID string) string {
	return NotifyChannelPrefix + deviceID
}

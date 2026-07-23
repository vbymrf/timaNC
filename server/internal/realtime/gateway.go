package realtime

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	commonv1 "github.com/tima/messnc/gen/go/proto/tima/v1/common"
	realtimev1 "github.com/tima/messnc/gen/go/proto/tima/v1/realtime"

	"github.com/gorilla/websocket"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"google.golang.org/protobuf/proto"

	"tima-server/internal/eventbus"
	"tima-server/internal/phase1"
)

const protobufSubprotocol = "tima.pb.v1"

type Gateway struct {
	DB    *pgxpool.Pool
	Redis *redis.Client
	Auth  *phase1.Service
}

func (g *Gateway) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("GET /v1/ws", g.serveWebSocket)
	return mux
}

func (g *Gateway) serveWebSocket(w http.ResponseWriter, r *http.Request) {
	if !contains(websocket.Subprotocols(r), protobufSubprotocol) {
		http.Error(w, "websocket subprotocol tima.pb.v1 is required", http.StatusUpgradeRequired)
		return
	}
	token := strings.TrimSpace(r.URL.Query().Get("token"))
	deviceID := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	principal, err := g.Auth.Authenticate(r.Context(), token, deviceID)
	if err != nil {
		http.Error(w, "authentication failed", http.StatusUnauthorized)
		return
	}
	connection, err := (&websocket.Upgrader{
		Subprotocols: []string{protobufSubprotocol},
	}).Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer connection.Close()

	if err = connection.SetReadDeadline(time.Now().Add(10 * time.Second)); err != nil {
		return
	}
	messageType, encoded, err := connection.ReadMessage()
	if err != nil {
		return
	}
	if messageType != websocket.BinaryMessage {
		_ = connection.WriteControl(
			websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseUnsupportedData, "binary protobuf required"),
			time.Now().Add(time.Second),
		)
		return
	}
	var frame realtimev1.ClientFrame
	if err = proto.Unmarshal(encoded, &frame); err != nil || frame.GetSubscribe() == nil {
		_ = connection.WriteControl(
			websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseUnsupportedData, "invalid ClientFrame"),
			time.Now().Add(time.Second),
		)
		return
	}
	subscriptions, err := g.authorizeChats(r.Context(), principal.UserID, frame.GetSubscribe().ChatIds)
	if err != nil {
		_ = connection.WriteControl(
			websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.ClosePolicyViolation, "chat subscription denied"),
			time.Now().Add(time.Second),
		)
		return
	}
	if err = connection.SetReadDeadline(time.Time{}); err != nil {
		return
	}

	pubsub := g.Redis.Subscribe(r.Context(), eventbus.NotifyChannel(principal.DeviceID))
	defer pubsub.Close()
	if _, err = pubsub.Receive(r.Context()); err != nil {
		return
	}
	ack, _ := proto.Marshal(&realtimev1.ServerFrame{
		Body: &realtimev1.ServerFrame_Ack{Ack: &realtimev1.Ack{RequestId: frame.RequestId}},
	})
	if err = connection.WriteMessage(websocket.BinaryMessage, ack); err != nil {
		return
	}
	presenceKey := eventbus.PresenceKey(principal.DeviceID)
	if err = g.Redis.Set(r.Context(), presenceKey, "online", 90*time.Second).Err(); err != nil {
		return
	}
	presenceTicker := time.NewTicker(30 * time.Second)
	defer presenceTicker.Stop()

	disconnected := make(chan struct{})
	go func() {
		defer close(disconnected)
		for {
			if _, _, readErr := connection.ReadMessage(); readErr != nil {
				return
			}
		}
	}()

	sequence := uint64(0)
	delivered := map[string]bool{}
	for {
		select {
		case <-r.Context().Done():
			return
		case <-disconnected:
			return
		case <-presenceTicker.C:
			if err = g.Redis.Set(r.Context(), presenceKey, "online", 90*time.Second).Err(); err != nil {
				return
			}
		case message, ok := <-pubsub.Channel():
			if !ok {
				return
			}
			var notification eventbus.DeviceNotification
			if json.Unmarshal([]byte(message.Payload), &notification) != nil ||
				!subscriptions[notification.ChatID] || delivered[notification.EventID] {
				continue
			}
			delivered[notification.EventID] = true
			sequence++
			if err = writeNotification(connection, sequence, notification); err != nil {
				return
			}
		}
	}
}

func (g *Gateway) authorizeChats(
	ctx context.Context,
	userID string,
	ids []*commonv1.Uuid,
) (map[string]bool, error) {
	result := make(map[string]bool, len(ids))
	for _, id := range ids {
		chatID, err := uuidText(id.GetValue())
		if err != nil {
			return nil, err
		}
		var member bool
		if err = g.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM chats
			WHERE chat_id=$1 AND ($2=user_a OR $2=user_b))`, chatID, userID).Scan(&member); err != nil {
			return nil, err
		}
		if !member {
			return nil, phase1.ErrForbidden
		}
		result[chatID] = true
	}
	return result, nil
}

func writeNotification(
	connection *websocket.Conn,
	sequence uint64,
	notification eventbus.DeviceNotification,
) error {
	chatID, err := uuidValue(notification.ChatID)
	if err != nil {
		return err
	}
	senderID, err := uuidValue(notification.SenderID)
	if err != nil {
		return err
	}
	revisionID, err := uuidValue(notification.RevisionID)
	if err != nil {
		return err
	}
	var parentRevisionID *commonv1.Uuid
	if notification.ParentRevisionID != nil {
		parentRevisionID, err = uuidValue(*notification.ParentRevisionID)
		if err != nil {
			return err
		}
	}
	protocolVersion := notification.ProtocolVersion
	presenceBitmap := notification.PresenceBitmap
	hasWrappedKey := notification.HasWrappedKey
	revisionEvent := &realtimev1.MessageRevisionEvent{
		ChatId:           chatID,
		MessageId:        notification.MessageID,
		SenderId:         senderID,
		ProtocolVersion:  &protocolVersion,
		FormatVersion:    notification.FormatVersion,
		PresenceBitmap:   &presenceBitmap,
		KeyCommitment:    notification.KeyCommitment,
		RevisionId:       revisionID,
		ParentRevisionId: parentRevisionID,
		RevisionNumber:   notification.RevisionNumber,
		CreatedAt: &commonv1.Timestamp{
			Seconds: notification.CreatedAt.Unix(),
			Nanos:   int32(notification.CreatedAt.Nanosecond()),
		},
		HasWrappedKey: &hasWrappedKey,
	}
	serverEvent := &realtimev1.ServerEvent{}
	if notification.Topic == "personal_message.edited" {
		serverEvent.Event = &realtimev1.ServerEvent_MessageEdited{
			MessageEdited: &realtimev1.MessageEdited{Message: revisionEvent},
		}
	} else {
		serverEvent.Event = &realtimev1.ServerEvent_MessageCreated{
			MessageCreated: &realtimev1.MessageCreated{Message: revisionEvent},
		}
	}
	frame := &realtimev1.ServerFrame{
		Seq: sequence,
		Body: &realtimev1.ServerFrame_Event{
			Event: serverEvent,
		},
	}
	encoded, err := proto.Marshal(frame)
	if err != nil {
		return err
	}
	return connection.WriteMessage(websocket.BinaryMessage, encoded)
}

func uuidValue(value string) (*commonv1.Uuid, error) {
	compact := strings.ReplaceAll(value, "-", "")
	decoded, err := hex.DecodeString(compact)
	if err != nil || len(decoded) != 16 {
		return nil, phase1.ErrInvalid
	}
	return &commonv1.Uuid{Value: decoded}, nil
}

func uuidText(value []byte) (string, error) {
	if len(value) != 16 {
		return "", phase1.ErrInvalid
	}
	encoded := hex.EncodeToString(value)
	return encoded[0:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" +
		encoded[16:20] + "-" + encoded[20:32], nil
}

func contains(values []string, expected string) bool {
	for _, value := range values {
		if value == expected {
			return true
		}
	}
	return false
}

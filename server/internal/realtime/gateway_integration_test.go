//go:build integration

package realtime

import (
	"context"
	"crypto/sha256"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
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

func TestGatewayAuthenticationAndSubscriptionsIntegration(t *testing.T) {
	databaseURL := os.Getenv("DATABASE_URL")
	redisURL := os.Getenv("REDIS_URL")
	if databaseURL == "" || redisURL == "" {
		t.Skip("DATABASE_URL and REDIS_URL are required")
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	redisOptions, err := redis.ParseURL(redisURL)
	if err != nil {
		t.Fatal(err)
	}
	redisClient := redis.NewClient(redisOptions)
	defer redisClient.Close()
	if err = redisClient.Ping(ctx).Err(); err != nil {
		t.Fatal(err)
	}

	service := &phase1.Service{DB: pool, TokenPepper: []byte("realtime-integration-pepper")}
	userID, _ := phase1.NewUUID()
	peerID, _ := phase1.NewUUID()
	deviceID, _ := phase1.NewUUID()
	sessionID, _ := phase1.NewUUID()
	chatID, _ := phase1.NewUUID()
	accessToken := "realtime-integration-access-" + sessionID
	refreshHash := sha256.Sum256([]byte("refresh-" + sessionID))
	if _, err = pool.Exec(ctx, `INSERT INTO users(user_id,account_home_region,display_name)
		VALUES($1,'RU','realtime integration'),($2,'RU','realtime peer')`, userID, peerID); err != nil {
		t.Fatal(err)
	}
	userA, userB := userID, peerID
	if userA > userB {
		userA, userB = userB, userA
	}
	if _, err = pool.Exec(ctx, `INSERT INTO chats(chat_id,user_a,user_b,conversation_home_region)
		VALUES($1,$2,$3,'RU')`, chatID, userA, userB); err != nil {
		t.Fatal(err)
	}
	if _, err = pool.Exec(ctx, `INSERT INTO devices
		(device_id,user_id,platform,identity_pubkey,signing_pubkey,name,attestation_ok,attested_at)
		VALUES($1,$2,'android',$3,$4,'realtime device',true,now())`,
		deviceID, userID, make([]byte, 32), make([]byte, 32)); err != nil {
		t.Fatal(err)
	}
	accessHash := sha256.New()
	accessHash.Write([]byte("realtime-integration-pepper"))
	accessHash.Write([]byte{0})
	accessHash.Write([]byte(accessToken))
	if _, err = pool.Exec(ctx, `INSERT INTO sessions
		(session_id,device_id,refresh_hash,access_hash,access_expires_at,expires_at)
		VALUES($1,$2,$3,$4,now()+interval '15 minutes',now()+interval '1 day')`,
		sessionID, deviceID, refreshHash[:], accessHash.Sum(nil)); err != nil {
		t.Fatal(err)
	}

	gateway := &Gateway{DB: pool, Redis: redisClient, Auth: service}
	server := httptest.NewServer(gateway.Handler())
	defer server.Close()
	websocketURL := "ws" + strings.TrimPrefix(server.URL, "http") + "/v1/ws"

	t.Run("non-protobuf subprotocol", func(t *testing.T) {
		request, requestErr := http.NewRequest(http.MethodGet, server.URL+"/v1/ws", nil)
		if requestErr != nil {
			t.Fatal(requestErr)
		}
		request.Header.Set("Sec-WebSocket-Protocol", "json")
		response, requestErr := http.DefaultClient.Do(request)
		if requestErr != nil {
			t.Fatal(requestErr)
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusUpgradeRequired {
			t.Fatalf("status = %d", response.StatusCode)
		}
	})

	t.Run("authentication failure", func(t *testing.T) {
		dialer := websocket.Dialer{Subprotocols: []string{protobufSubprotocol}}
		connection, response, dialErr := dialer.Dial(
			websocketURL+"?token=invalid", http.Header{"X-Device-Id": []string{deviceID}},
		)
		if connection != nil {
			connection.Close()
		}
		if dialErr == nil || response == nil || response.StatusCode != http.StatusUnauthorized {
			t.Fatalf("dial error = %v, response = %#v", dialErr, response)
		}
		response.Body.Close()
	})

	dialAuthenticated := func(t *testing.T) *websocket.Conn {
		t.Helper()
		dialer := websocket.Dialer{Subprotocols: []string{protobufSubprotocol}}
		connection, response, dialErr := dialer.Dial(
			websocketURL+"?token="+accessToken, http.Header{"X-Device-Id": []string{deviceID}},
		)
		if dialErr != nil {
			if response != nil {
				response.Body.Close()
			}
			t.Fatal(dialErr)
		}
		return connection
	}

	t.Run("non-protobuf frame", func(t *testing.T) {
		connection := dialAuthenticated(t)
		defer connection.Close()
		if err := connection.WriteMessage(websocket.TextMessage, []byte(`{"subscribe":[]}`)); err != nil {
			t.Fatal(err)
		}
		_, _, readErr := connection.ReadMessage()
		var closeError *websocket.CloseError
		if !asCloseError(readErr, &closeError) || closeError.Code != websocket.CloseUnsupportedData {
			t.Fatalf("close error = %v", readErr)
		}
	})

	t.Run("invalid protobuf frame", func(t *testing.T) {
		connection := dialAuthenticated(t)
		defer connection.Close()
		if err := connection.WriteMessage(websocket.BinaryMessage, []byte{0xff}); err != nil {
			t.Fatal(err)
		}
		_, _, readErr := connection.ReadMessage()
		var closeError *websocket.CloseError
		if !asCloseError(readErr, &closeError) || closeError.Code != websocket.CloseUnsupportedData {
			t.Fatalf("close error = %v", readErr)
		}
	})

	t.Run("forbidden subscription", func(t *testing.T) {
		connection := dialAuthenticated(t)
		defer connection.Close()
		unknownChatID, _ := phase1.NewUUID()
		chatID, err := uuidValue(unknownChatID)
		if err != nil {
			t.Fatal(err)
		}
		sendClientFrame(t, connection, &realtimev1.ClientFrame{
			RequestId: 11,
			Event: &realtimev1.ClientFrame_Subscribe{Subscribe: &realtimev1.SubscribeChats{
				ChatIds: []*commonv1.Uuid{chatID},
			}},
		})
		_, _, readErr := connection.ReadMessage()
		var closeError *websocket.CloseError
		if !asCloseError(readErr, &closeError) || closeError.Code != websocket.ClosePolicyViolation {
			t.Fatalf("close error = %v", readErr)
		}
	})

	t.Run("safe allowed subscription", func(t *testing.T) {
		connection := dialAuthenticated(t)
		defer connection.Close()
		allowedChatID, err := uuidValue(chatID)
		if err != nil {
			t.Fatal(err)
		}
		sendClientFrame(t, connection, &realtimev1.ClientFrame{
			RequestId: 12,
			Event: &realtimev1.ClientFrame_Subscribe{
				Subscribe: &realtimev1.SubscribeChats{
					ChatIds: []*commonv1.Uuid{allowedChatID},
				},
			},
		})
		if err := connection.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
			t.Fatal(err)
		}
		messageType, encoded, readErr := connection.ReadMessage()
		if readErr != nil {
			t.Fatal(readErr)
		}
		var frame realtimev1.ServerFrame
		if messageType != websocket.BinaryMessage || proto.Unmarshal(encoded, &frame) != nil ||
			frame.GetAck() == nil || frame.GetAck().RequestId != 12 {
			t.Fatalf("ack frame = %s", frame.String())
		}
		presence, err := redisClient.Get(ctx, eventbus.PresenceKey(deviceID)).Result()
		if err != nil || presence != "online" {
			t.Fatalf("presence = %q, %v", presence, err)
		}
	})
}

func sendClientFrame(t *testing.T, connection *websocket.Conn, frame *realtimev1.ClientFrame) {
	t.Helper()
	encoded, err := proto.Marshal(frame)
	if err != nil {
		t.Fatal(err)
	}
	if err = connection.WriteMessage(websocket.BinaryMessage, encoded); err != nil {
		t.Fatal(err)
	}
}

func asCloseError(err error, target **websocket.CloseError) bool {
	if err == nil {
		return false
	}
	closeError, ok := err.(*websocket.CloseError)
	if ok {
		*target = closeError
	}
	return ok
}

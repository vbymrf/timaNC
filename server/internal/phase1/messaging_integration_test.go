//go:build integration

package phase1

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func TestReserveSendHistoryAndDirectoryIntegration(t *testing.T) {
	databaseURL := os.Getenv("DATABASE_URL")
	if databaseURL == "" {
		t.Skip("DATABASE_URL is not set")
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	now := time.Now().UTC()
	service := &Service{DB: pool, Now: func() time.Time { return now }}

	userA, _ := NewUUID()
	userB, _ := NewUUID()
	userC, _ := NewUUID()
	deviceA, _ := NewUUID()
	deviceB, _ := NewUUID()
	_, signingPrivateA, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signingPublicA := signingPrivateA.Public().(ed25519.PublicKey)
	identityA := make([]byte, 32)
	identityB := make([]byte, 32)
	if _, err = rand.Read(identityA); err != nil {
		t.Fatal(err)
	}
	if _, err = rand.Read(identityB); err != nil {
		t.Fatal(err)
	}
	_, err = pool.Exec(ctx, `INSERT INTO users(user_id,account_home_region,display_name) VALUES
		($1,'RU','integration-a'),($2,'RU','integration-b'),($3,'RU','integration-c')`,
		userA, userB, userC)
	if err != nil {
		t.Fatal(err)
	}
	_, err = pool.Exec(ctx, `INSERT INTO devices
		(device_id,user_id,platform,identity_pubkey,signing_pubkey,name) VALUES
		($1,$2,'android',$3,$4,'a'),($5,$6,'android',$7,$8,'b')`,
		deviceA, userA, identityA, signingPublicA, deviceB, userB, identityB, make([]byte, 32))
	if err != nil {
		t.Fatal(err)
	}
	principalA := Principal{UserID: userA, DeviceID: deviceA}

	chatKey, _ := NewUUID()
	chat, _, err := service.CreateChat(ctx, principalA, userB, chatKey, []byte(`{"peer_user_id":"`+userB+`"}`))
	if err != nil {
		t.Fatal(err)
	}
	otherChat, _ := NewUUID()
	a, b := userA, userC
	if a > b {
		a, b = b, a
	}
	_, err = pool.Exec(ctx, `INSERT INTO chats(chat_id,user_a,user_b,conversation_home_region)
		VALUES($1,$2,$3,'RU')`, otherChat, a, b)
	if err != nil {
		t.Fatal(err)
	}

	reservationKey, _ := NewUUID()
	reservation, _, err := service.ReserveMessageID(ctx, principalA, chat.ID, reservationKey)
	if err != nil {
		t.Fatal(err)
	}
	replayed, _, err := service.ReserveMessageID(ctx, principalA, chat.ID, reservationKey)
	if err != nil || replayed != reservation {
		t.Fatalf("reservation replay = %#v, %v", replayed, err)
	}
	if _, _, err = service.ReserveMessageID(ctx, principalA, otherChat, reservationKey); !errors.Is(err, ErrConflict) {
		t.Fatalf("changed reservation chat error = %v", err)
	}

	commitment := make([]byte, 32)
	escrow := &canonicalWriter{}
	escrow.WriteString("tima/escrow-blob/v1")
	escrow.WriteByte(0)
	escrow.u32(1)
	epoch := currentQuarter(now)
	escrow.blob([]byte(epoch))
	escrow.u32(0)
	escrow.blob([]byte("RU-" + epoch + "-0"))
	escrow.Write(commitment)
	escrow.Write(make([]byte, 32))
	escrow.blob(make([]byte, 1088))
	escrow.blob([]byte{1})
	document := PrivateDocument{
		EncryptedNodes:  []string{base64.StdEncoding.EncodeToString([]byte("ciphertext"))},
		Metadata:        json.RawMessage(`{"content_mode":"private","format_version":2,"revision_number":1}`),
		ProtocolVersion: 2,
		PresenceBitmap:  1,
		KeyCommitment:   base64.StdEncoding.EncodeToString(commitment),
		EscrowBlob:      base64.StdEncoding.EncodeToString(escrow.Bytes()),
	}
	messageID, err := parseMessageID(reservation.MessageID)
	if err != nil {
		t.Fatal(err)
	}
	signatureInput, err := personalSignatureInput(document, messageID, reservation.RevisionID, nil, 0,
		userA, deviceA, chat.ID)
	if err != nil {
		t.Fatal(err)
	}
	document.Signature = base64.StdEncoding.EncodeToString(ed25519.Sign(signingPrivateA, signatureInput))
	write := PrivateMessageWrite{
		MessageID: reservation.MessageID, RevisionID: reservation.RevisionID, MessageKeyID: 0,
		Document: document, WrappedKeys: []WrappedKey{
			{DeviceID: deviceA, WrappedKey: base64.StdEncoding.EncodeToString([]byte("wrap-a")),
				ProtocolVersion: 2, KeyCommitment: document.KeyCommitment},
			{DeviceID: deviceB, WrappedKey: base64.StdEncoding.EncodeToString([]byte("wrap-b")),
				ProtocolVersion: 2, KeyCommitment: document.KeyCommitment},
		},
	}
	secondReservationKey, _ := NewUUID()
	secondReservation, _, err := service.ReserveMessageID(ctx, principalA, chat.ID, secondReservationKey)
	if err != nil {
		t.Fatal(err)
	}
	wrongRevision, _ := NewUUID()
	badWrite := write
	badWrite.MessageID = secondReservation.MessageID
	badWrite.RevisionID = wrongRevision
	secondMessageID, _ := parseMessageID(secondReservation.MessageID)
	badInput, err := personalSignatureInput(document, secondMessageID, wrongRevision, nil, 0, userA, deviceA, chat.ID)
	if err != nil {
		t.Fatal(err)
	}
	badWrite.Document.Signature = base64.StdEncoding.EncodeToString(ed25519.Sign(signingPrivateA, badInput))
	badRequest, _ := json.Marshal(badWrite)
	badSendKey, _ := NewUUID()
	if _, _, err = service.SendMessage(ctx, principalA, chat.ID, badSendKey, badRequest, badWrite); !errors.Is(err, ErrConflict) {
		t.Fatalf("mismatched reservation revision error = %v", err)
	}
	request, _ := json.Marshal(write)
	sendKey, _ := NewUUID()
	sent, _, err := service.SendMessage(ctx, principalA, chat.ID, sendKey, request, write)
	if err != nil {
		t.Fatal(err)
	}
	retried, _, err := service.SendMessage(ctx, principalA, chat.ID, sendKey, request, write)
	if err != nil || retried.ID != sent.ID {
		t.Fatalf("send replay = %#v, %v", retried, err)
	}
	for _, principal := range []Principal{principalA, {UserID: userB, DeviceID: deviceB}} {
		history, err := service.ListMessages(ctx, principal, chat.ID, 10)
		if err != nil || len(history) != 1 || len(history[0].WrappedKeys) != 1 ||
			history[0].WrappedKeys[0].DeviceID != principal.DeviceID {
			t.Fatalf("filtered history for %s = %#v, %v", principal.DeviceID, history, err)
		}
		if history[0].SenderDeviceID != deviceA || history[0].MessageKeyID != 0 ||
			history[0].ParentRevisionID != nil {
			t.Fatalf("signed history headers = %#v", history[0])
		}
	}

	revisionID, _ := NewUUID()
	revisedDocument := document
	revisedDocument.EncryptedNodes = []string{base64.StdEncoding.EncodeToString([]byte("revised-ciphertext"))}
	revisedDocument.Metadata = json.RawMessage(
		`{"content_mode":"private","format_version":2,"revision_number":2}`,
	)
	revisionInput, err := personalSignatureInput(
		revisedDocument, messageID, revisionID, &reservation.RevisionID, 1,
		userA, deviceA, chat.ID,
	)
	if err != nil {
		t.Fatal(err)
	}
	revisedDocument.Signature = base64.StdEncoding.EncodeToString(
		ed25519.Sign(signingPrivateA, revisionInput),
	)
	revisionWrite := PrivateMessageWrite{
		MessageID: reservation.MessageID, RevisionID: revisionID, MessageKeyID: 1,
		Document: revisedDocument, WrappedKeys: []WrappedKey{
			{DeviceID: deviceA, WrappedKey: base64.StdEncoding.EncodeToString([]byte("revision-wrap-a")),
				ProtocolVersion: 2, KeyCommitment: revisedDocument.KeyCommitment},
			{DeviceID: deviceB, WrappedKey: base64.StdEncoding.EncodeToString([]byte("revision-wrap-b")),
				ProtocolVersion: 2, KeyCommitment: revisedDocument.KeyCommitment},
		},
	}
	revisionRequest, _ := json.Marshal(revisionWrite)
	revisionKey, _ := NewUUID()
	revised, _, err := service.ReviseMessage(
		ctx, principalA, chat.ID, reservation.MessageID, revisionKey, revisionRequest, revisionWrite,
	)
	if err != nil || revised.ParentRevisionID != reservation.RevisionID || revised.RevisionNumber != 2 {
		t.Fatalf("revision = %#v, %v", revised, err)
	}
	replayedRevision, _, err := service.ReviseMessage(
		ctx, principalA, chat.ID, reservation.MessageID, revisionKey, revisionRequest, revisionWrite,
	)
	if err != nil || replayedRevision.ID != revised.ID {
		t.Fatalf("revision replay = %#v, %v", replayedRevision, err)
	}
	for _, principal := range []Principal{principalA, {UserID: userB, DeviceID: deviceB}} {
		history, historyErr := service.ListMessages(ctx, principal, chat.ID, 10)
		if historyErr != nil || len(history) != 1 ||
			history[0].CurrentRevisionID != revisionID ||
			history[0].ParentRevisionID == nil ||
			*history[0].ParentRevisionID != reservation.RevisionID ||
			history[0].MessageKeyID != 1 {
			t.Fatalf("revised history for %s = %#v, %v", principal.DeviceID, history, historyErr)
		}
	}
	var revisionCount, wrappedKeyCount int
	if err = pool.QueryRow(ctx, `SELECT count(*) FROM personal_message_revisions
		WHERE chat_id=$1 AND message_id=$2`, chat.ID, messageID).Scan(&revisionCount); err != nil {
		t.Fatal(err)
	}
	if err = pool.QueryRow(ctx, `SELECT count(*) FROM personal_message_keys
		WHERE chat_id=$1 AND message_id=$2`, chat.ID, messageID).Scan(&wrappedKeyCount); err != nil {
		t.Fatal(err)
	}
	if revisionCount != 2 || wrappedKeyCount != 4 {
		t.Fatalf("immutable revision rows=%d wrapped keys=%d", revisionCount, wrappedKeyCount)
	}

	_, err = pool.Exec(ctx, `INSERT INTO prekeys(device_id,key_id,kind,public_key)
		VALUES($1,1,'onetime',$2)`, deviceB, make([]byte, 32))
	if err != nil {
		t.Fatal(err)
	}
	bundles, err := service.GetKeyBundles(ctx, principalA, userB)
	if err != nil || len(bundles) != 1 || bundles[0].SignedPrekey != nil || len(bundles[0].OneTimePrekeys) != 0 {
		t.Fatalf("envelope-only directory = %#v, %v", bundles, err)
	}
	if bundles[0].SigningIdentityKey != base64.StdEncoding.EncodeToString(make([]byte, ed25519.PublicKeySize)) {
		t.Fatalf("directory signing identity = %q", bundles[0].SigningIdentityKey)
	}
	var consumed *time.Time
	if err = pool.QueryRow(ctx, `SELECT consumed_at FROM prekeys
		WHERE device_id=$1 AND kind='onetime' AND key_id=1`, deviceB).Scan(&consumed); err != nil || consumed != nil {
		t.Fatalf("one-time prekey was consumed: %v, %v", consumed, err)
	}

	var payload string
	if err = pool.QueryRow(ctx, `SELECT payload::text FROM outbox_events
		WHERE aggregate_id=$1 AND topic='personal_message.created'`, chat.ID).Scan(&payload); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(payload, "ciphertext") || strings.Contains(payload, "wrap-a") {
		t.Fatalf("outbox contains cryptographic payload: %s", payload)
	}
	if err = pool.QueryRow(ctx, `SELECT payload::text FROM outbox_events
		WHERE aggregate_id=$1 AND topic='personal_message.edited'`, chat.ID).Scan(&payload); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(payload, "ciphertext") || strings.Contains(payload, "revision-wrap") {
		t.Fatalf("edit outbox contains cryptographic payload: %s", payload)
	}
}

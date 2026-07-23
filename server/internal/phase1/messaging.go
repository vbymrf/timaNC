package phase1

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
)

type Chat struct {
	ID                     string    `json:"id"`
	Peer                   User      `json:"peer"`
	ConversationHomeRegion string    `json:"conversation_home_region"`
	CreatedAt              time.Time `json:"created_at"`
	UnreadCount            int       `json:"unread_count"`
}

type WrappedKey struct {
	DeviceID        string `json:"device_id"`
	WrappedKey      string `json:"wrapped_key"`
	ProtocolVersion int    `json:"protocol_version"`
	KeyCommitment   string `json:"key_commitment"`
}

type PrivateDocument struct {
	EncryptedNodes    []string        `json:"encrypted_nodes"`
	Markup            json.RawMessage `json:"markup,omitempty"`
	EncryptedMetadata string          `json:"encrypted_metadata,omitempty"`
	Metadata          json.RawMessage `json:"metadata"`
	ProtocolVersion   int             `json:"protocol_version"`
	PresenceBitmap    uint32          `json:"presence_bitmap"`
	KeyCommitment     string          `json:"key_commitment"`
	EscrowBlob        string          `json:"escrow_blob"`
	RatchetEnvelope   string          `json:"ratchet_envelope,omitempty"`
	Signature         string          `json:"signature"`
}

type PrivateMessageWrite struct {
	SenderID     string          `json:"sender_id,omitempty"`
	MessageID    string          `json:"message_id"`
	RevisionID   string          `json:"revision_id"`
	MessageKeyID int64           `json:"message_key_id"`
	Document     PrivateDocument `json:"document"`
	WrappedKeys  []WrappedKey    `json:"wrapped_keys"`
}

type PrivateMessage struct {
	ID                string          `json:"id"`
	ConversationID    string          `json:"conversation_id"`
	SenderID          string          `json:"sender_id"`
	SenderDeviceID    string          `json:"sender_device_id"`
	CurrentRevisionID string          `json:"current_revision_id"`
	MessageKeyID      int64           `json:"message_key_id"`
	ParentRevisionID  *string         `json:"parent_revision_id"`
	CreatedAt         time.Time       `json:"created_at"`
	Document          PrivateDocument `json:"document"`
	WrappedKeys       []WrappedKey    `json:"wrapped_keys"`
}

type Reservation struct {
	MessageID  string    `json:"message_id"`
	RevisionID string    `json:"revision_id"`
	ExpiresAt  time.Time `json:"expires_at"`
}

func (s *Service) CreateChat(ctx context.Context, p Principal, peerID, idemKey string, request []byte) (Chat, int, error) {
	if _, err := uuidBytes(peerID); err != nil || peerID == p.UserID {
		return Chat{}, 0, ErrInvalid
	}
	hash := sha256.Sum256(request)
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return Chat{}, 0, err
	}
	defer tx.Rollback(ctx)
	replayed, status, body, err := beginIdempotency(ctx, tx, p.DeviceID, idemKey, "create_chat", hash[:])
	if err != nil {
		return Chat{}, 0, err
	}
	if replayed {
		var out Chat
		if err := json.Unmarshal(body, &out); err != nil {
			return Chat{}, 0, err
		}
		return out, status, tx.Commit(ctx)
	}
	a, b := p.UserID, peerID
	if a > b {
		a, b = b, a
	}
	id, _ := NewUUID()
	var chat Chat
	err = tx.QueryRow(ctx, `WITH peer AS (
			SELECT user_id,account_type,display_name,account_home_region,created_at
			FROM users WHERE user_id=$4 AND deleted_at IS NULL
		), ins AS (
			INSERT INTO chats(chat_id,user_a,user_b,conversation_home_region)
			SELECT $1,$2,$3,account_home_region FROM peer
			ON CONFLICT(user_a,user_b) DO UPDATE SET user_a=excluded.user_a
			RETURNING chat_id,conversation_home_region,created_at
		)
		SELECT ins.chat_id,peer.user_id,peer.account_type,peer.display_name,peer.account_home_region,
		       peer.created_at,ins.conversation_home_region,ins.created_at FROM ins,peer`,
		id, a, b, peerID).Scan(&chat.ID, &chat.Peer.ID, &chat.Peer.AccountType, &chat.Peer.DisplayName,
		&chat.Peer.AccountHomeRegion, &chat.Peer.CreatedAt, &chat.ConversationHomeRegion, &chat.CreatedAt)
	if err == pgx.ErrNoRows {
		return Chat{}, 0, ErrNotFound
	}
	if err != nil {
		return Chat{}, 0, err
	}
	chat.UnreadCount = 0
	response, _ := json.Marshal(chat)
	if err = finishIdempotency(ctx, tx, p.DeviceID, idemKey, 201, response); err != nil {
		return Chat{}, 0, err
	}
	if err = tx.Commit(ctx); err != nil {
		return Chat{}, 0, err
	}
	return chat, 201, nil
}

func (s *Service) ListChats(ctx context.Context, p Principal, limit int) ([]Chat, error) {
	if limit < 1 || limit > 100 {
		limit = 50
	}
	rows, err := s.DB.Query(ctx, `SELECT c.chat_id,u.user_id,u.account_type,u.display_name,u.account_home_region,
		u.created_at,c.conversation_home_region,c.created_at
		FROM chats c JOIN users u ON u.user_id=CASE WHEN c.user_a=$1 THEN c.user_b ELSE c.user_a END
		WHERE c.user_a=$1 OR c.user_b=$1 ORDER BY c.created_at DESC LIMIT $2`, p.UserID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Chat
	for rows.Next() {
		var c Chat
		if err := rows.Scan(&c.ID, &c.Peer.ID, &c.Peer.AccountType, &c.Peer.DisplayName,
			&c.Peer.AccountHomeRegion, &c.Peer.CreatedAt, &c.ConversationHomeRegion, &c.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (s *Service) ReserveMessageID(ctx context.Context, p Principal, chatID, idemKey string) (Reservation, int, error) {
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return Reservation{}, 0, err
	}
	defer tx.Rollback(ctx)
	hash := sha256.Sum256([]byte(chatID))
	replayed, status, body, err := beginIdempotency(ctx, tx, p.DeviceID, idemKey, "reserve_chat_message", hash[:])
	if err != nil {
		return Reservation{}, 0, err
	}
	if replayed {
		var out Reservation
		if err = json.Unmarshal(body, &out); err != nil {
			return Reservation{}, 0, err
		}
		return out, status, tx.Commit(ctx)
	}
	var member bool
	if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM chats WHERE chat_id=$1 AND ($2=user_a OR $2=user_b))`,
		chatID, p.UserID).Scan(&member); err != nil || !member {
		return Reservation{}, 0, ErrForbidden
	}
	if _, err = tx.Exec(ctx, `INSERT INTO chat_message_counters(chat_id) VALUES($1) ON CONFLICT DO NOTHING`, chatID); err != nil {
		return Reservation{}, 0, err
	}
	var id int64
	if err = tx.QueryRow(ctx, `UPDATE chat_message_counters SET next_message_id=next_message_id+1
		WHERE chat_id=$1 RETURNING next_message_id-1`, chatID).Scan(&id); err != nil {
		return Reservation{}, 0, err
	}
	revisionID, err := NewUUID()
	if err != nil {
		return Reservation{}, 0, err
	}
	expires := s.Now().UTC().Add(5 * time.Minute)
	if _, err = tx.Exec(ctx, `INSERT INTO message_id_reservations
		(chat_id,message_id,revision_id,device_id,expires_at) VALUES($1,$2,$3,$4,$5)`,
		chatID, id, revisionID, p.DeviceID, expires); err != nil {
		return Reservation{}, 0, err
	}
	out := Reservation{MessageID: strconv.FormatInt(id, 10), RevisionID: revisionID, ExpiresAt: expires}
	response, _ := json.Marshal(out)
	if err = finishIdempotency(ctx, tx, p.DeviceID, idemKey, 201, response); err != nil {
		return Reservation{}, 0, err
	}
	if err = tx.Commit(ctx); err != nil {
		return Reservation{}, 0, err
	}
	return out, 201, nil
}

func (s *Service) SendMessage(ctx context.Context, p Principal, chatID, idemKey string, request []byte, in PrivateMessageWrite) (PrivateMessage, int, error) {
	hash := sha256.Sum256(request)
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return PrivateMessage{}, 0, err
	}
	defer tx.Rollback(ctx)
	replayed, status, body, err := beginIdempotency(ctx, tx, p.DeviceID, idemKey, "send_private_message", hash[:])
	if err != nil {
		return PrivateMessage{}, 0, err
	}
	if replayed {
		var out PrivateMessage
		if err := json.Unmarshal(body, &out); err != nil {
			return PrivateMessage{}, 0, err
		}
		return out, status, tx.Commit(ctx)
	}
	if err := s.validateMessage(ctx, p, chatID, in, nil, 1); err != nil {
		return PrivateMessage{}, 0, err
	}
	messageID, _ := parseMessageID(in.MessageID)
	var owner, member bool
	err = tx.QueryRow(ctx, `SELECT r.device_id=$3,
		EXISTS(SELECT 1 FROM chats c WHERE c.chat_id=$1 AND ($4=c.user_a OR $4=c.user_b))
		FROM message_id_reservations r
		WHERE r.chat_id=$1 AND r.message_id=$2 AND r.revision_id=$5
		  AND r.consumed_at IS NULL AND r.expires_at>now()
		FOR UPDATE`, chatID, messageID, p.DeviceID, p.UserID, in.RevisionID).Scan(&owner, &member)
	if err == pgx.ErrNoRows || !owner {
		return PrivateMessage{}, 0, ErrConflict
	}
	if err != nil {
		return PrivateMessage{}, 0, err
	}
	if !member {
		return PrivateMessage{}, 0, ErrForbidden
	}
	nodes, _ := decodeNodes(in.Document.EncryptedNodes)
	markup, _, _ := validateAndCanonicalizeMarkup(in.Document.Markup, len(nodes))
	metadata, _, _ := canonicalMetadata(in.Document.Metadata)
	commitment, _ := decodeExact(in.Document.KeyCommitment, 32)
	escrow, _ := decodeNonEmpty(in.Document.EscrowBlob, 1<<20)
	encryptedMetadata, _ := decodeOptional(in.Document.EncryptedMetadata, 1<<20)
	ratchet, _ := decodeOptional(in.Document.RatchetEnvelope, 1<<20)
	signature, _ := decodeExact(in.Document.Signature, ed25519.SignatureSize)
	if _, err = tx.Exec(ctx, `INSERT INTO personal_messages
		(message_id,chat_id,sender_id,sender_device,message_key_id,encrypted_nodes,markup,encrypted_metadata,metadata,
		 presence_bitmap,key_commitment,current_revision_id,escrow_blob,ratchet_envelope,signature)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)`,
		messageID, chatID, p.UserID, p.DeviceID, in.MessageKeyID, nullableNodes(nodes),
		nullableJSON(markup), nullableBytes(encryptedMetadata), metadata,
		in.Document.PresenceBitmap, commitment, in.RevisionID, escrow, nullableBytes(ratchet), signature); err != nil {
		return PrivateMessage{}, 0, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO personal_message_revisions
		(chat_id,message_id,revision_id,revision_number,message_key_id,encrypted_nodes,markup,encrypted_metadata,metadata,
		 presence_bitmap,key_commitment,escrow_blob,signature)
		VALUES($1,$2,$3,1,$4,$5,$6,$7,$8,$9,$10,$11,$12)`,
		chatID, messageID, in.RevisionID, in.MessageKeyID, nullableNodes(nodes), nullableJSON(markup),
		nullableBytes(encryptedMetadata), metadata, in.Document.PresenceBitmap, commitment, escrow, signature); err != nil {
		return PrivateMessage{}, 0, err
	}
	for _, wrap := range in.WrappedKeys {
		wrapped, _ := decodeNonEmpty(wrap.WrappedKey, 65536)
		if _, err = tx.Exec(ctx, `INSERT INTO personal_message_keys
			(message_id,chat_id,revision_id,recipient_key,wrapped_key,key_commitment)
			VALUES($1,$2,$3,$4,$5,$6)`,
			messageID, chatID, in.RevisionID, wrap.DeviceID, wrapped, commitment); err != nil {
			return PrivateMessage{}, 0, err
		}
	}
	now := s.Now().UTC()
	if _, err = tx.Exec(ctx, `UPDATE message_id_reservations SET consumed_at=$3
		WHERE chat_id=$1 AND message_id=$2 AND revision_id=$4`, chatID, messageID, now, in.RevisionID); err != nil {
		return PrivateMessage{}, 0, err
	}
	eventID, _ := NewUUID()
	if _, err = tx.Exec(ctx, `INSERT INTO outbox_events(event_id,aggregate_id,topic,payload)
		VALUES($1,$2,'personal_message.created',$3)`, eventID, chatID,
		map[string]any{"chat_id": chatID, "message_id": in.MessageID,
			"sender_id": p.UserID, "sender_device_id": p.DeviceID}); err != nil {
		return PrivateMessage{}, 0, err
	}
	out := PrivateMessage{ID: in.MessageID, ConversationID: chatID,
		SenderID: p.UserID, SenderDeviceID: p.DeviceID, CurrentRevisionID: in.RevisionID,
		MessageKeyID: in.MessageKeyID, ParentRevisionID: nil, CreatedAt: now,
		Document: in.Document, WrappedKeys: in.WrappedKeys}
	response, _ := json.Marshal(out)
	if err = finishIdempotency(ctx, tx, p.DeviceID, idemKey, 201, response); err != nil {
		return PrivateMessage{}, 0, err
	}
	if err = tx.Commit(ctx); err != nil {
		return PrivateMessage{}, 0, err
	}
	return out, 201, nil
}

func (s *Service) validateMessage(
	ctx context.Context,
	p Principal,
	chatID string,
	in PrivateMessageWrite,
	parentRevisionID *string,
	expectedRevision int64,
) error {
	d := in.Document
	messageID, err := parseMessageID(in.MessageID)
	if err != nil || in.MessageKeyID < 0 {
		return ErrInvalid
	}
	if _, err = uuidBytes(in.RevisionID); err != nil {
		return ErrInvalid
	}
	if in.SenderID != "" && in.SenderID != p.UserID || d.ProtocolVersion != 2 ||
		len(in.WrappedKeys) == 0 {
		return ErrInvalid
	}
	nodes, err := decodeNodes(d.EncryptedNodes)
	if err != nil {
		return err
	}
	markup, markupInfo, err := validateAndCanonicalizeMarkup(d.Markup, len(nodes))
	if err != nil || len(nodes) == 0 && !markupInfo.HasMedia {
		return ErrInvalid
	}
	for _, mediaID := range markupInfo.MediaIDs {
		var ready bool
		if err = s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM media_objects
			WHERE media_id=$1 AND status='ready' AND is_encrypted
			  AND content_mode='private' AND scope_type='chat' AND scope_id=$2)`,
			mediaID, chatID).Scan(&ready); err != nil {
			return err
		}
		if !ready {
			return ErrInvalid
		}
	}
	expectedBitmap := uint32(0)
	if len(nodes) > 0 {
		expectedBitmap |= 1
	}
	if len(markup) > 0 {
		expectedBitmap |= 4
	}
	if d.EncryptedMetadata != "" {
		expectedBitmap |= 8
	}
	if d.PresenceBitmap != expectedBitmap ||
		markupInfo.HasSecrets != (d.EncryptedMetadata != "") {
		return ErrInvalid
	}
	if _, metadata, err := canonicalMetadata(d.Metadata); err != nil || metadata.Revision != expectedRevision {
		return ErrInvalid
	}
	commitment, err := decodeExact(d.KeyCommitment, 32)
	if err != nil {
		return ErrInvalid
	}
	escrow, err := decodeNonEmpty(d.EscrowBlob, 1<<20)
	if err != nil {
		return err
	}
	headers, err := parseEscrowBlob(escrow)
	if err != nil || !subtleEqual(commitment, headers.KeyCommitment) {
		return ErrInvalid
	}
	var region string
	if err = s.DB.QueryRow(ctx, `SELECT conversation_home_region FROM chats
		WHERE chat_id=$1 AND ($2=user_a OR $2=user_b)`, chatID, p.UserID).Scan(&region); err != nil {
		return ErrForbidden
	}
	epoch := currentQuarter(s.Now().UTC())
	regionID := uint32(2)
	if region == "RU" {
		regionID = 1
	}
	const acceptedShard = uint32(0)
	if headers.Region != regionID || headers.EpochID != epoch || headers.ShardID != acceptedShard ||
		headers.KeyID != fmt.Sprintf("%s-%s-%d", region, epoch, acceptedShard) {
		return ErrInvalid
	}
	input, err := personalSignatureInput(d, messageID, in.RevisionID, parentRevisionID, in.MessageKeyID,
		p.UserID, p.DeviceID, chatID)
	if err != nil {
		return err
	}
	signature, err := decodeExact(d.Signature, ed25519.SignatureSize)
	if err != nil || !canonicalEd25519Signature(signature) {
		return ErrInvalid
	}
	var public []byte
	if err = s.DB.QueryRow(ctx, `SELECT signing_pubkey FROM devices WHERE device_id=$1 AND user_id=$2 AND revoked_at IS NULL`,
		p.DeviceID, p.UserID).Scan(&public); err != nil {
		return fmt.Errorf("signing key lookup: %w", ErrInvalid)
	}
	if !ed25519.Verify(ed25519.PublicKey(public), input, signature) {
		return fmt.Errorf("signature verification: %w", ErrInvalid)
	}
	rows, err := s.DB.Query(ctx, `SELECT d.device_id FROM chats c JOIN devices d
		ON d.user_id IN(c.user_a,c.user_b) AND d.revoked_at IS NULL WHERE c.chat_id=$1
		AND ($2=c.user_a OR $2=c.user_b)`, chatID, p.UserID)
	if err != nil {
		return err
	}
	defer rows.Close()
	active := map[string]bool{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return err
		}
		active[id] = true
	}
	if len(active) == 0 {
		return ErrForbidden
	}
	seen := map[string]bool{}
	for _, wrap := range in.WrappedKeys {
		if wrap.ProtocolVersion != 2 || !active[wrap.DeviceID] || seen[wrap.DeviceID] ||
			wrap.KeyCommitment != d.KeyCommitment {
			return ErrInvalid
		}
		if _, err := decodeNonEmpty(wrap.WrappedKey, 65536); err != nil {
			return err
		}
		if _, err := decodeExact(wrap.KeyCommitment, 32); err != nil {
			return ErrInvalid
		}
		seen[wrap.DeviceID] = true
	}
	if len(seen) != len(active) {
		return ErrInvalid
	}
	return nil
}

func (s *Service) ListMessages(ctx context.Context, p Principal, chatID string, limit int) ([]PrivateMessage, error) {
	if limit < 1 || limit > 100 {
		limit = 50
	}
	rows, err := s.DB.Query(ctx, `SELECT m.message_id,m.sender_id,m.sender_device,m.current_revision_id,
		m.message_key_id,r.parent_revision_id,m.created_at,
		m.encrypted_nodes,m.markup,m.encrypted_metadata,m.metadata,m.presence_bitmap,
		m.key_commitment,m.escrow_blob,m.ratchet_envelope,m.signature,k.wrapped_key
		FROM chats c JOIN personal_messages m ON m.chat_id=c.chat_id
		JOIN personal_message_revisions r ON r.chat_id=m.chat_id AND r.message_id=m.message_id
		  AND r.revision_id=m.current_revision_id
		JOIN personal_message_keys k ON k.chat_id=m.chat_id AND k.message_id=m.message_id
		  AND k.revision_id=m.current_revision_id
		WHERE c.chat_id=$1 AND ($2=c.user_a OR $2=c.user_b) AND k.recipient_key=$3
		ORDER BY m.message_id DESC LIMIT $4`, chatID, p.UserID, p.DeviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PrivateMessage
	for rows.Next() {
		var m PrivateMessage
		var id int64
		var nodes [][]byte
		var encryptedMetadata, markup, metadata, commitment, escrow, ratchet, signature, wrapped []byte
		if err = rows.Scan(&id, &m.SenderID, &m.SenderDeviceID, &m.CurrentRevisionID,
			&m.MessageKeyID, &m.ParentRevisionID, &m.CreatedAt,
			&nodes, &markup, &encryptedMetadata, &metadata, &m.Document.PresenceBitmap,
			&commitment, &escrow, &ratchet, &signature, &wrapped); err != nil {
			return nil, err
		}
		m.ID, m.ConversationID = strconv.FormatInt(id, 10), chatID
		m.Document.ProtocolVersion, m.Document.Metadata = 2, metadata
		m.Document.KeyCommitment = base64.StdEncoding.EncodeToString(commitment)
		m.Document.EscrowBlob = base64.StdEncoding.EncodeToString(escrow)
		m.Document.Signature = base64.StdEncoding.EncodeToString(signature)
		for _, node := range nodes {
			m.Document.EncryptedNodes = append(m.Document.EncryptedNodes, base64.StdEncoding.EncodeToString(node))
		}
		if len(markup) > 0 {
			m.Document.Markup = json.RawMessage(markup)
		}
		if len(encryptedMetadata) > 0 {
			m.Document.EncryptedMetadata = base64.StdEncoding.EncodeToString(encryptedMetadata)
		}
		if len(ratchet) > 0 {
			m.Document.RatchetEnvelope = base64.StdEncoding.EncodeToString(ratchet)
		}
		m.WrappedKeys = []WrappedKey{{DeviceID: p.DeviceID,
			WrappedKey: base64.StdEncoding.EncodeToString(wrapped), ProtocolVersion: 2,
			KeyCommitment: m.Document.KeyCommitment}}
		out = append(out, m)
	}
	if err = rows.Err(); err != nil {
		return nil, err
	}
	if out == nil {
		var member bool
		if err = s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM chats WHERE chat_id=$1 AND ($2=user_a OR $2=user_b))`,
			chatID, p.UserID).Scan(&member); err != nil {
			return nil, err
		}
		if !member {
			return nil, ErrForbidden
		}
	}
	return out, nil
}

func beginIdempotency(ctx context.Context, tx pgx.Tx, deviceID, key, operation string, hash []byte) (bool, int, []byte, error) {
	if _, err := uuidBytes(key); err != nil {
		return false, 0, nil, ErrInvalid
	}
	if _, err := tx.Exec(ctx, `DELETE FROM idempotency_requests
		WHERE device_id=$1 AND idempotency_key=$2 AND expires_at<=now()`, deviceID, key); err != nil {
		return false, 0, nil, err
	}
	_, err := tx.Exec(ctx, `INSERT INTO idempotency_requests(device_id,idempotency_key,operation,request_hash)
		VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING`, deviceID, key, operation, hash)
	if err != nil {
		return false, 0, nil, err
	}
	var stored []byte
	var storedOperation string
	var status *int
	var body []byte
	err = tx.QueryRow(ctx, `SELECT operation,request_hash,response_status,response_body
		FROM idempotency_requests WHERE device_id=$1 AND idempotency_key=$2 FOR UPDATE`,
		deviceID, key).Scan(&storedOperation, &stored, &status, &body)
	if err != nil {
		return false, 0, nil, err
	}
	if storedOperation != operation || !subtleEqual(stored, hash) {
		return false, 0, nil, ErrConflict
	}
	if status != nil {
		return true, *status, body, nil
	}
	return false, 0, nil, nil
}

func finishIdempotency(ctx context.Context, tx pgx.Tx, deviceID, key string, status int, body []byte) error {
	_, err := tx.Exec(ctx, `UPDATE idempotency_requests SET response_status=$3,response_body=$4
		WHERE device_id=$1 AND idempotency_key=$2`, deviceID, key, status, body)
	return err
}

func decodeNonEmpty(v string, max int) ([]byte, error) {
	b, err := base64.StdEncoding.Strict().DecodeString(v)
	if err != nil || len(b) == 0 || len(b) > max {
		return nil, ErrInvalid
	}
	return b, nil
}

func decodeOptional(v string, max int) ([]byte, error) {
	if v == "" {
		return nil, nil
	}
	return decodeNonEmpty(v, max)
}

func decodeNodes(values []string) ([][]byte, error) {
	out := make([][]byte, len(values))
	for i := range values {
		var err error
		out[i], err = decodeNonEmpty(values[i], 1<<20)
		if err != nil {
			return nil, err
		}
	}
	return out, nil
}

func nullableBytes(v []byte) any {
	if len(v) == 0 {
		return nil
	}
	return v
}

func nullableNodes(v [][]byte) any {
	if len(v) == 0 {
		return nil
	}
	return v
}

func nullableJSON(v []byte) any {
	if len(v) == 0 {
		return nil
	}
	return v
}

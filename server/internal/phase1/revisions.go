package phase1

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/json"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
)

type PrivateRevision struct {
	ID               string          `json:"id"`
	ParentRevisionID string          `json:"parent_revision_id"`
	RevisionNumber   int64           `json:"revision_number"`
	CreatedAt        time.Time       `json:"created_at"`
	Document         PrivateDocument `json:"document"`
}

func (s *Service) ReviseMessage(
	ctx context.Context,
	p Principal,
	chatID, pathMessageID, idemKey string,
	request []byte,
	in PrivateMessageWrite,
) (PrivateRevision, int, error) {
	messageID, err := parseMessageID(pathMessageID)
	if err != nil || in.MessageID != pathMessageID {
		return PrivateRevision{}, 0, ErrInvalid
	}
	hash := sha256.Sum256(request)
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return PrivateRevision{}, 0, err
	}
	defer tx.Rollback(ctx)
	operation := revisionOperation(chatID, messageID)
	replayed, status, body, err := beginIdempotency(ctx, tx, p.DeviceID, idemKey, operation, hash[:])
	if err != nil {
		return PrivateRevision{}, 0, err
	}
	if replayed {
		var out PrivateRevision
		if err = json.Unmarshal(body, &out); err != nil {
			return PrivateRevision{}, 0, err
		}
		return out, status, tx.Commit(ctx)
	}

	var senderID, currentRevisionID string
	var currentRevisionNumber int64
	err = tx.QueryRow(ctx, `SELECT m.sender_id,m.current_revision_id,r.revision_number
		FROM personal_messages m JOIN personal_message_revisions r
		  ON r.chat_id=m.chat_id AND r.message_id=m.message_id
		  AND r.revision_id=m.current_revision_id
		WHERE m.chat_id=$1 AND m.message_id=$2 FOR UPDATE OF m`,
		chatID, messageID).Scan(&senderID, &currentRevisionID, &currentRevisionNumber)
	if err == pgx.ErrNoRows {
		return PrivateRevision{}, 0, ErrNotFound
	}
	if err != nil {
		return PrivateRevision{}, 0, err
	}
	if senderID != p.UserID {
		return PrivateRevision{}, 0, ErrForbidden
	}
	if in.RevisionID == currentRevisionID {
		return PrivateRevision{}, 0, ErrConflict
	}
	if err = s.validateMessage(
		ctx, p, chatID, in, &currentRevisionID, currentRevisionNumber+1,
	); err != nil {
		return PrivateRevision{}, 0, err
	}

	nodes, _ := decodeNodes(in.Document.EncryptedNodes)
	markup, _, _ := validateAndCanonicalizeMarkup(in.Document.Markup, len(nodes))
	metadata, _, _ := canonicalMetadata(in.Document.Metadata)
	commitment, _ := decodeExact(in.Document.KeyCommitment, 32)
	escrow, _ := decodeNonEmpty(in.Document.EscrowBlob, 1<<20)
	encryptedMetadata, _ := decodeOptional(in.Document.EncryptedMetadata, 1<<20)
	ratchet, _ := decodeOptional(in.Document.RatchetEnvelope, 1<<20)
	signature, _ := decodeExact(in.Document.Signature, ed25519.SignatureSize)
	var createdAt time.Time
	err = tx.QueryRow(ctx, `INSERT INTO personal_message_revisions
		(chat_id,message_id,revision_id,parent_revision_id,revision_number,message_key_id,
		 encrypted_nodes,markup,encrypted_metadata,metadata,presence_bitmap,key_commitment,escrow_blob,signature)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
		RETURNING created_at`,
		chatID, messageID, in.RevisionID, currentRevisionID, currentRevisionNumber+1,
		in.MessageKeyID, nullableNodes(nodes), nullableJSON(markup), nullableBytes(encryptedMetadata), metadata,
		in.Document.PresenceBitmap, commitment, escrow, signature).Scan(&createdAt)
	if err != nil {
		return PrivateRevision{}, 0, err
	}
	if _, err = tx.Exec(ctx, `UPDATE personal_messages SET
		message_key_id=$3,encrypted_nodes=$4,markup=$5,encrypted_metadata=$6,metadata=$7,
		presence_bitmap=$8,key_commitment=$9,current_revision_id=$10,escrow_blob=$11,
		ratchet_envelope=$12,signature=$13
		WHERE chat_id=$1 AND message_id=$2`,
		chatID, messageID, in.MessageKeyID, nullableNodes(nodes), nullableJSON(markup),
		nullableBytes(encryptedMetadata), metadata,
		in.Document.PresenceBitmap, commitment, in.RevisionID, escrow,
		nullableBytes(ratchet), signature); err != nil {
		return PrivateRevision{}, 0, err
	}
	for _, wrap := range in.WrappedKeys {
		wrapped, _ := decodeNonEmpty(wrap.WrappedKey, 65536)
		if _, err = tx.Exec(ctx, `INSERT INTO personal_message_keys
			(message_id,chat_id,revision_id,recipient_key,wrapped_key,key_commitment)
			VALUES($1,$2,$3,$4,$5,$6)`,
			messageID, chatID, in.RevisionID, wrap.DeviceID, wrapped, commitment); err != nil {
			return PrivateRevision{}, 0, err
		}
	}
	eventID, _ := NewUUID()
	if _, err = tx.Exec(ctx, `INSERT INTO outbox_events(event_id,aggregate_id,topic,payload)
		VALUES($1,$2,'personal_message.edited',$3)`, eventID, chatID,
		map[string]any{
			"chat_id": chatID, "message_id": pathMessageID,
			"sender_id": p.UserID, "sender_device_id": p.DeviceID,
		}); err != nil {
		return PrivateRevision{}, 0, err
	}
	out := PrivateRevision{
		ID: in.RevisionID, ParentRevisionID: currentRevisionID,
		RevisionNumber: currentRevisionNumber + 1, CreatedAt: createdAt,
		Document: in.Document,
	}
	response, _ := json.Marshal(out)
	if err = finishIdempotency(ctx, tx, p.DeviceID, idemKey, 201, response); err != nil {
		return PrivateRevision{}, 0, err
	}
	if err = tx.Commit(ctx); err != nil {
		return PrivateRevision{}, 0, err
	}
	return out, 201, nil
}

func revisionOperation(chatID string, messageID int64) string {
	return "revise_private_message:" + chatID + ":" + strconv.FormatInt(messageID, 10)
}

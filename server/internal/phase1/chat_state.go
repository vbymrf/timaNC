package phase1

import (
	"context"
	"strconv"
	"time"
)

type ReadState struct {
	ConversationID    string    `json:"conversation_id"`
	LastReadMessageID string    `json:"last_read_message_id"`
	ReadAt            time.Time `json:"read_at"`
}

func (s *Service) MarkChatRead(
	ctx context.Context,
	p Principal,
	chatID string,
	messageID int64,
) (ReadState, error) {
	if messageID < 1 {
		return ReadState{}, ErrInvalid
	}
	var allowed bool
	err := s.DB.QueryRow(ctx, `SELECT EXISTS(
		SELECT 1 FROM chats c JOIN personal_messages m ON m.chat_id=c.chat_id
		WHERE c.chat_id=$1 AND m.message_id=$3 AND ($2=c.user_a OR $2=c.user_b)
	)`, chatID, p.UserID, messageID).Scan(&allowed)
	if err != nil {
		return ReadState{}, err
	}
	if !allowed {
		return ReadState{}, ErrNotFound
	}
	now := s.Now().UTC()
	var stored int64
	var readAt time.Time
	err = s.DB.QueryRow(ctx, `INSERT INTO chat_read_state(chat_id,user_id,last_read_message_id,read_at)
		VALUES($1,$2,$3,$4)
		ON CONFLICT(chat_id,user_id) DO UPDATE SET
		  last_read_message_id=greatest(chat_read_state.last_read_message_id,excluded.last_read_message_id),
		  read_at=CASE WHEN excluded.last_read_message_id>chat_read_state.last_read_message_id
		    THEN excluded.read_at ELSE chat_read_state.read_at END
		RETURNING last_read_message_id,read_at`, chatID, p.UserID, messageID, now).Scan(&stored, &readAt)
	if err != nil {
		return ReadState{}, err
	}
	return ReadState{
		ConversationID: chatID, LastReadMessageID: strconv.FormatInt(stored, 10), ReadAt: readAt,
	}, nil
}

func (s *Service) DeleteChatMessage(
	ctx context.Context,
	p Principal,
	chatID string,
	messageID int64,
) error {
	command, err := s.DB.Exec(ctx, `UPDATE personal_messages SET deleted=true,deleted_at=now()
		WHERE chat_id=$1 AND message_id=$2 AND sender_id=$3 AND deleted=false`,
		chatID, messageID, p.UserID)
	if err != nil {
		return err
	}
	if command.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

package phase1

import (
	"context"
	"encoding/json"
	"regexp"
	"strings"

	"github.com/jackc/pgx/v5"
)

type UserProfile struct {
	User
	Username      *string `json:"username,omitempty"`
	Bio           *string `json:"bio,omitempty"`
	AvatarMediaID *string `json:"avatar_media_id,omitempty"`
}

type UserPatch struct {
	Username      *string         `json:"username,omitempty"`
	DisplayName   *string         `json:"display_name,omitempty"`
	Bio           *string         `json:"bio,omitempty"`
	AvatarMediaID json.RawMessage `json:"avatar_media_id,omitempty"`
}

var usernamePattern = regexp.MustCompile(`^[a-zA-Z0-9_]{3,32}$`)
var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$`)

func (s *Service) GetCurrentUser(ctx context.Context, p Principal) (UserProfile, error) {
	var out UserProfile
	err := s.DB.QueryRow(ctx, `SELECT user_id,account_type,display_name,account_home_region,
		created_at,username,bio,avatar_media_id::text FROM users
		WHERE user_id=$1 AND deleted_at IS NULL AND blocked_at IS NULL`, p.UserID).Scan(
		&out.ID, &out.AccountType, &out.DisplayName, &out.AccountHomeRegion, &out.CreatedAt,
		&out.Username, &out.Bio, &out.AvatarMediaID,
	)
	if err != nil {
		return UserProfile{}, ErrNotFound
	}
	return out, nil
}

func (s *Service) UpdateCurrentUser(
	ctx context.Context,
	p Principal,
	in UserPatch,
) (UserProfile, error) {
	if in.Username == nil && in.DisplayName == nil && in.Bio == nil && in.AvatarMediaID == nil {
		return UserProfile{}, ErrInvalid
	}
	if in.Username != nil && !usernamePattern.MatchString(*in.Username) {
		return UserProfile{}, ErrInvalid
	}
	if in.DisplayName != nil && (strings.TrimSpace(*in.DisplayName) == "" || len(*in.DisplayName) > 100) {
		return UserProfile{}, ErrInvalid
	}
	if in.Bio != nil && len(*in.Bio) > 1000 {
		return UserProfile{}, ErrInvalid
	}
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return UserProfile{}, err
	}
	defer tx.Rollback(ctx)
	if in.Username != nil {
		if _, err = tx.Exec(ctx, `UPDATE users SET username=$2 WHERE user_id=$1`, p.UserID, *in.Username); err != nil {
			return UserProfile{}, ErrConflict
		}
	}
	if in.DisplayName != nil {
		if _, err = tx.Exec(ctx, `UPDATE users SET display_name=$2 WHERE user_id=$1`, p.UserID, *in.DisplayName); err != nil {
			return UserProfile{}, err
		}
	}
	if in.Bio != nil {
		if _, err = tx.Exec(ctx, `UPDATE users SET bio=$2 WHERE user_id=$1`, p.UserID, *in.Bio); err != nil {
			return UserProfile{}, err
		}
	}
	if in.AvatarMediaID != nil {
		var avatar *string
		if string(in.AvatarMediaID) != "null" {
			var value string
			if json.Unmarshal(in.AvatarMediaID, &value) != nil || !uuidPattern.MatchString(value) {
				return UserProfile{}, ErrInvalid
			}
			avatar = &value
		}
		if _, err = tx.Exec(ctx, `UPDATE users SET avatar_media_id=$2 WHERE user_id=$1`, p.UserID, avatar); err != nil {
			return UserProfile{}, ErrInvalid
		}
	}
	if err = tx.Commit(ctx); err != nil {
		return UserProfile{}, err
	}
	return s.GetCurrentUser(ctx, p)
}

func (s *Service) ListDevices(ctx context.Context, p Principal) ([]Device, error) {
	rows, err := s.DB.Query(ctx, `SELECT device_id,name,platform,created_at,
		coalesce(last_seen,created_at),revoked_at FROM devices
		WHERE user_id=$1 ORDER BY created_at DESC`, p.UserID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		var device Device
		if err = rows.Scan(&device.ID, &device.Name, &device.Platform, &device.CreatedAt,
			&device.LastSeenAt, &device.RevokedAt); err != nil {
			return nil, err
		}
		device.Current = device.ID == p.DeviceID
		out = append(out, device)
	}
	return out, rows.Err()
}

func (s *Service) RevokeDevice(ctx context.Context, p Principal, deviceID string) error {
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	command, err := tx.Exec(ctx, `UPDATE devices SET revoked_at=now()
		WHERE device_id=$1 AND user_id=$2 AND revoked_at IS NULL`, deviceID, p.UserID)
	if err != nil {
		return err
	}
	if command.RowsAffected() == 0 {
		return ErrNotFound
	}
	if _, err = tx.Exec(ctx, `UPDATE sessions SET revoked_at=now()
		WHERE device_id=$1 AND revoked_at IS NULL`, deviceID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

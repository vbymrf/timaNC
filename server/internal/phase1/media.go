package phase1

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"image"
	"image/color"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

type MediaVariantInput struct {
	Name        string `json:"name"`
	ContentType string `json:"content_type"`
	Size        int64  `json:"size"`
	SHA256      string `json:"sha256"`
}

type MediaUploadCreate struct {
	Kind     string              `json:"kind"`
	Variants []MediaVariantInput `json:"variants"`
}

type UploadSlot struct {
	Variant   string            `json:"variant"`
	Method    string            `json:"method"`
	URL       string            `json:"url"`
	Headers   map[string]string `json:"headers"`
	ExpiresAt time.Time         `json:"expires_at"`
}

type MediaUpload struct {
	MediaID     string       `json:"media_id"`
	ContentMode string       `json:"content_mode"`
	Uploads     []UploadSlot `json:"uploads"`
	ExpiresAt   time.Time    `json:"expires_at"`
}

type MediaResource struct {
	ID          string              `json:"id"`
	Kind        string              `json:"kind"`
	ContentMode string              `json:"content_mode"`
	Status      string              `json:"status"`
	Variants    []MediaVariantInput `json:"variants"`
	CreatedAt   time.Time           `json:"created_at"`
}

type MediaAccess struct {
	MediaID   string    `json:"media_id"`
	Variant   string    `json:"variant"`
	URL       string    `json:"url"`
	ExpiresAt time.Time `json:"expires_at"`
}

func (s *Service) CreateChatMediaUpload(
	ctx context.Context,
	p Principal,
	chatID, idemKey string,
	request []byte,
	in MediaUploadCreate,
) (MediaUpload, int, error) {
	if s.MediaStore == nil || validateMediaCreate(in, true) != nil {
		return MediaUpload{}, 0, ErrInvalid
	}
	hash := sha256.Sum256(request)
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return MediaUpload{}, 0, err
	}
	defer tx.Rollback(ctx)
	replayed, status, body, err := beginIdempotency(
		ctx, tx, p.DeviceID, idemKey, "create_chat_media:"+chatID, hash[:],
	)
	if err != nil {
		return MediaUpload{}, 0, err
	}
	if replayed {
		var out MediaUpload
		if err = json.Unmarshal(body, &out); err != nil {
			return MediaUpload{}, 0, err
		}
		return out, status, tx.Commit(ctx)
	}
	var member bool
	if err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM chats
		WHERE chat_id=$1 AND ($2=user_a OR $2=user_b))`, chatID, p.UserID).Scan(&member); err != nil {
		return MediaUpload{}, 0, err
	}
	if !member {
		return MediaUpload{}, 0, ErrForbidden
	}
	out, err := s.createMediaUpload(
		ctx, tx, p, "chat", chatID, "private", in, s.MediaPrivateBucket,
	)
	if err != nil {
		return MediaUpload{}, 0, err
	}
	response, _ := json.Marshal(out)
	if err = finishIdempotency(ctx, tx, p.DeviceID, idemKey, 201, response); err != nil {
		return MediaUpload{}, 0, err
	}
	if err = tx.Commit(ctx); err != nil {
		return MediaUpload{}, 0, err
	}
	return out, 201, nil
}

func (s *Service) CreatePublicMediaUpload(
	ctx context.Context,
	p Principal,
	idemKey string,
	request []byte,
	in MediaUploadCreate,
) (MediaUpload, int, error) {
	if s.MediaStore == nil || validateMediaCreate(in, false) != nil {
		return MediaUpload{}, 0, ErrInvalid
	}
	hash := sha256.Sum256(request)
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return MediaUpload{}, 0, err
	}
	defer tx.Rollback(ctx)
	replayed, status, body, err := beginIdempotency(
		ctx, tx, p.DeviceID, idemKey, "create_public_media", hash[:],
	)
	if err != nil {
		return MediaUpload{}, 0, err
	}
	if replayed {
		var out MediaUpload
		if err = json.Unmarshal(body, &out); err != nil {
			return MediaUpload{}, 0, err
		}
		return out, status, tx.Commit(ctx)
	}
	out, err := s.createMediaUpload(
		ctx, tx, p, "user", p.UserID, "public", in, s.MediaStagingBucket,
	)
	if err != nil {
		return MediaUpload{}, 0, err
	}
	response, _ := json.Marshal(out)
	if err = finishIdempotency(ctx, tx, p.DeviceID, idemKey, 201, response); err != nil {
		return MediaUpload{}, 0, err
	}
	if err = tx.Commit(ctx); err != nil {
		return MediaUpload{}, 0, err
	}
	return out, 201, nil
}

func (s *Service) createMediaUpload(
	ctx context.Context,
	tx pgx.Tx,
	p Principal,
	scopeType, scopeID, contentMode string,
	in MediaUploadCreate,
	bucket string,
) (MediaUpload, error) {
	mediaID, err := NewUUID()
	if err != nil {
		return MediaUpload{}, err
	}
	var total int64
	for _, variant := range in.Variants {
		total += variant.Size
	}
	if _, err = tx.Exec(ctx, `INSERT INTO media_objects
		(media_id,owner_id,is_encrypted,size_bytes,mime_type,status,scope_type,scope_id,content_mode,kind)
		VALUES($1,$2,$3,$4,$5,'uploading',$6,$7,$8,$9)`,
		mediaID, p.UserID, contentMode == "private", total, in.Variants[0].ContentType,
		scopeType, scopeID, contentMode, in.Kind); err != nil {
		return MediaUpload{}, err
	}
	out := MediaUpload{MediaID: mediaID, ContentMode: contentMode}
	for _, variant := range in.Variants {
		checksum, _ := hex.DecodeString(variant.SHA256)
		key := mediaStorageKey(contentMode, scopeID, mediaID, variant.Name)
		if _, err = tx.Exec(ctx, `INSERT INTO media_upload_variants
			(media_id,variant,content_type,size_bytes,checksum,storage_key)
			VALUES($1,$2,$3,$4,$5,$6)`,
			mediaID, variant.Name, variant.ContentType, variant.Size, checksum, key); err != nil {
			return MediaUpload{}, err
		}
		url, expires, presignErr := s.MediaStore.PresignPut(ctx, bucket, key)
		if presignErr != nil {
			return MediaUpload{}, presignErr
		}
		if out.ExpiresAt.IsZero() || expires.Before(out.ExpiresAt) {
			out.ExpiresAt = expires
		}
		out.Uploads = append(out.Uploads, UploadSlot{
			Variant: variant.Name, Method: "PUT", URL: url,
			Headers:   map[string]string{"Content-Type": variant.ContentType},
			ExpiresAt: expires,
		})
	}
	return out, nil
}

func (s *Service) CompleteMediaUpload(
	ctx context.Context,
	p Principal,
	mediaID, idemKey string,
	request []byte,
	variants []MediaVariantInput,
) (MediaResource, int, error) {
	if s.MediaStore == nil {
		return MediaResource{}, 0, ErrInvalid
	}
	hash := sha256.Sum256(request)
	tx, err := s.DB.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return MediaResource{}, 0, err
	}
	defer tx.Rollback(ctx)
	replayed, status, body, err := beginIdempotency(
		ctx, tx, p.DeviceID, idemKey, "complete_media:"+mediaID, hash[:],
	)
	if err != nil {
		return MediaResource{}, 0, err
	}
	if replayed {
		var out MediaResource
		if err = json.Unmarshal(body, &out); err != nil {
			return MediaResource{}, 0, err
		}
		return out, status, tx.Commit(ctx)
	}
	var ownerID, contentMode, kind, objectStatus string
	var createdAt time.Time
	err = tx.QueryRow(ctx, `SELECT owner_id,content_mode,kind,status,created_at
		FROM media_objects WHERE media_id=$1 FOR UPDATE`,
		mediaID).Scan(&ownerID, &contentMode, &kind, &objectStatus, &createdAt)
	if err == pgx.ErrNoRows {
		return MediaResource{}, 0, ErrNotFound
	}
	if err != nil {
		return MediaResource{}, 0, err
	}
	if ownerID != p.UserID {
		return MediaResource{}, 0, ErrForbidden
	}
	if objectStatus != "uploading" {
		return MediaResource{}, 0, ErrConflict
	}
	expected, err := loadExpectedVariants(ctx, tx, mediaID)
	if err != nil || !sameManifest(expected, variants) {
		return MediaResource{}, 0, ErrInvalid
	}
	bucket := s.MediaPrivateBucket
	if contentMode == "public" {
		bucket = s.MediaStagingBucket
	}
	uploaded := make(map[string][]byte, len(expected))
	for _, variant := range expected {
		value, readErr := s.MediaStore.Read(ctx, bucket, variant.storageKey, variant.Size)
		if readErr != nil {
			return MediaResource{}, 0, ErrConflict
		}
		digest := sha256.Sum256(value)
		checksum, _ := hex.DecodeString(variant.SHA256)
		if int64(len(value)) != variant.Size || !bytes.Equal(digest[:], checksum) {
			return MediaResource{}, 0, ErrConflict
		}
		uploaded[variant.Name] = value
	}
	var completed []MediaVariantInput
	if contentMode == "private" {
		for _, variant := range expected {
			if _, err = tx.Exec(ctx, `INSERT INTO media_variants
				(media_id,variant,storage_key,size_bytes,checksum)
				VALUES($1,$2,$3,$4,$5)`,
				mediaID, variant.Name, variant.storageKey, variant.Size,
				mustDecodeHex(variant.SHA256)); err != nil {
				return MediaResource{}, 0, err
			}
			completed = append(completed, variant.MediaVariantInput)
		}
	} else {
		completed, err = s.processPublicImage(ctx, tx, mediaID, uploaded["full"])
		if err != nil {
			_, _ = tx.Exec(ctx, `UPDATE media_objects SET status='blocked',
				threat_scan='{"result":"blocked"}'::jsonb WHERE media_id=$1`, mediaID)
			_, _ = tx.Exec(ctx, `DELETE FROM idempotency_requests
				WHERE device_id=$1 AND idempotency_key=$2`, p.DeviceID, idemKey)
			if commitErr := tx.Commit(ctx); commitErr != nil {
				return MediaResource{}, 0, commitErr
			}
			return MediaResource{}, 0, ErrInvalid
		}
		if err = s.MediaStore.Remove(ctx, s.MediaStagingBucket, expected[0].storageKey); err != nil {
			return MediaResource{}, 0, err
		}
	}
	if _, err = tx.Exec(ctx, `UPDATE media_objects SET status='ready' WHERE media_id=$1`, mediaID); err != nil {
		return MediaResource{}, 0, err
	}
	out := MediaResource{
		ID: mediaID, Kind: kind, ContentMode: contentMode, Status: "ready",
		Variants: completed, CreatedAt: createdAt,
	}
	response, _ := json.Marshal(out)
	if err = finishIdempotency(ctx, tx, p.DeviceID, idemKey, 202, response); err != nil {
		return MediaResource{}, 0, err
	}
	if err = tx.Commit(ctx); err != nil {
		return MediaResource{}, 0, err
	}
	return out, 202, nil
}

func (s *Service) AccessMedia(
	ctx context.Context,
	p Principal,
	mediaID, variant string,
) (MediaAccess, error) {
	if s.MediaStore == nil || !validVariant(variant) {
		return MediaAccess{}, ErrInvalid
	}
	var contentMode, scopeType, scopeID, status, storageKey string
	err := s.DB.QueryRow(ctx, `SELECT o.content_mode,o.scope_type,o.scope_id,o.status,v.storage_key
		FROM media_objects o JOIN media_variants v ON v.media_id=o.media_id
		WHERE o.media_id=$1 AND v.variant=$2`,
		mediaID, variant).Scan(&contentMode, &scopeType, &scopeID, &status, &storageKey)
	if err == pgx.ErrNoRows {
		return MediaAccess{}, ErrNotFound
	}
	if err != nil {
		return MediaAccess{}, err
	}
	if status != "ready" {
		return MediaAccess{}, ErrForbidden
	}
	if contentMode == "private" {
		var member bool
		if scopeType != "chat" {
			return MediaAccess{}, ErrForbidden
		}
		if err = s.DB.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM chats
			WHERE chat_id=$1 AND ($2=user_a OR $2=user_b))`,
			scopeID, p.UserID).Scan(&member); err != nil || !member {
			return MediaAccess{}, ErrForbidden
		}
	}
	bucket := s.MediaPublicBucket
	if contentMode == "private" {
		bucket = s.MediaPrivateBucket
	}
	url, expires, err := s.MediaStore.PresignGet(ctx, bucket, storageKey)
	if err != nil {
		return MediaAccess{}, err
	}
	return MediaAccess{MediaID: mediaID, Variant: variant, URL: url, ExpiresAt: expires}, nil
}

type expectedVariant struct {
	MediaVariantInput
	storageKey string
}

func loadExpectedVariants(ctx context.Context, tx pgx.Tx, mediaID string) ([]expectedVariant, error) {
	rows, err := tx.Query(ctx, `SELECT variant,content_type,size_bytes,encode(checksum,'hex'),storage_key
		FROM media_upload_variants WHERE media_id=$1 ORDER BY variant`, mediaID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []expectedVariant
	for rows.Next() {
		var value expectedVariant
		if err = rows.Scan(&value.Name, &value.ContentType, &value.Size, &value.SHA256, &value.storageKey); err != nil {
			return nil, err
		}
		out = append(out, value)
	}
	return out, rows.Err()
}

func validateMediaCreate(in MediaUploadCreate, private bool) error {
	if in.Kind != "image" && in.Kind != "video" && in.Kind != "audio" && in.Kind != "file" {
		return ErrInvalid
	}
	if private {
		if len(in.Variants) != 3 {
			return ErrInvalid
		}
	} else if in.Kind != "image" || len(in.Variants) != 1 || in.Variants[0].Name != "full" {
		return ErrInvalid
	}
	seen := map[string]bool{}
	for _, variant := range in.Variants {
		if !validVariant(variant.Name) || seen[variant.Name] ||
			variant.Size < 1 || variant.Size > 100<<20 ||
			!validContentType(variant.ContentType) {
			return ErrInvalid
		}
		checksum, err := hex.DecodeString(variant.SHA256)
		if err != nil || len(checksum) != sha256.Size || hex.EncodeToString(checksum) != variant.SHA256 {
			return ErrInvalid
		}
		seen[variant.Name] = true
	}
	if private && (!seen["thumbnail"] || !seen["preview"] || !seen["full"]) {
		return ErrInvalid
	}
	return nil
}

func sameManifest(expected []expectedVariant, actual []MediaVariantInput) bool {
	if len(expected) != len(actual) {
		return false
	}
	byName := make(map[string]MediaVariantInput, len(actual))
	for _, value := range actual {
		byName[value.Name] = value
	}
	for _, value := range expected {
		got, ok := byName[value.Name]
		if !ok || got != value.MediaVariantInput {
			return false
		}
	}
	return true
}

func mediaStorageKey(contentMode, scopeID, mediaID, variant string) string {
	if contentMode == "public" {
		return "staging/" + scopeID + "/" + mediaID + "/source"
	}
	return "private/" + scopeID + "/" + mediaID + "/" + variant
}

func validVariant(value string) bool {
	return value == "thumbnail" || value == "preview" || value == "full"
}

func validContentType(value string) bool {
	if value == "" || len(value) > 100 || strings.ContainsAny(value, "\r\n") {
		return false
	}
	lower := strings.ToLower(value)
	return !strings.Contains(lower, "svg") && !strings.Contains(lower, "html") &&
		!strings.Contains(lower, "javascript") && !strings.Contains(lower, "executable")
}

func mustDecodeHex(value string) []byte {
	out, _ := hex.DecodeString(value)
	return out
}

func (s *Service) processPublicImage(
	ctx context.Context,
	tx pgx.Tx,
	mediaID string,
	source []byte,
) ([]MediaVariantInput, error) {
	if executableMagic(source) {
		return nil, errors.New("executable media blocked")
	}
	decoded, _, err := image.Decode(bytes.NewReader(source))
	if err != nil {
		return nil, errors.New("public media is not a supported image")
	}
	specs := []struct {
		name string
		max  int
	}{
		{"thumbnail", 320},
		{"preview", 1280},
		{"full", 2048},
	}
	out := make([]MediaVariantInput, 0, len(specs))
	for _, spec := range specs {
		resized := resizeWithin(decoded, spec.max)
		var encoded bytes.Buffer
		if err = jpeg.Encode(&encoded, resized, &jpeg.Options{Quality: 88}); err != nil {
			return nil, err
		}
		value := encoded.Bytes()
		digest := sha256.Sum256(value)
		key := "public/" + mediaID + "/" + spec.name + ".jpg"
		if err = s.MediaStore.Put(ctx, s.MediaPublicBucket, key, "image/jpeg", value); err != nil {
			return nil, err
		}
		bounds := resized.Bounds()
		if _, err = tx.Exec(ctx, `INSERT INTO media_variants
			(media_id,variant,storage_key,size_bytes,width,height,checksum)
			VALUES($1,$2,$3,$4,$5,$6,$7)`,
			mediaID, spec.name, key, len(value), bounds.Dx(), bounds.Dy(), digest[:]); err != nil {
			return nil, err
		}
		out = append(out, MediaVariantInput{
			Name: spec.name, ContentType: "image/jpeg", Size: int64(len(value)),
			SHA256: hex.EncodeToString(digest[:]),
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}

func resizeWithin(source image.Image, maxDimension int) image.Image {
	bounds := source.Bounds()
	width, height := bounds.Dx(), bounds.Dy()
	if width <= maxDimension && height <= maxDimension {
		return source
	}
	scale := float64(maxDimension) / float64(width)
	if height > width {
		scale = float64(maxDimension) / float64(height)
	}
	targetWidth := max(1, int(float64(width)*scale))
	targetHeight := max(1, int(float64(height)*scale))
	target := image.NewRGBA(image.Rect(0, 0, targetWidth, targetHeight))
	for y := range targetHeight {
		sourceY := bounds.Min.Y + y*height/targetHeight
		for x := range targetWidth {
			sourceX := bounds.Min.X + x*width/targetWidth
			target.Set(x, y, color.NRGBAModel.Convert(source.At(sourceX, sourceY)))
		}
	}
	return target
}

func executableMagic(value []byte) bool {
	return len(value) >= 2 && (bytes.Equal(value[:2], []byte("MZ")) ||
		bytes.Equal(value[:2], []byte("#!"))) ||
		len(value) >= 4 && (bytes.Equal(value[:4], []byte{0x7f, 'E', 'L', 'F'}) ||
			bytes.Equal(value[:4], []byte("PK\x03\x04")))
}

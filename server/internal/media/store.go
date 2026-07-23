package media

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/url"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type Store struct {
	internal *minio.Client
	public   *minio.Client
	ttl      time.Duration
}

func NewStore(
	internalEndpoint, publicEndpoint, accessKey, secretKey string,
	ttl time.Duration,
) (*Store, error) {
	internal, err := newClient(internalEndpoint, accessKey, secretKey)
	if err != nil {
		return nil, err
	}
	public, err := newClient(publicEndpoint, accessKey, secretKey)
	if err != nil {
		return nil, err
	}
	if ttl != 15*time.Minute {
		return nil, errors.New("media URL TTL must be exactly 15 minutes")
	}
	return &Store{internal: internal, public: public, ttl: ttl}, nil
}

func newClient(endpoint, accessKey, secretKey string) (*minio.Client, error) {
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Host == "" {
		return nil, errors.New("invalid MinIO endpoint")
	}
	return minio.New(parsed.Host, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: parsed.Scheme == "https",
		Region: "us-east-1",
	})
}

func (s *Store) PresignPut(
	ctx context.Context,
	bucket, key string,
) (string, time.Time, error) {
	value, err := s.public.PresignedPutObject(ctx, bucket, key, s.ttl)
	if err != nil {
		return "", time.Time{}, err
	}
	return value.String(), time.Now().UTC().Add(s.ttl), nil
}

func (s *Store) PresignGet(
	ctx context.Context,
	bucket, key string,
) (string, time.Time, error) {
	value, err := s.public.PresignedGetObject(ctx, bucket, key, s.ttl, nil)
	if err != nil {
		return "", time.Time{}, err
	}
	return value.String(), time.Now().UTC().Add(s.ttl), nil
}

func (s *Store) Read(
	ctx context.Context,
	bucket, key string,
	maxBytes int64,
) ([]byte, error) {
	object, err := s.internal.GetObject(ctx, bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, err
	}
	defer object.Close()
	value, err := io.ReadAll(io.LimitReader(object, maxBytes+1))
	if err != nil {
		return nil, err
	}
	if int64(len(value)) > maxBytes {
		return nil, errors.New("media object exceeds declared size")
	}
	return value, nil
}

func (s *Store) Put(
	ctx context.Context,
	bucket, key, contentType string,
	value []byte,
) error {
	_, err := s.internal.PutObject(
		ctx, bucket, key, bytes.NewReader(value), int64(len(value)),
		minio.PutObjectOptions{ContentType: contentType},
	)
	return err
}

func (s *Store) Remove(ctx context.Context, bucket, key string) error {
	return s.internal.RemoveObject(ctx, bucket, key, minio.RemoveObjectOptions{})
}

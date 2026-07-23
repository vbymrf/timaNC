package push

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
)

type Message struct {
	Type        string `json:"type"`
	ChatID      string `json:"chat_id"`
	Preview     string `json:"preview"`
	Encrypted   bool   `json:"encrypted"`
	CollapseKey string `json:"collapse_key"`
}

type Sender interface {
	Send(context.Context, string, string, Message) error
}

type HTTPSender struct {
	URL         string
	BearerToken string
	Client      *http.Client
}

func (s *HTTPSender) Send(
	ctx context.Context,
	provider string,
	deviceToken string,
	message Message,
) error {
	if s.URL == "" || s.BearerToken == "" {
		return errors.New("push gateway is not configured")
	}
	body, err := json.Marshal(map[string]any{
		"provider": provider,
		"token":    deviceToken,
		"payload":  message,
	})
	if err != nil {
		return err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, s.URL, bytes.NewReader(body))
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", "Bearer "+s.BearerToken)
	request.Header.Set("Content-Type", "application/json")
	client := s.Client
	if client == nil {
		client = http.DefaultClient
	}
	response, err := client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode > 299 {
		_, _ = io.Copy(io.Discard, response.Body)
		return fmt.Errorf("push gateway returned status %d", response.StatusCode)
	}
	return nil
}

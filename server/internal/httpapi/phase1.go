package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"

	"tima-server/internal/phase1"
)

type principalKey struct{}

func (h *Handler) registerPhase1(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/auth/register", h.startRegistration)
	mux.HandleFunc("POST /v1/auth/verify", h.verifyRegistration)
	mux.HandleFunc("POST /v1/auth/login", h.login)
	mux.HandleFunc("POST /v1/auth/refresh", h.refresh)
	mux.Handle("POST /v1/auth/logout", h.auth(http.HandlerFunc(h.logout)))
	mux.Handle("PUT /v1/keys/bundle", h.auth(http.HandlerFunc(h.putKeyBundle)))
	mux.Handle("GET /v1/keys/bundle/{user_id}", h.auth(http.HandlerFunc(h.getKeyBundles)))
	mux.Handle("GET /v1/escrow/config", h.auth(http.HandlerFunc(h.getEscrowConfig)))
	mux.Handle("GET /v1/chats", h.auth(http.HandlerFunc(h.listChats)))
	mux.Handle("POST /v1/chats", h.auth(http.HandlerFunc(h.createChat)))
	mux.Handle("POST /v1/chats/{id}/message-reservations", h.auth(http.HandlerFunc(h.reserveMessage)))
	mux.Handle("GET /v1/chats/{id}/messages", h.auth(http.HandlerFunc(h.listMessages)))
	mux.Handle("POST /v1/chats/{id}/messages", h.auth(http.HandlerFunc(h.sendMessage)))
	mux.Handle("POST /v1/chats/{id}/messages/{msg_id}/revisions", h.auth(http.HandlerFunc(h.reviseMessage)))
	mux.Handle("POST /v1/chats/{chat_id}/media/uploads", h.auth(http.HandlerFunc(h.createChatMediaUpload)))
	mux.Handle("POST /v1/posts/assets", h.auth(http.HandlerFunc(h.createPublicMediaUpload)))
	mux.Handle("POST /v1/media/uploads/{media_id}/complete", h.auth(http.HandlerFunc(h.completeMediaUpload)))
	mux.Handle("POST /v1/media/{media_id}/access", h.auth(http.HandlerFunc(h.accessMedia)))
}

func (h *Handler) requestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id, err := phase1.NewUUID()
		if err != nil {
			http.Error(w, "request identifier unavailable", http.StatusInternalServerError)
			return
		}
		w.Header().Set("X-Request-Id", id)
		next.ServeHTTP(w, r)
	})
}

func (h *Handler) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth := strings.TrimSpace(r.Header.Get("Authorization"))
		if !strings.HasPrefix(auth, "Bearer ") {
			h.problem(w, r, phase1.ErrUnauthorized)
			return
		}
		p, err := h.phase1.Authenticate(r.Context(), strings.TrimSpace(strings.TrimPrefix(auth, "Bearer ")),
			r.Header.Get("X-Device-Id"))
		if err != nil {
			h.problem(w, r, err)
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), principalKey{}, p)))
	})
}

func principal(r *http.Request) phase1.Principal {
	return r.Context().Value(principalKey{}).(phase1.Principal)
}

func (h *Handler) startRegistration(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Phone  string `json:"phone"`
		Locale string `json:"locale"`
	}
	if _, err := decodeStrict(r, &in); err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, err := h.phase1.StartRegistration(r.Context(), in.Phone, in.Locale)
	h.respond(w, r, http.StatusAccepted, out, err)
}

func (h *Handler) verifyRegistration(w http.ResponseWriter, r *http.Request) {
	var in struct {
		ChallengeID string             `json:"challenge_id"`
		OTP         string             `json:"otp"`
		Password    string             `json:"password"`
		DisplayName string             `json:"display_name,omitempty"`
		Device      phase1.DeviceInput `json:"device"`
	}
	if _, err := decodeStrict(r, &in); err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, err := h.phase1.VerifyRegistration(r.Context(), in.ChallengeID, in.OTP, in.Password, in.DisplayName, in.Device)
	h.respond(w, r, http.StatusCreated, out, err)
}

func (h *Handler) login(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Phone    string             `json:"phone"`
		Password string             `json:"password"`
		Device   phase1.DeviceInput `json:"device"`
	}
	if _, err := decodeStrict(r, &in); err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, err := h.phase1.Login(r.Context(), in.Phone, in.Password, in.Device)
	h.respond(w, r, http.StatusOK, out, err)
}

func (h *Handler) refresh(w http.ResponseWriter, r *http.Request) {
	var in struct {
		RefreshToken string `json:"refresh_token"`
		DeviceID     string `json:"device_id"`
	}
	if _, err := decodeStrict(r, &in); err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, err := h.phase1.Refresh(r.Context(), in.RefreshToken, in.DeviceID)
	h.respond(w, r, http.StatusOK, out, err)
}

func (h *Handler) logout(w http.ResponseWriter, r *http.Request) {
	if err := h.phase1.Logout(r.Context(), principal(r)); err != nil {
		h.problem(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) putKeyBundle(w http.ResponseWriter, r *http.Request) {
	var in phase1.KeyBundleWrite
	if _, err := decodeStrict(r, &in); err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, err := h.phase1.PutKeyBundle(r.Context(), principal(r), in)
	h.respond(w, r, http.StatusOK, out, err)
}

func (h *Handler) getKeyBundles(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("user_id")
	out, err := h.phase1.GetKeyBundles(r.Context(), principal(r), id)
	h.respond(w, r, http.StatusOK, map[string]any{"user_id": id, "bundles": out}, err)
}

func (h *Handler) getEscrowConfig(w http.ResponseWriter, r *http.Request) {
	shard, err := strconv.Atoi(r.URL.Query().Get("shard"))
	if err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, err := h.phase1.EscrowConfig(r.Context(), principal(r), r.URL.Query().Get("conversation_type"),
		r.URL.Query().Get("conversation_id"), r.URL.Query().Get("epoch"), shard)
	h.respond(w, r, http.StatusOK, out, err)
}

func (h *Handler) createChat(w http.ResponseWriter, r *http.Request) {
	var in struct {
		PeerUserID string `json:"peer_user_id"`
	}
	body, err := decodeStrict(r, &in)
	if err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, status, err := h.phase1.CreateChat(r.Context(), principal(r), in.PeerUserID,
		r.Header.Get("Idempotency-Key"), body)
	h.respond(w, r, status, out, err)
}

func (h *Handler) listChats(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	out, err := h.phase1.ListChats(r.Context(), principal(r), limit)
	h.respond(w, r, http.StatusOK, map[string]any{"items": out}, err)
}

func (h *Handler) reserveMessage(w http.ResponseWriter, r *http.Request) {
	out, status, err := h.phase1.ReserveMessageID(r.Context(), principal(r), r.PathValue("id"),
		r.Header.Get("Idempotency-Key"))
	h.respond(w, r, status, out, err)
}

func (h *Handler) sendMessage(w http.ResponseWriter, r *http.Request) {
	var in phase1.PrivateMessageWrite
	body, err := decodeStrict(r, &in)
	if err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, status, err := h.phase1.SendMessage(r.Context(), principal(r), r.PathValue("id"),
		r.Header.Get("Idempotency-Key"), body, in)
	h.respond(w, r, status, out, err)
}

func (h *Handler) listMessages(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	out, err := h.phase1.ListMessages(r.Context(), principal(r), r.PathValue("id"), limit)
	h.respond(w, r, http.StatusOK, map[string]any{"items": out}, err)
}

func (h *Handler) reviseMessage(w http.ResponseWriter, r *http.Request) {
	var in phase1.PrivateMessageWrite
	body, err := decodeStrict(r, &in)
	if err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, status, err := h.phase1.ReviseMessage(
		r.Context(), principal(r), r.PathValue("id"), r.PathValue("msg_id"),
		r.Header.Get("Idempotency-Key"), body, in,
	)
	h.respond(w, r, status, out, err)
}

func (h *Handler) createChatMediaUpload(w http.ResponseWriter, r *http.Request) {
	var in phase1.MediaUploadCreate
	body, err := decodeStrict(r, &in)
	if err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, status, err := h.phase1.CreateChatMediaUpload(
		r.Context(), principal(r), r.PathValue("chat_id"),
		r.Header.Get("Idempotency-Key"), body, in,
	)
	h.respond(w, r, status, out, err)
}

func (h *Handler) createPublicMediaUpload(w http.ResponseWriter, r *http.Request) {
	var in phase1.MediaUploadCreate
	body, err := decodeStrict(r, &in)
	if err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, status, err := h.phase1.CreatePublicMediaUpload(
		r.Context(), principal(r), r.Header.Get("Idempotency-Key"), body, in,
	)
	h.respond(w, r, status, out, err)
}

func (h *Handler) completeMediaUpload(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Variants []phase1.MediaVariantInput `json:"variants"`
	}
	body, err := decodeStrict(r, &in)
	if err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, status, err := h.phase1.CompleteMediaUpload(
		r.Context(), principal(r), r.PathValue("media_id"),
		r.Header.Get("Idempotency-Key"), body, in.Variants,
	)
	h.respond(w, r, status, out, err)
}

func (h *Handler) accessMedia(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Variant string `json:"variant"`
	}
	if _, err := decodeStrict(r, &in); err != nil {
		h.problem(w, r, phase1.ErrInvalid)
		return
	}
	out, err := h.phase1.AccessMedia(
		r.Context(), principal(r), r.PathValue("media_id"), in.Variant,
	)
	h.respond(w, r, http.StatusOK, out, err)
}

func decodeStrict(r *http.Request, out any) ([]byte, error) {
	body, err := io.ReadAll(io.LimitReader(r.Body, (2<<20)+1))
	if err != nil || len(body) == 0 || len(body) > 2<<20 {
		return nil, phase1.ErrInvalid
	}
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.DisallowUnknownFields()
	if err = dec.Decode(out); err != nil {
		return nil, err
	}
	var extra any
	if err = dec.Decode(&extra); err != io.EOF {
		return nil, phase1.ErrInvalid
	}
	return body, nil
}

func (h *Handler) respond(w http.ResponseWriter, r *http.Request, status int, value any, err error) {
	if err != nil {
		h.problem(w, r, err)
		return
	}
	writeJSON(w, status, value)
}

func (h *Handler) problem(w http.ResponseWriter, r *http.Request, err error) {
	log.Printf("request_id=%s method=%s path=%s error=%v",
		w.Header().Get("X-Request-Id"), r.Method, r.URL.Path, err)
	status, code, message := http.StatusInternalServerError, "INTERNAL_ERROR", "request could not be completed"
	switch {
	case errors.Is(err, phase1.ErrInvalid):
		status, code, message = 400, "INVALID_REQUEST", "request validation failed"
	case errors.Is(err, phase1.ErrUnauthorized):
		status, code, message = 401, "UNAUTHORIZED", "authentication failed"
	case errors.Is(err, phase1.ErrForbidden):
		status, code, message = 403, "FORBIDDEN", "operation is not permitted"
	case errors.Is(err, phase1.ErrNotFound):
		status, code, message = 404, "NOT_FOUND", "resource was not found"
	case errors.Is(err, phase1.ErrConflict):
		status, code, message = 409, "CONFLICT", "request conflicts with existing state"
	}
	w.Header().Set("Content-Type", "application/problem+json")
	writeJSON(w, status, map[string]any{"error": map[string]any{
		"code": code, "message": message, "request_id": w.Header().Get("X-Request-Id")}})
}

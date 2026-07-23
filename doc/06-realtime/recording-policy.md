# Recording Policy

> **Decision:** [ADR-0006](../adr/0006-livekit-media-policy.md)

## 1. Summary

| Content | Recording | Storage | E2E |
|---------|-----------|---------|-----|
| Voice message (async) | Client-side only | MinIO ciphertext | Yes |
| 1:1 / group call | **Off by default** | — | N/A |
| Voice chat room | Off by default | — | N/A |
| LiveKit Egress | **Disabled v1** | Would be plaintext | No |

Асинхронная voice message относится к **private media pipeline**: клиент шифрует bytes до upload, а чувствительные свойства передаются как `DocumentV2.encrypted_metadata`. Она не может быть автоматически перенаправлена в public processing. Presigned URL выдаётся только после auth и действует 15 минут.

## 2. Rationale

LiveKit **Track Egress** decodes WebRTC to plaintext on server — incompatible with strict private messaging policy without explicit multi-party consent and separate legal framework.

## 3. If recording enabled (future, public rooms only)

Requirements:

1. Banner «Ведётся запись» visible to all participants.
2. Explicit tap-to-consent per participant.
3. Separate retention policy (90d max default).
4. Audit log entry.
5. **Never** default on for private E2E chats.

## 4. Voice chat UI note

[doc_UI/07-voice-chat.md](../doc_UI/07-voice-chat.md): recording only if policy enabled — default **disabled**.

## 5. Compliance calls

If enterprise compliance requires call recording:

- Use dedicated «compliance room» type with plaintext recording.
- Not mixed with consumer private calls.

## 6. Client-side call recording (declined v1)

Alternative: record on client → Kodium encrypt → upload.

- High complexity, battery impact.
- Not in MVP scope.

## 7. Ссылки

- [livekit-integration.md](./livekit-integration.md)
- [content-security-matrix.md](../01-product/content-security-matrix.md)

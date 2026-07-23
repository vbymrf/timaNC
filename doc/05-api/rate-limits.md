# Rate Limits

## 1. Gateway limits (per IP)

| Endpoint class | Limit |
|----------------|-------|
| Auth OTP | 5/hour per phone |
| Login | 20/min per IP |
| General REST | 300/min |
| Domain media upload create | 30/min per user |
| WS connect | 10/min per IP |
| Recovery sessions | 10/day per IP |

## 2. Authenticated limits (per user)

| Action | Limit |
|--------|-------|
| Send message | 60/min per chat, 500/min global |
| Create chat | 20/hour |
| Upload media | 10 concurrent |
| Media per message | 10 |
| Document text | 4096 Unicode code points |
| Create message revision | 30/min per chat |
| Unified search (`GET /v1/search`) | 30/min |
| Report | 10/day |
| Recovery sessions | 3/day per account |
| Recovery proof | 5 attempts per session |

Recovery session TTL is 24h maximum. Limits apply at session creation before peer notification; expired/consumed sessions and a sixth proof attempt are rejected. Account and IP buckets are both enforced.

## 3. Key service

| Action | Limit |
|--------|-------|
| Fetch bundle | 100/min |
| Upload OTK | 10/min |

## 4. Response headers

```http
X-RateLimit-Limit: 300
X-RateLimit-Remaining: 42
X-RateLimit-Reset: 1717000000
Retry-After: 60
```

## 5. Abuse response

- 429 → exponential backoff client-side.
- Sustained abuse → temporary account flag + captcha (Phase 2).
- Attestation failure → 403, no retry without new token.

## 6. Bot API

Отдельные лимиты: [bot-rate-limits.md](./bot-rate-limits.md).

## 7. Escrow / admin

- Separate higher limits for internal services via mTLS service accounts.

## 8. Ссылки

- [api-guidelines.md](./api-guidelines.md)
- [bot-rate-limits.md](./bot-rate-limits.md)
- [threat-model.md](../03-security/threat-model.md)

# Realtime Events (WebSocket)

## 1. Connection

```text
WSS /v1/ws?token={access_token}
X-Device-Id: {device_uuid}
Sec-WebSocket-Protocol: tima.pb.v1
```

- Heartbeat: ping/pong every 30s.
- Reconnect with exponential backoff + cursor sync.

## 2. Frame format

Machine wire truth is binary Protobuf `ClientFrame` / `ServerFrame` from
`schema/proto/tima/v1/realtime/ws_frames.proto`. Text and JSON WebSocket frames
are rejected with close code `1003`. JSON below is a conceptual event map only.

```json
{
  "type": "event|ack|error",
  "event": "message.created",
  "payload": {},
  "seq": 12345
}
```

## 3. Client → Server

| Event | Payload |
|-------|---------|
| `subscribe` | `{"chat_ids": ["uuid"]}` |
| `subscribe.inbox` | `{}` — window 4: обращения и события агрегатора |
| `subscribe.communities` | `{"community_ids": ["uuid"]}` |
| `subscribe.channels` | `{"channel_ids": ["uuid"]}` — explicit; community subscription does not imply it |
| `unsubscribe` | `{"chat_ids": ["uuid"]}` |
| `unsubscribe.communities` | `{"community_ids": ["uuid"]}` |
| `unsubscribe.channels` | `{"channel_ids": ["uuid"]}` |
| `typing.start` | `{"chat_id": "uuid"}` |
| `typing.stop` | `{"chat_id": "uuid"}` |
| `presence.update` | `{"status": "online|away"}` |

## 4. Server → Client

| Event | Description |
|-------|-------------|
| `message.created` | New `DocumentV2` envelope metadata |
| `message.edited` | New immutable revision; invalidate render cache |
| `message.deleted` | Soft delete notification |
| `message.read` | Read cursor update |
| `chat.update` | Title, members |
| `gk.rotate` | New group key version |
| `call.incoming` | Incoming call invite |
| `call.ended` | Remote hangup |
| `community.member_joined` | New community subscriber |
| `community.member_left` | Unsubscribe |
| `community.child_added` | Child object attached |
| `community.object_acl_changed` | Visibility/permissions updated |
| `voice_room.participant_joined` | User joined audio room |
| `voice_room.participant_left` | User left audio room |
| `post.published` | Draft published to feed/channel |
| `post.edited` | New immutable post revision |
| `post.failed` | Publish error |
| `media.processing` | Video transcode started |
| `media.ready` | Asset ready for publish |
| `media.failed` | Asset processing failed |
| `comment.created` | New comment on post/media/collection |
| `comment.edited` | New immutable comment revision |
| `comment.deleted` | Comment removed |
| `emotion.added` | Emotion set on target (`emotion` 1..9) |
| `emotion.removed` | Emotion removed |
| `emotion.counters` | Aggregated counters updated under target |
| `rating.updated` | `rating_counters` changed for user/group/channel |
| `recommendation.added` | [+]/[−] on public content |
| `recommendation.removed` | Recommendation removed |
| `feed.new` | New posts badge counter (not full feed payload) |
| `shelf.item_added` | Item added to public shelf (friends fan-out) |
| `shelf.item_removed` | Item removed from public shelf |
| `shelf.access_requested` | Friend requested private shelf access |
| `shelf.access_granted` | Private shelf access granted (wrapped keys) |
| `shelf.access_revoked` | Private shelf access revoked (key rotation) |
| `virtual_user.created` | New VP for owner |
| `virtual_user.operator_changed` | Operator grant/revoke |
| `virtual_user.keys_rotated` | VP identity keys rotated |
| `virtual_user.transfer_updated` | Transfer state change |
| `inbox.thread` | Новое обращение / смена статуса (`thread_id`, `identity_id`, `status`, `assignee`) — командное |
| `inbox.event` | Личное событие по правилам маршрутизации (`event_type`, `identity_id`, `target_ref`); incl. `entity_message` |
| `device.revoked` | Force logout |
| `sync.required` | Server requests full resync |
| `recovery.requested` | Peer recovery initiated (`request_id`, `chat_id`, `requester_id`) |
| `recovery.responded` | Peer accepted/declined recovery |
| `recovery.completed` | History transfer finished |

### message.created / message.edited payload

```json
{
  "chat_id": "uuid",
  "message_id": 123456789,
  "sender_id": "uuid",
  "protocol_version": 2,
  "format_version": 2,
  "presence_bitmap": 1,
  "key_commitment": "base64-32-bytes",
  "revision_id": "uuid",
  "parent_revision_id": null,
  "revision_number": 1,
  "created_at": "ISO8601",
  "has_wrapped_key": true
}
```

Private events include `protocol_version`, `presence_bitmap` and `key_commitment`; public group events omit them. Client fetches full envelope via REST if not inline (large messages).

### inbox.thread payload

```json
{
  "thread_id": "uuid",
  "identity_type": "virtual_user|group|channel|community",
  "identity_id": "uuid",
  "status": "new|taken|snoozed|closed",
  "assignee_id": "uuid|null",
  "snoozed_until": "ISO8601|null",
  "updated_at": "ISO8601"
}
```

### inbox.event payload

```json
{
  "event_id": 123456789,
  "event_type": "appeal|entity_message|reply|mention|reaction|comment|role_assigned|moderation_request|thread_activity",
  "identity_id": "uuid",
  "target_ref": { "type": "entity_message", "id": "uuid" },
  "preview": "…",
  "created_at": "ISO8601"
}
```

For `event_type=entity_message`, `target_ref.type` is always `entity_message`; full content is fetched with `GET /v1/inbox/entity-messages/{id}`. Appeals continue to reference `thread` and are not entity messages.

## 5. Ordering

- Per-chat `seq` monotonic.
- Gap detection: if `seq` skip → `GET /v1/chats/{id}/messages?after_message_id=`.

## 6. Backpressure

- Server may send `sync.required` if client lag > 1000 events.
- Client should batch UI updates 100ms.

## 7. Ссылки

- [feed-ranking.md](../04-data/feed-ranking.md)
- [social-objects/00-index.md](../01-product/social-objects/00-index.md)
- [public-content-format.md](../01-product/public-content-format.md)
- [data-flows.md](../02-architecture/data-flows.md)
- [push-payloads.md](./push-payloads.md)
- [bot-api.md](./bot-api.md)

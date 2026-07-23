# Retention и Archival

## 1. Policy summary

| Data class | Retention | End-of-life action |
|------------|-----------|--------------------|
| Transmission metadata (routing/delivery timestamps, sender/recipient identifiers, call transmission records) | 3 years | Physical purge or irreversible anonymization where legally approved |
| Content and private/public message payloads, ciphertext | 6 months | Physical purge |
| Immutable message revisions and tombstones | 6 months with parent content | Atomic physical purge with parent |
| Media variants (`thumbnail`, `preview`, `full`) | 6 months with linked revision | Atomic physical purge; no retained `original` |
| `escrow_blob` | 6 months with protected content | Physical purge + eligible HSM epoch-key destruction |
| Participant wrapped keys (device/GK/shelf/recovery wraps) | 6 months with protected content | Physical purge; never extended as user recovery archive |
| Escrow, legal-hold and generic WORM audit records/proofs | 7 years minimum | Controlled expiry per compliance policy |

Active legal hold overrides every scheduled purge above. Storage tiering (hot/warm/cold) may change cost/performance, but **must not** extend these retention periods.

## 2. User-facing controls

| Feature | Scope |
|---------|-------|
| Auto-delete chat | Client + server flag `ttl` |
| Self-destruct messages | Per-chat timer |
| Clear history for all | `deleted=true` soft delete |
| Account delete | 30d grace → anonymize PII |

## 3. Auto-delete implementation

```text
Server job: DELETE visibility WHERE chat.ttl < now()
Escrow: blobs retained per compliance
Revisions/media: retained with parent record; no user-triggered CASCADE
Client: purge local SQLite on sync ack
```

TTL, self-destruct и user delete изменяют visibility, но не сокращают обязательный retention и не обходят legal hold. По достижении retention physical-purge worker удаляет весь object graph атомарно: payload/ciphertext, revisions/tombstones, three media variants, `escrow_blob` и participant wrapped keys. Transmission metadata живёт по отдельному 3-летнему schedule.

## 4. Export

Formats: PDF, HTML, TXT — decrypt client-side for E2E.

## 5. Backup

- Postgres: PITR 5 min RPO.
- MinIO: versioning + cross-AZ replication.
- HSM: vendor backup ceremony.
- Backup expiry обязан укладываться в тот же retention; purge включает active replicas, versions, snapshots и crypto-erasure backup copies. Restore не может воскресить expired/physically-purged данные.

## 6. Legal hold

Legal hold — отдельный generic объект, а не только boolean на user/chat:

```text
LegalHold {
  hold_id, authority_ref, reason
  selector { subject_ids?, account_ids?, conversation_ids?, message_ids?,
             revision_ids?, media_ids?, date_from?, date_to? }
  status: active | released
  created_at, released_at?, actor_id
}
```

- Active hold materialизует защищённый scope и приостанавливает physical purge полного связанного object graph, включая transmission metadata, revisions/tombstones, media, `escrow_blob`, participant wrapped keys и необходимые regional escrow epoch keys.
- Legal hold имеет приоритет над TTL, user/account erasure, auto-delete и обычным schedule. Нет operator/feature-flag bypass.
- Release возвращает объекты к исходному schedule; уже истёкшие объекты удаляются отдельной контролируемой purge operation.
- Hold/create/expand/release, purge и ошибки записываются в generic append-only WORM audit на 7 лет.

## 6.1. Audit integrity

Generic security/compliance audit является append-only:

- каждая запись содержит canonical payload, sequence, timestamp и `prev_hash`;
- batch/epoch checkpoint формирует Merkle root и подписывается audit signing key;
- inclusion/consistency proofs хранятся 7 лет вместе с escrow/legal-hold/WORM audit;
- verifier обязан обнаруживать изменение, удаление, вставку и перестановку записей.

## 7. Ссылки

- [escrow-legal-access.md](../03-security/escrow-legal-access.md)
- [privacy-compliance.md](../03-security/privacy-compliance.md)

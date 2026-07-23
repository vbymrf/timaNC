# ADR-0014: Participant E2E + обязательный escrow + recovery

**Статус:** принят · **Дата:** 2026-07-13

## Контекст

[ADR-0004](./0004-controlled-escrow.md) фиксирует обязательный controlled escrow для private контента. [ADR-0013](./0013-double-ratchet-phase.md) описывает поэтапное включение Double Ratchet. Исследовательская спецификация `е2е личная переписка .md` задаёт модель восстановления истории через peers и секретную фразу.

Требования продукта:

1. **Участники** общаются через E2E-механизм (ключи на устройствах, сервер — ретранслятор шифртекста).
2. **Escrow** обязателен для правоохранительных органов: полный доступ, включая сообщения с `deleted=true`, до physical purge по retention.
3. Escrow **не** является пользовательским recovery-каналом.
4. Восстановление истории: старое устройство → peer recovery с согласием → опциональный backup под секретную фразу.

## Решение

### 1. Четыре независимых слоя

| Слой | Назначение | Доступ |
|------|------------|--------|
| **Participant E2E** | X3DH + Double Ratchet (1:1), Sender Keys/GK (группы), `shelf_key`/content keys (личный контент) | Только участники с ключами на устройствах |
| **Wrapped keys** | Доставка на устройства, fallback при desync ratchet | Сервер хранит wraps; не расшифровывает без device key |
| **Escrow blob** | ML-KEM wrap каждого `message_key` / GK / `shelf_key` | Только HSM после M-of-N; **включая soft-deleted** |
| **Recovery** | Peer transfer, device transfer, optional phrase backup | Пользователь; **не** через escrow UI |

> **Ratchet = PFS. Wrapped keys = delivery fallback. Escrow = compliance. Recovery = peer/device/phrase.**

### 2. Participant E2E (основной контур)

**1:1 чат:** X3DH handshake → `DoubleRatchetSession` (Kodium). Каждое сообщение подписано Ed25519 автором. `ratchet_envelope` — основной путь; `wrapped_key` — fallback при desync/offline.

**Приватная группа:** Sender Keys / GK. Ротация: join/leave/kick + каждые 100 сообщений. Peer recovery: участники с историей отдают её новому устройству, re-encrypt под ключ запрашивающего; подписи проверяются.

**Личный контент** (`private_shelves`, заметки «для себя», self-only медиа): случайный `content_key` / `shelf_key`; wraps только на устройства владельца. Второго участника нет — recovery **только** через секретную фразу (backup) или старое устройство.

### 3. Escrow (обязательный, корректируемый)

- Каждый private `message_key`, GK period и `shelf_key` version **MUST** иметь `escrow_blob` и explicit `key_commitment`; commitment обязателен также на ratchet, participant-wrap, recovery и backup paths.
- Send fail-closed если escrow недоступен ([escrow-legal-access.md](../03-security/escrow-legal-access.md) §8).
- Юридический доступ: M-of-N → HSM decapsulate → расшифровка **всех** сообщений в scope, **включая user-deleted** (`deleted=true`).
- Physical purge по [retention-archival.md](../04-data/retention-archival.md) — единственное основание уничтожения для escrow archive.
- Реализация escrow **может корректироваться** (алгоритм, HSM vendor, M-of-N policy) без изменения participant path.

### 4. Recovery (пользовательский, не escrow)

Приоритет источников истории:

1. **Старое устройство** — полное доверенное восстановление (export ratchet state + локальная история).
2. **Peer recovery** — собеседник (1:1) или участники (группа) с согласия отдают историю, зашифровав под ключ нового устройства; подписи верифицируются.
3. **Опциональный backup под секретную фразу** — страховка; opt-in.

**Секретная фраза** — удостоверение личности и ключ backup, **не** master key живой переписки. Одна фраза на аккаунт → per-chat subkeys через HKDF.

Anti-theft: согласие peer, push «это вы?», серверный мониторинг паттернов. Defaults: recovery init `3/day/account`, `10/day/IP`; proof attempts `5/session`; session TTL `24h`. Параметры configurable; изменения, срабатывания и overrides аудитируются.

**Escrow recovery для пользователя запрещён** — только юридическая процедура.

### 5. Терминология

| Термин | Значение |
|--------|----------|
| Participant E2E | Шифрование между участниками; сервер не видит plaintext |
| Controlled escrow | Параллельный compliance-слой; продукт **не** strict E2E в юридическом смысле |
| UI «Защищённый чат» | Participant E2E + disclosure об escrow в политике |

### 6. Gated rollout

| Gate | Scope |
|------|-------|
| Envelope baseline | Envelope + signatures + mandatory escrow; wrapped keys fallback; device linking |
| Private-group gate | GK/Sender Keys для private groups |
| Recovery gate | Peer recovery protocol (группа → 1:1); identity phrase + proof; normative limits/TTL |
| Backup gate | Optional phrase backup |
| External ratchet tester gate | Signed PreKey verification уже обязательна и протестирована |
| GA gate | Double Ratchet required-by-default для 1:1 + ADR-0005 audit/vectors ([ADR-0013](./0013-double-ratchet-phase.md)) |

## Отклонено

- Escrow как единственный recovery для пользователя.
- Отказ от escrow ради маркетингового «strict E2E».
- Peer recovery без верификации подписей.
- Секретная фраза как замена device-bound messaging keys.

## Последствия

- Новые таблицы/API: `recovery_requests`, peer transfer wire format — [data-model.md](../04-data/data-model.md), [rest-api.md](../05-api/rest-api.md).
- `key-lifecycle.md` §12 переписан под три источника recovery.
- Canvas и UI обновлены: disclosure escrow, recovery flows.
- ADR-0013 остаётся в силе для **rollout** ratchet; ADR-0014 — **каноническая** модель participant + escrow + recovery.

## References

- [ADR-0004](./0004-controlled-escrow.md)
- [ADR-0013](./0013-double-ratchet-phase.md)
- [crypto-protocol.md](../03-security/crypto-protocol.md)
- [key-lifecycle.md](../03-security/key-lifecycle.md)
- [escrow-legal-access.md](../03-security/escrow-legal-access.md)
- Исследовательский источник `е2е личная переписка .md`.

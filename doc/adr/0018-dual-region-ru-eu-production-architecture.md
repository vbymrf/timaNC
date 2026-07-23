# ADR-0018: Dual-Region RU/EU Production Architecture

## Status

Accepted · 2026-07-22 · **Amends** [ADR-0008](./0008-multi-region-strategy.md)

## Context

Beta запускается на одном VPS в одном регионе. Эта временная топология пригодна для проверки продукта, но не задаёт production-модель: требования локализации данных RU и EU, юрисдикция controlled escrow и трансграничная доставка должны быть определены до GA, а не отложены до достижения 10M MAU.

[ADR-0008](./0008-multi-region-strategy.md) связывал второй регион преимущественно с Phase 6 / масштабированием. Это недостаточно: юридическая и криптографическая изоляция регионов является gate релиза, тогда как active-active внутри региона и дальнейшее географическое масштабирование остаются задачами Phase 6.

## Decision

### 1. Этапы и production boundary

- **Beta:** один регион, один VPS; только beta-данные и пользователи, для которых выбранный регион допустим. Beta не считается GA-ready и не обещает cross-region delivery или автоматический failover.
- **До GA:** развернуть две изолированные regional cells — **RU** и **EU**. Каждая cell имеет собственные edge/API, identity projection, message/media storage, очереди, observability, backups, KMS/HSM и операционный контур.
- Cell не является синхронной репликой другой cell. Репликация plaintext, escrow-ключей или региональных баз между RU и EU запрещена.
- Active-active масштабирование внутри/между дополнительными cells, автоматический межрегиональный failover и multi-master остаются **Phase 6**. Наличие двух RU/EU residency cells и безопасного ciphertext relay — обязательная готовность **до GA**, а не Phase 6.

### 2. Home region

- `account_home_region ∈ {RU, EU}` назначается при регистрации из юридически утверждённых сигналов и сохраняется как обязательный атрибут аккаунта. Неопределённое или конфликтующее назначение блокирует регистрацию/маршрутизацию до разрешения.
- `conversation_home_region ∈ {RU, EU}` назначается при создании разговора по юридически утверждённой policy, учитывающей home regions участников и тип разговора. Сервер не выбирает регион эвристически при каждом сообщении.
- Home region неизменяем в обычном API. Миграция аккаунта или разговора — отдельная контролируемая процедура с legal approval, аудитом, re-encryption/rewrap при необходимости и доказанным удалением исходных копий; до проектирования и одобрения такой процедуры миграция запрещена.
- **Conversation home region является authority** для приватных сообщений разговора: там выполняются ordering, durable storage, retention/legal hold и controlled escrow.

### 3. Residency и минимальный global plane

Архитектурные границы данных:

| Класс данных | Место хранения / обработки |
|---|---|
| Account system of record: профиль, credentials/verifiers, устройства, настройки, compliance records | Только `account_home_region`; в другой cell допустима минимальная удаляемая projection, необходимая для конкретного cross-region разговора и одобренная legal |
| Private conversation ciphertext, encrypted attachments, conversation state, membership/role state, delivery/read state, abuse/compliance metadata, indexes, backups и durable logs | Только `conversation_home_region` |
| Plaintext private content и `message_key` | Только на клиентских устройствах; при санкционированном escrow-доступе — внутри HSM/enclave `conversation_home_region` в пределах ордера |
| Escrow key material, custodian shares, decapsulation audit | Только `conversation_home_region`; согласно региональной иерархии ниже |
| Global directory/routing plane | Только `account_id → account_home_region`, `conversation_id → conversation_home_region`, непрозрачные routing handles, версии policy/config и технические health-сигналы без пользовательского содержимого |

Global plane не хранит профиль, контакты/social graph, membership list, message/media ciphertext, ключи, escrow blobs, текстовые push-preview, read state или бизнес-логи разговора. Telemetry и audit events остаются в исходной cell; глобально разрешены только обезличенные агрегаты после privacy/legal review.

Региональные backups, replicas, search indexes, caches, dead-letter queues и observability подчиняются той же residency, что и исходные данные. CDN/object replication за пределы home region выключены по умолчанию.

### 4. Cross-region private messaging

Cross-region доставка использует отдельный **ciphertext-only relay**:

1. Source cell аутентифицирует отправителя и получает из global plane authoritative `conversation_home_region`.
2. Если source cell не является home cell разговора, она пересылает opaque end-to-end encrypted envelope в home cell по mTLS-соединению.
3. Home cell валидирует conversation policy/membership по своей authoritative state, присваивает порядок, сохраняет ciphertext и маршрутизирует opaque delivery envelopes в home cells получателей.
4. Recipient cell доставляет envelope устройству, не создавая durable-копию истории разговора; допустим только ограниченный TTL ciphertext spool по утверждённой policy доставки.

Relay и промежуточная cell могут видеть только минимальный транспортный заголовок: protocol/version, source/destination cell, opaque conversation routing handle, message id, размер/bucket, timestamps/TTL и anti-replay data. Заголовок аутентифицирован и не содержит plaintext, профиля, membership list, escrow material или cryptographic keys. Relay:

- не завершает content encryption и не расшифровывает payload;
- не получает `message_key`, ratchet/group keys, escrow private/public-key selection material или custodian shares;
- не индексирует содержимое и не сохраняет payload дольше bounded retry TTL;
- не является альтернативным storage/backup path.

Все чувствительные message metadata, включая reply/reaction targets, entities, attachment metadata и rich push text, находятся внутри подписанного encrypted envelope. Неизбежные transport metadata документируются в DPIA/data-flow inventory, минимизируются и имеют отдельный короткий retention.

### 5. Региональный escrow и retention

[ADR-0016](./0016-escrow-key-hierarchy-and-threshold.md) применяется независимо в каждой cell:

```text
Escrow_Public[region, epoch, shard]
Escrow_Private / threshold shares[region, epoch, shard]
```

- RU и EU используют отдельные HSM/enclave clusters, кастодианов, ceremonies, audit/transparency logs и key publication roots.
- Private/threshold shares, ML-KEM private material и восстановленные `message_key` **никогда не пересекают региональную границу**.
- Клиент выбирает escrow public key по `conversation_home_region × epoch × shard`; region входит в подписанный заголовок, key identifier и AEAD AAD.
- `conversation_home_region` определяет применимые escrow policy, retention, legal hold, уничтожение ключа эпохи и место санкционированной декапсуляции.
- Relay не выбирает и не подменяет escrow key. Несовпадение conversation region, key region или route приводит к отказу.

### 6. Fail-closed

Создание разговора, отправка, cross-region forwarding и escrow wrapping запрещены, если:

- home region отсутствует, конфликтует или policy version неизвестна;
- authoritative route не подтверждён либо global directory недоступен/устарел;
- destination cell или ciphertext relay не подтверждает ожидаемый region binding;
- escrow key не принадлежит `conversation_home_region`, просрочен или не проверен;
- residency policy не разрешает требуемый data flow.

Система не делает fallback в другой регион, глобальную очередь, общий HSM или plaintext transport. Клиент получает retryable/non-retryable ошибку без утечки чувствительных деталей; событие фиксируется в региональном security audit.

## Security and Compliance Gates

Cross-region функциональность не выпускается, пока одновременно не выполнены:

1. Письменный **legal sign-off** для RU и EU: основания трансграничной передачи, data-controller/processor roles, localization, retention, lawful access, sanctions и договорные механизмы.
2. Утверждённые DPIA, data-flow diagram и machine-readable residency matrix для всех storage/queue/cache/log/backup/CDN путей.
3. Threat model и независимый security review relay, region binding, replay/downgrade protection и запрета key/plaintext transit.
4. HSM ceremonies и тесты, доказывающие раздельные `region × epoch × shard` roots, отсутствие cross-region export и корректное уничтожение по retention.
5. Автоматические negative tests: route ambiguity, stale directory, wrong-region escrow key, relay compromise, destination outage и попытка запрещённой репликации завершаются fail-closed.
6. Проверенные regional incident response, audit access, backup/restore и deletion/legal-hold процедуры; restore не может сменить residency.
7. Наблюдаемость подтверждает SLO доставки без включения content, keys или запрещённой metadata в глобальные логи.

До прохождения gates cross-region feature flag выключен; наличие технически работающего relay не является разрешением на production release.

## Consequences

**Positive:**

- Residency, routing, escrow jurisdiction и retention имеют одну authoritative границу — conversation home region.
- Компрометация relay не раскрывает plaintext или ключи; региональный escrow ограничивает blast radius также по юрисдикции.
- RU/EU compliance readiness достигается до GA без преждевременного multi-master/active-active.
- Fail-closed исключает тихий fallback в юридически неверный регион.

**Negative:**

- Две независимые cells, HSM ceremonies и operational teams увеличивают стоимость до GA.
- Cross-region сообщения получают дополнительную latency и зависят от relay/global routing availability.
- Ограниченный global directory всё равно является чувствительной инфраструктурой и требует высокой доступности, integrity и privacy review.
- Conversation region усложняет onboarding смешанных RU/EU участников, account migration, support и disaster recovery.
- Региональная изоляция исключает простой cross-region restore; DR capacity должна существовать в допустимой юрисдикции.

## Alternatives Considered

1. **Один global region до Phase 6.** Отклонено: откладывает residency/legal architecture после GA и смешивает escrow-юрисдикции.
2. **Полная cross-region репликация или global database.** Отклонено: переносит metadata/content и backups через границы, расширяет compromise scope и создаёт конфликтующие authorities.
3. **Relay с расшифровкой или общим escrow HSM.** Отклонено: relay становится plaintext/key trust boundary, а ключи пересекают юрисдикции.
4. **Только account home region без conversation home region.** Отклонено: у одного разговора появляются несколько authorities для ordering, retention и escrow.
5. **Полностью запретить RU/EU разговоры.** Допустимый временный pre-gate режим, но не целевая product architecture.
6. **Сразу multi-region active-active.** Отклонено до Phase 6 как несоразмерная сложность; для GA достаточно двух изолированных cells с single-home conversation и ciphertext relay.

## References

- [ADR-0008](./0008-multi-region-strategy.md) — DR и позднее active-active масштабирование
- [ADR-0016](./0016-escrow-key-hierarchy-and-threshold.md) — epoch×shard escrow hierarchy и HSM scope invariant
- [ADR-0017](./0017-kodium-crypto-hardening.md) — key commitment и canonical envelope binding
- [roadmap.md](../09-delivery/roadmap.md) — Phase 6 multi-region milestone, уточнённый этим ADR
- [privacy-compliance.md](../03-security/privacy-compliance.md)
- [disaster-recovery.md](../07-operations/disaster-recovery.md)

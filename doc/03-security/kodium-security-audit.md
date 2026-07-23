# Аудит Kodium: backdoor-проверка и опасные сценарии использования

> **Дата:** 2026-07-22 · **Библиотека:** `eu.livotov.labs:kodium:1.0.0` (`kodium-main git/`)
> **Область:** (1) проверка на встроенную возможность «расшифровать всё»; (2) анализ 4 крипто-ограничений против фактического использования в `doc/`.
> **Связанные документы:** [crypto-protocol.md](./crypto-protocol.md) · [escrow-legal-access.md](./escrow-legal-access.md) · [threat-model.md](./threat-model.md)

---

## 0. TL;DR

- **В самой библиотеке Kodium скрытой возможности «расшифровать всё» (backdoor / мастер-ключ / key-escrow / сетевая утечка ключей) НЕТ.** Исходники проверены: нет hardcoded-ключей, нет сетевого кода, нет платформенных `expect/actual` подмен генератора случайных чисел, ключи не покидают процесс.
- **Возможность «расшифровать всё» существует НЕ в библиотеке, а в архитектуре мессенджера (`doc/`) — и она там сделана осознанно, по дизайну.** Обязательный `escrow_blob` (ML-KEM к `Escrow_Public`) на **каждом** приватном сообщении + серверные `wrapped_keys` + доступ к soft-deleted = штатный «controlled escrow». Это не скрытый backdoor, но по факту это встроенный технический канал массовой расшифровки. Документация это честно фиксирует: продукт **юридически не является strict E2E**.
- **Все 4 названных ограничения TweetNaCl подтверждены в коде** и релевантны дизайну в разной степени: критично — **отсутствие key-commitment** (из-за мульти-путевой доставки и escrow); умеренно — **malleability подписи**; низко/контролируемо — **length-extension SHA-512** и **side-channel в JS**.

---

## 1. Задача 1 — есть ли в Kodium встроенная возможность «расшифровать всё»?

### 1.1. Что проверялось

| Проверка | Результат |
|----------|-----------|
| Hardcoded-ключи / мастер-ключ / «debug key» | ❌ Не найдено (единственный `Hardcoded...` — это KAT-тест `NaClLowLevel.test.kt`) |
| Key escrow / резервное депонирование внутри библиотеки | ❌ Нет (подтверждается и `messenger-crypto-architecture.md`: «Escrow / wrapped keys — ❌ Нет» в библиотеке) |
| Сетевые вызовы (HTTP/сокеты/телеметрия) | ❌ Нет; единственные URL — Apache-лицензия в комментариях |
| Подмена генератора случайных чисел под конкретную платформу (`expect/actual`) | ❌ Нет ни одного `expect/actual`; всюду один источник |
| Слабый/предсказуемый ГПСЧ | ❌ Нет: `NaClLowLevel.randombytes` использует `org.kotlincrypto.random.CryptoRand.Default` (платформенный CSPRNG) |
| «Тихая» вторая копия ключа / утечка секретов из API | ❌ Нет; `exportToArray()`/`exportToEncryptedString()` работают только по явному вызову приложения |

### 1.2. Вывод

Kodium — это чистая крипто-библиотека (порт TweetNaCl + Double Ratchet + ML-KEM). Механизма «расшифровать всё» в ней **нет**. Единственный путь получить сообщение — иметь соответствующий секретный ключ (identity / message / ratchet).

> ⚠️ **Важный нюанс.** Отсутствие key-commitment (см. §3.1) означает, что **один и тот же шифртекст может валидно расшифроваться под двумя разными ключами**. Это не backdoor, но это свойство, которое злоумышленник может использовать при построении протокола (см. риск R-1).

---

## 2. Задача 2 — где архитектура `doc/` создаёт возможность массовой расшифровки

Здесь «возможность расшифровать всё» **есть — и это заявленная функция**, не дефект библиотеки:

| Механизм | Где | Что даёт |
|----------|-----|----------|
| **Обязательный `escrow_blob`** на каждом приватном сообщении, GK-периоде и `shelf_key` | `crypto-protocol.md` §3–7, `escrow-legal-access.md` §1 | HSM с `Escrow_Private` после M-of-N может расшифровать **любой** приватный контент |
| **Доступ к `deleted=true`** до physical purge | `escrow-legal-access.md` §5, `crypto-protocol.md` §10 | «Удалённые» пользователем сообщения остаются доступны следствию |
| **Серверные `wrapped_keys`** (fallback-доставка) | `crypto-protocol.md` §3.2 | Ключ сообщения обёрнут на устройство получателя; зашифровано под identity-ключ устройства (сервер сам расшифровать не может) |
| **Fail-closed на escrow** | `escrow-legal-access.md` §8 | Если escrow недоступен — отправка **запрещается**, т.е. сообщений без канала депонирования не бывает |

Оценка: архитектура **честная** — есть явные оговорки («продукт не strict E2E», транспарентность, M-of-N, WORM-audit, `escrow perception as backdoor` в остаточных рисках `threat-model.md` §5). Это не скрытая закладка. Но её нужно правильно называть в UI/маркетинге (документ это уже требует).

> **Решение по R-0 (принято, [ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md)):** единый `Escrow_Private` заменён независимыми RU/EU иерархиями `Escrow_Public[region,epoch,shard]` (радиус = regional cell×квартал×шард); public keys приходят в подписанном `/v1/escrow/config` bundle с pinned regional root, current+next keys и validity; **threshold-decapsulation** не собирает полный приватный ключ; HSM отдаёт только in-scope message keys и никогда private key; per-epoch уничтожение закрывает «harvest-now-decrypt-later»; generic hash-chain/WORM + Merkle transparency делает доступ проверяемым.

---

## 3. Задача 2 — анализ 4 крипто-ограничений против фактического использования

Все примитивы взяты из `NaCl.kt` / `NaClLowLevel.kt`: `SecretBox` = XSalsa20-Poly1305, `Box` = Curve25519+XSalsa20-Poly1305, `Sign` = Ed25519, `crypto_hash` = SHA-512.

### 3.1. Отсутствие key-commitment (No secret key commitment) — 🔴 КРИТИЧНО для этого дизайна

**Подтверждено:** Poly1305 в `SecretBox`/`Box` не является key-committing. Один шифртекст можно расшифровать в валидный (возможно, другой) plaintext под двумя разными ключами.

**Почему это опаснее обычного здесь.** В `crypto-protocol.md` один и тот же `message_key` доставляется по **нескольким независимым путям**: (a) ratchet-envelope, (b) per-device `wrapped_key`, (c) `escrow_blob`, а доставка **молча падает на fallback** (§3.5 «Desync → silent fallback to wrapped keys»). Ни один из путей не подтверждает, что распакованный ключ — тот же самый.

Возможные последствия (риск **R-1**):
- **Расхождение «получатель vs escrow»:** злонамеренный отправитель формирует сообщение так, что `wrapped_key`-путь и `escrow_blob`-путь распаковываются в **разные** валидные plaintext → следствие/аудит видит одно, получатель другое. Подрывается сама цель compliance-слоя.
- **Расхождение между устройствами/путями:** ratchet-путь и wrapped-путь дают разный контент («invisible salamander»-класс атак, известный по multi-recipient AEAD).
- Подпись Ed25519 покрывает `encrypted_nodes`+AAD, но **не** доказывает, что все ключевые пути ведут к одному ключу.

**Рекомендации:**
1. Ввести **key-commitment**: включать `commit = HKDF(message_key, info="commit")` (или `H(message_key)`) в подписываемые заголовки и проверять его на каждом пути расшифровки (ratchet / wrapped / escrow).
2. Или перейти на committing-AEAD-конструкцию для конвертного слоя.
3. Escrow-путь и wrapped-путь **обязаны** приводить к байтово одинаковому `message_key`; добавить это как инвариант в HSM-пакет расследования и в клиентскую валидацию.

> **Решение (принято, [ADR-0017](../adr/0017-kodium-crypto-hardening.md)):** введён `key_commitment = HKDF-SHA256(message_key, info="tima/commit/v1")` в подписываемые заголовки, AEAD AAD, `WrappedKeyRecord` и `escrow_blob`. Все пути расшифровки (ratchet/wrapped/escrow/HSM) пересчитывают и сверяют commitment; `commitment_mismatch` → reject. Инвариант закреплён в [crypto-protocol.md](./crypto-protocol.md) §3.1/3.3/3.4, [escrow-legal-access.md](./escrow-legal-access.md) §3c. Тесты: `security-test-plan.md` V1-023/024.

### 3.2. Malleability подписи Ed25519 (Signature malleability) — 🟡 УМЕРЕННО

**Подтверждено в коде:** `crypto_sign_open` (`NaClLowLevel.kt:623`) выполняет только стандартную проверку `R,S` и не отвергает не-канонический `S` (нет проверки `S < L`) и малые порядки. Это классическое свойство TweetNaCl → для подписанного сообщения без знания ключа можно получить **другую** валидную подпись.

**Где используется подпись (`crypto-protocol.md`):** аутентичность/anti-repudiation, **проверка каждого сообщения при peer-recovery**, immutable revisions и signed tombstones.

Последствия (риск **R-2**):
- Если байты подписи где-либо используются как **идентификатор/ключ дедупликации/идемпотентности** — malleability позволяет создать «другое» валидное сообщение с тем же содержимым (обход «уже видели», дублирование в recovery-переносе).
- Идентичность сообщения в дизайне держится на `revision_id`/`message_id`, а не на подписи — при соблюдении этого влияние ограничено.

**Рекомендации:**
1. Никогда не использовать байты подписи как уникальный идентификатор/ключ дедупа.
2. Идемпотентность строить на `(chat_id, revision_id)` и `message_id`, а не на `signature`.
3. Опционально — на прикладном уровне отвергать не-канонический `S` (проверка low-S) перед приёмом.

> **Решение (принято, [ADR-0017](../adr/0017-kodium-crypto-hardening.md)):** идентичность сообщения — только `(chat_id, message_id, revision_id)`, байты подписи не используются как id/dedup; приёмная сторона отвергает не-канонический `S` (low-S) в прикладной обёртке над `verifyDetached`. См. [crypto-protocol.md](./crypto-protocol.md) §3.4/3.6. Тест: `security-test-plan.md` V1-025.

### 3.3. Length-extension атаки на SHA-512 (`nacl.hash`) — 🟢 НИЗКО/КОНТРОЛИРУЕМО

**Подтверждено:** `crypto_hash` = SHA-512 (`NaClLowLevel.kt:485`) — уязвим к length-extension, как любой «голый» Merkle–Damgård хеш.

**Фактическое использование:**
- SHA-512 в Kodium используется **только внутри Ed25519** — это безопасное применение, не MAC.
- Публичного `nacl.hash` наружу объект `nacl` **не экспонирует** (есть только `SecretBox`, `Box`, `Sign`, `randomBytes`).
- Аутентификация ключей в дизайне — **HMAC-SHA256** (PBKDF2) и **HKDF-SHA256**, которые к length-extension не уязвимы.
- CAS-дедуп использует `SHA-256(plaintext)` **без секретного префикса** → length-extension неэксплуатируем (у CAS-хеша иные риски — утечка факта «файл уже был», см. `threat-model.md`).

Последствия (риск **R-3**): проявятся, только если кто-то на прикладном уровне построит самодельный MAC вида `H(secret ‖ msg)` на голом SHA-256/512.

**Рекомендации:**
1. Запретить конструкции `H(secret ‖ message)`; для аутентификации использовать только HMAC/HKDF/Poly1305 (как сейчас).
2. Зафиксировать это правило в code-review и в `security-test-plan.md`.

> **Решение (принято, [ADR-0017](../adr/0017-kodium-crypto-hardening.md)):** MAC/hash policy — только HMAC-SHA256/HKDF-SHA256/Poly1305; самодельные `H(secret‖msg)` запрещены; CAS `SHA-256(plaintext)` помечен как metadata-leak trade-off, не MAC. См. [crypto-protocol.md](./crypto-protocol.md) §3.6. Тест: `security-test-plan.md` V1-027.

### 3.4. Атаки по побочным каналам (Side-channel) — 🟡 УМЕРЕННО (зависит от таргета)

**Подтверждено:** TweetNaCl спроектирован constant-time, но чистый Kotlin/JS (JIT, GC, отсутствие гарантий constant-time на арифметике) не даёт 100%-й гарантии. ML-KEM, PBKDF2, HKDF — тоже pure-Kotlin.

**Фактическое использование:** Kodium поддерживает JS/Wasm, но клиенты продукта — Android/iOS/Windows (`threat-model.md` §1: Web client — out of scope), что снижает риск для JS-таргета.

Последствия (риск **R-4**): на JS/Wasm возможна утечка секретов по времени/кэшу; наиболее чувствительны операции с долговременными identity-ключами и `deriveKeyFromPassword`.

**Рекомендации:**
1. Крипто-операции с секретными ключами выполнять на native/JVM-таргетах; не запускать их в браузерном JS.
2. Долговременные ключи держать в Keystore/Secure Enclave (уже в `threat-model.md`), минимизировать время жизни ключей в JS-heap.
3. Учесть в независимом аудите Kodium (гейт `ADR-0005`) проверку constant-time на целевых платформах.

> **Решение (принято, [ADR-0017](../adr/0017-kodium-crypto-hardening.md)):** секретные операции — только native/JVM; браузерный JS для них запрещён (Web client out of scope); ключи в Keystore/Enclave, зануление `ByteArray` ([client-hardening.md](./client-hardening.md) §4); constant-time — обязательный пункт аудита [ADR-0005](../adr/0005-kodium-readiness-gate.md).

---

## 4. Сводная таблица рисков

| ID | Ограничение | Severity | Релевантно из-за | Ключевая митигация | Статус |
|----|-------------|----------|------------------|--------------------|--------|
| R-1 | Нет key-commitment | 🔴 Высокая | Мульти-путевая доставка (ratchet/wrapped/escrow) + silent fallback | Commitment `HKDF(message_key)` в подпись/AAD; инвариант «все пути → один ключ» | ✅ Решено [ADR-0017](../adr/0017-kodium-crypto-hardening.md) |
| R-2 | Malleability подписи | 🟡 Средняя | Подпись в peer-recovery/revisions | Не использовать подпись как id/dedup; low-S проверка | ✅ Решено [ADR-0017](../adr/0017-kodium-crypto-hardening.md) |
| R-3 | Length-extension SHA-512 | 🟢 Низкая | Только при самодельном MAC | Только HMAC/HKDF/Poly1305; запрет `H(secret‖msg)` | ✅ Решено [ADR-0017](../adr/0017-kodium-crypto-hardening.md) |
| R-4 | Side-channel в JS | 🟡 Средняя | Kodium поддерживает JS/Wasm | Секретные операции — native/JVM; Keystore | ✅ Решено [ADR-0017](../adr/0017-kodium-crypto-hardening.md) |
| R-0 | Escrow = технический канал legal access | ⚠️ By design | Обязательный `escrow_blob` + soft-delete доступ | RU/EU region×epoch×shard + signed config + threshold-decap + scope + destruction + transparency | ✅ Усилено [ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md) |

---

## 5. Итог

1. **Backdoor в библиотеке Kodium отсутствует.** Возможность массовой расшифровки существует на уровне архитектуры мессенджера через штатный controlled-escrow и осознанно задокументирована (продукт — не strict E2E).
2. **Из 4 ограничений главный практический риск — отсутствие key-commitment** — закрыт [ADR-0017](../adr/0017-kodium-crypto-hardening.md): `key_commitment` в подписи/AAD/wrapped/escrow, проверка на всех путях.
3. Malleability, length-extension и side-channel закрыты политиками [ADR-0017](../adr/0017-kodium-crypto-hardening.md) и включены в `security-test-plan.md` и в чек-лист аудита ([ADR-0005](../adr/0005-kodium-readiness-gate.md)).
4. **R-0 усилен** [ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md): независимые RU/EU region×epoch×shard hierarchies, signed config с pinned roots, threshold-decapsulation, scope-инвариант HSM, per-epoch уничтожение и verifiable transparency log — единичная утечка/доступ больше не вскрывает весь контент.

> **Реализационные гейты:** signed PreKey verify до external ratchet testers; ratchet required-by-default до GA; signed `/v1/escrow/config` без production bypass; threshold-decap ceremony и per-epoch destruction в HSM; low-S wrapper; PQ-threshold (future). См. [ADR-0005](../adr/0005-kodium-readiness-gate.md) и `security-test-plan.md` §8.

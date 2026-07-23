# ADR-0016: Escrow Key Hierarchy, Threshold Decapsulation, and Scope Invariant

## Status

Accepted · 2026-07-22 · **Amends** [ADR-0004](./0004-controlled-escrow.md)

## Context

[ADR-0004](./0004-controlled-escrow.md) ввёл обязательный `escrow_blob` под **единый** глобальный `Escrow_Public`, с реконструкцией полного `Escrow_Private` в HSM по M-of-N (Shamir). Аудит [kodium-security-audit.md](../03-security/kodium-security-audit.md) зафиксировал риск **R-0**: единый долгоживущий `Escrow_Private` — это точка отказа, компрометация которой (утечка ключа, сговор кастодианов, инсайдер в момент сборки ключа в памяти HSM) вскрывает **весь** приватный контент ретроспективно и потенциально бесследно. Требование законодательства (controlled legal intercept) при этом сохраняется.

Цель решения: возможность легального доступа остаётся, но **единичный доступ или единичная компрометация вскрывает минимальный срез**, а не всё; полный приватный ключ не существует целиком ни в одной точке; каждый доступ доказуемо ограничен ордером.

## Decision

Три ортогональных механизма — **скоуп ключей**, **threshold-decapsulation**, **scope-инвариант HSM** — плюс verifiable transparency.

### 1. Иерархия escrow-ключей (регион × эпоха × шард)

Единый `Escrow_Public` заменяется семейством ключей:

```text
Escrow_Public[region, epoch, shard]
  region = immutable conversation.home_region (RU | EU)
  epoch  = календарный квартал (YYYYQn), настраиваемо
  shard  = chat_shard (hash-партиция chat_id, см. storage-sharding.md)
```

- RU и EU — отдельные regional cells с независимыми HSM, custodians, key shares и pinned signing roots. Conversation получает immutable `home_region`; private key material не пересекает границу cell.
- Клиент выбирает `Escrow_Public[region,epoch,shard]` по home region, дате сообщения и шарду чата.
- Публичные ключи публикуются через signed `GET /v1/escrow/config` bundle: `config_version`, region, epoch, shard, `key_id`, validity, current+next X25519-threshold/ML-KEM public keys, signature с цепочкой к pinned regional root. Beta stub обязан иметь тот же контракт и отдельный test root.
- Unsigned/expired/not-yet-valid, unknown-key и cross-region bundle отклоняется fail-closed; private keys никогда не входят в bundle/API и не покидают HSM.
- `escrow_blob` начинается с открытых `region_id || epoch_id || shard_id || key_id || key_commitment`, чтобы regional HSM выбрал нужный приватный ключ и проверил одинаковость ratchet/wrapped/escrow paths.
- **Ротация:** новый ключ на каждую эпоху. **Уничтожение:** `Escrow_Private[region,epoch,shard]` физически стирается в HSM после 6-месячного content retention всех зависимых blob (или снятия legal hold) — «harvest now, decrypt later» невозможен за пределами окна.

Радиус компрометации одного `Escrow_Private[region,epoch,shard]` = один квартал одного шарда одной regional cell, а не вся история.

### 2. Threshold decapsulation (ключ не собирается целиком)

`Escrow_Public[region,epoch,shard]` — **гибридный** ключ `(X25519_thr, MLKEM_pub)`:

```text
Encapsulation (клиент):
  ss_pq   = MLKEM.encapsulate(MLKEM_pub)            // PQ-конфиденциальность
  eph     = X25519.generate()
  ss_cl   = X25519(eph.secret, X25519_thr.public)   // classical, threshold-декапсулируемый
  K       = HKDF-SHA256(ss_cl || ss_pq, info="tima/escrow/v1")
  sym     = SecretBox(message_key, K)
  escrow_blob = region_id || epoch_id || shard_id || key_id || key_commitment ||
                eph.public || kem_ct || sym

Decapsulation (HSM/enclave, t-of-n):
  каждый кастодиан i: partial_i = share_i * eph.public   // доля скаляра X25519
  ss_cl = Lagrange_combine(partial_i…)                    // полный скаляр НЕ собирается
  ss_pq = MLKEM.decapsulate(kem_ct, MLKEM_priv)           // внутри enclave
  K     = HKDF(ss_cl || ss_pq); message_key = SecretBox.open(sym, K)
```

- **Classical-слой** даёт свойство threshold: приватный X25519-скаляр разделён Shamir t-of-n и **никогда не реконструируется целиком** — кастодианы отдают только частичные результаты DH.
- **PQ-слой** (ML-KEM) обеспечивает post-quantum конфиденциальность; оба слоя обязательны (HKDF от обоих).
- **Known limitation:** нативный threshold для ML-KEM — research-grade. Интерим: `MLKEM_priv` живёт только внутри enclave за той же M-of-N-авторизацией и уничтожается по эпохе; access-control-гарантия threshold обеспечивается classical-слоем. Полный PQ-threshold — в roadmap.

### 3. Scope-инвариант HSM

HSM/enclave **никогда не экспортирует приватный материал**. Вход — подписанный ордер, выход — только message-ключи по scope:

```text
DecapRequest {
  warrant_ref, scope{ chat_ids[], date_from, date_to }, custodian_approvals[t]
}
→ enclave: для каждого escrow_blob в scope → threshold-decap → message_key
→ выход: список message_key строго в пределах scope; Escrow_Private НЕ покидает HSM
```

Инвариант: `один доступ = ровно ордер, не больше`. Massaccess = массовый ордер, что видно в transparency log.

### 4. Verifiable transparency log

Каждый факт декапсуляции добавляется в append-only лог с криптографическим доказательством включения (Merkle-tree, в духе Certificate Transparency), публично-проверяемый. Generic внутренний WORM-audit для escrow/legal hold/purge/key-config/recovery событий является нормативным: записи связаны `prev_hash`, Merkle checkpoints подписаны и имеют inclusion/consistency proofs. Скрыть изменение или массовый доступ невозможно даже для инсайдера.

### 5. Связь с key-commitment (R-1)

Escrow-путь **обязан** приводить к тому же `message_key`, что и wrapped/ratchet-путь. Обеспечивается key-commitment из [ADR-0017](./0017-kodium-crypto-hardening.md): enclave пересчитывает commitment из восстановленного ключа и сверяет с подписанным заголовком; несовпадение → ключ помечается `commitment_mismatch` в investigation package и audit. Иначе следствие может получить не тот контент, что видел получатель.

## Consequences

**Positive:** радиус компрометации ограничен (регион×эпоха×шард); полный приватный ключ не существует целиком; доступ доказуемо ограничен ордером и публично прозрачен; сохранён legal intercept; принцип минимизации/пропорциональности (GDPR-friendly).

**Negative:** сложнее ceremony и HSM-интеграция (threshold-DH, per-epoch ключи); больше ключей в управлении и публикации; PQ-threshold пока частичный (задокументированное ограничение); клиент должен корректно выбирать `epoch/shard`.

## References

- [ADR-0004](./0004-controlled-escrow.md) — базовое решение controlled escrow
- [ADR-0017](./0017-kodium-crypto-hardening.md) — key-commitment (R-1)
- [escrow-legal-access.md](../03-security/escrow-legal-access.md)
- [key-lifecycle.md](../03-security/key-lifecycle.md) §7
- [threat-model.md](../03-security/threat-model.md) §3 (Escrow), §5 (R-0)
- [kodium-security-audit.md](../03-security/kodium-security-audit.md) §2, R-0
- [storage-sharding.md](../04-data/storage-sharding.md) — определение shard

# ADR-0013: Double Ratchet — gated rollout (путь A)

**Статус:** принят · **Дата:** 2026-07-13

## Контекст

[ADR-0004](./0004-controlled-escrow.md) и [crypto-protocol.md](../03-security/crypto-protocol.md) фиксируют трёхслойную модель: конверт + wrapped keys (путь B) + escrow всегда; Double Ratchet — опциональный слой PFS (путь A).

Kodium уже реализует X3DH и Double Ratchet, но [ADR-0005](./0005-kodium-readiness-gate.md) блокирует production до независимого аудита и signed PreKey verification.

## Решение

1. **Envelope baseline:** путь B — конверт + wrapped keys + обязательный escrow. Доставка и мультиустройство не зависят от ratchet-состояния.
2. **Internal ratchet gate:** включить путь A — Double Ratchet поверх конвертов только для внутренних 1:1 сессий:
   - X3DH через PreKey bundle (`prekeys`);
   - `DoubleRatchetSession` (Kodium); `maxSkippedMessages = 2000`;
   - состояние — SQLDelight encrypted export;
   - при desync — silent fallback на путь B, фоновое пересоздание сессии.
3. **External tester gate:** ни один внешний ratchet tester не допускается, пока клиент не проверяет Ed25519 signature Signed PreKey и не отвергает missing/invalid/stale bundle.
4. **Не в scope rollout:** PQ ratchet (PQXDH), ratchet для групп (остаётся Sender Keys / GK), ratchet для ВП (только путь B).
5. **GA gate:** путь A становится required-by-default для всех поддерживаемых 1:1 до GA, только после закрытия ADR-0005 (аудит, signed prekey verify, cross-platform vectors). Wrapped key остаётся fallback, но production bypass ratchet default запрещён.

## Отклонено

- Ratchet как единственный механизм доставки — операционно хрупко (офлайн, мультиустройство, ВП).
- Включение ratchet в MVP до стабилизации конвертного контура — риск регрессий доставки.

## Последствия

- Клиент и сервер MUST корректно обрабатывать `ratchet_envelope = null` на всём жизненном цикле.
- PreKey maintenance — [key-lifecycle.md](../03-security/key-lifecycle.md) §3.
- Метрики: доля сообщений path A vs B, частота ratchet resync.

## References

- [ADR-0004](./0004-controlled-escrow.md)
- [ADR-0005](./0005-kodium-readiness-gate.md)
- [messenger-crypto-architecture.md](../messenger-crypto-architecture.md)
- [crypto-protocol.md](../03-security/crypto-protocol.md) §3.5, §12

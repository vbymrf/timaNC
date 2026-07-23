# Privacy и Compliance

## 1. Regulatory scope

| Regulation | Applicability |
|------------|---------------|
| GDPR | EU users — export, erasure, DPA |
| 152-FZ (RU) | Применяется к RU regional cell; localization и cross-region relay требуют legal sign-off до production |
| Legal intercept | Escrow workflow — domestic law dependent |

> **Action item:** legal review before prod in each jurisdiction.

## 2. Data categories

| Category | Examples | Lawful basis |
|----------|----------|--------------|
| Account | phone, email, profile | Contract |
| Messaging metadata | chat_id, timestamps | Contract |
| Message content (E2E) | ciphertext | Contract |
| Public content | posts, reactions | Contract / legitimate interest |
| Call metadata | duration, participants | Contract |
| Analytics (opt-in) | screen opens, crashes | Consent |

## 3. User rights

| Right | Implementation |
|-------|----------------|
| Access | Export JSON/HTML (UI: export chat) |
| Erasure | 30-day account delete grace ([doc_UI/25-settings-help-bugs.md](../doc_UI/25-settings-help-bugs.md)) |
| Portability | Export formats PDF/HTML/TXT |
| Objection | Opt-out analytics |

**Conflict:** erasure vs escrow retention — user notified in privacy policy that legal hold may retain ciphertext blobs, immutable revisions, tombstones и media variants per law.

## 3.1. Retention и legal hold

- Отправленные/опубликованные документы хранятся как immutable revisions; edit создаёт новую revision, delete — tombstone.
- Нормативный schedule: transmission metadata — **3 года**; content/ciphertext/media/revisions/`escrow_blob`/participant wrapped keys — **6 месяцев**; escrow/legal-hold/WORM audit и криптографические proofs — **7 лет**.
- Generic legal hold может адресовать subject/account/conversation/message/revision/media/time-range и приостанавливает physical purge полного связанного object graph, включая soft-deleted revisions, wraps, `escrow_blob` и media variants.
- Снятие hold возвращает данные к обычному schedule и не означает немедленный purge.
- Постановка/снятие hold и physical purge фиксируются в generic append-only WORM audit с hash-chain (`prev_hash`) и Merkle checkpoints/proofs.
- Active legal hold всегда имеет приоритет над TTL, user erasure и scheduled purge; административного bypass нет.
- Public и private permanent storage содержат только `thumbnail`, `preview`, `full`; `original` не является хранимым variant. Public upload source существует только временно в quarantine.

## 4. Data residency

- Production topology содержит две отдельные regional cells: **RU** и **EU** ([ADR-0018](../adr/0018-dual-region-ru-eu-production-architecture.md), amendment к ADR-0008).
- Каждая conversation получает immutable `home_region`; content, transmission metadata и crypto processing маршрутизируются в эту cell.
- RU/EU имеют независимые escrow key hierarchies, HSM, custodians и pinned signing roots. Escrow private keys и key shares **must not** покидать home region.
- Cross-region replication private content/escrow material запрещена; изменение home region требует отдельной audited migration с re-encryption и legal approval.

## 5. DPA / subprocessors

| Subprocessor | Data shared |
|--------------|-------------|
| Cloud provider | All infra |
| FCM/APNs | Push tokens, generic payload |
| SMS provider | Phone number |
| Apple/Google | Attestation tokens |

Maintain list in public privacy policy.

## 6. Transparency report (template)

Annual publication:

- Number of legal requests received
- Number approved / rejected
- Accounts affected (ranges)
- Average response time
- Escrow decapsulations: number of orders, aggregate scope size (message ranges), `commitment_mismatch` count ([escrow-legal-access.md](./escrow-legal-access.md) §3c)
- Verifiable transparency log root(s) published for external audit ([ADR-0016](../adr/0016-escrow-key-hierarchy-and-threshold.md) §4)

## 7. Moderation (public)

- User reports (UI 🚩) → moderation queue.
- E2E private content **not** scanned server-side.
- Public content: hash blocklists, optional ML (Phase 3).

## 7.1. Children's privacy

- Age gate if required by market — product decision pending.

## 8. Cookie / tracking

- Desktop/mobile app: no third-party ad trackers v1.
- Analytics: anonymized, opt-in ([doc_UI/25-settings-help-bugs.md](../doc_UI/25-settings-help-bugs.md)).

## 9. Breach notification

- Internal: 24h to security team.
- External: per GDPR 72h if personal data breach.
- Playbook: [incident-response.md](../07-operations/incident-response.md).

## 10. Ссылки

- [escrow-legal-access.md](./escrow-legal-access.md)
- [content-security-matrix.md](../01-product/content-security-matrix.md)

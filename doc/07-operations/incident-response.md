# Incident Response

## 1. Severity levels

| Sev | Definition | Response time |
|-----|------------|---------------|
| S1 | Full outage, data breach | 15 min |
| S2 | Major degradation (>10% users) | 30 min |
| S3 | Partial feature broken | 4 h |
| S4 | Minor bug | Next sprint |

## 2. Roles

| Role | Responsibility |
|------|----------------|
| Incident Commander | Coordination |
| Tech Lead | Mitigation |
| Comms | Status page, support |
| Security | Breach assessment |
| Legal | Escrow/regulatory |

## 3. Playbook (generic)

1. Detect (alert / report)
2. Acknowledge in PagerDuty
3. Open incident channel
4. Assess severity
5. Mitigate (rollback / scale / disable feature flag)
6. Resolve
7. Postmortem within 5 business days (S1/S2)

## 4. Security incidents

| Type | Immediate action |
|------|------------------|
| Key compromise | Revoke devices, rotate JWT keys, GK rotation |
| Escrow abuse | Disable Escrow Service, preserve audit |
| Data leak | Contain, notify legal 24h |
| DDoS | Enable gateway aggressive rate limit |
| Private media entered public pipeline | Stop affected pipeline, quarantine derivatives, revoke capabilities, preserve audit |
| Executable accepted | Disable affected content path, quarantine object, assess client execution exposure |
| Presigned URL exposure | Revoke where supported, verify auth and 15m expiry, redact URL from telemetry |
| Message/revision mutation | Disable edits, preserve immutable records/events, investigate authorization and storage path |
| Legal hold violation | Stop purge immediately, preserve complete object graph, notify Security and Legal |

## 5. Communication templates

- Status page: «Investigating elevated error rates»
- User notification for breach: per GDPR 72h

## 6. Postmortem template

- Timeline
- Root cause (5 whys)
- Impact (users, duration)
- Data scope (messages, revisions, media variants, metadata) and retention/legal-hold impact
- Action items with owners

## 7. On-call

- Follow-the-sun or regional rotation at Growth.
- Runbook links in alert annotations.

## 8. Ссылки

- [runbooks.md](./runbooks.md)
- [privacy-compliance.md](../03-security/privacy-compliance.md)

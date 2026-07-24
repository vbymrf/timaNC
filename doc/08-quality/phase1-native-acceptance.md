# Phase 1 native acceptance evidence

> **Статус:** обязательный Phase 1 exit gate · **Версия:** 1.0 · **Дата:** 2026-07-24

## 1. Назначение

Этот gate подтверждает не только компиляцию platform shell, но и фактический
private 1:1 journey на Android, iOS и Windows. Hosted build, unit reports и
unsigned package сами по себе не являются native acceptance.

Проверка выполняется на одном commit SHA. Для каждого platform artifact
сохраняются SHA-256, runner/toolchain, версия ОС и ссылка на неизменяемый CI
artifact. Signed/store artifacts и vendor credentials остаются Phase 5 gates.

## 2. Безопасность evidence

- Используются отдельные development accounts и несекретный test plaintext.
- В logs/screenshots запрещены access/refresh tokens, OTP, device seed, media
  keys, escrow material и presigned URLs.
- Private image plaintext не прикладывается как CI artifact. Допустимы только
  screenshot уже расшифрованного test image и ciphertext/checksum metadata.
- Development HMAC/escrow разрешены только явными debug build/runtime flags.
- После journey выполняется logout и проверяется очистка session, UI cache,
  message/media outbox и media ciphertext blobs.

## 3. Hosted build evidence

Workflow `client-platform-validation.yml` обязан сохранить:

| Platform | Обязательные artifacts |
|---|---|
| Android | unsigned AAB, `testReleaseUnitTest`/shared JUnit XML, SHA-256 manifest |
| iOS | XCFramework, unsigned simulator `.app`, shared/iOS JUnit XML, Xcode version и SHA-256 manifest |
| Windows | unsigned MSIX, Windows/shared JUnit XML, SHA-256 manifest |

Green workflow доказывает Swift/Kotlin/native packaging совместимость, но не
заменяет шаги ниже.

## 4. Обязательный journey

Один и тот же сценарий выполняется на Android, iOS и Windows. Windows сначала
проходит QR link/claim с доверенного mobile device; mobile platforms используют
development registration/login.

1. Восстановить secure session после перезапуска приложения.
2. Создать/open private 1:1 chat и получить chat list/history.
3. Отправить encrypted text; peer получает и расшифровывает его.
4. Симулировать retryable transport failure после reservation/encryption,
   завершить процесс, запустить снова и подтвердить отправку из durable outbox
   с тем же message/revision identity и idempotency key.
5. Выполнить edit, mark-read и delete с корректными author/peer restrictions.
6. Выбрать одно изображение, получить ровно `thumbnail`, `preview`, `full`,
   загрузить только ciphertext и привязать media-only DocumentV2.
7. Peer скачивает/расшифровывает thumbnail и preview, видит thumbnail в thread и
   открывает in-app preview. External export/open не используется.
8. Отключить сеть, перезапустить приложение и подтвердить encrypted durable
   chat/thread cache без ложного background-delivery claim.
9. Выполнить logout, перезапустить приложение и подтвердить отсутствие session,
   decrypted history, pending message/media queue и доступного media preview.

## 5. Evidence manifest

Для каждой платформы создаётся JSON или Markdown manifest:

```text
commit_sha:
platform:
os_version:
device_or_simulator:
artifact_name:
artifact_sha256:
build_run_url:
journey_started_at_utc:
journey_finished_at_utc:
steps_1_to_9: PASS|FAIL
evidence_files:
reviewer:
```

Каждый screenshot/video именуется номером шага. Failure не перезаписывается
повторным run: создаётся новый manifest, а предыдущий сохраняется.

## 6. Exit rule

Gate считается закрытым только когда manifests Android, iOS и Windows:

- относятся к одному commit SHA;
- содержат PASS для шагов 1–9;
- ссылаются на green hosted package/test artifacts;
- проверены reviewer;
- не содержат секретов или private production data.

До этого `phase1-exit-review.md` сохраняет решение **BLOCKED**.

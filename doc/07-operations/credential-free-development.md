# Разработка без учётных данных магазинов

Статус: **действующая политика до Phase 5**

## 1. Решение

Разработка и repository-controlled проверка TIMA продолжаются без Apple
Developer и Google Play Console accounts. Отсутствие этих аккаунтов не
останавливает Phase 1 implementation, но ограничивает подписанные сборки,
vendor attestation и фоновую доставку на iOS.

Учётные данные разделены на независимые классы:

| Класс | Примеры | Нужен сейчас |
|---|---|---|
| Store publication | App Store Connect / Play Console upload API | Нет |
| Signing | Android keystore, Apple certificate/profile, Windows PFX | Нет, Phase 5 |
| Vendor runtime | FCM service account, APNs p8, App Attest, Play Integrity | Нет, Phase 5 |
| TIMA service | Внутренний token между worker и `push-gateway` | Да для собственного deploy |

Store publication credentials не должны попадать в репозиторий или клиентскую
сборку. Их отсутствие не заменяется фиктивными production secrets.

## 2. Что разрабатывается сейчас

- unsigned Android AAB, iOS simulator app/XCFramework и Windows MSIX;
- Go server, realtime gateway, worker и собственный `push-gateway`;
- Android UnifiedPush fallback без Google Play;
- foreground WebSocket и REST catch-up на Android/iOS/Windows;
- local/dev attestation path только в development/test profile;
- contracts, code generation, migrations, Compose E2E и bounded SLO smoke;
- полный пользовательский messaging UI и native acceptance journeys.

Нормативная схема уведомлений:
[hybrid-notification-delivery.md](../02-architecture/hybrid-notification-delivery.md).

## 3. Что запрещено считать доказанным

До появления реальных accounts, credentials и устройств нельзя заявлять:

- signed/internal-track Android или iOS release;
- успешный App Attest или Play Integrity production flow;
- реальную FCM/APNs доставку;
- background push на iOS без APNs entitlement;
- installation acceptance подписанных AAB/xcarchive/MSIX;
- invited-cohort SLO до 100 пользователей.

Unsigned build и local smoke подтверждают реализацию, но не заменяют эти
артефакты.

## 4. Поведение приложений

### Android

Direct-distribution build использует UnifiedPush, foreground WebSocket и REST
catch-up. FCM adapter остаётся в приложении и включается при наличии валидной
Firebase configuration. Оба transport могут быть зарегистрированы одновременно;
dedup выполняется по event/message ID и sync cursor.

### iOS

APNs остаётся основным background channel для будущего credentialed build.
Без Apple Developer account приложение получает realtime события в foreground и
выполняет catch-up при запуске/resume. Suspended/terminated процесс нельзя
надёжно разбудить собственным сервером.

### Windows

Приложение использует foreground WebSocket и периодический REST catch-up.
Microsoft Store/WNS account не является условием Phase 1 development.

## 5. Profiles и секреты

| Profile | Vendor adapters | Placeholder credentials | Поведение |
|---|---|---|---|
| `development` / `test` | Необязательны | Разрешены только явно помеченные test fixtures | Локальный HMAC, fake endpoints, Compose |
| credential-free hosted validation | Выключены | Только non-production build config | Unsigned artifacts и собственный gateway |
| production / credentialed | Fail-closed | Запрещены | Каждый включённый adapter требует полный secret contract |

Production profile не должен автоматически откатываться на development HMAC,
fake Firebase config или незашифрованное хранение token.

## 6. План перехода в Phase 5

1. Назначить юридического владельца Apple/Google/Microsoft accounts.
2. Зафиксировать bundle/application/package identifiers.
3. Создать protected GitHub environments с required reviewers.
4. Выпустить signing certificates/keystores и настроить rotation/revocation.
5. Настроить FCM, APNs, App Attest и Play Integrity для реальных identifiers.
6. Добавить production APNs/App Attest entitlements и App Attest enrollment.
7. Запустить credentialed workflows и сохранить immutable artifacts/checksums.
8. Установить кандидаты на representative device matrix.
9. Проверить vendor и TIMA fallback channels end to end.
10. Провести invited cohort до 100 пользователей и сохранить SLO evidence.

Store upload остаётся отдельным release-manager действием и не выполняется
автоматически существующими workflows.

## 7. Критерии готовности credential-free этапа

- все hosted repository-controlled workflows зелёные;
- собственный `push-gateway` healthy без vendor credentials;
- Android UnifiedPush и общий wake-to-sync flow проходят E2E;
- Windows и iOS foreground/resume catch-up проходят acceptance;
- production profiles fail closed при отсутствующих обязательных secrets;
- документация и UI честно показывают ограничения канала;
- Phase 1 остаётся `BLOCKED`, пока его актуальные repository/UI gates не закрыты.

## 8. Ссылки

- [Гибридная доставка уведомлений](../02-architecture/hybrid-notification-delivery.md)
- [Phase 1 release gates](./release-gates.md)
- [Phase 1 exit review](../09-delivery/phase1-exit-review.md)
- [CI/CD и Release](../09-delivery/ci-cd-release.md)

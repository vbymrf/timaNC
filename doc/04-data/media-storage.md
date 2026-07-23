# Media Storage

> **Статус:** normative · **Версия:** 2.0 · **Дата:** 2026-07-22

## 1. Два контура обработки

| Контур | Содержимое на сервере | Проверка |
|--------|------------------------|----------|
| Private/E2E | Только ciphertext | Отправитель до шифрования и получатель после расшифрования |
| Public/social | Plaintext | Сервер: magic bytes, AV, санитизация и транскодирование |

Прямой режим передачи файлов отсутствует. Все файлы проходят соответствующий pipeline и сохраняются как `media_object`.

## 2. Private/E2E pipeline

1. Отправитель определяет MIME по magic bytes, проверяет файл локальным AV и блокирует executable.
2. Клиент очищает метаданные и создаёт `thumbnail`, `preview`, `full`.
3. Каждый вариант шифруется отдельным `media_key`; файлы больше 10 МБ шифруются по chunks.
4. Доменный `POST /v1/chats/{chat_id}/media/uploads` или `/v1/groups/{group_id}/media/uploads` создаёт объект и возвращает presigned PUT URL на 15 минут.
5. Клиент загружает ciphertext и вызывает `POST /v1/media/uploads/{media_id}/complete`.
6. В `DocumentV2.markup` записываются `media_id` и `secret_ref`; обязательные ключи вариантов и опциональные sensitive имя/caption входят в `encrypted_metadata`.
7. Получатель после расшифрования повторно определяет MIME и проверяет файл перед render/open.

Сервер никогда не видит private plaintext. Он проверяет размер, количество вариантов/chunks, checksum ciphertext, квоту и связь объекта с сообщением.

### 2.1. Chunked encryption

```text
media_key = random(32)
for i, chunk in enumerate(chunks):
  chunk_key = HKDF(media_key, info="chunk:" + i)
  chunk_ct[i] = AEAD(chunk_key, unique_nonce, chunk)
```

## 3. Public/social pipeline

1. `POST /v1/posts/assets` создаёт staging-объект и presigned PUT URL.
2. Сервер определяет MIME по magic bytes; расширению и клиентскому `Content-Type` не доверяет.
3. Сервер запускает AV, удаляет EXIF/IPTC/активное содержимое и перекодирует поддерживаемые image/video.
4. Создаются три варианта; после `media.ready` объект разрешено привязать к посту.
5. Заражённые, неизвестные и executable-файлы блокируются.

## 4. Поддерживаемые типы

| Группа | Политика |
|--------|----------|
| Image/video/audio/voice | Render после проверки; image/video нормализуются |
| PDF/Office/TXT | Download/open через системный read-only viewer после проверки |
| Archives | Download; распаковка только в sandbox |
| Executable/script (`exe`, `apk`, `bat`, `sh`, `vbs`, `jar`, `class` и аналоги) | Загрузка, хранение и передача запрещены |
| Unknown | Блокируется |

Максимальный размер файла — 2 ГБ. Максимум медиа в одном сообщении — 10.

## 5. Варианты

Хранятся только три производных варианта:

| Вариант | Image | Video | Назначение |
|---------|-------|-------|------------|
| `thumbnail` | 40×40 | первый безопасный кадр | Список сообщений |
| `preview` | до 320 px | до 480p | Поток |
| `full` | до 1280 px | до 720p | Полноэкранный просмотр |

Original не сохраняется, не выдаётся и не имеет `path`. Для документов, архивов и аудио `full` является нормализованным/проверенным представлением.

## 6. Доступ

1. Клиент отправляет авторизованный `POST /v1/media/{media_id}/access` с требуемым вариантом.
2. Сервер проверяет владельца, членство в чате или право на public-объект.
3. Сервер выдаёт presigned GET URL на 15 минут.
4. Private-клиент скачивает ciphertext, разрешает `secret_ref` и расшифровывает вариант ключом из `DocumentV2.encrypted_metadata`.

Постоянные и публичные URL в сообщении не хранятся. Range requests разрешены по границам chunks.

## 7. Жизненный цикл

- Незавершённая загрузка без связи удаляется через 7 дней.
- Заблокированный staging-объект удаляется через 24 часа, если нет legal hold.
- Удаление сообщения — soft delete; физический объект и его ревизии сохраняются по [retention-archival.md](./retention-archival.md).
- CASCADE physical delete при пользовательском удалении запрещён.
- CDN/Redis не определяют retention: истечение cache не удаляет origin.

## 8. Хранилища

| Bucket | Content |
|--------|---------|
| `tima-media-e2e` | Private ciphertext variants |
| `tima-media-public` | Проверенные public variants |
| `tima-public-staging` | Public assets до проверки |
| `tima-voice-e2e` | Private voice ciphertext |

Private CAS-дедупликация выключена, чтобы не раскрывать наличие файла. Public CAS разрешена после проверки.

## 9. Ссылки

- [message-document-format.md](./message-document-format.md)
- [public-content-format.md](../01-product/public-content-format.md)
- [crypto-protocol.md](../03-security/crypto-protocol.md)
- [doc_UI/19-attachments-media.md](../doc_UI/19-attachments-media.md)

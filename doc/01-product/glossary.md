# Глоссарий

| Термин | Определение |
|--------|-------------|
| **Сообщество (Community)** | Администраторский серверный контейнер. Индекс: [social-objects/00-index.md](./social-objects/00-index.md) |
| **Канал (Channel)** | Только посты; обсуждение — Comments к посту. [social-objects/channel.md](./social-objects/channel.md) |
| **Comment** | Публичный комментарий; `target_type` ∈ post, media, collection_item. Не для E2E-чатов |
| **DocumentV2** | Канонический nullable-payload документ: обязательный `metadata` (`format_version=2`, `revision_number`, `content_mode`), опциональные текстовый массив/`markup`/`encrypted_metadata`; отсутствующее — omitted/`NULL`, без пустых контейнеров/ciphertext. Public использует `nodes` и `text_link` с открытым `href`; private — `encrypted_nodes` и entity `text_link` с `secret_ref` на URL в `encrypted_metadata`. [public-content-format.md](./public-content-format.md) |
| **Immutable revision** | Неизменяемая версия сообщения, поста или комментария; редактирование создаёт новую ревизию со ссылкой на предыдущую |
| **PublicPublishing** | Черновики, расписание, publish для channel/media editors |
| **Дочерний объект (child object)** | Группа, канал или аудиочат внутри сообщества (или самостоятельно — кроме аудиочата, у которого `community_id` обязателен) |
| **Подписка (subscription)** | Связь пользователя с сообществом или каналом для ленты и уведомлений; не заменяет членство в private group |
| **Папка каталога (Catalog folder)** | Клиентская персональная группировка ссылок в каталоге; **не** сообщество, без серверных admin-прав |
| **Аудиочат (voice chat)** | Голосовая комната LiveKit; всегда в сообществе; при создании без выбора сообщества — auto-create контейнера |
| **Виртуальный пользователь (ВП)** | Обычная запись `users` с `account_type=virtual`, `owner_user_id`; без credentials/devices; управляется операторами. [virtual-user.md](./social-objects/virtual-user.md) |
| **Оператор ВП** | Human-пользователь с правом читать/отвечать от имени ВП; действия audit через `actor_user_id` |
| **Социальное взаимодействие** | Окно 4: агрегатор входящих ВП и social events; вкладки Входящие / Мои треды / Реакции / Коллекции; [10-free-communication.md](../doc_UI/10-free-communication.md) |
| **Managed inbox** | Серверные карточки обращений (`inbox_threads`) и событий (`inbox_events`) окна 4; не дублирует тела сообщений |
| **inbox_thread** | Обращение к ВП (E2E, `chat_id`) или к сущности (plaintext `appeal_messages`); FSM: `new → taken → snoozed → closed`; assignee командный |
| **entity_message** | Карточка сообщения от группы/канала/сообщества в окно 4; сущности **не пишут в личку**; MVP: `POST /inbox/notify`; Bot API: `notifyUser` |
| **notifyUser** | Bot API метод: карточка `entity_message` от имени установленной сущности; scope `notify` |
| **Обращение (appeal)** | `POST /appeals`: к ВП — E2E-чат; к сущности — публичный plaintext-тред с атрибуцией ответов сущности |
| **SocialInboxProjection** | Сервис read model окна 4: `inbox_threads`, `inbox_events`, `social_inbox_preferences` |
| **Bot Application** | Техническая автоматизация, установленная в group/channel/community; без `@username` и `user_id`; адресные сообщения → `entity_message` в окно 4. [bot-application.md](./social-objects/bot-application.md) |
| **Installation** | Привязка Bot Application к одному social object; сервер выводит author; deny-by-default scopes |
| **Participant E2E** | Основной контур между участниками: X3DH + Double Ratchet (1:1), Sender Keys/GK (группы), `shelf_key` (личный контент). Сервер — ретранслятор шифртекста. Канон: [ADR-0014](../adr/0014-participant-e2e-and-recovery.md) |
| **Controlled escrow** | Обязательный параллельный ML-KEM blob для HSM; юридический доступ по M-of-N, **включая soft-deleted** сообщения. **Не** пользовательский recovery. Канон: [escrow-legal-access.md](../03-security/escrow-legal-access.md) |
| **E2E (UI-ярлык)** | «Защищённый чат»: participant E2E на устройстве. Юридически продукт **не strict E2E** из-за обязательного escrow |
| **Envelope encryption** | Per-message symmetric key (`message_key`) + `SecretBox` для payload |
| **Wrapped key** | `Box(ephemeral, device_identity, message_key)` на сервере; fallback доставки при desync ratchet |
| **GK (Group Key)** | Симметричный ключ периода для private group; ротация при join/leave/kick + каждые 100 msg |
| **Double Ratchet** | Signal-style PFS-сессия; **основной** participant path для 1:1; wrapped keys — fallback |
| **Sender Keys** | Модель группового E2E: один GK на период, wrapped_GK per member |
| **Escrow blob** | ML-KEM-768 encapsulate(Escrow_Public, key) ~1088 B + symmetric wrap; обязателен для private |
| **Peer recovery** | Участник с историей отдаёт её новому устройству с согласия; подписи проверяются. Канон: [ADR-0014](../adr/0014-participant-e2e-and-recovery.md) |
| **Секретная фраза** | Удостоверение личности + optional backup; **не** master key живой переписки; per-chat subkeys через HKDF |
| **Physical purge** | Безвозвратное удаление ciphertext/escrow по retention; до этого soft-deleted доступен escrow |
| **Attestation** | App Attest (iOS), Play Integrity (Android); Windows — trust anchor через телефон |
| **SFU** | Selective Forwarding Unit (LiveKit); медиа relay без mixing |
| **SRTP** | Шифрование RTP в WebRTC; transport-level, не Kodium |
| **Outbox pattern** | Запись события в БД в той же транзакции, что и доменная запись; async publish в EventBus (Redis Streams beta, Kafka production/GA) |
| **Hot/warm/cold** | Tiered storage по `created_at`, не по содержимому |
| **MAU / DAU** | Monthly / Daily Active Users |
| **Shard** | Логический сегмент данных по `chat_id` или `user_id` |
| **WireMD** | Формат UI wireframe в markdown |
| **Рекомендация ([+]/[−])** | Лайк/дизлайк **только публичного** контента; сильный сигнал серверного скоринга общей ленты. Не путать с эмоциями. [18-content-actions.md](../doc_UI/18-content-actions.md), [feed-ranking.md](../04-data/feed-ranking.md) §5 |
| **Эмоция (шкала 9)** | Оценка любого сообщения: 4 пары ±1 + 🧘 нейтральная; одна на сообщение. 8 эмоций → раздельные счётчики рейтинга «+/−» автора/группы; 🧘 в метрику не входит. Таблица `emotions` |
| **Рейтинг «+/−»** | Раздельные счётчики положительных и отрицательных оценок по пользователю/группе/каналу (не суммируются). `rating_counters`; обновляется воркером по `emotions` |
| **Атрибут** | Хэштег из единого реестра; создаётся автором при публикации; резолвится из hashtag-node `DocumentV2`. [feed-ranking.md](../04-data/feed-ranking.md) §3 |
| **Жанр** | Курируемая сервером «папка» атрибутов; состав правит только сервер. `genres` |
| **Полка избранного** | Публичная (видна друзьям, питает ленту друзей) или личная 🔒 (шифруется `shelf_key`, в ленты не попадает). [feed-ranking.md](../04-data/feed-ranking.md) §2 |
| **`declared` / `approved` / `rejected`** | Статус пары *(пост, атрибут)*: объявлена автором → одобрена (тематическая выдача) или отклонена. `post_attributes` |
| **`community_access`** | Уровень доступа элемента внутри сообщества: `preview`, `open`, `restricted`. [communities.md](./communities.md) §3 |
| **`preview`** | Витрина: контент элемента доступен без подписки на сообщество |
| **`open`** | Контент только подписчикам сообщества; элемент виден в списке |
| **`restricted`** | Только по приглашению/роли; видимость названия — `restricted_visible` |

### Migration aliases (ACL v1 → v2)

| Legacy (`visibility`) | Канон (`community_access`) | Примечание |
|-----------------------|----------------------------|------------|
| `public` | `preview` | Контент/витрина без подписки на сообщество |
| `members_only` | `open` | Контент для подписчиков сообщества |
| `hidden` | `restricted` | При `restricted_visible=false` — полностью скрыт в списке |

## Сокращения протокола

| Поле | Значение |
|------|----------|
| `message_id` | Monotonic uint64 per chat |
| `gk_version` | Версия group key |
| `period_id` | Период escrow для media/text в одном чате |
| `media_id` | Непрозрачный UUID media в открытом `markup`; доступ только через авторизованный доменный запрос и presigned URL на 15 минут |

## UI vs юридическая терминология

| Где | Формулировка |
|-----|--------------|
| UI (статус чата) | «Защищённый чат — сообщения шифруются на вашем устройстве» |
| UI (окно информации) | Краткое пояснение + ссылка на политику — [16-profile-popup.md](../doc_UI/16-profile-popup.md), [27-security-privacy.md](../doc_UI/27-security-privacy.md) |
| Политика конфиденциальности | Полное описание: шифрование на клиенте; доступ провайдера возможен только по юридически обязывающему запросу через M-of-N с аудитом |
| Маркетинг | Не использовать «E2E»/«сквозное» без оговорки |
| Техдоки / compliance | **Controlled escrow** — см. [glossary.md](../01-product/glossary.md) |

В UI для пользователя: **«Защищённый чат»** / **«E2E»** — означает, что сервер не видит plaintext в обычной эксплуатации. В технической и compliance-документации используем **«шифрование с controlled escrow»**, чтобы не создавать ложного впечатления strict E2E без third-party access.

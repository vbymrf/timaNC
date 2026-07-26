# Перенос TIMA Phase 1 на новый ПК

Папка `perenos/` — снимок **локального состояния** этой машины (Docker volumes + Android AVD/system-image).  
Код репозитория сюда не входит — его клонируете отдельно.

Бинарники (`docker/`, `android/*.tar.gz`) в git **не коммитятся**. Копируйте всю папку `perenos` целиком (USB / сеть / архив).

Ориентировочный размер: ~1.5 GB (system-image) + AVD (~1–4 GB) + docker dumps (~10 MB).

---

## 1. Забрать с этой машины

1. Убедитесь, что экспорт завершён (есть файлы в `perenos/docker/` и `perenos/android/`, см. `MANIFEST.txt`).
2. Скопируйте каталог:

```text
z:\!MessNC\perenos
```

на USB или в сетевую шару. Не полагайтесь на `git push` — dumps в ignore.

---

## 2. На новом ПК: репозиторий и tooling

```powershell
git clone <url-репозитория-timaNC> MessNC
cd MessNC
```

Поставьте host-инструменты:

```powershell
.\scripts\setup-windows.bat
```

Нужны минимум: Docker Desktop, JDK 17, Git, Android SDK base (п.7).  
AVD можно не создавать заново (п.8), если восстанавливаете из `perenos/android`.

Скопируйте полученную папку `perenos` **внутрь** корня репо (рядом с `infra/`, `scripts/`).

---

## 3. Env

На исходной машине рабочего `infra/.env` не было — только шаблон.

```powershell
Copy-Item perenos\env\.env.example infra\.env
# при необходимости отредактируйте секреты
```

Если позже появится реальный `infra/.env` — положите его в `perenos/env/.env` перед копированием и на новом ПК: `Copy-Item perenos\env\.env infra\.env`.

---

## 4. Android: system-image + AVD

SDK root по умолчанию:

```text
%LOCALAPPDATA%\Android\Sdk
```

### System image

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
New-Item -ItemType Directory -Force -Path "$sdk\system-images" | Out-Null
tar -xzf perenos\android\system-images-android-34-google_apis-x86_64.tar.gz -C "$sdk\system-images"
```

Должно появиться:

```text
...\system-images\android-34\google_apis\x86_64\
```

### AVD

Если есть архив `Tima_API_34.avd.tar.gz`:

```powershell
$avd = "$env:USERPROFILE\.android\avd"
New-Item -ItemType Directory -Force -Path $avd | Out-Null
tar -xzf perenos\android\Tima_API_34.avd.tar.gz -C $avd
```

Если вместо архива лежит каталог `perenos\android\avd-raw\`:

```powershell
$avd = "$env:USERPROFILE\.android\avd"
New-Item -ItemType Directory -Force -Path $avd | Out-Null
Copy-Item perenos\android\avd-raw\* $avd -Recurse -Force
```

Проверка:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
# ожидается: Tima_API_34
```

Эмулятор / platform-tools / build-tools ставятся через `setup-windows` (п.7), их в `perenos` нет.

---

## 5. Docker volumes

Сначала поднимите stack (создаст пустые named volumes), затем остановите сервисы и залейте dumps.

```powershell
if (-not (Test-Path infra\.env)) { Copy-Item infra\.env.example infra\.env }
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml up -d --build
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml stop

$backup = (Resolve-Path perenos\docker).Path
$vols = @(
  @{ Vol='infra_postgres_data'; File='postgres_data.tar.gz' },
  @{ Vol='infra_redis_data';    File='redis_data.tar.gz' },
  @{ Vol='infra_minio_data';    File='minio_data.tar.gz' },
  @{ Vol='infra_caddy_data';    File='caddy_data.tar.gz' }
)
foreach ($v in $vols) {
  docker run --rm `
    -v "$($v.Vol):/data" `
    -v "${backup}:/backup:ro" `
    alpine:3.21 `
    sh -c "rm -rf /data/* /data/.[!.]* 2>/dev/null; tar xzf /backup/$($v.File) -C /data"
}

docker compose --env-file infra/.env -f infra/docker-compose.dev.yml up -d
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8080/healthz
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8080/readyz
```

Имена volumes рассчитаны на compose-проект `infra` (файл в каталоге `infra/`). Если `docker volume ls` показывает другие префиксы — подставьте их.

---

## 6. Что сознательно не переносится

| Артефакт | Почему |
|----------|--------|
| Полный Android SDK (emulator, build-tools, …) | Ставится `setup-windows` п.7 |
| Docker images (`tima-server:dev`, postgres, …) | `compose up --build` |
| `schema/.tools` codegen | `setup-windows` п.9 / `bootstrap.ps1` |
| LiveKit Phase 2 | Не нужен для Phase 1 |

---

## 7. Быстрая проверка после переноса

```powershell
docker compose --env-file infra/.env -f infra/docker-compose.dev.yml ps
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
Get-Content perenos\MANIFEST.txt
```

# Schema toolchain

Windows PowerShell toolchain для воспроизводимой генерации Go и Kotlin без Docker и Android SDK.

## Закреплённые инструменты

- Eclipse Temurin JDK `17.0.15+6`
- Go `1.24.5`
- protoc `31.1`
- Buf CLI `1.55.1`
- OpenAPI Generator CLI `7.14.0`
- Gradle `8.14.2`
- Local `protoc-gen-go` `v1.36.6` (installed by the pinned Go toolchain; no BSR authentication required)
- Square Wire `6.4.0` for Kotlin Multiplatform Protobuf models

Версии и ожидаемые SHA-256 задаются только в `codegen/versions.psd1`. Скрипты сначала ищут совместимую закреплённую версию в `PATH`, затем скачивают Windows x64 portable-дистрибутив в локальный `schema/.tools`. Каждый скачанный архив или исполняемый файл проверяется по закреплённому SHA-256. `schema/.tools` исключён из Git.

## Команды

Запускать из Windows PowerShell 5.1+:

```powershell
.\schema\codegen\bootstrap.ps1
.\schema\codegen\validate.ps1
.\schema\codegen\generate.ps1
.\schema\codegen\compile.ps1
```

Можно установить только часть инструментов:

```powershell
.\schema\codegen\bootstrap.ps1 -Tool Jdk,Go,Buf
```

Можно генерировать контракты независимо:

```powershell
.\schema\codegen\generate.ps1 -Target Proto
.\schema\codegen\generate.ps1 -Target OpenApi -OpenApiSpec .\schema\openapi\client-api.yaml
```

Если `-OpenApiSpec` не задан, в `schema/openapi` должен находиться ровно один корневой YAML, YML или JSON документ.

## Выходные каталоги

- `gen/go/proto`
- `gen/go/openapi`
- `gen/kotlin/proto`
- `gen/kotlin/openapi`

Генератор очищает соответствующие versioned output-каталоги перед записью, поэтому удалённые модели не остаются в `gen/`. Канонический OpenAPI сохраняет внешние ссылки на JSON Schema. Для обхода ограничения OpenAPI Generator на вложенные внешние `$defs` скрипт создаёт невключаемое в Git codegen-представление, где DocumentV2 передаётся как JSON object; строгая структура остаётся нормативной в `schema/json/` и проверяется отдельно.

## Compile harness

`gen/go/go.mod` компилирует Go Protobuf/OpenAPI models. `gen/kotlin/build.gradle.kts` генерирует Protobuf-модели через Square Wire, подключает Kotlin Multiplatform OpenAPI models и проверяет common metadata + JVM target на JDK 17. Harness не зависит от Android SDK.

`validate.ps1` проверяет JSON Schema 2020-12 и positive/negative fixtures, все OpenAPI `$ref`, наличие `operationId`/runtime phase у 145 Client API операций и выполняет `buf lint`. `compile.ps1` затем компилирует сохранённые Go/Kotlin артефакты.

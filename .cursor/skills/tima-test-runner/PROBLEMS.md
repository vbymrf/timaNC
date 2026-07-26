# TIMA test problems

Use each entry as `symptom → check → cause → fix → verify`. Do not apply a fix
until its stated cause is demonstrated.

## Required integration test reported green but did not run

- Symptom: Go output says `DATABASE_URL is not set`, or HTTP roundtrip is absent.
- Check: inspect command environment and executed test names.
- Cause: integration tags or required E2E variables were omitted.
- Fix: set `DATABASE_URL` and `REDIS_URL` for tagged Go tests; set both
  `TIMA_E2E_BASE_URL` and `TIMA_REQUIRE_HTTP_E2E=true` for the JVM harness.
- Verify: expected integration test names execute and the process exits zero.

## HTTP E2E cannot connect

- Symptom: connection refused, timeout, or readiness failure on port 8080.
- Check: Compose `ps`, `/healthz`, `/readyz`, and port ownership.
- Cause: stack is stopped/unhealthy, a packaged migration is stale, or another
  process owns the port.
- Fix: hand repair to `tima-ops-runner`; rebuild only after Dockerfile,
  migrations, server, or packaged configuration changed.
- Verify: health and ready return success before rerunning E2E once.

## Go integration is skipped or Redis delivery fails

- Symptom: database test skip, Redis connection error, or realtime timeout.
- Check: tagged command, `DATABASE_URL`, `REDIS_URL`, Compose service health.
- Cause: missing environment or unhealthy PostgreSQL/Redis.
- Fix: correct environment; do not substitute unit-test success for integration.
- Verify: `go test -tags=integration ./internal/phase1 ./internal/realtime`.

## Go command is unavailable

- Symptom: server test command raises `CommandNotFoundException`.
- Check: `Get-Command go`, `go version`, and `.github/workflows/server.yml`.
- Cause: Go is missing or absent from PATH.
- Fix: hand installation to `tima-ops-runner`; the current
  `scripts/setup-windows.ps1` does not install Go.
- Verify: supported Go is on PATH and the narrow package command executes.

## Gradle uses the wrong Java or version

- Symptom: toolchain error, unsupported class version, daemon startup failure.
- Check: `Get-Command gradle`, `gradle --version`, `java -version`, `JAVA_HOME`.
- Cause: JDK other than 17, Gradle below 8.14, stale PATH, or temporary install.
- Fix: use persistent setup from `scripts/setup-windows.ps1`; refresh PATH.
- Verify: Gradle reports 8.14+ on JVM 17, then rerun the narrow failed target.

## Gradle output is inconsistent or locked

- Symptom: locked cache/output, missing XML, unstable parallel failure.
- Check: running Gradle/Java processes and other agent jobs.
- Cause: multiple heavy Gradle invocations share one checkout.
- Fix: wait for the active run; serialize heavy tasks. Do not kill a healthy
  build merely to start another.
- Verify: one invocation owns the outputs and emits current JUnit XML.

## Test appears cached when execution was required

- Symptom: HTTP gate is `UP-TO-DATE` or no fresh evidence exists.
- Check: `TIMA_REQUIRE_HTTP_E2E` and report timestamps.
- Cause: ordinary JVM mode was used instead of required E2E mode.
- Fix: set required E2E variables; the harness build disables up-to-date reuse.
- Verify: report timestamp and executed test names belong to this run.

## Worker summary disagrees with test artifacts

- Symptom: Composer's aggregate or per-suite counts differ from JUnit/k6 files.
- Check: parse authoritative artifact totals and timestamps independently.
- Cause: worker parsing or summarization error.
- Fix: report the artifact-derived result and note the corrected discrepancy;
  do not rerun a passing deterministic test solely to change the summary.
- Verify: passed/failed/skipped totals equal the source reports.

## k6 fixture or summary is missing

- Symptom: k6 cannot open fixture, or no summary JSON is produced.
- Check: E2E fixture path, sample count, file existence, and k6 exit code.
- Cause: fixture-producing E2E did not run/pass or paths differ by working
  directory.
- Fix: generate `build/phase1-k6-fixture.json` from required E2E, then run k6
  from repository root.
- Verify: `build/phase1-k6-summary.json` exists and all thresholds pass.

## Generated contract drift

- Symptom: `git diff --exit-code` fails after codegen.
- Check: diff only generated paths and source schema changes.
- Cause: committed generated clients are stale or generation is nondeterministic.
- Fix: regenerate with pinned `schema/.tools`; do not discard legitimate drift.
- Verify: a second generation produces no additional diff and compile passes.

## Android install reports signature mismatch

- Symptom: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- Check: package name and whether an app signed by another key is installed.
- Cause: existing `com.tima.client` signature differs from the new debug build.
- Fix: only with approval when app state may be lost, uninstall the old package,
  then install the intended APK.
- Verify: package installs and launches with `am start`, not `monkey`.

## Android media test fails while API calls pass

- Symptom: presigned MinIO upload/download fails only in the emulator.
- Check: `adb reverse --list` and port 9000 reachability.
- Cause: missing `adb reverse tcp:9000 tcp:9000`.
- Fix: add the reverse mapping for the selected emulator.
- Verify: encrypted media upload and in-app preview complete.

## Native evidence image is unreadable

- Symptom: image validation throws `OutOfMemoryException` or decoder error.
- Check: open every retained PNG with an image decoder before finalizing.
- Cause: truncated or corrupt capture.
- Fix: recapture the same step. Never replace it with a different step merely
  because the screen looks similar; record any evidence limitation honestly.
- Verify: every listed image decodes, matches its manifest step, and contains no
  secret material.

## Acceptance state or commit does not match

- Symptom: platforms use different SHAs, an old FAIL was replaced, or a partial
  journey is labeled PASS.
- Check: manifest SHA, artifact checksum, all steps 1–9, timestamps, reviewer.
- Cause: evidence was collected across incompatible builds or incompletely.
- Fix: preserve old evidence and run a new commit-bound journey.
- Verify: the manifest and artifacts prove all required steps on one final SHA.

## Hosted CI differs from local result

- Symptom: local target passes while one hosted workflow fails.
- Check: exact failing workflow command, OS, JDK/Gradle/Go versions, credentials,
  and uploaded reports.
- Cause: local command did not match CI scope or platform prerequisites.
- Fix: reproduce the workflow's exact target when possible; do not claim iOS
  native parity from Windows.
- Verify: authoritative hosted check is green or remains explicitly BLOCKED.

# TIMA operations problems

Apply only entries whose cause is demonstrated. Format is
`symptom → check → cause → fix → verify`.

## Git reports dubious ownership on the shared checkout

- Symptom: Git refuses operations because `Z:\!MessNC` has dubious ownership.
- Check: confirm the current path is the intended primary checkout.
- Cause: VMware shared-folder ownership cannot be mapped normally.
- Fix: use a command-scoped `git -c safe.directory='Z:/!MessNC' ...` override.
  Never change global Git configuration and never use wildcard trust.
- Verify: the requested read-only Git command succeeds for this checkout only.

## Docker Desktop does not become ready

- Symptom: Docker CLI exists but engine calls fail or hang.
- Check: Docker Desktop process, `docker version`, `docker info`, WSL status,
  virtualization availability, disk, and memory.
- Cause: Desktop is stopped, WSL2 backend is stale, required virtualization is
  unavailable, or the VM is resource-starved.
- Fix: start Desktop; with approval update/restart WSL and Docker. If the VMware
  guest has only two vCPUs, increase its allocation before heavy builds.
- Verify: both client and server versions return, Compose v2 is present, and
  `docker run --rm hello-world` or Compose config succeeds.

## WSL update fails without elevation

- Symptom: `wsl --update` or feature repair returns access/elevation errors.
- Check: `wsl --status`, current user elevation, Windows feature state.
- Cause: the operation requires administrator privileges.
- Fix: request approval, run the exact elevated update (use
  `wsl --update --web-download` when Store delivery is unavailable), then
  `wsl --shutdown` and restart Docker Desktop.
- Verify: WSL status is healthy and Docker engine responds.

## Docker volume archive is invisible from a shared drive

- Symptom: Linux container `tar` cannot open a `.tar.gz` that exists on `Z:`.
- Check: host path resolution and whether Docker Desktop can mount that VMware
  shared directory.
- Cause: the shared drive is not reliably exposed to Docker's Linux VM.
- Fix: copy archives to a local path such as `%LOCALAPPDATA%\Temp`, verify size
  or checksum, and mount that local directory read-only.
- Verify: a disposable container can list/test the archive before any restore.

## Volume names differ from the transfer guide

- Symptom: restore reports that `infra_*` volume is missing or creates a new
  empty volume.
- Check: `docker volume ls` and the actual Compose project name.
- Cause: Compose derived a different project prefix.
- Fix: map each archive to the observed named volume. Restore remains
  destructive and requires approval plus backup.
- Verify: stack health and expected retained data after restart.

## Host tooling verify reports a missing component

- Symptom: `setup-windows.ps1` reports Docker, JDK, Git, gh, Windows SDK,
  Gradle, Android SDK/AVD, or schema tools missing.
- Check: run the matching `Test-*` verifier and inspect PATH.
- Cause: package absent, wrong version, or current process has stale PATH.
- Fix: after approval install only that numbered package, refresh PATH, rerun
  its verifier. Do not install temporary JDK/Gradle under `%TEMP%`.
- Verify: verifier writes a PASS line with the expected persistent path/version.

## Go is unavailable for server tests

- Symptom: `go` raises `CommandNotFoundException` and server tests do not start.
- Check: `Get-Command go`, `go version`, and the version in
  `.github/workflows/server.yml`.
- Cause: Go is absent or not on PATH; `scripts/setup-windows.ps1` currently does
  not install it.
- Fix: request approval, install the CI-pinned Go toolchain persistently, refresh
  PATH, and do not claim that the nine-package setup script covers it.
- Verify: `go version` matches the supported CI line and a narrow package test
  executes.

## Android SDK is partial

- Symptom: `sdkmanager`, platform-tools, emulator, platform, or build-tools is
  missing; package installation previously returned failure.
- Check: SDK directories and `sdkmanager --list`.
- Cause: interrupted download/license acceptance or partial setup.
- Fix: after approval rerun package 7, accept licenses, then install only missing
  packages; rerun package 8 for the system image/AVD.
- Verify: `adb version`, `emulator -list-avds`, and Android build all succeed.

## AVD is slow or unreliable on Z:

- Symptom: emulator boot stalls, snapshots corrupt, or disk I/O is excessive.
- Check: AVD `path` in `%USERPROFILE%\.android\avd\*.ini`.
- Cause: active AVD is on VMware shared storage.
- Fix: stop the emulator with approval and restore/create the AVD on local C:.
  Remove unused `build/android-avd` only after confirming it is not active.
- Verify: AVD path is local and one emulator reaches boot completed.

## More than one Android device is present

- Symptom: ADB says more than one device/emulator, or actions hit the wrong peer.
- Check: `adb devices -l`.
- Cause: a second/stale emulator is running.
- Fix: add `-s emulator-5554` to every command. Stop a stale second peer only
  after approval.
- Verify: package, reverse mapping, and activity belong to the intended serial.

## Android app signature mismatch

- Symptom: install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- Check: installed package and signing identity of the intended build.
- Cause: existing `com.tima.client` was signed with another key.
- Fix: warn that app session/cache will be lost, obtain approval, uninstall the
  old package, install the intended APK.
- Verify: explicit activity starts and expected state is visible.

## Android UI automation taps or scrolls the wrong control

- Symptom: ADB tap/swipe has no effect or selects a different element.
- Check: fresh `uiautomator dump`, bounds, orientation, resolution, and current
  screen state.
- Cause: coordinates were reused after layout/state changed.
- Fix: derive coordinates from the current XML. Prefer resource IDs/text and use
  manual clicks when coordinate automation is not reliable.
- Verify: dump the UI again and confirm the intended state transition.

## Android media cannot reach MinIO

- Symptom: messaging works but encrypted media upload/preview fails.
- Check: API URL and `adb reverse --list`.
- Cause: emulator uses the wrong host address or lacks port 9000 reverse.
- Fix: API must be `10.0.2.2:8080`; add `reverse tcp:9000 tcp:9000`.
- Verify: all three media variants send and in-app decrypt/preview succeeds.

## Tima window cannot be found

- Symptom: process exists but `FindWindow("Tima")` returns no handle.
- Check: enumerate visible top-level windows and their owning process IDs.
- Cause: timing, title matching, hidden characters, or a background helper
  process was selected.
- Fix: use `EnumWindows`, exact visible title, and
  `GetWindowThreadProcessId`; wait for UI startup.
- Verify: resolved handle has a valid rectangle and belongs to visible Tima.

## Multiple Tima.exe processes or wrong activation target

- Symptom: input goes nowhere or `AppActivate` targets the wrong PID.
- Check: CIM process list plus visible-window owner PID.
- Cause: jpackage launcher/helper or stale instances coexist.
- Fix: identify the visible owner. Stop stale processes only with approval;
  never kill all processes blindly during evidence collection.
- Verify: one intended visible window receives focus and action.

## Windows clicks or SendKeys do not reach Tima

- Symptom: scripted send/edit/delete produces no state change.
- Check: foreground window, DPI scaling, window rectangle, and screenshot before
  and after.
- Cause: `AppActivate` alone did not grant foreground focus or coordinates were
  not DPI-correct.
- Fix: bring the verified handle to top, activate it, click the title bar, then
  use coordinates relative to the live window. Prefer manual clicks for
  acceptance when focus remains unreliable.
- Verify: UI and peer state both show the requested operation.

## Copy-link payload leaves the clipboard empty

- Symptom: clipboard has no Windows link payload.
- Check: visible Tima focus and whether the copy mnemonic/action fired.
- Cause: the window was not active.
- Fix: activate the verified window and invoke its copy action/mnemonic; read
  clipboard only after the action completes.
- Verify: payload format is valid in memory. Never print or retain it in logs.

## PowerShell ToHexString is unavailable

- Symptom: `[Convert]::ToHexString` raises `MethodNotFound`.
- Check: PowerShell/.NET runtime version.
- Cause: Windows PowerShell 5.1 lacks the newer API.
- Fix: use
  `[BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()`.
- Verify: output is lowercase hex of expected length without logging secrets.

## PowerShell HttpClientHandler type is missing

- Symptom: a local fault proxy reports `TypeNotFound` for
  `System.Net.Http.HttpClientHandler`.
- Check: whether `System.Net.Http` was loaded.
- Cause: Windows PowerShell did not auto-load the assembly.
- Fix: add `Add-Type -AssemblyName System.Net.Http` before constructing types.
- Verify: proxy emits its ready sentinel and forwards a harmless health request.

## Elevated helper exits without useful diagnostics

- Symptom: helper returns an opaque negative code, often after UAC cancellation
  or parsing failure.
- Check: whether elevation was accepted and capture stdout/stderr to a local
  diagnostic file that contains no secrets.
- Cause: cancelled UAC or an error hidden by the elevated process boundary.
- Fix: request explicit approval; run a minimal wrapper and inspect captured
  diagnostics. Fall back to manual UI action instead of repeated blind retries.
- Verify: helper starts cleanly or the manual action is independently observed.

## Windows session becomes UNAUTHORIZED

- Symptom: sends or refresh calls return 401 after a short lifetime.
- Check: access/refresh timing and `/v1/auth/refresh` result without exposing
  tokens.
- Cause: expired session or a client refresh-path defect.
- Fix: a fresh acceptance relink can restore a test session, but it is only a
  mitigation and must not be reported as fixing token refresh. Record the
  underlying defect if reproducible.
- Verify: fresh session sends successfully and the refresh defect remains
  accurately classified.

## Forced outbox fault does not occur at the durable boundary

- Symptom: reservation fails too early or the message sends normally.
- Check: proxy route, readiness, and whether exactly the serialized message POST
  is aborted after reservation/encryption.
- Cause: wrong endpoint interception or proxy not loaded.
- Fix: use a one-shot loopback proxy, fail only
  `POST /v1/chats/<id>/messages`, then return to the normal endpoint for restart.
- Verify: durable row reaches ERROR, restart retries the same message to SENT,
  and the peer receives it. Remove the proxy and task-only environment afterward.

## Evidence screenshot is corrupt

- Symptom: image decoder throws `OutOfMemoryException` or cannot open a PNG.
- Check: decode every file and compare it to the manifest step.
- Cause: capture was truncated/corrupted.
- Fix: recapture the same state. Do not substitute another step's image.
- Verify: all evidence decodes, corresponds to its step, and is secret-free.

## Secrets appear in logs or evidence

- Symptom: output includes token, OTP, QR/link payload, key material, DPAPI
  bytes, credentials, or presigned URL.
- Check: stop further output and identify every retained copy.
- Cause: verbose command/UI capture crossed a secret boundary.
- Fix: do not repeat the value. Remove unsafe transient output from proposed
  evidence and recapture a redacted state; do not modify committed history
  without explicit incident handling.
- Verify: retained reports prove behavior without secret material.

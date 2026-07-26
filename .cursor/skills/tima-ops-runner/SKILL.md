---
name: tima-ops-runner
description: Installs, verifies, starts, diagnoses, and safely repairs the TIMA Windows development environment, including Docker Desktop and Compose, WSL2 backend, JDK 17, Gradle, Windows SDK, Android SDK/AVD/ADB, schema tools, and Windows peer processes. Use automatically for environment setup, service failures, tooling installation, emulator problems, machine transfer, or local runtime troubleshooting.
---

# TIMA operations runner

Use Composer 2.5 Fast as the operations worker. The main model defines the
boundary, grants only approved actions, and verifies the result.

## Required workflow

1. Read [REFERENCE.md](REFERENCE.md). For any known symptom or failed command,
   read [PROBLEMS.md](PROBLEMS.md) before inventing a new workaround.
2. Inspect state first. Never start a second Compose stack, emulator, or visible
   Tima process.
3. Classify actions:
   - safe and reversible: execute automatically within the user's requested
     scope
   - UAC, installation, data mutation, process termination, migration, restore,
     or external side effect: obtain explicit approval first
   - forbidden actions below: do not execute
4. Delegate work to one local shell subagent with:
   - `model="composer-2.5-fast"`
   - `subagent_type="shell"`
   - the exact allowed actions and stop conditions
   - the result contract below
5. Use the loop `inspect → diagnose → safe fix → verify`. Allow at most two
   evidence-based fix attempts. Stop on an authorization prompt, destructive
   requirement, unknown root cause, or repeated failure.
6. The main model independently checks the final health probe, version, process
   state, device list, or other authoritative evidence before reporting success.

## Safe automatic actions

- Read versions, process/service/device state, disk space, Compose config/ps,
  health endpoints, CI status, and Git status.
- Create `infra/.env` from `infra/.env.example` only when it is absent.
- Start an already installed Docker Desktop or the requested development stack
  when no duplicate exists.
- Add the required ADB reverse mapping and launch an existing AVD when none is
  running.
- Run non-destructive verification, build, contract, and health commands.
- Refresh the current process PATH after an approved installation.

The user's direct request to start or verify an existing service is sufficient
authorization for these reversible actions.

## Approval required

Ask before:

- `winget`, SDK/tool downloads, Android license acceptance, UAC, Windows feature
  or WSL changes
- uninstalling an app, stopping/killing a process, replacing an AVD, or changing
  persistent user/machine environment variables
- database migrations, Compose rebuilds that replace runtime components, volume
  restore, or any operation that may alter retained acceptance state
- deleting local files/state, changing firewall/network rules, Git push, or
  triggering credentialed/external workflows

Explain the effect and retained-state risk in one sentence before asking.

## Forbidden actions

- Never run `docker compose down -v` or `down --volumes` during acceptance.
- Never wipe/overwrite Docker volumes or an AVD without explicit destructive
  approval and a verified backup.
- Never use `emulator -wipe-data` when session/cache evidence matters.
- Never place an active AVD on VMware shared `Z:`.
- Never use `adb shell monkey` to launch TIMA; use the explicit activity.
- Always invoke the development Compose project with
  `--env-file infra/.env -f infra/docker-compose.dev.yml`.
- Never update global Git configuration to bypass repository safety.
- Never overwrite an old FAIL acceptance manifest or fabricate substitute
  evidence.
- Never log or retain tokens, OTP, QR payloads/secrets, private keys, DPAPI
  material, media/escrow keys, credentials, or presigned URLs.

## Minimal worker prompt

Send only repository path, requested target state, observed state, allowed
actions, explicit prohibitions, relevant problem entries, verification command,
and this contract:

```text
status: PASS | FAIL | BLOCKED
state: <important versions/services/devices>
root_cause: <one demonstrated cause or unknown>
actions:
- <short command effect; no secrets>
verification:
- <authoritative check and result>
changes: <runtime, installed component, file, or none>
user_action: <none or one precise approval/manual action>
```

Do not include full logs. Include at most three short diagnostic lines when
needed.

## Installation authority

Reuse `scripts/setup-windows.ps1` and its verification functions. Do not create
a second installer. Install one missing package at a time after approval, then
verify it before continuing. Persistent host requirements are Docker Desktop
with Compose v2, JDK 17, Go matching the current CI version, Git, GitHub CLI,
Windows SDK, Gradle 8.14+, Android SDK, AVD `Tima_API_34`, and pinned schema
tools. The current setup script does not install Go; diagnose it separately and
request approval before installing it.

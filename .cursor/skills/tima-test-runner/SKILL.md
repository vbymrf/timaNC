---
name: tima-test-runner
description: Runs and diagnoses TIMA tests, CI-parity checks, build gates, HTTP/WebSocket E2E, k6 smoke tests, platform validation, and native acceptance. Use automatically whenever tests, verification, CI failures, test counts, build validation, or acceptance results are requested.
---

# TIMA test runner

Use Composer 2.5 Fast as the execution worker. The main model remains the
orchestrator and verifier.

## Required workflow

1. Read [REFERENCE.md](REFERENCE.md). Read [PROBLEMS.md](PROBLEMS.md) when a
   command fails, skips unexpectedly, or produces inconsistent evidence.
2. Select the smallest test tier that proves the requested claim. Do not expand
   into platform packaging, load tests, or native acceptance unless requested
   or required by the changed surface.
3. Inspect current processes before any tier that needs Docker, an emulator, or
   Tima. Never start a duplicate stack, emulator, or Windows peer.
4. Delegate execution to one local shell subagent with:
   - `model="composer-2.5-fast"`
   - `subagent_type="shell"`
   - a precise working directory, commands, timeout expectations, constraints,
     and the result contract below
5. Independent commands may use separate Composer workers only when they do not
   share Gradle outputs, ports, Docker state, emulator state, or acceptance
   evidence. Never run multiple heavy Gradle invocations concurrently.
6. The main model checks the worker's exit codes and at least one authoritative
   artifact: JUnit XML, k6 summary JSON, health/ready response, Go test result,
   package checksum, or native manifest. When fresh execution is required,
   compare report timestamps and reject stale or merely cached evidence.
   Recompute aggregate counts from the artifact; correct any worker summary
   that disagrees with the source report.
7. Return the verified concise result. Include raw log excerpts only when they
   are necessary to identify a failure.

## Minimal worker prompt

Send only:

- repository path and requested scope
- chosen tier and exact commands
- known current state relevant to the commands
- safety constraints from this skill
- authoritative report paths
- the result contract

Do not send the full conversation or unrelated source code.

## Result contract

Require exactly this compact structure:

```text
status: PASS | FAIL | BLOCKED
scope: <tier and targets>
counts: <passed/failed/skipped or unknown>
failures:
- <at most 3: test or command | cause | useful location>
evidence:
- <exit code, report path, health result, or artifact>
changes: <none, or generated/runtime state only>
next: <none, one fix, or one required user action>
```

The worker must not paste full logs. It may summarize up to three root failures
and cite paths or short error lines.

## Failure policy

- A skipped integration or HTTP E2E test is not a pass when that gate was
  required. Confirm required environment variables were set.
- On infrastructure failure, consult [PROBLEMS.md](PROBLEMS.md), perform only
  safe diagnostics, and hand environment repair to `tima-ops-runner`.
- On a test assertion or compile failure, diagnose it but do not edit product
  code unless the user also requested a fix.
- Retry once only for a demonstrated transient failure. Never retry deterministic
  failures merely to obtain green output.
- Do not overwrite old FAIL acceptance manifests. A PASS manifest requires all
  native steps 1–9 on the same final commit.

## Invariants

- Work from the repository root, switching only to the documented command
  directory.
- Gradle 8.14+ and JDK 17 are required. Do not use temporary toolchains from
  `%TEMP%`.
- Do not run `docker compose down -v` or `down --volumes` during acceptance.
- Do not use `emulator -wipe-data` when session/cache evidence matters.
- Never log access/refresh tokens, OTP, QR payloads or secrets, private keys,
  DPAPI material, media keys, escrow material, or presigned URLs.
- Do not modify `infra/.env`, credentials, application state, or evidence unless
  the selected gate explicitly requires it and the user authorized that scope.

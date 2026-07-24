# Android native acceptance — partial run

```text
commit_sha: 2ceaefe57a40ee1bd8f72888122e50de074f0ca3
platform: Android
os_version: Android 14
device_or_simulator: google sdk_gphone64_x86_64 API 34 emulator
artifact_name: android-debug.apk (explicit local development build)
artifact_sha256: df995b9a33b6017f88fee27d515fd51f7b719bb19712ef60059b05adda1d3983
build_run_url: https://github.com/vbymrf/timaNC/actions/runs/30124957450
journey_started_at_utc: 2026-07-24T20:31:00Z
journey_finished_at_utc: 2026-07-24T20:44:30Z
steps_1_to_9: FAIL
reviewer: pending
```

This is retained failure/partial evidence and must not be promoted to a passing
Phase 1 manifest.

| Step | Result | Local observation |
|---|---|---|
| 1 | PASS | Login persisted a protected refresh token; application update/restart rotated it through `/v1/auth/refresh` and restored the same user/device session. |
| 2 | PASS | Existing private chat list and history loaded; a new private chat had also been created earlier in the same emulator journey. |
| 3 | PARTIAL | Encrypted text send and edit reached `SENT`; a second native peer receive/decrypt was not executed. |
| 4 | NOT RUN | Durable outbox recovery is covered by JVM tests, but the required native forced-failure journey was not executed. |
| 5 | PARTIAL | Author edit passed. Native peer mark-read and author/peer delete restrictions were not executed end to end. |
| 6 | PASS | Three independently encrypted variants uploaded; PostgreSQL retained exactly three ready private variants and the media-only message was sent. |
| 7 | PARTIAL | The app rendered the thumbnail and downloaded/decrypted the in-app preview. A second native peer did not perform the receive/decrypt side. |
| 8 | PASS | With emulator networking disabled, restart retained the signed-in identity, chat and encrypted media message cache without claiming background delivery. |
| 9 | PASS | Offline logout followed by restart showed signed-out state, no messages and no image upload/preview. |

Evidence files:

- `step-07-decrypted-preview.png` — synthetic test image decrypted inside the app.
- `step-09-logout-restart.png` — signed-out restart with no retained message or
  media content.
- Hosted package/unit evidence: [client validation run
  30124957450](https://github.com/vbymrf/timaNC/actions/runs/30124957450), green
  for Android AAB, iOS simulator/XCFramework and Windows MSIX.

No access/refresh token, OTP, device seed, media key, escrow material or
presigned URL is retained in this evidence.

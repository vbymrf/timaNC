# Android native acceptance — passing run

```text
commit_sha: f17548e2a2eed98768b2515c6376f767b4706540
platform: Android
os_version: Android 14
device_or_simulator: google sdk_gphone64_x86_64 API 34 emulator
artifact_name: android-debug.apk (explicit local development build)
artifact_sha256: 712b8b6dae898d324dc3e09827825d7f068c92d105d00746acc82991eeeafb81
build_run_url: https://github.com/vbymrf/timaNC/actions/runs/30150128033
journey_started_at_utc: 2026-07-25T08:22:16Z
journey_finished_at_utc: 2026-07-25T08:42:30Z
steps_1_to_9: PASS
reviewer: Evgenii (local acceptance operator)
```

| Step | Result | Local observation |
|---|---|---|
| 1 | PASS | A protected session for the same user and device was restored after force-stop and restart. |
| 2 | PASS | The existing private chat and encrypted history opened; Android received and decrypted the Windows peer text `clean-peer-f17548e`. |
| 3 | PASS | Android sent `clean-f17548e`; the Windows native peer received it, then returned the peer text shown in Android. |
| 4 | PASS | The debug-only post-encryption failure left message 12 in `ERROR`; process restart recovered the durable outbox and sent the same message 12. |
| 5 | PASS | Android edited its message to `clean-f17548ev2`, marked peer message 11 read, deleted its own message 12, and exposed no peer edit/delete actions. |
| 6 | PASS | Android selected one image and sent media-only message 13. Server inspection showed one ready media object with exactly `thumbnail`, `preview`, and `full` ciphertext variants, each with a 32-byte checksum. |
| 7 | PASS | The Windows native peer rendered the decrypted thumbnail and opened the decrypted in-app preview; no external export/open was used. |
| 8 | PASS | With emulator networking disabled, restart retained the signed-in identity, chat, edited text, peer text, and encrypted image message from the durable cache while network operations reported failure. |
| 9 | PASS | Offline logout followed by restart showed signed-out state, no messages, no image upload, and disabled messaging controls. |

Evidence files:

- `step-01-session-restore.png`
- `step-03-peer-roundtrip.png`
- `step-04-forced-failure.png`
- `step-04-restart-recovery.png`
- `step-05-edit-read-delete-restrictions.png`
- `step-06-media-upload.png`
- `step-07-windows-peer-preview.png`
- `step-08-offline-cache.png`
- `step-09-logout-wipe.png`

Hosted package/unit evidence: [client platform validation run
30150128033](https://github.com/vbymrf/timaNC/actions/runs/30150128033), green
for Android AAB, iOS simulator/XCFramework and Windows MSIX.

No access/refresh token, OTP, device seed, media key, escrow material or
presigned URL is retained in this evidence.

# Windows native acceptance — passing run

```text
commit_sha: f17548e2a2eed98768b2515c6376f767b4706540
platform: Windows
os_version: Windows 10 22H2 (10.0.19045)
device_or_simulator: VMware Windows guest, 8 logical processors, 16 GiB RAM
artifact_name: Tima.exe (Windows app-image development build)
artifact_sha256: 27bdc56fbb8fe89144bd28a3ca60f942c2265a61c64c74d4a4d295976e4a12e0
build_run_url: https://github.com/vbymrf/timaNC/actions/runs/30150128033
journey_started_at_utc: 2026-07-26T13:51:00Z
journey_finished_at_utc: 2026-07-26T15:08:00Z
steps_1_to_9: PASS
reviewer: Evgenii (local acceptance operator)
```

| Step | Result | Local observation |
|---|---|---|
| 1 | PASS | The DPAPI-protected linked session was restored after terminating and restarting the Windows process. |
| 2 | PASS | The existing private chat and encrypted history opened; Windows decrypted the Android peer text. |
| 3 | PASS | Windows sent `windows-f17548e-step3f`; the Android peer received and decrypted it. |
| 4 | PASS | A local one-shot fault proxy allowed reservation and encryption, then aborted the first serialized message POST. The durable row changed to `ERROR`; after process restart against the normal endpoint, the same local message changed to `SENT` and the Android peer received it. |
| 5 | PASS | Windows edited its message to `windows-f17548e-step3d-edited`, marked the peer message read, deleted its own `windows-f17548e-step3f`, and exposed no peer edit/delete actions. |
| 6 | PASS | Windows selected one image, completed all three encrypted variants (`SENT_TO_OUTBOX: 3/3`), rendered the thumbnail, and sent the media-only message. |
| 7 | PASS | The Android peer rendered the decrypted image and opened the decrypted in-app preview; no external export/open was used. |
| 8 | PASS | After restart against an unreachable loopback endpoint, Windows retained its linked identity, chat, edited text, peer text, and encrypted image in the durable cache while reporting `CLIENT_OPERATION_FAILED`. |
| 9 | PASS | Offline logout followed by restart showed `Not linked`, empty chat/thread state, no image upload, and disabled messaging controls. |

Evidence files:

- `step-01-session-restore.png`
- `step-02-chat-history.png`
- `step-03-peer-roundtrip.png`
- `step-04-forced-failure.png`
- `step-04-restart-recovery.png`
- `step-05-edit-read-delete-restrictions.png`
- `step-06-media-upload.png`
- `step-07-peer-preview.png`
- `step-08-offline-cache.png`
- `step-09-logout-wipe.png`

Hosted package/unit evidence: [client platform validation run
30150128033](https://github.com/vbymrf/timaNC/actions/runs/30150128033), green
for Android AAB, iOS simulator/XCFramework and Windows MSIX.

No access/refresh token, OTP, QR secret, device seed, media key, escrow material
or presigned URL is retained in this evidence.

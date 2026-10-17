# Verification record and phone test plan

## Verified in the build environment

- Android source compiled against Android SDK 35 with JDK 17.
- APK resources linked by Android SDK `aapt2`, Java classes converted to DEX by `d8`.
- APK aligned and debug-signed by the official Android SDK tools.
- APK Signature Scheme v2 and v3 verification passed.
- Manifest inspected: package `in.proxyproof.app.demo`, version 1.0.0, minimum API 26, target API 35, expected launcher activity and permissions.
- 22 backend tests passed: check-in/idempotency; expired/invalid beacon; weak/invalid/insufficient RSSI; wrong key; nonce replay; closed/expired session; key uniqueness; admin-approved replacement; manual removal; manual addition/percentage; session ownership; enrolment snapshot; CSV escaping; role restrictions; logout revocation; token rotation overlap; login throttling; real HTTP health/login requests.
- Actual unittest output is in `backend-test-results.txt`.

The tests used Python 3.12 and cryptography 50.0.2. No attendance data from real students was used.

## Build distinction

The included APK was built with `tools/build_demo.py` using the official SDK command-line tools. Gradle dependency resolution was blocked in this environment, so **the Gradle APK build and lint job were not completed here**. The Gradle wrapper was generated successfully. Android Studio and GitHub Actions configurations are included, but their full runs must be verified on an environment that can resolve Google's Maven repository.

To reproduce the verified direct build after installing JDK 17, Android SDK platform 35, and build-tools 35.0.0:

```bash
# Set JAVA_HOME and ANDROID_HOME to your installations first.
python tools/build_demo.py
```

This uses no third-party Android app dependencies. The generated debug signing key lives in ignored `build/direct/`; keep it locally if you need to reinstall updates without changing the signature. APKs built in another environment may have a different debug signer.

## Not verified here

There was no attached Android phone or emulator. Installation, on-device rendering, runtime permission prompts, live BLE scan/advertise behaviour, device-specific RSSI, and physical two-phone end-to-end attendance are **not yet hardware-tested**. Do not claim those tests passed until you run them. This is a foreground prototype with known anti-relay and app-installation binding limitations, not a production-certified attendance system.

## Required two-phone checklist

| Test | Expected result | Record your result |
|---|---|---|
| Install APK on two supported phones | App opens, no crash | Pending |
| Open `/health` on both phones | Server JSON returned | Pending |
| Sign in with organiser and student | Correct role screens | Pending |
| Deny Bluetooth permission | Useful explanation; no crash | Pending |
| Grant permissions; start 5-minute session | Organiser shows broadcast status | Pending |
| Student within 1–2m, app visible | Automatic confirmed mark | Pending |
| Keep scanning after success | Single attendance record | Pending |
| Close session before student submits | Server rejects late attendance | Pending |
| Move farther away / behind a wall | Measure RSSI behaviour; calibrate rather than assuming room boundary | Pending |
| Turn off network | No false successful mark; useful error | Pending |
| Turn Bluetooth off/on | Broadcast/scan can resume | Pending |
| Leave app and return | BLE pauses, then resumes while session is active | Pending |
| Start a second completed session | History and denominator correct | Pending |
| Manual remove then remain nearby | Explicit absence persists | Pending |
| Export CSV | Valid names/status/source/reason | Pending |
| Sign student in on replacement device | Blocked until admin approval | Pending |
| Approve replacement | New login works; old token rejected | Pending |

For your demo evidence, record phone models, Android versions, threshold, five-sample median, distance/room position, network arrangement, and results. Use test accounts rather than real student records.

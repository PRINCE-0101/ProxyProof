# ProxyProof — Bluetooth Attendance

An Android attendance prototype for the app-development recruitment brief. An organiser broadcasts a changing BLE token; nearby students check in automatically through an authenticated API. Includes the Android app, Python backend, SQLite persistence, tests, and GitHub Actions build.

**Start here:** [Setup and submission guide](docs/START_HERE.md). **Understand the design:** [Architecture](docs/ARCHITECTURE.md). **Verification and remaining limits:** [Testing](docs/TESTING.md).

## What is implemented

| Requirement | Implementation |
|---|---|
| Organiser/student login | Provisioned accounts; server-enforced roles; separate admin role |
| One device per student | Android Keystore P-256 key bound on first login; administrator reviews changes |
| Fixed-duration sessions | 30–3600 seconds at API; minutes in app; server-enforced end time |
| Automatic nearby check-in | Foreground BLE scan, five RSSI samples, median threshold, signed submission |
| Rotating token | Server-minted 8-byte random token, normally rotates every 8 seconds, expires after 12 seconds |
| Replay resistance | Fresh beacon checks, signed request timestamp/nonce, idempotent attendance |
| Dashboard | Four-second polling, roster and count, manual add/remove with reason, session close |
| Student history | Present/absent and percentage over completed enrolled sessions |
| Export | CSV via Android's document picker |
| Device changes | Student request; admin approves/rejects; approval revokes existing logins |

This is a working **foreground prototype**, not a production anti-fraud guarantee. RSSI is noisy and client-reported. A live relay or modified/rooted client is not defeated by rotating tokens alone. The Keystore binding represents an app installation, not a guaranteed unique physical handset. See the threat model before presenting claims.

## Stack

- Native Android: Java, Android framework UI, BLE scanner/advertiser, Android Keystore. Android 8.0+ (API 26), compile/target API 35.
- API: Python 3.10+, standard-library HTTP server, SQLite, `cryptography` for ECDSA verification.
- Build: JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3.
- No Firebase account, paid API, or hardcoded login credentials.

## Quick start

```bash
cd backend
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
python -m pip install -r requirements.txt
python server.py init
python server.py add-user teacher "Demo Organiser" organiser
python server.py add-user student01 "Demo Student" student
python server.py add-user admin "Device Administrator" admin
python server.py serve
```

Each account command prompts for a password of at least 10 characters. Provision students **before** starting a session: the class roster is snapshotted at session creation.

Install the APK on two real Android phones. Put the laptop and phones on the same trusted network, enter `http://YOUR_LAPTOP_LAN_IP:8000` in both apps, and sign in with the two different roles. `localhost` on a phone refers to the phone, not the laptop. Keep both apps visible. The organiser phone must support BLE advertising.

## Build and test

```bash
cd backend
python -m unittest -v
cd ../android
./gradlew assembleDebug
# Windows: gradlew.bat assembleDebug
```

Gradle output: `android/app/build/outputs/apk/debug/app-debug.apk`.
The demo/debug build permits HTTP for LAN testing. Release configuration requires HTTPS. The downloadable APK is a signed **demo build**, not a Play Store release.

## Repository layout

```text
android/                 Native Android project and Gradle wrapper
backend/server.py        API, database schema, provisioning CLI
backend/test_server.py   Backend security and attendance tests
docs/                    Setup, architecture, testing and presentation guide
.github/workflows/       APK build and backend checks
releases/                Compiled demo APK, when included in the submission bundle
```

## Submission

Upload the **extracted repository contents**, including `README.md`, `android`, `backend`, `.github`, and `docs`, to GitHub. Do not upload only the ZIP. Attach the APK to a GitHub Release, or retain the small supplied APK under `releases/`. Include a short two-phone demonstration video after conducting the physical test checklist. The repository is prepared locally; it has not been published to a GitHub account.

Be ready to explain the code and any assistance used according to your club's submission rules. Add your own observed BLE measurements and actual demo evidence; do not present unperformed hardware tests as completed.

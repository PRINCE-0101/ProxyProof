# How ProxyProof works

## End-to-end flow

1. An administrator provisions a student account. Passwords are stored using salted scrypt; raw passwords are not stored.
2. The Android app creates an EC P-256 key inside Android Keystore. The API stores the public key on the first successful student login. The private key stays on the device.
3. The organiser creates a timed session. The server copies the currently provisioned student IDs into a session roster.
4. The organiser requests a fresh beacon. The server issues 8 random bytes with a 12-second lifetime, normally rotated on the 8-second refresh. A brief overlap reduces edge-of-rotation failures.
5. The phone advertises manufacturer data: 1 version byte, 4 session-ID bytes (big endian), and 8 token bytes. With manufacturer-field overhead and advertising flags this fits the legacy 31-byte BLE advertising budget. No pairing or connection is required.
6. A student's foreground scanner filters this payload, accumulates five RSSI samples for that exact token, and submits them over the API.
7. The student signs `METHOD + newline + PATH + newline + UNIX_TIME + newline + NONCE + newline + SHA256(BODY)` using SHA256withECDSA. Public keys use DER SubjectPublicKeyInfo; signatures use DER and Base64 on the wire.
8. The server authenticates the bearer token, verifies the device signature and timestamp, rejects consumed nonces, checks the session and enrolment, validates the beacon expiry, and compares the median RSSI against the session threshold.
9. Attendance is written once per `(session, student)`. The dashboard polls every four seconds. Manual overrides include a reason and audit entry.

## Design choices

- **Server authority:** phone clocks cannot extend a session/token lifetime. Phone clock accuracy is still needed for request freshness.
- **Median RSSI:** reduces sensitivity to a single unusually strong/weak sample; it does not make RSSI a trusted distance sensor.
- **Device key uniqueness:** the database prevents one enrolled public key from belonging to two student IDs. Rebinding needs an admin.
- **Roster snapshot:** newly created accounts do not retroactively change historical percentages. This demo has one shared class; multi-course enrolment is future work.
- **Attendance denominator:** only closed/expired sessions in the student's roster count. Active sessions are displayed separately.
- **Manual removal:** persisted as an explicit absent override, so ongoing scanning cannot immediately undo an organiser's correction.
- **Lifecycle:** app keeps the screen awake while visible; scan/advertisement stop on pause. On returning, scanning resumes and an open organiser session resumes. After process restart, log in and reopen it from History.
- **CSV safety:** values starting with spreadsheet formula characters are escaped.
- **Transport:** debug APK allows LAN HTTP; release manifest blocks it. Use HTTPS outside a trusted local demo network.

## Security boundaries

| Attack | What this build does | Remaining limit |
|---|---|---|
| Forward an old code | Server rejects expired beacon | A live forwarding/relay attack inside the freshness window remains possible |
| Repeat an intercepted signed request | Timestamp + unique nonce + idempotent row | Secure transport is still required; do not expose the HTTP demo publicly |
| Use same installation for another student | Unique public-key binding | Reinstall/multiple profiles/rooting can create separate installation identities |
| Log in on a replacement phone | Attendance rejected until admin approves its key | First enrolment relies on account credentials and operator provisioning |
| Check in from far away | Median RSSI threshold on five samples | RSSI is self-reported by client; a modified client can forge measurements |
| Access another organiser's session | Server ownership checks; admins can review | Admin compromise remains powerful |
| Modify attendance invisibly | Server stores manual source, reason, actor audit | No audit review UI; database operator is trusted |

This is **proxy-resistant**, not proxy-proof in the cryptographic proximity sense. Stronger proximity assurance needs independent trusted measurements, hardware-backed attestation, and/or distance-bounding approaches. Rotating codes alone cannot solve relay attacks.

`0xFFFF` is used as a development manufacturer identifier. Obtain a suitable assigned identifier or redesign the advertisement format before distributing a commercial BLE product.

## API overview

| Method/path | Access | Purpose |
|---|---|---|
| GET /health | Public | Reachability |
| POST /login | Public, rate-limited | Credentials and first-device enrolment |
| POST /logout | Authenticated | Revoke current token |
| GET /sessions | Authenticated; signed for students | Role-scoped sessions/history |
| POST /sessions | Organiser/admin | Start a timed session |
| POST /sessions/{id}/beacon | Owner | Issue/reuse current rotating beacon |
| POST /attendance | Student, device-signed | Validate BLE check-in |
| GET /sessions/{id} | Owner/admin | Dashboard roster |
| POST /sessions/{id}/manual | Owner/admin | Correct attendance with reason |
| POST /sessions/{id}/close | Owner/admin | Stop acceptance immediately |
| GET /sessions/{id}/csv | Owner/admin | Export roster |
| POST /device/request | Student, signed by proposed key | Ask to replace device |
| GET /device/requests | Admin | Review pending requests |
| POST /device/approve | Admin | Approve/reject; approval revokes student's tokens |

All student read/write requests except login/logout must be signed; the proposed key signs device-change requests. Authentication tokens last 12 hours. Signature timestamps allow ±30 seconds. Failed operations do not partially update attendance.

## Source references

- Android BLE permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- BLE scanning: https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices
- BLE advertiser API: https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser
- Android Keystore: https://developer.android.com/privacy-and-security/keystore

The permission implementation includes Android 12+ Nearby devices permissions and location permission because the app infers physical proximity. No `neverForLocation` assertion is used.

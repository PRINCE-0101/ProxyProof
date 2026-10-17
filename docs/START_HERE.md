# Run it and submit it

## 1. What you need

- A Windows/macOS/Linux laptop with Python 3.10 or newer.
- Two Android 8+ phones. One must support BLE advertising; the app reports if it cannot.
- A shared Wi-Fi network with client isolation disabled, or a hotspot allowing phones to reach the laptop.
- The included APK. Android Studio is only needed if you want to edit/rebuild.

The APK is the phone client. It **needs the backend running** to authenticate and store attendance. There is no hidden hosted backend and no fake successful check-in mode.

## 2. Start the backend (Windows example)

Extract the project. In its `backend` folder, open PowerShell or Command Prompt:

```powershell
py -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python server.py init
.venv\Scripts\python server.py add-user teacher "Demo Organiser" organiser
.venv\Scripts\python server.py add-user student01 "Demo Student" student
.venv\Scripts\python server.py add-user admin "Device Administrator" admin
.venv\Scripts\python server.py serve --host 0.0.0.0 --port 8000
```

Choose and remember your own passwords. Input is intentionally invisible at the password prompt. Leave the server terminal running. Use `ipconfig` in a second terminal to find your laptop's Wi-Fi IPv4 address, such as `192.168.1.25`. The server URL would then be `http://192.168.1.25:8000`.

On macOS/Linux, replace `py` with `python3` and `.venv\Scripts\python` with `.venv/bin/python`.

Allow inbound TCP 8000 on the laptop's **private/trusted** network firewall when prompted. Do not expose this development server to the public internet. From each phone's browser, open `http://YOUR_IP:8000/health`; it should return a small JSON status.

## 3. Install and check in

1. Transfer `releases/ProxyProof-demo.apk` to both phones and open it. Allow installation from the file manager if Android asks.
2. Enable Bluetooth. Also enable location services on the phones, especially on Android 8–11.
3. Open ProxyProof and enter the same backend URL on both devices.
4. Sign in as `teacher` on the organiser phone and `student01` on the student phone.
5. Grant Nearby devices and precise Location permissions. BLE is used to infer proximity, so location permission is explicitly requested.
6. On the organiser phone, set a title, duration (e.g. 5 minutes), and RSSI threshold (start at -70 dBm). Tap **Start session & broadcast**.
7. Keep both apps in the foreground with the screens on. The student automatically sends a check-in after five valid samples. No session code or attendance button is required.
8. The student sees confirmation and the organiser's count updates within a few seconds.
9. Close the session. Check student History. Export the CSV using the system file picker.

A more negative RSSI threshold is more permissive: -85 accepts weaker signals than -60. Do not treat a particular threshold as a fixed distance. Test inside and outside the actual room.

## 4. Device-change demo

Use a third phone or uninstall/reinstall on the student phone. A new app installation generates a new key. Sign in with the same student credentials. Tap **Request device change**. On an admin login, open **Review device change requests**, verify identity, and approve. The student signs in again. Prior login tokens are revoked. Organisers cannot approve device changes unless their account is an admin.

One app installation cannot bind to two student accounts. Clearing app data/reinstalling may create a new identity; the prototype does not provide hardware attestation against deliberately obtaining multiple installation identities.

## 5. Upload to GitHub

**Browser route:** create a repository named `ProxyProof`, then use Add file → Upload files to upload the extracted folders and files. Ensure hidden `.github/workflows/build.yml` is included; a git client is more reliable for this. Do not include `.venv`, databases, build caches, passwords, or signing keys.

**Git route**, from the extracted `ProxyProof` folder (replace the URL):

```bash
git init
git add .
git commit -m "Implement ProxyProof BLE attendance prototype"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/ProxyProof.git
git push -u origin main
```

On GitHub, open Actions. The workflow runs backend tests, builds the Android app, and uploads the APK as a workflow artifact. You can also create a Release `v1.0.0-demo` and attach the supplied APK. Submit the repository URL requested by the recruitment form.

## 6. Troubleshooting

| Symptom | Check |
|---|---|
| Connection refused / timeout | Backend running, correct laptop LAN address, same network, firewall, no campus Wi-Fi client isolation |
| Invalid ID or password | Provisioned ID and password; no built-in accounts exist |
| Device approval required | Admin approves requested new installation; student signs in again |
| Bluetooth off | Turn on Bluetooth and use Retry Bluetooth; organiser may need to reopen active session from History |
| No BLE events | Permissions, phone location setting, foreground apps, advertising support, physical proximity |
| Signal too weak | Move closer; calibrate threshold using several phones; do not simply make it universally permissive |
| Invalid signature | Enable automatic date/time; clock difference must be under 30 seconds |
| Session already active | Open History → active session → Open dashboard, or close it |
| Manual removal prevents automatic mark | Intentional: organiser must correct the manual decision |
| Installation conflicts with another build | Debug signing keys can differ between local/CI builds; uninstall old demo, reinstall, then request device approval |

## 7. Production work still needed

HTTPS reverse proxy and production server, managed enrolment/course administration, secure account recovery, stronger rate limiting, key attestation/integrity checks, accessibility and device-matrix testing, privacy retention policy, and resilient background BLE with appropriate foreground-service lifecycle. This package intentionally implements one class roster and foreground operation.

# Explain it in a three-minute demo

**Problem (20 seconds):** Shared attendance codes can be forwarded. Manual roll calls take time. ProxyProof combines a short-lived BLE token, an enrolled device key, and proximity filtering.

**Live demonstration (90 seconds):** Log in on two phones, start a short session, show automatic student attendance and live organiser count. Close the session, show History, and export CSV. If time permits, show a replacement-device approval request.

**Technical explanation (40 seconds):** The organiser gets a token from the server and broadcasts it over BLE. The student collects five signal readings and signs the request using its Keystore key. The server checks role, device, nonce, token expiry, session membership, and signal strength. SQLite stores the result.

**Honest limitations (30 seconds):** This version runs in the foreground, has one class roster, and uses polling. RSSI varies across rooms and devices. Rotating tokens block stale replay, but live relays and a modified client remain open problems. Hardware tests must be conducted on real phones.

## Questions you should be able to answer

- Why BLE rather than pairing? Advertisements allow many nearby listeners without a paired connection.
- Why not only a changing QR code? A changing code can still be forwarded live; the device key and proximity filter add barriers.
- Why five samples? A median is less affected by an outlier than one RSSI reading. The value still needs classroom calibration.
- Where does the private key live? Android Keystore; the API receives only the public key and signatures.
- What happens when the student changes phones? Request, identity verification, admin approval, then new login. Old tokens are revoked.
- Can the same beacon mark many students? Yes. Each authorised student uses it with their own signed request. The beacon must not be globally single-use.
- What is replay protection? Freshness of the beacon plus timestamp/nonce checks for exact signed requests; attendance itself is idempotent.
- Is this truly proxy-proof? No. Explain the live-relay and forged-RSSI limitations instead of making an unsupported claim.
- What would you add next? Course-specific rosters, validated background operation, TLS hosting, device attestation, field calibration, and trusted proximity measurements.

Before submission, read `MainActivity.java`, `BleController.java`, and the `/attendance` handler. Make one small change you understand (for example, session defaults or layout text) and test it. Record your own measurements and demo. Follow the club's rules for acknowledging development assistance.

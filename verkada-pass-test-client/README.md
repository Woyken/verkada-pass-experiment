## Verkada Pass Test Client

Small interactive CLI for:

1. requesting a Verkada Pass magic link
2. redeeming the magic link
3. choosing a mode: remote unlock, BLE central, or BLE peripheral
4. either selecting a door to unlock remotely or exercising one of the BLE flows

## Setup

```powershell
uv sync
Copy-Item .\config.example.toml .\config.toml
```

## Usage

```powershell
uv run verkada-pass-test-client
```

Useful options:

```powershell
uv run verkada-pass-test-client --request-link-only
uv run verkada-pass-test-client --force-login
uv run verkada-pass-test-client --mode remote --list-only
uv run verkada-pass-test-client --logout
uv run verkada-pass-test-client --unlock-method mobile
uv run verkada-pass-test-client --mode ble-central
uv run verkada-pass-test-client --mode ble-central --list-only -vv --log-file ble.log
uv run verkada-pass-test-client --mode ble-peripheral
uv run verkada-pass-test-client --mode ble-central --ble-user-id <uuid>
```

## Notes

- `config.toml` is optional. If values are missing, the app prompts for them.
- The saved session is stored in `.verkada-pass-session.toml` by default.
- BLE keys are stored in `.verkada-pass-ble.toml` by default.
- The default remote unlock request uses `unlockMethod = "mobile"` because that is the strongest value proven from the APK.
- You can override the method in `config.toml` or with `--unlock-method` if you want to test alternatives.
- Remote mode now surfaces `scheduleStates` from the APK-backed `userSchedules` response when they are present.
- If remote unlock fails, the CLI prints the selected door's returned schedule window, schedule events, reader/device counts, user access methods, and org policy flags before the HTTP error.
- `ble-central` first scans for nearby readers on service `FD3B`. If none are found, it falls back to an unfiltered diagnostic scan and reports any `FD3A`, `A4DD`, APK beacon UUID `AC3EF23C-70D8-4773-97AD-B9A566A0FB40`, and serial candidates it can decode from raw scan-record bytes or manufacturer data.
- `ble-central --list-only` prints scan diagnostics without attempting a GATT connection, including known reader serials from unlockables and their predicted beacon major/minor pairs.
- `ble-central` writes the unlock payload with `response=False` to match the APK's reader-write path.
- BLE scan diagnostics now include iBeacon major/minor values when the APK beacon UUID is present.
- `ble-peripheral` advertises the phone-side flow on `FD3A` and serves the read/write GATT characteristics used by the hands-free path.
- BLE mode defaults to the current session user ID unless you override it with `ble_user_id`, `--ble-user-id`, or a `reader_user_ids` config mapping.

## Android test app

A minimal native Android app now lives in `android-app\` for on-device testing of the proven flows:

- request magic links
- redeem a pasted magic-link URL and persist the session
- list doors and send remote unlock requests
- scan nearby BLE readers, show diagnostics, and send the BLE central unlock payload
- persist verbose logs, share the active log file, or copy a log snapshot into `Downloads\VerkadaPassTest`

The Android app intentionally skips BLE peripheral mode in this first version to keep the test harness simple and reliable.

Recent Android-side fixes:

- BLE central scanning now follows the APK's raw scan-record serial extraction path instead of relying only on manufacturer data.
- The Android manifest/runtime permission flow now requests location alongside Bluetooth so nearby-reader and beacon scans are not filtered out by `neverForLocation`.
- The Android scanner now falls back from a normal `FD3B` callback scan to an APK-style `FD3B` pending-intent scan before giving up on central reader discovery.
- The Android auto-unlock flow no longer treats beacon-matched addresses as reader GATT targets; beacons remain diagnostics only because the APK central path connects only actual `FD3B` `ScanResult` devices.

Recent Python-side fixes:

- The desktop scanner now reconstructs WinRT raw advertisement bytes from Bleak `platform_data` and applies the same `scanRecord[9:23]` serial extraction path proven in the APK.
- BLE diagnostics now treat both Apollo (`APL...`) and 14-character Verkada serials like `DMLD-HT99-NT7H` as valid reader-serial candidates.

Build the APK with:

```powershell
Set-Location .\android-app
.\gradlew.bat assembleDebug
```
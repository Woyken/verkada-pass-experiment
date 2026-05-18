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
- `ble-central` scans for nearby readers on service `FD3B`, reads `1001`, and writes the unlock payload to `2000`.
- `ble-peripheral` advertises the phone-side flow on `FD3A` and serves the read/write GATT characteristics used by the hands-free path.
- BLE mode defaults to the current session user ID unless you override it with `ble_user_id`, `--ble-user-id`, or a `reader_user_ids` config mapping.
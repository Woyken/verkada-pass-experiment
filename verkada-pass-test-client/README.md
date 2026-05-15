## Verkada Pass Test Client

Small interactive CLI for:

1. requesting a Verkada Pass magic link
2. redeeming the magic link
3. listing doors available for remote unlock
4. selecting a door to unlock

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
uv run verkada-pass-test-client --list-only
uv run verkada-pass-test-client --logout
uv run verkada-pass-test-client --unlock-method mobile
```

## Notes

- `config.toml` is optional. If values are missing, the app prompts for them.
- The saved session is stored in `.verkada-pass-session.toml` by default.
- The default remote unlock request uses `unlockMethod = "mobile"` because that is the strongest value proven from the APK.
- You can override the method in `config.toml` or with `--unlock-method` if you want to test alternatives.
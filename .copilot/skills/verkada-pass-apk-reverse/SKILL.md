# Verkada Pass APK Reverse

Use for `com.verkada.VerkadaPass` when tracing login, door unlock, or BLE behavior.

## Install

- Java: https://adoptium.net/temurin/ -> `java -version`
- Rust/Cargo: https://rustup.rs/ -> `cargo --version`
- apkeep: https://github.com/EFForg/apkeep -> `cargo install apkeep`
- JADX: https://github.com/skylot/jadx/releases -> `jadx --version`
- APKTool: https://apktool.org/docs/install -> `apktool --version`
- Optional: `adb` https://developer.android.com/tools/releases/platform-tools, mitmproxy https://mitmproxy.org/, Frida https://frida.re/

## Download / decompile

```powershell
apkeep -a com.verkada.VerkadaPass -l .
apkeep -a com.verkada.VerkadaPass -v <version> .
jadx --deobf -d "jadx output <version>" "com.verkada.VerkadaPass@<version>.apk"
jadx-gui --deobf "com.verkada.VerkadaPass@<version>.apk"
apktool d -f "com.verkada.VerkadaPass@<version>.apk" -o "apktool output <version>"
```

Fallback for mangled classes:

```powershell
jadx --deobf -m fallback --single-class zp.h0 --single-class-output "jadx fallback h0.java" "com.verkada.VerkadaPass@<version>.apk"
jadx --deobf -m fallback --single-class z.u1 --single-class-output "jadx fallback u1.java" "com.verkada.VerkadaPass@<version>.apk"
```

## Read first

- `resources\AndroidManifest.xml`
- `resources\res\values\strings.xml`
- `sources\com\verkada\passapp\network\api\ApiConstants.java`
- `sources\mo\a.java`
- `sources\mo\m.java`
- `sources\com\verkada\passapp\network\EmailMagicLinkRequest.java`
- `sources\com\verkada\android\library\auth\network\requests\LoginRequest.java`
- `sources\com\verkada\android\library\auth\network\data\CredentialsNetworkModel.java`
- `sources\com\verkada\passapp\network\GetAccessPointsRequest.java`
- `sources\com\verkada\passapp\network\GetAccessPointsResponse.java`
- `sources\com\verkada\passapp\network\RemoteUnlockRequest.java`
- `sources\com\verkada\passapp\ble\BleService.java`
- `sources\z\u1.java`
- `sources\zp\h0.java`

## Search terms

`magicToken`, `PASS_APP`, `auth/magic`, `user/login`, `X-Verkada-Auth`, `X-Verkada-Organization-Id`, `X-Verkada-User-Id`, `unlockables`, `virtual_device`, `unlockMethod`, `FD3A`, `FD3B`

## Prove

1. Magic-link request and redeem flow.
2. Persisted session fields and auth headers.
3. Shard host rewrite behavior.
4. Door list endpoint and payload.
5. Remote unlock endpoint and exact request body.
6. BLE permissions, UUIDs, GATT flow, and key registration.

## Rules

- Record APK version for every claim.
- Always use JADX with `--deobf`.
- Record only verified claims.
- Mark unknowns as unresolved until directly proven.
- Use APKTool/smali or fallback JADX only when normal decompile is ambiguous.
- If static analysis is still unclear, use only an authorized test account for live verification.

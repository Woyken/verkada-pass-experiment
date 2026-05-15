# Verkada Pass Bluetooth Unlock

## Scope

These notes cover Bluetooth-based door access for a company setup where NFC is disabled and Bluetooth unlock is enabled.

## High-level conclusion

The app supports **two distinct BLE unlock modes**:

1. **Central mode**: the phone scans for nearby readers and connects to them.
2. **Peripheral / hands-free mode**: the phone advertises itself and readers connect to the phone.

This is much more complex than the remote unlock API and is not the best first target for a widget app.

## Permissions and components

Relevant manifest permissions include:

- `BLUETOOTH`
- `BLUETOOTH_ADMIN`
- `BLUETOOTH_ADVERTISE`
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `NFC`

For Android 12+, the app checks for:

- `BLUETOOTH_CONNECT`
- `BLUETOOTH_ADVERTISE`
- `BLUETOOTH_SCAN`

Nearby-door scanning also depends on location permission.

Relevant manifest components:

- `com.verkada.passapp.ble.BleService`
- `com.verkada.passapp.ble.central.BlePendingIntentScanResultBroadcastReceiver`

## Feature gating

Bluetooth unlock is not just a local capability. It is gated by both:

- organization config `bleUnlockEnabled`
- user profile `bluetoothAccessAllowed`

The derived permissions model in the app includes:

- `isBluetoothUnlockAccessAllowed`
- `isRemoteUnlockAllowed`
- `isIntercomAccessAllowed`
- `isNfcUnlockAllowed`

## BLE service behavior

`BleService` is a foreground service and starts both:

- BLE peripheral mode
- BLE central mode

The app also presents user-facing strings about nearby doors, Bluetooth unlock, and the permissions needed for it.

## Central mode details

### Discovery

The phone scans for readers exposing service UUID:

```text
FD3B
0000FD3B-0000-1000-8000-00805F9B34FB
```

The nearby-door stack combines:

- BLE scanning
- AltBeacon ranging

The AltBeacon region UUID found in the app is:

```text
AC3EF23C-70D8-4773-97AD-B9A566A0FB40
```

### GATT characteristics

Reader-side service `FD3B` uses:

- characteristic `1001` for reader public key
- characteristic `2000` for the phone's unlock write

### Payload

After connecting and reading the reader public key, the phone computes auth material and writes:

```text
[phone public key (32 bytes)] [auth tag (32 bytes)] [userId as 16 UUID bytes]
```

The app parses BLE manufacturer data into a reader serial and accepts Apollo readers with serials starting with `APL`.

One gap remains here: the exact source that populates the central reader-serial-to-user mapping was not fully proven.

## Peripheral / hands-free mode details

### Advertising

The phone advertises:

- service `FD3A`
- extra service UUID `A4DD`

### Phone-hosted GATT server

The phone-side GATT server exposes:

- characteristic `1001` for reader writes
- characteristic `2000` for reader reads

### Request and response shape

The reader writes a value to `1001` that the app parses as:

```text
[32-byte value] [UTF-8 reader serial]
```

The app then resolves a user ID for that reader serial, falling back to the current user when possible, and returns on `2000`:

```text
[phone public key (32 bytes)] [auth tag (32 bytes)] [userId as 16 UUID bytes]
```

This matches the app's hands-free unlock behavior.

## Crypto and key registration

The Bluetooth flow uses a locally stored BLE keypair. The app stores keys under a user-specific encrypted preferences namespace and uses keys named:

- `ble_encryption_public_key`
- `ble_encryption_private_key`

The app uses libsodium-style key-exchange and authentication routines to derive session material and auth tags.

Before BLE unlock can work, the phone's public key is registered with the backend if needed:

```text
POST https://vcerberus.command.verkada.com/{organizationId}/keys
```

Registration request fields include:

- `publicKey`
- `platform = ANDROID`
- `version = <Android version>`
- `make = <manufacturer>`
- `model = <model>`
- `name = <model>`

The backend key type constant used by the app is:

```text
BLE_UNLOCK_PUBLIC_KEY_ED25519
```

## What a third-party BLE implementation would need

At minimum:

- authenticated Pass session
- org permission for BLE unlock
- user permission for BLE unlock
- Bluetooth and location permissions
- a foreground BLE service
- local BLE keypair generation and storage
- backend key registration
- nearby reader scanning or phone advertising, depending on mode
- the exact GATT handshake described above

## Practical recommendation

For a home-screen widget, implement **remote unlock first** and treat BLE as a separate project.

Remote unlock fits a widget tap well. BLE unlock is better suited to a full foreground mobile app because it depends on:

- persistent Bluetooth permissions
- a foreground service
- continuous scanning or advertising
- GATT interactions with nearby hardware

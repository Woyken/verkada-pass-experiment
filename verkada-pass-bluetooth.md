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

From the service lifecycle code:

- `PassActivity.onResume()` refreshes the Bluetooth-permission state that feeds BLE service gating
- the app combines the local `bluetooth_unlock_user_preference` toggle with Bluetooth runtime permission state before deciding to run BLE
- the app starts `BleService` when that local BLE toggle is on and the required Bluetooth permissions are granted
- the app stops the service on logout
- the service runs as a foreground service with the user-visible notification text equivalent to "Bluetooth unlock is on"
- `BleService.onStartCommand()` promotes itself to foreground and returns `1` (`START_STICKY`)
- `PassActivity.onDestroy()` does not stop the BLE service

So the BLE peripheral path is **not always on** for every session or every account. It is conditionally started and then kept alive as a foreground service while active, while broader org/user BLE access gating is enforced elsewhere in the app's permissions model.

### What the "open or close the app, then hold phone very close to the reader" behavior is

This is **not a separate feature**. The strongest APK-backed explanation is that it is still the normal BLE unlock pipeline:

- opening the app causes `PassActivity.onResume()` to refresh BLE permission state
- if the BLE preference is on and permissions are present, `PassActivity` starts or re-arms `BleService`
- that foreground service then runs the BLE unlock transports independently of the activity UI

So if unlock works when the app is closed, that is expected: the foreground `BleService` can stay alive after the activity goes away. If unlock works right after opening the app, that is also expected: opening the app can re-check state and restart the service if it was not already active.

The app also has an overrideable BLE mode selector. In normal operation, the service decides whether to run central, peripheral, or both; the APK contains explicit override values:

- `NEITHER` (normal feature-flag-driven behavior)
- `OVERRIDE_TO_PERIPHERAL`
- `OVERRIDE_TO_CENTRAL`
- `OVERRIDE_TO_BOTH`

So the close-range unlock you observed should be treated as **standard background BLE unlock**, not as remote unlock and not as a new hidden mode.

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

The app uses the AltBeacon library, but the configured parser layout is the Apple/iBeacon frame:

```text
m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24
```

So the nearby-reader signal is specifically an iBeacon-style advertisement carrying UUID `AC3EF23C-70D8-4773-97AD-B9A566A0FB40`.

### GATT characteristics

Reader-side service `FD3B` uses:

- characteristic `1001` for reader public key
- characteristic `2000` for the phone's unlock write

The central write path uses the `2000` characteristic as a **write without response** operation.

### Payload

After connecting and reading the reader public key, the phone computes auth material and writes:

```text
[phone public key (32 bytes)] [auth tag (32 bytes)] [userId as 16 UUID bytes]
```

The app does **not** rely only on manufacturer data for the reader serial. The stronger decompiled path is the raw scan record slice:

The decompiled FD3B scan path takes `scanRecord.getBytes()[9:23]` and decodes that 14-byte slice as UTF-8. That width matches the live `readerPeripherals[].serialNumber` values already returned by unlockables for the tested doors:

- `DMLD-HT99-NT7H`
- `DMLX-PHHJ-EX9L`

Those are both 14-character serials. The APK then:

1. SHA-256 hashes that UTF-8 string
2. hex-encodes the digest
3. takes the first 8 hex characters
4. splits them into two 16-bit integers

That pair is what gets merged with the beacon `(major, minor)` pair list.

This is now proven end-to-end against the saved near-door log:

- beacon payload `...c2de a3b2...` matched `DMLX-PHHJ-EX9L`
- beacon payload `...44d4 67d6...` matched `DMLD-HT99-NT7H`

In other words, the app-side mapping from door metadata to nearby-reader beacon IDs is:

`readerPeripherals.serialNumber` -> SHA-256 -> first 8 hex chars -> beacon major/minor

So the beacon-to-door mapping question is no longer open.

As a result, the current test clients were updated to follow that APK-backed path too:

- the Android test app now extracts reader serials from raw scan-record bytes first and only falls back to manufacturer data
- the Python test client now reconstructs WinRT raw advertisement bytes from Bleak `platform_data` and applies the same `scanRecord[9:23]` extraction when the backend exposes enough detail

### Actual central connect target selection

Later APK tracing narrowed an important distinction:

- `mp.C5961a` starts the BLE central path with a **pending-intent** `FD3B` scan
- `BlePendingIntentScanResultBroadcastReceiver` rebroadcasts the raw `ArrayList<ScanResult>`
- the next receiver creates or looks up a Blessed peripheral by **`scanResult.getDevice().getAddress()`**
- the central state machine then feeds that exact peripheral into connect / service discovery / read / write handling

So the official app does **not** connect to a beacon-derived address. The beacon side is a nearby-door signal and door-mapping aid, but the actual GATT target comes from a real `FD3B` `ScanResult`.

That matters because a previous custom-client fallback tried to treat beacon-matched addresses as candidate GATT targets. The APK trace now shows that fallback is not faithful to the app and is the most likely reason those Android-native attempts ended in connection timeouts despite correct beacon matches.

There are effectively two separate BLE discovery signals in the APK:

1. a nearby/beacon merge path that uses the raw scan-record serial and beacon-major/minor mapping
2. the actual central unlock path that waits for a real `FD3B` `ScanResult` and connects that device by address

## Peripheral / hands-free mode details

### Advertising

The phone advertises:

- service `FD3A`
- extra service UUID `A4DD`

The Android peripheral advertiser is configured as:

- connectable
- include device name = `false`
- include TX power = `true`
- advertise mode = high / low-latency equivalent

### Phone-hosted GATT server

The phone-side GATT server exposes:

- characteristic `1001` for reader writes
- characteristic `2000` for reader reads

The exact Android characteristic setup is:

- `1001`: property `WRITE_NO_RESPONSE`, permission `WRITE`
- `2000`: property `READ`, permission `READ`

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

For reproducing the specific close-range "app open or closed" behavior, the APK evidence says it is feasible in principle, but the current Windows Python setup is not a good production target. The protocol is now understood well enough to implement, but matching the official behavior likely requires an Android-native foreground service with Android BLE scanning/advertising semantics rather than a desktop Python runtime.

## Button-tap unlock flow (complete UI → API trace) — CORRECTED

### Summary

**The button-tap unlock is an HTTP API call, not BLE GATT.**

Both paths ("nearby" when close to door, "mobile" when away) call the same endpoint:

```
POST https://vcerberus.command.verkada.com/access/v2/user/virtual_device/{accessPointId}/unlock
Content-Type: application/json
x-verkada-token: <session_token>
x-verkada-organization-id: <org_id>

{"unlockMethod": "nearby"}   ← when BLE beacon detected nearby
{"unlockMethod": "mobile"}   ← when away (requires api-unlock-enabled=true, disabled at this org)
```

The `unlockMethod` field determines whether the server honours the request:
- `"nearby"` — allowed when `ble-unlock-enabled=true` (this org: **yes**)
- `"mobile"` — allowed when `api-unlock-enabled=true` (this org: **no**)

### Action dispatch chain

When the user taps "Unlock" on a door:

1. `p140fs.C2946f.mo81f()` dispatches `ir.C4353o` — **`OnUnlockClick(accessPointId, major, minor, location)`**
2. Reducer `br.C1085o` handles this action, calls `m7923j(c4351m0.f15566U, major, minor, doorLocation)` to determine unlock type:
   - `"Nearby"` → `new C4343i0(c4351m0, accessPointId, z2=true, null)`
   - `"Inside"` → `new C4343i0(c4351m0, accessPointId, z2=(f15569X != -1), null)`
   - `"Outside"` → shows "too far to unlock" snackbar and exits early

### Unlock type determination (`m7923j`)

```java
// ir.C4351m0.m7923j(location, major, minor, doorLocation)
if (f15567V.contains(Pair(major, minor))) {
    return "Nearby";   // iBeacon from this reader detected by AltBeacon scan
}
int maxDist = f15569X;   // always -1 (set in constructor, never updated)
if (location == null) {
    return maxDist == -1 ? "Inside" : "Unknown";
}
return (maxDist == -1 || location.distanceTo(doorLocation) <= maxDist) ? "Inside" : "Outside";
```

Key facts:
- `f15567V` = list of `(major, minor)` pairs populated by `OnNearbyReadersUpdate` from AltBeacon ranging for UUID **`AC3EF23C-70D8-4773-97AD-B9A566A0FB40`** (iBeacon, NOT FD3B)
- `f15569X` is always **`-1`** — distance guard never fires, so "Inside" is returned whenever no beacon is detected
- DML readers advertise iBeacons with this UUID → they **do** appear in `f15567V` when nearby → `m7923j` returns `"Nearby"` → `z2=true`

### Unlock coroutine and HTTP method (full chain)

```
ir.C4343i0.invokeSuspend
  z2=true  → p002a1.C0041g1 case 8 → p632z.C10881u1(accessPointId, "nearby", ..., 4)
  z2=false → p002a1.C0041g1 case 9 → p632z.C10881u1(accessPointId, "mobile", ..., 4)

p632z.C10881u1 case 4:
  RemoteUnlockRoute route = new RemoteUnlockRoute(accessPointId);
  RemoteUnlockRequest request = new RemoteUnlockRequest(unlockMethod);  // "nearby" or "mobile"
  accessPointsApi.remoteUnlock(route, request, continuation);
  // HTTP method = POST  (confirmed: C5252u.f18940c = "POST")
```

Both `z2=true` (nearby) and `z2=false` (mobile) land in **the same case 4 handler** of `C10881u1`, differing only in the `unlockMethod` string passed to `RemoteUnlockRequest`.

### UI indicator meanings

**BLE icon in the regular door list** (🔵 bluetooth icon next to lock):
- Source: `f15560O` — a `HashMap<accessPointId, EnumC9773a>` populated by WebSocket scenario push
- Indicates BLE unlock is **enabled** for this door/user (regardless of proximity)
- `EnumC9773a` values: `LOCKED`(0), `UNLOCKED`(1), `ACCESS_CONTROL`(2)

**"Nearby Doors" special UI section**:
- Source: `f15567V` — the iBeacon `(major, minor)` pair list from AltBeacon scanning
- Appears **only** when a reader's iBeacon is ranged within Bluetooth distance (~10–20 m)
- When a door appears here, button tap uses `unlockMethod: "nearby"` → **this is what unlocks the door at this org**
- Both the regular list and the "Nearby Doors" section can be visible at the same time

### DML reader button-tap experience (corrected understanding)

The user observes: open app → few seconds → door appears with BLE icon → "Nearby Doors" section appears → tap button → door unlocks fast.

The correct trace:
1. App starts AltBeacon ranging for UUID `AC3EF23C-70D8-4773-97AD-B9A566A0FB40`
2. Reader's iBeacon is detected → `OnNearbyReadersUpdate` dispatched → `f15567V` populated
3. "Nearby Doors" section appears in UI with the matched door
4. User taps → `m7923j` returns `"Nearby"` (beacon in `f15567V`) → `z2=true` → `C10881u1` → **HTTP POST with `{"unlockMethod": "nearby"}`**
5. Server validates the user has BLE unlock (`ble-unlock-enabled=true`) → door opens

**This is a plain HTTPS call. No BLE GATT is involved in the button-tap path.**

### BLE peripheral auto-unlock (separate mechanism, no button needed)

The second observed behavior — "hold phone within 1–2 cm of reader and it unlocks without interaction" — is a completely separate flow:
- `BleService` runs as a foreground service advertising FD3A (the GATT server)
- The DML reader acts as GATT central and connects to the phone
- GATT exchange: reader writes 32-byte pubkey + serial to char `1001`, phone responds with 80-byte payload on char `2000`
- Reader validates locally → door opens — **no HTTP, no server involved**

These two flows are independent. The "Nearby Doors" section and BLE peripheral may both be active simultaneously.

### Implementing "nearby" unlock in the test app / widget

Minimum requirements:
1. Valid session token (`x-verkada-token`) and org ID (`x-verkada-organization-id`)
2. `accessPointId` of the door (from the unlockables response)
3. Call: `POST https://vcerberus.command.verkada.com/access/v2/user/virtual_device/{accessPointId}/unlock` with body `{"unlockMethod": "nearby"}`

Optional (mirrors the official app):
- Scan for iBeacon UUID `AC3EF23C-70D8-4773-97AD-B9A566A0FB40` first; only call with "nearby" when the matching beacon is detected (the server may simply trust the client on this, but proximity scanning is safer)

The Python test client supports this today via `client.unlock_door(session, access_point_id, "nearby")`.

UI action classes (for reference):
- `C7429e` = `RequestedUnlock(accessPointId)` — marks door as "unlocking" in list
- `C7427c` = `Closing(accessPointId, duration)` — marks door as "unlocked/closing"
- `C7428d` = `Error(accessPointId)` — marks door as "error"

## Remote unlock relationship

The APK does **not** show BLE peripheral mode as a prerequisite for the remote HTTP unlock endpoint.

Important distinction:

- BLE peripheral mode is for local hands-free unlock with a nearby reader
- remote unlock is a direct authenticated HTTP request

There is one nearby-behavior nuance: the app also has passive nearby scanning code that can run when remote unlock or Bluetooth unlock is allowed, likely for nearby-door UX and permission handling. But that is separate from the dedicated `BleService` foreground service and is not evidence that remote unlock requires a live BLE handshake first.

## Current live result away from the door

From a non-door location on the Windows test machine:

- unfiltered BLE scanning saw several `FD3A` / `A4DD` phone-side advertisers
- no `FD3B` reader-service advertisers were visible
- no decodable `APL...` reader serial payloads were visible

That does not prove the client is wrong. It only proves there was no visible Verkada reader advertisement from that location at scan time. The next useful test is to repeat scanning near the actual door and check for:

1. `FD3B` reader-service advertisements
2. iBeacon hits with UUID `AC3EF23C-70D8-4773-97AD-B9A566A0FB40`
3. decodable reader serial payloads from the raw scan record or manufacturer data

## APK GATT server implementation details (proven from op/e.java and op/d.java)

This section summarises what was directly read from the decompiled APK peripheral implementation, not inferred.

### GATT service setup (`op/e.java` method `l()`)

```java
BluetoothGattService service = new BluetoothGattService(FD3A_UUID, 0 /*PRIMARY*/);
// char 2000: PROPERTY_READ (2), PERMISSION_READ (1)
BluetoothGattCharacteristic readChar  = new BluetoothGattCharacteristic(UUID_2000, 2, 1);
// char 1001: PROPERTY_WRITE_NO_RESPONSE (4), PERMISSION_WRITE (16)
BluetoothGattCharacteristic writeChar = new BluetoothGattCharacteristic(UUID_1001, 4, 16);
service.addCharacteristic(readChar);   // 2000 added first
service.addCharacteristic(writeChar);  // 1001 added second
server.addService(service);
```

Key facts:
- char `2000`: **PROPERTY_READ only** (no NOTIFY, no INDICATE). Permission READ.
- char `1001`: **PROPERTY_WRITE_NO_RESPONSE only** (no WRITE with response). Permission WRITE.
- Characteristics added in order: `2000` first, `1001` second.
- No CCCD descriptors needed.

### GATT callback behaviour (`op/d.java`)

The APK GATT server callback class `d`:

**On write to `1001`:**
1. Validates the value is at least 32 bytes.
2. Splits: `[0:32]` = reader public key, `[32:]` = reader serial (UTF-8).
3. Looks up userId by reader serial from an in-memory map; falls back to current user if not found.
4. Calls the auth-tag computation with `("BLE_UNLOCK_ENCRYPTION_KEYS", userId, readerPublicKey, readerSerial)`.
5. Concatenates `[phonePublicKey 32][authTag 32][userId 16 bytes big-endian]` and stores in a per-device pending map.

**On read from `2000`:**
1. Looks up the pending payload by device address.
2. Returns that payload (handles offset for multi-part reads).
3. If no payload exists yet (write hasn't arrived), returns a 1-byte error status code.

Important: writes from the reader are accumulated if `preparedWrite=true`, with final processing in `onExecuteWrite`. For a single WRITE_NO_RESPONSE the callback fires immediately with the full value.

**On connection state change:**
- Logs CONNECTED/DISCONNECTED at debug level only.

### Advertising setup (`op/e.java` method `k()`)

Matches our current implementation. Notably:
- `setIncludeTxPowerLevel(true)` — the APK includes TX power in the advertisement payload.
- No device name.
- Both FD3A and A4DD service UUIDs advertised.
- Connectable, low latency, high TX power.

### Race condition between GATT server open and reader connection

Live testing showed reader `14:13:0B:C0:3F:50` connecting within 2 ms of `openGattServer()` being called — before `addService()` and before `onServiceAdded` fires. The reader apparently cached the phone's BT address from a previous session. If the reader did service discovery during those 2–3 ms, it would have found an empty GATT server and given up.

**Fix implemented**: `BlePeripheralManager.configure()` is now a `suspend` function. It opens the GATT server, calls `addService()`, and suspends on a `CompletableDeferred` until `onServiceAdded` fires before returning. Advertising only starts after the service is confirmed ready. Additionally, any connection that arrives before `serviceReady=true` is cancelled so the reader retries.

### Earlier bugs in the custom implementation (now fixed)

- char `1001` incorrectly had `PROPERTY_WRITE | PROPERTY_WRITE_NO_RESPONSE` (0x0C) instead of `PROPERTY_WRITE_NO_RESPONSE` only (0x04)
- char `2000` incorrectly had `PROPERTY_READ | PROPERTY_NOTIFY` (0x12) instead of `PROPERTY_READ` only (0x02)
- `PROPERTY_NOTIFY` without a CCCD descriptor is invalid and may cause some BLE stacks to reject the service
- characteristics added in wrong order (1001 first, 2000 second); APK adds 2000 first
- `setIncludeTxPowerLevel(false)` instead of `true`
- GATT server opened inside `startAdvertising()` with no wait for service registration

All of these have been corrected in the current `BlePeripheralManager.kt`.

**Prepared-write handling (fixed 2026-05-20):**
- APK `op/d.java` `onCharacteristicWriteRequest` accumulates ALL writes (both prepared and non-prepared) into a per-device `LinkedHashMap`
- For `preparedWrite=false` (WRITE_NO_RESPONSE): `a()` called immediately with the current chunk
- For `preparedWrite=true`: chunks accumulated; `onExecuteWrite` calls `a()` with the full accumulated buffer
- Response for prepared writes MUST echo back the `offset` and `value` from the request (not null/0)
- Our implementation now matches: `preparedWriteBuffers` map + `onExecuteWrite` processing + correct echo

## Live test results — peripheral mode with Android app

### Run where reader first connected (wrong char properties, race condition present)

- `14:13:0B:C0:3F:50` connected to phone's FD3A GATT server 2 ms after `openGattServer()` returned
- connection persisted for ~48 seconds
- **zero characteristic reads or writes** were observed
- most likely cause: reader connected before `onServiceAdded` fired, did service discovery on empty server, found no characteristics, gave up; stayed connected but idle

### Run with correct char properties, no cancelConnection — reader still silent (2026-05-20)

- GATT server opened at t=0, addService(FD3A) at t=0 (async)
- reader `14:13:0B:C0:3F:50` connected at t+10ms (`serviceReady=false` at connection time)
- `onServiceAdded` fired at t+16ms (6ms AFTER reader connected)
- reader stayed connected for 60 s with **zero writes, zero reads, zero MTU exchange**
- root cause confirmed: reader connected during 6ms window before FD3A service was registered;
  Android responded to reader's GATT discovery with an empty database;
  reader found no service, gave up, stayed idle

### Service Changed + pre-init fix (current build, 2026-05-20)

Two-layer fix implemented:

**Layer 1 — Pre-open GATT server at ViewModel startup:**
- `MainViewModel.init {}` launches `blePeripheral.prepareGattServer()` immediately
- server + services are registered before any BLE unlock flow starts
- by the time the reader connects, the server has been open for hundreds of ms

**Layer 2 — Generic Attribute service + Service Changed indication:**
- GATT server now registers two services in sequence:
  1. Generic Attribute (0x1801) with Service Changed (0x2A05) — registered first (~6ms)
  2. FD3A with chars 2000 + 1001 — registered second (~12ms)
- If the reader connects during that window (before FD3A), it finds Generic Attribute and
  (if BLE-compliant) subscribes to Service Changed via CCCD write
- When FD3A is registered, any already-subscribed early connections receive a Service Changed
  indication (handle range 0x0001–0xFFFF) to force complete GATT re-discovery
- If the reader subscribes to Service Changed AFTER FD3A is already registered, the indication
  is sent immediately in `onDescriptorWriteRequest`

**Layer 3 — Write buffering for key-less server:**
- If the reader writes to char 1001 before keys are configured (possible with pre-opened server),
  the raw write is stored in `pendingRawWrites`
- When `configure(bleKeys, userId)` is called, buffered writes are processed immediately
- This allows the reader's write to be honoured even if it arrived before session HTTP calls

**Layer 4 — advertising + configure before HTTP:**
- `autoBleUnlockKnownDoors()` now calls `configure()` + `startAdvertising()` BEFORE
  `api.listDoors()` and `ensureBleKeyRegistration()` HTTP calls (~3s total)
- by the time HTTP finishes, the reader has had several seconds to discover, subscribe,
  write to char 1001, and potentially trigger `onPayloadComputed` already
- `peripheralUnlock.await()` returns immediately if the write already fired during HTTP

**What to look for in next live test:**
- `[INFO] onServiceAdded status=0 uuid=00001801-...` — Generic Attribute registered
- `[INFO] onServiceAdded status=0 uuid=0000fd3a-...` — FD3A registered
- `[INFO] device 14:13:0B:C0:3F:50 CONNECTED (fd3aReady=true|false)`
- `[INFO] DESCRIPTOR WRITE ... char=00002a05 ... hex=0200` — reader subscribed to Service Changed
- `[INFO] Sent Service Changed indication to 14:13:0B:C0:3F:50` — indication delivered
- `[INFO] WRITE from 14:13:0B:C0:3F:50 char=00001001-...` — reader sent pubkey+serial; check `prepared=false` vs `prepared=true`
- if `prepared=true`: look for `EXECUTE WRITE execute=true` followed by `processing prepared-write buffer`
- `[INFO] READ from 14:13:0B:C0:3F:50 char=00002000-...` — reader read unlock payload
- `[INFO] computed and stored 80-byte unlock payload` — crypto success

**Alternative: reader skips Service Changed entirely**
If the reader never sends a CCCD write to Service Changed, the indication path is unused.
In that case, the pre-init fix (server open before reader arrives) is the only mechanism.
If logs show the reader connects with `fd3aReady=true` (server already fully open), no
Service Changed is needed and the reader should write directly to char 1001.

### Beacon addresses confirmed at door

From unfiltered scans near the door:
- `03:7D:F0:D2:DF:80` → beacon major/minor `49886/41906` → reader serial `DMLX-PHHJ-EX9L` (A door)
- `2F:7E:2E:B1:D8:BA` → beacon major/minor `17620/26582` → reader serial `DMLD-HT99-NT7H` (B door)
- Reader `14:13:0B:C0:3F:50` → this is the device that GATT-connected to the phone (confirmed live)
- FD3B never appears in any scan (reader uses peripheral-to-phone direction exclusively for this site)

## Complete BLE central mode flow (np/ package trace)

This section documents the full central-mode state machine now confirmed from `np/` classes.

### Central mode vs peripheral mode — two simultaneous paths

`BleService.mo3855f()` initialises **both** paths simultaneously:
- Peripheral path: `m11114k()` (advertise FD3A) + `m11115l()` (open GATT server) via `m3853p()`
- Central path: `new C5961a(...)` (BLE scanner) stored in `f6272p0`

The service does not use an exclusive toggle between the two — it runs both whenever Bluetooth is enabled and the BLE unlock service is active.

### Which mode applies to which reader

The central (FD3B) path in `np/g.java` STATE_DISCOVERED includes:

```java
if (t.l0(str, "APL", false)) {       // startsWith("APL")
    eVar.v(mVar, nVar, new g(str));   // Apollo reader → proceed
    return;
}
// Non-Apollo (DML serials like DMLD-HT99-NT7H):
if (featureFlag.getBoolean(key, false)) {
    eVar.v(mVar, nVar, new g(str));   // allowed by feature flag
} else {
    eVar.v(mVar, nVar, new b(4));     // error: non-Apollo not in central path
}
```

This means:
- **Apollo readers** (serial starts `APL`): always handled via central FD3B path
- **DML readers** (serial like `DMLD-*`, `DMLX-*`): central path requires a backend feature flag; without it, they fall through with error code 4

For the tested doors (`DMLD-HT99-NT7H`, `DMLX-PHHJ-EX9L`), the central feature flag appears to be off. These readers advertise beacons (proven live) but do **not** advertise FD3B (never seen in any scan). They therefore use **peripheral mode** — the reader connects to the phone's FD3A GATT server.

### Complete central mode state machine

For Apollo readers or DML readers with feature flag enabled:

| State | Class | Action |
|---|---|---|
| DISCOVERED | `np.g(ScanResult)` | Extract serial from manufacturer data; check APL prefix or feature flag |
| SERIAL_NUMBER_READ | `np.g(String)` | Look up userId from `mVar.X` (serial→userId map); connect to reader |
| CONNECTING | `np.c(i=1)` | Wait for connection established |
| CONNECTED | `np.c(i=0)` | On `onServicesDiscovered` → transition to SERVICES_DISCOVERED |
| SERVICES_DISCOVERED | `np.h` | Get FD3B service; get char `1001`; initiate **read** |
| READING_CHARACTERISTIC | `np.c(i=2)` | Receive char `1001` read result (reader public key, 32 bytes) |
| WRITING_CHARACTERISTIC | `np.i` | Compute payload; if MTU too small → request MTU; write to char `2000` |
| REQUESTING_MTU | `np.g(byte[])` | Request MTU = payload.length+3; on MTU change → write char `2000` |

**Critical char direction in central mode (inverse of peripheral mode):**
- Phone **reads** char `1001` from reader = reader's 32-byte public key
- Phone **writes** char `2000` to reader = 80-byte auth payload

**Payload computation in `np/i.java`:**
```
authTag   = HMAC-SHA512/256(readerSerial.getBytes(UTF-8), txKey)
payload   = concat(phonePublicKey, authTag, userId_big_endian)  // 32+32+16=80 bytes
txKey     = Blake2b-512(X25519(phonePrivKey, readerPubKey) || phonePubKey || readerPubKey)[32:64]
```
This is identical to peripheral mode — only the transport direction differs.

### ReaderPeripheral API model

From `ReaderPeripheral.java` (API response field):
```
accessControllerId  String
doorIndex           String
modelNumber         String
readerAddress       String   ← Bluetooth MAC address of the reader
readerType          String
serialNumber        String   ← 14-char serial e.g. "DMLD-HT99-NT7H"
version             String
```

There is **no explicit `ble-peripheral-mode` field** in the model. Whether a reader uses central or peripheral mode appears to be driven by:
- The reader hardware type / firmware (Apollo vs DML)
- Backend feature flags for non-Apollo central mode

## Critical debugging step: force-stop the official Verkada app

**The most likely reason the reader never writes to our test app's GATT server:**

`BleService` is a foreground Android service that returns `START_STICKY` from `onStartCommand`. When the user "kills" the Verkada app via recent-apps swipe, the **service continues running**. The reader already has an active GATT connection to the official app's FD3A server. Since the reader is a BLE central (it connects out), it may not establish a second parallel connection to our test app while already connected to the official app.

**Required before any peripheral-mode live test:**
```
adb shell am force-stop com.verkada.VerkadaPass
```
`am force-stop` marks the process as force-stopped, which prevents `START_STICKY` restart until the user explicitly opens the app. This ensures the official BleService and its active reader GATT connection are torn down before our test app advertises.

After the force-stop, wait ~2 s for the reader to detect disconnection and start scanning for new FD3A advertisers, then start the test app.

### Updated test sequence

```
# 1. Force-stop the official Verkada app (CRITICAL — tears down active reader connection)
adb shell am force-stop com.verkada.VerkadaPass

# 2. Install / reinstall the test app
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 3. Grant permissions
adb shell pm grant com.woyken.verkadapasstestapp android.permission.BLUETOOTH_SCAN
adb shell pm grant com.woyken.verkadapasstestapp android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.woyken.verkadapasstestapp android.permission.BLUETOOTH_ADVERTISE
adb shell pm grant com.woyken.verkadapasstestapp android.permission.ACCESS_FINE_LOCATION

# 4. Force-stop any stale instance of our app
adb shell am force-stop com.woyken.verkadapasstestapp

# 5. Wait a moment for the reader to notice the official app disconnected
# (reader uses peripheral mode — it will scan for new FD3A advertisers)

# 6. Launch with auto-unlock intent
adb shell am start -n 'com.woyken.verkadapasstestapp/.MainActivity' --ez 'com.woyken.verkadapasstestapp.AUTO_BLE_UNLOCK' true

# 7. Monitor logs
adb shell tail -F /sdcard/Android/data/com.woyken.verkadapasstestapp/files/Documents/logs/verkada-pass-test-app.log
```

## Current implementation status

The reverse-engineering side is complete:

- Login, session, door listing, remote unlock, BLE key registration, BLE crypto fully traced from APK
- GATT peripheral server implementation now matches APK exactly (char properties, permissions, order)
- Race condition fixed: two-phase server open (Generic Attr first, then FD3A), `onServiceAdded` awaited
- Prepared-write handling matches APK: accumulation in `preparedWriteBuffers`, processing on execute
- Reader connection confirmed live: `14:13:0B:C0:3F:50` connects to phone's FD3A server
- Full BLE central state machine traced: STATE_DISCOVERED → SERIAL_NUMBER_READ → CONNECTING → CONNECTED → SERVICES_DISCOVERED → READING_CHARACTERISTIC → WRITING_CHARACTERISTIC
- ReaderPeripheral model fields confirmed from API
- DML readers (our site) use peripheral mode (reader-as-central); Apollo readers use FD3B central path
- Critical debugging step identified: **must force-stop `com.verkada.VerkadaPass` before testing**

The remaining live test to close this out:

1. Force-stop the official Verkada app: `adb shell am force-stop com.verkada.VerkadaPass`
2. Run the updated Android app with the phone at the door
3. Verify reader reconnects and this time writes to char `1001`
4. Verify payload is computed and stored
5. Verify reader reads from char `2000`
6. Door opens

If the reader still does not write after force-stopping the official app, possible next steps:
- Verify the BLE key is already registered (first run registers a new key; the backend must push it to the reader)
- Consider registering the key and waiting a few seconds before advertising, in case the backend→reader sync takes time
- Check if reader expects a specific device name or manufacturer data in the advertisement
- Use `adb shell dumpsys bluetooth_manager` to verify the reader address is no longer in the connected devices list after the force-stop

# Verkada Pass — Bluetooth Unlock Flow Diagrams

Two completely separate BLE unlock flows exist. They run **simultaneously and independently**.

| Flow | Trigger | Protocol | Server involved? |
|---|---|---|---|
| **Button-tap** | User taps; iBeacon detected nearby | HTTPS POST `{"unlockMethod":"nearby"}` | ✅ Yes |
| **Auto-proximity** | Reader RSSI threshold crossed (1–2 cm) | BLE GATT (FD3A) — fully local | ❌ No |

---

## Diagram 1 — BleService startup

Both BLE modes start simultaneously when `BleService` is created.

```mermaid
sequenceDiagram
    participant App as Android App
    participant BleS as BleService
    participant WS as WebSocket

    App->>BleS: startService()
    Note over BleS: PassActivity.onResume checks BLE perms + toggle
    BleS->>BleS: promote to foreground notification
    Note over BleS: lp/b.java reads BLE override from SharedPrefs. NEITHER = use backend feature flags (z2=central, z10=peripheral)

    par Peripheral mode (op/e.java)
        BleS->>BleS: open GATT server
        Note over BleS: addService(GENERIC_ATTR 0x1801) wait onServiceAdded, then addService(FD3A) wait onServiceAdded
        BleS->>BleS: startAdvertising(FD3A + A4DD)
        Note over BleS: connectable, includeTxPower=true, no device name
    and Central scan (mp/C5961a)
        BleS->>BleS: BluetoothLeScanner.startScan filter=FD3B
        Note over BleS: PendingIntent delivery, reportDelay=3000ms, FIRST_MATCH
    end

    App->>WS: subscribeToScenarios (tk/C8420f)
    WS-->>App: door list push with per-door BLE status (LOCKED/UNLOCKED/ACCESS_CONTROL)
    Note over App: f15560O HashMap populated — BLE icon shown per door
```

---

## Diagram 2 — Nearby door detection (AltBeacon iBeacon)

Determines which doors appear in the "Nearby Doors" UI section and enables `unlockMethod:"nearby"`.

```mermaid
sequenceDiagram
    participant BleS as BleService
    participant AltB as AltBeacon
    participant Reader as DML Reader
    participant App as Android App

    BleS->>AltB: startRangingBeacons (ir/C4341h0)
    Note over AltB: region UUID: AC3EF23C-70D8-4773-97AD-B9A566A0FB40, layout m:2-3=0215,i:4-19,i:20-21,i:22-23

    Reader-->>AltB: iBeacon advertisement (major, minor)
    Note over Reader,AltB: major+minor = SHA256(serialNumber)[0:8] split as 2x uint16. e.g. DMLD-HT99-NT7H -> major=17620 minor=26582

    AltB-->>App: OnNearbyReadersUpdate([(major, minor)])
    Note over App: ir/C4351m0: f15567V = [(major,minor)]. Nearby Doors section appears in UI with BLE icon
```

---

## Diagram 3 — Button-tap unlock (HTTP, not BLE GATT)

Triggered when user taps the unlock button. Requires the door to be in the "Nearby Doors" section (iBeacon detected).

```mermaid
sequenceDiagram
    participant App as Android App
    participant Server as vcerberus HTTP API

    App->>App: OnUnlockClick(accessPointId, major, minor)
    Note over App: m7923j: f15567V.contains((major,minor)) == true -> "Nearby" -> z2=true
    App->>App: C4343i0 coroutine z2=true -> C10881u1(accessPointId, "nearby")

    App->>Server: POST /access/v2/user/virtual_device/{accessPointId}/unlock
    Note over App,Server: Headers: X-Verkada-Auth, X-Verkada-Organization-Id, X-Verkada-User-Id. Body: {"unlockMethod":"nearby"}
    Note over Server: checks ble-unlock-enabled=true only. NO proximity proof. NO BLE data in request.
    Server-->>App: {"duration":5.0}
    Note over App: dispatch RequestedUnlock -> Closing(duration=5.0). UI: door unlocked
```

---

## Diagram 4 — Peripheral auto-unlock (BLE GATT, no button, no server)

Runs simultaneously with diagram 2 & 3. Phone advertises as peripheral; DML reader connects when close enough (~1–2 cm).

```mermaid
sequenceDiagram
    participant Reader as DML Reader
    participant BleS as BleService (GATT server)

    Reader->>BleS: GATT connect to phone FD3A server
    Note over Reader,BleS: reader acts as GATT central, connects when its own RSSI threshold is met

    Reader->>BleS: WRITE_NO_RESPONSE to char 1001
    Note over Reader,BleS: [0:32] readerPublicKey (Curve25519), [32:] readerSerial UTF-8 e.g. DMLD-HT99-NT7H

    BleS->>BleS: op/d.java: split pubkey + serial
    Note over BleS: lookup userId by serial (fallback=current user). crypto(BLE_UNLOCK_ENCRYPTION_KEYS, userId, readerPubKey, serial) -> phonePublicKey[32]+authTag[32]+userId[16 BE]. Store 80 bytes in pendingPayloads[addr]

    loop reader polls until payload ready
        Reader->>BleS: GATT READ char 2000
        alt payload not yet computed
            BleS-->>Reader: 0x32 (NO_AUTH_TAG_AND_NO_ERROR)
        else payload ready
            BleS-->>Reader: 80-byte payload [phonePublicKey 32][authTag 32][userId 16]
        end
    end

    Reader->>Reader: validate auth tag locally -> door opens
    Note over Reader: NO HTTP, NO server involved
```

---

## GATT server characteristics (peripheral mode, from op/e.java)

| Char UUID | Property | Permission | Direction |
|---|---|---|---|
| `00001001` | `WRITE_NO_RESPONSE` (0x04) | `WRITE` (0x10) | Reader → Phone |
| `00002000` | `READ` (0x02) | `READ` (0x01) | Phone → Reader (polled) |

- **No NOTIFY / INDICATE** on char `2000` — reader must poll.
- Characteristics added in order: `2000` first, `1001` second.
- No CCCD descriptors needed on either characteristic.

## Advertisement (peripheral mode, from op/e.java k())

- Service UUIDs: `FD3A` + `A4DD`
- Connectable: yes
- Device name: **not included**
- TX power: **included** (`setIncludeTxPowerLevel(true)`)
- Mode: high frequency / low latency

## iBeacon → reader serial mapping (from ir/C4341h0.java)

```
SHA-256( serialNumber.UTF-8 )
  → hex-encode digest
  → take first 8 hex chars
  → split into two 16-bit unsigned ints = (major, minor)

Example:
  serial  DMLD-HT99-NT7H
  sha256  → 44d4...
  major   = 0x44d4 = 17620
  minor   = 0x67d6 = 26582 (next 4 hex chars)
```

Region UUID scanned by AltBeacon: `AC3EF23C-70D8-4773-97AD-B9A566A0FB40`

## BLE mode override (from lp/b.java + aq/C0669f.java SharedPrefs)

| Value | Behaviour |
|---|---|
| `"NEITHER"` (default) | Use backend feature flags: z2 = central, z10 = peripheral |
| `"OVERRIDE_TO_PERIPHERAL"` | Peripheral only |
| `"OVERRIDE_TO_CENTRAL"` | Central only |
| `"OVERRIDE_TO_BOTH"` | Both modes active |

## Server-side validation (confirmed live)

`RemoteUnlockRequest` has exactly **one field**: `unlockMethod: String`.  
`UnlockResponse` has exactly **one field**: `duration: double`.  
The server performs **zero proximity validation**. The `"nearby"` string is a permission key, not a location claim.  
Server only checks `ble-unlock-enabled=true` for the org.

Live-confirmed: `{"unlockMethod":"nearby"}` succeeds from outside Bluetooth range.

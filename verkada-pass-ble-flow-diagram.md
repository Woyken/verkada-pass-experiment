# Verkada Pass — BLE Unlock Wire-Level Data Flow Diagrams

Exact data flowing over each transport, showing direction and byte-level payloads.

---

## 1. iBeacon Advertisement (Reader → Phone, over-the-air)

The DML reader continuously broadcasts standard Apple iBeacon frames. The phone passively receives them.

```mermaid
sequenceDiagram
    participant Reader as DML Reader (Broadcaster)
    participant Phone as Phone (AltBeacon RX)

    Note over Reader: BLE ADV_NONCONN_IND frame (non-connectable undirected)

    Reader->>Phone: BLE Advertisement PDU
    Note right of Reader: AD Type 0xFF (Manufacturer Specific)<br/>Company: 0x004C (Apple)<br/>SubType: 0x02, Length: 0x15<br/>UUID: AC3EF23C-70D8-4773-97AD-B9A566A0FB40<br/>Major: 2 bytes (uint16 BE)<br/>Minor: 2 bytes (uint16 BE)<br/>TX Power: 1 byte (signed int8)

    Note over Phone: AltBeacon parser layout:<br/>m:2-3=0215, i:4-19, i:20-21, i:22-23, p:24-24

    Phone->>Phone: Compute expected (major, minor) for each known door
    Note over Phone: SHA-256(serial.getBytes("UTF-8"))<br/>hex = hexEncode(digest)<br/>major = parseInt(hex[0:4], 16)<br/>minor = parseInt(hex[4:8], 16)

    Phone->>Phone: Match received (major,minor) against door list
    Note over Phone: If match found -> door added to "Nearby Doors" UI
```

### iBeacon PDU byte layout (31 bytes payload)

```
Offset  Bytes  Field
------  -----  -----
0-1     02 01  AD Length=2, AD Type=Flags
2       06     Flags (LE General + BR/EDR Not Supported)
3-4     1A FF  AD Length=26, AD Type=0xFF (Manufacturer Specific)
5-6     4C 00  Company ID: Apple (0x004C, little-endian)
7       02     iBeacon subtype
8       15     iBeacon data length (21 bytes)
9-24    AC3E.. Proximity UUID: AC3EF23C-70D8-4773-97AD-B9A566A0FB40
25-26   XX XX  Major (big-endian uint16)
27-28   YY YY  Minor (big-endian uint16)  
29      ZZ     Measured TX Power at 1m (signed int8, dBm)
```

---

## 2. Phone BLE Advertisement (Phone → Reader, peripheral mode)

The phone advertises to let nearby readers discover and connect to it.

```mermaid
sequenceDiagram
    participant Phone as Phone (Peripheral/Advertiser)
    participant Reader as DML Reader (Central/Scanner)

    Note over Phone: BLE ADV_IND frame (connectable undirected)

    Phone->>Reader: BLE Advertisement PDU
    Note right of Phone: AD Type 0x03 (Complete 16-bit Service UUIDs)<br/>UUIDs: 0xFD3A, 0xA4DD<br/>AD Type 0x0A (TX Power Level)<br/>Value: varies (signed int8 dBm)<br/>NO device name included<br/>Connectable = true

    Note over Phone: AdvertiseSettings:<br/>mode = ADVERTISE_MODE_LOW_LATENCY (2)<br/>txPower = ADVERTISE_TX_POWER_HIGH (3)<br/>timeout = 0 (never stops)
```

---

## 3. Peripheral Mode GATT Unlock (Reader → Phone → Reader)

Phone is GATT **server**. Reader is GATT **client**. No internet involved.

```mermaid
sequenceDiagram
    participant Reader as DML Reader (GATT Client)
    participant Phone as Phone (GATT Server on FD3A)

    Reader->>Phone: BLE Connect Request
    Note over Reader, Phone: L2CAP connection established

    Reader->>Phone: Discover Services
    Phone-->>Reader: Service 0x1801 (Generic Attribute)
    Phone-->>Reader: Service 0xFD3A (Verkada Unlock)
    Note over Phone: Chars under FD3A:<br/>0x2000: READ (prop=0x02, perm=0x01)<br/>0x1001: WRITE_NO_RESPONSE (prop=0x04, perm=0x10)

    Reader->>Phone: ATT Write Command (no response) to char 0x1001
    Note right of Reader: Payload (variable length, 32 + serial_len bytes):<br/>[0:32] Reader Curve25519 Public Key<br/>[32:N] Reader Serial Number (UTF-8 string)<br/><br/>Example (46 bytes total):<br/>0x1A2B...32 bytes...F9E8 (pubkey)<br/>0x444D4C442D485439392D4E543748 ("DMLD-HT99-NT7H")

    Phone->>Phone: Process write (op/d.java)
    Note over Phone: 1. Extract readerPubKey = bytes[0:32]<br/>2. Extract serial = new String(bytes[32:], UTF-8)<br/>3. Lookup userId for this serial<br/>4. Compute 80-byte response (see crypto below)

    loop Reader polls char 0x2000
        Reader->>Phone: ATT Read Request to char 0x2000
        alt Crypto not yet complete
            Phone-->>Reader: ATT Read Response: [0x32]
            Note left of Phone: Single byte 0x32 = "processing, try again"<br/>Error codes: 0x0A=NO_KEYS, 0x14=NO_USER,<br/>0x15=KEY_EXPIRED, 0x1E=GENERAL_ERROR,<br/>0x1F=NO_ACCESS, 0x28=INVALID_READER,<br/>0x32=NO_AUTH_TAG (retry), 0x50=LOCKED_OUT
        else 80-byte payload ready
            Phone-->>Reader: ATT Read Response: 80 bytes
            Note left of Phone: [0:32]  Phone Curve25519 Public Key<br/>[32:64] Auth Tag (HMAC-SHA512/256)<br/>[64:80] User ID (UUID as 16 bytes, BE)<br/><br/>See detailed crypto derivation below
        end
    end

    Reader->>Reader: Verify auth tag, open door
    Reader->>Phone: BLE Disconnect
```

### 80-byte response payload breakdown

```
Offset  Length  Field                    Derivation
------  ------  -----                    ----------
0-31    32      phonePublicKey           From EncryptedSharedPrefs (Curve25519 pub)
32-63   32      authTag                  crypto_auth(serial_bytes, rx_key)
64-79   16      userId                   UUID.toBigEndianBytes()

userId encoding (op/d.java):
  ByteBuffer bb = ByteBuffer.allocate(16)
  bb.order(BIG_ENDIAN)
  bb.putLong(uuid.getMostSignificantBits())   // bytes 0-7
  bb.putLong(uuid.getLeastSignificantBits())  // bytes 8-15
```

### Crypto derivation detail

```
Inputs:
  phonePrivKey[32]   — from EncryptedSharedPrefs "ble_encryption_private_key"
  phonePubKey[32]    — from EncryptedSharedPrefs "ble_encryption_public_key"  
  readerPubKey[32]   — received in char 1001 write
  serial             — received in char 1001 write (UTF-8 string)

Step 1: X25519 Key Exchange (libsodium crypto_kx)
  crypto_kx_client_session_keys(
    tx[32],          ← output (UNUSED)
    rx[32],          ← output (this is the auth key)
    phonePubKey,     ← phone is "client"
    phonePrivKey,
    readerPubKey     ← reader is "server"
  )
  
  Internally: shared = X25519(phonePrivKey, readerPubKey)
              BLAKE2B-512(phonePubKey || readerPubKey || shared)
              rx = first 32 bytes, tx = last 32 bytes

Step 2: Auth Tag (libsodium crypto_auth = HMAC-SHA512/256)
  crypto_auth(
    authTag[32],             ← output
    serial.getBytes("UTF-8"),← message (reader serial as bytes)
    serial.length,           ← message length
    rx[32]                   ← key from step 1
  )
```

---

## 4. Central Mode GATT Unlock (Phone → Reader → Phone)

Phone is GATT **client**. Reader is GATT **server**. No internet involved.  
Roles of characteristics are **reversed** from peripheral mode.

```mermaid
sequenceDiagram
    participant Phone as Phone (GATT Client)
    participant Reader as DML Reader (GATT Server on FD3B)

    Phone->>Phone: BLE scan detects FD3B service
    Note over Phone: ScanFilter: serviceUuid=0xFD3B<br/>ScanSettings: SCAN_MODE_LOW_LATENCY (2)<br/>CALLBACK_TYPE_FIRST_MATCH (1)

    Phone->>Phone: Extract serial from manufacturer data
    Note over Phone: ScanRecord.getManufacturerSpecificData()<br/>mfgId (2 bytes) + payload → concatenate → UTF-8 string = serial<br/>APL-prefix = Apollo device (always allowed)

    Phone->>Reader: BLE Connect Request (TRANSPORT_LE)
    Note over Phone, Reader: L2CAP connection established

    Phone->>Reader: Discover Services
    Reader-->>Phone: Service 0xFD3B
    Note over Reader: Chars under FD3B:<br/>0x1001: READ (reader pubkey lives here)<br/>0x2000: WRITE (phone sends payload here)

    Phone->>Reader: ATT Read Request to char 0x1001
    Reader-->>Phone: ATT Read Response: 32 bytes
    Note left of Reader: [0:32] Reader Curve25519 Public Key

    Phone->>Phone: Compute 80-byte payload
    Note over Phone: Same crypto as peripheral mode:<br/>1. crypto_kx_client_session_keys(tx, rx, phonePub, phonePriv, readerPub)<br/>2. crypto_auth(tag, serial_bytes, serial_len, rx)<br/>3. Assemble: phonePubKey[32] + authTag[32] + userId[16]

    alt payload.length + 3 > current MTU
        Phone->>Reader: ATT MTU Exchange Request
        Note right of Phone: Requested MTU = payload.length + 3 = 83<br/>(min 23, max 517)
        Reader-->>Phone: ATT MTU Exchange Response
    end

    Phone->>Reader: ATT Write Request to char 0x2000
    Note right of Phone: 80 bytes:<br/>[0:32]  Phone Curve25519 Public Key<br/>[32:64] Auth Tag (HMAC-SHA512/256)<br/>[64:80] User ID (16 bytes, BE UUID)
    Reader-->>Phone: ATT Write Response (success)

    Note over Phone: State -> USER_ID_LOOKUP_SUCCESS
    Phone->>Reader: BLE Disconnect

    Reader->>Reader: Verify auth tag, open door
```

### Central mode characteristic roles (reversed!)

| Char UUID | On Reader (FD3B server) | Data | Direction |
|---|---|---|---|
| `0x1001` | READ | Reader's 32-byte Curve25519 public key | Reader → Phone |
| `0x2000` | WRITE | Phone's 80-byte unlock payload | Phone → Reader |

### Serial extraction from scan (np/C6484g.java)

```
ScanRecord manufacturer data structure:
  Key: manufacturer ID (uint16)
  Value: byte[] payload

Serial = new String(
  concat(
    shortToByteArray(manufacturerId),  // 2 bytes, big-endian
    manufacturerPayload                // N bytes
  ),
  "UTF-8"
)

Example: mfgId=0x4150 ("AP"), payload=[0x4C, ...] → "APL..."
```

---

## 5. Button-Tap HTTP Unlock (Phone → Server)

Uses HTTP, not BLE GATT. Only requires iBeacon proximity detection (Diagram 1).

```mermaid
sequenceDiagram
    participant Phone as Phone App
    participant Server as vcerberus.command.verkada.com

    Phone->>Phone: User taps unlock button
    Note over Phone: Precondition: door's (major,minor) found in<br/>f15567V nearby list from iBeacon ranging

    Phone->>Server: HTTPS POST
    Note right of Phone: POST /access/v2/user/virtual_device/{accessPointId}/unlock<br/>Host: vcerberus.command.verkada.com<br/><br/>Headers:<br/>  X-Verkada-Auth: {jwt_token}<br/>  X-Verkada-Organization-Id: {org_uuid}<br/>  X-Verkada-User-Id: {user_uuid}<br/>  Content-Type: application/json<br/><br/>Body:<br/>  {"unlockMethod": "nearby"}

    Server-->>Phone: HTTPS 200 OK
    Note left of Server: Content-Type: application/json<br/><br/>Body:<br/>  {"duration": 5.0}

    Note over Phone: UI state: RequestedUnlock -> Closing(5.0s)
```

### HTTP request/response exact fields

```
REQUEST:
  Method: POST
  Path:   /access/v2/user/virtual_device/<accessPointId>/unlock
  
  Headers:
    X-Verkada-Auth:            <JWT token string>
    X-Verkada-Organization-Id: <UUID string, e.g. "bbe18f9c-a606-4619-82e1-103c45f7b49e">
    X-Verkada-User-Id:         <UUID string>
    Content-Type:              application/json
  
  Body (JSON):
    {
      "unlockMethod": "nearby"   ← only valid value for BLE proximity unlock
    }

RESPONSE:
  Status: 200 OK
  
  Body (JSON):
    {
      "duration": 5.0            ← seconds the door stays unlocked (double)
    }

NOTE: Server performs ZERO proximity validation.
      "nearby" is a permission gate, not a location proof.
      Confirmed: works from any distance if iBeacon was seen at least once.
```

---

## 6. BLE Key Registration (Phone → Server, one-time setup)

Before any BLE unlock can work, the phone must register its public key with the server.

```mermaid
sequenceDiagram
    participant Phone as Phone App
    participant KS as EncryptedSharedPrefs
    participant Server as vcerberus.command.verkada.com

    Phone->>KS: Check for existing keypair
    alt No keys stored
        Phone->>Phone: crypto_kx_keypair()
        Note over Phone: Generates 32-byte Curve25519 keypair<br/>via libsodium (lazysodium JNA)
        Phone->>KS: Store Base64(pubKey) + Base64(privKey)
        Note over KS: File: ble_encryption_keys_{userId}<br/>Keys: "ble_encryption_public_key"<br/>      "ble_encryption_private_key"<br/>Encrypted with AndroidKeyStore AES-256-GCM
    end

    Phone->>Phone: Compute fingerprint
    Note over Phone: fingerprint = Base64(SHA-256(Base64(publicKey)))

    Phone->>Server: GET /{organizationId}/keys
    Note right of Phone: Host: vcerberus.command.verkada.com<br/>Headers: X-Verkada-Auth, X-Verkada-Organization-Id

    Server-->>Phone: JSON array of registered keys
    Note left of Server: [{fingerprint, publicKey, keyType, ...}, ...]

    alt Fingerprint not found in server response
        Phone->>Server: POST /{organizationId}/keys
        Note right of Phone: Body:<br/>{<br/>  "publicKey": "Base64(pubKey32)",<br/>  "platform": "ANDROID",<br/>  "version": "3.5.6",<br/>  "make": "Google",<br/>  "model": "Pixel 7",<br/>  "name": "Pixel 7",<br/>  "keyType": "BLE_UNLOCK_PUBLIC_KEY_ED25519"<br/>}
        Server-->>Phone: 200 OK (key registered)
    else Fingerprint already registered
        Note over Phone: No action needed, key is current
    end
```

---

## Quick Reference: Data Direction Summary

```
┌─────────────────────────────────────────────────────────────────────┐
│                    PERIPHERAL MODE (FD3A)                            │
│                                                                     │
│  Reader ──[char 1001 WRITE]──► Phone                                │
│         readerPubKey[32] + serial[N]  (variable len)                │
│                                                                     │
│  Reader ◄──[char 2000 READ]─── Phone                                │
│         phonePubKey[32] + authTag[32] + userId[16]  (80 bytes)      │
│         OR error: single byte (0x0A/0x14/0x15/0x1E/0x1F/0x28/0x32) │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    CENTRAL MODE (FD3B)                               │
│                                                                     │
│  Phone ◄──[char 1001 READ]─── Reader                                │
│         readerPubKey[32]  (32 bytes)                                │
│                                                                     │
│  Phone ──[char 2000 WRITE]──► Reader                                │
│         phonePubKey[32] + authTag[32] + userId[16]  (80 bytes)      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    HTTP BUTTON-TAP                                   │
│                                                                     │
│  Phone ──[HTTPS POST]──► Server                                     │
│         {"unlockMethod":"nearby"}                                   │
│                                                                     │
│  Phone ◄──[HTTPS 200]─── Server                                     │
│         {"duration":5.0}                                            │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    iBEACON (passive, no connection)                  │
│                                                                     │
│  Reader ──[ADV broadcast]──► Phone                                  │
│         iBeacon: UUID + major[2] + minor[2] + txPower[1]           │
│         (phone never transmits back to reader for this)            │
└─────────────────────────────────────────────────────────────────────┘
```

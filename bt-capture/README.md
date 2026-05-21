# Verkada BLE Capture Toolkit

Intercepts all Bluetooth traffic between your Pixel 9 Pro and BLE devices
(Verkada door reader, watch, etc.) using Android's built-in HCI snoop log.

## Requirements

- Phone connected via ADB (`adb devices` shows it)
- Python 3 in PATH
- Wireshark 4+ installed (installed at `C:\Program Files\Wireshark\`)

---

## Quick-start for door capture

### Step 1 — Before leaving for the door

```powershell
cd C:\Projects\Personal\verkada\bt-capture
.\capture-ble.ps1 pre
```

This enables HCI logging and **cold-restarts Bluetooth** so the log starts clean
(connection events are included, not just mid-session traffic).

### Step 2 — At the door

1. Open Verkada Pass app on the phone
2. Wait for door to appear in "Nearby Doors"
3. Either: tap the unlock button **or** hold phone close to reader (~1-2 cm)
4. Come back to PC

### Step 3 — Extract the log

```powershell
.\capture-ble.ps1 post
```

Captures a bugreport, extracts `btsnooz_hci-TIMESTAMP.log`, prints packet stats.

### Step 4 — Analyze

```powershell
# Python analyzer — shows Verkada GATT payloads decoded
.\capture-ble.ps1 analyze

# Or open in Wireshark for full visual inspection
& "C:\Program Files\Wireshark\Wireshark.exe" btsnooz_hci-TIMESTAMP.log
```

---

## Wireshark display filters

Paste these into the Wireshark filter bar to focus on relevant traffic.

| What you want to see | Filter |
|---|---|
| All Bluetooth ATT (GATT) | `btatt` |
| ATT Write Commands (char 1001 — reader→phone, peripheral mode) | `btatt.opcode == 0x52` |
| ATT Read Responses (char 2000 — phone→reader, peripheral mode) | `btatt.opcode == 0x0b` |
| ATT Write Requests (char 2000 — phone→reader, central mode) | `btatt.opcode == 0x12` |
| ATT Read Requests | `btatt.opcode == 0x0a` |
| BLE advertisements only | `btle.advertising_header` |
| iBeacon (Apple company 0x004C) | `btcommon.eir_ad.entry.company_id == 0x004c` |
| All HCI events | `hci_evt` |
| BLE connection events | `hci_evt.code == 0x3e` |
| Specific device by MAC | `bluetooth.src == "XX:XX:XX:XX:XX:XX" or bluetooth.dst == "XX:XX:XX:XX:XX:XX"` |
| 80-byte payloads (unlock payloads) | `btatt.value && data.len == 80` |

### Useful Wireshark columns to add

Right-click column header → Column Preferences → add:

- `_ws.col.Protocol` — Protocol name
- `bluetooth.src` — BT source address
- `bluetooth.dst` — BT destination address
- `btatt.handle` — ATT handle

---

## What the Python analyzer looks for

`analyze_verkada_ble.py` automatically decodes:

| Event | What it prints |
|---|---|
| iBeacon from Verkada reader | `major`, `minor`, `RSSI` |
| BLE connection to/from Verkada device | `handle`, `bdaddr`, role (central/peripheral) |
| ATT Write to char 1001 (peripheral mode) | `readerPubKey[32]` + `readerSerial` |
| ATT Read Response 80 bytes (peripheral mode) | `phonePubKey[32]` + `authTag[32]` + `userId[16]` as UUID |
| ATT Read Response 32 bytes (central mode) | `readerPubKey[32]` |
| ATT Write Request 80 bytes (central mode) | Same 80-byte breakdown |

A device is marked as "Verkada" if it advertised FD3A, FD3B, A4DD, or iBeacon UUID
`AC3EF23C-70D8-4773-97AD-B9A566A0FB40`.

---

## How the capture works

Android's Bluetooth stack writes all HCI traffic (commands, events, ACL data) to
`/data/misc/bluetooth/logs/btsnooz_hci.log` when `bluetooth_hci_log=1` is set.
This file is not directly accessible without root, but `adb bugreport` packages it.

The file format is standard **btsnoop** (RFC 1761), readable by Wireshark and tshark.

HCI ACL packets contain L2CAP frames → ATT (BLE GATT) protocol data.
All BLE traffic — advertisements, connections, GATT reads/writes — is captured here.
There is no encryption at the HCI layer; the Verkada app-level GATT payloads appear in plaintext.

---

## Files

| File | Purpose |
|---|---|
| `capture-ble.ps1` | Main workflow: `pre` / `post` / `analyze` |
| `analyze_verkada_ble.py` | Decodes Verkada GATT traffic from btsnoop log |
| `parse_btsnoop.py` | Quick packet-count summary |
| `btsnooz_hci-*.log` | Captured logs (gitignored) |
| `bugreport-*.zip` | Raw bugreports (gitignored) |

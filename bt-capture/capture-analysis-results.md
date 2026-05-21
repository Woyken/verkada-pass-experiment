# BLE Capture Analysis Results

**Capture file:** `btsnooz_hci-20260521-131622.log`  
**Date:** 2025-05-21  
**Device:** Pixel 9 Pro (Android 16)  
**Location:** Near two Verkada door readers  

## Key Findings

### Verkada Readers Detected: 2

| Address | Major | Minor | Reader Serial | RSSI Range | Advert Count |
|---------|-------|-------|---------------|------------|--------------|
| `03:7D:F0:D2:DF:80` | 49886 | 41906 | `DMLX-PHHJ-EX9L` | -91 to -66 dBm | ~159 |
| `2F:7E:2E:B1:D8:BA` | 17620 | 26582 | `DMLD-HT99-NT7H` | -92 to -54 dBm | ~179 |

### iBeacon Parameters (both readers identical)

- **UUID:** `AC3EF23C-70D8-4773-97AD-B9A566A0FB40`
- **TX Power:** -75 dBm (calibrated reference at 1m)
- **Address Type:** Random (0x01)
- **Event Type:** 0x0010 (non-connectable, non-scannable directed)

### Serial-to-Beacon Mapping (confirmed)

```
SHA-256("DMLX-PHHJ-EX9L") → first 8 hex chars → C2DE A3B2 → major=49886, minor=41906 ✓
SHA-256("DMLD-HT99-NT7H") → first 8 hex chars → 44D4 67D6 → major=17620, minor=26582 ✓
```

### What Was NOT Observed

| Feature | Status | Reason |
|---------|--------|--------|
| FD3A service data (phone advertising) | ❌ Not seen | Phone had empty advertising data — app likely not in active BLE mode |
| FD3B service data (reader central trigger) | ❌ Not seen | No physical presence detected (no tap/wave at reader) |
| GATT connections | ❌ None | Too far away + no FD3B trigger |
| Unlock payloads (80-byte) | ❌ None | No GATT connection established |

### Other BLE Traffic

- **1 non-Verkada iBeacon:** `DA:BD:DC:FA:DF:73` (major=43690, minor=48059) — unrelated device
- **27 total unique advertising addresses** in capture
- **72 ATT WriteCmd** to handle 0x001B — Pixel Watch pre-existing BLE connection
- **42 Extended Advertising HCI commands** from phone — all with data_length=0

## Technical Notes

### Extended Advertising Reports (Critical for Android 16)

The Pixel 9 Pro on Android 16 reports **ALL** BLE advertisements using Extended Advertising Reports (HCI LE Meta sub-event `0x0D`) instead of the legacy format (`0x02`). The analyzer has been updated to handle this.

**Per-entry format (24 bytes fixed + variable data):**
```
Offset  Size  Field
0       2     Event_Type (LE)
2       1     Address_Type
3       6     Address (little-endian)
9       1     Primary_PHY
10      1     Secondary_PHY
11      1     Advertising_SID
12      1     TX_Power (signed, 0x7F=unavailable)
13      1     RSSI (signed)
14      2     Periodic_Advertising_Interval
16      1     Direct_Address_Type
17      6     Direct_Address
23      1     Data_Length
24      N     AD structures
```

### AD Structure of Verkada iBeacon

```
02 01 04                          # Flags: BR/EDR Not Supported
1A FF 4C 00 02 15                 # Manufacturer Specific: Apple iBeacon
AC 3E F2 3C 70 D8 47 73          # UUID bytes 0-7
97 AD B9 A5 66 A0 FB 40          # UUID bytes 8-15
[MM MM] [mm mm]                   # Major (BE), Minor (BE)
B5                                # TX Power (-75 signed)
```

## Next Steps for Complete Capture

To capture the full GATT unlock exchange, re-run with:
1. **Verkada Pass app open and active** (foreground, BLE enabled)
2. **Phone physically touching/waving at the reader** (within ~5cm)
3. This should trigger FD3B advertisement from reader → phone connects → 80-byte unlock payload exchange

#!/usr/bin/env python3
"""
analyze_verkada_ble.py — Decode Verkada BLE GATT traffic from a btsnoop_hci.log.

What it looks for:
  - BLE advertisements with FD3A / FD3B service UUIDs (reader or phone)
  - iBeacon advertisements matching UUID AC3EF23C-70D8-4773-97AD-B9A566A0FB40
  - ATT Write Command to char 0x1001 (reader → phone in peripheral mode)
    Payload: readerPubKey[32] + readerSerial[N]
  - ATT Read Response from char 0x2000 (phone → reader in peripheral mode)
    Payload: phonePubKey[32] + authTag[32] + userId[16]  = 80 bytes
  - ATT Read Request/Response for char 0x1001 (phone ← reader in central mode)
  - ATT Write Request to char 0x2000 (phone → reader in central mode)
"""

import struct
import sys
from datetime import datetime, timedelta

# ── btsnoop constants ─────────────────────────────────────────────────────────
HCI_CMD   = 0x01
ACL_DATA  = 0x02
HCI_EVENT = 0x04

# HCI event codes
EVT_LE_META = 0x3E
LE_ADV_REPORT = 0x02
LE_EXT_ADV_REPORT = 0x0D  # Extended Advertising Report (Android 12+ / BT 5.0+)
LE_CONN_COMPLETE = 0x01

# ATT opcodes
ATT_WRITE_CMD  = 0x52   # Write Command (no response) — char 1001 write in peripheral mode
ATT_WRITE_REQ  = 0x12   # Write Request — char 2000 write in central mode
ATT_WRITE_RSP  = 0x13   # Write Response
ATT_READ_REQ   = 0x0A   # Read Request
ATT_READ_RSP   = 0x0B   # Read Response — char 2000 response in peripheral mode / char 1001 in central

# Known Verkada UUIDs (16-bit, little-endian in packets)
UUID_FD3A = b'\x3A\xFD'  # Phone peripheral GATT service
UUID_FD3B = b'\x3B\xFD'  # Reader GATT server service (central mode)
UUID_1001 = b'\x01\x10'  # Characteristic: reader pubkey write / reader pubkey read
UUID_2000 = b'\x00\x20'  # Characteristic: phone payload response / phone payload write
UUID_A4DD = b'\xDD\xA4'  # Extra advert service UUID

IBEACON_UUID = bytes.fromhex('AC3EF23C70D8477397ADB9A566A0FB40')


# ── btsnoop parser ────────────────────────────────────────────────────────────
def iter_packets(data):
    """Yield (flags, timestamp_us, payload) for each btsnoop record."""
    offset = 16  # skip 16-byte file header
    epoch = datetime(2000, 1, 1)  # btsnoop ts is microseconds since 2000-01-01 00:00 UTC
    while offset + 24 <= len(data):
        orig_len = struct.unpack('>I', data[offset:offset+4])[0]
        incl_len = struct.unpack('>I', data[offset+4:offset+8])[0]
        flags    = struct.unpack('>I', data[offset+8:offset+12])[0]
        drops    = struct.unpack('>I', data[offset+12:offset+16])[0]
        ts_us    = struct.unpack('>q', data[offset+16:offset+24])[0]
        offset  += 24
        payload  = data[offset:offset+incl_len]
        offset  += incl_len
        ts = epoch + timedelta(microseconds=ts_us)
        yield flags, ts, payload


# ── HCI event helpers ─────────────────────────────────────────────────────────
def parse_le_adv_report(data):
    """Parse LE Meta Subevent 0x02 (LE Advertising Report).
    Returns list of dicts with adv_type, bdaddr, raw_ad."""
    reports = []
    if len(data) < 2:
        return reports
    num = data[0]
    offset = 1
    for _ in range(num):
        if offset + 9 > len(data):
            break
        evt_type  = data[offset]
        addr_type = data[offset+1]
        bdaddr    = ':'.join('%02X' % b for b in reversed(data[offset+2:offset+8]))
        adv_len   = data[offset+8]
        offset   += 9
        ad_data   = data[offset:offset+adv_len]
        rssi      = data[offset+adv_len] if offset+adv_len < len(data) else 0
        offset   += adv_len + 1
        reports.append({'evt_type': evt_type, 'bdaddr': bdaddr,
                        'ad': ad_data, 'rssi': rssi - 256 if rssi > 127 else rssi})
    return reports


def parse_le_ext_adv_report(data):
    """Parse LE Meta Subevent 0x0D (LE Extended Advertising Report).
    Used by Android 12+/BT 5.0+ instead of legacy 0x02.
    Returns list of dicts with bdaddr, raw_ad, rssi."""
    reports = []
    if len(data) < 1:
        return reports
    num = data[0]
    offset = 1
    for _ in range(num):
        if offset + 24 > len(data):
            break
        evt_type = struct.unpack('<H', data[offset:offset+2])[0]
        addr_type = data[offset+2]
        bdaddr = ':'.join('%02X' % b for b in reversed(data[offset+3:offset+9]))
        # primary_phy(1) + secondary_phy(1) + adv_sid(1) + tx_power(1) + rssi(1)
        rssi_byte = data[offset+13]
        rssi = rssi_byte - 256 if rssi_byte > 127 else rssi_byte
        # periodic_adv_interval(2) + direct_addr_type(1) + direct_addr(6)
        data_len = data[offset+23]
        ad_data = data[offset+24:offset+24+data_len]
        offset += 24 + data_len
        reports.append({'evt_type': evt_type, 'bdaddr': bdaddr,
                        'ad': ad_data, 'rssi': rssi})
    return reports


def parse_ad_structures(ad_data):
    """Parse AD structures from advertisement payload. Returns list of (type, value)."""
    result = []
    i = 0
    while i < len(ad_data):
        if i >= len(ad_data):
            break
        length = ad_data[i]
        if length == 0 or i + length >= len(ad_data):
            break
        ad_type = ad_data[i+1]
        value   = ad_data[i+2:i+1+length]
        result.append((ad_type, value))
        i += 1 + length
    return result


def check_ibeacon(mfg_data):
    """Return (major, minor, tx_power) if this is a Verkada iBeacon, else None."""
    if len(mfg_data) < 23:
        return None
    company = struct.unpack('<H', mfg_data[0:2])[0]
    if company != 0x004C:  # Apple
        return None
    if mfg_data[2] != 0x02 or mfg_data[3] != 0x15:  # iBeacon subtype+length
        return None
    uuid_bytes = mfg_data[4:20]
    if uuid_bytes != IBEACON_UUID:
        return None
    major    = struct.unpack('>H', mfg_data[20:22])[0]
    minor    = struct.unpack('>H', mfg_data[22:24])[0]
    tx_power = struct.unpack('b', bytes([mfg_data[24]]))[0] if len(mfg_data) > 24 else 0
    return major, minor, tx_power


# ── ACL/L2CAP/ATT helpers ─────────────────────────────────────────────────────
def parse_acl(data):
    """Parse HCI ACL Data packet. Returns (handle, pb, l2cap_data) or None."""
    if len(data) < 5:
        return None
    handle_flags = struct.unpack('<H', data[1:3])[0]
    handle = handle_flags & 0x0FFF
    pb     = (handle_flags >> 12) & 0x03
    total_len = struct.unpack('<H', data[3:5])[0]
    l2cap = data[5:]
    return handle, pb, l2cap


def parse_l2cap_att(l2cap):
    """Return ATT payload if L2CAP channel ID == 0x0004 (ATT)."""
    if len(l2cap) < 4:
        return None
    length = struct.unpack('<H', l2cap[0:2])[0]
    cid    = struct.unpack('<H', l2cap[2:4])[0]
    if cid != 0x0004:
        return None
    return l2cap[4:4+length]


def handle_name(h):
    return '(handle=0x%03X)' % h


# ── Main analysis ─────────────────────────────────────────────────────────────
def analyze(path):
    print('='*70)
    print('Verkada BLE Traffic Analyzer')
    print('File: %s' % path)
    print('='*70)

    with open(path, 'rb') as f:
        data = f.read()

    magic = data[:8]
    if magic != b'btsnoop\x00':
        print('ERROR: Not a valid btsnoop file (magic=%r)' % magic)
        return

    # State tracking
    connections = {}        # handle → bdaddr
    verkada_devices = set() # bdaddrs that showed FD3A/FD3B/iBeacon
    gatt_events = []        # interesting ATT events

    for flags, ts, payload in iter_packets(data):
        if not payload:
            continue
        hci_type = payload[0]
        ts_str = ts.strftime('%H:%M:%S.%f')[:-3]

        # ── HCI Events (advertisements, connections) ──
        if hci_type == HCI_EVENT and len(payload) >= 3:
            evt_code = payload[1]
            # LE Meta event
            if evt_code == EVT_LE_META and len(payload) >= 4:
                subevent = payload[3]

                # LE Connection Complete
                if subevent == LE_CONN_COMPLETE and len(payload) >= 19:
                    status   = payload[4]
                    handle   = struct.unpack('<H', payload[5:7])[0]
                    role     = payload[7]   # 0=central, 1=peripheral
                    bdaddr   = ':'.join('%02X' % b for b in reversed(payload[9:15]))
                    if status == 0:
                        connections[handle] = bdaddr
                        role_str = 'CENTRAL(phone initiates)' if role == 0 else 'PERIPHERAL(phone accepts)'
                        marker = ' *** VERKADA?' if bdaddr in verkada_devices else ''
                        print('[%s] BLE CONNECTION handle=0x%03X  %s  role=%s%s' % (
                            ts_str, handle, bdaddr, role_str, marker))
                        if bdaddr in verkada_devices:
                            gatt_events.append({'ts': ts_str, 'event': 'CONNECTED', 'bdaddr': bdaddr,
                                                'handle': handle, 'role': role_str})

                # LE Advertising Report
                elif subevent == LE_ADV_REPORT:
                    for rpt in parse_le_adv_report(payload[4:]):
                        ads = parse_ad_structures(rpt['ad'])
                        is_verkada = False
                        ibeacon_info = None
                        svc_uuids = []

                        for ad_type, value in ads:
                            # Complete / Incomplete 16-bit UUIDs
                            if ad_type in (0x02, 0x03):
                                for i in range(0, len(value)-1, 2):
                                    uuid = value[i:i+2]
                                    if uuid in (UUID_FD3A, UUID_FD3B, UUID_A4DD):
                                        svc_uuids.append('0x%04X' % struct.unpack('<H', uuid)[0])
                                        is_verkada = True
                            # Service Data (16-bit UUID)
                            if ad_type == 0x16 and len(value) >= 2:
                                svc = struct.unpack('<H', value[:2])[0]
                                if svc in (0xFD3A, 0xFD3B, 0xA4DD):
                                    svc_uuids.append('0x%04X(svcdata)' % svc)
                                    is_verkada = True
                            # Manufacturer Specific
                            if ad_type == 0xFF:
                                ib = check_ibeacon(value)
                                if ib:
                                    ibeacon_info = ib
                                    is_verkada = True

                        if is_verkada:
                            verkada_devices.add(rpt['bdaddr'])
                            if svc_uuids:
                                print('[%s] ADV  %s  RSSI=%ddBm  Services=%s' % (
                                    ts_str, rpt['bdaddr'], rpt['rssi'], ','.join(svc_uuids)))
                            if ibeacon_info:
                                major, minor, txp = ibeacon_info
                                print('[%s] iBEACON  %s  major=%d minor=%d txPower=%ddBm  RSSI=%ddBm' % (
                                    ts_str, rpt['bdaddr'], major, minor, txp, rpt['rssi']))
                                gatt_events.append({'ts': ts_str, 'event': 'IBEACON',
                                                    'bdaddr': rpt['bdaddr'],
                                                    'major': major, 'minor': minor,
                                                    'rssi': rpt['rssi']})

                # LE Extended Advertising Report (BT 5.0+ / Android 12+)
                elif subevent == LE_EXT_ADV_REPORT:
                    for rpt in parse_le_ext_adv_report(payload[4:]):
                        ads = parse_ad_structures(rpt['ad'])
                        is_verkada = False
                        ibeacon_info = None
                        svc_uuids = []

                        for ad_type, value in ads:
                            if ad_type in (0x02, 0x03):
                                for i in range(0, len(value)-1, 2):
                                    uuid = value[i:i+2]
                                    if uuid in (UUID_FD3A, UUID_FD3B, UUID_A4DD):
                                        svc_uuids.append('0x%04X' % struct.unpack('<H', uuid)[0])
                                        is_verkada = True
                            if ad_type == 0x16 and len(value) >= 2:
                                svc = struct.unpack('<H', value[:2])[0]
                                if svc in (0xFD3A, 0xFD3B, 0xA4DD):
                                    svc_uuids.append('0x%04X(svcdata)' % svc)
                                    is_verkada = True
                            if ad_type == 0xFF:
                                ib = check_ibeacon(value)
                                if ib:
                                    ibeacon_info = ib
                                    is_verkada = True

                        if is_verkada:
                            verkada_devices.add(rpt['bdaddr'])
                            if svc_uuids:
                                print('[%s] ADV  %s  RSSI=%ddBm  Services=%s' % (
                                    ts_str, rpt['bdaddr'], rpt['rssi'], ','.join(svc_uuids)))
                            if ibeacon_info:
                                major, minor, txp = ibeacon_info
                                print('[%s] iBEACON  %s  major=%d minor=%d txPower=%ddBm  RSSI=%ddBm' % (
                                    ts_str, rpt['bdaddr'], major, minor, txp, rpt['rssi']))
                                gatt_events.append({'ts': ts_str, 'event': 'IBEACON',
                                                    'bdaddr': rpt['bdaddr'],
                                                    'major': major, 'minor': minor,
                                                    'rssi': rpt['rssi']})

        # ── ACL Data (GATT/ATT) ──
        elif hci_type == ACL_DATA:
            acl = parse_acl(payload)
            if not acl:
                continue
            handle, pb, l2cap = acl
            att = parse_l2cap_att(l2cap)
            if not att or len(att) < 1:
                continue

            opcode = att[0]
            bdaddr = connections.get(handle, '??:??:??:??:??:??')
            is_vk  = bdaddr in verkada_devices

            # direction: flags bit 0 = 0 means host→controller (phone→reader), 1 = controller→host
            direction = '->Reader' if (flags & 1) == 0 else '<-Reader'

            # ATT Write Command (WRITE_NO_RESPONSE) — 0x52
            # In peripheral mode: reader writes readerPubKey[32]+serial to char 1001
            if opcode == ATT_WRITE_CMD and len(att) >= 3:
                char_handle = struct.unpack('<H', att[1:3])[0]
                value = att[3:]
                tag = '[VK-PERIPHERAL]' if is_vk else ''
                print('[%s] ATT WriteCmd  %s  charHandle=0x%04X  len=%d  %s%s' % (
                    ts_str, bdaddr, char_handle, len(value), direction, tag))
                if len(value) >= 32:
                    pubkey = value[:32].hex()
                    serial_bytes = value[32:]
                    try:
                        serial = serial_bytes.decode('utf-8')
                    except Exception:
                        serial = serial_bytes.hex()
                    print('         readerPubKey[32]: %s' % pubkey)
                    print('         readerSerial    : %r (%s)' % (serial, serial_bytes.hex()))
                    if is_vk:
                        gatt_events.append({'ts': ts_str, 'event': 'READER_WRITE_1001',
                                            'bdaddr': bdaddr, 'pubkey': pubkey, 'serial': serial})

            # ATT Read Response — 0x0B
            # In peripheral mode: phone returns 80-byte payload on char 2000
            # In central mode: phone receives reader pubkey from char 1001 (32 bytes)
            elif opcode == ATT_READ_RSP and len(att) >= 1:
                value = att[1:]
                tag = '[VK]' if is_vk else ''
                print('[%s] ATT ReadRsp   %s  len=%d  %s%s' % (
                    ts_str, bdaddr, len(value), direction, tag))
                if len(value) == 80:
                    phone_pub  = value[0:32].hex()
                    auth_tag   = value[32:64].hex()
                    user_id    = value[64:80].hex()
                    print('         [UNLOCK PAYLOAD 80 bytes]')
                    print('         phonePubKey[32]: %s' % phone_pub)
                    print('         authTag    [32]: %s' % auth_tag)
                    print('         userId     [16]: %s' % user_id)
                    # format UUID from 16 BE bytes
                    uid = user_id
                    uuid_str = '%s-%s-%s-%s-%s' % (uid[0:8], uid[8:12], uid[12:16], uid[16:20], uid[20:32])
                    print('         userId (UUID)  : %s' % uuid_str)
                    if is_vk:
                        gatt_events.append({'ts': ts_str, 'event': 'PHONE_PAYLOAD_2000',
                                            'bdaddr': bdaddr, 'phonePubKey': phone_pub,
                                            'authTag': auth_tag, 'userId': uuid_str})
                elif len(value) == 32:
                    print('         readerPubKey[32] (central mode): %s' % value.hex())
                    if is_vk:
                        gatt_events.append({'ts': ts_str, 'event': 'READER_PUBKEY_1001_CENTRAL',
                                            'bdaddr': bdaddr, 'pubkey': value.hex()})
                elif is_vk:
                    print('         raw: %s' % value[:64].hex() + ('...' if len(value)>64 else ''))

            # ATT Write Request — 0x12
            # In central mode: phone writes 80-byte payload to char 2000
            elif opcode == ATT_WRITE_REQ and len(att) >= 3:
                char_handle = struct.unpack('<H', att[1:3])[0]
                value = att[3:]
                tag = '[VK-CENTRAL]' if is_vk else ''
                print('[%s] ATT WriteReq  %s  charHandle=0x%04X  len=%d  %s%s' % (
                    ts_str, bdaddr, char_handle, len(value), direction, tag))
                if len(value) == 80:
                    phone_pub = value[0:32].hex()
                    auth_tag  = value[32:64].hex()
                    user_id   = value[64:80].hex()
                    print('         [UNLOCK PAYLOAD 80 bytes — central mode]')
                    print('         phonePubKey[32]: %s' % phone_pub)
                    print('         authTag    [32]: %s' % auth_tag)
                    print('         userId     [16]: %s' % user_id)
                    uid = user_id
                    uuid_str = '%s-%s-%s-%s-%s' % (uid[0:8], uid[8:12], uid[12:16], uid[16:20], uid[20:32])
                    print('         userId (UUID)  : %s' % uuid_str)
                    if is_vk:
                        gatt_events.append({'ts': ts_str, 'event': 'PHONE_PAYLOAD_2000_CENTRAL',
                                            'bdaddr': bdaddr, 'phonePubKey': phone_pub,
                                            'authTag': auth_tag, 'userId': uuid_str})

            # ATT Read Request — 0x0A
            elif opcode == ATT_READ_REQ and len(att) >= 3:
                char_handle = struct.unpack('<H', att[1:3])[0]
                if is_vk:
                    print('[%s] ATT ReadReq   %s  charHandle=0x%04X  %s[VK]' % (
                        ts_str, bdaddr, char_handle, direction))

    # ── Summary ──
    print()
    print('='*70)
    print('SUMMARY -- Verkada-related devices: %d' % len(verkada_devices))
    for bd in sorted(verkada_devices):
        print('  %s' % bd)
    print()
    print('Key events (%d):' % len(gatt_events))
    for ev in gatt_events:
        print('  [%s] %s  %s' % (ev['ts'], ev['event'], ev.get('bdaddr', '')))
        if ev['event'] == 'IBEACON':
            print('           major=%d  minor=%d  RSSI=%d' % (ev['major'], ev['minor'], ev['rssi']))
        elif ev['event'] == 'READER_WRITE_1001':
            print('           serial=%r' % ev.get('serial', ''))
            print('           pubkey=%s' % ev.get('pubkey', '')[:32] + '...')
        elif ev['event'] in ('PHONE_PAYLOAD_2000', 'PHONE_PAYLOAD_2000_CENTRAL'):
            print('           userId=%s' % ev.get('userId', ''))
            print('           authTag=%s...' % ev.get('authTag', '')[:32])


if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else r'C:\Projects\Personal\verkada\bt-capture\btsnooz_hci.log'
    analyze(path)


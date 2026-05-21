#!/usr/bin/env python3
"""
Quick btsnoop stats — packet type summary.
Used by capture-ble.ps1 for post-session summary.
"""
import struct
import sys

def parse(path):
    with open(path, 'rb') as f:
        data = f.read()

    magic = data[:8]
    if magic != b'btsnoop\x00':
        print('ERROR: Not a valid btsnoop file')
        return

    version  = struct.unpack('>I', data[8:12])[0]
    datalink = struct.unpack('>I', data[12:16])[0]
    print('Format: btsnoop v%d, datalink=%d (1002=HCI H4)' % (version, datalink))

    offset = 16
    packets = 0
    hci_types = {}
    while offset + 24 <= len(data):
        orig_len = struct.unpack('>I', data[offset:offset+4])[0]
        incl_len = struct.unpack('>I', data[offset+4:offset+8])[0]
        offset += 24
        pkt_data = data[offset:offset+incl_len]
        offset += incl_len
        packets += 1
        if pkt_data:
            t = pkt_data[0]
            hci_types[t] = hci_types.get(t, 0) + 1

    names = {1: 'HCI Command', 2: 'ACL Data', 3: 'SCO', 4: 'HCI Event'}
    print('Total packets: %d' % packets)
    for t, c in sorted(hci_types.items()):
        print('  0x%02X %-14s %d' % (t, names.get(t, 'unknown'), c))

if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else r'C:\Projects\Personal\verkada\bt-capture\btsnooz_hci.log'
    parse(path)

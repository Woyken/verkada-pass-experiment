#!/usr/bin/env python3
"""
Raw HCI packet dumper for btsnoop logs.
Shows ALL packets (connections, GATT, advertisements) without filtering for Verkada only.
Focuses on LE connection events and ATT/GATT exchanges with non-watch handles.
"""
import struct
import sys
from datetime import datetime, timedelta

BTSNOOP_EPOCH = datetime(2000, 1, 1)

def parse_btsnoop(path):
    with open(path, 'rb') as f:
        magic = f.read(8)
        if magic != b'btsnoop\x00':
            print(f"Not a btsnoop file (magic={magic!r})")
            return
        ver, datalink = struct.unpack('>II', f.read(8))
        print(f"btsnoop v{ver}, datalink={datalink} ({'HCI H4' if datalink == 1002 else 'unknown'})")
        print("="*90)

        pkt_num = 0
        connections = {}  # handle -> info
        
        while True:
            hdr = f.read(24)
            if len(hdr) < 24:
                break
            orig_len, inc_len, flags, drops, ts_us = struct.unpack('>IIIIq', hdr)
            data = f.read(inc_len)
            if len(data) < inc_len:
                break
            pkt_num += 1
            
            ts = BTSNOOP_EPOCH + timedelta(microseconds=ts_us)
            direction = "TX" if (flags & 1) == 0 else "RX"  # 0=sent, 1=received
            is_cmd_evt = (flags >> 1) & 1  # 1=command/event, 0=data
            
            if len(data) < 1:
                continue
                
            pkt_type = data[0]  # H4 type
            payload = data[1:]
            
            # HCI Event (0x04)
            if pkt_type == 0x04 and len(payload) >= 2:
                evt_code = payload[0]
                evt_len = payload[1]
                evt_data = payload[2:]
                
                # LE Meta Event (0x3E)
                if evt_code == 0x3E and len(evt_data) >= 1:
                    sub_evt = evt_data[0]
                    
                    # LE Connection Complete (0x01)
                    if sub_evt == 0x01 and len(evt_data) >= 19:
                        status = evt_data[1]
                        handle = struct.unpack('<H', evt_data[2:4])[0]
                        role = evt_data[4]  # 0=central, 1=peripheral
                        peer_type = evt_data[5]
                        peer_addr = ':'.join(f'{b:02x}' for b in reversed(evt_data[6:12]))
                        connections[handle] = {'addr': peer_addr, 'role': role}
                        role_str = "CENTRAL" if role == 0 else "PERIPHERAL"
                        print(f"[{ts.strftime('%H:%M:%S.%f')[:-3]}] LE_CONN_COMPLETE  handle=0x{handle:04X}  "
                              f"role={role_str}  peer={peer_addr}  status={status}")
                    
                    # LE Enhanced Connection Complete (0x0A)
                    elif sub_evt == 0x0A and len(evt_data) >= 31:
                        status = evt_data[1]
                        handle = struct.unpack('<H', evt_data[2:4])[0]
                        role = evt_data[4]
                        peer_type = evt_data[5]
                        peer_addr = ':'.join(f'{b:02x}' for b in reversed(evt_data[6:12]))
                        connections[handle] = {'addr': peer_addr, 'role': role}
                        role_str = "CENTRAL" if role == 0 else "PERIPHERAL"
                        print(f"[{ts.strftime('%H:%M:%S.%f')[:-3]}] LE_ENH_CONN      handle=0x{handle:04X}  "
                              f"role={role_str}  peer={peer_addr}  status={status}")
                    
                    # LE Advertising Report (0x02) - just count
                    elif sub_evt == 0x02:
                        pass  # Skip ads for raw dump (too noisy)
                    
                    # LE Extended Advertising Report (0x0D)
                    elif sub_evt == 0x0D:
                        pass  # Skip extended ads
                
                # Disconnection Complete (0x05)
                elif evt_code == 0x05 and len(evt_data) >= 4:
                    status = evt_data[0]
                    handle = struct.unpack('<H', evt_data[1:3])[0]
                    reason = evt_data[3]
                    info = connections.get(handle, {})
                    peer = info.get('addr', '??')
                    print(f"[{ts.strftime('%H:%M:%S.%f')[:-3]}] DISCONNECT        handle=0x{handle:04X}  "
                          f"peer={peer}  reason=0x{reason:02X}")
                
                # Number of Completed Packets (0x13) - skip
                elif evt_code == 0x13:
                    pass
            
            # ACL Data (0x02)
            elif pkt_type == 0x02 and len(payload) >= 4:
                handle_flags = struct.unpack('<H', payload[0:2])[0]
                handle = handle_flags & 0x0FFF
                acl_len = struct.unpack('<H', payload[2:4])[0]
                acl_data = payload[4:]
                
                # Skip watch traffic (handle 0x001B with tiny payloads)
                if handle == 0x001B:
                    continue
                
                # L2CAP header
                if len(acl_data) >= 4:
                    l2cap_len, cid = struct.unpack('<HH', acl_data[0:4])
                    l2cap_payload = acl_data[4:]
                    
                    # ATT (CID 0x0004)
                    if cid == 0x0004 and len(l2cap_payload) >= 1:
                        att_opcode = l2cap_payload[0]
                        att_data = l2cap_payload[1:]
                        
                        att_names = {
                            0x01: "ErrorRsp",
                            0x02: "ExchangeMTUReq",
                            0x03: "ExchangeMTURsp",
                            0x04: "FindInfoReq",
                            0x05: "FindInfoRsp",
                            0x06: "FindByTypeValueReq",
                            0x07: "FindByTypeValueRsp",
                            0x08: "ReadByTypeReq",
                            0x09: "ReadByTypeRsp",
                            0x0A: "ReadReq",
                            0x0B: "ReadRsp",
                            0x10: "ReadByGroupTypeReq",
                            0x11: "ReadByGroupTypeRsp",
                            0x12: "WriteReq",
                            0x13: "WriteRsp",
                            0x16: "PrepWriteReq",
                            0x17: "PrepWriteRsp",
                            0x18: "ExecWriteReq",
                            0x19: "ExecWriteRsp",
                            0x1B: "HandleValueNotif",
                            0x1D: "HandleValueIndic",
                            0x1E: "HandleValueConfirm",
                            0x52: "WriteCmd",
                        }
                        att_name = att_names.get(att_opcode, f"ATT_0x{att_opcode:02X}")
                        
                        info = connections.get(handle, {})
                        peer = info.get('addr', f'handle=0x{handle:04X}')
                        
                        # Show details for interesting ATT operations
                        detail = ""
                        if att_opcode in (0x08, 0x10) and len(att_data) >= 6:  # ReadByType/ReadByGroupType
                            start_h, end_h = struct.unpack('<HH', att_data[0:4])
                            uuid_data = att_data[4:]
                            if len(uuid_data) == 2:
                                uuid = struct.unpack('<H', uuid_data)[0]
                                detail = f"  range=[0x{start_h:04X}-0x{end_h:04X}] uuid=0x{uuid:04X}"
                            elif len(uuid_data) == 16:
                                uuid = uuid_data[::-1].hex()
                                uuid_str = f"{uuid[0:8]}-{uuid[8:12]}-{uuid[12:16]}-{uuid[16:20]}-{uuid[20:32]}"
                                detail = f"  range=[0x{start_h:04X}-0x{end_h:04X}] uuid={uuid_str}"
                        elif att_opcode in (0x12, 0x52) and len(att_data) >= 2:  # Write
                            attr_handle = struct.unpack('<H', att_data[0:2])[0]
                            write_data = att_data[2:]
                            detail = f"  attr=0x{attr_handle:04X} len={len(write_data)} data={write_data[:20].hex()}"
                            if len(write_data) > 20:
                                detail += "..."
                        elif att_opcode == 0x0A and len(att_data) >= 2:  # Read
                            attr_handle = struct.unpack('<H', att_data[0:2])[0]
                            detail = f"  attr=0x{attr_handle:04X}"
                        elif att_opcode == 0x0B:  # ReadRsp
                            detail = f"  len={len(att_data)} data={att_data[:20].hex()}"
                            if len(att_data) > 20:
                                detail += "..."
                        elif att_opcode == 0x1B and len(att_data) >= 2:  # Notification
                            attr_handle = struct.unpack('<H', att_data[0:2])[0]
                            notif_data = att_data[2:]
                            detail = f"  attr=0x{attr_handle:04X} len={len(notif_data)} data={notif_data[:20].hex()}"
                            if len(notif_data) > 20:
                                detail += "..."
                        
                        print(f"[{ts.strftime('%H:%M:%S.%f')[:-3]}] {direction} ATT {att_name:<20s} "
                              f"peer={peer}{detail}")
                    
                    # SMP (CID 0x0006)
                    elif cid == 0x0006:
                        smp_code = l2cap_payload[0] if l2cap_payload else 0
                        smp_names = {1:"PairingReq",2:"PairingRsp",3:"PairingConfirm",
                                     4:"PairingRandom",5:"PairingFailed",0x0B:"SecurityReq"}
                        smp_name = smp_names.get(smp_code, f"SMP_0x{smp_code:02X}")
                        print(f"[{ts.strftime('%H:%M:%S.%f')[:-3]}] {direction} SMP {smp_name}  "
                              f"handle=0x{handle:04X}")
            
            # HCI Command (0x01) - only show LE-related
            elif pkt_type == 0x01 and len(payload) >= 3:
                opcode = struct.unpack('<H', payload[0:2])[0]
                ogf = (opcode >> 10) & 0x3F
                ocf = opcode & 0x3FF
                
                # LE commands (OGF 0x08)
                if ogf == 0x08:
                    le_cmds = {
                        0x000D: "LE_CreateConn",
                        0x0043: "LE_ExtCreateConn",
                        0x000E: "LE_CreateConnCancel",
                        0x000C: "LE_SetScanParams",
                        0x000B: "LE_SetScanEnable",
                        0x0041: "LE_SetExtScanParams",
                        0x0042: "LE_SetExtScanEnable",
                        0x0006: "LE_SetAdvParams",
                        0x0008: "LE_SetAdvData",
                        0x000A: "LE_SetAdvEnable",
                        0x0036: "LE_SetExtAdvParams",
                        0x0037: "LE_SetExtAdvData",
                        0x0039: "LE_SetExtAdvEnable",
                    }
                    cmd_name = le_cmds.get(ocf, f"LE_OGF08_OCF{ocf:04X}")
                    # For create connection, show target address
                    detail = ""
                    cmd_data = payload[3:]
                    if ocf == 0x000D and len(cmd_data) >= 22:  # LE Create Connection
                        peer_type = cmd_data[4]
                        peer_addr = ':'.join(f'{b:02x}' for b in reversed(cmd_data[5:11]))
                        detail = f"  peer={peer_addr}"
                    
                    print(f"[{ts.strftime('%H:%M:%S.%f')[:-3]}] CMD {cmd_name}{detail}")

    print(f"\n{'='*90}")
    print(f"Total packets: {pkt_num}")
    print(f"Active connections seen: {len(connections)}")
    for h, info in connections.items():
        role = "CENTRAL" if info.get('role') == 0 else "PERIPHERAL"
        print(f"  handle=0x{h:04X}  peer={info['addr']}  role={role}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python raw_hci_dump.py <btsnoop_file>")
        sys.exit(1)
    parse_btsnoop(sys.argv[1])

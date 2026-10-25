#!/usr/bin/env python3
"""Bangun file .pac sintetis sesuai layout BinPack SPD Flash Tool,
untuk menguji PacParser.java tanpa perangkat fisik."""
import struct, os

HDR = 2124
ENT = 2580

def crc16_table():
    t = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
        t.append(c)
    return t

T = crc16_table()

def crc16(crc, data):
    for b in data:
        crc = (crc >> 8) ^ T[(crc ^ b) & 0xFF]
    return crc & 0xFFFF

def u16(v): return struct.pack('<H', v)
def u32(v): return struct.pack('<I', v)

def name_arr(s, chars):
    b = s.encode('utf-16-le')[:chars*2]
    return b + b'\x00' * (chars*2 - len(b))

# ---- entri PAC
entries = [
    # id, filename, version, size, flag, check, addrs
    ("FDL1", "fdl1-sign.bin",  "R1.0", b'\x11' * 8192,  1, 1, [0x5500]),
    ("FDL2", "fdl2-sign.bin",  "R1.0", b'\x22' * 16384, 1, 1, [0x9EFFFE00]),
    ("PACKET_XML", "xml.bin",  "",     b'',              1, 0, [0]),
    ("BOOT", "boot.img",       "R1.0", b'\x33' * 4096,  1, 1, [0]),
    ("SYSTEM", "system.img",   "R1.0", b'\x44' * 8192,  1, 1, [0]),
    ("USERDATA", "userdata.img","R1.0", b'\x55' * 2048, 1, 1, [0]),
]

xml = """<?xml version="1.0" encoding="UTF-8"?>
<BMAConfig>
<FileList>
<File id="FDL1" id_alias="FDL" type="FDL" base_address="0x5500"/>
<File id="FDL2" id_alias="FDL2" type="FDL" base_address="0x9EFFFE00"/>
<File id="BOOT" id_alias="boot" type="NANDGEN2">
  <Block id="BOOT" base="0x0" size="0x4000000"/>
</File>
<File id="SYSTEM" id_alias="system" type="NANDGEN2">
  <Block id="SYSTEM" base="0x0" size="0x80000000"/>
</File>
<File id="USERDATA" id_alias="userdata" type="NANDGEN2">
  <Block id="USERDATA" base="0x0" size="0x40000000"/>
</File>
</FileList>
</BMAConfig>
""".encode('utf-8')

file_offset = HDR
xml_start = file_offset + len(entries) * ENT
data_start = xml_start + len(xml)

# hitung data offset tiap entri
blobs = []
off = data_start
for (eid, fn, ver, data, flag, check, addrs) in entries:
    doff = off if data else 0
    blobs.append((doff, data))
    if data:
        off += len(data)

total = off

# ---- header
h = bytearray(HDR)
h[0:48]      = name_arr("BP_R1.0.0", 24)
h[48:52]     = u32(total)
h[52:564]    = name_arr("ums312_2h10", 256)
h[564:1076]  = name_arr("SP9832E_V01", 256)
h[1076:1080] = struct.pack('<i', len(entries))
h[1080:1084] = u32(file_offset)
h[1084:1088] = u32(0)          # mode normal
h[1088:1092] = u32(2)          # eMMC
h[1092:1096] = u32(0)
h[1096:1100] = u32(0)
h[1100:1104] = u32(0)
h[1104:1304] = name_arr("ums312", 100)
h[2116:2120] = u32(0xFFFAFFFA)

# ---- tabel entri
tbl = bytearray()
for i, (eid, fn, ver, data, flag, check, addrs) in enumerate(entries):
    e = bytearray(ENT)
    e[0:4]       = u32(ENT)
    e[4:516]     = name_arr(eid, 256)
    e[516:1028]  = name_arr(fn, 256)
    e[1028:1540] = name_arr(ver, 256)
    doff = blobs[i][0]
    e[1540:1544] = u32(len(data))
    e[1544:1548] = struct.pack('<i', flag)
    e[1548:1552] = u32(check)
    e[1552:1556] = u32(doff)
    e[1556:1560] = u32(0)
    e[1560:1564] = u32(len(addrs))
    for k in range(5):
        e[1564+k*4:1568+k*4] = u32(addrs[k] if k < len(addrs) else 0)
    tbl += e

body = bytes(h) + bytes(tbl) + xml + b''.join(d for _, d in blobs)

# crc1 = crc16(0, header[0:2120])
c1 = crc16(0, body[0:2120])
body = body[:2120] + u16(c1) + body[2122:]

# crc2 = crc16(0, whole file dengan field crc2 dinolkan)
b = bytearray(body)
b[2122:2124] = b'\x00\x00'
c2 = crc16(0, bytes(b))
body = body[:2122] + u16(c2) + body[2124:]

os.makedirs('/root/spd/test', exist_ok=True)
out = '/root/spd/test/fake.pac'
open(out, 'wb').write(body)

print(f"PAC  : {out}")
print(f"size : {len(body)} byte")
print(f"crc1 : 0x{c1:04X}")
print(f"crc2 : 0x{c2:04X}")
print(f"entri: {len(entries)}")
print(f"xml  : {len(xml)} byte @ {xml_start}")
print(f"data : @ {data_start}")

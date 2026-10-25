package com.unisoc.spdflasher;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PacParser {

    public static final long PAC_MAGIC = 0xFFFAFFFAL;
    public static final int HDR_SIZE = 2124;
    public static final int ENTRY_SIZE = 2580;

    public static class Entry {
        public int index;
        public String id = "";
        public String name = "";
        public String version = "";
        public long size;
        public long dataOffset;
        public int fileFlag;
        public int checkFlag;
        public int omitFlag;
        public int addrCount;
        public long[] addr = new long[5];

        public String idName = "";
        public String type = "";
        public long baseAddress = -1;
        public long blockSize = 0;
        public long partSizeMb = 0;
        public final List<String> ops = new ArrayList<>();

        public boolean hasData() {
            return fileFlag != 0 && size > 0 && dataOffset > 0;
        }

        public String label() {
            String n = idName.isEmpty() ? id : idName;
            if (n.isEmpty()) n = name;
            if (n.isEmpty()) n = "entry" + index;
            return n;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder(label());
            if (!type.isEmpty()) sb.append("  [").append(type).append(']');
            if (size > 0) sb.append("  ").append(human(size));
            if (baseAddress > 0) sb.append("  @0x").append(Long.toHexString(baseAddress));
            return sb.toString();
        }
    }

    public static class Pac {
        public String version = "";
        public String productName = "";
        public String productVersion = "";
        public String productAlias = "";
        public long size;
        public int fileCount;
        public int fileOffset;
        public int mode;
        public int flashType;
        public int nandStrategy;
        public int nvBackup;
        public int nandPageType;
        public int magic;
        public int crc1;
        public int crc2;
        public String xml = "";
        public long xmlOffset = -1;
        public long xmlSize = 0;
        public final List<Entry> entries = new ArrayList<>();
        public File file;

        public boolean magicOk() {
            return (magic & 0xFFFFFFFFL) == PAC_MAGIC;
        }

        public String modeStr() {
            switch (mode) {
                case 0: return "Normal";
                case 1: return "NV Backup";
                case 2: return "Factory";
                default: return "Unknown(" + mode + ")";
            }
        }

        public String flashTypeStr() {
            switch (flashType) {
                case 0: return "NOR";
                case 1: return "NAND";
                case 2: return "eMMC";
                default: return "Unknown(" + flashType + ")";
            }
        }
    }

    public static Pac parse(File f) throws IOException {
        Pac p = new Pac();
        p.file = f;
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            if (raf.length() < HDR_SIZE) throw new IOException("File terlalu kecil, bukan PAC");
            byte[] h = new byte[HDR_SIZE];
            raf.readFully(h);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);

            p.version = utf16(h, 0, 24);
            p.size = b.getInt(48) & 0xFFFFFFFFL;
            p.productName = utf16(h, 52, 256);
            p.productVersion = utf16(h, 564, 256);
            p.fileCount = b.getInt(1076);
            p.fileOffset = b.getInt(1080);
            p.mode = b.getInt(1084);
            p.flashType = b.getInt(1088);
            p.nandStrategy = b.getInt(1092);
            p.nvBackup = b.getInt(1096);
            p.nandPageType = b.getInt(1100);
            p.productAlias = utf16(h, 1104, 100);
            p.magic = b.getInt(2116);
            p.crc1 = b.getShort(2120) & 0xFFFF;
            p.crc2 = b.getShort(2122) & 0xFFFF;

            if (!p.magicOk()) {
                throw new IOException("Magic PAC tidak cocok (0x"
                        + Long.toHexString(p.magic & 0xFFFFFFFFL)
                        + "), file bukan firmware PAC Unisoc");
            }

            if (p.fileCount > 0 && p.fileCount < 4096 && p.fileOffset > 0
                    && (long) p.fileOffset + (long) p.fileCount * ENTRY_SIZE <= raf.length()) {
                raf.seek(p.fileOffset);
                byte[] e = new byte[ENTRY_SIZE];
                for (int i = 0; i < p.fileCount; i++) {
                    raf.readFully(e);
                    ByteBuffer eb = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN);
                    Entry en = new Entry();
                    en.index = i;
                    en.id = utf16(e, 4, 256);
                    en.name = utf16(e, 516, 256);
                    en.version = utf16(e, 1028, 256);

                    en.size = ((long) eb.getInt(1532) & 0xFFFFFFFFL) << 32
                            | (eb.getInt(1540) & 0xFFFFFFFFL);
                    en.fileFlag = eb.getInt(1544);
                    en.checkFlag = eb.getInt(1548);
                    en.dataOffset = ((long) eb.getInt(1536) & 0xFFFFFFFFL) << 32
                            | (eb.getInt(1552) & 0xFFFFFFFFL);
                    en.omitFlag = eb.getInt(1556);
                    en.addrCount = eb.getInt(1560);
                    for (int k = 0; k < 5; k++) {
                        en.addr[k] = eb.getInt(1564 + k * 4) & 0xFFFFFFFFL;
                    }
                    p.entries.add(en);
                }
            }

            Entry xmlEntry = null;
            for (Entry en : p.entries) {
                if (en.fileFlag == 2 && en.size > 0) { xmlEntry = en; break; }
            }
            if (xmlEntry != null && xmlEntry.size < 32L * 1024 * 1024
                    && xmlEntry.dataOffset + xmlEntry.size <= raf.length()) {
                raf.seek(xmlEntry.dataOffset);
                byte[] xb = new byte[(int) xmlEntry.size];
                raf.readFully(xb);
                p.xml = decodeXml(xb);
                p.xmlOffset = xmlEntry.dataOffset;
                p.xmlSize = xmlEntry.size;
            } else {
                long xmlStart = (long) p.fileOffset + (long) p.fileCount * ENTRY_SIZE;
                long xmlEnd = raf.length();
                for (Entry en : p.entries) {
                    if (en.dataOffset > xmlStart && en.dataOffset < xmlEnd) xmlEnd = en.dataOffset;
                }
                if (xmlStart > 0 && xmlStart < raf.length() && xmlEnd > xmlStart
                        && (xmlEnd - xmlStart) < 32L * 1024 * 1024) {
                    raf.seek(xmlStart);
                    byte[] xb = new byte[(int) (xmlEnd - xmlStart)];
                    raf.readFully(xb);
                    p.xml = decodeXml(xb);
                }
            }
        } finally {
            try { raf.close(); } catch (IOException ignored) { }
        }

        mergeXml(p);
        return p;
    }

    public static byte[] readEntryData(Pac pac, Entry en) throws IOException {
        if (!en.hasData()) return new byte[0];
        if (en.size > 512L * 1024 * 1024) throw new IOException("Entri terlalu besar: " + en.label());
        RandomAccessFile raf = new RandomAccessFile(pac.file, "r");
        try {
            if (en.dataOffset + en.size > raf.length()) {
                throw new IOException("Offset data di luar file untuk " + en.label());
            }
            raf.seek(en.dataOffset);
            byte[] out = new byte[(int) en.size];
            raf.readFully(out);
            return out;
        } finally {
            try { raf.close(); } catch (IOException ignored) { }
        }
    }

    static String utf16(byte[] buf, int off, int chars) {
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            int lo = buf[off + i * 2] & 0xFF;
            int hi = buf[off + i * 2 + 1] & 0xFF;
            int c = lo | (hi << 8);
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString().trim();
    }

    static String decodeXml(byte[] b) {
        if (b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return clean(new String(b, 2, b.length - 2, Charset.forName("UTF-16LE")));
        }
        if (b.length > 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return clean(new String(b, 2, b.length - 2, Charset.forName("UTF-16BE")));
        }
        int n = Math.min(b.length, 8192);
        int zeroOdd = 0, zeroEven = 0;
        for (int i = 0; i < n; i++) {
            if (b[i] == 0) {
                if ((i & 1) == 1) zeroOdd++; else zeroEven++;
            }
        }
        if (zeroOdd > n / 8) return clean(new String(b, Charset.forName("UTF-16LE")));
        if (zeroEven > n / 8) return clean(new String(b, Charset.forName("UTF-16BE")));
        return clean(new String(b, Charset.forName("UTF-8")));
    }

    static String clean(String s) {
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0) { end = i; break; }
        }
        s = s.substring(0, end);
        int lt = s.indexOf('<');
        if (lt > 0) s = s.substring(lt);
        return s.trim();
    }

    static long parseNum(String v) {
        if (v == null) return -1;
        v = v.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.startsWith("0x")) return Long.parseLong(v.substring(2), 16);
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String attr(org.xmlpull.v1.XmlPullParser x, String name) {
        for (int i = 0; i < x.getAttributeCount(); i++) {
            String an = x.getAttributeName(i);
            if (an != null && an.toLowerCase(Locale.ROOT).equals(name)) {
                return x.getAttributeValue(i);
            }
        }
        return null;
    }

    static String human(long n) {
        if (n >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", n / 1073741824.0);
        if (n >= 1024L * 1024) return String.format(Locale.US, "%.2f MB", n / 1048576.0);
        if (n >= 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return n + " B";
    }

    static void mergeXml(Pac p) {
        if (p.xml == null || p.xml.isEmpty()) return;
        String xml = p.xml;
        if (xml.startsWith("<?xml")) {
            int e = xml.indexOf("?>");
            if (e > 0) xml = xml.substring(e + 2);
        }
        try {
            org.xmlpull.v1.XmlPullParserFactory fac =
                    org.xmlpull.v1.XmlPullParserFactory.newInstance();
            fac.setNamespaceAware(false);
            org.xmlpull.v1.XmlPullParser x = fac.newPullParser();
            x.setInput(new java.io.StringReader(xml));

            Entry cur = null;
            int ev = x.getEventType();
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    String tag = x.getName() == null ? "" : x.getName().toLowerCase(Locale.ROOT);
                    if (tag.equals("file") || tag.equals("partition") || tag.equals("image")) {
                        String id = attr(x, "id");
                        cur = findEntry(p, id);
                        if (cur == null) {
                            cur = new Entry();
                            cur.index = -1;
                            p.entries.add(cur);
                        }
                        if (id != null && !id.isEmpty() && cur.id.isEmpty()) cur.id = id;
                        String al = attr(x, "id_alias");
                        if (al == null) al = attr(x, "idalias");
                        if (al == null) al = attr(x, "id_name");
                        if (al != null && !al.isEmpty()) cur.idName = al;
                        String t = attr(x, "type");
                        if (t != null && !t.isEmpty()) cur.type = t;
                        String ba = attr(x, "base_address");
                        if (ba == null) ba = attr(x, "base");
                        if (ba != null) cur.baseAddress = parseNum(ba);
                        String sz = attr(x, "size");
                        if (sz != null) {
                            long v = parseNum(sz);
                            if (v > 0) cur.partSizeMb = v;
                        }
                    } else if (tag.equals("block")) {
                        if (cur != null) {
                            String ba = attr(x, "base");
                            if (ba == null) ba = attr(x, "base_address");
                            if (ba != null) {
                                long v = parseNum(ba);
                                if (v >= 0) cur.baseAddress = v;
                            }
                            String sz = attr(x, "size");
                            if (sz != null) {
                                long v = parseNum(sz);
                                if (v > 0) cur.blockSize = v;
                            }
                        }
                    } else if (tag.equals("operation")) {
                        if (cur != null) {
                            String t = attr(x, "type");
                            if (t != null) cur.ops.add(t);
                        }
                    }
                } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    String tag = x.getName() == null ? "" : x.getName().toLowerCase(Locale.ROOT);
                    if (tag.equals("file") || tag.equals("partition") || tag.equals("image")) cur = null;
                }
                ev = x.next();
            }
        } catch (Exception ignored) {

        }
    }

    static Entry findEntry(Pac p, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Entry en : p.entries) {
            if (en.id.equalsIgnoreCase(id)) return en;
        }
        for (Entry en : p.entries) {
            if (en.idName.equalsIgnoreCase(id)) return en;
        }
        return null;
    }

    private static final int[] CRC_TABLE = {
            0x0000, 0xC0C1, 0xC181, 0x0140, 0xC301, 0x03C0, 0x0280, 0xC241,
            0xC601, 0x06C0, 0x0780, 0xC741, 0x0500, 0xC5C1, 0xC481, 0x0440,
            0xCC01, 0x0CC0, 0x0D80, 0xCD41, 0x0F00, 0xCFC1, 0xCE81, 0x0E40,
            0x0A00, 0xCAC1, 0xCB81, 0x0B40, 0xC901, 0x09C0, 0x0880, 0xC841,
            0xD801, 0x18C0, 0x1980, 0xD941, 0x1B00, 0xDBC1, 0xDA81, 0x1A40,
            0x1E00, 0xDEC1, 0xDF81, 0x1F40, 0xDD01, 0x1DC0, 0x1C80, 0xDC41,
            0x1400, 0xD4C1, 0xD581, 0x1540, 0xD701, 0x17C0, 0x1680, 0xD641,
            0xD201, 0x12C0, 0x1380, 0xD341, 0x1100, 0xD1C1, 0xD081, 0x1040,
            0xF001, 0x30C0, 0x3180, 0xF141, 0x3300, 0xF3C1, 0xF281, 0x3240,
            0x3600, 0xF6C1, 0xF781, 0x3740, 0xF501, 0x35C0, 0x3480, 0xF441,
            0x3C00, 0xFCC1, 0xFD81, 0x3D40, 0xFF01, 0x3FC0, 0x3E80, 0xFE41,
            0xFA01, 0x3AC0, 0x3B80, 0xFB41, 0x3900, 0xF9C1, 0xF881, 0x3840,
            0x2800, 0xE8C1, 0xE981, 0x2940, 0xEB01, 0x2BC0, 0x2A80, 0xEA41,
            0xEE01, 0x2EC0, 0x2F80, 0xEF41, 0x2D00, 0xEDC1, 0xEC81, 0x2C40,
            0xE401, 0x24C0, 0x2580, 0xE541, 0x2700, 0xE7C1, 0xE681, 0x2640,
            0x2200, 0xE2C1, 0xE381, 0x2340, 0xE101, 0x21C0, 0x2080, 0xE041,
            0xA001, 0x60C0, 0x6180, 0xA141, 0x6300, 0xA3C1, 0xA281, 0x6240,
            0x6600, 0xA6C1, 0xA781, 0x6740, 0xA501, 0x65C0, 0x6480, 0xA441,
            0x6C00, 0xACC1, 0xAD81, 0x6D40, 0xAF01, 0x6FC0, 0x6E80, 0xAE41,
            0xAA01, 0x6AC0, 0x6B80, 0xAB41, 0x6900, 0xA9C1, 0xA881, 0x6840,
            0x7800, 0xB8C1, 0xB981, 0x7940, 0xBB01, 0x7BC0, 0x7A80, 0xBA41,
            0xBE01, 0x7EC0, 0x7F80, 0xBF41, 0x7D00, 0xBDC1, 0xBC81, 0x7C40,
            0xB401, 0x74C0, 0x7580, 0xB541, 0x7700, 0xB7C1, 0xB681, 0x7640,
            0x7200, 0xB2C1, 0xB381, 0x7340, 0xB101, 0x71C0, 0x7080, 0xB041,
            0x5000, 0x90C1, 0x9181, 0x5140, 0x9301, 0x53C0, 0x5280, 0x9241,
            0x9601, 0x56C0, 0x5780, 0x9741, 0x5500, 0x95C1, 0x9481, 0x5440,
            0x9C01, 0x5CC0, 0x5D80, 0x9D41, 0x5F00, 0x9FC1, 0x9E81, 0x5E40,
            0x5A00, 0x9AC1, 0x9B81, 0x5B40, 0x9901, 0x59C0, 0x5880, 0x9841,
            0x8801, 0x48C0, 0x4980, 0x8941, 0x4B00, 0x8BC1, 0x8A81, 0x4A40,
            0x4E00, 0x8EC1, 0x8F81, 0x4F40, 0x8D01, 0x4DC0, 0x4C80, 0x8C41,
            0x4400, 0x84C1, 0x8581, 0x4540, 0x8701, 0x47C0, 0x4680, 0x8641,
            0x8201, 0x42C0, 0x4380, 0x8341, 0x4100, 0x81C1, 0x8081, 0x4040
    };

    public static int crc16(int crc, byte[] data, int off, int len) {
        for (int i = off; i < off + len; i++) {
            crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ (data[i] & 0xFF)) & 0xFF];
        }
        return crc & 0xFFFF;
    }

    public static String verifyCrc(File f) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                long len = raf.length();
                if (len < HDR_SIZE) return "file terlalu kecil";
                byte[] h = new byte[HDR_SIZE];
                raf.readFully(h);
                ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
                int want1 = b.getShort(2120) & 0xFFFF;
                int want2 = b.getShort(2122) & 0xFFFF;

                int c1a = crc16(0, h, 0, 2120);
                int c1b = crc16(0xFFFF, h, 0, 2120);
                String hdr = (c1a == want1 || c1b == want1) ? "OK" : "TIDAK COCOK";

                raf.seek(0);
                byte[] buf = new byte[1 << 20];
                int c2a = 0, c2b = 0xFFFF;
                long done = 0;
                while (done < len) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, len - done));
                    if (n <= 0) break;

                    for (int i = 0; i < n; i++) {
                        long pos = done + i;
                        if (pos == 2122 || pos == 2123) buf[i] = 0;
                    }
                    c2a = crc16(c2a, buf, 0, n);
                    c2b = crc16(c2b, buf, 0, n);
                    done += n;
                }
                String full = (c2a == want2 || c2b == want2) ? "OK" : "TIDAK COCOK";
                return "CRC header: " + hdr + ", CRC file: " + full;
            } finally {
                try { raf.close(); } catch (IOException ignored) { }
            }
        } catch (Exception e) {
            return "gagal verifikasi: " + e.getMessage();
        }
    }
}

package com.unisoc.spdflasher;

import java.io.IOException;
import java.util.Arrays;

public class Bsl {

    public interface Link {
        void write(byte[] data) throws IOException;

        byte[] read(int timeoutMs) throws IOException;
        void flush() throws IOException;
    }

    public static final int CMD_CONNECT = 0x00;
    public static final int CMD_START_DATA = 0x01;
    public static final int CMD_MIDST_DATA = 0x02;
    public static final int CMD_END_DATA = 0x03;
    public static final int CMD_EXEC_DATA = 0x04;
    public static final int CMD_NORMAL_RESET = 0x05;
    public static final int CMD_ERASE_FLASH = 0x06;
    public static final int CMD_CHANGE_BAUD = 0x09;
    public static final int CMD_REPARTITION = 0x0B;
    public static final int CMD_READ_START = 0x10;
    public static final int CMD_READ_MIDST = 0x11;
    public static final int CMD_READ_END = 0x12;
    public static final int CMD_READ_CHIP_UID = 0x1A;
    public static final int CMD_DISABLE_TRANSCODE = 0x21;
    public static final int CMD_READ_FLASH_UID = 0x15;
    public static final int CMD_SET_FIRST_MODE = 0x2A;
    public static final int CMD_READ_PARTITION = 0x2D;
    public static final int CMD_EMMC_CID = 0x43;

    /* extended command FDL2 (spd_cmd.h: BSL_CMD_E_*) */
    public static final int CMD_E_READ_MEM = 0x70;
    public static final int CMD_E_BL = 0x71;
    public static final int CMD_E_RPMB_PAGECOUNT = 0x73;
    public static final int CMD_E_RPMB_COUNTER = 0x74;
    public static final int CMD_E_RPMB_READ = 0x75;
    public static final int CMD_E_RPMB_WRITE = 0x76;
    public static final int CMD_E_EFUSE_READ = 0x78;
    public static final int CMD_E_PWN = 0x79;
    public static final int CMD_E_CHECKPWN = 0x7A;

    public static final int REP_ACK = 0x80;
    public static final int REP_BSL_VER = 0x81;
    public static final int REP_INVALID_CMD = 0x82;
    public static final int REP_UNKNOWN_CHIP = 0x83;
    public static final int REP_VERIFY_OK = 0x85;
    public static final int REP_VERIFY_ERR = 0x8B;
    public static final int REP_READ_DATA = 0x93;
    public static final int REP_LAYOUT_MISMATCH = 0x96;
    public static final int REP_READ_FLASH_UID = 0x9E;
    public static final int REP_READ_PARTITION = 0xBA;
    public static final int REP_EMMC_CID_DATA = 0xC0;
    public static final int REP_E_READ_MEM = 0xE0;
    public static final int REP_E_BL = 0xE1;
    public static final int REP_E_RPMB_PAGECOUNT = 0xE3;
    public static final int REP_E_RPMB_COUNTER = 0xE4;
    public static final int REP_E_RPMB_READ = 0xE5;
    public static final int REP_E_EFUSE_READ = 0xE8;
    public static final int REP_PART_NOT_FOUND = 0xFE;

    public static class Resp {
        public int type;
        public byte[] payload = new byte[0];

        public String hex() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(payload.length, 24); i++) {
                sb.append(String.format("%02X ", payload[i]));
            }
            return sb.toString().trim();
        }

        @Override
        public String toString() {
            return String.format("resp 0x%02X len=%d %s", type, payload.length, hex());
        }
    }

    private final Link link;
    private boolean useSum16 = false;
    private boolean noTranscode = false;
    private final byte[] acc = new byte[1 << 20];
    private int accLen = 0;

    public Bsl(Link link) {
        this.link = link;
    }

    public void setUseSum16(boolean v) { useSum16 = v; }
    public boolean isUseSum16() { return useSum16; }
    public void setNoTranscode(boolean v) { noTranscode = v; }
    public boolean isNoTranscode() { return noTranscode; }

    public static int crc16ccitt(byte[] d, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= (d[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) & 0xFFFF
                                            : (crc << 1) & 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }

    public static int sum16(byte[] d, int off, int len) {
        long s = 0;
        for (int i = off; i + 1 < off + len; i += 2) {
            s += ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
        }
        if ((len & 1) != 0) {
            s += (d[off + len - 1] & 0xFF) << 8;
        }
        while ((s >> 16) != 0) s = (s & 0xFFFF) + (s >> 16);
        return (~(int) s) & 0xFFFF;
    }

    public static long sum32(byte[] d, int off, int len) {
        long s = 0;
        for (int i = 0; i < len; i += 4) {
            long w = 0;
            for (int k = 0; k < 4; k++) {
                w = (w << 8) | ((i + k < len) ? (d[off + i + k] & 0xFF) : (off + i + k >= off + len ? 0 : 0));
            }
            s = (s + w) & 0xFFFFFFFFL;
        }
        return s;
    }

    private byte[] frame(int type, byte[] payload) {
        int plen = payload == null ? 0 : payload.length;
        if (plen > 0xFFFF) {
            throw new IllegalArgumentException(
                    "Payload BSL melebihi 65535 byte (field LENGTH 16-bit): " + plen);
        }
        byte[] body = new byte[4 + plen + 2];
        body[0] = (byte) ((type >> 8) & 0xFF);
        body[1] = (byte) (type & 0xFF);
        body[2] = (byte) ((plen >> 8) & 0xFF);
        body[3] = (byte) (plen & 0xFF);
        if (plen > 0) System.arraycopy(payload, 0, body, 4, plen);
        int ck = useSum16 ? sum16(body, 0, 4 + plen) : crc16ccitt(body, 0, 4 + plen);
        body[4 + plen] = (byte) ((ck >> 8) & 0xFF);
        body[4 + plen + 1] = (byte) (ck & 0xFF);

        if (noTranscode) {
            byte[] out = new byte[body.length + 2];
            out[0] = 0x7E;
            System.arraycopy(body, 0, out, 1, body.length);
            out[out.length - 1] = 0x7E;
            return out;
        }

        byte[] tmp = new byte[body.length * 2 + 2];
        int n = 0;
        tmp[n++] = 0x7E;
        for (byte b : body) {
            if (b == 0x7E) {
                tmp[n++] = 0x7D; tmp[n++] = 0x5E;
            } else if (b == 0x7D) {
                tmp[n++] = 0x7D; tmp[n++] = 0x5D;
            } else {
                tmp[n++] = b;
            }
        }
        tmp[n++] = 0x7E;
        return Arrays.copyOf(tmp, n);
    }

    public boolean send(int type, byte[] payload) throws IOException {
        link.write(frame(type, payload));
        return true;
    }

    public Resp recv(int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Resp r = tryParse();
            if (r != null) return r;
            int wait = (int) Math.max(20, Math.min(500, deadline - System.currentTimeMillis()));
            byte[] chunk = link.read(wait);
            if (chunk != null && chunk.length > 0) {
                if (accLen + chunk.length > acc.length) {
                    accLen = 0;
                }
                int copy = Math.min(chunk.length, acc.length - accLen);
                System.arraycopy(chunk, 0, acc, accLen, copy);
                accLen += copy;
            }
        }
        return null;
    }

    public Resp transact(int type, byte[] payload, int timeoutMs) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            send(type, payload);
            Resp r = recv(timeoutMs);
            if (r != null) return r;
        }
        return null;
    }

    private Resp tryParse() {
        int start = -1;
        for (int i = 0; i < accLen; i++) {
            if ((acc[i] & 0xFF) == 0x7E) { start = i; break; }
        }
        if (start < 0) { accLen = 0; return null; }

        if (noTranscode) {

            if (accLen - start - 1 < 4) return null;
            int plen = ((acc[start + 3] & 0xFF) << 8) | (acc[start + 4] & 0xFF);
            int total = 1 + 4 + plen + 2;
            if (accLen - start < total) return null;
            byte[] body = Arrays.copyOfRange(acc, start + 1, start + 1 + 4 + plen);
            Resp r = new Resp();
            r.type = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
            r.payload = plen > 0 ? Arrays.copyOfRange(body, 4, 4 + plen) : new byte[0];
            shift(start + total);
            return r;
        }

        int esc = 0;
        int i = start + 1;
        int w = 0;
        byte[] out = new byte[acc.length];
        for (; i < accLen; i++) {
            int b = acc[i] & 0xFF;
            if (b == 0x7E) break;
            if (b == 0x7D) { esc = 1; continue; }
            if (esc == 1) {
                if (b == 0x5E) b = 0x7E;
                else if (b == 0x5D) b = 0x7D;
                esc = 0;
            }
            out[w++] = (byte) b;
        }
        if (i >= accLen) return null;
        if (w < 4) { shift(i + 1); return null; }

        int plen = ((out[2] & 0xFF) << 8) | (out[3] & 0xFF);
        if (w < 4 + plen) { shift(i + 1); return null; }
        Resp r = new Resp();
        r.type = ((out[0] & 0xFF) << 8) | (out[1] & 0xFF);
        r.payload = plen > 0 ? Arrays.copyOfRange(out, 4, 4 + plen) : new byte[0];
        shift(i + 1);
        return r;
    }

    private void shift(int n) {
        if (n >= accLen) { accLen = 0; return; }
        System.arraycopy(acc, n, acc, 0, accLen - n);
        accLen -= n;
    }

    public void resetBuffer() { accLen = 0; }

    public boolean handshake(int timeoutMs) throws IOException {
        link.flush();
        resetBuffer();
        byte[] burst = new byte[32];
        Arrays.fill(burst, (byte) 0x7E);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            link.write(burst);
            long sub = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < sub) {
                byte[] r = link.read(100);
                if (r != null && r.length > 1) {
                    int seen = 0;
                    for (byte bb : r) if (bb == 0x7E) seen++;
                    if (seen >= 1) {
                        if (accLen + r.length <= acc.length) {
                            System.arraycopy(r, 0, acc, accLen, r.length);
                            accLen += r.length;
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static String printable(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            int c = b & 0xFF;
            sb.append(c >= 32 && c < 127 ? (char) c : '.');
        }
        return sb.toString();
    }

    public static byte[] be32(long v) {
        return new byte[]{(byte) ((v >> 24) & 0xFF), (byte) ((v >> 16) & 0xFF),
                (byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)};
    }

    public static byte[] le32(long v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)};
    }

    public static long beToLong(byte[] d, int off, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (d[off + i] & 0xFF);
        return v;
    }

    public static byte[] name72(String name) {
        byte[] out = new byte[72];
        for (int i = 0; i < 36 && i < name.length(); i++) {
            char c = name.charAt(i);
            out[i * 2] = (byte) (c & 0xFF);
            out[i * 2 + 1] = (byte) ((c >> 8) & 0xFF);
        }
        return out;
    }

    public boolean isAck(Resp r) {
        return r != null && (r.type == REP_ACK || r.type == REP_VERIFY_OK);
    }

    public static String describeType(int t) {
        switch (t) {
            case REP_ACK: return "ACK";
            case REP_BSL_VER: return "BSL_VER";
            case REP_INVALID_CMD: return "INVALID_CMD";
            case REP_UNKNOWN_CHIP: return "UNKNOWN_CHIP";
            case REP_VERIFY_OK: return "VERIFY_OK";
            case REP_VERIFY_ERR: return "VERIFY_ERROR";
            case REP_READ_DATA: return "READ_DATA";
            case REP_LAYOUT_MISMATCH: return "LAYOUT_MISMATCH(0x96)";
            case REP_READ_FLASH_UID: return "READ_FLASH_UID(0x9E)";
            case REP_READ_PARTITION: return "READ_PARTITION(0xBA)";
            case REP_EMMC_CID_DATA: return "EMMC_CID(0xC0)";
            case REP_E_READ_MEM: return "E_READ_MEM(0xE0)";
            case REP_E_BL: return "E_BL(0xE1)";
            case REP_E_RPMB_PAGECOUNT: return "E_RPMB_PAGECOUNT(0xE3)";
            case REP_E_RPMB_COUNTER: return "E_RPMB_COUNTER(0xE4)";
            case REP_E_RPMB_READ: return "E_RPMB_READ(0xE5)";
            case REP_E_EFUSE_READ: return "E_EFUSE_READ(0xE8)";
            case REP_PART_NOT_FOUND: return "PART_NOT_FOUND(0xFE)";
            default: return String.format("0x%02X", t);
        }
    }
}

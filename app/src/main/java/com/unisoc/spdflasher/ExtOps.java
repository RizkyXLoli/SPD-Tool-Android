package com.unisoc.spdflasher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Operasi lanjutan di atas sesi FDL2 (protokol BSL).
 * Port 1:1 dari fitur spreadtrum_flash / spd_dump v36 (common.c):
 *  - baca / tulis / hapus partisi by name
 *  - patch byte pada offset partisi
 *  - set slot aktif (misc -> bootloader_control)
 *  - matikan / nyalakan dm-verity (vbmeta flag byte 0x7B)
 *  - matikan AVB (patch trustos, payload_offset != sizeof(sys_img_header))
 *  - reboot normal / recovery / fastboot
 */
public class ExtOps {

    public interface Cb {
        void log(String msg);
        void progress(int pct, String stage);
    }

    private static final int CHUNK = 0x8000;        // 32 KiB per MIDST_DATA
    private static final int READ_CHUNK = 0x2000;   // 8 KiB per READ_MIDST
    private static final long MAX_INMEM = 96L * 1024 * 1024;
    private static final long SYS_IMG_HEADER = 0x200;

    private final Bsl bsl;
    private final Cb cb;

    public ExtOps(Bsl bsl, Cb cb) {
        this.bsl = bsl;
        this.cb = cb;
    }

    private void log(String m) { if (cb != null) cb.log(m); }
    private void prog(int p, String s) { if (cb != null) cb.progress(p, s); }

    // ==================== util ====================

    private static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    private static long u32(byte[] d, int off) {
        return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8)
                | ((d[off + 2] & 0xFFL) << 16) | ((d[off + 3] & 0xFFL) << 24);
    }

    private static void put32(byte[] d, int off, long v) {
        d[off] = (byte) (v & 0xFF);
        d[off + 1] = (byte) ((v >> 8) & 0xFF);
        d[off + 2] = (byte) ((v >> 16) & 0xFF);
        d[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private void ack(Bsl.Resp r, String what) throws IOException {
        if (r == null) throw new IOException(what + ": timeout");
        if (!bsl.isAck(r)) throw new IOException(what + ": " + Bsl.describeType(r.type));
    }

    /** Payload select_partition: 72 byte nama + size, mode64 bila > 4 GiB. */
    private static byte[] selPayload(String name, long size) {
        byte[] head = Bsl.name72(name);
        if (size > 0xFFFFFFFFL) {
            return cat(head, Bsl.le32(size & 0xFFFFFFFFL), Bsl.le32(size >>> 32), new byte[8]);
        }
        return cat(head, Bsl.le32(size));
    }

    private static long sum32File(File f) throws IOException {
        long s = 0;
        byte[] buf = new byte[CHUNK];
        try (FileInputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i += 4) {
                    long w = 0;
                    for (int k = 0; k < 4; k++) {
                        w = (w << 8) | (i + k < n ? (buf[i + k] & 0xFF) : 0);
                    }
                    s = (s + w) & 0xFFFFFFFFL;
                }
            }
        }
        return s;
    }

    // ==================== info partisi ====================

    /**
     * Ukuran partisi dibaca dari partisi semu "&lt;name&gt;_size" (FDL2),
     * format isi: "size:<...>: 0x<hex>" — sama seperti get_partition_info() v36.
     */
    public long partitionSize(String name) throws IOException {
        byte[] raw;
        try {
            raw = readPartition(name + "_size", 0x200);
        } catch (IOException e) {
            throw new IOException("Partisi '" + name + "' tidak ditemukan / tanpa info ukuran ("
                    + e.getMessage() + ")");
        }
        String txt = Bsl.printable(raw);
        Matcher m = Pattern.compile("0[xX]([0-9a-fA-F]{1,16})").matcher(txt);
        long v = -1;
        while (m.find()) {
            try {
                v = new BigInteger(m.group(1), 16).longValue();
            } catch (NumberFormatException ignored) {
            }
        }
        if (v <= 0) {
            throw new IOException("Gagal parse ukuran partisi '" + name + "': " + txt.trim());
        }
        return v;
    }

    // ==================== baca ====================

    public byte[] readPartition(String name, long size) throws IOException {
        if (size > MAX_INMEM) {
            throw new IOException("Partisi " + name + " terlalu besar untuk buffer memori ("
                    + size + " byte) — gunakan dump ke file");
        }
        byte[] out = new byte[(int) size];
        readInto(name, size, out);
        return out;
    }

    /** Dump partisi langsung ke file, hemat memori. */
    public void dumpPartition(String name, long size, File out) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[READ_CHUNK];
            long off = 0;
            ack(bsl.transact(Bsl.CMD_READ_START, selPayload(name, size), 30000),
                    "READ_START(" + name + ")");
            while (off < size) {
                int n = (int) Math.min(buf.length, size - off);
                Bsl.Resp r = bsl.transact(Bsl.CMD_READ_MIDST,
                        cat(Bsl.le32(n), Bsl.le32(off)), 30000);
                if (r == null) throw new IOException("READ_MIDST timeout @0x" + Long.toHexString(off));
                if (r.type != Bsl.REP_READ_DATA && r.type != Bsl.REP_ACK) {
                    throw new IOException("READ_MIDST: " + Bsl.describeType(r.type));
                }
                int got = Math.min(r.payload.length, n);
                if (got > 0) fos.write(r.payload, 0, got);
                if (got < n) java.util.Arrays.fill(buf, 0, n - got, (byte) 0);
                off += n;
                prog((int) (100L * off / Math.max(1, size)), "baca " + name);
            }
            bsl.transact(Bsl.CMD_READ_END, new byte[0], 10000);
        }
    }

    private void readInto(String name, long size, byte[] out) throws IOException {
        ack(bsl.transact(Bsl.CMD_READ_START, selPayload(name, size), 30000),
                "READ_START(" + name + ")");
        long off = 0;
        while (off < size) {
            int n = (int) Math.min(READ_CHUNK, size - off);
            Bsl.Resp r = bsl.transact(Bsl.CMD_READ_MIDST,
                    cat(Bsl.le32(n), Bsl.le32(off)), 30000);
            if (r == null) throw new IOException("READ_MIDST timeout @0x" + Long.toHexString(off));
            if (r.type != Bsl.REP_READ_DATA && r.type != Bsl.REP_ACK) {
                throw new IOException("READ_MIDST: " + Bsl.describeType(r.type));
            }
            int got = Math.min(r.payload.length, n);
            if (got > 0) System.arraycopy(r.payload, 0, out, (int) off, got);
            off += n;
            prog((int) (100L * off / Math.max(1, size)), "baca " + name);
        }
        bsl.transact(Bsl.CMD_READ_END, new byte[0], 10000);
    }

    // ==================== tulis / hapus ====================

    public void writePartition(String name, byte[] data) throws IOException {
        long size = data.length;
        long sum = Bsl.sum32(data, 0, data.length);
        byte[] head = size > 0xFFFFFFFFL
                ? cat(Bsl.name72(name), Bsl.le32(size & 0xFFFFFFFFL), Bsl.le32(size >>> 32), new byte[8])
                : cat(Bsl.name72(name), Bsl.le32(size), Bsl.be32(sum));
        ack(bsl.transact(Bsl.CMD_START_DATA, head, 30000), "START_DATA(" + name + ")");
        int off = 0;
        while (off < data.length) {
            int n = Math.min(CHUNK, data.length - off);
            byte[] part = new byte[n];
            System.arraycopy(data, off, part, 0, n);
            ack(bsl.transact(Bsl.CMD_MIDST_DATA, part, 120000), "MIDST_DATA");
            off += n;
            prog((int) (100L * off / Math.max(1, data.length)), "tulis " + name);
        }
        ack(bsl.transact(Bsl.CMD_END_DATA, new byte[0], 60000), "END_DATA(" + name + ")");
    }

    public void writePartition(String name, File src) throws IOException {
        long size = src.length();
        long sum = sum32File(src);
        byte[] head = size > 0xFFFFFFFFL
                ? cat(Bsl.name72(name), Bsl.le32(size & 0xFFFFFFFFL), Bsl.le32(size >>> 32), new byte[8])
                : cat(Bsl.name72(name), Bsl.le32(size), Bsl.be32(sum));
        ack(bsl.transact(Bsl.CMD_START_DATA, head, 30000), "START_DATA(" + name + ")");
        byte[] buf = new byte[CHUNK];
        long sent = 0;
        try (FileInputStream in = new FileInputStream(src)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                byte[] part = new byte[n];
                System.arraycopy(buf, 0, part, 0, n);
                ack(bsl.transact(Bsl.CMD_MIDST_DATA, part, 120000), "MIDST_DATA");
                sent += n;
                prog((int) (100L * sent / Math.max(1, size)), "tulis " + name);
            }
        }
        ack(bsl.transact(Bsl.CMD_END_DATA, new byte[0], 60000), "END_DATA(" + name + ")");
    }

    public void erasePartition(String name) throws IOException {
        ack(bsl.transact(Bsl.CMD_ERASE_FLASH, selPayload(name, 0), 60000),
                "ERASE(" + name + ")");
    }

    /** Patch byte di offset tertentu pada partisi (baca - ubah - tulis ulang). */
    public void patchPartition(String name, long offset, byte[] patch) throws IOException {
        long size = partitionSize(name);
        if (size > MAX_INMEM) {
            throw new IOException("Partisi " + name + " terlalu besar untuk patch langsung ("
                    + size + " byte)");
        }
        if (offset < 0 || offset + patch.length > size) {
            throw new IOException("Offset 0x" + Long.toHexString(offset) + " + " + patch.length
                    + " byte di luar partisi " + name + " (0x" + Long.toHexString(size) + ")");
        }
        log("Membaca " + name + " (" + size + " byte)...");
        byte[] data = readPartition(name, size);
        System.arraycopy(patch, 0, data, (int) offset, patch.length);
        log("Menulis balik " + name + "...");
        writePartition(name, data);
    }

    // ==================== slot A/B ====================

    /**
     * misc[0x800] = struct bootloader_control (32 byte, little endian):
     * 0..3 slot_suffix ("_a"/"_b"), 4..7 magic 0x42414342,
     * 8 version=1, 9 nb_slot=2, 10 recovery_tries, 11 merge_status,
     * 12..15 crc32 (atas 0x1C byte), 16..31 slot_info[4]{prio, tries, ok, verity_corrupt}
     */
    public void setActiveSlot(char slot) throws IOException {
        char s = Character.toLowerCase(slot);
        if (s != 'a' && s != 'b') throw new IOException("Slot harus a atau b");
        byte[] t = new byte[32];
        t[0] = (byte) '_';
        t[1] = (byte) s;
        put32(t, 4, 0x42414342L);
        t[8] = 0x01;
        t[9] = 0x02;
        int act = (s == 'a') ? 0 : 1;
        int ina = 1 - act;
        t[16 + act * 4] = 15;      // priority
        t[16 + act * 4 + 1] = 6;   // tries_remaining
        t[16 + act * 4 + 2] = 0;   // successful_boot
        t[16 + ina * 4] = 14;
        t[16 + ina * 4 + 1] = 1;
        CRC32 crc = new CRC32();
        crc.update(t, 0, 0x1C);
        put32(t, 12, crc.getValue());
        patchPartition("misc", 0x800, t);
        log("Slot aktif diset ke " + s);
    }

    // ==================== dm-verity / AVB ====================

    /** dm_disable(): vbmeta[0x7B] = 1 (HASHTREE_DISABLED). */
    public void disableVerity() throws IOException {
        patchPartition("vbmeta", 0x7B, new byte[]{0x01});
        log("dm-verity dimatikan (vbmeta[0x7B]=1)");
    }

    /** dm_enable(): vbmeta*[0x7B] = 0 untuk semua varian vbmeta. */
    public void enableVerity() throws IOException {
        String[] list = {"vbmeta", "vbmeta_system", "vbmeta_vendor",
                "vbmeta_system_ext", "vbmeta_product", "vbmeta_odm"};
        for (String p : list) {
            try {
                patchPartition(p, 0x7B, new byte[]{0x00});
                log("dm-verity dinyalakan di " + p);
            } catch (IOException e) {
                log("lewati " + p + ": " + e.getMessage());
            }
        }
    }

    /**
     * dis_avb(): patch image trustos (DHTB). Cari MOV Xt, Xn di sekitar
     * signature verifikasi AVB lalu ganti dengan "MOV W0, #0" (0x52800000),
     * sehingga fungsi cek selalu mengembalikan 0.
     *
     * @return true bila image dipatch, false bila sudah dipatch sebelumnya
     */
    public boolean disableAvb(String partName) throws IOException {
        long size = partitionSize(partName);
        if (size > MAX_INMEM) throw new IOException("Partisi " + partName + " terlalu besar");
        log("Membaca " + partName + " (" + size + " byte)...");
        byte[] mem = readPartition(partName, size);
        int n = mem.length;

        int mImgSize = (int) u32(mem, 0);
        long foot = SYS_IMG_HEADER + (mImgSize & 0xFFFFFFFFL);
        if (foot + 4 > n) throw new IOException("Header image tidak valid (ukuran payload "
                + mImgSize + " melebihi partisi)");
        long payloadOffset = u32(mem, (int) foot);
        if (payloadOffset != SYS_IMG_HEADER) {
            log(payloadOffset == 0
                    ? "Image tidak bertanda tangan (payload_offset=0) — tidak perlu dipatch"
                    : "AVB sudah dimatikan sebelumnya (payload_offset=0x"
                    + Long.toHexString(payloadOffset) + ")");
            return false;
        }

        int lo = 0x200;
        int hi = (int) Math.min(mImgSize - 8, n - 8);
        if (hi <= lo) throw new IOException("Payload terlalu kecil untuk dipindai");

        int[] mov = new int[64];
        int movCount = 0;
        for (int i = lo; i <= hi; i++) {
            if ((mem[i] & 0xFF) != 0x3E) continue;
            if ((mem[i + 1] & 0xFF) != 0x03) continue;
            if ((mem[i + 2] & 0xFF) != 0x00) continue;
            if ((mem[i + 3] & 0xFF) != 0x91) continue;
            if (((mem[i - 4] & 0xFF) != 0xE0) || ((mem[i - 5] & 0xFF) != 0x03)) continue;
            if ((mem[i - 7] & 0xFF) != 0x03) continue;
            if (((mem[i - 8] & 0xFF) != 0x08) || ((mem[i - 6] & 0xFF) != 0xAA)) continue;

            int start = i - 4 - 20;
            int found = -1;
            for (int j = start; j < i; j++) {
                if (j < lo || j + 3 >= n) continue;
                if ((mem[j] & 0xFF) == 0x00 && (mem[j + 1] & 0xFF) == 0x00
                        && (mem[j + 2] & 0xFF) == 0x80 && (mem[j + 3] & 0xFF) == 0x52) {
                    found = j;
                }
            }
            if (found >= 0) {
                if (movCount >= mov.length) throw new IOException("Terlalu banyak site MOV (varian image tak dikenal)");
                mov[movCount++] = found;
                i += 16;
            }
        }

        if (movCount == 0) {
            log("Tidak ada instruksi 'MOV W0,#0' yang bisa dipatch — image tidak dikenali");
            return false;
        }
        for (int k = 0; k < movCount; k++) {
            log("Patch MOV @0x" + Integer.toHexString(mov[k]) + " -> 0x52800000");
            put32(mem, mov[k], 0x52800000L);
        }
        log("Menulis balik " + partName + "...");
        writePartition(partName, mem);
        log("AVB dimatikan pada " + partName + " (" + movCount + " site)");
        return true;
    }

    // ==================== reboot ====================

    /** mode: "system" | "recovery" | "fastboot" */
    public void reboot(String mode) throws IOException {
        String m = mode == null ? "system" : mode.toLowerCase();
        if ("recovery".equals(m)) {
            byte[] b = new byte[0x800];
            byte[] tag = "boot-recovery".getBytes("US-ASCII");
            System.arraycopy(tag, 0, b, 0, tag.length);
            patchPartition("misc", 0, b);
            log("misc diset ke recovery, reboot...");
        } else if ("fastboot".equals(m)) {
            byte[] b = new byte[0x800];
            byte[] tag = "boot-recovery".getBytes("US-ASCII");
            byte[] cmd = "recovery\n--fastboot\n".getBytes("US-ASCII");
            System.arraycopy(tag, 0, b, 0, tag.length);
            System.arraycopy(cmd, 0, b, 0x40, cmd.length);
            patchPartition("misc", 0, b);
            log("misc diset ke fastboot, reboot...");
        }
        bsl.transact(Bsl.CMD_NORMAL_RESET, new byte[0], 5000);
    }

    // ==================== info ====================

    public String chipUid() throws IOException {
        Bsl.Resp r = bsl.transact(Bsl.CMD_READ_CHIP_UID, new byte[0], 5000);
        if (r == null) throw new IOException("READ_CHIP_UID: timeout");
        if (r.type != Bsl.REP_ACK && r.type != Bsl.REP_BSL_VER) {
            throw new IOException("READ_CHIP_UID: " + Bsl.describeType(r.type));
        }
        return Bsl.printable(r.payload);
    }
}

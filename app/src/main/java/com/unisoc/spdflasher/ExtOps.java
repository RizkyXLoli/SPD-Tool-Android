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

    // ==================== peta partisi (menu Dump) ====================

    /** Satu entri peta partisi. */
    public static class Part {
        public final int id;
        public final String name;
        public final long size;   // byte, 0 = tidak diketahui

        public Part(int id, String name, long size) {
            this.id = id;
            this.name = name;
            this.size = size;
        }

        public String label() {
            return String.format("%3d  %-22s %10s", id, name, size > 0 ? PacParser.human(size) : "?");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private File workDir;

    /** Direktori kerja untuk file sementara (patch offset / dump besar). */
    public void setWorkDir(File d) {
        this.workDir = d;
    }

    private File tmpFile(String tag) throws IOException {
        File dir = workDir;
        if (dir == null) dir = new File("/data/local/tmp");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("direktori kerja tidak bisa dibuat: " + dir);
        }
        return new File(dir, "spd_" + tag + "_" + System.currentTimeMillis() + ".bin");
    }

    /**
     * Peta partisi. Sumber utama GPT (partisi semu "user_partition"), fallback
     * BSL_CMD_READ_PARTITION — sama seperti partition_list() di spd_dump v36.
     */
    public java.util.List<Part> listPartitions() throws IOException {
        java.util.List<Part> out = new java.util.ArrayList<>();
        try {
            byte[] gpt = readPartition("user_partition", 32 * 1024);
            if (parseGpt(gpt, out) && !out.isEmpty()) {
                log("Peta partisi: GPT (" + out.size() + " entri)");
                return out;
            }
        } catch (IOException e) {
            log("GPT tidak tersedia (" + e.getMessage() + ") — pakai READ_PARTITION");
        }
        out.clear();
        Bsl.Resp r = bsl.transact(Bsl.CMD_READ_PARTITION, new byte[0], 15000);
        if (r == null) throw new IOException("READ_PARTITION: timeout");
        if (r.type != Bsl.REP_READ_PARTITION) {
            throw new IOException("READ_PARTITION: " + Bsl.describeType(r.type));
        }
        parseSprdTable(r.payload, out);
        if (out.isEmpty()) throw new IOException("tabel partisi kosong / format tidak dikenal");
        log("Peta partisi: BSL_READ_PARTITION (" + out.size() + " entri)");
        return out;
    }

    private static long u64(byte[] d, int o) {
        return (u32(d, o) & 0xFFFFFFFFL) | ((u32(d, o + 4) & 0xFFFFFFFFL) << 32);
    }

    private static String utf16le(byte[] d, int off, int chars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chars; i++) {
            int c = (d[off + i * 2] & 0xFF) | ((d[off + i * 2 + 1] & 0xFF) << 8);
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString().trim();
    }

    /** Parse tabel GPT 32 KiB (signature "EFI PART", entri 128 byte, nama UTF-16LE @+56). */
    private static boolean parseGpt(byte[] b, java.util.List<Part> out) {
        if (b.length < 1024) return false;
        int hdr = -1;
        for (int off = 0; off + 92 <= b.length && off <= 4096; off += 512) {
            if (b[off] == 'E' && b[off + 1] == 'F' && b[off + 2] == 'I' && b[off + 3] == ' ') {
                hdr = off;
                break;
            }
        }
        if (hdr < 0) return false;
        long entLba = u64(b, hdr + 72);
        long num = u32(b, hdr + 80);
        long esz = u32(b, hdr + 84);
        if (esz < 128 || num <= 0 || num > 1024) return false;
        long entOff = entLba * 512L;
        if (entOff < 0 || entOff >= b.length) return false;
        int n = (int) Math.min(num, (b.length - entOff) / esz);
        for (int i = 0; i < n; i++) {
            int o = (int) (entOff + i * esz);
            boolean empty = true;
            for (int k = 0; k < 16; k++) {
                if (b[o + k] != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) continue;
            long first = u64(b, o + 32);
            long last = u64(b, o + 40);
            String name = utf16le(b, o + 56, 36);
            if (name.isEmpty()) name = "part" + i;
            out.add(new Part(i, name, last >= first ? (last - first + 1) * 512L : 0));
        }
        return !out.isEmpty();
    }

    /** Parse tabel internal: entri 0x4C byte, nama UTF-16LE 36 char, ukuran u32 @0x48. */
    private void parseSprdTable(byte[] p, java.util.List<Part> out) {
        int n = p.length / 0x4C;
        if (n <= 0) return;
        int divisor = 10;
        for (int i = 0; i < n; i++) {
            long v = u32(p, i * 0x4C + 0x48);
            while (divisor > 0 && (v >> divisor) == 0) divisor--;
        }
        for (int i = 0; i < n; i++) {
            int o = i * 0x4C;
            String name = utf16le(p, o, 36);
            long v = u32(p, o + 0x48);
            long size = divisor >= 20 ? 0 : v << (20 - divisor);
            out.add(new Part(i, name.isEmpty() ? "part" + i : name, size));
        }
    }

    /** Ukuran pasti partisi (info "<name>_size"), fallback ke ukuran peta. */
    public long sizeOf(Part p) {
        try {
            long s = partitionSize(p.name);
            if (s > 0) return s;
        } catch (IOException ignored) {
        }
        return p.size;
    }

    /** Tulis peta partisi ke XML gaya spd_dump (&lt;Partitions&gt;). */
    public void exportPartitionXml(java.util.List<Part> list, File out) throws IOException {
        try (java.io.PrintWriter w = new java.io.PrintWriter(out, "UTF-8")) {
            w.println("<Partitions>");
            for (Part p : list) {
                w.println("    <Partition id=\"" + p.name + "\" size=\"" + (p.size / (1024 * 1024)) + "\"/>");
            }
            w.println("</Partitions>");
        }
        log("XML partisi: " + out.getAbsolutePath());
    }

    /** Repartisi dari daftar partisi (entri 0x4C byte, ukuran u32 @0x48). */
    public int repartition(java.util.List<Part> list) throws IOException {
        int n = list.size();
        if (n == 0) throw new IOException("daftar partisi kosong");
        long max = 0;
        for (Part p : list) max = Math.max(max, p.size);
        int divisor = 10;
        while (divisor > 0 && (max >> divisor) == 0) divisor--;
        byte[] buf = new byte[n * 0x4C];
        for (int i = 0; i < n; i++) {
            Part p = list.get(i);
            int o = i * 0x4C;
            System.arraycopy(Bsl.name72(p.name), 0, buf, o, 72);
            put32(buf, o + 0x48, p.size >> (20 - divisor));
        }
        Bsl.Resp r = bsl.transact(Bsl.CMD_REPARTITION, buf, 30000);
        if (r == null) throw new IOException("REPARTITION: timeout");
        if (!bsl.isAck(r)) throw new IOException("REPARTITION: " + Bsl.describeType(r.type));
        log("Repartisi: " + n + " entri dikirim");
        return n;
    }

    // ==================== baca dengan offset ====================

    /** Baca [offset, offset+size) dari partisi ke memori (READ_START size = offset+size). */
    public byte[] readRange(String name, long offset, long size) throws IOException {
        if (size > MAX_INMEM) throw new IOException("range terlalu besar untuk buffer (" + size + ")");
        byte[] out = new byte[(int) size];
        ack(bsl.transact(Bsl.CMD_READ_START, selPayload(name, offset + size), 30000),
                "READ_START(" + name + ")");
        long off = 0;
        while (off < size) {
            int n = (int) Math.min(READ_CHUNK, size - off);
            Bsl.Resp r = bsl.transact(Bsl.CMD_READ_MIDST,
                    cat(Bsl.le32(n), Bsl.le32(offset + off)), 30000);
            if (r == null) throw new IOException("READ_MIDST timeout @0x" + Long.toHexString(offset + off));
            if (r.type != Bsl.REP_READ_DATA && r.type != Bsl.REP_ACK) {
                throw new IOException("READ_MIDST: " + Bsl.describeType(r.type));
            }
            int got = Math.min(r.payload.length, n);
            if (got > 0) System.arraycopy(r.payload, 0, out, (int) off, got);
            off += n;
            prog((int) (100L * off / Math.max(1, size)), "baca " + name);
        }
        bsl.transact(Bsl.CMD_READ_END, new byte[0], 10000);
        return out;
    }

    /** Dump rentang [offset, offset+size) partisi langsung ke file. */
    public void dumpRange(String name, long offset, long size, File out) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            ack(bsl.transact(Bsl.CMD_READ_START, selPayload(name, offset + size), 30000),
                    "READ_START(" + name + ")");
            long off = 0;
            byte[] zero = new byte[READ_CHUNK];
            while (off < size) {
                int n = (int) Math.min(READ_CHUNK, size - off);
                Bsl.Resp r = bsl.transact(Bsl.CMD_READ_MIDST,
                        cat(Bsl.le32(n), Bsl.le32(offset + off)), 30000);
                if (r == null) throw new IOException("READ_MIDST timeout @0x" + Long.toHexString(offset + off));
                if (r.type != Bsl.REP_READ_DATA && r.type != Bsl.REP_ACK) {
                    throw new IOException("READ_MIDST: " + Bsl.describeType(r.type));
                }
                int got = Math.min(r.payload.length, n);
                if (got > 0) fos.write(r.payload, 0, got);
                if (got < n) fos.write(zero, 0, n - got);
                off += n;
                prog((int) (100L * off / Math.max(1, size)), "dump " + name);
            }
            bsl.transact(Bsl.CMD_READ_END, new byte[0], 10000);
        }
    }

    /** Dump satu partisi penuh ke file (nama file = <name>.img di direktori out). */
    public File dumpPartitionTo(String name, File dir) throws IOException {
        long size = partitionSize(name);
        File out = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_") + ".img");
        log("Dump " + name + " (" + PacParser.human(size) + ") -> " + out.getAbsolutePath());
        dumpRange(name, 0, size, out);
        log("Selesai " + name + ": " + PacParser.human(out.length()));
        return out;
    }

    // ==================== tulis di offset (w_mem_to_part_offset) ====================

    /**
     * Patch byte pada offset partisi. Sama seperti w_mem_to_part_offset():
     * partisi dibaca utuh, dipatch, lalu ditulis kembali.
     */
    public void writeAtOffset(String name, long offset, byte[] data) throws IOException {
        long total = partitionSize(name);
        if (offset < 0 || offset + data.length > total) {
            throw new IOException("offset+data (" + (offset + data.length)
                    + ") melewati ukuran partisi " + name + " (" + total + ")");
        }
        if (total <= MAX_INMEM) {
            byte[] buf = readPartition(name, total);
            System.arraycopy(data, 0, buf, (int) offset, data.length);
            writePartition(name, buf);
        } else {
            File tmp = tmpFile("patch");
            try {
                dumpPartition(name, total, tmp);
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(tmp, "rw")) {
                    raf.seek(offset);
                    raf.write(data);
                }
                writePartition(name, tmp);
            } finally {
                if (!tmp.delete()) log("peringatan: gagal hapus " + tmp.getAbsolutePath());
            }
        }
        log("Patch " + name + " @0x" + Long.toHexString(offset) + " (" + data.length + " byte)");
    }

    // ==================== exploit / UBL (e_*) ====================

    private static byte[] aes128Ecb(byte[] key16, byte[] data) throws IOException {
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key16, "AES"));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new IOException("AES-128 ECB: " + e.getMessage());
        }
    }

    /**
     * UBL / unlock bootloader (do_e_bl): E_BL -> HUK (payload XOR 0x55+i) ->
     * AES-128-ECB("VerifiedBoot-UNLOCK" 64 byte) -> tulis 64 byte ke miscdata@0x2000.
     */
    public String unlockBootloader() throws IOException {
        Bsl.Resp r = bsl.transact(Bsl.CMD_E_BL, new byte[0], 20000);
        if (r == null) throw new IOException("E_BL: timeout");
        if (r.type != Bsl.REP_E_BL) {
            throw new IOException("E_BL: " + Bsl.describeType(r.type) + " " + hexOf(r.payload));
        }
        byte[] huk = new byte[r.payload.length];
        for (int i = 0; i < huk.length; i++) huk[i] = (byte) (r.payload[i] ^ (0x55 + i));
        log("HUK (" + huk.length + " byte): " + hexOf(huk));
        if (huk.length < 16) throw new IOException("E_BL: respons terlalu pendek (" + huk.length + " byte)");
        byte[] pt = new byte[64];
        byte[] magic = "VerifiedBoot-UNLOCK".getBytes("US-ASCII");
        System.arraycopy(magic, 0, pt, 0, magic.length);
        byte[] ct = aes128Ecb(java.util.Arrays.copyOf(huk, 16), pt);
        log("Encrypted: " + hexOf(ct));
        writeAtOffset("miscdata", 0x2000, ct);
        log("UBL: blok unlock ditulis ke miscdata@0x2000");
        return "OK";
    }

    /** ab: 0 = trustos, 1 = trustos_a, 2 = trustos_b. */
    private static byte[] pwnPayload(int ab) {
        byte[] d = new byte[32];
        String nm = ab > 0 ? "trustos_" + (char) ('a' + ab - 1) : "trustos";
        byte[] nb = nm.getBytes();
        System.arraycopy(nb, 0, d, 0, Math.min(nb.length, 32));
        for (int i = 0; i < 32; i++) d[i] ^= (byte) (0x65 + i);
        return d;
    }

    /** e_checkpwn: cek apakah write-protect bisa ditembus (resp 7 = rentan). */
    public String checkPwn(int ab) throws IOException {
        Bsl.Resp r = bsl.transact(Bsl.CMD_E_CHECKPWN, pwnPayload(ab), 20000);
        if (r == null) throw new IOException("E_CHECKPWN: timeout");
        if (!bsl.isAck(r)) {
            return "checkpwn: " + Bsl.describeType(r.type) + " " + hexOf(r.payload);
        }
        long code = r.payload.length >= 4 ? u32(r.payload, 0) : -1;
        return "checkpwn resp=" + code + " data=" + hexOf(r.payload) + (code == 7 ? " (RENTAN)" : "");
    }

    /** e_pwn: jalankan exploit write-protect bila checkpwn melaporkan 7. */
    public String doPwn(int ab) throws IOException {
        Bsl.Resp c = bsl.transact(Bsl.CMD_E_CHECKPWN, pwnPayload(ab), 20000);
        if (c == null) throw new IOException("E_CHECKPWN: timeout");
        if (!bsl.isAck(c)) throw new IOException("E_CHECKPWN: " + Bsl.describeType(c.type));
        long code = c.payload.length >= 4 ? u32(c.payload, 0) : -1;
        if (code != 7) return "tidak rentan (resp=" + code + ")";
        Bsl.Resp r = bsl.transact(Bsl.CMD_E_PWN, pwnPayload(ab), 30000);
        if (r == null) throw new IOException("E_PWN: timeout");
        return (bsl.isAck(r) ? "PWN OK " : "PWN gagal " + Bsl.describeType(r.type)) + " " + hexOf(r.payload);
    }

    /** e_readmem: baca memori mentah ke file (addr/len u32 LE). */
    public void readMemory(long addr, long len, File out) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            long done = 0;
            while (done < len) {
                int n = (int) Math.min(CHUNK, len - done);
                Bsl.Resp r = bsl.transact(Bsl.CMD_E_READ_MEM,
                        cat(Bsl.le32(addr + done), Bsl.le32(n)), 30000);
                if (r == null) throw new IOException("E_READ_MEM: timeout @0x" + Long.toHexString(addr + done));
                if (r.type != Bsl.REP_E_READ_MEM) {
                    throw new IOException("E_READ_MEM: " + Bsl.describeType(r.type));
                }
                int got = Math.min(r.payload.length, n);
                if (got > 0) fos.write(r.payload, 0, got);
                done += got;
                prog((int) (100L * done / Math.max(1, len)), "baca memori");
                if (got != n) break;
            }
            log("e_readmem: addr=0x" + Long.toHexString(addr) + " target=0x" + Long.toHexString(len)
                    + " read=0x" + Long.toHexString(done) + " -> " + out.getAbsolutePath());
        }
    }

    // ==================== info tambahan ====================

    public long rpmbPageCount() throws IOException {
        Bsl.Resp r = bsl.transact(Bsl.CMD_E_RPMB_PAGECOUNT, new byte[0], 15000);
        if (r == null) throw new IOException("E_RPMB_PAGECOUNT: timeout");
        if (r.type != Bsl.REP_E_RPMB_PAGECOUNT) {
            throw new IOException("E_RPMB_PAGECOUNT: " + Bsl.describeType(r.type));
        }
        return r.payload.length >= 4 ? u32(r.payload, 0) : -1;
    }

    public long rpmbCounter() throws IOException {
        Bsl.Resp r = bsl.transact(Bsl.CMD_E_RPMB_COUNTER, new byte[0], 15000);
        if (r == null) throw new IOException("E_RPMB_COUNTER: timeout");
        if (r.type != Bsl.REP_E_RPMB_COUNTER) {
            throw new IOException("E_RPMB_COUNTER: " + Bsl.describeType(r.type));
        }
        return r.payload.length >= 4 ? u32(r.payload, 0) : -1;
    }

    public String emmcCid() throws IOException {
        Bsl.Resp r = bsl.transact(Bsl.CMD_EMMC_CID, new byte[0], 15000);
        if (r == null) throw new IOException("EMMC_CID: timeout");
        if (r.type != Bsl.REP_EMMC_CID_DATA) {
            throw new IOException("EMMC_CID: " + Bsl.describeType(r.type));
        }
        return hexOf(r.payload);
    }

    public String flashUid() throws IOException {
        Bsl.Resp r = bsl.transact(Bsl.CMD_READ_FLASH_UID, new byte[0], 15000);
        if (r == null) throw new IOException("READ_FLASH_UID: timeout");
        if (r.type != Bsl.REP_READ_FLASH_UID) {
            throw new IOException("READ_FLASH_UID: " + Bsl.describeType(r.type));
        }
        return hexOf(r.payload);
    }

    /** pactime: 8 byte @0x81400 miscdata, FILETIME 100ns -> unix detik. */
    public long pactime() throws IOException {
        byte[] d = readRange("miscdata", 0x81400, 8);
        long t = (u32(d, 0) & 0xFFFFFFFFL) | ((u32(d, 4) & 0xFFFFFFFFL) << 32);
        long unix = t == 0 ? 0 : (t / 10000000L) - 11644473600L;
        log("pactime = 0x" + Long.toHexString(t) + " (unix = " + unix + ")");
        return t;
    }

    /** firstmode: tulis (mode + 0x53464D00) u32 ke miscdata@0x2420. */
    public void setFirstMode(int mode) throws IOException {
        writeAtOffset("miscdata", 0x2420, Bsl.le32((mode & 0xFFFFFFFFL) + 0x53464D00L));
        log("firstmode " + mode + " ditulis ke miscdata@0x2420");
    }

    private static String hexOf(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}

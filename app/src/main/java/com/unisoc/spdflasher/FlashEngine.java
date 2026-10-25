package com.unisoc.spdflasher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Alur kerja flashing Unisoc (setara fungsi inti SPD Upgrade Tool):
 *
 *   BROM -> muat FDL1 -> EXEC -> re-enumerate
 *        -> muat FDL2 -> EXEC -> ExecNandInit
 *        -> per partisi: START_DATA -> MIDST_DATA x N -> END_DATA
 *        -> NORMAL_RESET
 *
 * Tidak ada operasi bypass/autentikasi yang diakali: perangkat harus berada
 * dalam mode download (BROM/FDL) dan firmware harus cocok dengan perangkat.
 */
public class FlashEngine {

    public interface Cb {
        void log(String msg);
        void progress(int pct, String stage);
    }

    public interface Opener {
        /** Buka ulang transport setelah perangkat re-enumerate. */
        UsbTransport reopen(int waitMs) throws IOException;
    }

    private volatile boolean cancelled = false;

    public void cancel() { cancelled = true; }

    private void check() throws IOException {
        if (cancelled) throw new IOException("Dibatalkan oleh pengguna");
    }

    private static void log(Cb cb, String s) {
        if (cb != null) cb.log(s);
    }

    private static void prog(Cb cb, int p, String s) {
        if (cb != null) cb.progress(p, s);
    }

    // ================================================================ FLASH

    public void flash(UsbTransport t, Opener opener, PacParser.Pac pac,
                      List<PacParser.Entry> selected, boolean allowRepartition,
                      boolean skipFdlLoad, Cb cb) throws Exception {

        cancelled = false;
        Bsl bsl = new Bsl(t);
        t.setIoTimeout(3000);

        log(cb, "Handshake dengan perangkat...");
        prog(cb, 1, "handshake");
        if (!bsl.handshake(4000)) {
            throw new IOException("Perangkat tidak merespons. Pastikan HP dalam mode "
                    + "download (BROM) dan kabel OTG terpasang dengan benar.");
        }
        log(cb, "Perangkat merespons BSL.");

        // ---- cek apakah sudah di FDL (string 'Spreadtrum' pada balasan)
        boolean inFdl = false;
        try {
            Bsl.Resp v = bsl.transact(Bsl.CMD_CONNECT, new byte[0], 2000);
            if (v != null) {
                String s = Bsl.printable(v.payload);
                log(cb, "CONNECT -> " + Bsl.describeType(v.type)
                        + (s.trim().isEmpty() ? "" : "  \"" + s.trim() + "\""));
                if (s.toLowerCase(Locale.ROOT).contains("spreadtrum")) inFdl = true;
            }
        } catch (IOException e) {
            log(cb, "CONNECT gagal: " + e.getMessage());
        }
        check();

        if (!inFdl && !skipFdlLoad) {
            // ---------- tahap 1: FDL1
            PacParser.Entry fdl1 = findFdl(pac, 1);
            if (fdl1 == null) {
                throw new IOException("FDL1 tidak ada di dalam PAC — pakai PAC firmware "
                        + "lengkap, bukan file partisi tunggal.");
            }
            long addr1 = fdl1.baseAddress >= 0 ? fdl1.baseAddress : fdl1.addr[0];
            if (addr1 <= 0) throw new IOException("Alamat muat FDL1 tidak diketahui");
            byte[] data1 = PacParser.readEntryData(pac, fdl1);
            log(cb, "Muat FDL1 " + PacParser.human(data1.length)
                    + " ke 0x" + Long.toHexString(addr1));
            prog(cb, 5, "muat FDL1");
            downloadAddress(bsl, addr1, data1, 512, false, cb, 5, 12);

            log(cb, "EXEC FDL1...");
            Bsl.Resp r = bsl.transact(Bsl.CMD_EXEC_DATA, Bsl.be32(addr1), 3000);
            log(cb, "  -> " + (r == null ? "tanpa balasan" : Bsl.describeType(r.type)));

            // FDL1 jalan -> perangkat re-enumerate
            log(cb, "Menunggu perangkat re-enumerate...");
            prog(cb, 13, "re-enumerate");
            try { t.close(); } catch (Exception ignored) { }
            Thread.sleep(1500);
            t = opener.reopen(8000);
            bsl = new Bsl(t);
            if (!bsl.handshake(5000)) {
                throw new IOException("Perangkat tidak kembali setelah FDL1 dijalankan");
            }
            log(cb, "Perangkat kembali di mode FDL.");
        }

        // ---------- tahap 2: FDL2
        bsl.setUseSum16(true);
        Bsl.Resp c2 = bsl.transact(Bsl.CMD_CONNECT, new byte[0], 2500);
        log(cb, "CONNECT(FDL2) -> " + (c2 == null ? "tanpa balasan" : Bsl.describeType(c2.type)));

        boolean transcodeSupported = false;
        if (c2 != null && c2.payload.length >= 8) {
            long ver = Bsl.beToLong(c2.payload, 0, 4);
            long flags = Bsl.beToLong(c2.payload, 4, 4);
            log(cb, "  DA_INFO ver=" + ver + " flags=0x" + Long.toHexString(flags));
            if (ver == 1 || ver == 2 || ver == 4) transcodeSupported = flags != 0;
        }

        Bsl.Resp cb2 = bsl.transact(Bsl.CMD_CHANGE_BAUD, Bsl.be32(115200), 2000);
        log(cb, "CHANGE_BAUD -> " + (cb2 == null ? "tanpa balasan (diabaikan)" : Bsl.describeType(cb2.type)));

        PacParser.Entry fdl2 = findFdl(pac, 2);
        if (fdl2 != null && fdl2.hasData()) {
            long addr2 = fdl2.baseAddress >= 0 ? fdl2.baseAddress : fdl2.addr[0];
            byte[] data2 = PacParser.readEntryData(pac, fdl2);
            if (addr2 > 0 && data2.length > 0) {
                log(cb, "Muat FDL2 " + PacParser.human(data2.length)
                        + " ke 0x" + Long.toHexString(addr2));
                prog(cb, 15, "muat FDL2");
                downloadAddress(bsl, addr2, data2, 1024, false, cb, 15, 28);
                log(cb, "EXEC FDL2...");
                Bsl.Resp r2 = bsl.transact(Bsl.CMD_EXEC_DATA, Bsl.be32(addr2), 3000);
                log(cb, "  -> " + (r2 == null ? "tanpa balasan" : Bsl.describeType(r2.type)));
                Thread.sleep(2000);
            }
        } else {
            log(cb, "PAC tanpa FDL2 — mengandalkan FDL yang sudah berjalan.");
        }

        // ---------- ExecNandInit
        prog(cb, 30, "inisialisasi flash");
        Bsl.Resp init = bsl.transact(Bsl.CMD_EXEC_DATA, new byte[0], 60000);
        int initType = init == null ? -1 : init.type;
        log(cb, "ExecNandInit -> " + (init == null ? "tanpa balasan" : Bsl.describeType(initType)));

        if (init != null && init.payload.length >= 8 && !transcodeSupported) {
            long ver = Bsl.beToLong(init.payload, 0, 4);
            long flags = Bsl.beToLong(init.payload, 4, 4);
            if ((ver == 1 || ver == 2 || ver == 4) && flags != 0) transcodeSupported = true;
        }

        if (initType == Bsl.REP_LAYOUT_MISMATCH) {
            log(cb, "Tabel partisi di perangkat tidak cocok dengan firmware.");
            if (!allowRepartition) {
                throw new IOException("Layout partisi berbeda. Aktifkan opsi "
                        + "\"Izinkan repartition\" bila Anda yakin firmware ini untuk perangkat ini.");
            }
            log(cb, "Melakukan repartition sesuai XML PAC...");
            repartition(bsl, pac, cb);
            bsl.transact(Bsl.CMD_EXEC_DATA, new byte[0], 60000);
        }

        if (transcodeSupported) {
            Bsl.Resp dt = bsl.transact(Bsl.CMD_DISABLE_TRANSCODE, new byte[0], 2000);
            if (bsl.isAck(dt)) {
                bsl.setNoTranscode(true);
                log(cb, "Mode transcode dimatikan (transfer lebih cepat).");
            }
        }

        // ---------- tulis partisi
        List<PacParser.Entry> list = selected != null ? selected : new ArrayList<>(pac.entries);
        int total = 0;
        for (PacParser.Entry e : list) if (canFlash(e)) total++;
        int done = 0;
        long t0 = System.currentTimeMillis();

        for (PacParser.Entry e : list) {
            check();
            if (!canFlash(e)) continue;
            boolean isFdl = isFdlEntry(e);
            if (isFdl) continue;

            done++;
            int basePct = 30 + (int) (65.0 * (done - 1) / Math.max(1, total));
            String nm = e.idName.isEmpty() ? e.id : e.idName;
            log(cb, "[" + done + "/" + total + "] " + nm + "  " + PacParser.human(e.size));
            prog(cb, basePct, nm);

            byte[] data = PacParser.readEntryData(pac, e);
            boolean named = !e.idName.isEmpty() && (e.type.contains("2") || e.baseAddress <= 0);
            // LENGTH di header BSL hanya 16-bit -> payload wajib <= 65535 byte
            int chunk = e.type.startsWith("CODE2") || e.type.startsWith("YAFFS") ? 16384 : 32768;

            final int d = done;
            if (named) {
                downloadNamed(bsl, e.idName, data, chunk, cb, basePct,
                        30 + (int) (65.0 * d / Math.max(1, total)));
            } else {
                long addr = e.baseAddress >= 0 ? e.baseAddress : e.addr[0];
                if (addr <= 0) {
                    log(cb, "  dilewati: alamat tidak diketahui");
                    continue;
                }
                downloadAddress(bsl, addr, data, chunk, true, cb, basePct,
                        30 + (int) (65.0 * d / Math.max(1, total)));
            }
        }

        check();
        log(cb, "Semua partisi terkirim dalam "
                + ((System.currentTimeMillis() - t0) / 1000) + " detik. Reset perangkat...");
        prog(cb, 97, "reset");
        bsl.transact(Bsl.CMD_NORMAL_RESET, new byte[0], 3000);
        prog(cb, 100, "selesai");
        log(cb, "SELESAI. Perangkat akan reboot.");
    }

    private boolean canFlash(PacParser.Entry e) {
        return e.hasData() && e.size > 0 && !isFdlEntry(e) && e.checkFlag != 0;
    }

    private boolean isFdlEntry(PacParser.Entry e) {
        String id = (e.id + " " + e.idName).toUpperCase(Locale.ROOT);
        return id.contains("FDL1") || id.contains("FDL2")
                || (e.type.toUpperCase(Locale.ROOT).contains("FDL"));
    }

    public static PacParser.Entry findFdl(PacParser.Pac pac, int stage) {
        for (PacParser.Entry e : pac.entries) {
            String id = (e.id + " " + e.idName).toUpperCase(Locale.ROOT);
            if (id.contains("FDL" + stage)) return e;
        }
        // fallback: cari berdasar nama file
        for (PacParser.Entry e : pac.entries) {
            String n = e.name.toUpperCase(Locale.ROOT);
            if (n.contains("FDL" + stage)) return e;
        }
        return null;
    }

    // =========================================================== transfer

    private void downloadAddress(Bsl bsl, long addr, byte[] data, int chunk,
                                 boolean withChecksum, Cb cb, int p0, int p1)
            throws IOException {
        int size = data.length;
        byte[] start;
        if (withChecksum) {
            long sum = Bsl.sum32(data, 0, size);
            start = concat(Bsl.be32(addr), Bsl.be32(size), Bsl.be32(sum));
        } else {
            start = concat(Bsl.be32(addr), Bsl.be32(size));
        }
        Bsl.Resp r = bsl.transact(Bsl.CMD_START_DATA, start, 30000);
        if (r == null) throw new IOException("START_DATA tidak dijawab (addr 0x"
                + Long.toHexString(addr) + ")");
        if (r.type != Bsl.REP_ACK) {
            throw new IOException("START_DATA ditolak: " + Bsl.describeType(r.type));
        }
        sendChunks(bsl, data, chunk, cb, p0, p1);
        endData(bsl, 60000);
    }

    private void downloadNamed(Bsl bsl, String name, byte[] data, int chunk,
                               Cb cb, int p0, int p1) throws IOException {
        int size = data.length;
        long sum = Bsl.sum32(data, 0, size);
        byte[] start = concat(Bsl.name72(name), Bsl.le32(size), Bsl.be32(sum));
        Bsl.Resp r = bsl.transact(Bsl.CMD_START_DATA, start, 30000);
        if (r == null) throw new IOException("START_DATA(" + name + ") tidak dijawab");
        if (r.type == Bsl.REP_PART_NOT_FOUND) {
            throw new IOException("Partisi \"" + name + "\" tidak ada di tabel FDL2. "
                    + "Perangkat mungkin perlu repartition atau PAC bukan untuk model ini.");
        }
        if (r.type == Bsl.REP_LAYOUT_MISMATCH) {
            throw new IOException("Ukuran partisi \"" + name + "\" tidak cocok dengan perangkat.");
        }
        if (r.type != Bsl.REP_ACK) {
            throw new IOException("START_DATA(" + name + ") ditolak: " + Bsl.describeType(r.type));
        }
        sendChunks(bsl, data, chunk, cb, p0, p1);
        endData(bsl, 120000);
    }

    private void sendChunks(Bsl bsl, byte[] data, int chunk, Cb cb, int p0, int p1)
            throws IOException {
        int off = 0;
        int lastPct = -1;
        while (off < data.length) {
            check();
            int n = Math.min(chunk, data.length - off);
            byte[] part = new byte[n];
            System.arraycopy(data, off, part, 0, n);
            Bsl.Resp r = bsl.transact(Bsl.CMD_MIDST_DATA, part, 120000);
            if (r == null || r.type != Bsl.REP_ACK) {
                throw new IOException("MIDST_DATA gagal pada offset 0x"
                        + Integer.toHexString(off) + " ("
                        + (r == null ? "timeout" : Bsl.describeType(r.type)) + ")");
            }
            off += n;
            int pct = p0 + (int) ((p1 - p0) * (double) off / Math.max(1, data.length));
            if (pct != lastPct) {
                prog(cb, pct, "transfer");
                lastPct = pct;
            }
        }
    }

    private void endData(Bsl bsl, int timeout) throws IOException {
        Bsl.Resp r = bsl.transact(Bsl.CMD_END_DATA, new byte[0], timeout);
        if (r == null) throw new IOException("END_DATA tidak dijawab");
        if (r.type != Bsl.REP_ACK && r.type != Bsl.REP_VERIFY_OK) {
            throw new IOException("Verifikasi data gagal: " + Bsl.describeType(r.type));
        }
    }

    // ======================================================== repartition

    private void repartition(Bsl bsl, PacParser.Pac pac, Cb cb) throws IOException {
        List<PacParser.Entry> parts = new ArrayList<>();
        for (PacParser.Entry e : pac.entries) {
            if (e.idName.isEmpty()) continue;
            if (isFdlEntry(e)) continue;
            if (e.partSizeMb <= 0 && e.blockSize > 0) {
                e.partSizeMb = Math.max(1, e.blockSize / (1024 * 1024));
            }
            parts.add(e);
        }
        if (parts.isEmpty()) {
            log(cb, "  XML tidak memuat tabel partisi — repartition dilewati");
            return;
        }
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        for (PacParser.Entry e : parts) {
            byte[] nm = Bsl.name72(e.idName);
            bo.write(nm, 0, nm.length);
            byte[] sz = Bsl.le32(Math.max(1, e.partSizeMb));
            bo.write(sz, 0, sz.length);
        }
        Bsl.Resp r = bsl.transact(Bsl.CMD_REPARTITION, bo.toByteArray(), 60000);
        log(cb, "REPARTITION (" + parts.size() + " entri) -> "
                + (r == null ? "tanpa balasan" : Bsl.describeType(r.type)));
    }

    // ============================================================== READ

    /** Baca isi partisi dari perangkat dan simpan ke file. */
    public File readPartition(UsbTransport t, String partName, long sizeBytes,
                              File outFile, Cb cb) throws IOException {
        Bsl bsl = new Bsl(t);
        t.setIoTimeout(5000);
        if (!bsl.handshake(4000)) throw new IOException("Perangkat tidak merespons");
        bsl.setUseSum16(true);
        bsl.transact(Bsl.CMD_CONNECT, new byte[0], 2500);

        byte[] start = concat(Bsl.name72(partName), Bsl.le32(sizeBytes));
        Bsl.Resp r = bsl.transact(Bsl.CMD_READ_START, start, 20000);
        if (r == null || (r.type != Bsl.REP_ACK && r.type != Bsl.REP_READ_DATA)) {
            throw new IOException("READ_START(" + partName + ") gagal: "
                    + (r == null ? "timeout" : Bsl.describeType(r.type)));
        }

        FileOutputStream fo = new FileOutputStream(outFile);
        long off = 0;
        int chunk = 4096;
        try {
            while (off < sizeBytes) {
                int n = (int) Math.min(chunk, sizeBytes - off);
                Bsl.Resp rr = bsl.transact(Bsl.CMD_READ_MIDST,
                        concat(Bsl.le32(n), Bsl.le32(off)), 20000);
                if (rr == null) throw new IOException("READ_MIDST timeout di 0x"
                        + Long.toHexString(off));
                if (rr.type != Bsl.REP_READ_DATA && rr.type != Bsl.REP_ACK) {
                    throw new IOException("READ_MIDST gagal: " + Bsl.describeType(rr.type));
                }
                if (rr.payload.length > 0) fo.write(rr.payload, 0, rr.payload.length);
                off += n;
                prog(cb, (int) (100.0 * off / Math.max(1, sizeBytes)), "baca " + partName);
            }
        } finally {
            try { fo.flush(); fo.close(); } catch (Exception ignored) { }
        }
        bsl.transact(Bsl.CMD_READ_END, new byte[0], 5000);
        return outFile;
    }

    /** Baca UID chip (0x1A). */
    public String readChipUid(UsbTransport t, Cb cb) throws IOException {
        Bsl bsl = new Bsl(t);
        t.setIoTimeout(3000);
        if (!bsl.handshake(4000)) throw new IOException("Perangkat tidak merespons");
        bsl.setUseSum16(true);
        bsl.transact(Bsl.CMD_CONNECT, new byte[0], 2500);
        Bsl.Resp r = bsl.transact(Bsl.CMD_READ_CHIP_UID, new byte[0], 5000);
        if (r == null) throw new IOException("Tidak ada balasan UID");
        StringBuilder sb = new StringBuilder();
        for (byte b : r.payload) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    /** Baca info BSL/versi dari balasan handshake. */
    public String readBslVersion(UsbTransport t) throws IOException {
        Bsl bsl = new Bsl(t);
        t.setIoTimeout(3000);
        if (!bsl.handshake(4000)) throw new IOException("Perangkat tidak merespons");
        Bsl.Resp r = bsl.recv(2000);
        if (r == null) return "(tanpa balasan versi)";
        return Bsl.printable(r.payload);
    }

    // ============================================================= util

    static byte[] concat(byte[]... arrs) {
        int n = 0;
        for (byte[] a : arrs) n += a.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] a : arrs) {
            System.arraycopy(a, 0, out, o, a.length);
            o += a.length;
        }
        return out;
    }
}

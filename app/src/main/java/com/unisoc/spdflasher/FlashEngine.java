package com.unisoc.spdflasher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class FlashEngine {

    public interface Cb {
        void log(String msg);
        void progress(int pct, String stage);
    }

    public interface Opener {

        UsbTransport reopen(int waitMs) throws IOException;
    }

    private static final int MISC_SIZE = 4096;

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

    public static final class Session {
        public final UsbTransport transport;
        public final Bsl bsl;
        Session(UsbTransport t, Bsl b) { transport = t; bsl = b; }
    }

    public Session openFdl(UsbTransport t, Opener opener, PacParser.Pac pac,
                           boolean allowRepartition, boolean skipFdlLoad, Cb cb)
            throws Exception {

        cancelled = false;
        Bsl bsl = new Bsl(t);
        t.setIoTimeout(3000);

        log(cb, "Menghubungkan ke HP...");
        prog(cb, 1, "handshake");
        if (!bsl.handshake(4000)) {
            throw new IOException("Perangkat tidak merespons. Pastikan HP dalam mode "
                    + "download dan kabel OTG terpasang dengan benar.");
        }
        log(cb, "HP merespons. Koneksi berhasil.");

        boolean inFdl = false;
        try {
            Bsl.Resp v = bsl.transact(Bsl.CMD_CONNECT, new byte[0], 2000);
            if (v != null) {
                String s = Bsl.printable(v.payload);
                log(cb, "Mode terdeteksi: " + Bsl.describeType(v.type)
                        + (s.trim().isEmpty() ? "" : "  \"" + s.trim() + "\""));
                if (s.toLowerCase(Locale.ROOT).contains("spreadtrum")) inFdl = true;
            }
        } catch (IOException e) {
            log(cb, "Gagal menyambung ke mode download: " + e.getMessage());
        }
        check();

        if (!inFdl && !skipFdlLoad) {

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

            log(cb, "Menjalankan FDL1 (loader tahap 1)...");
            Bsl.Resp r = bsl.transact(Bsl.CMD_EXEC_DATA, Bsl.be32(addr1), 3000);
            log(cb, "  -> " + (r == null ? "tanpa balasan" : Bsl.describeType(r.type)));

            log(cb, "Menunggu perangkat tersambung ulang...");
            prog(cb, 13, "re-enumerate");
            try { t.close(); } catch (Exception ignored) { }
            Thread.sleep(1500);
            t = opener.reopen(8000);
            bsl = new Bsl(t);
            if (!bsl.handshake(5000)) {
                throw new IOException("Perangkat tidak kembali setelah FDL1 dijalankan");
            }
            log(cb, "HP kembali tersambung, siap lanjut.");
        }

        bsl.setUseSum16(true);
        Bsl.Resp c2 = bsl.transact(Bsl.CMD_CONNECT, new byte[0], 2500);
        log(cb, "Lanjut ke FDL2 -> " + (c2 == null ? "tanpa balasan" : Bsl.describeType(c2.type)));

        boolean transcodeSupported = false;
        if (c2 != null && c2.payload.length >= 8) {
            long ver = Bsl.beToLong(c2.payload, 0, 4);
            long flags = Bsl.beToLong(c2.payload, 4, 4);
            log(cb, "  DA_INFO ver=" + ver + " flags=0x" + Long.toHexString(flags));
            if (ver == 1 || ver == 2 || ver == 4) transcodeSupported = flags != 0;
        }

        Bsl.Resp cb2 = bsl.transact(Bsl.CMD_CHANGE_BAUD, Bsl.be32(115200), 2000);
        log(cb, "Menaikkan kecepatan transfer -> " + (cb2 == null ? "tanpa balasan (diabaikan)" : Bsl.describeType(cb2.type)));

        PacParser.Entry fdl2 = findFdl(pac, 2);
        if (fdl2 != null && fdl2.hasData()) {
            long addr2 = fdl2.baseAddress >= 0 ? fdl2.baseAddress : fdl2.addr[0];
            byte[] data2 = PacParser.readEntryData(pac, fdl2);
            if (addr2 > 0 && data2.length > 0) {
                log(cb, "Muat FDL2 " + PacParser.human(data2.length)
                        + " ke 0x" + Long.toHexString(addr2));
                prog(cb, 15, "muat FDL2");
                downloadAddress(bsl, addr2, data2, 1024, false, cb, 15, 28);
                log(cb, "Menjalankan FDL2 (loader tahap 2)...");
                Bsl.Resp r2 = bsl.transact(Bsl.CMD_EXEC_DATA, Bsl.be32(addr2), 3000);
                log(cb, "  -> " + (r2 == null ? "tanpa balasan" : Bsl.describeType(r2.type)));
                Thread.sleep(2000);
            }
        } else {
            log(cb, "Firmware ini tanpa FDL2 — memakai loader yang sudah berjalan.");
        }

        prog(cb, 30, "inisialisasi flash");
        Bsl.Resp init = bsl.transact(Bsl.CMD_EXEC_DATA, new byte[0], 60000);
        int initType = init == null ? -1 : init.type;
        log(cb, "Menyiapkan penyimpanan HP -> " + (init == null ? "tanpa balasan" : Bsl.describeType(initType)));

        if (init != null && init.payload.length >= 8 && !transcodeSupported) {
            long ver = Bsl.beToLong(init.payload, 0, 4);
            long flags = Bsl.beToLong(init.payload, 4, 4);
            if ((ver == 1 || ver == 2 || ver == 4) && flags != 0) transcodeSupported = true;
        }

        if (initType == Bsl.REP_LAYOUT_MISMATCH) {
            log(cb, "Susunan partisi di HP berbeda dengan firmware.");
            if (!allowRepartition) {
                throw new IOException("Layout partisi berbeda. Aktifkan opsi "
                        + "\"Izinkan repartition\" bila Anda yakin firmware ini untuk perangkat ini.");
            }
            log(cb, "Menyusun ulang partisi mengikuti firmware...");
            repartition(bsl, pac, cb);
            bsl.transact(Bsl.CMD_EXEC_DATA, new byte[0], 60000);
        }

        if (transcodeSupported) {
            Bsl.Resp dt = bsl.transact(Bsl.CMD_DISABLE_TRANSCODE, new byte[0], 2000);
            if (bsl.isAck(dt)) {
                bsl.setNoTranscode(true);
                log(cb, "Mode transcode dimatikan supaya transfer lebih cepat.");
            }
        }

        return new Session(t, bsl);
    }

    public void flash(UsbTransport t, Opener opener, PacParser.Pac pac,
                      List<PacParser.Entry> selected, boolean allowRepartition,
                      boolean skipFdlLoad, Cb cb) throws Exception {

        Session s = openFdl(t, opener, pac, allowRepartition, skipFdlLoad, cb);
        Bsl bsl = s.bsl;

        List<PacParser.Entry> list = selected != null ? selected : new ArrayList<>(pac.entries);
        int total = 0;
        for (PacParser.Entry e : list) if (canFlash(e)) total++;
        int done = 0;
        long t0 = System.currentTimeMillis();

        for (PacParser.Entry e : list) {
            check();
            if (!canFlash(e)) continue;
            if (isFdlEntry(e)) continue;

            done++;
            int basePct = 30 + (int) (65.0 * (done - 1) / Math.max(1, total));
            String nm = e.idName.isEmpty() ? e.id : e.idName;
            log(cb, "[" + done + "/" + total + "] " + nm + "  " + PacParser.human(e.size));
            prog(cb, basePct, nm);

            boolean named = !e.idName.isEmpty() && (e.type.contains("2") || e.baseAddress <= 0);
            int chunk = e.type.startsWith("CODE2") || e.type.startsWith("YAFFS") ? 16384 : 32768;

            final int d = done;
            if (named) {
                downloadNamedStream(bsl, pac, e, chunk, cb, basePct,
                        30 + (int) (65.0 * d / Math.max(1, total)));
            } else {
                byte[] data = PacParser.readEntryData(pac, e);
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
        log(cb, "Semua partisi selesai dikirim dalam "
                + ((System.currentTimeMillis() - t0) / 1000) + " detik. Reset perangkat...");
        prog(cb, 97, "reset");
        bsl.transact(Bsl.CMD_NORMAL_RESET, new byte[0], 3000);
        prog(cb, 100, "selesai");
        log(cb, "SELESAI. HP akan menyala ulang sendiri. Jangan cabut kabel.");
    }

    public void forceFastboot(UsbTransport t, Opener opener, PacParser.Pac pac,
                              String command, Cb cb) throws Exception {
        Session s = openFdl(t, opener, pac, false, false, cb);
        try {
            log(cb, "Menulis perintah boot ke partisi misc...");
            prog(cb, 80, "tulis misc");
            writeMiscCommand(s.bsl, command, cb);
            log(cb, "Perintah \"" + command + "\" tersimpan. HP akan restart "
                    + "dan langsung masuk fastboot.");
            prog(cb, 95, "restart");
            s.bsl.transact(Bsl.CMD_NORMAL_RESET, new byte[0], 3000);
            prog(cb, 100, "selesai");
            log(cb, "Selesai. Cabut kabel OTG lalu sambungkan lagi — HP akan "
                    + "terdeteksi sebagai perangkat fastboot.");
        } finally {
            try { s.transport.close(); } catch (Exception ignored) { }
        }
    }

    private void writeMiscCommand(Bsl bsl, String command, Cb cb) throws IOException {
        byte[] misc = new byte[MISC_SIZE];
        byte[] cmd = command.getBytes("US-ASCII");
        System.arraycopy(cmd, 0, misc, 0, Math.min(cmd.length, 31));

        long sum = Bsl.sum32(misc, 0, misc.length);
        byte[] start = concat(Bsl.name72("misc"), Bsl.le32(misc.length), Bsl.be32(sum));
        Bsl.Resp r = bsl.transact(Bsl.CMD_START_DATA, start, 30000);
        if (r == null) throw new IOException("Perangkat tidak menjawab saat menulis misc");
        if (r.type == Bsl.REP_PART_NOT_FOUND) {
            throw new IOException("Partisi \"misc\" tidak ditemukan di HP ini.");
        }
        if (r.type != Bsl.REP_ACK) {
            throw new IOException("Penulisan misc ditolak: " + Bsl.describeType(r.type));
        }
        sendChunks(bsl, misc, 1024, cb, 80, 95);
        endData(bsl, 60000);
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

        for (PacParser.Entry e : pac.entries) {
            String n = e.name.toUpperCase(Locale.ROOT);
            if (n.contains("FDL" + stage)) return e;
        }
        return null;
    }

    private static final class EntrySrc implements java.io.Closeable {
        final RandomAccessFile raf;
        final long start;
        final long size;

        EntrySrc(PacParser.Pac pac, PacParser.Entry e) throws IOException {
            raf = new RandomAccessFile(pac.file, "r");
            start = e.dataOffset;
            size = e.size;
            if (start < 0 || start + size > raf.length()) {
                raf.close();
                throw new IOException("Offset data di luar file untuk " + e.label());
            }
        }

        void seek(long off) throws IOException { raf.seek(start + off); }

        int read(byte[] b, int off, int len) throws IOException {
            return raf.read(b, off, len);
        }

        @Override public void close() {
            try { raf.close(); } catch (IOException ignored) { }
        }
    }

    static final class Sum32 {
        private long sum = 0;
        private final byte[] rem = new byte[4];
        private int remLen = 0;

        void update(byte[] d, int off, int len) {
            int i = off;
            int end = off + len;
            if (remLen > 0) {
                while (remLen < 4 && i < end) rem[remLen++] = d[i++];
                if (remLen < 4) return;
                sum = (sum + word(rem, 0)) & 0xFFFFFFFFL;
                remLen = 0;
            }
            int last = end - 3;
            for (; i < last; i += 4) sum = (sum + word(d, i)) & 0xFFFFFFFFL;
            for (; i < end; i++) rem[remLen++] = d[i];
        }

        private static long word(byte[] d, int i) {
            return ((d[i] & 0xFFL) << 24) | ((d[i + 1] & 0xFFL) << 16)
                    | ((d[i + 2] & 0xFFL) << 8) | (d[i + 3] & 0xFFL);
        }

        long value() {
            if (remLen > 0) {
                long w = 0;
                for (int k = 0; k < 4; k++) {
                    w = (w << 8) | (k < remLen ? (rem[k] & 0xFFL) : 0);
                }
                sum = (sum + w) & 0xFFFFFFFFL;
                remLen = 0;
            }
            return sum;
        }
    }

    private static final int COPY_BUF = 1 << 20;

    private static byte[] namedStart(String name, long size, long checksum) {
        if (size > 0xFFFFFFFFL) {
            byte[] p = new byte[72 + 16];
            System.arraycopy(Bsl.name72(name), 0, p, 0, 72);
            byte[] lo = Bsl.le32(size & 0xFFFFFFFFL);
            byte[] hi = Bsl.le32((size >>> 32) & 0xFFFFFFFFL);
            System.arraycopy(lo, 0, p, 72, 4);
            System.arraycopy(hi, 0, p, 76, 4);
            return p;
        }
        return concat(Bsl.name72(name), Bsl.le32(size), Bsl.be32(checksum));
    }

    private void downloadNamedStream(Bsl bsl, PacParser.Pac pac, PacParser.Entry e,
                                     int chunk, Cb cb, int p0, int p1) throws IOException {
        String name = e.idName;
        EntrySrc src = new EntrySrc(pac, e);
        try {
            Sum32 cs = new Sum32();
            byte[] buf = new byte[COPY_BUF];
            long off = 0;
            while (off < src.size) {
                check();
                int n = src.read(buf, 0, (int) Math.min(buf.length, src.size - off));
                if (n <= 0) break;
                cs.update(buf, 0, n);
                off += n;
                prog(cb, p0 + (int) ((p1 - p0) * 0.05 * off / Math.max(1, src.size)), "cek " + name);
            }
            long checksum = cs.value();
            log(cb, "  checksum " + name + " = 0x" + Long.toHexString(checksum)
                    + (src.size > 0xFFFFFFFFL ? " (mode 64-bit)" : ""));

            byte[] start = namedStart(name, src.size, checksum);
            Bsl.Resp r = bsl.transact(Bsl.CMD_START_DATA, start, 60000);
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
            src.seek(0);
            sendChunksStream(bsl, src, chunk, cb, p0 + (int) ((p1 - p0) * 0.05), p1);
            endData(bsl, 180000);
        } finally {
            src.close();
        }
    }

    private void sendChunksStream(Bsl bsl, EntrySrc src, int chunk,
                                  Cb cb, int p0, int p1) throws IOException {
        byte[] buf = new byte[chunk];
        long off = 0;
        int lastPct = -1;
        while (off < src.size) {
            check();
            int want = (int) Math.min(chunk, src.size - off);
            int got = 0;
            while (got < want) {
                int n = src.read(buf, got, want - got);
                if (n <= 0) throw new IOException("PAC terpotong di offset 0x"
                        + Long.toHexString(off + got));
                got += n;
            }
            byte[] part = (got == chunk) ? buf : copyOf(buf, got);
            Bsl.Resp r = bsl.transact(Bsl.CMD_MIDST_DATA, part, 180000);
            if (r == null || r.type != Bsl.REP_ACK) {
                throw new IOException("MIDST_DATA gagal pada offset 0x"
                        + Long.toHexString(off) + " ("
                        + (r == null ? "timeout" : Bsl.describeType(r.type)) + ")");
            }
            off += got;
            int pct = p0 + (int) ((p1 - p0) * (double) off / Math.max(1, src.size));
            if (pct != lastPct) {
                prog(cb, pct, "transfer");
                lastPct = pct;
            }
        }
    }

    private static byte[] copyOf(byte[] src, int n) {
        byte[] out = new byte[n];
        System.arraycopy(src, 0, out, 0, n);
        return out;
    }

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
        log(cb, "Menyusun ulang partisi (" + parts.size() + " entri) -> "
                + (r == null ? "tanpa balasan" : Bsl.describeType(r.type)));
    }

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

    public String readBslVersion(UsbTransport t) throws IOException {
        Bsl bsl = new Bsl(t);
        t.setIoTimeout(3000);
        if (!bsl.handshake(4000)) throw new IOException("Perangkat tidak merespons");
        Bsl.Resp r = bsl.recv(2000);
        if (r == null) return "(tanpa balasan versi)";
        return Bsl.printable(r.payload);
    }

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

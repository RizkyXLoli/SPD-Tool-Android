import com.unisoc.spdflasher.Bsl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/** Uji round-trip framing BSL: frame() -> recv() harus menghasilkan paket identik. */
public class BslLoopTest {

    /** Link palsu: byte yang ditulis dikembalikan sebagai balasan. */
    static class Loop implements Bsl.Link {
        final Deque<Byte> q = new ArrayDeque<>();
        ByteArrayOutputStream written = new ByteArrayOutputStream();

        public void write(byte[] d) { written.write(d, 0, d.length); }

        public byte[] read(int timeout) {
            if (q.isEmpty()) return new byte[0];
            int n = Math.min(q.size(), 4096);
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = q.poll();
            return out;
        }

        public void flush() { }

        void feed(byte[] b) { for (byte x : b) q.add(x); }
    }

    static byte[] frame(Bsl b, int type, byte[] p) throws Exception {
        java.lang.reflect.Method m = Bsl.class.getDeclaredMethod("frame", int.class, byte[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(b, type, p);
    }

    static int fail = 0;

    static void check(String what, boolean ok, String extra) {
        System.out.printf("%-46s %s %s%n", what, ok ? "PASS" : "FAIL", extra);
        if (!ok) fail++;
    }

    public static void main(String[] a) throws Exception {
        // ---- kasus 1: payload pendek tanpa escape
        test("ACK kosong (crc)", new byte[0], false);
        test("payload 7E/7D (crc)", new byte[]{0x7E, 0x7D, 0x00, 0x7E, 0x7D}, false);
        test("payload 1KB acak (crc)", rand(1024), false);
        // ---- kasus 2: sum16 (tahap FDL2)
        test("payload 512 (sum16)", rand(512), true);
        test("payload 7E (sum16)", new byte[]{0x7E}, true);
        test("payload 64KB-1 (sum16)", rand(65535), true);
        // batas keras: LENGTH 16-bit -> 65536 harus ditolak
        try {
            Loop lx = new Loop();
            Bsl bx = new Bsl(lx);
            frame(bx, 0x02, rand(65536));
            check("payload 65536 ditolak", false, "tidak melempar exception");
        } catch (java.lang.reflect.InvocationTargetException e) {
            IllegalArgumentException iae = e.getCause() instanceof IllegalArgumentException
                    ? (IllegalArgumentException) e.getCause() : null;
            check("payload 65536 ditolak", iae != null,
                    iae == null ? "exception lain: " + e.getCause() : "IllegalArgumentException");
        }

        // ---- kasus 3: dua frame berurutan dalam satu buffer
        Loop l = new Loop();
        Bsl b = new Bsl(l);
        l.feed(frame(b, 0x80, new byte[]{1, 2, 3}));
        l.feed(frame(b, 0x93, new byte[]{9, 8, 7, 6}));
        Bsl.Resp r1 = b.recv(500);
        Bsl.Resp r2 = b.recv(500);
        check("dua frame berurutan: #1", r1 != null && r1.type == 0x80 && r1.payload.length == 3, r1 == null ? "null" : r1.toString());
        check("dua frame berurutan: #2", r2 != null && r2.type == 0x93 && r2.payload.length == 4, r2 == null ? "null" : r2.toString());

        // ---- kasus 4: byte sampah sebelum frame (harus disinkronkan)
        Loop l2 = new Loop();
        Bsl b2 = new Bsl(l2);
        l2.feed(new byte[]{0x00, 0x11, 0x22, 0x33});
        l2.feed(frame(b2, 0x81, new byte[]{0x41, 0x42}));
        Bsl.Resp r3 = b2.recv(500);
        check("sinkron setelah byte sampah", r3 != null && r3.type == 0x81, r3 == null ? "null" : r3.toString());

        // ---- kasus 5: frame terpotong lalu dilengkapi
        Loop l3 = new Loop();
        Bsl b3 = new Bsl(l3);
        byte[] f = frame(b3, 0x93, rand(100));
        byte[] half1 = new byte[f.length / 2];
        byte[] half2 = new byte[f.length - half1.length];
        System.arraycopy(f, 0, half1, 0, half1.length);
        System.arraycopy(f, half1.length, half2, 0, half2.length);
        l3.feed(half1);
        Bsl.Resp r4 = b3.recv(200);
        l3.feed(half2);
        Bsl.Resp r5 = b3.recv(500);
        check("frame terpotong: belum lengkap", r4 == null, "r=" + r4);
        check("frame terpotong: lengkap", r5 != null && r5.type == 0x93 && r5.payload.length == 100,
                r5 == null ? "null" : "len=" + r5.payload.length);

        // ---- kasus 6: no-transcode mode
        Loop l4 = new Loop();
        Bsl b4 = new Bsl(l4);
        b4.setNoTranscode(true);
        byte[] raw = frame(b4, 0x80, new byte[]{0x7E, 0x00});
        l4.feed(raw);
        Bsl.Resp r6 = b4.recv(500);
        check("mode no-transcode", r6 != null && r6.type == 0x80 && r6.payload.length == 2,
                r6 == null ? "null" : r6.toString());

        System.out.println("\n" + (fail == 0 ? "SEMUA PASS" : fail + " GAGAL"));
        if (fail > 0) System.exit(1);
    }

    static void test(String name, byte[] payload, boolean sum16) throws Exception {
        Loop l = new Loop();
        Bsl b = new Bsl(l);
        b.setUseSum16(sum16);
        byte[] f = frame(b, 0x93, payload);
        l.feed(f);
        Bsl.Resp r = b.recv(500);
        boolean ok = r != null && r.type == 0x93 && java.util.Arrays.equals(r.payload, payload);
        check(name, ok, "frame=" + f.length + "B payload=" + payload.length + "B");
    }

    static byte[] rand(int n) {
        byte[] b = new byte[n];
        java.util.Random rnd = new java.util.Random(n);
        rnd.nextBytes(b);
        // sisipkan byte khusus agar escape benar-benar diuji
        for (int i = 0; i < n; i += 97) b[i] = 0x7E;
        for (int i = 1; i < n; i += 89) b[i] = 0x7D;
        return b;
    }
}

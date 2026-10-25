import com.unisoc.spdflasher.Bsl;
import com.unisoc.spdflasher.PacParser;

import java.io.File;
import java.util.Locale;

/** Harness JVM untuk PacParser (dijalankan di desktop, bukan Android). */
public class PacTest {
    public static void main(String[] a) throws Exception {
        File f = new File(a.length > 0 ? a[0] : "test/fake.pac");
        PacParser.Pac p = PacParser.parse(f);

        System.out.println("== HEADER ==");
        System.out.println("version       : " + p.version);
        System.out.println("product       : " + p.productName);
        System.out.println("productVer    : " + p.productVersion);
        System.out.println("alias         : " + p.productAlias);
        System.out.println("size          : " + p.size);
        System.out.println("fileCount     : " + p.fileCount);
        System.out.println("fileOffset    : " + p.fileOffset);
        System.out.println("mode          : " + p.mode + " -> " + p.modeStr());
        System.out.println("flashType     : " + p.flashType + " -> " + p.flashTypeStr());
        System.out.println("magic ok      : " + p.magicOk());
        System.out.println("crc           : " + PacParser.verifyCrc(f));
        System.out.println("xml chars     : " + p.xml.length());

        System.out.println("\n== ENTRI (" + p.entries.size() + ") ==");
        System.out.printf(Locale.US, "%-12s %-16s %-10s %10s %10s %10s %-14s %s%n",
                "id", "idName", "type", "size", "dataOff", "base", "label", "flags");
        for (PacParser.Entry e : p.entries) {
            System.out.printf(Locale.US, "%-12s %-16s %-10s %10d %10d %10d %-14s f=%d c=%d%n",
                    e.id, e.idName, e.type, e.size, e.dataOffset, e.baseAddress,
                    e.label(), e.fileFlag, e.checkFlag);
        }

        System.out.println("\n== FDL ==");
        PacParser.Entry f1 = findFdl(p, 1);
        PacParser.Entry f2 = findFdl(p, 2);
        System.out.println("FDL1: " + (f1 == null ? "null" : f1.label() + " " + f1.size + " @0x" + Long.toHexString(f1.baseAddress)));
        System.out.println("FDL2: " + (f2 == null ? "null" : f2.label() + " " + f2.size + " @0x" + Long.toHexString(f2.baseAddress)));

        System.out.println("\n== DATA (5 byte pertama tiap entri) ==");
        for (PacParser.Entry e : p.entries) {
            if (!e.hasData()) continue;
            byte[] d = PacParser.readEntryData(p, e);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(5, d.length); i++) sb.append(String.format("%02X ", d[i]));
            System.out.printf(Locale.US, "%-10s len=%d  %s%n", e.label(), d.length, sb);
        }

        System.out.println("\n== BSL CHECKSUM ==");
        byte[] t = "123456789".getBytes("US-ASCII");
        System.out.printf(Locale.US, "crc16ccitt(\"123456789\") = 0x%04X (harap 0x31C3)%n",
                Bsl.crc16ccitt(t, 0, t.length));
        byte[] d4 = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        System.out.printf(Locale.US, "sum32(FFFFFFFF) = 0x%08X%n", Bsl.sum32(d4, 0, 4));
        System.out.printf(Locale.US, "sum16(FFFF FFFF) = 0x%04X%n", Bsl.sum16(d4, 0, 4));

        System.out.println("\n== FRAMING ==");
        Bsl.Link dummy = new Bsl.Link() {
            public void write(byte[] d) {}
            public byte[] read(int t) { return new byte[0]; }
            public void flush() {}
        };
        Bsl bsl = new Bsl(dummy);
        byte[] fr = bslFrame(bsl, 0x80, new byte[]{0x7E, 0x7D, 0x00});
        System.out.print("frame ACK payload 7E 7D 00 = ");
        for (byte b : fr) System.out.printf("%02X ", b);
        System.out.println();
    }

    /** salinan logika FlashEngine.findFdl (FlashEngine butuh kelas Android) */
    static PacParser.Entry findFdl(PacParser.Pac pac, int stage) {
        for (PacParser.Entry e : pac.entries) {
            String id = (e.id + " " + e.idName).toUpperCase(Locale.ROOT);
            if (id.contains("FDL" + stage)) return e;
        }
        return null;
    }

    /** akses frame() lewat refleksi agar tidak perlu mengekspos API internal */
    static byte[] bslFrame(Bsl bsl, int type, byte[] payload) throws Exception {
        java.lang.reflect.Method m = Bsl.class.getDeclaredMethod("frame", int.class, byte[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(bsl, type, payload);
    }
}

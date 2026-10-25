package com.unisoc.spdflasher;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;

public class Fastboot {

    private static final int PKT = 64;

    private final UsbDeviceConnection conn;
    private final UsbInterface itf;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;
    private final byte[] buf = new byte[PKT];
    private final StringBuilder info = new StringBuilder();

    private Fastboot(UsbDeviceConnection c, UsbInterface i, UsbEndpoint in, UsbEndpoint out) {
        conn = c;
        itf = i;
        epIn = in;
        epOut = out;
    }

    public static UsbInterface findInterface(UsbDevice d) {
        if (d == null) return null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            if (it.getInterfaceClass() == 0xFF && it.getInterfaceSubclass() == 0x42
                    && it.getInterfaceProtocol() == 0x03) return it;
        }
        return null;
    }

    public static boolean isFastboot(UsbDevice d) {
        return findInterface(d) != null;
    }

    public static UsbDevice find(UsbManager mgr) {
        if (mgr == null) return null;
        HashMap<String, UsbDevice> map = mgr.getDeviceList();
        UsbDevice fallback = null;
        for (UsbDevice d : map.values()) {
            if (!isFastboot(d)) continue;
            if (mgr.hasPermission(d)) return d;
            if (fallback == null) fallback = d;
        }
        return fallback;
    }

    public static Fastboot open(UsbManager mgr, UsbDevice dev) throws IOException {
        UsbInterface it = findInterface(dev);
        if (it == null) throw new IOException("Perangkat ini tidak dalam mode fastboot");
        UsbDeviceConnection c = mgr.openDevice(dev);
        if (c == null) throw new IOException("Gagal membuka perangkat USB");
        UsbEndpoint in = null, out = null;
        for (int e = 0; e < it.getEndpointCount(); e++) {
            UsbEndpoint ep = it.getEndpoint(e);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
            else out = ep;
        }
        if (in == null || out == null) {
            c.close();
            throw new IOException("Endpoint fastboot tidak lengkap");
        }
        if (!c.claimInterface(it, true)) {
            c.close();
            throw new IOException("Interface fastboot gagal dipakai");
        }
        return new Fastboot(c, it, in, out);
    }

    private void send(String s) throws IOException {
        byte[] p = new byte[PKT];
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, p, 0, Math.min(b.length, PKT));
        if (conn.bulkTransfer(epOut, p, PKT, 15000) < 0) {
            throw new IOException("Gagal mengirim perintah ke perangkat fastboot");
        }
    }

    private String recv(int timeout) throws IOException {
        int n = conn.bulkTransfer(epIn, buf, PKT, timeout);
        if (n <= 0) throw new IOException("Perangkat fastboot tidak menjawab");
        int len = 0;
        while (len < n && buf[len] != 0) len++;
        return new String(buf, 0, len, StandardCharsets.US_ASCII);
    }

    private String raw(String cmd) throws IOException {
        send(cmd);
        for (int i = 0; i < 5000; i++) {
            String r = recv(20000);
            if (r.startsWith("INFO")) {
                if (info.length() > 0) info.append('\n');
                info.append(r.length() > 4 ? r.substring(4) : "");
                continue;
            }
            return r;
        }
        throw new IOException("Balasan fastboot terlalu panjang");
    }

    public String command(String cmd) throws IOException {
        info.setLength(0);
        String r = raw(cmd);
        if (r.startsWith("FAIL")) {
            throw new IOException("Perangkat menolak: "
                    + (r.length() > 4 ? r.substring(4) : "tanpa alasan"));
        }
        if (!r.startsWith("OKAY")) throw new IOException("Balasan tidak dikenal: " + r);
        String val = r.length() > 4 ? r.substring(4) : "";
        if (info.length() > 0) return val.isEmpty() ? info.toString() : info + "\n" + val;
        return val.isEmpty() ? "OKAY" : val;
    }

    public String getvar(String name) throws IOException {
        return command("getvar:" + name);
    }

    public String oem(String args) throws IOException {
        return command("oem " + args);
    }

    public void reboot() throws IOException {
        command("reboot");
    }

    public void rebootBootloader() throws IOException {
        command("reboot-bootloader");
    }

    public void continueBoot() throws IOException {
        command("continue");
    }

    public void erase(String part) throws IOException {
        command("erase:" + part);
    }

    public void download(byte[] data) throws IOException {
        info.setLength(0);
        String r = raw(String.format(java.util.Locale.US, "download:%08x", data.length));
        if (!r.startsWith("DATA")) throw new IOException("Perangkat menolak data: " + r);
        byte[] p = new byte[PKT];
        int off = 0;
        while (off < data.length) {
            int n = Math.min(PKT, data.length - off);
            Arrays.fill(p, (byte) 0);
            System.arraycopy(data, off, p, 0, n);
            if (conn.bulkTransfer(epOut, p, PKT, 20000) < 0) {
                throw new IOException("Transfer data gagal di offset " + off);
            }
            off += n;
        }
        String done = recv(60000);
        if (!done.startsWith("OKAY")) {
            throw new IOException("Data ditolak: "
                    + (done.length() > 4 ? done.substring(4) : done));
        }
    }

    public void flash(String part, byte[] data) throws IOException {
        download(data);
        info.setLength(0);
        String r = raw("flash:" + part);
        if (r.startsWith("FAIL")) {
            throw new IOException("Gagal menulis " + part + ": "
                    + (r.length() > 4 ? r.substring(4) : "tanpa alasan"));
        }
        if (!r.startsWith("OKAY")) throw new IOException("Balasan tidak dikenal: " + r);
    }

    public void close() {
        try { conn.releaseInterface(itf); } catch (Exception ignored) { }
        try { conn.close(); } catch (Exception ignored) { }
    }
}

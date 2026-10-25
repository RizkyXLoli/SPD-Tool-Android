package com.unisoc.spdflasher;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.HashMap;

/**
 * Transport USB bulk untuk perangkat Unisoc (Spreadtrum).
 *
 * VID 0x1782:
 *   0x4D00 = mode download (tahap awal, FDL1 belum jalan)
 *   0x5D00 = FDL1 berjalan
 *   0x3D00 = varian / engineering
 *   0x5D01, 0x4D01 dst. juga muncul pada beberapa chipset.
 */
public class UsbTransport implements Bsl.Link {

    public static final int SPRD_VID = 0x1782;

    private final UsbDeviceConnection conn;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;
    private final UsbInterface usbInterface;
    private final byte[] readBuf = new byte[16384];
    private volatile int ioTimeout = 3000;

    private UsbTransport(UsbDeviceConnection conn, UsbInterface itf,
                         UsbEndpoint in, UsbEndpoint out) {
        this.conn = conn;
        this.usbInterface = itf;
        this.epIn = in;
        this.epOut = out;
    }

    public void setIoTimeout(int ms) { this.ioTimeout = ms; }

    public static boolean isUnisoc(UsbDevice d) {
        return d != null && d.getVendorId() == SPRD_VID;
    }

    public static boolean isDownloadMode(UsbDevice d) {
        return isUnisoc(d) && (d.getProductId() & 0xFF00) == 0x4D00;
    }

    public static String modeOf(UsbDevice d) {
        if (d == null) return "?";
        if (!isUnisoc(d)) return "bukan Unisoc";
        int pid = d.getProductId();
        if ((pid & 0xFF00) == 0x4D00) return "DOWNLOAD";
        if ((pid & 0xFF00) == 0x5D00) return "FDL";
        if ((pid & 0xFF00) == 0x3D00) return "ENG";
        return String.format("PID 0x%04X", pid);
    }

    /** Cari perangkat Unisoc yang punya izin akses. */
    public static UsbDevice find(UsbManager mgr) {
        HashMap<String, UsbDevice> map = mgr.getDeviceList();
        for (UsbDevice d : map.values()) {
            if (isUnisoc(d) && mgr.hasPermission(d)) return d;
        }
        for (UsbDevice d : map.values()) {
            if (isUnisoc(d)) return d;
        }
        return null;
    }

    public static UsbDevice findByPid(UsbManager mgr, int pidMask) {
        HashMap<String, UsbDevice> map = mgr.getDeviceList();
        for (UsbDevice d : map.values()) {
            if (isUnisoc(d) && (d.getProductId() & 0xFF00) == pidMask) return d;
        }
        return null;
    }

    /** Buka koneksi & klaim interface pertama yang punya bulk IN + OUT. */
    public static UsbTransport open(UsbManager mgr, UsbDevice dev) throws IOException {
        if (dev == null) throw new IOException("Tidak ada perangkat Unisoc terdeteksi");
        if (!mgr.hasPermission(dev)) throw new IOException("Izin USB belum diberikan");

        UsbDeviceConnection c = mgr.openDevice(dev);
        if (c == null) throw new IOException("Gagal membuka perangkat USB");

        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface itf = dev.getInterface(i);
            UsbEndpoint in = null, out = null;
            for (int e = 0; e < itf.getEndpointCount(); e++) {
                UsbEndpoint ep = itf.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                else out = ep;
            }
            if (in == null || out == null) continue;
            if (!c.claimInterface(itf, true)) continue;
            return new UsbTransport(c, itf, in, out);
        }
        c.close();
        throw new IOException("Interface bulk IN/OUT tidak ditemukan");
    }

    @Override
    public void write(byte[] data) throws IOException {
        int off = 0;
        while (off < data.length) {
            int n = Math.min(4096, data.length - off);
            byte[] chunk = new byte[n];
            System.arraycopy(data, off, chunk, 0, n);
            int sent = -1;
            for (int attempt = 0; attempt < 3 && sent < 0; attempt++) {
                sent = conn.bulkTransfer(epOut, chunk, n, ioTimeout);
            }
            if (sent < 0) throw new IOException("Gagal kirim data USB (bulk OUT)");
            off += sent;
        }
    }

    @Override
    public byte[] read(int timeoutMs) {
        int n = conn.bulkTransfer(epIn, readBuf, readBuf.length, Math.max(1, timeoutMs));
        if (n <= 0) return new byte[0];
        byte[] out = new byte[n];
        System.arraycopy(readBuf, 0, out, 0, n);
        return out;
    }

    @Override
    public void flush() {
        for (int i = 0; i < 2; i++) {
            while (true) {
                int n = conn.bulkTransfer(epIn, readBuf, readBuf.length, 20);
                if (n <= 0) break;
            }
        }
    }

    public void close() {
        try { conn.releaseInterface(usbInterface); } catch (Exception ignored) { }
        try { conn.close(); } catch (Exception ignored) { }
    }
}

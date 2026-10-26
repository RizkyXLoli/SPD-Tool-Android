package com.unisoc.spdflasher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.unisoc.spdflasher.USB_PERMISSION";
    private static final int REQ_PAC = 1001;

    private UsbManager usbManager;
    private UsbDevice device;
    private UsbTransport transport;

    private PacParser.Pac pac;
    private File pacFile;

    private TextView tvDevice, tvPac, tvLog;
    private LinearLayout partBox;
    private ProgressBar progress;
    private Button btnDevice, btnPac, btnFlash, btnUid, btnDump, btnCheck, btnFb, btnForceFb;
    private Fastboot fb;
    private UsbDevice fbDevice;
    private CheckBox cbRepart, cbSkipFdl;

    private final List<CheckBox> partChecks = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String a = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(a)) {
                synchronized (this) {
                    UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (granted && d != null) {
                        if (Fastboot.isFastboot(d)) {
                            fbDevice = d;
                            log("Izin USB diberikan untuk mode fastboot.");
                        } else {
                            device = d;
                            log("Izin USB diberikan. HP siap dipakai.");
                        }
                        refreshDeviceLabel();
                    } else {
                        log("Izin USB ditolak. Tekan \"Izinkan\" saat HP meminta izin.");
                        toast("Izin USB ditolak");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (Fastboot.isFastboot(d)) {
                    fbDevice = d;
                    log("HP terpasang dalam mode fastboot. Pilih menu fastboot untuk mulai.");
                    refreshDeviceLabel();
                } else if (UsbTransport.isUnisoc(d)) {
                    device = d;
                    log("HP terpasang (mode download). Pilih menu untuk mulai — izin USB "
                            + "diminta saat menu dijalankan.");
                    refreshDeviceLabel();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d == null) return;
                boolean hit = false;
                if (device != null && d.getDeviceName().equals(device.getDeviceName())) {
                    device = null;
                    hit = true;
                }
                if (fbDevice != null && d.getDeviceName().equals(fbDevice.getDeviceName())) {
                    fbDevice = null;
                    fb = null;
                    hit = true;
                }
                if (hit) {
                    log("HP terlepas dari kabel OTG.");
                    refreshDeviceLabel();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        setContentView(buildUi());
        registerUsbReceiver();
        log("Pilih menu dulu. Alat baru mulai mencari perangkat setelah Anda menekan salah satu menu.");
        refreshDeviceLabel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        if (transport != null) { try { transport.close(); } catch (Exception ignored) { } }
    }

    private void registerUsbReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }
    }

    private View buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF101418);

        TextView title = new TextView(this);
        title.setText("SPD Flasher — Unisoc OTG");
        title.setTextColor(0xFF7FD1FF);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Flash firmware .pac lewat OTG.");
        sub.setTextColor(0xFF9AA5B1);
        sub.setTextSize(11);
        sub.setPadding(0, dp(2), 0, dp(10));
        root.addView(sub);

        tvDevice = new TextView(this);
        tvDevice.setTextColor(0xFFE6EDF3);
        tvDevice.setTextSize(13);
        tvDevice.setPadding(0, dp(4), 0, dp(4));
        root.addView(tvDevice);

        tvPac = new TextView(this);
        tvPac.setTextColor(0xFFE6EDF3);
        tvPac.setTextSize(13);
        tvPac.setPadding(0, 0, 0, dp(8));
        root.addView(tvPac);

        root.addView(row(
                btnDevice = button("Cari Perangkat", v -> scanDevices(false)),
                btnPac = button("Pilih .pac", v -> pickPac())
        ));
        root.addView(row(
                btnCheck = button("Inspeksi PAC", v -> inspectPac()),
                btnUid = button("Baca UID", v -> readUid())
        ));
        root.addView(row(
                btnFlash = button("FLASH", v -> confirmFlash()),
                btnDump = button("Backup Partisi", v -> dumpSelected())
        ));
        btnFlash.setTextColor(0xFF00E676);
        btnDump.setTextColor(0xFFFFD54F);

        root.addView(row(
                btnForceFb = button("Paksa Fastboot", v -> forceFastboot()),
                btnFb = button("Menu Fastboot", v -> fastbootMenu())
        ));
        btnForceFb.setTextColor(0xFFFF7043);

        Button btnSvc = button("Spreadtrum Service Tool  (lanjutan)", v ->
                startActivity(new Intent(MainActivity.this, SpreadtrumToolActivity.class)));
        btnSvc.setTextColor(0xFF7FD1FF);
        root.addView(row(btnSvc));

        cbRepart = new CheckBox(this);
        cbRepart.setText("Izinkan repartition bila layout beda");
        cbRepart.setTextColor(0xFFFFAB91);
        cbRepart.setTextSize(11);
        root.addView(cbRepart);

        cbSkipFdl = new CheckBox(this);
        cbSkipFdl.setText("Lewati muat FDL (perangkat sudah di FDL)");
        cbSkipFdl.setTextColor(0xFF9AA5B1);
        cbSkipFdl.setTextSize(11);
        root.addView(cbSkipFdl);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        pp.topMargin = dp(8);
        root.addView(progress, pp);

        TextView partLabel = new TextView(this);
        partLabel.setText("Partisi");
        partLabel.setTextColor(0xFF7FD1FF);
        partLabel.setTextSize(13);
        partLabel.setTypeface(Typeface.DEFAULT_BOLD);
        partLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(partLabel);

        partBox = new LinearLayout(this);
        partBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView partScroll = new ScrollView(this);
        partScroll.addView(partBox);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(partScroll, lp);

        tvLog = new TextView(this);
        tvLog.setTextColor(0xFFB9F6CA);
        tvLog.setTextSize(10);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setPadding(dp(6), dp(6), dp(6), dp(6));
        tvLog.setBackgroundColor(0xFF05080A);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(tvLog);
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(hs);
        logScroll.setBackgroundColor(0xFF05080A);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140));
        llp.topMargin = dp(8);
        root.addView(logScroll, llp);

        return root;
    }

    private LinearLayout row(Button... bs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(3), 0, dp(3));
        for (Button b : bs) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            lp.rightMargin = dp(6);
            r.addView(b, lp);
        }
        return r;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void log(String s) {
        ui.post(() -> {
            tvLog.append(s + "\n");
        });
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private void setBusy(boolean b) {
        busy = b;
        ui.post(() -> {
            btnDevice.setEnabled(!b);
            btnPac.setEnabled(!b);
            btnFlash.setEnabled(!b);
            btnUid.setEnabled(!b);
            btnDump.setEnabled(!b);
            btnCheck.setEnabled(!b);
        });
    }

    private ProgressUpdater prog = new ProgressUpdater();

    private class ProgressUpdater implements FlashEngine.Cb {
        @Override
        public void log(String msg) {
            MainActivity.this.log(msg);
        }

        @Override
        public void progress(int pct, String stage) {
            ui.post(() -> progress.setProgress(Math.max(0, Math.min(100, pct))));
        }
    }

    private void scanDevices(boolean quiet) {
        if (usbManager == null) { toast("USB host tidak tersedia"); return; }
        if (device != null) { try { return; } finally { } }
        UsbDevice d = UsbTransport.find(usbManager);
        if (d == null) {
            if (!quiet) toast("HP belum tersambung");
            log("Menunggu perangkat... HP belum terdeteksi. Cek kabel OTG dan "
                    + "pastikan HP dalam mode download.");
            refreshDeviceLabel();
            return;
        }
        device = d;
        log("Perangkat terdeteksi: HP Unisoc (PID 0x" + Integer.toHexString(d.getProductId())
                + ", " + UsbTransport.modeOf(d) + ")");
        refreshDeviceLabel();
        if (!usbManager.hasPermission(d)) requestPerm(d);
    }

    private void requestPerm(UsbDevice d) {
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
        usbManager.requestPermission(d, pi);
        log("Menunggu izin akses USB dari HP...");
    }

    private void refreshDeviceLabel() {
        ui.post(() -> {
            if (device == null && fbDevice != null) {
                tvDevice.setText("Perangkat: tersambung — mode fastboot");
                tvDevice.setTextColor(0xFF80CBC4);
            } else if (device == null) {
                tvDevice.setText("Perangkat: belum tersambung");
                tvDevice.setTextColor(0xFFFF8A80);
            } else {
                tvDevice.setText("Perangkat: tersambung — " + UsbTransport.modeOf(device));
                tvDevice.setTextColor(0xFF80CBC4);
            }
        });
    }

    private UsbTransport ensureTransport() throws IOException {
        if (transport != null) return transport;
        if (device == null) {
            log("Menunggu perangkat... Sambungkan HP Unisoc lewat OTG, lalu hidupkan "
                    + "sambil menekan tombol Volume (masuk mode download).");
            long end = System.currentTimeMillis() + 60000;
            while (device == null && System.currentTimeMillis() < end) {
                UsbDevice d = UsbTransport.find(usbManager);
                if (d != null) {
                    device = d;
                    log("Perangkat terdeteksi: PID 0x" + Integer.toHexString(d.getProductId())
                            + ", " + UsbTransport.modeOf(d));
                    refreshDeviceLabel();
                    if (!usbManager.hasPermission(d)) requestPerm(d);
                    break;
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Dibatalkan");
                }
            }
        }
        if (device == null) throw new IOException("Perangkat tidak terdeteksi dalam 60 detik. "
                + "Sambungkan lewat OTG dan pastikan HP sudah masuk mode download.");
        if (!usbManager.hasPermission(device)) {
            requestPerm(device);
            long end = System.currentTimeMillis() + 30000;
            while (!usbManager.hasPermission(device) && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Dibatalkan");
                }
            }
            if (!usbManager.hasPermission(device)) {
                throw new IOException("Izin USB belum diberikan. Tekan \"Izinkan\" di HP, "
                        + "lalu jalankan menu lagi.");
            }
            log("Izin USB diberikan.");
        }
        transport = UsbTransport.open(usbManager, device);
        return transport;
    }

    private Fastboot ensureFastboot() throws IOException {
        if (fb != null) return fb;
        UsbDevice d = fbDevice;
        if (d == null) d = Fastboot.find(usbManager);
        if (d == null) {
            throw new IOException("Tidak ada perangkat fastboot. Sambungkan HP "
                    + "dalam mode fastboot lewat OTG.");
        }
        if (!usbManager.hasPermission(d)) {
            requestPerm(d);
            throw new IOException("Menunggu izin USB — tekan izinkan lalu coba lagi.");
        }
        fbDevice = d;
        fb = Fastboot.open(usbManager, d);
        return fb;
    }

    private void fastbootMenu() {
        String[] items = {
                "Cek info perangkat",
                "Reboot ke sistem",
                "Reboot ke fastboot",
                "Reboot ke recovery",
                "Lanjutkan boot (continue)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Menu Fastboot")
                .setItems(items, (dlg, which) -> {
                    new Thread(() -> {
                        try {
                            Fastboot f = ensureFastboot();
                            String out;
                            if (which == 0) {
                                out = "produk: " + f.getvar("product") + "\n"
                                        + "versi: " + f.getvar("version") + "\n"
                                        + "slot: " + f.getvar("current-slot") + "\n"
                                        + "terkunci: " + f.getvar("unlocked") + "\n"
                                        + "secure: " + f.getvar("secure");
                            } else {
                                if (which == 1) f.command("reboot");
                                else if (which == 2) f.rebootBootloader();
                                else if (which == 3) f.command("reboot-recovery");
                                else f.continueBoot();
                                out = "Perintah dikirim.";
                            }
                            log(out);
                        } catch (Exception e) {
                            log("Fastboot gagal: " + e.getMessage());
                        }
                    }, "fb-menu").start();
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void forceFastboot() {
        if (device == null) {
            toast("Sambungkan HP dalam mode download dulu (bukan fastboot).");
            return;
        }
        String[] modes = {"fastboot", "boot-fastboot", "recovery", "bootonce-bootloader"};
        new AlertDialog.Builder(this)
                .setTitle("Paksa masuk Fastboot")
                .setMessage("HP akan ditulis perintah boot ke partisi misc lalu restart "
                        + "sendiri ke fastboot.\n\nPakai ini bila HP berhenti di logo "
                        + "setelah flashing dan tidak bisa masuk fastboot dengan tombol.")
                .setItems(modes, (dlg, which) -> runForceFastboot(modes[which]))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void runForceFastboot(String command) {
        if (pac == null) {
            toast("Pilih file .pac dulu — FDL diambil dari firmware.");
            return;
        }
        setBusy(true);
        log("=== PAKSA FASTBOOT === Perintah: " + command);
        new Thread(() -> {
            FlashEngine eng = new FlashEngine();
            UsbTransport t = null;
            try {
                t = ensureTransport();
                final UsbManager m = usbManager;
                FlashEngine.Opener opener = waitMs -> {
                    long end = System.currentTimeMillis() + waitMs;
                    while (System.currentTimeMillis() < end) {
                        UsbDevice d = UsbTransport.find(m);
                        if (d != null && m.hasPermission(d)) {
                            ui.post(() -> { device = d; refreshDeviceLabel(); });
                            return UsbTransport.open(m, d);
                        }
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Dibatalkan saat menunggu perangkat");
                        }
                    }
                    throw new IOException("Perangkat tidak muncul kembali");
                };
                eng.forceFastboot(t, opener, pac, command, prog);
                toast("Perintah fastboot terkirim");
                awaitFastboot();
            } catch (Exception e) {
                log("GAGAL paksa fastboot: " + e.getMessage());
                toast("Gagal: " + e.getMessage());
            } finally {
                if (t != null) { try { t.close(); } catch (Exception ignored) { } }
                transport = null;
                device = null;
                setBusy(false);
                refreshDeviceLabel();
            }
        }, "force-fb").start();
    }

    private void awaitFastboot() {
        log("Menunggu HP muncul sebagai perangkat fastboot...");
        long end = System.currentTimeMillis() + 90000;
        while (System.currentTimeMillis() < end) {
            UsbDevice d = Fastboot.find(usbManager);
            if (d != null) {
                if (!usbManager.hasPermission(d)) requestPerm(d);
                final UsbDevice fd = d;
                ui.post(() -> { fbDevice = fd; refreshDeviceLabel(); });
                log("HP terdeteksi dalam mode fastboot.");
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                return;
            }
        }
        log("HP belum muncul sebagai perangkat fastboot. Cabut-pasang kabel OTG, "
                + "lalu tekan \"Menu Fastboot\".");
    }

    private void pickPac() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "*/*"});
        try {
            startActivityForResult(i, REQ_PAC);
        } catch (Exception e) {
            toast("File picker tidak tersedia");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PAC || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                File dst = new File(getCacheDir(), "fw.pac");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dst)) {
                    if (in == null) throw new IOException("Tidak bisa membaca file");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                pacFile = dst;
                pac = PacParser.parse(dst);
                String nm = uri.getLastPathSegment();
                log("Firmware dimuat: " + nm);
                ui.post(() -> {
                    tvPac.setText("PAC: " + pac.productName + " " + pac.productVersion
                            + "  (" + PacParser.human(pac.size) + ")");
                    tvPac.setTextColor(0xFF80CBC4);
                });
                fillPartitions();
                inspectPac();
            } catch (Exception e) {
                log("Gagal membaca firmware: " + e.getMessage());
                toast("PAC tidak valid: " + e.getMessage());
            }
        }, "pac-load").start();
    }

    private void fillPartitions() {
        ui.post(() -> {
            partBox.removeAllViews();
            partChecks.clear();
            if (pac == null) return;
            for (PacParser.Entry e : pac.entries) {
                if (!e.hasData()) continue;
                CheckBox c = new CheckBox(this);
                c.setText(e.describe());
                c.setTextSize(11);
                c.setTextColor(0xFFE6EDF3);
                boolean fdl = (e.id + e.idName).toUpperCase(Locale.ROOT).contains("FDL");
                c.setChecked(!fdl && e.checkFlag != 0);
                c.setTag(e);
                partBox.addView(c);
                partChecks.add(c);
            }
            if (partChecks.isEmpty()) {
                TextView t = new TextView(this);
                t.setText("Tidak ada entri data di PAC ini.");
                t.setTextColor(0xFFFF8A80);
                t.setTextSize(11);
                partBox.addView(t);
            }
        });
    }

    private void inspectPac() {
        if (pac == null) { toast("Pilih file .pac dulu"); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("— Info PAC —\n");
        sb.append("Versi format : ").append(pac.version).append('\n');
        sb.append("Produk       : ").append(pac.productName).append('\n');
        sb.append("Versi produk : ").append(pac.productVersion).append('\n');
        sb.append("Alias        : ").append(pac.productAlias).append('\n');
        sb.append("Mode         : ").append(pac.modeStr()).append('\n');
        sb.append("Flash type   : ").append(pac.flashTypeStr()).append('\n');
        sb.append("Ukuran       : ").append(PacParser.human(pac.size)).append('\n');
        sb.append("Entri        : ").append(pac.fileCount).append('\n');
        sb.append("XML          : ").append(pac.xml.isEmpty() ? "tidak ada" : pac.xml.length() + " char").append('\n');
        sb.append("CRC          : ").append(PacParser.verifyCrc(pacFile)).append('\n');

        int withData = 0;
        long bytes = 0;
        for (PacParser.Entry e : pac.entries) {
            if (e.hasData()) { withData++; bytes += e.size; }
        }
        sb.append("Entri data   : ").append(withData).append(" (").append(PacParser.human(bytes)).append(")\n");

        PacParser.Entry f1 = FlashEngine.findFdl(pac, 1);
        PacParser.Entry f2 = FlashEngine.findFdl(pac, 2);
        sb.append("FDL1         : ").append(f1 == null ? "TIDAK ADA" : f1.label() + " (" + PacParser.human(f1.size) + ")").append('\n');
        sb.append("FDL2         : ").append(f2 == null ? "TIDAK ADA" : f2.label() + " (" + PacParser.human(f2.size) + ")").append('\n');

        log(sb.toString().replace("\n", " | "));

        ui.post(() -> new AlertDialog.Builder(this)
                .setTitle("Info PAC")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show());
    }

    private void confirmFlash() {
        if (pac == null) { toast("Pilih PAC dulu"); return; }
        List<PacParser.Entry> sel = selectedEntries();
        if (sel.isEmpty()) { toast("Tidak ada partisi dipilih"); return; }
        long total = 0;
        for (PacParser.Entry e : sel) total += e.size;
        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi Flash")
                .setMessage("Akan menulis " + sel.size() + " partisi (" + PacParser.human(total)
                        + ") ke perangkat.\n\nJANGAN cabut kabel selama proses.\n\n"
                        + "Pastikan PAC ini memang untuk model perangkat Anda.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("FLASH", (d, w) -> doFlash(sel))
                .show();
    }

    private List<PacParser.Entry> selectedEntries() {
        List<PacParser.Entry> sel = new ArrayList<>();
        for (CheckBox c : partChecks) {
            if (c.isChecked() && c.getTag() instanceof PacParser.Entry) {
                sel.add((PacParser.Entry) c.getTag());
            }
        }
        return sel;
    }

    private void doFlash(List<PacParser.Entry> sel) {
        if (busy) { toast("Sedang sibuk"); return; }
        setBusy(true);
        log("=== MULAI FLASH === Jangan cabut kabel sampai selesai.");
        new Thread(() -> {
            FlashEngine eng = new FlashEngine();
            UsbTransport t = null;
            try {
                t = ensureTransport();
                final UsbManager m = usbManager;
                FlashEngine.Opener opener = waitMs -> {
                    long end = System.currentTimeMillis() + waitMs;
                    while (System.currentTimeMillis() < end) {
                        UsbDevice d = UsbTransport.find(m);
                        if (d != null && m.hasPermission(d)) {
                            ui.post(() -> { device = d; refreshDeviceLabel(); });
                            return UsbTransport.open(m, d);
                        }
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Dibatalkan saat menunggu perangkat");
                        }
                    }
                    throw new IOException("Perangkat tidak muncul kembali setelah " + waitMs + " ms");
                };
                eng.flash(t, opener, pac, sel, cbRepart.isChecked(),
                        cbSkipFdl.isChecked(), prog);
                toast("Flash selesai");
            } catch (Exception e) {
                log("GAGAL: " + e.getMessage());
                toast("Gagal: " + e.getMessage());
            } finally {
                if (t != null) { try { t.close(); } catch (Exception ignored) { } }
                transport = null;
                setBusy(false);
            }
        }, "flash").start();
    }

    private void dumpSelected() {
        if (pac == null) { toast("Pilih PAC dulu"); return; }
        List<PacParser.Entry> sel = selectedEntries();
        if (sel.isEmpty()) { toast("Pilih satu partisi untuk dibackup"); return; }
        if (sel.size() > 1) { toast("Backup satu per satu — pilih satu partisi"); return; }
        final PacParser.Entry e = sel.get(0);
        final String name = e.idName.isEmpty() ? e.id : e.idName;

        new AlertDialog.Builder(this)
                .setTitle("Backup Partisi")
                .setMessage("Baca \"" + name + "\" (" + PacParser.human(e.size)
                        + ") dari perangkat?\n\nUkuran dibaca mengikuti ukuran di PAC.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Baca", (d, w) -> doDump(e, name))
                .show();
    }

    private void doDump(PacParser.Entry e, String name) {
        if (busy) return;
        setBusy(true);
        new Thread(() -> {
            UsbTransport t = null;
            try {
                t = ensureTransport();
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File out = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_") + ".img");
                log("Membaca partisi " + name + "...");
                new FlashEngine().readPartition(t, name, e.size, out, prog);
                log("Backup selesai: " + out.getAbsolutePath()
                        + " (" + PacParser.human(out.length()) + ")");
                toast("Tersimpan: " + out.getName());
            } catch (Exception ex) {
                log("Gagal membaca partisi: " + ex.getMessage());
                toast("Gagal: " + ex.getMessage());
            } finally {
                if (t != null) { try { t.close(); } catch (Exception ignored) { } }
                transport = null;
                setBusy(false);
            }
        }, "dump").start();
    }

    private void readUid() {
        if (busy) return;
        setBusy(true);
        new Thread(() -> {
            UsbTransport t = null;
            try {
                t = ensureTransport();
                String uid = new FlashEngine().readChipUid(t, prog);
                log("UID chip: " + uid);
                final String u = uid;
                ui.post(() -> new AlertDialog.Builder(this)
                        .setTitle("Chip UID")
                        .setMessage(u)
                        .setPositiveButton("OK", null).show());
            } catch (Exception ex) {
                log("Gagal membaca UID: " + ex.getMessage());
                toast("Gagal: " + ex.getMessage());
            } finally {
                if (t != null) { try { t.close(); } catch (Exception ignored) { } }
                transport = null;
                setBusy(false);
            }
        }, "uid").start();
    }
}

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

/**
 * SPD Flasher — klien flashing Unisoc lewat USB OTG.
 *
 * Fitur: inspeksi PAC, flash partisi terpilih, backup/baca partisi, baca UID.
 * Tidak ada fungsi bypass/exploit; perangkat harus sudah dalam mode download/FDL.
 */
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
    private Button btnDevice, btnPac, btnFlash, btnUid, btnDump, btnCheck;
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
                        device = d;
                        log("Izin USB diberikan untuk " + d.getDeviceName());
                        refreshDeviceLabel();
                    } else {
                        log("Izin USB DITOLAK");
                        toast("Izin USB ditolak");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (UsbTransport.isUnisoc(d)) {
                    device = d;
                    log("Perangkat Unisoc terpasang: " + d.getDeviceName());
                    refreshDeviceLabel();
                    requestPerm(d);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && device != null && d.getDeviceName().equals(device.getDeviceName())) {
                    device = null;
                    log("Perangkat USB dilepas");
                    refreshDeviceLabel();
                }
            }
        }
    };

    // ============================================================= lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        setContentView(buildUi());
        registerUsbReceiver();
        log("SPD Flasher siap. Sambungkan HP Unisoc (mode download) via OTG.");
        scanDevices(true);
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

    // ==================================================================== UI

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
        sub.setText("Flash firmware .pac lewat OTG. Tanpa bypass, tanpa exploit.");
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

    // ================================================================ device

    private void scanDevices(boolean quiet) {
        if (usbManager == null) { toast("USB host tidak tersedia"); return; }
        if (device != null) { try { return; } finally { } }
        UsbDevice d = UsbTransport.find(usbManager);
        if (d == null) {
            if (!quiet) toast("Tidak ada perangkat Unisoc terdeteksi");
            log("Pindai: tidak ada perangkat Unisoc (VID 0x1782).");
            refreshDeviceLabel();
            return;
        }
        device = d;
        log(String.format(Locale.US, "Ditemukan %s  PID=0x%04X  (%s)",
                d.getDeviceName(), d.getProductId(), UsbTransport.modeOf(d)));
        refreshDeviceLabel();
        if (!usbManager.hasPermission(d)) requestPerm(d);
    }

    private void requestPerm(UsbDevice d) {
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
        usbManager.requestPermission(d, pi);
        log("Meminta izin USB...");
    }

    private void refreshDeviceLabel() {
        ui.post(() -> {
            if (device == null) {
                tvDevice.setText("Perangkat: —");
                tvDevice.setTextColor(0xFFFF8A80);
            } else {
                tvDevice.setText(String.format(Locale.US, "Perangkat: VID 0x%04X PID 0x%04X — %s",
                        device.getVendorId(), device.getProductId(), UsbTransport.modeOf(device)));
                tvDevice.setTextColor(0xFF80CBC4);
            }
        });
    }

    private UsbTransport ensureTransport() throws IOException {
        if (transport != null) return transport;
        if (device == null) scanDevices(true);
        if (device == null) throw new IOException("Tidak ada perangkat. Sambungkan HP via OTG "
                + "dan pastikan berada di mode download.");
        if (!usbManager.hasPermission(device)) {
            requestPerm(device);
            throw new IOException("Menunggu izin USB — tekan izinkan lalu coba lagi.");
        }
        transport = UsbTransport.open(usbManager, device);
        return transport;
    }

    // =================================================================== PAC

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
                log("PAC dimuat: " + nm);
                ui.post(() -> {
                    tvPac.setText("PAC: " + pac.productName + " " + pac.productVersion
                            + "  (" + PacParser.human(pac.size) + ")");
                    tvPac.setTextColor(0xFF80CBC4);
                });
                fillPartitions();
                inspectPac();
            } catch (Exception e) {
                log("Gagal memuat PAC: " + e.getMessage());
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

    // ================================================================ FLASH

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
        log("=== MULAI FLASH ===");
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
                log("Membaca " + name + " -> " + out.getAbsolutePath());
                new FlashEngine().readPartition(t, name, e.size, out, prog);
                log("Backup selesai: " + out.getAbsolutePath()
                        + " (" + PacParser.human(out.length()) + ")");
                toast("Tersimpan: " + out.getName());
            } catch (Exception ex) {
                log("Gagal baca: " + ex.getMessage());
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
                log("Chip UID: " + uid);
                final String u = uid;
                ui.post(() -> new AlertDialog.Builder(this)
                        .setTitle("Chip UID")
                        .setMessage(u)
                        .setPositiveButton("OK", null).show());
            } catch (Exception ex) {
                log("Gagal baca UID: " + ex.getMessage());
                toast("Gagal: " + ex.getMessage());
            } finally {
                if (t != null) { try { t.close(); } catch (Exception ignored) { } }
                transport = null;
                setBusy(false);
            }
        }, "uid").start();
    }
}

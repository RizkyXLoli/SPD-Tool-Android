package com.unisoc.spdflasher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Menu terpisah: Spreadtrum Service Tool.
 * Port fitur spreadtrum_flash v36 (spd_dump) ke SPD Flasher:
 * dump / tulis / hapus partisi, patch byte, slot A-B, dm-verity, AVB, reboot.
 */
public class SpreadtrumToolActivity extends Activity {

    private static final int REQ_PAC = 1101;
    private static final int REQ_IMG = 1102;
    private static final int REQ_USB_PERM = 1103;

    private interface Sink {
        void accept(String s);
    }

    private interface Op {
        void run(ExtOps x) throws Exception;
    }

    private UsbManager usbManager;
    private UsbTransport transport;
    private PacParser.Pac pac;
    private File pacFile;
    private String pendingPartition;
    private volatile boolean cancelOp;
    private boolean usbReceiverRegistered;

    private TextView tvPac, tvLog, tvStage;
    private ProgressBar progress;
    private CheckBox cbSkipFdl;
    private boolean busy;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private final FlashEngine.Cb cb = new FlashEngine.Cb() {
        @Override
        public void log(String msg) {
            ui.post(() -> append(msg));
        }

        @Override
        public void progress(int pct, String stage) {
            ui.post(() -> {
                progress.setProgress(Math.max(0, Math.min(100, pct)));
                tvStage.setText(stage == null ? "" : stage);
            });
        }
    };

    private final ExtOps.Cb extCb = new ExtOps.Cb() {
        @Override
        public void log(String msg) {
            ui.post(() -> append(msg));
        }

        @Override
        public void progress(int pct, String stage) {
            ui.post(() -> {
                progress.setProgress(Math.max(0, Math.min(100, pct)));
                tvStage.setText(stage == null ? "" : stage);
            });
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                append("USB dilepas");
            } else if ("com.unisoc.spdflasher.USB_PERMISSION".equals(a)) {
                append(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        ? "Izin USB diberikan" : "Izin USB ditolak");
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            setContentView(buildUi());
            registerUsbReceiver();
            append("Spreadtrum Service Tool siap.");
            append("Pilih salah satu menu di atas — alat baru mulai mencari perangkat setelah menu dipilih.");
        } catch (Throwable t) {
            showFatal(t);
        }
    }

    private void registerUsbReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction("com.unisoc.spdflasher.USB_PERMISSION");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }
        usbReceiverRegistered = true;
    }

    /** Jaring pengaman: jangan force close, tampilkan penyebabnya. */
    private void showFatal(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        TextView tv = new TextView(this);
        tv.setText("Spreadtrum Service Tool gagal dibuka\n\n" + sw.toString());
        tv.setTextColor(0xFFFF8A80);
        tv.setTextSize(10);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(12), dp(30), dp(12), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0B0F14);
        sv.addView(tv);
        setContentView(sv);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        if (transport != null) {
            try {
                transport.close();
            } catch (Exception ignored) {
            }
        }
        exec.shutdownNow();
        super.onDestroy();
    }

    // ==================== UI ====================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F14);
        root.setPadding(dp(12), dp(14), dp(12), dp(10));

        TextView title = new TextView(this);
        title.setText("SPREADTRUM SERVICE TOOL");
        title.setTextColor(0xFF7FD1FF);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Dump partisi (pilih) / tulis / patch, UBL, e_pwn, RPMB, slot A-B, verity, AVB, reboot");
        sub.setTextColor(0xFF9AA5B1);
        sub.setTextSize(11);
        sub.setPadding(0, dp(2), 0, dp(10));
        root.addView(sub);

        tvPac = new TextView(this);
        tvPac.setText("PAC: belum dipilih");
        tvPac.setTextColor(0xFFFFD54F);
        tvPac.setTextSize(11);
        tvPac.setPadding(0, 0, 0, dp(6));
        root.addView(tvPac);

        root.addView(row(button("Pilih PAC / Firmware", v -> pickPac())));

        cbSkipFdl = new CheckBox(this);
        cbSkipFdl.setText("FDL sudah termuat (lewati muat FDL)");
        cbSkipFdl.setTextColor(0xFF9AA5B1);
        cbSkipFdl.setTextSize(11);
        root.addView(cbSkipFdl);

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);

        menu.addView(row(
                button("Info Chip", v -> actInfoChip()),
                button("Info Partisi", v -> actInfoPart())
        ));
        menu.addView(row(
                button("Daftar Partisi", v -> actListParts()),
                button("Dump (pilih)", v -> actDumpPick())
        ));
        menu.addView(row(
                button("Dump Semua", v -> actDumpAll()),
                button("Dump @offset", v -> actDumpRange())
        ));
        menu.addView(row(
                button("Tulis Partisi", v -> actWrite()),
                button("Tulis @offset", v -> actWriteAt())
        ));
        menu.addView(row(
                button("Hapus Partisi", v -> actErase()),
                button("Patch Byte", v -> actPatch())
        ));
        menu.addView(row(
                button("UBL / Unlock BL", v -> actUbl()),
                button("Cek PWN", v -> actCheckPwn())
        ));
        menu.addView(row(
                button("ePWN", v -> actPwn()),
                button("Baca Memori", v -> actReadMem())
        ));
        menu.addView(row(
                button("Slot A", v -> actSlot('a')),
                button("Slot B", v -> actSlot('b'))
        ));
        menu.addView(row(
                button("Verity OFF", v -> actVerity(true)),
                button("Verity ON", v -> actVerity(false))
        ));
        menu.addView(row(
                button("AVB OFF", v -> actAvb()),
                button("First Mode", v -> actFirstMode())
        ));
        menu.addView(row(
                button("RPMB Info", v -> actRpmb()),
                button("EMMC CID", v -> actCid())
        ));
        menu.addView(row(
                button("PACTIME", v -> actPacTime()),
                button("Export XML", v -> actExportXml())
        ));
        menu.addView(row(
                button("Reboot System", v -> actReboot("system")),
                button("Reboot Fastboot", v -> actReboot("fastboot"))
        ));
        menu.addView(row(
                button("Reboot Recovery", v -> actReboot("recovery")),
                button("Flash UID", v -> actFlashUid())
        ));

        Button btnStop = button("Batal / Stop", v -> {
            if (!busy) {
                toast("Tidak ada operasi berjalan");
                return;
            }
            cancelOp = true;
            append("Permintaan batal dikirim...");
            tvStage.setText("membatalkan");
        });
        btnStop.setTextColor(0xFFFF8A80);
        menu.addView(row(btnStop));

        ScrollView menuScroll = new ScrollView(this);
        menuScroll.addView(menu);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.9f);
        mlp.topMargin = dp(2);
        root.addView(menuScroll, mlp);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        pp.topMargin = dp(10);
        root.addView(progress, pp);

        tvStage = new TextView(this);
        tvStage.setTextColor(0xFF80CBC4);
        tvStage.setTextSize(10);
        tvStage.setPadding(0, dp(2), 0, dp(4));
        root.addView(tvStage);

        tvLog = new TextView(this);
        tvLog.setTextColor(0xFFB9F6CA);
        tvLog.setTextSize(10);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setPadding(dp(6), dp(6), dp(6), dp(6));
        tvLog.setBackgroundColor(0xFF05080A);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(tvLog);
        ScrollView ls = new ScrollView(this);
        ls.addView(hs);
        ls.setBackgroundColor(0xFF05080A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        lp.topMargin = dp(6);
        root.addView(ls, lp);

        return root;
    }

    private LinearLayout row(Button... bs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(4);
        r.setLayoutParams(p);
        for (Button b : bs) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            bp.rightMargin = dp(4);
            r.addView(b, bp);
        }
        return r;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(0xFFE6EDF3);
        b.setBackgroundColor(0xFF17212B);
        b.setPadding(dp(2), dp(8), dp(2), dp(8));
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void append(final String s) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(() -> append(s));
            return;
        }
        if (tvLog == null) return;
        String t = tvLog.getText().toString();
        if (t.length() > 12000) t = t.substring(t.length() - 8000);
        tvLog.setText(t + (t.isEmpty() ? "" : "\n") + s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void setBusy(boolean b) {
        busy = b;
        runOnUiThread(() -> progress.setProgress(0));
    }

    // ==================== input helpers ====================

    private void ask(String title, String hint, String def, Sink sink) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        if (def != null) e.setText(def);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(e)
                .setPositiveButton("OK", (d, w) -> sink.accept(e.getText().toString().trim()))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void confirm(String msg, Runnable ok) {
        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi")
                .setMessage(msg)
                .setPositiveButton("Jalankan", (d, w) -> ok.run())
                .setNegativeButton("Batal", null)
                .show();
    }

    // ==================== PAC ====================

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
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (req == REQ_PAC) {
            new Thread(() -> {
                try {
                    File dst = new File(getCacheDir(), "fw.pac");
                    copyUri(uri, dst);
                    pacFile = dst;
                    pac = PacParser.parse(dst);
                    ui.post(() -> tvPac.setText("PAC: " + pac.productName + " " + pac.productVersion
                            + "  (" + PacParser.human(pac.size) + ")"));
                    append("Firmware dimuat: " + pac.productName + " " + pac.productVersion);
                } catch (Exception e) {
                    append("Gagal membaca PAC: " + e.getMessage());
                    toast("PAC tidak valid: " + e.getMessage());
                }
            }, "pac-load").start();
        } else if (req == REQ_IMG) {
            final String name = pendingPartition;
            if (name == null) {
                toast("Nama partisi belum diisi");
                return;
            }
            new Thread(() -> {
                try {
                    File tmp = new File(getCacheDir(), "upload.img");
                    copyUri(uri, tmp);
                    final File src = tmp;
                    runOp("Tulis " + name, x -> {
                        long sz = src.length();
                        append("Menulis " + src.length() + " byte -> " + name);
                        if (sz > 0x100000000L) append("Mode 64-bit (size > 4 GiB)");
                        x.writePartition(name, src);
                    }, false);
                } catch (Exception e) {
                    append("Gagal membaca file: " + e.getMessage());
                }
            }, "img-copy").start();
        }
    }

    private void copyUri(Uri uri, File dst) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("Tidak bisa membuka file");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ==================== session ====================

    private void requestUsbPermission(UsbDevice d) {
        int flags = android.os.Build.VERSION.SDK_INT >= 23
                ? android.app.PendingIntent.FLAG_MUTABLE : 0;
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 0,
                new Intent("com.unisoc.spdflasher.USB_PERMISSION").setPackage(getPackageName()),
                flags);
        usbManager.requestPermission(d, pi);
    }

    private void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Dibatalkan");
        }
    }

    /**
     * Menunggu perangkat HANYA setelah pengguna memilih operasi.
     * Tidak ada pemindaian otomatis saat activity dibuka.
     */
    private UsbTransport waitForDevice() throws IOException {
        if (transport != null) return transport;
        append("Menunggu perangkat...");
        append("Sambungkan HP lewat OTG, lalu hidupkan sambil menekan Volume (masuk mode download).");
        ui.post(() -> tvStage.setText("menunggu perangkat"));
        long t0 = System.currentTimeMillis();
        long lastTick = 0;
        while (!cancelOp) {
            UsbDevice d = UsbTransport.find(usbManager);
            if (d != null) {
                if (!usbManager.hasPermission(d)) {
                    requestUsbPermission(d);
                    append("Menunggu izin akses USB dari HP...");
                    long end = System.currentTimeMillis() + 30000;
                    while (System.currentTimeMillis() < end && !cancelOp
                            && !usbManager.hasPermission(d)) {
                        pause(300);
                    }
                    if (!usbManager.hasPermission(d)) {
                        append("Izin USB belum diberikan — tunggu berikutnya");
                        pause(1000);
                        continue;
                    }
                }
                UsbTransport t = UsbTransport.open(usbManager, d);
                append("Perangkat terdeteksi: PID 0x" + Integer.toHexString(d.getProductId())
                        + " (" + UsbTransport.modeOf(d) + ")");
                ui.post(() -> tvStage.setText("perangkat terhubung"));
                transport = t;
                return t;
            }
            long now = System.currentTimeMillis();
            if (now - lastTick > 5000) {
                lastTick = now;
                long sec = (now - t0) / 1000;
                ui.post(() -> tvStage.setText("menunggu perangkat... " + sec + "s"));
            }
            pause(400);
        }
        throw new IOException("Dibatalkan oleh pengguna");
    }

    private void runOp(String label, Op op, boolean resetAfter) {
        if (busy) {
            toast("Sedang sibuk — tekan Batal dulu");
            return;
        }
        if (pac == null && !cbSkipFdl.isChecked()) {
            toast("Pilih PAC dulu, atau centang 'FDL sudah termuat'");
            append("Operasi dibatalkan: PAC belum dipilih dan FDL belum ditandai termuat.");
            return;
        }
        cancelOp = false;
        setBusy(true);
        append("=== " + label + " ===");
        exec.execute(() -> {
            UsbTransport t = null;
            try {
                t = waitForDevice();
                final UsbManager m = usbManager;
                FlashEngine.Opener opener = waitMs -> {
                    long end = System.currentTimeMillis() + waitMs;
                    while (System.currentTimeMillis() < end) {
                        if (cancelOp) throw new IOException("Dibatalkan");
                        UsbDevice d = UsbTransport.find(m);
                        if (d != null && m.hasPermission(d)) return UsbTransport.open(m, d);
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Dibatalkan");
                        }
                    }
                    throw new IOException("Perangkat tidak muncul kembali setelah " + waitMs + " ms");
                };
                FlashEngine eng = new FlashEngine();
                FlashEngine.Session s = eng.openFdl(t, opener, pac, false,
                        cbSkipFdl.isChecked(), cb);
                ExtOps x = new ExtOps(s.bsl, extCb);
                x.setWorkDir(dumpDir());
                op.run(x);
                if (resetAfter) {
                    append("Reboot perangkat...");
                    x.reboot("system");
                }
                append("=== " + label + ": SELESAI ===");
                ui.post(() -> toast(label + " selesai"));
            } catch (Exception e) {
                append("GAGAL: " + e.getMessage());
                ui.post(() -> toast("Gagal: " + e.getMessage()));
            } finally {
                if (t != null) {
                    try {
                        t.close();
                    } catch (Exception ignored) {
                    }
                }
                transport = null;
                ui.post(() -> setBusy(false));
            }
        });
    }

    // ==================== aksi ====================

    private void actInfoChip() {
        runOp("Info Chip", x -> {
            String uid = x.chipUid();
            append("Chip UID: " + (uid.isEmpty() ? "(kosong)" : uid));
        }, false);
    }

    private void actInfoPart() {
        ask("Info Partisi", "nama partisi (mis. boot)", "boot", name -> {
            if (name.isEmpty()) return;
            runOp("Info Partisi " + name, x -> {
                long sz = x.partitionSize(name);
                append(name + ": " + sz + " byte (" + PacParser.human(sz) + ")");
                long head = Math.min(sz, 64);
                byte[] d = x.readPartition(name, head);
                append("head: " + hex(d));
            }, false);
        });
    }

    // ==================== peta partisi / dump ====================

    private java.util.List<ExtOps.Part> parts;

    private interface PartSink {
        void accept(java.util.List<ExtOps.Part> sel);
    }

    private File dumpDir() {
        File dir = getExternalFilesDir("dump");
        if (dir == null) dir = new File(getFilesDir(), "dump");
        if (!dir.isDirectory() && !dir.mkdirs()) append("peringatan: gagal buat " + dir);
        return dir;
    }

    private void actListParts() {
        runOp("Daftar Partisi", x -> {
            java.util.List<ExtOps.Part> list = x.listPartitions();
            parts = list;
            append(String.format("%3s  %-22s %10s", "#", "NAMA", "UKURAN"));
            for (ExtOps.Part p : list) append(p.label());
            append("Total: " + list.size() + " partisi");
        }, false);
    }

    private void showPartPicker(String title, PartSink sink) {
        final java.util.List<ExtOps.Part> src = parts;
        if (src == null || src.isEmpty()) {
            toast("Muat daftar partisi dulu (Daftar Partisi)");
            return;
        }
        String[] items = new String[src.size()];
        for (int i = 0; i < src.size(); i++) items[i] = src.get(i).label();
        final boolean[] checked = new boolean[src.size()];
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Jalankan", (d, w) -> {
                    java.util.List<ExtOps.Part> sel = new java.util.ArrayList<>();
                    for (int i = 0; i < src.size(); i++) if (checked[i]) sel.add(src.get(i));
                    if (sel.isEmpty()) {
                        toast("Tidak ada partisi dipilih");
                        return;
                    }
                    sink.accept(sel);
                })
                .setNeutralButton("Semua", (d, w) ->
                        sink.accept(new java.util.ArrayList<>(src)))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void ensureParts(Runnable then) {
        if (parts != null && !parts.isEmpty()) {
            then.run();
            return;
        }
        runOp("Daftar Partisi", x -> {
            parts = x.listPartitions();
            ui.post(then);
        }, false);
    }

    private void actDumpPick() {
        ensureParts(() -> showPartPicker("Dump partisi (pilih)", sel ->
                runOp("Dump " + sel.size() + " partisi", x -> {
                    File dir = dumpDir();
                    x.setWorkDir(dir);
                    for (ExtOps.Part p : sel) x.dumpPartitionTo(p.name, dir);
                    append("Semua dump selesai di " + dir.getAbsolutePath());
                }, false)));
    }

    private void actDumpAll() {
        ensureParts(() -> showPartPicker("Dump SEMUA partisi", sel -> confirm(
                "Dump " + sel.size() + " partisi ke " + dumpDir().getAbsolutePath() + "?",
                () -> runOp("Dump semua partisi", x -> {
                    File dir = dumpDir();
                    x.setWorkDir(dir);
                    for (ExtOps.Part p : sel) {
                        try {
                            x.dumpPartitionTo(p.name, dir);
                        } catch (Exception e) {
                            append("lewat " + p.name + ": " + e.getMessage());
                        }
                    }
                    append("Selesai. Direktori: " + dir.getAbsolutePath());
                }, false))));
    }

    private void actDumpRange() {
        ask("Dump @offset", "nama partisi (mis. boot)", "boot", name -> {
            if (name.isEmpty()) return;
            ask("Offset", "offset byte (hex 0x.. atau desimal)", "0", offs ->
                    ask("Size", "ukuran byte (hex/desimal)", "1048576", szs -> {
                        long off, size;
                        try {
                            off = parseNum(offs);
                            size = parseNum(szs);
                        } catch (Exception e) {
                            toast("offset/size tidak valid");
                            return;
                        }
                        if (size <= 0) {
                            toast("size harus > 0");
                            return;
                        }
                        runOp("Dump " + name + " @0x" + Long.toHexString(off), x -> {
                            File dir = dumpDir();
                            x.setWorkDir(dir);
                            File out = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_")
                                    + "_" + off + ".img");
                            x.dumpRange(name, off, size, out);
                            append("Selesai: " + out.getAbsolutePath()
                                    + " (" + PacParser.human(out.length()) + ")");
                        }, false);
                    }));
        });
    }

    private void actWriteAt() {
        ask("Tulis @offset", "nama partisi (mis. miscdata)", "miscdata", name -> {
            if (name.isEmpty()) return;
            ask("Offset", "offset byte (hex/desimal)", "0", offs ->
                    ask("Data", "byte hex, mis. 01 02 FF", "00", hexs -> {
                        long off;
                        byte[] data;
                        try {
                            off = parseNum(offs);
                            data = parseHex(hexs);
                        } catch (Exception e) {
                            toast("offset/data tidak valid");
                            return;
                        }
                        if (data.length == 0) {
                            toast("data kosong");
                            return;
                        }
                        runOp("Tulis " + name + " @0x" + Long.toHexString(off), x -> {
                            x.setWorkDir(dumpDir());
                            x.writeAtOffset(name, off, data);
                        }, false);
                    }));
        });
    }

    // ==================== UBL / exploit ====================

    private void actUbl() {
        confirm("Unlock bootloader?\nE_BL -> HUK -> AES-128-ECB -> miscdata@0x2000.\n"
                        + "Perangkat akan reboot ke state unlocked.",
                () -> runOp("UBL / Unlock Bootloader", x -> {
                    String r = x.unlockBootloader();
                    append("UBL: " + r);
                }, false));
    }

    private void actCheckPwn() {
        ask("Cek PWN", "slot trustos: 0 = trustos, 1 = trustos_a, 2 = trustos_b", "0", s -> {
            final int ab;
            try {
                ab = Integer.parseInt(s.trim());
            } catch (Exception e) {
                toast("slot harus 0/1/2");
                return;
            }
            runOp("e_checkpwn (ab=" + ab + ")", x -> append(x.checkPwn(ab)), false);
        });
    }

    private void actPwn() {
        ask("ePWN", "slot trustos: 0 = trustos, 1 = trustos_a, 2 = trustos_b", "0", s -> {
            final int ab;
            try {
                ab = Integer.parseInt(s.trim());
            } catch (Exception e) {
                toast("slot harus 0/1/2");
                return;
            }
            confirm("Jalankan e_pwn pada trustos slot " + ab + "?\n"
                            + "Hanya jalan bila checkpwn melaporkan resp=7.",
                    () -> runOp("e_pwn (ab=" + ab + ")", x -> append(x.doPwn(ab)), false));
        });
    }

    private void actReadMem() {
        ask("Baca Memori", "alamat (hex 0x.. atau desimal)", "0x40000000", addrs ->
                ask("Panjang", "ukuran byte (hex/desimal)", "65536", lens -> {
                    long addr, len;
                    try {
                        addr = parseNum(addrs);
                        len = parseNum(lens);
                    } catch (Exception e) {
                        toast("alamat/panjang tidak valid");
                        return;
                    }
                    if (len <= 0) {
                        toast("panjang harus > 0");
                        return;
                    }
                    runOp("e_readmem @0x" + Long.toHexString(addr), x -> {
                        File dir = dumpDir();
                        x.setWorkDir(dir);
                        File out = new File(dir, "mem_" + Long.toHexString(addr) + ".bin");
                        x.readMemory(addr, len, out);
                        append("Selesai: " + out.getAbsolutePath()
                                + " (" + PacParser.human(out.length()) + ")");
                    }, false);
                }));
    }

    // ==================== info tambahan ====================

    private void actRpmb() {
        runOp("RPMB Info", x -> {
            append("RPMB page count: " + x.rpmbPageCount());
            append("RPMB counter  : " + x.rpmbCounter());
        }, false);
    }

    private void actCid() {
        runOp("EMMC CID", x -> {
            append("EMMC CID : " + x.emmcCid());
            try {
                append("Flash UID: " + x.flashUid());
            } catch (Exception e) {
                append("Flash UID: " + e.getMessage());
            }
        }, false);
    }

    private void actFlashUid() {
        runOp("Flash UID", x -> append("Flash UID: " + x.flashUid()), false);
    }

    private void actPacTime() {
        runOp("PACTIME", x -> {
            long t = x.pactime();
            long unix = t == 0 ? 0 : (t / 10000000L) - 11644473600L;
            append("pactime = 0x" + Long.toHexString(t) + "  unix = " + unix
                    + "  (" + (unix > 0 ? new java.util.Date(unix * 1000L).toString() : "kosong") + ")");
        }, false);
    }

    private void actFirstMode() {
        ask("First Mode", "mode id (desimal/hex, ditambah 0x53464D00)", "0", s -> {
            final int mode;
            try {
                mode = (int) parseNum(s);
            } catch (Exception e) {
                toast("mode tidak valid");
                return;
            }
            confirm("Tulis firstmode " + mode + " ke miscdata@0x2420?",
                    () -> runOp("firstmode " + mode, x -> x.setFirstMode(mode), false));
        });
    }

    private void actExportXml() {
        ensureParts(() -> {
            final java.util.List<ExtOps.Part> list = parts;
            runOp("Export XML partisi", x -> {
                File dir = dumpDir();
                x.exportPartitionXml(list, new File(dir, "partition_list.xml"));
            }, false);
        });
    }

    private void actWrite() {
        ask("Tulis Partisi", "nama partisi tujuan (mis. boot)", "boot", name -> {
            if (name.isEmpty()) return;
            pendingPartition = name;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(i, REQ_IMG);
            } catch (Exception e) {
                toast("File picker tidak tersedia");
            }
        });
    }

    private void actErase() {
        ask("Hapus Partisi", "nama partisi", "userdata", name -> {
            if (name.isEmpty()) return;
            confirm("Hapus isi partisi " + name + "?", () ->
                    runOp("Hapus " + name, x -> x.erasePartition(name), false));
        });
    }

    private void actPatch() {
        ask("Patch Byte", "nama partisi", "vbmeta", name -> {
            if (name.isEmpty()) return;
            ask("Offset", "offset hex (mis. 7B) atau desimal", "0", offTxt -> {
                final long off;
                try {
                    off = parseNum(offTxt);
                } catch (Exception e) {
                    toast("Offset tidak valid");
                    return;
                }
                ask("Data", "byte hex, mis. 01 atau 00 01 FF", "", dataTxt -> {
                    byte[] d;
                    try {
                        d = parseHex(dataTxt);
                    } catch (Exception e) {
                        toast("Data hex tidak valid");
                        return;
                    }
                    confirm("Patch " + name + " @0x" + Long.toHexString(off) + " = " + hex(d) + "?", () ->
                            runOp("Patch " + name, x -> x.patchPartition(name, off, d), false));
                });
            });
        });
    }

    private void actSlot(char slot) {
        confirm("Set slot aktif ke " + slot + "? (menulis misc)", () ->
                runOp("Slot " + slot, x -> x.setActiveSlot(slot), false));
    }

    private void actVerity(boolean disable) {
        confirm(disable ? "Matikan dm-verity (vbmeta[0x7B]=1)?"
                        : "Nyalakan dm-verity (semua vbmeta[0x7B]=0)?", () ->
                runOp(disable ? "Verity OFF" : "Verity ON",
                        x -> {
                            if (disable) x.disableVerity();
                            else x.enableVerity();
                        }, false));
    }

    private void actAvb() {
        ask("AVB OFF", "nama partisi TOS/trustos", "trustos", name -> {
            if (name.isEmpty()) return;
            confirm("Patch " + name + " untuk mematikan verifikasi AVB?", () ->
                    runOp("AVB OFF " + name, x -> {
                        boolean patched = x.disableAvb(name);
                        append(patched ? "AVB dimatikan" : "Tidak ada perubahan");
                    }, false));
        });
    }

    private void actReboot(String mode) {
        confirm("Reboot ke " + mode + "?", () ->
                runOp("Reboot " + mode, x -> x.reboot(mode), false));
    }

    // ==================== util ====================

    private static long parseNum(String s) {
        String v = s.trim().toLowerCase();
        if (v.startsWith("0x")) return Long.parseLong(v.substring(2), 16);
        return Long.parseLong(v);
    }

    private static byte[] parseHex(String s) {
        String v = s.replaceAll("[^0-9a-fA-F]", "");
        if (v.isEmpty() || (v.length() & 1) != 0) throw new IllegalArgumentException("hex");
        byte[] out = new byte[v.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(v.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", d[i] & 0xFF));
        }
        return sb.toString();
    }
}

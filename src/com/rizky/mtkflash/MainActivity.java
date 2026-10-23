package com.rizky.mtkflash;

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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity implements BromProtocol.Log {

    private static final String ACTION_USB_PERM = "com.rizky.mtkflash.USB_PERMISSION";
    private static final int REQ_TREE = 1001;
    private static final int REQ_SCATTER = 1002;

    private static final Set<String> BROM_ONLY = new HashSet<>(Arrays.asList(
            "preloader", "pgpt", "sgpt", "boot_para", "nvram", "nvdata", "nvcfg",
            "protect1", "protect2", "seccfg", "persist", "proinfo", "expdb",
            "flashinfo", "otp"));

    private static final Map<String, String> FB_ALIAS = new HashMap<>();

    static {
        FB_ALIAS.put("android", "system");
        FB_ALIAS.put("usrdata", "userdata");
        FB_ALIAS.put("userdata2", "userdata");
        FB_ALIAS.put("system_a", "system");
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> logLines = new ArrayList<>();

    private UsbManager usbManager;
    private UsbTransport transport;
    private BromProtocol brom;
    private FastbootProtocol fastboot;
    private boolean wantFastboot = false;

    private TextView statusView;
    private TextView logView;
    private TextView romView;
    private LinearLayout tableBox;
    private Button flashBtn;
    private Button modeBtn;
    private FlashMode mode = FlashMode.DOWNLOAD_ONLY;

    private Uri treeUri;
    private Uri scatterUri;
    private ScatterParser.Scatter scatter;
    private final List<Row> rows = new ArrayList<>();

    private static class Row {
        ScatterParser.Partition p;
        RomFolder.Entry file;
        CheckBox cb;
        String note = "";
        boolean blocked = false;
    }

    private interface Job {
        Object run() throws Exception;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String a = intent.getAction();
            if (ACTION_USB_PERM.equals(a)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (ok && d != null) {
                    if (wantFastboot) openFastboot(d);
                    else openBrom(d);
                } else {
                    log("izin USB ditolak untuk " + (d == null ? "?" : d.getDeviceName()));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                log("perangkat tersambung: " + (d == null ? "?" : d.getDeviceName()));
                refreshDeviceList();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                log("perangkat terlepas");
                closeTransport();
                refreshDeviceList();
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        setContentView(buildUi());

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }

        log("MTK Flash siap. Alurnya sama seperti versi PC:");
        log("1) ekstrak dulu file ROM hasil download (zip/rar)");
        log("2) tekan Pilih file scatter, cari scatter .txt atau .xml");
        log("3) aplikasi baca scatter dan menampilkan daftar partisi");
        log("4) centang partisi yang mau ditulis");
        log("5) colok HP dalam mode fastboot, tekan FB scan");
        log("6) tekan Flash terpilih");
        refreshDeviceList();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        closeTransport();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#12141a"));
        root.setPadding(dp(12), dp(12), dp(12), dp(8));

        TextView title = new TextView(this);
        title.setText("MTK Flash");
        title.setTextColor(Color.parseColor("#7fd1ff"));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("belum tersambung");
        statusView.setTextColor(Color.parseColor("#9aa4b2"));
        statusView.setTextSize(12);
        statusView.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusView);

        root.addView(row(
                btn("Pilih file scatter", this::doPickScatter),
                btn("Pilih folder ROM", this::doPickFolder),
                btn("Muat ulang", this::reloadRom),
                btn("Centang siap", this::checkReady),
                btn("Kosongkan", this::clearChecks)));

        romView = new TextView(this);
        romView.setText("belum ada file scatter dipilih");
        romView.setTextColor(Color.parseColor("#9aa4b2"));
        romView.setTextSize(11);
        romView.setPadding(0, dp(6), 0, dp(4));
        root.addView(romView);

        tableBox = new LinearLayout(this);
        tableBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView tableScroll = new ScrollView(this);
        tableScroll.addView(tableBox);
        tableScroll.setBackgroundColor(Color.parseColor("#171b22"));
        tableScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f));
        root.addView(tableScroll);

        flashBtn = btn("Flash terpilih", this::confirmFlash);
        modeBtn = btn("Mode: " + mode.label, this::pickMode);
        root.addView(row(
                btn("FB scan", this::doFastbootScan),
                btn("FB info", this::doFastbootInfo),
                modeBtn,
                flashBtn,
                btn("Putus", this::doDisconnect)));

        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#c8d3e0"));
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setTextIsSelectable(true);
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView);
        logScroll.setBackgroundColor(Color.parseColor("#0d0f14"));
        logScroll.setPadding(dp(6), dp(6), dp(6), dp(6));
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));
        root.addView(logScroll);

        root.addView(row(
                btn("Connect BROM", this::doConnectBrom),
                btn("Info chip", this::doChipInfo)));

        return root;
    }

    private Button btn(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private View row(Button... buttons) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, dp(4), 0, dp(4));
        for (Button b : buttons) {
            b.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            l.addView(b);
        }
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.addView(l);
        return h;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void i(String s) {
        log(s);
    }

    @Override
    public void e(String s) {
        log("ERROR: " + s);
    }

    private void doPickScatter() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain",
                "text/xml",
                "application/xml",
                "application/octet-stream"
        });
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_SCATTER);
        } catch (Exception e) {
            log("pemilih berkas tidak tersedia: " + e.getMessage());
            doPickFolder();
        }
    }

    private void doPickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            log("pemilih folder tidak tersedia: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            if (req == REQ_SCATTER || req == REQ_TREE) log("pemilihan dibatalkan");
            return;
        }
        Uri u = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        if (req == REQ_SCATTER) {
            scatterUri = u;
            treeUri = null;
            loadScatterFile();
        } else if (req == REQ_TREE) {
            treeUri = u;
            scatterUri = null;
            loadRom();
        }
    }

    private void reloadRom() {
        if (scatterUri != null) {
            loadScatterFile();
        } else if (treeUri != null) {
            loadRom();
        } else {
            toast("pilih file scatter dulu");
        }
    }

    private void loadScatterFile() {
        final Uri u = scatterUri;
        runBg("baca scatter", () -> {
            String name = RomFolder.displayName(this, u);
            ScatterParser.Scatter parsed;
            try (InputStream in = RomFolder.open(this, u)) {
                parsed = ScatterParser.parse(in);
            }

            List<RomFolder.Entry> entries = new ArrayList<>();
            boolean folderOk = true;
            try {
                entries = RomFolder.siblings(this, u);
            } catch (Exception e) {
                folderOk = false;
                log("folder induk tidak bisa dibaca: " + e.getMessage());
            }
            apply(name, parsed, entries, folderOk);
            return null;
        });
    }

    private void loadRom() {
        runBg("baca ROM", () -> {
            List<RomFolder.Entry> entries = RomFolder.list(this, treeUri);
            RomFolder.Entry sc = pickScatter(entries);
            if (sc == null) {
                List<String> txt = new ArrayList<>();
                for (RomFolder.Entry e : entries) {
                    if (!e.dir && e.name.toLowerCase(Locale.US).endsWith(".txt")) {
                        txt.add(e.name);
                    }
                }
                List<String> cand = new ArrayList<>();
                for (RomFolder.Entry e : entries) {
                    if (!e.dir) {
                        String n = e.name.toLowerCase(Locale.US);
                        if (n.endsWith(".txt") || n.endsWith(".xml")) cand.add(e.name);
                    }
                }
                throw new IllegalStateException("file scatter (.txt/.xml) tidak ada di folder ini"
                        + (cand.isEmpty() ? "" : " (ada: " + String.join(", ", cand) + ")"));
            }

            ScatterParser.Scatter parsed;
            try (InputStream in = RomFolder.open(this, sc.uri)) {
                parsed = ScatterParser.parse(in);
            }
            apply(sc.name, parsed, entries, true);
            return null;
        });
    }

    private void apply(String name, ScatterParser.Scatter parsed,
                       List<RomFolder.Entry> entries, boolean folderOk) {
        Map<String, RomFolder.Entry> byName = new HashMap<>();
        for (RomFolder.Entry e : entries) {
            if (!e.dir) byName.put(e.name.toLowerCase(Locale.US), e);
        }

        List<Row> built = new ArrayList<>();
        for (ScatterParser.Partition p : parsed.partitions) {
            Row r = new Row();
            r.p = p;
            String key = p.fileName == null ? "" : p.fileName.toLowerCase(Locale.US).trim();
            if (key.isEmpty()) {
                r.note = "tanpa image";
                r.blocked = true;
            } else {
                r.file = byName.get(key);
                if (r.file == null) {
                    r.note = folderOk ? "tidak ada di folder" : "image tidak ketemu";
                } else if (BROM_ONLY.contains(p.name.toLowerCase(Locale.US))) {
                    r.blocked = true;
                    r.note = "khusus BROM/DA";
                }
            }
            built.add(r);
        }

        int ready = 0;
        for (Row r : built) if (r.file != null && !r.blocked) ready++;
        final int readyCount = ready;

        ui.post(() -> {
            scatter = parsed;
            rows.clear();
            rows.addAll(built);
            buildTable();
            romView.setText(String.format(Locale.US,
                    "%s · %s · %s · %d partisi · %d siap diflash",
                    name,
                    parsed.xml ? "scatter xml" : "scatter txt",
                    parsed.platform.isEmpty() ? "platform ?" : parsed.platform,
                    parsed.partitions.size(), readyCount));
            log("scatter   : " + name + (parsed.xml ? "  (xml)" : "  (txt)"));
            log("platform  : " + (parsed.platform.isEmpty() ? "?" : parsed.platform)
                    + "   storage: " + (parsed.storage.isEmpty() ? "?" : parsed.storage));
            log(String.format(Locale.US,
                    "partisi   : %d terbaca, %d punya image di folder",
                    parsed.partitions.size(), readyCount));
            if (readyCount == 0) {
                log("tidak ada image yang cocok. Pastikan ROM sudah diekstrak,");
                log("bukan masih di dalam zip/rar, dan image ada di folder yang sama");
                log("dengan file scatter.");
                if (!folderOk) log("folder induk tidak terbaca: pakai tombol Pilih folder ROM");
            }
            status("scatter siap: " + readyCount + " partisi");
        });
    }

    private RomFolder.Entry pickScatter(List<RomFolder.Entry> entries) {
        RomFolder.Entry best = null;
        RomFolder.Entry xml = null;
        for (RomFolder.Entry e : entries) {
            if (e.dir) continue;
            String n = e.name.toLowerCase(Locale.US);
            if (!(n.endsWith(".txt") || n.endsWith(".xml"))) continue;
            if (!n.contains("scatter")) continue;
            if (n.equals("android_scatter.txt")) return e;
            if (n.endsWith(".xml") && xml == null) xml = e;
            if (best == null) best = e;
        }
        return best != null ? best : xml;
    }

    private void buildTable() {
        tableBox.removeAllViews();
        if (scatter == null) return;

        TextView head = new TextView(this);
        head.setText("   partisi         image                        ukuran");
        head.setTextColor(Color.parseColor("#6f7c93"));
        head.setTextSize(9);
        head.setTypeface(Typeface.MONOSPACE);
        head.setPadding(dp(4), dp(6), dp(4), dp(4));
        tableBox.addView(head);

        for (Row r : rows) {
            CheckBox cb = new CheckBox(this);
            cb.setTextSize(10);
            cb.setTypeface(Typeface.MONOSPACE);
            cb.setPadding(dp(2), dp(2), dp(2), dp(2));

            boolean usable = r.file != null && !r.blocked;
            cb.setTextColor(Color.parseColor(usable ? "#dfe6f2" : "#5d6779"));

            String size = "-";
            if (r.file != null) {
                size = r.file.size > 0 ? FastbootProtocol.human(r.file.size) : "?";
            }
            String line = String.format(Locale.US, "%-14s %-24s %8s",
                    r.p.name,
                    r.p.fileName == null || r.p.fileName.isEmpty() ? "-" : r.p.fileName,
                    size);
            if (!r.note.isEmpty()) line = line + "  " + r.note;
            cb.setText(line);

            cb.setEnabled(usable);
            cb.setChecked(usable && r.p.download);
            cb.setOnCheckedChangeListener((v, checked) -> updateFlashBtn());
            r.cb = cb;
            tableBox.addView(cb);
        }
        updateFlashBtn();
    }

    private void pickMode() {
        int cur = mode.ordinal();
        new AlertDialog.Builder(this)
                .setTitle("Mode flash")
                .setSingleChoiceItems(FlashMode.labels(), cur, (d, which) -> {
                    mode = FlashMode.byIndex(which);
                    modeBtn.setText("Mode: " + mode.label);
                    log("mode flash: " + mode.label + " — " + mode.note);
                    updateFlashBtn();
                    d.dismiss();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void updateFlashBtn() {
        int n = 0;
        for (Row r : rows) if (r.cb != null && r.cb.isChecked()) n++;
        if (mode.flashAll()) {
            int all = 0;
            for (Row r : rows) {
                if (r.file != null && !r.blocked && !mode.keepData(r.p.name)) all++;
            }
            flashBtn.setText("Flash " + all + " partisi (" + mode.label + ")");
        } else {
            flashBtn.setText(n == 0 ? "Flash terpilih" : "Flash " + n + " partisi");
        }
    }

    private void checkReady() {
        for (Row r : rows) {
            if (r.cb != null && r.cb.isEnabled()) r.cb.setChecked(true);
        }
        updateFlashBtn();
    }

    private void clearChecks() {
        for (Row r : rows) if (r.cb != null) r.cb.setChecked(false);
        updateFlashBtn();
    }

    private void confirmFlash() {
        if (fastboot == null) {
            toast("FB scan dulu, HP harus di mode fastboot");
            return;
        }
        List<Row> pick = new ArrayList<>();
        for (Row r : rows) {
            if (r.file == null || r.blocked) continue;
            if (mode.flashAll()) {
                if (!mode.keepData(r.p.name)) pick.add(r);
            } else if (r.cb != null && r.cb.isChecked()) {
                pick.add(r);
            }
        }
        if (pick.isEmpty()) {
            toast(mode.flashAll() ? "tidak ada image di scatter ini" : "belum ada partisi dicentang");
            return;
        }

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Row r : pick) {
            if (shown++ < 40) {
                sb.append("\n  ").append(fbName(r.p.name)).append("  <-  ").append(r.p.fileName);
            }
        }
        if (pick.size() > 40) sb.append("\n  ... dan ").append(pick.size() - 40).append(" lainnya");

        String extra = "";
        if (mode.wipeFirst()) {
            extra = "\n\nMODE Format All + Download: data user (userdata, metadata, cache) dihapus dulu."
                    + "\nSemua aplikasi, foto, dan akun di HP akan hilang.";
        } else if (mode == FlashMode.FIRMWARE_UPGRADE) {
            extra = "\n\nMODE Firmware Upgrade: semua partisi ROM ditulis, data user dipertahankan.";
        } else {
            extra = "\n\nMODE Download Only: hanya partisi yang dicentang.";
        }

        new AlertDialog.Builder(this)
                .setTitle(mode.label + " — " + pick.size() + " partisi?")
                .setMessage("Yang akan ditulis:" + sb + extra
                        + "\n\nJangan cabut kabel sampai selesai. Salah partisi bisa brick.")
                .setNegativeButton("Batal", null)
                .setPositiveButton(mode.wipeFirst() ? "Hapus & Flash" : "Flash",
                        (d, w) -> doFlash(pick))
                .show();
    }

    private void doFlash(List<Row> pick) {
        runBg("flash", () -> {
            int ok = 0, fail = 0;
            log("mode: " + mode.label + " — " + mode.note);
            if (mode.wipeFirst()) {
                for (String p : new String[]{"userdata", "metadata", "cache"}) {
                    try {
                        status("hapus " + p);
                        log("hapus " + p + " ...");
                        fastboot.erase(p);
                        log("  OK hapus " + p);
                    } catch (Exception e) {
                        log("  lewati hapus " + p + ": " + e.getMessage());
                    }
                }
            }
            for (Row r : pick) {
                String part = fbName(r.p.name);
                long size = RomFolder.size(this, r.file.uri, r.file.size);
                if (size <= 0) {
                    log("lewati " + part + ": ukuran berkas tidak terbaca");
                    fail++;
                    continue;
                }
                log(String.format(Locale.US, "tulis %-14s <- %-24s %8s",
                        part, r.file.name, FastbootProtocol.human(size)));
                status("flash " + part);
                try (InputStream in = RomFolder.open(this, r.file.uri)) {
                    fastboot.flashStream(part, size, in,
                            (pct, msg) -> status(part + " " + pct + "% " + msg));
                    log("  OK " + part);
                    ok++;
                } catch (Exception e) {
                    log("  GAGAL " + part + ": " + e.getMessage());
                    fail++;
                }
            }
            log(String.format(Locale.US, "selesai: %d berhasil, %d gagal", ok, fail));
            status(fail == 0 ? "flash selesai" : "flash selesai, " + fail + " gagal");
            if (fail == 0) log("semua partisi terpilih sudah ditulis.");
            return null;
        });
    }

    private static String fbName(String scatterName) {
        String n = scatterName == null ? "" : scatterName.toLowerCase(Locale.US).trim();
        String alias = FB_ALIAS.get(n);
        return alias == null ? n : alias;
    }

    private void doFastbootScan() {
        UsbDevice target = null;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (FastbootProtocol.isFastboot(d)) {
                target = d;
                break;
            }
        }
        if (target == null) {
            log("tidak ada perangkat dengan antarmuka fastboot (FF/42/03)");
            log("pastikan HP sudah di mode fastboot/bootloader");
            status("fastboot tidak ditemukan");
            return;
        }
        wantFastboot = true;
        if (usbManager.hasPermission(target)) {
            openFastboot(target);
        } else {
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERM).setPackage(getPackageName()), flags);
            usbManager.requestPermission(target, pi);
            status("menunggu izin USB");
        }
    }

    private void openFastboot(UsbDevice d) {
        runBg("buka fastboot", () -> {
            closeTransport();
            FastbootProtocol fb = new FastbootProtocol(usbManager, d);
            fastboot = fb;
            log("fastboot terhubung: " + d.getDeviceName());
            log(String.format(Locale.US, "  %-20s %s", "product", safeGetVar(fb, "product")));
            log(String.format(Locale.US, "  %-20s %s", "unlocked", safeGetVar(fb, "unlocked")));
            log(String.format(Locale.US, "  %-20s %s", "max-download-size",
                    safeGetVar(fb, "max-download-size")));
            if ("no".equalsIgnoreCase(safeGetVar(fb, "unlocked"))) {
                log("bootloader masih terkunci, flash akan ditolak.");
            }
            status("fastboot siap");
            return null;
        });
    }

    private String safeGetVar(FastbootProtocol fb, String name) {
        try {
            return fb.getVar(name);
        } catch (Exception e) {
            return "?";
        }
    }

    private void doFastbootInfo() {
        if (fastboot == null) {
            toast("FB scan dulu");
            return;
        }
        runBg("fb info", () -> {
            String[] vars = {"product", "variant", "serialno", "version-bootloader",
                    "version-baseband", "current-slot", "slot-count", "max-download-size",
                    "unlocked", "secure", "partition-type:boot", "partition-size:boot"};
            for (String v : vars) {
                log(String.format(Locale.US, "  %-22s %s", v, safeGetVar(fastboot, v)));
            }
            status("info fastboot selesai");
            return null;
        });
    }

    private void doConnectBrom() {
        UsbDevice target = null;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getVendorId() == UsbTransport.MTK_VID) {
                target = d;
                break;
            }
        }
        if (target == null) {
            log("tidak ada perangkat MediaTek 0x0E8D");
            log("BROM hanya untuk baca info chip; flashing memakai fastboot");
            status("BROM tidak ditemukan");
            return;
        }
        wantFastboot = false;
        if (usbManager.hasPermission(target)) {
            openBrom(target);
        } else {
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERM).setPackage(getPackageName()), flags);
            usbManager.requestPermission(target, pi);
            status("menunggu izin USB");
        }
    }

    private void openBrom(UsbDevice d) {
        runBg("buka BROM", () -> {
            closeTransport();
            UsbTransport t = new UsbTransport(usbManager, d);
            brom = new BromProtocol(t, this);
            transport = t;
            log("BROM terhubung: " + d.getDeviceName()
                    + String.format(Locale.US, " (VID %04X PID %04X)",
                    d.getVendorId(), d.getProductId()));
            status("BROM siap");
            return null;
        });
    }

    private void doChipInfo() {
        if (brom == null) {
            toast("Connect BROM dulu");
            return;
        }
        runBg("info chip", () -> {
            brom.handshake(4);
            log(String.format(Locale.US, "BROM version  : 0x%04X", brom.getBromVersion()));
            log(String.format(Locale.US, "HW code       : 0x%04X", brom.getHwCode()));
            int[] v = brom.getHwSwVersion();
            log(String.format(Locale.US, "HW/SW version : 0x%04X / 0x%04X", v[0], v[1]));
            try {
                log(String.format(Locale.US, "Target config : 0x%08X", brom.getTargetConfig()));
            } catch (Exception e) {
                log("target config tidak didukung chip ini");
            }
            status("info chip selesai");
            return null;
        });
    }

    private void doDisconnect() {
        closeTransport();
        status("terputus");
    }

    private void closeTransport() {
        FastbootProtocol fb = fastboot;
        fastboot = null;
        if (fb != null) {
            try {
                fb.close();
            } catch (Exception ignored) {
            }
        }
        UsbTransport t = transport;
        transport = null;
        brom = null;
        if (t != null) {
            try {
                t.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshDeviceList() {
        int mtk = 0, fb = 0;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getVendorId() == UsbTransport.MTK_VID) mtk++;
            if (FastbootProtocol.isFastboot(d)) fb++;
        }
        status(String.format(Locale.US, "perangkat: %d MediaTek, %d fastboot", mtk, fb));
        if (mtk > 0 || fb > 0) {
            log(String.format(Locale.US, "USB: %d MediaTek, %d fastboot", mtk, fb));
        }
    }

    private void runBg(String name, Job job) {
        status(name + "...");
        new Thread(() -> {
            try {
                job.run();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                log(name + " gagal: " + msg);
                status(name + " gagal");
            }
        }, name).start();
    }

    private void log(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        ui.post(() -> {
            logLines.add(t + "  " + s);
            if (logLines.size() > 600) logLines.remove(0);
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) sb.append(l).append('\n');
            if (logView != null) logView.setText(sb.toString());
        });
    }

    private void status(String s) {
        ui.post(() -> {
            if (statusView != null) statusView.setText(s);
        });
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }
}

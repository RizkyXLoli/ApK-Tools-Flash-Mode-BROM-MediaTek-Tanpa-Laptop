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
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.rizky.mtkflash.auth.AuthBypass;
import com.rizky.mtkflash.auth.UsbBromLink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private static final int REQ_DUMP = 1003;
    private static final int REQ_DA = 1004;
    private static final int REQ_BROM_OUT = 1005;

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
    private final List<String> dumpQueue = new ArrayList<>();
    private byte[] daData;
    private String daName = "";
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
                btn("Info chip", this::doChipInfo),
                btn("Dump partisi", this::startDump),
                btn("Dump BROM", this::startBromDump),
                btn("Terminal", this::openTerminal)));

        root.addView(row(
                btn("Bypass auth", () -> doBypassAuth(false)),
                btn("Bypass kamakiri2", () -> doBypassAuth(true))));

        return root;
    }

    private Button btn(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTextColor(Color.parseColor("#dfe6f2"));
        b.setBackground(cellBg("#1c2230", "#2c3547"));
        b.setStateListAnimator(null);
        b.setIncludeFontPadding(false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private GradientDrawable cellBg(String fill, String stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(6));
        d.setColor(Color.parseColor(fill));
        d.setStroke(dp(1), Color.parseColor(stroke));
        return d;
    }

    private View row(Button... buttons) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int perRow = buttons.length <= 3 ? Math.max(1, buttons.length) : 3;
        LinearLayout line = null;
        for (int i = 0; i < buttons.length; i++) {
            if (i % perRow == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                box.addView(line);
            }
            Button b = buttons[i];
            b.setLayoutParams(cellParams());
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setAllCaps(false);
            b.setTextSize(11);
            b.setMaxLines(2);
            b.setEllipsize(TextUtils.TruncateAt.END);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(4), dp(2), dp(4), dp(2));
            b.setIncludeFontPadding(false);
            line.addView(b);
        }

        int sisa = buttons.length % perRow;
        if (line != null && sisa != 0) {
            for (int i = 0; i < perRow - sisa; i++) {
                View spacer = new View(this);
                spacer.setLayoutParams(cellParams());
                line.addView(spacer);
            }
        }
        return box;
    }

    private LinearLayout.LayoutParams cellParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
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
        } else if (req == REQ_DUMP) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            doDump(u);
        } else if (req == REQ_DA) {
            loadDa(u);
        } else if (req == REQ_BROM_OUT) {
            doBromDump(u);
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

    private static final int COL_CHECK = 40;
    private static final float W_NAME = 15f;
    private static final float W_FILE = 22f;
    private static final float W_SIZE = 11f;

    private void buildTable() {
        tableBox.removeAllViews();
        if (scatter == null) return;

        tableBox.addView(tableHead());
        for (int i = 0; i < rows.size(); i++) {
            tableBox.addView(tableRow(rows.get(i), i % 2 == 1));
        }
        updateFlashBtn();
    }

    private View tableHead() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        h.setBackgroundColor(Color.parseColor("#1d2431"));
        h.addView(cell("", COL_CHECK, 0f, Gravity.START, "#7d8aa0", 9));
        h.addView(cell("partisi", 0, W_NAME, Gravity.START, "#7d8aa0", 9));
        h.addView(cell("image", 0, W_FILE, Gravity.START, "#7d8aa0", 9));
        h.addView(cell("ukuran", 0, W_SIZE, Gravity.END, "#7d8aa0", 9));
        return h;
    }

    private View tableRow(Row r, boolean alt) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        line.setBackgroundColor(Color.parseColor(alt ? "#1a1f29" : "#171b22"));

        boolean usable = r.file != null && !r.blocked;

        CheckBox cb = new CheckBox(this);
        cb.setLayoutParams(new LinearLayout.LayoutParams(
                dp(COL_CHECK), ViewGroup.LayoutParams.MATCH_PARENT));
        cb.setText("");
        cb.setPadding(dp(2), 0, 0, 0);
        cb.setEnabled(usable);
        cb.setChecked(usable && r.p.download);
        cb.setOnCheckedChangeListener((v, checked) -> updateFlashBtn());
        r.cb = cb;
        line.addView(cb);

        String size = "-";
        if (r.file != null) {
            size = r.file.size > 0 ? FastbootProtocol.human(r.file.size) : "?";
        }
        String img = r.p.fileName == null || r.p.fileName.isEmpty() ? "-" : r.p.fileName;
        if (!r.note.isEmpty()) img = img + " " + r.note;

        line.addView(cell(r.p.name, 0, W_NAME, Gravity.START,
                usable ? "#dfe6f2" : "#5d6779", 10));
        line.addView(cell(img, 0, W_FILE, Gravity.START,
                usable ? "#9fb0c6" : "#4f596b", 10));
        line.addView(cell(size, 0, W_SIZE, Gravity.END,
                usable ? "#8fd3a0" : "#4f596b", 10));

        line.setOnClickListener(v -> {
            if (cb.isEnabled()) cb.toggle();
        });
        return line;
    }

    private TextView cell(String text, int fixedDp, float weight, int gravity,
                          String color, int sizeSp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(Color.parseColor(color));
        t.setGravity(gravity | Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setPadding(dp(3), 0, dp(3), 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                fixedDp > 0 ? dp(fixedDp) : 0, ViewGroup.LayoutParams.MATCH_PARENT, weight));
        return t;
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

    private void startDump() {
        if (fastboot == null) {
            toast("FB scan dulu, HP harus di mode fastboot");
            return;
        }
        final List<String> q = new ArrayList<>();
        for (Row r : rows) {
            if (r.cb != null && r.cb.isChecked()) q.add(fbName(r.p.name));
        }
        if (q.isEmpty()) {
            for (Row r : rows) {
                if (r.file != null && !r.blocked) q.add(fbName(r.p.name));
            }
        }
        if (q.isEmpty()) {
            toast("belum ada partisi, pilih file scatter dulu");
            return;
        }
        dumpQueue.clear();
        dumpQueue.addAll(q);

        new AlertDialog.Builder(this)
                .setTitle("Dump " + q.size() + " partisi")
                .setMessage("Hasil dibaca dari HP lewat perintah fetch fastboot, lalu disimpan ke folder yang kamu pilih.\n\n"
                        + "Syarat: bootloader mendukung fetch (getvar max-fetch-size). Kalau tidak didukung, "
                        + "partisi itu akan dilewati dan dicatat di log. Tidak ada data yang diubah di HP.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Pilih folder", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try {
                        startActivityForResult(i, REQ_DUMP);
                    } catch (Exception e) {
                        log("pemilih folder tidak tersedia: " + e.getMessage());
                    }
                })
                .show();
    }

    private void doDump(Uri tree) {
        final List<String> q = new ArrayList<>(dumpQueue);
        runBg("dump", () -> {
            int ok = 0;
            int fail = 0;
            long bytes = 0;
            log("dump " + q.size() + " partisi");
            log("dukungan fetch: max-fetch-size = " + fastboot.maxFetchSize());

            for (String part : q) {
                status("dump " + part);
                Uri doc = null;
                try {
                    doc = DocumentsContract.createDocument(getContentResolver(), tree,
                            "application/octet-stream", Readback.fileName(part));
                    if (doc == null) throw new IOException("tidak bisa membuat berkas " + Readback.fileName(part));
                    OutputStream out = getContentResolver().openOutputStream(doc);
                    if (out == null) throw new IOException("tidak bisa menulis berkas");
                    try (OutputStream o = out) {
                        long n = fastboot.dumpPartition(part, o,
                                (pct, msg) -> status(part + " " + pct + "% " + msg));
                        bytes += n;
                        log(String.format(Locale.US, "  OK    %-16s %10s", part, FastbootProtocol.human(n)));
                    }
                    ok++;
                } catch (Exception e) {
                    fail++;
                    log("  GAGAL " + part + ": " + e.getMessage());
                    if (doc != null) {
                        try {
                            DocumentsContract.deleteDocument(getContentResolver(), doc);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            log("dump selesai: " + ok + " berhasil, " + fail + " gagal, "
                    + FastbootProtocol.human(bytes) + " ditulis");
            status("dump selesai");
            return null;
        });
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
            log("BROM tidak terdeteksi: tidak ada USB dengan VID 0x0E8D");
            log("yang perlu dicek: OTG nyala, kabel data (bukan kabel charge), "
                    + "HP target sudah dimatikan lalu dicolok sambil tahan volume");
            log("perangkat USB yang terbaca sekarang:");
            int n = 0;
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                n++;
                log(String.format(Locale.US, "  %04X:%04X  %s",
                        d.getVendorId(), d.getProductId(),
                        d.getProductName() == null ? "?" : d.getProductName()));
            }
            if (n == 0) log("  (kosong, tidak ada apa pun di port OTG)");
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

    private void doBypassAuth(boolean kamakiri2) {
        if (brom == null || transport == null) {
            toast("Connect BROM dulu");
            return;
        }
        final boolean useK2 = kamakiri2;
        runBg(useK2 ? "bypass kamakiri2" : "bypass auth", () -> {
            UsbBromLink link = new UsbBromLink(transport);
            AuthBypass ab = new AuthBypass(link, this::log);
            AuthBypass.Options o = new AuthBypass.Options();
            o.var1 = -1;
            o.maxVar1 = 0x20;
            o.kamakiri2 = useK2;
            o.skipAftermath = false;
            log("bypass: primitive=" + (useK2 ? "kamakiri2 (SET_LINE_CODING)" : "kamakiri (0xE0)"));
            AuthBypass.Result r = ab.run(o);
            log("");
            log("=== hasil bypass ===");
            for (String line : r.summary().split("\n")) log(line);
            if (!r.evidence.isEmpty()) {
                log("--- evidence ---");
                for (String e : r.evidence) log("  " + e);
            }
            status(r.success ? "bypass OK" : "bypass gagal");
            if (r.success) toast("bypass OK: blacklist hilang, DA unsigned diterima");
            return null;
        });
    }

    private void doDisconnect() {
        closeTransport();
        status("terputus");
    }

    private void openTerminal() {
        if (transport != null || fastboot != null) {
            closeTransport();
            status("koneksi dilepas untuk terminal");
        }
        try {
            startActivity(new Intent(this, TerminalActivity.class));
        } catch (Exception e) {
            log("tidak bisa membuka terminal: " + e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDeviceList();
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

    private List<BromDump.Target> bromTargets() {
        List<BromDump.Target> all = BromDump.targets(scatter == null ? null : scatter.partitions);
        List<String> picked = new ArrayList<>();
        for (Row r : rows) {
            if (r.cb != null && r.cb.isChecked()) picked.add(fbName(r.p.name));
        }
        return BromDump.filter(all, picked);
    }

    private void startBromDump() {
        if (brom == null) {
            toast("Connect BROM dulu, HP harus di mode BROM");
            return;
        }
        if (scatter == null) {
            toast("pilih file scatter dulu");
            return;
        }
        List<BromDump.Target> q = bromTargets();
        if (q.isEmpty()) {
            toast("scatter tidak punya alamat fisik partisi");
            return;
        }
        if (daData == null) {
            pickDa();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Dump " + q.size() + " partisi via BROM")
                .setMessage("Alur: handshake BROM, upload DA, lalu baca flash per alamat fisik partisi.\n\n"
                        + "DA: " + daName + "\n"
                        + "Total: " + FastbootProtocol.human(BromDump.total(q)) + "\n\n"
                        + "Pakai DA resmi dari ROM atau SP Flash Tool punyamu sendiri.\n"
                        + "Chip dengan SLA/DAA akan menolak DA tanpa tanda tangan vendor, "
                        + "aplikasi berhenti dan tidak menembus kunci.\n"
                        + "Hanya membaca, tidak ada data di HP yang diubah.")
                .setNegativeButton("Batal", null)
                .setNeutralButton("Ganti DA", (d, w) -> pickDa())
                .setPositiveButton("Lanjut", (d, w) -> pickBromOut())
                .show();
    }

    private void pickDa() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_DA);
        } catch (Exception e) {
            log("pemilih berkas tidak tersedia: " + e.getMessage());
        }
    }

    private void pickBromOut() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_BROM_OUT);
        } catch (Exception e) {
            log("pemilih folder tidak tersedia: " + e.getMessage());
        }
    }

    private void loadDa(Uri u) {
        runBg("baca DA", () -> {
            byte[] all;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                if (in == null) throw new IOException("tidak bisa membuka berkas DA");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                all = bo.toByteArray();
            }
            if (all.length < 1024) throw new IOException("berkas DA terlalu kecil");
            daData = all;
            daName = String.valueOf(u.getLastPathSegment());
            log("DA dimuat: " + daName + " (" + FastbootProtocol.human(all.length) + ")");
            status("DA siap, tekan Dump BROM lagi");
            return null;
        });
    }

    private void doBromDump(Uri tree) {
        final List<BromDump.Target> q = bromTargets();
        if (q.isEmpty() || daData == null) {
            toast("target atau DA belum siap");
            return;
        }
        final byte[] da = daData;
        runBg("dump brom", () -> {
            int ok = 0;
            int fail = 0;
            long done = 0;
            log("dump BROM " + q.size() + " partisi, total " + FastbootProtocol.human(BromDump.total(q)));
            log("DA: " + daName);

            brom.handshake(4);
            log(String.format(Locale.US, "BROM tersambung, chip 0x%04X", brom.getHwCode()));

            File tmp = new File(getCacheDir(), "da.bin");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(da);
            }
            List<DaLoader.DaImage> imgs = DaLoader.load(tmp);
            log("DA berisi " + imgs.size() + " bagian");
            for (DaLoader.DaImage img : imgs) {
                status("upload DA " + img.name);
                brom.sendDa(img.loadAddr, img.data, 0, (pct, msg) -> status("DA " + pct + "%"));
            }
            brom.jumpDa(imgs.get(0).loadAddr);
            DaProtocol da2 = new DaProtocol(transport, this);
            da2.sync();
            log("DA jalan");

            for (BromDump.Target t : q) {
                status("baca " + t.name);
                Uri doc = null;
                try {
                    doc = DocumentsContract.createDocument(getContentResolver(), tree,
                            "application/octet-stream", BromDump.fileName(t.name));
                    if (doc == null) throw new IOException("tidak bisa membuat berkas");
                    long n;
                    try (OutputStream o = getContentResolver().openOutputStream(doc)) {
                        if (o == null) throw new IOException("tidak bisa menulis berkas");
                        n = da2.readBack(t.addr, t.size, o,
                                (pct, msg) -> status(t.name + " " + pct + "%"));
                    }
                    done += n;
                    ok++;
                    log("  OK    " + t.label() + " -> " + BromDump.fileName(t.name));
                } catch (Exception e) {
                    fail++;
                    log("  GAGAL " + t.name + ": " + e.getMessage());
                    if (doc != null) {
                        try {
                            DocumentsContract.deleteDocument(getContentResolver(), doc);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            try {
                da2.finish();
            } catch (Exception ignored) {
            }
            log("dump BROM selesai: " + ok + " berhasil, " + fail + " gagal, "
                    + FastbootProtocol.human(done));
            status("dump BROM selesai");
            return null;
        });
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

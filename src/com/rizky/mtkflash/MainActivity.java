package com.rizky.mtkflash;

import android.app.Activity;
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
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Antarmuka utama MTK Flash.
 *
 * Alur kerja mengikuti SP Flash Tool:
 *   1. sambungkan HP dalam mode BROM lewat OTG
 *   2. connect  -> handshake, baca identitas chip
 *   3. unggah DA -> perangkat pindah ke mode Download Agent
 *   4. pilih scatter + folder image -> flash
 */
public class MainActivity extends Activity implements BromProtocol.Log {

    private static final String ACTION_USB_PERM = "com.rizky.mtkflash.USB_PERMISSION";
    private static final int REQ_SCATTER = 1001;
    private static final int REQ_DA = 1002;
    private static final int REQ_FB_IMG = 1003;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String> logLines = new ArrayList<>();

    private UsbManager usbManager;
    private UsbTransport transport;
    private BromProtocol brom;
    private DaProtocol da;
    private FastbootProtocol fastboot;

    private TextView statusView;
    private TextView logView;

    private ScatterParser.Scatter scatter;
    private File daFile;
    private int daLoadAddr = DaLoader.DEFAULT_LOAD_ADDR;
    private EditText partInput;
    private File fbImage;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String a = intent.getAction();
            if (ACTION_USB_PERM.equals(a)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (ok && d != null) {
                    openDevice(d);
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

    /* ------------------------------------------------------------------ */

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

        log("MTK Flash siap.");
        log("Sambungkan HP lewat OTG dalam mode BROM (tekan volume+ lalu colok kabel,");
        log("atau lepas baterai lalu colok) supaya terdeteksi sebagai 0x0E8D.");
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

    /* ------------------------------------------------------------------ */
    /*  UI                                                                 */
    /* ------------------------------------------------------------------ */

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#12141a"));
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("MTK Flash — MediaTek BROM/DA flasher");
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

        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        row.addView(button("Scan", v -> refreshDeviceList()));
        row.addView(button("Connect", v -> doConnect()));
        row.addView(button("Info chip", v -> doChipInfo()));
        row.addView(button("Pilih DA", v -> pickFile(REQ_DA, "application/octet-stream")));
        row.addView(button("Unggah DA", v -> doUploadDa()));
        row.addView(button("Pilih scatter", v -> pickFile(REQ_SCATTER, "*/*")));
        row.addView(button("Flash", v -> doFlash()));
        row.addView(button("Baca partisi", v -> doReadPartition()));
        row.addView(button("FB scan", v -> doFastbootScan()));
        row.addView(button("FB info", v -> doFastbootInfo()));
        row.addView(button("FB flash", v -> doFastbootFlash()));
        row.addView(button("Putus", v -> closeTransport()));

        hs.addView(row);
        root.addView(hs);

        LinearLayout fbRow = new LinearLayout(this);
        fbRow.setOrientation(LinearLayout.HORIZONTAL);
        fbRow.setPadding(0, dp(6), 0, 0);

        TextView lbl = new TextView(this);
        lbl.setText("partisi fastboot:");
        lbl.setTextColor(Color.parseColor("#9aa4b2"));
        lbl.setTextSize(12);
        lbl.setPadding(0, dp(6), dp(8), 0);
        fbRow.addView(lbl);

        partInput = new EditText(this);
        partInput.setHint("vendor_dlkm");
        partInput.setText("vendor_dlkm");
        partInput.setTextColor(Color.parseColor("#c8d3e0"));
        partInput.setHintTextColor(Color.parseColor("#5a6472"));
        partInput.setTextSize(13);
        partInput.setSingleLine(true);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                dp(200), ViewGroup.LayoutParams.WRAP_CONTENT);
        partInput.setLayoutParams(ilp);
        fbRow.addView(partInput);

        root.addView(fbRow);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#c8d3e0"));
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setTextIsSelectable(true);
        sv.addView(logView);
        root.addView(sv);

        return root;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /* ------------------------------------------------------------------ */
    /*  Log                                                                */
    /* ------------------------------------------------------------------ */

    @Override
    public void i(String s) {
        log(s);
    }

    @Override
    public void e(String s) {
        log("ERROR: " + s);
    }

    private void log(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        ui.post(() -> {
            logLines.add(t + "  " + s);
            if (logLines.size() > 600) logLines.remove(0);
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) sb.append(l).append('\n');
            logView.setText(sb.toString());
        });
    }

    private void status(String s) {
        ui.post(() -> statusView.setText(s));
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    /* ------------------------------------------------------------------ */
    /*  Perangkat                                                          */
    /* ------------------------------------------------------------------ */

    private void refreshDeviceList() {
        Map<String, UsbDevice> devs = usbManager.getDeviceList();
        if (devs.isEmpty()) {
            status("tidak ada perangkat USB");
            log("daftar USB kosong");
            return;
        }
        log("perangkat USB terdeteksi:");
        for (UsbDevice d : devs.values()) {
            log(String.format(Locale.US, "  %s  VID=%04X PID=%04X  %s",
                    d.getDeviceName(), d.getVendorId(), d.getProductId(),
                    d.getProductName() == null ? "" : d.getProductName()));
            if (d.getVendorId() == UsbTransport.MTK_VID) {
                status(String.format("MediaTek terdeteksi: PID %04X", d.getProductId()));
            }
        }
    }

    private UsbDevice findMtk() {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getVendorId() == UsbTransport.MTK_VID) return d;
        }
        return null;
    }

    private void doConnect() {
        UsbDevice d = findMtk();
        if (d == null) {
            toast("perangkat MediaTek (0E8D) tidak ditemukan");
            log("tidak ada perangkat dengan VID 0E8D");
            return;
        }
        if (!usbManager.hasPermission(d)) {
            log("minta izin USB...");
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0;
            usbManager.requestPermission(d, PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERM).setPackage(getPackageName()), flags));
            return;
        }
        openDevice(d);
    }

    private void openDevice(UsbDevice d) {
        closeTransport();
        runBg("connect", () -> {
            transport = new UsbTransport(usbManager, d);
            brom = new BromProtocol(transport, this);
            brom.handshake(50);
            status("terhubung: " + d.getDeviceName());
            return null;
        });
    }

    private void closeTransport() {
        if (fastboot != null) {
            fastboot.close();
            fastboot = null;
            log("fastboot ditutup");
        }
        if (transport != null) {
            transport.close();
            transport = null;
            brom = null;
            da = null;
            status("terputus");
            log("koneksi ditutup");
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Aksi                                                               */
    /* ------------------------------------------------------------------ */

    private void doChipInfo() {
        if (brom == null) {
            toast("connect dulu");
            return;
        }
        runBg("info", () -> {
            brom.getBromVersion();
            brom.getHwCode();
            brom.getHwSwVersion();
            try {
                brom.getTargetConfig();
            } catch (IOException e) {
                log("target config tidak didukung chip ini: " + e.getMessage());
            }
            return null;
        });
    }

    private void doUploadDa() {
        if (brom == null) {
            toast("connect dulu");
            return;
        }
        if (daFile == null) {
            toast("pilih file DA dulu");
            return;
        }
        runBg("unggah DA", () -> {
            List<DaLoader.DaImage> list = DaLoader.load(daFile);
            log("DA ditemukan: " + list.size() + " image");
            DaLoader.DaImage pick = list.get(0);
            if (list.size() > 1) {
                log("memakai image pertama; kalau salah, pilih DA tunggal untuk chip ini");
            }
            log("  " + pick);
            daLoadAddr = pick.loadAddr;

            brom.sendDa(daLoadAddr, pick.data, 0,
                    (pct, msg) -> status("DA " + pct + "%"));

            brom.jumpDa(daLoadAddr);
            log("menunggu DA aktif...");
            sleep(600);

            da = new DaProtocol(transport, this);
            da.sync();
            status("DA aktif, siap menulis");
            return null;
        });
    }

    private void doFlash() {
        if (brom == null) {
            toast("connect dulu");
            return;
        }
        if (da == null) {
            toast("unggah DA dulu");
            return;
        }
        if (scatter == null) {
            toast("pilih file scatter dulu");
            return;
        }
        runBg("flash", () -> {
            File dir = scatterFile().getParentFile();
            log("folder image: " + dir);
            int done = 0, skipped = 0, failed = 0;

            for (ScatterParser.Partition p : scatter.partitions) {
                if (!p.download || !p.hasImage()) {
                    skipped++;
                    continue;
                }
                File img = new File(dir, p.fileName);
                if (!img.exists()) {
                    log("  lewati " + p.name + ": " + p.fileName + " tidak ada");
                    skipped++;
                    continue;
                }
                try {
                    byte[] data = readFile(img);
                    status("menulis " + p.name);
                    log(String.format("tulis %-20s %10d byte -> 0x%08X",
                            p.name, data.length, p.physicalAddr));
                    da.writeData((int) p.physicalAddr, data, (pct, m) -> {
                    });
                    done++;
                } catch (Exception ex) {
                    log("  GAGAL " + p.name + ": " + ex.getMessage());
                    failed++;
                }
            }

            try {
                da.finish();
            } catch (Exception ex) {
                log("finish: " + ex.getMessage());
            }

            log(String.format("selesai: %d ditulis, %d dilewati, %d gagal",
                    done, skipped, failed));
            status("flash selesai");
            return null;
        });
    }

    private void doReadPartition() {
        if (da == null) {
            toast("unggah DA dulu");
            return;
        }
        runBg("baca", () -> {
            byte[] pt = da.readPartitionTable();
            log("tabel partisi dibaca: " + pt.length + " byte");
            int nonzero = 0;
            for (byte x : pt) if (x != 0) nonzero++;
            log("byte tidak nol: " + nonzero);
            return null;
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Fastboot lewat OTG                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Fastboot tidak butuh DA, auth, atau bypass. Yang dibutuhkan hanya
     * bootloader yang sudah dibuka. Ini jalur resmi untuk mem-flash
     * partisi setelah unlock.
     */
    private void doFastbootScan() {
        UsbDevice found = null;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (FastbootProtocol.isFastboot(d)) {
                found = d;
                break;
            }
        }
        if (found == null) {
            log("tidak ada perangkat dengan antarmuka fastboot (FF/42/03)");
            log("pastikan HP sudah di mode fastboot/bootloader dan bootloader unlocked");
            toast("perangkat fastboot tidak ditemukan");
            return;
        }
        log(String.format(Locale.US, "fastboot ditemukan: %s VID=%04X PID=%04X",
                found.getDeviceName(), found.getVendorId(), found.getProductId()));
        status("fastboot: " + found.getDeviceName());

        final UsbDevice dev = found;
        if (!usbManager.hasPermission(dev)) {
            log("minta izin USB...");
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0;
            usbManager.requestPermission(dev, PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERM).setPackage(getPackageName()), flags));
            return;
        }
        openFastboot(dev);
    }

    private void openFastboot(UsbDevice d) {
        runBg("fastboot open", () -> {
            if (fastboot != null) {
                fastboot.close();
                fastboot = null;
            }
            fastboot = new FastbootProtocol(usbManager, d);
            log("fastboot terhubung");
            return null;
        });
    }

    private void doFastbootInfo() {
        if (fastboot == null) {
            toast("FB scan dulu");
            return;
        }
        runBg("fb info", () -> {
            String[] vars = {"product", "variant", "serialno", "version-bootloader",
                    "version-baseband", "current-slot", "max-download-size",
                    "unlocked", "secure", "slot-count"};
            for (String v : vars) {
                try {
                    log(String.format("  %-20s %s", v, fastboot.getVar(v)));
                } catch (IOException e) {
                    log(String.format("  %-20s (tidak tersedia)", v));
                }
            }
            status("info fastboot selesai");
            return null;
        });
    }

    private void doFastbootFlash() {
        if (fastboot == null) {
            toast("FB scan dulu");
            return;
        }
        if (fbImage == null) {
            pickFile(REQ_FB_IMG, "*/*");
            toast("pilih file image dulu, lalu tekan FB flash lagi");
            return;
        }
        String part = partInput.getText().toString().trim();
        if (part.isEmpty()) {
            toast("isi nama partisi");
            return;
        }
        runBg("fb flash", () -> {
            byte[] img = readFile(fbImage);
            log(String.format("flash %s <- %s (%d byte)",
                    part, fbImage.getName(), img.length));
            status("fastboot flash " + part);
            fastboot.flash(part, img, (pct, m) -> status("FB " + pct + "%"));
            log("OKAY — " + part + " tertulis");
            status("flash selesai");
            return null;
        });
    }

    /* ------------------------------------------------------------------ */
    /*  Berkas                                                             */
    /* ------------------------------------------------------------------ */

    private File scatterFile() {
        return new File(scatterPath);
    }

    private String scatterPath = "";

    private void pickFile(int req, String mime) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType(mime);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "pilih file"), req);
        } catch (Exception e) {
            toast("tidak ada aplikasi pemilih berkas");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            String name = req == REQ_DA ? "da.bin"
                    : req == REQ_FB_IMG ? "fb_image.img" : "scatter.txt";
            File tmp = copyToCache(uri, name);
            if (req == REQ_DA) {
                daFile = tmp;
                log("DA dipilih: " + tmp.getAbsolutePath() + " (" + tmp.length() + " byte)");
            } else if (req == REQ_FB_IMG) {
                fbImage = tmp;
                log("image fastboot dipilih: " + tmp.getAbsolutePath()
                        + " (" + tmp.length() + " byte)");
                status("siap flash fastboot: " + tmp.length() + " byte");
            } else {
                scatterPath = tmp.getAbsolutePath();
                scatter = ScatterParser.parse(tmp);
                log("scatter: platform=" + scatter.platform
                        + " project=" + scatter.project
                        + " storage=" + scatter.storage);
                log("partisi: " + scatter.partitions.size());
                for (ScatterParser.Partition p : scatter.partitions) {
                    log("  " + p);
                }
                status("scatter dimuat: " + scatter.partitions.size() + " partisi");
            }
        } catch (Exception e) {
            log("gagal membaca berkas: " + e.getMessage());
        }
    }

    private File copyToCache(Uri uri, String name) throws IOException {
        File out = new File(getCacheDir(), name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IOException("stream kosong");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        return out;
    }

    private static byte[] readFile(File f) throws IOException {
        long len = f.length();
        if (len <= 0 || len > 512L * 1024 * 1024) throw new IOException("ukuran tidak wajar");
        byte[] b = new byte[(int) len];
        try (InputStream in = new java.io.FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
        }
        return b;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Utilitas thread                                                    */
    /* ------------------------------------------------------------------ */

    private interface Job {
        Object run() throws Exception;
    }

    private void runBg(String name, Job job) {
        new Thread(() -> {
            try {
                job.run();
            } catch (Exception e) {
                log("[" + name + "] gagal: " + e.getMessage());
                status(name + " gagal");
            }
        }, name).start();
    }
}

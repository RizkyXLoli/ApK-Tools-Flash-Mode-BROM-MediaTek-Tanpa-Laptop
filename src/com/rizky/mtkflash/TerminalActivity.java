package com.rizky.mtkflash;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class TerminalActivity extends Activity {

    private static final String ACTION_USB_PERM = "com.rizky.mtkflash.TERM_USB_PERMISSION";
    private static final int REQ_IMG = 2001;

    private static final Set<String> FB_WORDS = new HashSet<>(Arrays.asList(
            "flash", "erase", "getvar", "reboot", "reboot-bootloader", "oem",
            "continue", "devices", "set_active", "format"));

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder out = new StringBuilder();

    private UsbManager usbManager;
    private TextView outView;
    private EditText input;
    private ScrollView outScroll;

    private Runnable afterPerm;
    private String pendingPart;
    private int lastPct = -1;

    private interface FbJob {
        void run(FastbootProtocol fb) throws Exception;
    }

    private interface AdbJob {
        void run(AdbClient adb) throws Exception;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (ACTION_USB_PERM.equals(intent.getAction())) {
                boolean ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Runnable r = afterPerm;
                afterPerm = null;
                if (ok && r != null) {
                    echo("izin USB diberikan");
                    r.run();
                } else {
                    echo("izin USB ditolak — perintah dibatalkan");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                echo("perangkat tersambung: " + name(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)));
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                echo("perangkat terlepas");
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

        echo("terminal manual — tulis perintah lalu tekan Kirim");
        echo("ketik help untuk daftar perintah");
        echo("contoh: fastboot devices   |   fastboot flash boot   |   adb shell getprop ro.product.model");
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#12141a"));
        root.setPadding(dp(10), dp(10), dp(10), dp(8));

        TextView title = new TextView(this);
        title.setText("Terminal ADB / Fastboot");
        title.setTextColor(Color.parseColor("#7fd1ff"));
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        outView = new TextView(this);
        outView.setTextColor(Color.parseColor("#c8d3e0"));
        outView.setTextSize(11);
        outView.setTypeface(android.graphics.Typeface.MONOSPACE);
        outView.setTextIsSelectable(true);
        outView.setMovementMethod(new ScrollingMovementMethod());
        outScroll = new ScrollView(this);
        outScroll.addView(outView);
        outScroll.setBackgroundColor(Color.parseColor("#0d0f14"));
        outScroll.setPadding(dp(6), dp(6), dp(6), dp(6));
        outScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f));
        root.addView(outScroll);

        input = new EditText(this);
        input.setHint("tulis perintah...");
        input.setHintTextColor(Color.parseColor("#5d6675"));
        input.setTextColor(Color.parseColor("#dfe6f2"));
        input.setTextSize(12);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setBackground(cellBg("#1c2230", "#2c3547"));
        input.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.setMargins(dp(3), dp(3), dp(3), dp(3));
        input.setLayoutParams(ilp);

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);
        sendRow.addView(input);
        Button send = btn("Kirim");
        send.setOnClickListener(v -> {
            String s = input.getText().toString();
            input.setText("");
            runLine(s);
        });
        sendRow.addView(send);
        root.addView(sendRow);

        Button bDev = btn("fastboot devices");
        bDev.setOnClickListener(v -> runLine("fastboot devices"));
        Button bVar = btn("fastboot getvar all");
        bVar.setOnClickListener(v -> runLine("fastboot getvar all"));
        Button bAdb = btn("adb devices");
        bAdb.setOnClickListener(v -> runLine("adb devices"));
        Button bProp = btn("adb shell getprop");
        bProp.setOnClickListener(v -> runLine("adb shell getprop"));
        Button bClr = btn("clear");
        bClr.setOnClickListener(v -> clearOut());
        Button bBack = btn("kembali");
        bBack.setOnClickListener(v -> finish());
        root.addView(row(bDev, bVar, bAdb, bProp, bClr, bBack));

        return root;
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(10);
        b.setTextColor(Color.parseColor("#dfe6f2"));
        b.setBackground(cellBg("#1c2230", "#2c3547"));
        b.setStateListAnimator(null);
        b.setIncludeFontPadding(false);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private View row(Button... buttons) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (Button b : buttons) {
            line.addView(b);
        }
        return line;
    }

    private android.graphics.drawable.GradientDrawable cellBg(String fill, String stroke) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(6));
        d.setColor(Color.parseColor(fill));
        d.setStroke(dp(1), Color.parseColor(stroke));
        return d;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void echo(String s) {
        ui.post(() -> {
            out.append(s).append('\n');
            if (out.length() > 400000) out.delete(0, out.length() - 300000);
            if (outView != null) outView.setText(out.toString());
            if (outScroll != null) outScroll.post(() -> outScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void clearOut() {
        ui.post(() -> {
            out.setLength(0);
            if (outView != null) outView.setText("");
        });
    }

    private void bg(Runnable r) {
        new Thread(r, "terminal").start();
    }

    private static String msg(Throwable e) {
        String s = e.getMessage();
        return s == null ? e.toString() : s;
    }

    private static String name(UsbDevice d) {
        if (d == null) return "?";
        return String.format(Locale.US, "%04X:%04X %s",
                d.getVendorId(), d.getProductId(), d.getDeviceName());
    }

    private void runLine(String raw) {
        final String line = raw == null ? "" : raw.trim();
        if (line.isEmpty()) return;
        echo("> " + line);
        final List<String> t = new ArrayList<>(Arrays.asList(line.split("\\s+")));
        final String head = t.get(0).toLowerCase(Locale.US);

        if ("clear".equals(head) || "cls".equals(head)) {
            clearOut();
            return;
        }
        if ("help".equals(head) || "?".equals(head) || "bantuan".equals(head)) {
            help();
            return;
        }
        if ("fastboot".equals(head) || "fb".equals(head)) {
            fastboot(t.subList(1, t.size()));
            return;
        }
        if ("adb".equals(head)) {
            adb(t.subList(1, t.size()));
            return;
        }
        if (FB_WORDS.contains(head)) {
            fastboot(t);
            return;
        }
        echo("perintah tidak dikenal: " + head + "   (ketik help)");
    }

    private void help() {
        echo("--- fastboot ---");
        echo("fastboot devices              daftar HP mode fastboot");
        echo("fastboot flash <partisi>      pilih file image lalu tulis");
        echo("fastboot erase <partisi>      hapus partisi");
        echo("fastboot getvar <nama>        baca variabel bootloader");
        echo("fastboot getvar all           semua variabel");
        echo("fastboot reboot               restart HP");
        echo("fastboot reboot-bootloader    kembali ke fastboot");
        echo("fastboot oem <perintah>       perintah oem apa adanya");
        echo("--- adb ---");
        echo("adb devices                   daftar HP dengan USB debugging");
        echo("adb shell <perintah>          jalankan perintah shell di HP");
        echo("adb getprop [nama]            baca properti sistem");
        echo("adb reboot [bootloader|recovery]");
        echo("--- lain ---");
        echo("clear                         bersihkan layar terminal");
    }

    private UsbDevice findFastboot() {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (FastbootProtocol.isFastboot(d)) return d;
        }
        return null;
    }

    private UsbDevice findAdb() {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (AdbClient.isAdb(d)) return d;
        }
        return null;
    }

    private void requestPerm(UsbDevice d) {
        try {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERM).setPackage(getPackageName()),
                    PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(d, pi);
        } catch (Exception e) {
            echo("tidak bisa minta izin USB: " + msg(e));
            afterPerm = null;
        }
    }

    private void withFb(FbJob job) {
        UsbDevice d = findFastboot();
        if (d == null) {
            echo("tidak ada perangkat fastboot.");
            echo("matikan HP, tahan Volume Turun + Power sampai layar fastboot, lalu colok kabel data.");
            return;
        }
        if (!usbManager.hasPermission(d)) {
            echo("minta izin USB untuk " + name(d));
            afterPerm = () -> withFb(job);
            requestPerm(d);
            return;
        }
        bg(() -> {
            FastbootProtocol f = null;
            try {
                f = new FastbootProtocol(usbManager, d);
                job.run(f);
            } catch (Exception e) {
                echo("gagal: " + msg(e));
            } finally {
                if (f != null) f.close();
            }
        });
    }

    private void withAdb(AdbJob job) {
        UsbDevice d = findAdb();
        if (d == null) {
            echo("tidak ada perangkat ADB.");
            echo("aktifkan Opsi Pengembang > USB debugging di HP, lalu colok kabel data.");
            return;
        }
        if (!usbManager.hasPermission(d)) {
            echo("minta izin USB untuk " + name(d));
            afterPerm = () -> withAdb(job);
            requestPerm(d);
            return;
        }
        bg(() -> {
            AdbClient c = null;
            try {
                c = new AdbClient(usbManager, d, AdbClient.loadOrCreate(this));
                job.run(c);
            } catch (Exception e) {
                echo("gagal: " + msg(e));
            } finally {
                if (c != null) c.close();
            }
        });
    }

    private void fastboot(List<String> a) {
        if (a.isEmpty() || "devices".equals(a.get(0))) {
            int n = 0;
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                if (FastbootProtocol.isFastboot(d)) {
                    echo(name(d) + "\tfastboot");
                    n++;
                }
            }
            if (n == 0) {
                echo("daftar kosong — HP belum di mode fastboot");
                echo("matikan HP, tahan Volume Turun + Power, lalu colok kabel data.");
            }
            return;
        }
        final String sub = a.get(0).toLowerCase(Locale.US);
        if ("flash".equals(sub)) {
            if (a.size() < 2) {
                echo("format: fastboot flash <partisi>");
                return;
            }
            pendingPart = a.get(1);
            echo("pilih file image untuk partisi " + pendingPart + " ...");
            pickImage();
            return;
        }
        if ("erase".equals(sub)) {
            if (a.size() < 2) {
                echo("format: fastboot erase <partisi>");
                return;
            }
            final String part = a.get(1);
            withFb(fb -> {
                echo("hapus " + part + " ...");
                fb.erase(part);
                echo("selesai: " + part + " dihapus");
            });
            return;
        }
        if ("getvar".equals(sub)) {
            final String var = a.size() > 1 ? a.get(1) : "all";
            withFb(fb -> {
                if ("all".equals(var)) {
                    echo(fb.command("getvar:all") + "");
                    echo("catatan: sebagian bootloader hanya mengirim jawaban lewat baris INFO");
                } else {
                    echo(var + " = " + fb.getVar(var));
                }
            });
            return;
        }
        final String cmd = join(a);
        withFb(fb -> {
            echo("kirim: " + cmd);
            FastbootProtocol.Response r = fb.command(cmd);
            echo("jawab: " + r);
            if (sub.startsWith("reboot")) echo("HP diminta restart — kabel bisa dilepas");
        });
    }

    private void adb(List<String> a) {
        if (a.isEmpty() || "devices".equals(a.get(0))) {
            int n = 0;
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                if (AdbClient.isAdb(d)) {
                    echo(name(d) + "\tdevice");
                    n++;
                }
            }
            if (n == 0) {
                echo("daftar kosong — USB debugging belum aktif atau HP belum dicolok");
            }
            return;
        }
        final String sub = a.get(0).toLowerCase(Locale.US);
        if ("shell".equals(sub)) {
            final String cmd = join(a.subList(1, a.size()));
            if (cmd.isEmpty()) {
                echo("format: adb shell <perintah>");
                return;
            }
            withAdb(c -> {
                String r = c.shell(cmd, this::echo);
                if (r.isEmpty()) echo("(selesai, tanpa keluaran)");
            });
            return;
        }
        if ("getprop".equals(sub)) {
            final String p = a.size() > 1 ? join(a.subList(1, a.size())) : "";
            withAdb(c -> {
                String r = c.shell(p.isEmpty() ? "getprop" : "getprop " + p, this::echo);
                if (r.trim().isEmpty()) echo("(kosong)");
            });
            return;
        }
        if ("reboot".equals(sub)) {
            final String to = a.size() > 1 ? a.get(1) : "";
            withAdb(c -> {
                c.shell("reboot " + to, this::echo);
                echo("HP diminta restart" + (to.isEmpty() ? "" : " ke " + to));
            });
            return;
        }
        echo("perintah adb yang didukung: devices, shell <perintah>, getprop [nama], reboot [bootloader|recovery]");
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_IMG);
        } catch (Exception e) {
            echo("pemilih berkas tidak tersedia: " + msg(e));
            pendingPart = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMG) return;
        final String part = pendingPart;
        pendingPart = null;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            echo("pemilihan berkas dibatalkan");
            return;
        }
        if (part == null) return;
        final Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        withFb(fb -> {
            long size = sizeOf(uri);
            File tmp = null;
            InputStream in;
            if (size > 0) {
                in = getContentResolver().openInputStream(uri);
            } else {
                tmp = materialize(uri);
                size = tmp.length();
                in = new java.io.FileInputStream(tmp);
            }
            if (in == null) throw new IOException("tidak bisa membuka berkas image");
            echo("flash " + part + " — " + FastbootProtocol.human(size));
            lastPct = -1;
            try {
                fb.flashStream(part, size, in, (pct, m) -> {
                    if (pct >= lastPct + 5 || pct == 100) {
                        lastPct = pct;
                        echo(String.format(Locale.US, "  %3d%%  %s", pct, m));
                    }
                });
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
                if (tmp != null) tmp.delete();
            }
            echo("selesai: " + part + " sudah ditulis");
        });
    }

    private long sizeOf(Uri u) {
        Cursor c = null;
        try {
            c = getContentResolver().query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private File materialize(Uri u) throws IOException {
        File f = new File(getCacheDir(), "image.tmp");
        try (InputStream in = getContentResolver().openInputStream(u);
             OutputStream o = new FileOutputStream(f)) {
            if (in == null) throw new IOException("tidak bisa membuka berkas image");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        return f;
    }

    private static String join(List<String> a) {
        StringBuilder sb = new StringBuilder();
        for (String s : a) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
    }
}

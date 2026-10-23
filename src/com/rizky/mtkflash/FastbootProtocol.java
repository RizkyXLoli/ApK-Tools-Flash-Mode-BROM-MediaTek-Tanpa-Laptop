package com.rizky.mtkflash;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class FastbootProtocol {

    private static final int IFACE_CLASS = 0xFF;
    private static final int IFACE_SUBCLASS = 0x42;
    private static final int IFACE_PROTOCOL = 0x03;

    private static final int MAX_RESPONSE = 64;

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;

    public FastbootProtocol(UsbManager manager, UsbDevice device) throws IOException {
        this.device = device;
        this.connection = manager.openDevice(device);
        if (connection == null) {
            throw new IOException("gagal membuka perangkat USB (izin ditolak?)");
        }

        UsbEndpoint in = null, out = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface itf = device.getInterface(i);
            if (itf.getInterfaceClass() != IFACE_CLASS
                    || itf.getInterfaceSubclass() != IFACE_SUBCLASS
                    || itf.getInterfaceProtocol() != IFACE_PROTOCOL) {
                continue;
            }
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                UsbEndpoint ep = itf.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN && in == null) in = ep;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT && out == null) out = ep;
            }
            if (in != null && out != null) {
                connection.claimInterface(itf, true);
                break;
            }
        }

        if (in == null || out == null) {
            connection.close();
            throw new IOException("antarmuka fastboot (FF/42/03) tidak ditemukan");
        }
        this.epIn = in;
        this.epOut = out;
    }

    public static boolean isFastboot(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() == IFACE_CLASS
                    && itf.getInterfaceSubclass() == IFACE_SUBCLASS
                    && itf.getInterfaceProtocol() == IFACE_PROTOCOL) {
                return true;
            }
        }
        return false;
    }

    public static class Response {
        public String status;
        public String message;

        public boolean ok() {
            return "OKAY".equals(status);
        }

        @Override
        public String toString() {
            return message == null || message.isEmpty() ? status : status + ": " + message;
        }
    }

    public Response command(String cmd) throws IOException {
        byte[] b = cmd.getBytes(StandardCharsets.US_ASCII);
        int n = connection.bulkTransfer(epOut, b, b.length, 5000);
        if (n <= 0) throw new IOException("gagal mengirim perintah: " + cmd);
        return readResponse();
    }

    private Response readResponse() throws IOException {
        StringBuilder info = new StringBuilder();
        for (int guard = 0; guard < 32; guard++) {
            byte[] buf = new byte[MAX_RESPONSE];
            int n = connection.bulkTransfer(epIn, buf, MAX_RESPONSE, 5000);
            if (n < 4) throw new IOException("respons fastboot tidak valid (n=" + n + ")");

            Response r = new Response();
            r.status = new String(buf, 0, 4, StandardCharsets.US_ASCII);

            int end = n;
            while (end > 4 && buf[end - 1] == 0) end--;
            r.message = new String(buf, 4, Math.max(0, end - 4), StandardCharsets.US_ASCII);

            if ("INFO".equals(r.status) || "TEXT".equals(r.status)) {
                if (r.message != null && !r.message.isEmpty()) {
                    info.append(r.message).append('\n');
                }
                continue;
            }
            if (info.length() > 0) {
                r.message = info + (r.message == null ? "" : r.message);
            }
            return r;
        }
        throw new IOException("terlalu banyak pesan INFO dari perangkat");
    }

    public String getVar(String name) throws IOException {
        Response r = command("getvar:" + name);
        if (!r.ok()) throw new IOException("getvar:" + name + " gagal — " + r.message);
        return r.message == null ? "" : r.message.trim();
    }

    public long maxDownloadSize() throws IOException {
        String v = getVar("max-download-size");
        try {
            return Long.parseLong(v.replace("0x", ""), 16);
        } catch (NumberFormatException e) {
            return 0x10000000L;
        }
    }

    public long maxFetchSize() {
        try {
            return Readback.parseHex(getVar("max-fetch-size"));
        } catch (IOException e) {
            return -1;
        }
    }

    public long partitionSize(String partition) {
        try {
            return Readback.parseHex(getVar("partition-size:" + partition));
        } catch (IOException e) {
            return -1;
        }
    }

    public long fetchTo(String partition, long offset, long size, OutputStream out,
                        BromProtocol.Progress progress) throws IOException {
        if (size <= 0) throw new IOException("ukuran baca tidak valid untuk " + partition);

        Response d = command(Readback.fetchCmd(partition, offset, size));
        if (!"DATA".equals(d.status)) {
            throw new IOException("bootloader menolak fetch — " + d);
        }

        byte[] buf = new byte[65536];
        long got = 0;
        while (got < size) {
            int want = (int) Math.min(buf.length, size - got);
            int n = connection.bulkTransfer(epIn, buf, want, 15000);
            if (n <= 0) throw new IOException("data terputus di " + got + "/" + size + " byte");
            out.write(buf, 0, n);
            got += n;
            if (progress != null) {
                progress.onProgress((int) (got * 100 / size),
                        human(got) + " / " + human(size));
            }
        }

        Response ack = readResponse();
        if (!ack.ok()) throw new IOException("fetch gagal — " + ack);
        return got;
    }

    public long dumpPartition(String partition, OutputStream out,
                              BromProtocol.Progress progress) throws IOException {
        long total = partitionSize(partition);
        if (total <= 0) throw new IOException("ukuran partisi " + partition + " tidak terbaca");
        long max = maxFetchSize();
        if (max <= 0) throw new IOException("bootloader tidak mendukung fetch");

        List<long[]> plan = Readback.plan(total, max);
        if (plan.isEmpty()) throw new IOException("rencana baca kosong untuk " + partition);

        long done = 0;
        for (long[] c : plan) {
            final long base = done;
            final long len = c[1];
            fetchTo(partition, c[0], len, out, (pct, msg) -> {
                if (progress != null) {
                    progress.onProgress((int) ((base + len * pct / 100) * 100 / total), msg);
                }
            });
            done += len;
        }
        return done;
    }

    public void flash(String partition, byte[] image, BromProtocol.Progress progress)
            throws IOException {
        flashStream(partition, image.length, new ByteArrayInputStream(image), progress);
    }

    public void flashStream(String partition, long length, InputStream in,
                            BromProtocol.Progress progress) throws IOException {
        if (length <= 0) {
            throw new IOException("ukuran image tidak diketahui untuk " + partition);
        }

        long max = maxDownloadSize();
        if (max > 0 && length > max) {
            throw new IOException(String.format(Locale.US,
                    "image %s byte melebihi batas bootloader (%s byte) — partisi ini harus lewat PC",
                    human(length), human(max)));
        }

        Response d = command(String.format(Locale.US, "download:%08x", length));
        if (!"DATA".equals(d.status)) {
            throw new IOException("bootloader menolak download — " + d);
        }

        byte[] buf = new byte[1048576];
        long sent = 0;
        while (sent < length) {
            int want = (int) Math.min(buf.length, length - sent);
            int got = in.read(buf, 0, want);
            if (got <= 0) {
                throw new IOException("berkas berakhir di " + sent + "/" + length + " byte");
            }
            int off = 0;
            while (off < got) {
                int n = connection.bulkTransfer(epOut, buf, off, got - off, 15000);
                if (n <= 0) throw new IOException("gagal mengirim data di offset " + (sent + off));
                off += n;
            }
            sent += got;
            if (progress != null) {
                progress.onProgress((int) (sent * 100 / length),
                        "kirim " + human(sent) + "/" + human(length));
            }
        }

        Response ack = readResponse();
        if (!ack.ok()) throw new IOException("data ditolak — " + ack);

        Response f = command("flash:" + partition);
        if (!f.ok()) throw new IOException("flash " + partition + " gagal — " + f);
    }

    public static String human(long v) {
        if (v < 1024) return v + " B";
        if (v < 1048576) return String.format(Locale.US, "%.1f KB", v / 1024.0);
        if (v < 1073741824L) return String.format(Locale.US, "%.1f MB", v / 1048576.0);
        return String.format(Locale.US, "%.2f GB", v / 1073741824.0);
    }

    public void erase(String partition) throws IOException {
        Response r = command("erase:" + partition);
        if (!r.ok()) throw new IOException("erase " + partition + " gagal — " + r);
    }

    public void reboot() throws IOException {
        try {
            command("reboot");
        } catch (IOException ignored) {

        }
    }

    public void close() {
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }
}

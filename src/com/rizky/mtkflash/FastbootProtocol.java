package com.rizky.mtkflash;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Klien protokol Fastboot lewat USB OTG.
 *
 * Fastboot adalah protokol terbuka dari AOSP (system/core/fastboot).
 * Tidak ada autentikasi di dalamnya: perangkat yang bootloader-nya sudah
 * dibuka akan menerima perintah flash apa adanya. Karena itu jalur ini
 * tidak butuh DA, tidak butuh file auth, dan tidak butuh bypass apa pun.
 *
 * Cara mengenali antarmuka fastboot (AOSP fastboot_usb):
 *   interface class    = 0xFF (vendor specific)
 *   interface subclass = 0x42 ('B')
 *   interface protocol = 0x03
 *
 * Bentuk satu perintah:
 *   host -> "download:00001000"   (ASCII)
 *   dev  -> "DATA" + 8 digit hex  (sisa kapasitas)
 *   host -> payload biner
 *   dev  -> "OKAY"
 *   host -> "flash:boot"
 *   dev  -> "OKAY"
 *
 * Status yang mungkin: OKAY, FAIL, DATA, INFO, TEXT.
 */
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

    /** True kalau perangkat ini punya antarmuka fastboot. */
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

    /* ------------------------------------------------------------------ */

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

    /** Kirim satu perintah ASCII, kembalikan respons pertama yang bukan INFO/TEXT. */
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

    /* ------------------------------------------------------------------ */

    /** Baca variabel dari bootloader, mis. product, max-download-size, current-slot. */
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
            return 0x10000000L; // 256 MB, batas aman umum
        }
    }

    /**
     * Kirim satu image ke perangkat lalu tulis ke partisi.
     *
     * Urutan: download:<hex> -> DATA -> payload -> OKAY -> flash:<nama> -> OKAY
     */
    public void flash(String partition, byte[] image, BromProtocol.Progress progress)
            throws IOException {
        long max = maxDownloadSize();
        if (image.length > max) {
            throw new IOException(String.format(
                    "image %d byte melebihi batas bootloader (%d byte)", image.length, max));
        }

        Response d = command(String.format(Locale.US, "download:%08x", image.length));
        if (!"DATA".equals(d.status)) {
            throw new IOException("bootloader menolak download — " + d);
        }

        int off = 0;
        while (off < image.length) {
            int chunk = Math.min(image.length - off, 1048576);
            int n = connection.bulkTransfer(epOut, image, off, chunk, 15000);
            if (n <= 0) throw new IOException("gagal mengirim data di offset " + off);
            off += n;
            if (progress != null) {
                progress.onProgress(off * 100 / image.length,
                        "kirim " + off + "/" + image.length);
            }
        }

        Response ack = readResponse();
        if (!ack.ok()) throw new IOException("data ditolak — " + ack);

        Response f = command("flash:" + partition);
        if (!f.ok()) throw new IOException("flash " + partition + " gagal — " + f);
    }

    public void erase(String partition) throws IOException {
        Response r = command("erase:" + partition);
        if (!r.ok()) throw new IOException("erase " + partition + " gagal — " + r);
    }

    public void reboot() throws IOException {
        try {
            command("reboot");
        } catch (IOException ignored) {
            // perangkat langsung putus koneksi setelah reboot; ini normal
        }
    }

    public void close() {
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }
}

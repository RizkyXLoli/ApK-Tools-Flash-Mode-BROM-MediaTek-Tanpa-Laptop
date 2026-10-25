package com.rizky.mtkflash;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Locale;

public class UsbTransport {

    public static final int MTK_VID = 0x0E8D;

    public static final int[] MTK_PIDS = {
            0x0003,
            0x0004,
            0x0006,
            0x2000,
            0x2001,
            0x201C,
            0x201D,
            0x3000,
            0x0005,
    };

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;

    private int timeoutMs = 5000;

    public UsbTransport(UsbManager manager, UsbDevice device) throws IOException {
        this.device = device;
        this.connection = manager.openDevice(device);
        if (connection == null) {
            throw new IOException("gagal membuka perangkat USB (izin ditolak?)");
        }

        UsbEndpoint in = null, out = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface itf = device.getInterface(i);
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                UsbEndpoint ep = itf.getEndpoint(j);
                if (ep.getType() != android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (ep.getDirection() == android.hardware.usb.UsbConstants.USB_DIR_IN && in == null) {
                    in = ep;
                } else if (ep.getDirection() == android.hardware.usb.UsbConstants.USB_DIR_OUT && out == null) {
                    out = ep;
                }
            }
            if (in != null && out != null) {
                connection.claimInterface(itf, true);
                break;
            }
        }

        if (in == null || out == null) {
            connection.close();
            throw new IOException("endpoint bulk tidak ditemukan pada perangkat ini");
        }
        this.epIn = in;
        this.epOut = out;
    }

    public UsbDevice device() {
        return device;
    }

    public void setTimeout(int ms) {
        this.timeoutMs = ms;
    }

    public int timeout() {
        return timeoutMs;
    }

    public void write(byte[] data) throws IOException {
        write(data, data.length);
    }

    public void write(byte[] data, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int chunk = Math.min(len - off, 16384);
            int n = connection.bulkTransfer(epOut, data, off, chunk, timeoutMs);
            if (n <= 0) {
                throw new IOException("USB write gagal di offset " + off + " (ret " + n + ")");
            }
            off += n;
        }
    }

    public byte[] read(int len, int timeout) throws IOException {
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int chunk = Math.min(len - off, 16384);
            byte[] buf = new byte[chunk];
            int n = connection.bulkTransfer(epIn, buf, chunk, timeout);
            if (n <= 0) {
                throw new IOException("USB read timeout setelah " + off + "/" + len + " byte");
            }
            System.arraycopy(buf, 0, out, off, n);
            off += n;
        }
        return out;
    }

    public byte[] read(int len) throws IOException {
        return read(len, timeoutMs);
    }

    /**
     * Control transfer. The BROM compares wIndex against its own var1 before
     * honouring a class request, so the exploit needs this path, not just bulk.
     *
     * @param requestType bmRequestType (0xA1 = IN class/device, 0x21 = OUT)
     * @param data        payload for OUT transfers, null for IN
     * @param length      bytes to read for IN transfers (ignored for OUT)
     */
    public byte[] control(int requestType, int request, int value, int index,
                          byte[] data, int length) throws IOException {
        if ((requestType & 0x80) != 0) {
            byte[] buf = new byte[length];
            int n = connection.controlTransfer(requestType, request, value, index, buf, length, timeoutMs);
            if (n < 0) {
                throw new IOException(String.format(Locale.US,
                        "control transfer IN gagal (bmRequestType %02X bRequest %02X wIndex %04X)",
                        requestType, request, index));
            }
            if (n == length) {
                return buf;
            }
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        }
        int n = connection.controlTransfer(requestType, request, value, index,
                data, data == null ? 0 : data.length, timeoutMs);
        if (n < 0) {
            throw new IOException(String.format(Locale.US,
                    "control transfer OUT gagal (bmRequestType %02X bRequest %02X wIndex %04X)",
                    requestType, request, index));
        }
        return new byte[0];
    }

    public void drain() {
        byte[] buf = new byte[4096];
        for (int i = 0; i < 8; i++) {
            int n = connection.bulkTransfer(epIn, buf, buf.length, 60);
            if (n <= 0) break;
        }
    }

    public void close() {
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }
}

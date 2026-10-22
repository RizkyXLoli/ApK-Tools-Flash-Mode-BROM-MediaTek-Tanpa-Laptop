package com.rizky.mtkflash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class BromProtocol {

    public enum Proto {
        CLASSIC, NEW_ARCH
    }

    public static final byte CMD_HANDSHAKE       = (byte) 0xA0;
    public static final byte CMD_GET_BROM_VER    = (byte) 0xFE;
    public static final byte CMD_GET_HW_CODE     = (byte) 0xFD;
    public static final byte CMD_GET_HW_SW_VER   = (byte) 0xFC;
    public static final byte CMD_GET_TARGET_CFG  = (byte) 0xD8;
    public static final byte CMD_SEND_DA         = (byte) 0xD7;
    public static final byte CMD_JUMP_DA         = (byte) 0xD5;
    public static final byte CMD_JUMP_BL         = (byte) 0xD6;
    public static final byte CMD_GET_FW_VER      = (byte) 0xBF;

    public static final byte NA_WRITE16          = (byte) 0xA1;
    public static final byte NA_READ16           = (byte) 0xA2;
    public static final byte NA_CHECKSUM16       = (byte) 0xA4;
    public static final byte NA_JUMP_AND_VERIFY  = (byte) 0xA8;
    public static final byte NA_WRITE_BUF        = (byte) 0xAD;
    public static final byte NA_READ32           = (byte) 0xAF;
    public static final byte NA_JUMP_MAUI        = (byte) 0xB7;
    public static final byte NA_BL_READY         = (byte) 0xB8;
    public static final byte NA_WRITE32          = (byte) 0xD4;

    public static final byte CL_READ16           = (byte) 0xD0;
    public static final byte CL_READ32           = (byte) 0xD1;
    public static final byte CL_WRITE16          = (byte) 0xD2;
    public static final byte CL_WRITE16_NOECHO   = (byte) 0xD3;
    public static final byte CL_WRITE32          = (byte) 0xD4;

    public static final int DA_CHUNK = 0x1000;

    private final UsbTransport usb;
    private final Log log;
    private Proto proto = Proto.CLASSIC;

    public BromProtocol(UsbTransport usb, Log log) {
        this.usb = usb;
        this.log = log;
    }

    public void setProto(Proto p) {
        this.proto = p;
    }

    public Proto proto() {
        return proto;
    }

    public void handshake(int attempts) throws IOException {
        log.i("handshake: kirim 0xA0 (maks " + attempts + " kali)");
        for (int i = 0; i < attempts; i++) {
            try {
                usb.write(new byte[]{CMD_HANDSHAKE});
                byte[] echo = usb.read(1, 500);
                if ((echo[0] & 0xFF) != 0xA0) {
                    continue;
                }
                byte[] st = usb.read(2, 500);
                if (status(st) == 0) {
                    log.i("handshake OK pada percobaan " + (i + 1));
                    return;
                }
            } catch (IOException e) {

            }
        }
        throw new IOException("handshake gagal: perangkat tidak menjawab 0xA0");
    }

    private static int status(byte[] b) {
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    public byte[] command(byte cmd, byte[] payload, int readLen) throws IOException {
        usb.write(new byte[]{cmd});
        byte[] echo = usb.read(1);
        if (echo[0] != cmd) {
            throw new IOException(String.format(
                    "echo perintah salah: kirim %02X, terima %02X", cmd & 0xFF, echo[0] & 0xFF));
        }

        int len = payload == null ? readLen : payload.length;
        byte[] lenBytes = be32(len);
        usb.write(lenBytes);
        byte[] lenEcho = usb.read(4);
        if (!java.util.Arrays.equals(lenBytes, lenEcho)) {
            throw new IOException("echo panjang salah");
        }

        byte[] data = new byte[0];
        if (payload != null && payload.length > 0) {
            usb.write(payload);
            data = usb.read(payload.length);
        }

        if (readLen > 0) {
            data = usb.read(readLen);
        }

        byte[] st = usb.read(2);
        int s = status(st);
        if (s != 0) {
            throw new IOException(String.format("perintah %02X gagal, status %04X", cmd & 0xFF, s));
        }
        return data;
    }

    public void commandWrite(byte cmd, byte[] payload) throws IOException {
        command(cmd, payload, 0);
    }

    private static byte[] be32(int v) {
        return new byte[]{
                (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v
        };
    }

    private static int le32(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    public int getBromVersion() throws IOException {
        byte[] r = command(CMD_GET_BROM_VER, null, 2);
        int v = ((r[0] & 0xFF) << 8) | (r[1] & 0xFF);
        log.i(String.format("BROM version : 0x%04X", v));
        return v;
    }

    public int getHwCode() throws IOException {
        byte[] r = command(CMD_GET_HW_CODE, null, 2);
        int v = ((r[0] & 0xFF) << 8) | (r[1] & 0xFF);
        log.i(String.format("HW code      : 0x%04X", v));
        return v;
    }

    public int[] getHwSwVersion() throws IOException {
        byte[] r = command(CMD_GET_HW_SW_VER, null, 8);
        int hwSub = ((r[0] & 0xFF) << 8) | (r[1] & 0xFF);
        int hwVer = ((r[2] & 0xFF) << 8) | (r[3] & 0xFF);
        int swVer = ((r[4] & 0xFF) << 8) | (r[5] & 0xFF);
        log.i(String.format("HW sub code  : 0x%04X", hwSub));
        log.i(String.format("HW version   : 0x%04X", hwVer));
        log.i(String.format("SW version   : 0x%04X", swVer));
        return new int[]{hwSub, hwVer, swVer};
    }

    public int getTargetConfig() throws IOException {
        byte[] r = command(CMD_GET_TARGET_CFG, null, 4);
        int v = ((r[0] & 0xFF) << 24) | ((r[1] & 0xFF) << 16)
                | ((r[2] & 0xFF) << 8) | (r[3] & 0xFF);
        log.i(String.format("Target config: 0x%08X", v));
        return v;
    }

    public void sendDa(int addr, byte[] da, int sigLen, Progress p) throws IOException {
        log.i(String.format("kirim DA: %d byte ke 0x%08X", da.length, addr));

        byte[] head = new byte[12];
        System.arraycopy(be32(addr), 0, head, 0, 4);
        System.arraycopy(be32(da.length), 0, head, 4, 4);
        System.arraycopy(be32(sigLen), 0, head, 8, 4);

        usb.write(new byte[]{CMD_SEND_DA});
        byte[] echo = usb.read(1);
        if (echo[0] != CMD_SEND_DA) {
            throw new IOException("echo SEND_DA salah");
        }
        usb.write(head);
        usb.read(12);

        int off = 0;
        while (off < da.length) {
            int chunk = Math.min(DA_CHUNK, da.length - off);
            byte[] part = new byte[chunk];
            System.arraycopy(da, off, part, 0, chunk);
            usb.write(part);
            usb.read(chunk);
            off += chunk;
            if (p != null) {
                p.onProgress(off * 100 / da.length, "unggah DA " + off + "/" + da.length);
            }
        }

        int sum = 0;
        for (byte b : da) {
            sum = (sum + (b & 0xFF)) & 0xFFFF;
        }
        byte[] cks = new byte[]{(byte) (sum >>> 8), (byte) sum};
        usb.write(cks);
        usb.read(2);

        byte[] st = usb.read(2);
        int s = status(st);
        if (s != 0) {
            throw new IOException(String.format("SEND_DA gagal, status %04X", s));
        }
        log.i("DA terkirim, checksum 0x" + Integer.toHexString(sum).toUpperCase());
    }

    public void jumpDa(int addr) throws IOException {
        log.i(String.format("JUMP_DA ke 0x%08X", addr));
        commandWrite(CMD_JUMP_DA, be32(addr));
    }

    public void jumpBl() throws IOException {
        log.i("JUMP_BL");
        commandWrite(CMD_JUMP_BL, new byte[]{0});
    }

    public byte[] read16(int addr, int len) throws IOException {
        byte cmd = proto == Proto.NEW_ARCH ? NA_READ16 : CL_READ16;
        byte[] payload = new byte[8];
        System.arraycopy(be32(addr), 0, payload, 0, 4);
        System.arraycopy(be32(len), 0, payload, 4, 4);
        return command(cmd, payload, len);
    }

    public void write16(int addr, byte[] data) throws IOException {
        byte cmd = proto == Proto.NEW_ARCH ? NA_WRITE16 : CL_WRITE16;
        byte[] payload = new byte[8 + data.length];
        System.arraycopy(be32(addr), 0, payload, 0, 4);
        System.arraycopy(be32(data.length), 0, payload, 4, 4);
        System.arraycopy(data, 0, payload, 8, data.length);
        commandWrite(cmd, payload);
    }

    public interface Progress {
        void onProgress(int percent, String message);
    }

    public interface Log {
        void i(String s);

        void e(String s);
    }
}

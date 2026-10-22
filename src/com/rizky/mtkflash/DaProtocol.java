package com.rizky.mtkflash;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DaProtocol {

    public static final byte CMD_SYNC            = (byte) 0x5A;

    public static final byte CMD_FINISH          = (byte) 0xD9;
    public static final byte CMD_POST_PROCESS    = (byte) 0xD1;
    public static final byte CMD_UPDATE_CFG      = (byte) 0xE1;
    public static final byte CMD_CHANGE_UART     = (byte) 0xD2;
    public static final byte CMD_ENABLE_WATCHDOG = (byte) 0xDB;
    public static final byte CMD_ENABLE_DRAM     = (byte) 0xE8;
    public static final byte CMD_MEMORY_TEST     = (byte) 0xFE;

    public static final byte CMD_FORMAT_FLASH    = (byte) 0x96;
    public static final byte CMD_SEND_DATA       = (byte) 0x96;
    public static final byte CMD_READ_PT         = (byte) 0xA5;
    public static final byte CMD_WRITE_PT        = (byte) 0xA6;
    public static final byte CMD_SWITCH_PART     = (byte) 0x60;
    public static final byte CMD_READ_BACK       = (byte) 0x69;
    public static final byte CMD_NAND_WRITE_IMG  = (byte) 0xDE;
    public static final byte CMD_NAND_IMG_LIST   = (byte) 0xDD;
    public static final byte CMD_NAND_BMT        = (byte) 0x52;
    public static final byte CMD_DL_BOOTLOADER   = (byte) 0x51;

    public static final byte CMD_WRITE_REG32     = (byte) 0x7B;
    public static final byte CMD_READ_REG32      = (byte) 0x7A;
    public static final byte CMD_WRITE_REG16     = (byte) 0xD7;
    public static final byte CMD_READ_REG16      = (byte) 0xD8;
    public static final byte CMD_WRITE_PWR16     = (byte) 0x7D;
    public static final byte CMD_READ_PWR16      = (byte) 0x7C;
    public static final byte CMD_WRITE_PWR8      = (byte) 0x7F;
    public static final byte CMD_READ_PWR8       = (byte) 0x7E;

    public static final byte CMD_CHECK_SEC       = (byte) 0xE0;
    public static final byte CMD_GET_PROJECT_ID  = (byte) 0xEF;
    public static final byte CMD_SECRO_GET_INFO  = (byte) 0xE5;
    public static final byte CMD_SECRO_READ      = (byte) 0xE6;
    public static final byte CMD_SECRO_WRITE     = (byte) 0xE7;
    public static final byte CMD_GET_DSP_CODE    = (byte) 0xDA;

    public static final int DATA_CHUNK = 0x8000;

    private final UsbTransport usb;
    private final BromProtocol.Log log;

    public DaProtocol(UsbTransport usb, BromProtocol.Log log) {
        this.usb = usb;
        this.log = log;
    }

    public void sync() throws IOException {
        log.i("sync dengan DA (0x5A)");
        usb.write(new byte[]{CMD_SYNC});
        byte[] st = usb.read(2);
        int s = ((st[0] & 0xFF) << 8) | (st[1] & 0xFF);
        if (s != 0) {
            throw new IOException(String.format("sync DA gagal, status %04X", s));
        }
        log.i("DA siap");
    }

    public void exec(byte cmd, byte[] payload) throws IOException {
        byte[] head = new byte[5];
        head[0] = cmd;
        int len = payload == null ? 0 : payload.length;
        head[1] = (byte) len;
        head[2] = (byte) (len >>> 8);
        head[3] = (byte) (len >>> 16);
        head[4] = (byte) (len >>> 24);
        usb.write(head);
        if (payload != null && payload.length > 0) {
            usb.write(payload);
        }
        byte[] st = usb.read(2);
        int s = ((st[0] & 0xFF) << 8) | (st[1] & 0xFF);
        if (s != 0) {
            throw new IOException(String.format("perintah DA %02X gagal, status %04X",
                    cmd & 0xFF, s));
        }
    }

    public void writeData(int addr, byte[] data, BromProtocol.Progress p) throws IOException {
        log.i(String.format("tulis %d byte ke 0x%08X", data.length, addr));

        ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(addr);
        b.putInt(data.length);
        b.putInt(0);
        exec(CMD_SEND_DATA, b.array());

        int off = 0;
        while (off < data.length) {
            int chunk = Math.min(DATA_CHUNK, data.length - off);
            byte[] part = new byte[chunk];
            System.arraycopy(data, off, part, 0, chunk);
            usb.write(part);
            byte[] st = usb.read(2);
            int s = ((st[0] & 0xFF) << 8) | (st[1] & 0xFF);
            if (s != 0) {
                throw new IOException(String.format("tulis data gagal di 0x%08X, status %04X",
                        addr + off, s));
            }
            off += chunk;
            if (p != null) {
                p.onProgress(off * 100 / data.length,
                        "tulis " + off + "/" + data.length + " byte");
            }
        }
    }

    public byte[] readPartitionTable() throws IOException {
        log.i("baca tabel partisi (0xA5)");
        int want = 0x8000;

        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0);
        b.putInt(want);

        byte[] head = new byte[5];
        head[0] = CMD_READ_PT;
        int len = b.array().length;
        head[1] = (byte) len;
        head[2] = (byte) (len >>> 8);
        head[3] = (byte) (len >>> 16);
        head[4] = (byte) (len >>> 24);
        usb.write(head);
        usb.write(b.array());

        byte[] out = usb.read(want);
        byte[] st = usb.read(2);
        int s = ((st[0] & 0xFF) << 8) | (st[1] & 0xFF);
        if (s != 0) {
            throw new IOException(String.format("baca tabel partisi gagal, status %04X", s));
        }
        return out;
    }

    public void finish() throws IOException {
        log.i("DA finish (0xD9)");
        exec(CMD_FINISH, null);
    }

    public int readReg32(int addr) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(addr);
        exec(CMD_READ_REG32, b.array());
        byte[] r = usb.read(4);
        return ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}

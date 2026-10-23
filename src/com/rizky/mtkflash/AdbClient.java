package com.rizky.mtkflash;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public class AdbClient implements Closeable {

    public interface Sink {
        void line(String s);
    }

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_STLS = 0x534c5453;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;
    private static final int CHUNK = 16384;

    private final UsbDeviceConnection conn;
    private final UsbInterface itf;
    private final UsbEndpoint in;
    private final UsbEndpoint out;
    private final KeyPair keys;

    private boolean connected = false;
    private String banner = "";
    private int nextId = 1;

    private static class Msg {
        int command;
        int arg0;
        int arg1;
        byte[] payload = new byte[0];
    }

    public AdbClient(UsbManager manager, UsbDevice device, KeyPair keys) throws IOException {
        if (keys == null) throw new IOException("kunci ADB tidak siap");
        this.keys = keys;
        UsbInterface found = null;
        UsbEndpoint epIn = null;
        UsbEndpoint epOut = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface itf = device.getInterface(i);
            if (itf.getInterfaceClass() != 0xFF
                    || itf.getInterfaceSubclass() != 0x42
                    || itf.getInterfaceProtocol() != 0x01) continue;
            for (int j = 0; j < itf.getEndpointCount(); j++) {
                UsbEndpoint ep = itf.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) epIn = ep;
                else epOut = ep;
            }
            if (epIn != null && epOut != null) {
                found = itf;
                break;
            }
        }
        if (found == null) {
            throw new IOException("antarmuka ADB tidak ditemukan di perangkat ini");
        }
        this.itf = found;
        this.in = epIn;
        this.out = epOut;
        UsbDeviceConnection c = manager.openDevice(device);
        if (c == null) throw new IOException("tidak bisa membuka perangkat USB");
        this.conn = c;
        if (!c.claimInterface(found, true)) {
            c.close();
            throw new IOException("antarmuka ADB dipakai proses lain (cabut-colok lalu ulangi)");
        }
    }

    public static boolean isAdb(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            if (itf.getInterfaceClass() == 0xFF
                    && itf.getInterfaceSubclass() == 0x42
                    && itf.getInterfaceProtocol() == 0x01) return true;
        }
        return false;
    }

    public String banner() {
        return banner;
    }

    public static KeyPair loadOrCreate(Context ctx) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences("adbkey", Context.MODE_PRIVATE);
        String priv = sp.getString("priv", null);
        String pub = sp.getString("pub", null);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv != null && pub != null) {
            try {
                return new KeyPair(
                        kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, 0))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, 0))));
            } catch (Exception ignored) {
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        sp.edit()
                .putString("priv", Base64.encodeToString(kp.getPrivate().getEncoded(), 0))
                .putString("pub", Base64.encodeToString(kp.getPublic().getEncoded(), 0))
                .apply();
        return kp;
    }

    public void connect(Sink sink) throws IOException {
        if (connected) return;
        write(A_CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
        if (sink != null) sink.line("ADB: CNXN terkirim, menunggu adbd...");
        int step = 0;
        long end = System.currentTimeMillis() + 90000L;
        while (System.currentTimeMillis() < end) {
            Msg m = read(5000);
            if (m == null) continue;
            if (m.command == A_CNXN) {
                connected = true;
                banner = new String(m.payload, StandardCharsets.UTF_8).trim();
                if (sink != null) sink.line("ADB tersambung: " + banner);
                return;
            }
            if (m.command == A_STLS) {
                throw new IOException("adbd minta ADB terenkripsi (TLS) — belum didukung di aplikasi ini; "
                        + "pakai fastboot atau coba HP/versi Android lain");
            }
            if (m.command == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (step == 0) {
                    if (sink != null) sink.line("ADB: adbd minta tanda tangan, kirim signature...");
                    write(A_AUTH, AUTH_SIGNATURE, 0, sign(m.payload));
                    step = 1;
                } else if (step == 1) {
                    if (sink != null) {
                        sink.line("ADB: kunci belum dikenal HP, kirim kunci publik.");
                        sink.line("ADB: kalau HP munculkan \"Izinkan penelusuran USB\", tekan IZINKAN.");
                    }
                    write(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload());
                    step = 2;
                } else {
                    throw new IOException("HP menolak kunci ADB — centang USB debugging di Opsi Pengembang");
                }
            }
        }
        throw new IOException("adbd tidak menjawab (USB debugging belum aktif?)");
    }

    public String shell(String cmd, Sink sink) throws IOException {
        connect(sink);
        int local = nextId++;
        write(A_OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        StringBuilder pending = new StringBuilder();
        long end = System.currentTimeMillis() + 120000L;
        while (System.currentTimeMillis() < end) {
            Msg m = read(30000);
            if (m == null) continue;
            if (m.command == A_WRTE && m.arg1 == local) {
                all.write(m.payload);
                write(A_OKAY, local, m.arg0, new byte[0]);
                if (sink != null) {
                    pending.append(new String(m.payload, StandardCharsets.UTF_8));
                    int cut;
                    while ((cut = pending.indexOf("\n")) >= 0) {
                        sink.line(pending.substring(0, cut));
                        pending.delete(0, cut + 1);
                    }
                }
                continue;
            }
            if (m.command == A_CLSE && m.arg1 == local) {
                write(A_CLSE, local, m.arg0, new byte[0]);
                break;
            }
            if (m.command == A_OKAY) continue;
            if (m.command == A_AUTH) {
                throw new IOException("sesi ADB kehilangan izin");
            }
        }
        if (sink != null && pending.length() > 0) sink.line(pending.toString());
        return new String(all.toByteArray(), StandardCharsets.UTF_8);
    }

    private byte[] publicKeyPayload() throws IOException {
        try {
            return (publicKeyString((RSAPublicKey) keys.getPublic()) + "\0")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("gagal menyusun kunci publik: " + e);
        }
    }

    static String publicKeyString(RSAPublicKey pk) {
        return Base64.encodeToString(adbPublicKey(pk), Base64.NO_WRAP) + " mtkflash@android";
    }

    static byte[] adbPublicKey(RSAPublicKey pk) {
        BigInteger n = pk.getModulus();
        BigInteger e = pk.getPublicExponent();
        int words = (n.bitLength() + 31) / 32;
        BigInteger mask = BigInteger.ONE.shiftLeft(32);
        int n0inv = mask.subtract(n.mod(mask).modInverse(mask)).intValue();
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt(n0inv);
        bb.put(le(n, words * 4));
        bb.put(le(BigInteger.ONE.shiftLeft(words * 64).mod(n), words * 4));
        bb.putInt(e.intValue());
        return bb.array();
    }

    private static byte[] le(BigInteger v, int len) {
        byte[] out = new byte[len];
        BigInteger x = v;
        BigInteger m = BigInteger.valueOf(0xff);
        for (int i = 0; i < len; i++) {
            out[i] = (byte) x.and(m).intValue();
            x = x.shiftRight(8);
        }
        return out;
    }

    private byte[] sign(byte[] token) throws IOException {
        try {
            Signature s = Signature.getInstance("SHA1withRSA");
            s.initSign((RSAPrivateKey) keys.getPrivate());
            s.update(token);
            return s.sign();
        } catch (Exception e) {
            throw new IOException("gagal tanda tangan token ADB: " + e);
        }
    }

    private void write(int cmd, int a0, int a1, byte[] data) throws IOException {
        bulkWrite(header(cmd, a0, a1, data.length));
        if (data.length > 0) bulkWrite(data);
    }

    static byte[] header(int cmd, int a0, int a1, int len) {
        byte[] h = new byte[24];
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd);
        bb.putInt(a0);
        bb.putInt(a1);
        bb.putInt(len);
        bb.putInt(0);
        bb.putInt(cmd ^ 0xffffffff);
        return h;
    }

    private void bulkWrite(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int len = Math.min(CHUNK, buf.length - off);
            int n = conn.bulkTransfer(out, buf, off, len, 5000);
            if (n <= 0) throw new IOException("gagal mengirim data ke HP (ADB)");
            off += n;
        }
    }

    private Msg read(int timeout) throws IOException {
        byte[] h = readFully(24, timeout);
        if (h == null) return null;
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = bb.getInt();
        int a0 = bb.getInt();
        int a1 = bb.getInt();
        int len = bb.getInt();
        bb.getInt();
        int magic = bb.getInt();
        if (magic != (cmd ^ 0xffffffff)) throw new IOException("paket ADB rusak");
        if (len < 0 || len > MAX_PAYLOAD) throw new IOException("panjang paket ADB aneh: " + len);
        Msg m = new Msg();
        m.command = cmd;
        m.arg0 = a0;
        m.arg1 = a1;
        if (len > 0) {
            byte[] p = readFully(len, timeout);
            m.payload = p == null ? new byte[0] : p;
        }
        return m;
    }

    private byte[] readFully(int len, int timeout) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = conn.bulkTransfer(in, buf, off, len - off, timeout);
            if (n <= 0) {
                if (off == 0) return null;
                throw new IOException("koneksi ADB terputus");
            }
            off += n;
        }
        return buf;
    }

    @Override
    public void close() {
        try {
            conn.releaseInterface(itf);
        } catch (Exception ignored) {
        }
        try {
            conn.close();
        } catch (Exception ignored) {
        }
    }
}

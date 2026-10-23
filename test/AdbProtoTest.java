package com.rizky.mtkflash;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

public class AdbProtoTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== ADB: header 24 byte ==");
        byte[] h = AdbClient.header(0x4e584e43, 0x01000000, 262144, 7);
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = bb.getInt();
        int a0 = bb.getInt();
        int a1 = bb.getInt();
        int len = bb.getInt();
        int sum = bb.getInt();
        int magic = bb.getInt();
        check("panjang header 24", h.length == 24, String.valueOf(h.length));
        check("cmd CNXN", cmd == 0x4e584e43, Integer.toHexString(cmd));
        check("arg0 versi", a0 == 0x01000000, Integer.toHexString(a0));
        check("arg1 maxpayload", a1 == 262144, String.valueOf(a1));
        check("panjang data", len == 7, String.valueOf(len));
        check("checksum nol (diabaikan adbd)", sum == 0, String.valueOf(sum));
        check("magic = ~cmd", magic == (cmd ^ 0xffffffff), Integer.toHexString(magic));

        System.out.println("== ADB: blob kunci publik 2048 bit ==");
        KeyPair kp = key(2048);
        RSAPublicKey pk = (RSAPublicKey) kp.getPublic();
        byte[] blob = AdbClient.adbPublicKey(pk);
        ByteBuffer kb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int words = kb.getInt();
        int n0inv = kb.getInt();
        BigInteger n = pk.getModulus();
        BigInteger e = pk.getPublicExponent();

        check("words = 64", words == 64, String.valueOf(words));
        check("panjang blob = 4+4+256+256+4",
                blob.length == 524, String.valueOf(blob.length));

        byte[] nRaw = new byte[words * 4];
        kb.get(nRaw);
        byte[] nLe = new byte[words * 4];
        byte[] nBe = n.toByteArray();
        for (int i = 0; i < words * 4; i++) {
            int src = nBe.length - 1 - i;
            nLe[i] = src >= 0 ? nBe[src] : 0;
        }
        check("modulus little-endian benar", java.util.Arrays.equals(nRaw, nLe), "beda byte");

        BigInteger mask = BigInteger.ONE.shiftLeft(32);
        BigInteger want = mask.subtract(n.mod(mask).modInverse(mask));
        check("n0inv = -n^-1 mod 2^32",
                n0inv == want.intValue(),
                "dapat " + Integer.toHexString(n0inv) + " harus " + Integer.toHexString(want.intValue()));

        byte[] rrRaw = new byte[words * 4];
        kb.get(rrRaw);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 64).mod(n);
        byte[] rrBe = rr.toByteArray();
        byte[] rrLe = new byte[words * 4];
        for (int i = 0; i < words * 4; i++) {
            int src = rrBe.length - 1 - i;
            rrLe[i] = src >= 0 ? rrBe[src] : 0;
        }
        check("rr = R^2 mod n", java.util.Arrays.equals(rrRaw, rrLe), "beda byte");

        int exp = kb.getInt();
        check("eksponen 65537", exp == 65537, String.valueOf(exp));
        check("tidak ada byte sisa", !kb.hasRemaining(), "sisa " + kb.remaining());

        System.out.println("== ADB: blob kunci publik 1024 bit ==");
        RSAPublicKey small = (RSAPublicKey) key(1024).getPublic();
        byte[] sb = AdbClient.adbPublicKey(small);
        ByteBuffer sbuf = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN);
        int sw = sbuf.getInt();
        check("words = 32", sw == 32, String.valueOf(sw));
        check("panjang blob = 268", sb.length == 268, String.valueOf(sb.length));

        System.out.println();
        System.out.println("HASIL: " + pass + " lulus, " + fail + " gagal");
        if (fail > 0) System.exit(1);
    }

    static KeyPair key(int bits) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(bits);
        return g.generateKeyPair();
    }
}

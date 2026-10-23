package com.rizky.mtkflash;

import java.util.List;

public class ReadbackTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  (" + detail + ")");
        }
    }

    public static void main(String[] args) {
        System.out.println("ReadbackTest");

        check("fetchCmd super 0..256MiB",
                Readback.fetchCmd("super", 0L, 268435456L)
                        .equals("fetch:super:0x00000000:0x10000000"),
                Readback.fetchCmd("super", 0L, 268435456L));

        check("fetchCmd offset 1MiB",
                Readback.fetchCmd("boot", 1048576L, 65536L)
                        .equals("fetch:boot:0x00100000:0x00010000"),
                Readback.fetchCmd("boot", 1048576L, 65536L));

        check("fetchCmd pakai nama partisi apa adanya",
                Readback.fetchCmd("vendor_a", 4096L, 4096L)
                        .equals("fetch:vendor_a:0x00001000:0x00001000"),
                Readback.fetchCmd("vendor_a", 4096L, 4096L));

        check("parseHex 0x1000", Readback.parseHex("0x1000") == 4096L,
                "" + Readback.parseHex("0x1000"));
        check("parseHex 0X2000", Readback.parseHex("0X2000") == 8192L,
                "" + Readback.parseHex("0X2000"));
        check("parseHex desimal 1024", Readback.parseHex("1024") == 1024L,
                "" + Readback.parseHex("1024"));
        check("parseHex heksa tanpa awalan 1f", Readback.parseHex("1f") == 31L,
                "" + Readback.parseHex("1f"));
        check("parseHex sampah = -1", Readback.parseHex("tidak ada") == -1L,
                "" + Readback.parseHex("tidak ada"));
        check("parseHex null = -1", Readback.parseHex(null) == -1L, "null");
        check("parseHex kosong = -1", Readback.parseHex("  ") == -1L, "kosong");

        check("chunkSize 0 = 0", Readback.chunkSize(0) == 0L, "" + Readback.chunkSize(0));
        check("chunkSize -5 = 0", Readback.chunkSize(-5) == 0L, "" + Readback.chunkSize(-5));
        check("chunkSize 1 naik ke MIN", Readback.chunkSize(1) == Readback.MIN_CHUNK,
                "" + Readback.chunkSize(1));
        check("chunkSize 1MiB tetap 1MiB", Readback.chunkSize(1048576L) == 1048576L,
                "" + Readback.chunkSize(1048576L));
        check("chunkSize 1GiB dibatasi MAX", Readback.chunkSize(1073741824L) == Readback.MAX_CHUNK,
                "" + Readback.chunkSize(1073741824L));

        List<long[]> p1 = Readback.plan(200000L, 65536L);
        check("plan 200000/65536 = 4 potong", p1.size() == 4, "" + p1.size());
        check("plan potong 1 = 0..65536", p1.get(0)[0] == 0L && p1.get(0)[1] == 65536L,
                p1.get(0)[0] + "/" + p1.get(0)[1]);
        check("plan potong 4 = 196608..3392", p1.get(3)[0] == 196608L && p1.get(3)[1] == 3392L,
                p1.get(3)[0] + "/" + p1.get(3)[1]);

        long sum = 0;
        long expect = 0;
        boolean rapi = true;
        for (long[] c : p1) {
            if (c[0] != expect) rapi = false;
            expect += c[1];
            sum += c[1];
        }
        check("plan tidak ada lubang", rapi, "offset tidak nyambung");
        check("plan jumlah byte = total", sum == 200000L, "" + sum);

        check("plan total 0 kosong", Readback.plan(0L, 65536L).isEmpty(), "tidak kosong");
        check("plan total negatif kosong", Readback.plan(-1L, 65536L).isEmpty(), "tidak kosong");
        check("plan maxFetch 0 kosong", Readback.plan(1000L, 0L).isEmpty(), "tidak kosong");

        check("fileName SUPER = super.img", Readback.fileName("SUPER").equals("super.img"),
                Readback.fileName("SUPER"));
        check("fileName kosong = partisi.img", Readback.fileName(" ").equals("partisi.img"),
                Readback.fileName(" "));
        check("fileName null = partisi.img", Readback.fileName(null).equals("partisi.img"),
                Readback.fileName(null));

        System.out.println();
        System.out.println(pass + " cek lulus, " + fail + " gagal");
        if (fail > 0) System.exit(1);
    }
}

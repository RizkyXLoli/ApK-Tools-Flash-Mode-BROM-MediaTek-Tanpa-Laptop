package com.rizky.mtkflash;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pembaca file Download Agent.
 *
 * Dua bentuk yang didukung:
 *
 * 1. DA tunggal (mis. DA_PL.bin) - seluruh file adalah satu image DA.
 *
 * 2. MTK_AllInOne_DA.bin - berkas gabungan berisi banyak DA, satu per chip.
 *
 * Penanda DA di dalam berkas gabungan adalah "MMM\x01" diikuti header
 * FILE_INFO. Penanda itu juga muncul sebagai teks biasa di dalam kode
 * (mis. string "MMMUnsupported source type"), jadi pencocokan harus
 * divalidasi dengan struktur header, bukan hanya magic-nya.
 *
 * Header FILE_INFO (little endian), hasil baca langsung dari
 * MTK_AllInOne_DA.bin v3.3001:
 *
 *   +0x00  "MMM" + versi 1
 *   +0x04  ukuran header (0x38)
 *   +0x08  "FILE_INFO"
 *   +0x14  nomor versi / tipe
 *   +0x18  tipe sumber
 *   +0x1C  alamat muat (load address)
 *   +0x20  ukuran blok
 *   +0x28  ukuran header lagi
 */
public class DaLoader {

    public static final int DEFAULT_LOAD_ADDR = 0x201000;

    private static final int MMM_MAGIC = 0x014D4D4D; // "MMM\x01"
    private static final byte[] FILE_INFO = {'F', 'I', 'L', 'E', '_', 'I', 'N', 'F', 'O'};

    public static class DaImage {
        public String name;
        public int offset;
        public int size;
        public int loadAddr;
        public byte[] data;

        @Override
        public String toString() {
            return String.format("%s  (offset 0x%X, %s, load 0x%08X)",
                    name, offset, ScatterParser.human(size), loadAddr);
        }
    }

    public static List<DaImage> load(File f) throws IOException {
        byte[] all = readAll(f);
        List<DaImage> out = new ArrayList<>();

        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 0x40 <= all.length; i++) {
            if (le32(all, i) != MMM_MAGIC) continue;
            if (!looksLikeDaHeader(all, i)) continue;
            starts.add(i);
            i += 0x3F;
        }

        if (starts.isEmpty()) {
            DaImage d = new DaImage();
            d.name = f.getName();
            d.offset = 0;
            d.size = all.length;
            d.loadAddr = DEFAULT_LOAD_ADDR;
            d.data = all;
            out.add(d);
            return out;
        }

        for (int i = 0; i < starts.size(); i++) {
            int off = starts.get(i);
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : all.length;
            while (end > off && all[end - 1] == 0) end--;

            DaImage d = new DaImage();
            d.offset = off;
            d.size = end - off;
            d.loadAddr = readLoadAddr(all, off);
            d.name = String.format("DA #%d", i + 1);
            d.data = new byte[d.size];
            System.arraycopy(all, off, d.data, 0, d.size);
            out.add(d);
        }
        return out;
    }

    /** Header sah: ukuran header wajar dan ada "FILE_INFO" di +0x08. */
    private static boolean looksLikeDaHeader(byte[] b, int off) {
        int hdr = le32(b, off + 4);
        if (hdr < 0x20 || hdr > 0x200) return false;
        if (off + 8 + FILE_INFO.length > b.length) return false;
        for (int i = 0; i < FILE_INFO.length; i++) {
            if (b[off + 8 + i] != FILE_INFO[i]) return false;
        }
        return true;
    }

    /**
     * Alamat muat di +0x1C. Kalau nilainya tidak masuk akal (nol, terlalu
     * kecil, atau tidak sejajar 4 KB), pakai alamat standar DA MediaTek.
     */
    private static int readLoadAddr(byte[] b, int off) {
        if (off + 0x20 > b.length) return DEFAULT_LOAD_ADDR;
        int addr = le32(b, off + 0x1C);
        boolean sane = addr >= 0x10000
                && (addr & 0xFFF) == 0
                && (addr >>> 24) != 0xFF;
        return sane ? addr : DEFAULT_LOAD_ADDR;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static byte[] readAll(File f) throws IOException {
        long len = f.length();
        if (len <= 0 || len > 256L * 1024 * 1024) {
            throw new IOException("ukuran file DA tidak wajar: " + len);
        }
        byte[] buf = new byte[(int) len];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
        }
        return buf;
    }
}

package com.rizky.mtkflash;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Readback {

    public static final long MIN_CHUNK = 65536L;
    public static final long MAX_CHUNK = 16777216L;

    public static String fetchCmd(String part, long offset, long size) {
        return String.format(Locale.US, "fetch:%s:0x%08x:0x%08x", part, offset, size);
    }

    public static long parseHex(String s) {
        if (s == null) return -1;
        String v = s.trim().toLowerCase(Locale.US);
        if (v.isEmpty()) return -1;
        try {
            if (v.startsWith("0x")) return Long.parseLong(v.substring(2), 16);
            if (v.matches("[0-9]+")) return Long.parseLong(v);
            return Long.parseLong(v, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static long chunkSize(long maxFetch) {
        if (maxFetch <= 0) return 0;
        return Math.min(MAX_CHUNK, Math.max(MIN_CHUNK, maxFetch));
    }

    public static List<long[]> plan(long total, long maxFetch) {
        List<long[]> out = new ArrayList<>();
        if (total <= 0) return out;
        long chunk = chunkSize(maxFetch);
        if (chunk <= 0) return out;
        long off = 0;
        while (off < total) {
            long n = Math.min(chunk, total - off);
            out.add(new long[]{off, n});
            off += n;
        }
        return out;
    }

    public static String fileName(String part) {
        String n = part == null ? "" : part.trim().toLowerCase(Locale.US);
        if (n.isEmpty()) n = "partisi";
        return n + ".img";
    }
}

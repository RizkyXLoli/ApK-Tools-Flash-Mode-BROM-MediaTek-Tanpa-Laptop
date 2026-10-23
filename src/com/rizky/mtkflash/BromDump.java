package com.rizky.mtkflash;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BromDump {

    public static class Target {
        public final String name;
        public final long addr;
        public final long size;

        public Target(String name, long addr, long size) {
            this.name = name;
            this.addr = addr;
            this.size = size;
        }

        public String label() {
            return String.format(Locale.US, "%-16s 0x%08X %10s",
                    name, addr, FastbootProtocol.human(size));
        }

        @Override
        public String toString() {
            return label();
        }
    }

    public static List<Target> targets(List<ScatterParser.Partition> parts) {
        List<Target> out = new ArrayList<>();
        if (parts == null) return out;
        for (ScatterParser.Partition p : parts) {
            if (p == null) continue;
            if (p.name == null || p.name.trim().isEmpty()) continue;
            if (p.size <= 0) continue;
            if (p.physicalAddr <= 0) continue;
            out.add(new Target(p.name.trim(), p.physicalAddr, p.size));
        }
        return out;
    }

    public static List<Target> filter(List<Target> all, List<String> names) {
        List<Target> out = new ArrayList<>();
        if (all == null) return out;
        if (names == null || names.isEmpty()) {
            out.addAll(all);
            return out;
        }
        for (Target t : all) {
            if (names.contains(t.name)) out.add(t);
        }
        return out;
    }

    public static long total(List<Target> list) {
        long n = 0;
        if (list == null) return 0;
        for (Target t : list) n += t.size;
        return n;
    }

    public static int percent(long done, long total) {
        if (total <= 0) return 0;
        long v = done * 100L / total;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return (int) v;
    }

    public static String fileName(String part) {
        String s = part == null ? "" : part.trim();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                b.append(c);
            } else {
                b.append('_');
            }
        }
        if (b.length() == 0) b.append("part");
        return b.toString().toLowerCase(Locale.US) + ".img";
    }

    public static boolean same(Target a, Target b) {
        if (a == null || b == null) return false;
        return a.addr == b.addr && a.size == b.size && a.name.equals(b.name);
    }
}

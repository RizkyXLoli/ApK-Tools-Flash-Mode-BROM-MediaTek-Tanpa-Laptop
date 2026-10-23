package com.rizky.mtkflash;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScatterParser {

    public static class Partition {
        public String index = "";
        public String name = "";
        public String fileName = "";
        public boolean download = true;
        public String type = "";
        public String region = "";
        public String storage = "";
        public String operationType = "";
        public long linearAddr = 0;
        public long physicalAddr = 0;
        public long size = 0;

        public boolean hasImage() {
            return fileName != null && !fileName.isEmpty()
                    && !fileName.equalsIgnoreCase("NONE");
        }

        @Override
        public String toString() {
            return String.format("%-24s %-12s %10s  %s",
                    name,
                    region.isEmpty() ? "-" : region,
                    human(size),
                    hasImage() ? fileName : "(tanpa image)");
        }
    }

    public static class Scatter {
        public String platform = "";
        public String project = "";
        public String storage = "";
        public String configVersion = "";
        public long blockSize = 0x20000;
        public final List<Partition> partitions = new ArrayList<>();

        public Partition find(String name) {
            for (Partition p : partitions) {
                if (p.name.equalsIgnoreCase(name)) return p;
            }
            return null;
        }
    }

    public static Scatter parse(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return parse(in);
        }
    }

    public static Scatter parse(InputStream in) throws IOException {
        Scatter sc = new Scatter();
        Map<String, String> cur = null;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;

                if (s.startsWith("- ")) {

                    if (cur != null && cur.containsKey("partition_name")) {
                        sc.partitions.add(toPartition(cur));
                    }
                    cur = new LinkedHashMap<>();
                    s = s.substring(2).trim();
                }

                int c = s.indexOf(':');
                if (c <= 0 || cur == null) continue;

                String key = s.substring(0, c).trim();
                String val = s.substring(c + 1).trim();
                cur.put(key, val);

                switch (key) {
                    case "platform":       sc.platform = val; break;
                    case "project":        sc.project = val; break;
                    case "storage":        sc.storage = val; break;
                    case "config_version": sc.configVersion = val; break;
                    case "block_size":     sc.blockSize = num(val); break;
                    default: break;
                }
            }
        }
        if (cur != null && cur.containsKey("partition_name")) {
            sc.partitions.add(toPartition(cur));
        }

        if (sc.partitions.isEmpty()) {
            throw new IOException("file scatter tidak berisi partisi yang bisa dibaca");
        }
        return sc;
    }

    private static Partition toPartition(Map<String, String> m) {
        Partition p = new Partition();
        p.index = m.getOrDefault("partition_index", "");
        p.name = m.getOrDefault("partition_name", "");
        p.fileName = m.getOrDefault("file_name", "");
        p.type = m.getOrDefault("type", "");
        p.region = m.getOrDefault("region", "");
        p.storage = m.getOrDefault("storage", "");
        p.operationType = m.getOrDefault("operation_type", "");
        p.download = !"false".equalsIgnoreCase(m.getOrDefault("is_download", "true"));
        p.linearAddr = num(m.getOrDefault("linear_start_addr", "0"));
        p.physicalAddr = num(m.getOrDefault("physical_start_addr", "0"));
        p.size = num(m.getOrDefault("partition_size", "0"));
        return p;
    }

    private static long num(String v) {
        try {
            String s = v.trim();
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseLong(s.substring(2), 16);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String human(long bytes) {
        if (bytes <= 0) return "0";
        String[] u = {"B", "KB", "MB", "GB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, u[i]);
    }
}

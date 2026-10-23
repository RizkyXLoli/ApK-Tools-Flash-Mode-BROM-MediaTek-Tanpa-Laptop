package com.rizky.mtkflash;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScatterParser {

    private static final Pattern P_BLOCK = Pattern.compile(
            "<partition_index\\b([^>]*)>(.*?)</partition_index\\s*>", Pattern.DOTALL);
    private static final Pattern P_BLOCK_ALT = Pattern.compile(
            "<partition\\b([^>]*)>(.*?)</partition\\s*>", Pattern.DOTALL);
    private static final Pattern P_ATTR = Pattern.compile(
            "([A-Za-z_][\\w.:-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern P_TAG = Pattern.compile(
            "<([A-Za-z_][\\w.:-]*)\\b[^>]*>([^<]*)</\\1\\s*>", Pattern.DOTALL);
    private static final Pattern P_SELF = Pattern.compile(
            "<(?:partition_index|partition)\\b([^>]*)/>", Pattern.DOTALL);

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
        public boolean xml = false;
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
        String text = readAll(in);
        String head = text.trim();
        if (head.startsWith("<") || head.startsWith("<?xml")) {
            return parseXml(text);
        }
        return parseText(text);
    }

    public static boolean looksLikeXml(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".xml");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        String s = new String(bos.toByteArray(), Charset.forName("UTF-8"));
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        return s;
    }

    private static Scatter parseText(String text) throws IOException {
        Scatter sc = new Scatter();
        Map<String, String> cur = null;

        try (BufferedReader r = new BufferedReader(new java.io.StringReader(text))) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;

                if (s.startsWith("- ")) {
                    if (cur != null && cur.containsKey("partition_name")) {
                        add(sc, cur);
                    }
                    cur = new LinkedHashMap<>();
                    s = s.substring(2).trim();
                }

                int c = s.indexOf(':');
                if (c <= 0 || cur == null) continue;

                String key = s.substring(0, c).trim().toLowerCase(Locale.US);
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
            add(sc, cur);
        }

        if (sc.partitions.isEmpty()) {
            throw new IOException("file scatter tidak berisi partisi yang bisa dibaca");
        }
        return sc;
    }

    private static Scatter parseXml(String text) throws IOException {
        Scatter sc = new Scatter();
        sc.xml = true;
        String clean = stripPadding(text);

        Matcher m = P_BLOCK.matcher(clean);
        boolean found = m.find();
        if (!found) {
            m = P_BLOCK_ALT.matcher(clean);
            found = m.find();
        }
        if (found) {
            do {
                Map<String, String> map = new LinkedHashMap<>();
                Matcher a = P_ATTR.matcher(m.group(1) == null ? "" : m.group(1));
                while (a.find()) map.put(a.group(1).toLowerCase(Locale.US), a.group(2).trim());
                Matcher t = P_TAG.matcher(m.group(2) == null ? "" : m.group(2));
                while (t.find()) map.put(t.group(1).toLowerCase(Locale.US), t.group(2).trim());
                if (map.containsKey("name") && !map.containsKey("partition_name")) {
                    map.put("partition_name", map.get("name"));
                }
                if (map.containsKey("partition_name")) add(sc, map);
            } while (m.find());
        } else {
            Matcher s = P_SELF.matcher(clean);
            while (s.find()) {
                Map<String, String> map = new LinkedHashMap<>();
                Matcher a = P_ATTR.matcher(s.group(1) == null ? "" : s.group(1));
                while (a.find()) map.put(a.group(1).toLowerCase(Locale.US), a.group(2).trim());
                if (map.containsKey("name") && !map.containsKey("partition_name")) {
                    map.put("partition_name", map.get("name"));
                }
                if (map.containsKey("partition_name")) add(sc, map);
            }
        }
        if (sc.partitions.isEmpty()) {
            throw new IOException("file XML ini bukan scatter MediaTek "
                    + "(tidak ada daftar <partition_index> di dalamnya)");
        }

        String rest = P_BLOCK.matcher(clean).replaceAll(" ");
        rest = P_BLOCK_ALT.matcher(rest).replaceAll(" ");

        String platform = tag(rest, "platform");
        if (!platform.isEmpty()) sc.platform = platform;
        String project = tag(rest, "project");
        if (!project.isEmpty()) sc.project = project;
        String cfg = tag(rest, "config_version");
        if (!cfg.isEmpty()) sc.configVersion = cfg;
        String storage = attrOfFirst(rest, "storage", "name");
        if (storage.isEmpty()) storage = tag(rest, "storage");
        if (!storage.isEmpty()) sc.storage = storage;
        long bs = num(tag(rest, "block_size"));
        if (bs > 0) sc.blockSize = bs;

        if (sc.platform.isEmpty()) sc.platform = rootAttr(clean, "platform");
        if (sc.project.isEmpty()) sc.project = rootAttr(clean, "project");
        if (sc.storage.isEmpty()) sc.storage = rootAttr(clean, "storage");
        if (sc.configVersion.isEmpty()) sc.configVersion = rootAttr(clean, "config_version");
        if (bs <= 0) {
            long b2 = num(rootAttr(clean, "block_size"));
            if (b2 > 0) sc.blockSize = b2;
        }

        if (sc.partitions.isEmpty()) {
            throw new IOException("file scatter tidak berisi partisi yang bisa dibaca");
        }
        return sc;
    }

    private static String stripPadding(String s) {
        String t = s.replace("\u0000", "").replace("\u000b", "").replace("\u000c", "");
        int end = t.lastIndexOf('>');
        if (end > 0 && end < t.length() - 1) t = t.substring(0, end + 1);
        return t;
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + "\\b[^>]*>([^<]*)</" + name + "\\s*>",
                Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String attrOfFirst(String xml, String tag, String attr) {
        Matcher m = Pattern.compile("<" + tag + "\\b([^>]*)>", Pattern.DOTALL).matcher(xml);
        if (!m.find()) return "";
        Matcher a = Pattern.compile(attr + "\\s*=\\s*\"([^\"]*)\"").matcher(m.group(1));
        return a.find() ? a.group(1).trim() : "";
    }

    private static String rootAttr(String xml, String attr) {
        Matcher m = Pattern.compile("<[A-Za-z_][\\w.:-]*\\b([^>]*)>", Pattern.DOTALL).matcher(xml);
        if (!m.find()) return "";
        Matcher a = Pattern.compile("\\b" + attr + "\\s*=\\s*\"([^\"]*)\"").matcher(m.group(1));
        return a.find() ? a.group(1).trim() : "";
    }

    private static void add(Scatter sc, Map<String, String> m) {
        Partition p = toPartition(m);
        if (p.name.isEmpty()) return;
        for (Partition old : sc.partitions) {
            if (old.name.equalsIgnoreCase(p.name)) return;
        }
        sc.partitions.add(p);
    }

    private static String pick(Map<String, String> m, String... keys) {
        for (String k : keys) {
            String v = m.get(k);
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private static Partition toPartition(Map<String, String> m) {
        Partition p = new Partition();
        p.index = pick(m, "partition_index", "index");
        p.name = pick(m, "partition_name", "name");
        p.fileName = pick(m, "file_name", "filename", "file", "image");
        if (p.fileName.equalsIgnoreCase("NONE")) p.fileName = "";
        p.type = pick(m, "type", "partition_type");
        p.region = pick(m, "region");
        p.storage = pick(m, "storage");
        p.operationType = pick(m, "operation_type", "operation");
        String dl = pick(m, "is_download", "download");
        p.download = !("false".equalsIgnoreCase(dl) || "0".equals(dl) || "no".equalsIgnoreCase(dl));
        p.linearAddr = num(pick(m, "linear_start_addr", "linear_addr", "linear_address"));
        p.physicalAddr = num(pick(m, "physical_start_addr", "phys_addr", "physical_addr"));
        p.size = num(pick(m, "partition_size", "size"));
        return p;
    }

    private static long num(String v) {
        try {
            String s = v == null ? "" : v.trim();
            if (s.isEmpty()) return 0;
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
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }
}

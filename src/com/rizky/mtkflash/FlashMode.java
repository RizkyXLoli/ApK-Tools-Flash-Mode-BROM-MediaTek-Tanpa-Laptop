package com.rizky.mtkflash;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public enum FlashMode {

    DOWNLOAD_ONLY("Download Only", "tulis partisi yang dicentang saja"),
    FIRMWARE_UPGRADE("Firmware Upgrade", "tulis semua partisi ROM, data user dipertahankan"),
    FORMAT_ALL_DOWNLOAD("Format All + Download", "hapus data user dulu, lalu tulis semua partisi");

    public static final Set<String> DATA_PARTS = new HashSet<>(Arrays.asList(
            "userdata", "usrdata", "metadata", "cache", "user", "nvdata"));

    public final String label;
    public final String note;

    FlashMode(String label, String note) {
        this.label = label;
        this.note = note;
    }

    public boolean flashAll() {
        return this != DOWNLOAD_ONLY;
    }

    public boolean wipeFirst() {
        return this == FORMAT_ALL_DOWNLOAD;
    }

    public boolean keepData(String partition) {
        if (partition == null) return false;
        if (this != FIRMWARE_UPGRADE) return false;
        String n = partition.toLowerCase(java.util.Locale.US).trim();
        return DATA_PARTS.contains(n);
    }

    public static FlashMode byIndex(int i) {
        FlashMode[] all = values();
        return i < 0 || i >= all.length ? DOWNLOAD_ONLY : all[i];
    }

    public static String[] labels() {
        FlashMode[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) out[i] = all[i].label + " — " + all[i].note;
        return out;
    }

    @Override
    public String toString() {
        return label;
    }
}

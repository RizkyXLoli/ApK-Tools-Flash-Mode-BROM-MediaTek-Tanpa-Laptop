package com.rizky.mtkflash.auth;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-chip BROM layout, keyed by HW code.
 *
 * Every entry carries what the bypass needs and nothing else: the watchdog
 * base (the frame slot the overflow reaches is {@code watchdog + 0x50}), where
 * the payload can be staged, the {@code var1} value the control transfer has to
 * match, and the two pointer slots the register-access primitive rewrites.
 *
 * A chip missing from this table is not a bug in the app: without the real
 * BROM dump for that part there is nothing safe to write, and the bypass
 * refuses instead of guessing addresses.
 */
public final class ChipTable {

    public static final class Chip {
        public final int hwCode;
        public final String name;
        public final String description;
        public final int watchdog;
        public final int payloadAddr;
        /** -1 when the part needs a brute-force scan. */
        public final int var1;
        /** Address of the DA entry pointer slot, 0 when unknown. */
        public final int sendPtrAddr;
        public final int sendPtrValue;
        /** Address of the register-access pointer slot, 0 when unknown. */
        public final int regAccessAddr;
        public final int regAccessValue;

        Chip(int hwCode, String name, String description, int watchdog, int payloadAddr,
             int var1, int sendPtrAddr, int sendPtrValue,
             int regAccessAddr, int regAccessValue) {
            this.hwCode = hwCode;
            this.name = name;
            this.description = description;
            this.watchdog = watchdog;
            this.payloadAddr = payloadAddr;
            this.var1 = var1;
            this.sendPtrAddr = sendPtrAddr;
            this.sendPtrValue = sendPtrValue;
            this.regAccessAddr = regAccessAddr;
            this.regAccessValue = regAccessValue;
        }

        /** Frame slot the certificate overflow reaches. */
        public int frameSlot() {
            return watchdog + 0x50;
        }

        public boolean hasRegisterAccess() {
            return regAccessAddr != 0;
        }

        public boolean hasSendPtr() {
            return sendPtrAddr != 0;
        }

        @Override
        public String toString() {
            return "0x" + Integer.toHexString(hwCode).toUpperCase() + " " + name + " " + description;
        }
    }

    private static final Map<Integer, Chip> BY_HWCODE = new HashMap<>();

    static {
        c(0x0279, "MT6797/MT6767", "Helio X23/X25/X27", 0x10007000, 0x00100A00, 10, 0x001027B0, 0x00009EAC, 0x0000A18C, 0x0000A354);
        c(0x0321, "MT6735/T,MT8735A", "", 0x10212000, 0x00100A00, 40, 0x001027A0, 0x000095F8, 0x000098CC, 0x00009A94);
        c(0x0326, "MT6755/MT6750/M/T/S", "Helio P10/P15/P18", 0x10007000, 0x00100A00, 10, 0x001027B0, 0x00009A6C, 0x00009D4C, 0x00009F14);
        c(0x0335, "MT6737M/MT6735G", "", 0x10212000, 0x00100A00, 40, 0x001027A0, 0x00009608, 0x000098DC, 0x00009AA4);
        c(0x0337, "MT6753", "", 0x10212000, 0x00100A00, 40, 0x001027A0, 0x00009668, 0x0000993C, 0x00009B04);
        c(0x0507, "MT6759", "Helio P30", 0x10210000, 0x00100A00, -1, 0, 0, 0, 0);
        c(0x0551, "MT6757/MT6757D", "Helio P20", 0x10007000, 0x00100A00, 10, 0x001027B8, 0x00009C2C, 0x0000A030, 0x0000A0E8);
        c(0x0562, "MT6799", "Helio X30/X35", 0x10211000, 0x00100A00, 10, 0x001028B4, 0x0000F5AC, 0x0000F9C0, 0x0000FA78);
        c(0x0598, "ELBRUS/MT0598", "", 0x10211000, 0x00100A00, -1, 0, 0, 0, 0);
        c(0x0601, "MT6750", "", 0x10007000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x0633, "MT6570/MT8321", "", 0x10007000, 0x00100A00, -1, 0, 0, 0, 0);
        c(0x0688, "MT6758", "Helio P30", 0x10211000, 0x00100A00, 10, 0x00102874, 0x0000D860, 0x0000DC74, 0x0000DD2C);
        c(0x0690, "MT6763", "Helio P23", 0x10007000, 0x00100A00, 127, 0x00102878, 0x0000D66C, 0x0000DA80, 0x0000DB38);
        c(0x0699, "MT6739/MT6731/MT8765", "", 0x10007000, 0x00100A00, 180, 0x00102870, 0x0000DF1C, 0x0000E330, 0x0000E3E8);
        c(0x0707, "MT6768/MT6769", "Helio P65/G85 k68v1", 0x10007000, 0x00100A00, 37, 0x0010286C, 0x0000C190, 0x0000C598, 0x0000C650);
        c(0x6768, "MT6768/MT6769 (dacode)", "Helio P65/G85 k68v1", 0x10007000, 0x00100A00, 37, 0x0010286C, 0x0000C190, 0x0000C598, 0x0000C650);
        c(0x0717, "MT6761/MT6762/MT3369/MT8766B/MT8761/AC8259/AC8257", "Helio A20/P22/A22/A25/G25", 0x10007000, 0x00100A00, 37, 0x0010286C, 0x0000BC8C, 0x0000C0A0, 0x0000C158);
        c(0x0725, "MT6779", "Helio P90 k79v1", 0x10007000, 0x00100A00, 10, 0x00102878, 0x0000E04C, 0x0000E454, 0x0000E50C);
        c(0x0766, "MT6765/MT8768t", "Helio P35/G35", 0x10007000, 0x00100A00, 37, 0x0010286C, 0x0000BDC0, 0x0000C1D4, 0x0000C28C);
        c(0x0788, "MT6771/MT8385/MT8183/MT8666", "Helio P60/P70/G80", 0x10007000, 0x00100A00, 10, 0x00102878, 0x0000DEBC, 0x0000E2D0, 0x0000E388);
        c(0x0813, "MT6785", "Helio G90", 0x10007000, 0x00100A00, 10, 0x00102878, 0x0000E2A4, 0x0000E6AC, 0x0000E764);
        c(0x0816, "MT6885/MT6883/MT6889/MT6880/MT6890", "Dimensity 1000L/1000", 0x10007000, 0x00100A00, 10, 0x00102888, 0x0000E6FC, 0x0000EB04, 0x0000EBBC);
        c(0x0886, "MT6873", "Dimensity 800/820 5G", 0x10007000, 0x00100A00, 10, 0x0010288C, 0x0000EA78, 0x0000EE80, 0x0000EF38);
        c(0x0907, "MT6983", "Dimensity 9000/9000+", 0x1C007000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x0908, "MT8696", "", 0x10007000, 0x00100A00, -1, 0, 0, 0, 0);
        c(0x0930, "MT8195 Chromebook", "", 0x10007000, 0x00100A00, -1, 0, 0, 0, 0);
        c(0x0950, "MT6891/MT6893", "Dimensity 1200", 0x10007000, 0x00100A00, 10, 0x00102888, 0x0000E79C, 0x0000EBA4, 0x0000EC5C);
        c(0x0959, "MT6877/MT6877V/MT8791N", "Dimensity 900/1080/7050", 0x10007000, 0x00100A00, 10, 0x00102888, 0x0000E8D0, 0x0000ECD8, 0x0000ED90);
        c(0x0989, "MT6833", "Dimensity 700 5G k6833", 0x10007000, 0x00100A00, 115, 0x00102884, 0x0000DFE0, 0x0000E3E8, 0x0000E4A0);
        c(0x0992, "MT6880/MT6890 Modem", "", 0x10007000, 0x00100A00, -1, 0, 0, 0, 0);
        c(0x0996, "MT6853", "Dimensity 720 5G", 0x10007000, 0x00100A00, 10, 0x0010288C, 0x0000EA64, 0x0000EE6C, 0x0000EF24);
        c(0x1066, "MT6781", "Helio G96", 0x10007000, 0x00100A00, 115, 0x00102890, 0x0000E5D8, 0x0000E9DC, 0x0000EA94);
        c(0x1129, "MT6855", "Dimensity 8100", 0x1C007000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x1172, "MT6895", "Dimensity 8200", 0x1C007000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x1203, "MT6897", "Dimensity 8300 Ultra", 0x1C007000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x1208, "MT6789/MT8781V", "MTK Helio G99", 0x10007000, 0x00100A00, 10, 0, 0, 0x0000F99A, 0x0000FA0C);
        c(0x1229, "MT6886", "Dimensity 7200 Ultra", 0x1C007000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x1236, "MT6989W", "Dimensity 9300 Plus", 0x1C00B000, 0x00100A00, -1, 0, 0, 0, 0);
        c(0x1296, "MT6985", "Dimensity 9200/9200+", 0x1C007000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x1357, "MT6991", "Dimensity 9400 Ultra", 0x1C010000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x1375, "MT6878", "Dimensity 7300", 0x1C00A000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x1471, "MT6993", "Dimensity 9500", 0x1C010000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x2523, "MT2523", "", 0x10007000, 0x00100A00, 10, 0x11141F4C, 0x0000BA68, 0, 0);
        c(0x2601, "MT2601", "", 0x10007000, 0x00100A00, 10, 0x11141F4C, 0x0000BA68, 0x0040BD48, 0x0040BEFC);
        c(0x2625, "MT2625", "", 0x10007000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x6261, "MT6261/MT2503", "", 0xA0030000, 0x00100A00, 40, 0x700044B0, 0x700058EC, 0, 0);
        c(0x6572, "MT6572", "", 0x10007000, 0x010036A0, 10, 0x11141F4C, 0x0040BA68, 0x0040BD48, 0x0040BEFC);
        c(0x6575, "MT6575/MT8317", "", 0xC0000000, 0xF0000A00, -1, 0xF00025FC, 0xFFFFA0A0, 0xFFFFA3AA, 0xFFFFA4C4);
        c(0x6580, "MT6580", "", 0x10007000, 0x00100A00, 172, 0x001027A4, 0x0000B60C, 0x0000B8E0, 0x0000BA94);
        c(0x6582, "MT6582/MT6574/MT8382", "", 0x10007000, 0x00100A00, 10, 0x001027C8, 0x0000A5FC, 0x0000A8D0, 0x0000AA84);
        c(0x6583, "MT6583/6589", "", 0x10000000, 0x00100A00, -1, 0, 0, 0, 0);
        c(0x6592, "MT6592/MT8392", "", 0x10007000, 0x00100A00, 10, 0x001027A4, 0x0000A564, 0x0000A838, 0x0000A9EC);
        c(0x6595, "MT6595", "", 0x10007000, 0x00100A00, 10, 0x001027A8, 0x0000B218, 0x0000B4EC, 0x0000B6A0);
        c(0x6752, "MT6752", "", 0x10007000, 0x00100A00, 40, 0x001027A4, 0x0000990C, 0x00009BE0, 0x00009DA8);
        c(0x6795, "MT6795", "Helio X10", 0x10007000, 0x00100A00, 10, 0x001027A4, 0x0000978C, 0x00009A60, 0x00009C28);
        c(0x6899, "MT6899", "Dimensity 8400 Turbo/Ultra", 0x1C00B000, 0x00100A00, 10, 0, 0, 0, 0);
        c(0x8127, "MT8127/MT3367/AC8227L", "", 0x10007000, 0x00100A00, 10, 0x001028B0, 0x0000B2B8, 0x0000B58C, 0x0000B740);
        c(0x8163, "MT8163", "", 0x10007000, 0x00100A00, 177, 0x001028A8, 0x0000C12C, 0x0000C400, 0x0000C5C8);
        c(0x8167, "MT8167/MT8516/MT8362", "", 0x10007000, 0x00100A00, 204, 0x001029AC, 0x0000D2E4, 0x0000D6F2, 0x0000D7AC);
        c(0x8168, "MT8168/MT6357", "", 0x10007000, 0x00100A00, 10, 0x00103080, 0x00013834, 0x00013C18, 0x00013D78);
        c(0x8172, "MT8173", "", 0x10007000, 0x00120A00, 10, 0x001227B4, 0x0000A0E4, 0x0000A3B8, 0x0000A580);
        c(0x8176, "MT8176", "", 0x10007000, 0x00120A00, 10, 0x001227B4, 0x0000A0E4, 0x0000A3B8, 0x0000A580);
        c(0x8512, "MT8512", "", 0x10007000, 0x00100A00, 10, 0x00104258, 0x0000CC44, 0x0000D034, 0x0000D194);
        c(0x8590, "MT8590/MT7683/MT8521/MT7623", "", 0x10007000, 0x00100A00, 10, 0x001028B0, 0x0000BBE4, 0x0000BEB8, 0x0000C06C);
        c(0x8695, "MT8695", "", 0x10007000, 0x00100A00, 10, 0x00103088, 0x0000BEEC, 0x0000C298, 0x0000C3F8);
    }

    private static void c(int hwCode, String name, String description, int watchdog,
                          int payloadAddr, int var1, int sendPtrAddr, int sendPtrValue,
                          int regAccessAddr, int regAccessValue) {
        BY_HWCODE.put(hwCode, new Chip(hwCode, name, description, watchdog, payloadAddr,
                var1, sendPtrAddr, sendPtrValue, regAccessAddr, regAccessValue));
    }

    private ChipTable() {
    }

    /** @return the chip, or null when this HW code is not in the table. */
    public static Chip lookup(int hwCode) {
        return BY_HWCODE.get(hwCode);
    }

    public static int size() {
        return BY_HWCODE.size();
    }
}

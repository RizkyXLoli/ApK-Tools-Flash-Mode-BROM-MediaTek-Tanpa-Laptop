package com.rizky.mtkflash.auth;

import java.io.ByteArrayOutputStream;

/**
 * Minimal Thumb-2 encoder for the payloads shipped with the bypass.
 *
 * Only the encodings the payloads need are implemented. Anything added here
 * has to be exercised by the harness in {@code tools/} before it ships,
 * otherwise it is a guess that will fault on silicon.
 */
public final class Thumb {

    private Thumb() {
    }

    /** MOVW Rd, #imm16 */
    public static int[] movw(int rd, int imm16) {
        check(rd, imm16);
        int hw1 = 0xF240 | (((imm16 >> 11) & 1) << 10) | ((imm16 >> 12) & 0xF);
        int hw2 = (((imm16 >> 8) & 0x7) << 12) | (rd << 8) | (imm16 & 0xFF);
        return new int[]{hw1, hw2};
    }

    /** MOVT Rd, #imm16 */
    public static int[] movt(int rd, int imm16) {
        check(rd, imm16);
        int hw1 = 0xF2C0 | (((imm16 >> 11) & 1) << 10) | ((imm16 >> 12) & 0xF);
        int hw2 = (((imm16 >> 8) & 0x7) << 12) | (rd << 8) | (imm16 & 0xFF);
        return new int[]{hw1, hw2};
    }

    /** LDR.W Rt, [Rn, #imm12] (T3) */
    public static int[] ldrImm(int rt, int rn, int imm12) {
        check(rt, imm12);
        return new int[]{(0xF8D0 | rn), ((rt << 12) | imm12)};
    }

    /** STR.W Rt, [Rn, #imm12] (T4) */
    public static int[] strImm(int rt, int rn, int imm12) {
        check(rt, imm12);
        return new int[]{(0xF8C0 | rn), ((rt << 12) | imm12)};
    }

    /** MOVW/MOVT pair for a 32-bit constant. */
    public static int[] movImm(int rd, int imm32) {
        return concat(movw(rd, imm32 & 0xFFFF), movt(rd, (imm32 >>> 16) & 0xFFFF));
    }

    public static final int[] BX_LR = {0x4770};
    public static final int[] B_SELF = {0xE7FE};
    public static final int[] NOP = {0xBF00};

    private static void check(int reg, int imm) {
        if (reg < 0 || reg > 15) {
            throw new IllegalArgumentException("register di luar jangkauan: " + reg);
        }
        if (imm < 0 || imm > 0xFFFF) {
            throw new IllegalArgumentException("immediate di luar jangkauan: " + imm);
        }
    }

    public static int[] concat(int[]... ops) {
        int total = 0;
        for (int[] op : ops) {
            total += op.length;
        }
        int[] out = new int[total];
        int at = 0;
        for (int[] op : ops) {
            System.arraycopy(op, 0, out, at, op.length);
            at += op.length;
        }
        return out;
    }

    public static byte[] assemble(int[]... ops) {
        int[] words = concat(ops);
        ByteArrayOutputStream out = new ByteArrayOutputStream(words.length * 2);
        for (int w : words) {
            out.write(w & 0xFF);
            out.write((w >>> 8) & 0xFF);
        }
        return out.toByteArray();
    }

    /**
     * {@code *(u32*)addr = value; *(u32*)addr2 = value2; bx lr}
     *
     * The proof payload: after the hijack the host reads both words back over
     * READ32. Matching values mean the CPU ran our bytes inside the BROM with
     * no authentication.
     */
    public static byte[] payloadWriteMagic(int addr, int value, int addr2, int value2) {
        int[][] ops = new int[][]{
                movImm(0, addr),
                movImm(1, value),
                strImm(1, 0, 0),
                movImm(2, addr2),
                movImm(3, value2),
                strImm(3, 2, 0),
                BX_LR,
        };
        return assemble(ops);
    }

    public static byte[] payloadWriteMagic(int addr, int value) {
        return assemble(movImm(0, addr), movImm(1, value), strImm(1, 0, 0), BX_LR);
    }

    /** Same, but parks the CPU in the payload instead of returning. */
    public static byte[] payloadLoopMagic(int addr, int value) {
        return assemble(movImm(0, addr), movImm(1, value), strImm(1, 0, 0), B_SELF);
    }
}

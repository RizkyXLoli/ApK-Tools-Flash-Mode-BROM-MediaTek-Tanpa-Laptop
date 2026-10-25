package com.rizky.mtkflash.auth;

import java.io.IOException;

/**
 * Byte-level link to a device in download mode.
 *
 * The BROM echoes every byte the host writes, and the auth bypass needs both
 * bulk transfers (commands) and control transfers (the hijack trigger), so
 * both live here. Implementations: {@link UsbBromLink} on the phone,
 * {@link FakeBromLink} in the unit tests.
 */
public interface BromLink {

    /** Bulk OUT. */
    void write(byte[] data) throws IOException;

    /** Bulk IN, exactly {@code length} bytes. */
    byte[] read(int length, int timeoutMs) throws IOException;

    byte[] read(int length) throws IOException;

    /** Control transfer; see {@code UsbTransport#control}. */
    byte[] control(int requestType, int request, int value, int index,
                   byte[] data, int length) throws IOException;

    /** Discard anything left in the IN buffer. */
    void drain();

    void close();
}

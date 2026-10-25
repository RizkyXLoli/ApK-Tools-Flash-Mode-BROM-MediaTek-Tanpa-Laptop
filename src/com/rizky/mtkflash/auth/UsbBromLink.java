package com.rizky.mtkflash.auth;

import com.rizky.mtkflash.UsbTransport;

import java.io.IOException;

/** {@link BromLink} backed by the phone's USB host stack. */
public class UsbBromLink implements BromLink {

    private final UsbTransport usb;

    public UsbBromLink(UsbTransport usb) {
        this.usb = usb;
    }

    public UsbTransport transport() {
        return usb;
    }

    @Override
    public void write(byte[] data) throws IOException {
        usb.write(data);
    }

    @Override
    public byte[] read(int length, int timeoutMs) throws IOException {
        return usb.read(length, timeoutMs);
    }

    @Override
    public byte[] read(int length) throws IOException {
        return usb.read(length);
    }

    @Override
    public byte[] control(int requestType, int request, int value, int index,
                          byte[] data, int length) throws IOException {
        return usb.control(requestType, request, value, index, data, length);
    }

    @Override
    public void drain() {
        usb.drain();
    }

    @Override
    public void close() {
        usb.close();
    }
}

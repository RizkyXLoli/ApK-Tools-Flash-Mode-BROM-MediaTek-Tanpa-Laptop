import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

import com.rizky.mtkflash.auth.AuthBypass;
import com.rizky.mtkflash.auth.BromLink;

/**
 * Host-side harness: runs {@link AuthBypass} against the BROM emulator over
 * TCP, so the exact code that ships in the APK is exercised without a phone.
 *
 * Not part of the APK -- {@code build.sh} only compiles {@code src/}.
 */
public class AuthBypassHarness {

    /** {@link BromLink} over the bridge protocol in tools/fake_brom_server.py. */
    static class SocketBromLink implements BromLink {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;

        SocketBromLink(String host, int port) throws IOException {
            socket = new Socket(host, port);
            socket.setSoTimeout(30000);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        }

        private void checkStatus() throws IOException {
            int status = in.readUnsignedByte();
            if (status != 0) {
                int len = in.readInt();
                byte[] msg = new byte[len];
                in.readFully(msg);
                throw new IOException(new String(msg, "UTF-8"));
            }
        }

        private byte[] readPayload() throws IOException {
            int len = in.readInt();
            byte[] buf = new byte[len];
            in.readFully(buf);
            return buf;
        }

        @Override
        public synchronized void write(byte[] data) throws IOException {
            out.writeByte('W');
            out.writeInt(data.length);
            out.write(data);
            out.flush();
            checkStatus();
        }

        @Override
        public byte[] read(int length, int timeoutMs) throws IOException {
            return read(length);
        }

        @Override
        public synchronized byte[] read(int length) throws IOException {
            out.writeByte('R');
            out.writeInt(length);
            out.flush();
            checkStatus();
            return readPayload();
        }

        @Override
        public synchronized byte[] control(int requestType, int request, int value, int index,
                                           byte[] data, int length) throws IOException {
            out.writeByte('C');
            out.writeInt(requestType);
            out.writeInt(request);
            out.writeInt(value);
            out.writeInt(index);
            out.writeInt(data != null ? data.length : length);
            out.writeByte(data != null ? 1 : 0);
            if (data != null) {
                out.write(data);
            }
            out.flush();
            checkStatus();
            return readPayload();
        }

        @Override
        public synchronized void drain() {
            try {
                out.writeByte('D');
                out.flush();
                checkStatus();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            try {
                out.writeByte('X');
                out.flush();
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int var1 = args.length > 1 ? Integer.decode(args[1]) : -1;

        SocketBromLink link = new SocketBromLink("127.0.0.1", port);
        final StringBuilder log = new StringBuilder();
        AuthBypass bypass = new AuthBypass(link, new AuthBypass.Listener() {
            @Override
            public void log(String message) {
                log.append("  ").append(message).append('\n');
                System.out.println("  " + message);
            }
        });

        AuthBypass.Options opts = new AuthBypass.Options();
        opts.var1 = var1;
        opts.payloadAddr = 0x00100A00;

        AuthBypass.Result res = bypass.run(opts);
        System.out.println();
        System.out.println("=== HASIL ===");
        System.out.println(res.summary());
        for (String e : res.evidence) {
            System.out.println("  bukti: " + e);
        }
        System.out.println("SUCCESS=" + res.success);
        System.out.println("VAR1=" + res.var1);
        System.out.println("BLACKLIST_WRITE_AFTER=" + res.blacklistedWriteAfter);
        System.out.println("UNSIGNED_DA_AFTER=" + res.unsignedDaAfter);
        link.close();
        System.exit(res.success ? 0 : 1);
    }
}

package com.archie.casa;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Minimal RFC 6455 client (text frames, no extensions). Enough for Home Assistant. */
final class Ws {
    interface Listener {
        void onText(String s);
        void onClosed(Exception e);
    }

    private final Socket sock = new Socket();
    private final Random rnd = new Random();
    private InputStream in;
    private OutputStream out;
    private volatile boolean closed;

    void connect(URI uri, int timeoutMs) throws IOException {
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        sock.connect(new InetSocketAddress(uri.getHost(), port), timeoutMs);
        sock.setTcpNoDelay(true);
        sock.setSoTimeout(75_000); // pings every 30 s; silence longer than this = dead link
        in = new BufferedInputStream(sock.getInputStream(), 16 * 1024);
        out = new BufferedOutputStream(sock.getOutputStream(), 4 * 1024);
        byte[] k = new byte[16];
        rnd.nextBytes(k);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + uri.getHost() + ":" + port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.encodeToString(k, Base64.NO_WRAP) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String status = readLine();
        if (status == null || !status.contains(" 101")) throw new IOException("handshake: " + status);
        String l;
        while ((l = readLine()) != null && !l.isEmpty()) { /* skip headers */ }
        if (l == null) throw new EOFException();
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    void readLoop(Listener l) {
        Exception err = null;
        ByteArrayOutputStream msg = new ByteArrayOutputStream(8 * 1024);
        byte[] hdr = new byte[8];
        try {
            while (!closed) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) throw new EOFException();
                int op = b0 & 0x0F;
                boolean fin = (b0 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    readFully(hdr, 2);
                    len = ((hdr[0] & 0xFF) << 8) | (hdr[1] & 0xFF);
                } else if (len == 127) {
                    readFully(hdr, 8);
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | (hdr[i] & 0xFF);
                }
                byte[] mask = null;
                if ((b1 & 0x80) != 0) {
                    mask = new byte[4];
                    readFully(mask, 4);
                }
                if (len > 8_000_000) throw new IOException("frame too big");
                byte[] p = new byte[(int) len];
                readFully(p, p.length);
                if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
                if (op == 0 || op == 1) {
                    msg.write(p, 0, p.length);
                    if (fin) {
                        l.onText(msg.toString("UTF-8"));
                        msg.reset();
                    }
                } else if (op == 8) {
                    throw new EOFException("closed by server");
                } else if (op == 9) {
                    send(10, p);
                }
            }
        } catch (Exception e) {
            err = e;
        }
        close();
        l.onClosed(err);
    }

    private void readFully(byte[] b, int n) throws IOException {
        int o = 0;
        while (o < n) {
            int r = in.read(b, o, n - o);
            if (r < 0) throw new EOFException();
            o += r;
        }
    }

    void sendText(String s) throws IOException {
        send(1, s.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void send(int op, byte[] p) throws IOException {
        if (closed) throw new IOException("closed");
        int n = p.length;
        out.write(0x80 | op);
        if (n < 126) {
            out.write(0x80 | n);
        } else if (n < 65536) {
            out.write(0x80 | 126);
            out.write(n >>> 8);
            out.write(n & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) out.write(i >= 4 ? 0 : (n >>> (8 * i)) & 0xFF);
        }
        byte[] m = new byte[4];
        rnd.nextBytes(m);
        out.write(m);
        byte[] q = new byte[n];
        for (int i = 0; i < n; i++) q[i] = (byte) (p[i] ^ m[i & 3]);
        out.write(q);
        out.flush();
    }

    void close() {
        closed = true;
        try {
            sock.close();
        } catch (IOException ignored) {
        }
    }
}

package com.archie.casa;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Local caching proxy between MediaPlayer and the network.
 *
 * Why: Android 13's MatroskaExtractor cannot seek an audio-only WebM by its cues ("Did not locate the
 * video track for seeking") and falls back to walking the file cluster by cluster. Over HTTP that is
 * one small ranged request after another against YouTube: a seek to mid-song took ~46 s. Here the
 * track is downloaded once, sequentially and at full speed, into a temp file, and MediaPlayer reads
 * it through 127.0.0.1 with real Range support, so walking the file is a local read.
 *
 * Streams without a length (live / "flow" streams) or very large ones are not cached: the client is
 * redirected to the original URL. One track at a time; a new one cancels and deletes the previous.
 */
final class StreamProxy {
    private static final long MAX_CACHE = 400L << 20;

    private final File dir;
    private ServerSocket server;
    private volatile Entry current;
    private int seq;

    private static final class Entry {
        final int id;
        final String url;
        final File file;
        final Object lock = new Object();
        volatile long total = -1;      // -1 unknown yet
        volatile long have;
        volatile boolean done, failed, passthrough, cancelled;
        volatile String mime = "audio/mpeg";
        volatile HttpURLConnection conn;

        Entry(int id, String url, File file) { this.id = id; this.url = url; this.file = file; }
    }

    StreamProxy(File cacheDir) {
        dir = new File(cacheDir, "stream");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** Returns a local URL serving [url] from the cache (starting the download), or [url] itself if it cannot be proxied. */
    synchronized String proxify(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return url;
        if (isLocal(url)) return url; // LAN servers (Music Assistant flow streams...) are fast and may be live: go direct
        try {
            ensureServer();
        } catch (IOException e) {
            return url;
        }
        Entry old = current;
        if (old != null && old.url.equals(url) && !old.failed && !old.cancelled) {
            return local(old); // same track again (re-prepare after an error, replay)
        }
        if (old != null) cancel(old);
        final Entry e = new Entry(++seq, url, new File(dir, "t" + seq + ".bin"));
        current = e;
        Thread t = new Thread(() -> download(e), "casa-dl");
        t.setDaemon(true);
        t.start();
        return local(e);
    }

    /** Drops the current track's cache (playback stopped / queue cleared). */
    synchronized void clear() {
        if (current != null) cancel(current);
        current = null;
    }

    /** Host is on the local network (private IPv4, loopback, link-local or *.local). */
    static boolean isLocal(String url) {
        try {
            String h = new URL(url).getHost().toLowerCase(Locale.US);
            if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".lan")) return true;
            String[] p = h.split("\\.");
            if (p.length != 4) return false;
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31) || (a == 169 && b == 254);
        } catch (Exception e) {
            return false; // a hostname: the internet
        }
    }

    private String local(Entry e) {
        return "http://127.0.0.1:" + server.getLocalPort() + "/t/" + e.id;
    }

    private void cancel(Entry e) {
        e.cancelled = true;
        HttpURLConnection c = e.conn;
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) { }
        }
        synchronized (e.lock) { e.lock.notifyAll(); }
        //noinspection ResultOfMethodCallIgnored
        e.file.delete();
    }

    // ------------------------------------------------------------------ download

    /**
     * Downloads in 1 MB ranged chunks. YouTube (googlevideo) throttles a plain whole-file GET to about
     * playback speed (~35 KB/s measured) but serves ranged chunks at full speed (1 MB in ~0.09 s).
     * A server that ignores Range (200 instead of 206) is simply read to the end in one go.
     */
    private void download(Entry e) {
        try (RandomAccessFile out = new RandomAccessFile(e.file, "rw")) {
            byte[] buf = new byte[64 * 1024];
            while (!e.cancelled && (e.total < 0 || e.have < e.total)) {
                long from = e.have, to = from + CHUNK - 1;
                HttpURLConnection c = (HttpURLConnection) new URL(e.url).openConnection();
                e.conn = c;
                c.setConnectTimeout(8000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("Range", "bytes=" + from + "-" + to);
                int code = c.getResponseCode();
                String type = c.getContentType();
                if (type != null && !type.isEmpty() && e.total < 0) e.mime = type;
                if (code == 206) {
                    if (e.total < 0) {
                        long total = totalFromContentRange(c.getHeaderField("Content-Range"));
                        if (total <= 0 || total > MAX_CACHE) { // unknown size: not cacheable
                            c.disconnect();
                            e.passthrough = true;
                            return;
                        }
                        e.total = total;
                        signal(e);
                    }
                } else if (code == 200 && from == 0) {
                    long len = c.getContentLengthLong();
                    if (len <= 0 || len > MAX_CACHE) { // live / flow stream: let MediaPlayer talk to the origin
                        c.disconnect();
                        e.passthrough = true;
                        return;
                    }
                    e.total = len; // no Range support: this one response is the whole file
                    signal(e);
                } else {
                    c.disconnect();
                    if (e.total < 0) e.passthrough = true; else e.failed = true;
                    return;
                }
                try (InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024)) {
                    out.seek(e.have);
                    int n;
                    while (!e.cancelled && (n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        e.have += n;
                        signal(e);
                    }
                } finally {
                    c.disconnect();
                }
                if (e.have == from) { // no progress: give up rather than spin
                    e.failed = true;
                    return;
                }
            }
            if (!e.cancelled) e.done = e.total > 0 && e.have >= e.total;
        } catch (Exception ex) {
            if (e.total < 0) e.passthrough = true; // never got going: fall back to the origin
            else e.failed = true;
        } finally {
            signal(e);
        }
    }

    private static final long CHUNK = 1L << 20;

    /** "bytes 0-1048575/2622623" → 2622623, or -1 ("*" / missing). */
    private static long totalFromContentRange(String v) {
        if (v == null) return -1;
        int slash = v.lastIndexOf('/');
        if (slash < 0) return -1;
        try { return Long.parseLong(v.substring(slash + 1).trim()); } catch (NumberFormatException ex) { return -1; }
    }

    private static void signal(Entry e) {
        synchronized (e.lock) { e.lock.notifyAll(); }
    }

    // ------------------------------------------------------------------ local server

    private void ensureServer() throws IOException {
        if (server != null && !server.isClosed()) return;
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        server = ss;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    Thread w = new Thread(() -> serve(s), "casa-proxy");
                    w.setDaemon(true);
                    w.start();
                } catch (IOException ignored) {
                }
            }
        }, "casa-proxy-accept");
        t.setDaemon(true);
        t.start();
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30000);
            InputStream in = sock.getInputStream();
            String req = readLine(in);
            if (req == null) return;
            long start = 0, end = -1;
            boolean ranged = false;
            String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) {
                int c = l.indexOf(':');
                if (c > 0 && l.substring(0, c).trim().equalsIgnoreCase("range")) {
                    String v = l.substring(c + 1).trim().toLowerCase(Locale.US);
                    if (v.startsWith("bytes=")) {
                        String[] p = v.substring(6).split("-", 2);
                        try {
                            if (!p[0].isEmpty()) start = Long.parseLong(p[0].trim());
                            if (p.length > 1 && !p[1].trim().isEmpty()) end = Long.parseLong(p[1].trim());
                            ranged = true;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            String[] parts = req.split(" ");
            String path = parts.length > 1 ? parts[1] : "";
            boolean head = req.startsWith("HEAD");
            Entry e = current;
            OutputStream out = sock.getOutputStream();
            if (e == null || !path.equals("/t/" + e.id)) {
                write(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            // wait until we know what we are serving
            synchronized (e.lock) {
                long until = System.currentTimeMillis() + 20000;
                while (e.total < 0 && !e.passthrough && !e.cancelled && System.currentTimeMillis() < until) {
                    e.lock.wait(500);
                }
            }
            if (e.cancelled) return;
            if (e.passthrough || e.total < 0) {
                write(out, "HTTP/1.1 302 Found\r\nLocation: " + e.url + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            long total = e.total;
            if (end < 0 || end >= total) end = total - 1;
            if (start >= total) {
                write(out, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            long len = end - start + 1;
            StringBuilder h = new StringBuilder();
            h.append(ranged ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(e.mime).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            h.append("Content-Length: ").append(len).append("\r\n");
            if (ranged) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
            h.append("Connection: close\r\n\r\n");
            write(out, h.toString());
            if (head) return;

            byte[] buf = new byte[32 * 1024];
            long pos = start;
            try (RandomAccessFile f = new RandomAccessFile(e.file, "r")) {
                while (pos <= end && !e.cancelled) {
                    long avail;
                    synchronized (e.lock) {
                        while (e.have <= pos && !e.done && !e.failed && !e.cancelled) e.lock.wait(1000);
                        avail = e.have;
                    }
                    if (e.cancelled || avail <= pos) return; // failed before reaching here
                    int n = (int) Math.min(buf.length, Math.min(avail, end + 1) - pos);
                    f.seek(pos);
                    n = f.read(buf, 0, n);
                    if (n <= 0) return;
                    out.write(buf, 0, n);
                    pos += n;
                }
                out.flush();
            }
        } catch (Exception ignored) {
            // client went away (seek / stop): normal
        }
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("header too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}

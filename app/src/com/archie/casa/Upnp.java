package com.archie.casa;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal UPnP AV MediaRenderer: SSDP discovery, device/service descriptions, SOAP control
 * (AVTransport, RenderingControl, ConnectionManager) and GENA eventing (LastChange).
 */
final class Upnp implements Renderer.Listener {
    static final int PORT = 49494;
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String DEV = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RCS = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String CMS = "urn:schemas-upnp-org:service:ConnectionManager:1";
    private static final String SERVER = "Android/13 UPnP/1.0 Casa/1.0";

    static final String SINK_PROTOCOLS = String.join(",",
            "http-get:*:audio/mpeg:*", "http-get:*:audio/mp3:*", "http-get:*:audio/mp4:*",
            "http-get:*:audio/x-m4a:*", "http-get:*:audio/aac:*", "http-get:*:audio/aacp:*",
            "http-get:*:audio/flac:*", "http-get:*:audio/x-flac:*", "http-get:*:audio/wav:*",
            "http-get:*:audio/x-wav:*", "http-get:*:audio/L16:*", "http-get:*:audio/ogg:*",
            "http-get:*:audio/opus:*", "http-get:*:audio/webm:*", "http-get:*:application/ogg:*",
            "http-get:*:audio/*:*");

    private final Context ctx;
    private final Renderer r;
    private final String name, udn;
    private volatile boolean running;
    private ServerSocket server;
    private volatile MulticastSocket ssdp;
    private final ExecutorService gena = Executors.newSingleThreadExecutor();
    private final Random rnd = new Random();
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();
    private String lastAvt = "", lastRcs = "";

    private static final class Sub {
        final String sid, service;
        final URL callback;
        volatile long expires;
        int seq;
        Sub(String sid, String service, URL cb, long exp) { this.sid = sid; this.service = service; callback = cb; expires = exp; }
    }

    Upnp(Context c, Renderer r, String name) {
        ctx = c.getApplicationContext();
        this.r = r;
        this.name = name;
        String id = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        udn = "uuid:" + UUID.nameUUIDFromBytes(("casa-renderer-" + id).getBytes(StandardCharsets.UTF_8));
    }

    void start() {
        running = true;
        r.addListener(this);
        Thread http = new Thread(this::httpLoop, "upnp-http");
        http.setDaemon(true);
        http.start();
        Thread s = new Thread(this::ssdpLoop, "upnp-ssdp");
        s.setDaemon(true);
        s.start();
    }

    void stop() {
        running = false;
        r.removeListener(this);
        gena.execute(() -> notifyAll("ssdp:byebye"));
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try { if (ssdp != null) ssdp.close(); } catch (Exception ignored) { }
        gena.shutdown();
    }

    // ================================================================ HTTP

    private void httpLoop() {
        while (running) {
            try (ServerSocket ss = new ServerSocket()) {
                server = ss;
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                while (running) {
                    final Socket s = ss.accept();
                    Thread t = new Thread(() -> serve(s), "upnp-req");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException e) {
                if (running) sleep(3000);
            }
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] rl = line.split(" ");
            if (rl.length < 2) return;
            String method = rl[0].toUpperCase(Locale.US), path = rl[1];
            HashMap<String, String> h = new HashMap<>();
            String l;
            while ((l = readLine(in)) != null && !l.isEmpty()) {
                int c = l.indexOf(':');
                if (c > 0) h.put(l.substring(0, c).trim().toLowerCase(Locale.US), l.substring(c + 1).trim());
            }
            int len = 0;
            try { len = Integer.parseInt(h.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) { }
            byte[] body = new byte[Math.max(0, Math.min(len, 1 << 20))];
            int o = 0;
            while (o < body.length) {
                int n = in.read(body, o, body.length - o);
                if (n < 0) break;
                o += n;
            }
            OutputStream out = sock.getOutputStream();
            switch (method) {
                case "GET": case "HEAD": get(out, path, method.equals("HEAD")); break;
                case "POST": post(out, path, h, new String(body, 0, o, StandardCharsets.UTF_8)); break;
                case "SUBSCRIBE": subscribe(out, path, h); break;
                case "UNSUBSCRIBE": unsubscribe(out, h); break;
                default: reply(out, "405 Method Not Allowed", null, "");
            }
        } catch (Exception ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void reply(OutputStream out, String status, Map<String, String> extra, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
                .append("SERVER: ").append(SERVER).append("\r\n")
                .append("CONNECTION: close\r\n")
                .append("CONTENT-LENGTH: ").append(b.length).append("\r\n");
        if (b.length > 0) sb.append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet())
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private void get(OutputStream out, String path, boolean head) throws IOException {
        String body;
        switch (path) {
            case "/desc.xml": body = description(); break;
            case "/AVTransport.xml": case "/RenderingControl.xml": case "/ConnectionManager.xml":
                body = asset(path.substring(1)); break;
            default: reply(out, "404 Not Found", null, ""); return;
        }
        if (head) {
            reply(out, "200 OK", Collections.singletonMap("CONTENT-TYPE", "text/xml"), "");
        } else {
            reply(out, "200 OK", null, body);
        }
    }

    private String asset(String n) throws IOException {
        try (InputStream in = ctx.getAssets().open(n)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int k;
            while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
            return b.toString("UTF-8");
        }
    }

    private String description() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion><device>"
                + "<deviceType>" + DEV + "</deviceType>"
                + "<friendlyName>" + escape(name) + "</friendlyName>"
                + "<manufacturer>MiSmartClock-CasaApp</manufacturer>"
                + "<manufacturerURL>https://github.com/datingesp-hash/MiSmartClock-CasaApp</manufacturerURL>"
                + "<modelDescription>Casa music receiver</modelDescription>"
                + "<modelName>Casa</modelName><modelNumber>1</modelNumber>"
                + "<UDN>" + udn + "</UDN>"
                + "<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>"
                + "<serviceList>"
                + service(AVT, "AVTransport") + service(RCS, "RenderingControl") + service(CMS, "ConnectionManager")
                + "</serviceList></device></root>";
    }

    private static String service(String type, String n) {
        return "<service><serviceType>" + type + "</serviceType><serviceId>urn:upnp-org:serviceId:" + n + "</serviceId>"
                + "<SCPDURL>/" + n + ".xml</SCPDURL><controlURL>/ctl/" + n + "</controlURL>"
                + "<eventSubURL>/evt/" + n + "</eventSubURL></service>";
    }

    // ================================================================ SOAP

    private static final Pattern P_ARG = Pattern.compile("<(?:[A-Za-z0-9_]+:)?([A-Za-z0-9_]+)>(.*?)</(?:[A-Za-z0-9_]+:)?\\1>", Pattern.DOTALL);

    private void post(OutputStream out, String path, Map<String, String> h, String body) throws IOException {
        String sa = h.getOrDefault("soapaction", "").replace("\"", "");
        int hash = sa.indexOf('#');
        if (hash < 0) { reply(out, "400 Bad Request", null, ""); return; }
        String svc = sa.substring(0, hash), action = sa.substring(hash + 1);
        HashMap<String, String> a = new HashMap<>();
        int bodyStart = body.indexOf(action);
        Matcher m = P_ARG.matcher(bodyStart >= 0 ? body.substring(bodyStart) : body);
        while (m.find()) a.put(m.group(1), unescape(m.group(2)));
        LinkedMap res = new LinkedMap();
        int err;
        switch (path) {
            case "/ctl/AVTransport": err = avt(action, a, res); break;
            case "/ctl/RenderingControl": err = rcs(action, a, res); break;
            case "/ctl/ConnectionManager": err = cms(action, a, res); break;
            default: err = 401;
        }
        if (err != 0) {
            reply(out, "500 Internal Server Error", null, fault(err));
            return;
        }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + "Response xmlns:u=\"" + svc + "\">");
        for (int i = 0; i < res.keys.length && res.keys[i] != null; i++)
            sb.append('<').append(res.keys[i]).append('>').append(escape(res.vals[i])).append("</").append(res.keys[i]).append('>');
        sb.append("</u:").append(action).append("Response></s:Body></s:Envelope>");
        reply(out, "200 OK", Collections.singletonMap("EXT", ""), sb.toString());
    }

    /** Tiny ordered map (response args must keep their declared order). */
    private static final class LinkedMap {
        final String[] keys = new String[12], vals = new String[12];
        int n;
        LinkedMap put(String k, String v) { keys[n] = k; vals[n++] = v == null ? "" : v; return this; }
    }

    private static String fault(int code) {
        String desc;
        switch (code) {
            case 401: desc = "Invalid Action"; break;
            case 402: desc = "Invalid Args"; break;
            case 701: desc = "Transition not available"; break;
            case 711: desc = "Illegal seek target"; break;
            case 718: desc = "Invalid InstanceID"; break;
            default: desc = "Action Failed";
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault>"
                + "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code + "</errorCode>"
                + "<errorDescription>" + desc + "</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>";
    }

    private int avt(String action, Map<String, String> a, LinkedMap res) {
        if (!"0".equals(a.getOrDefault("InstanceID", "0").trim())) return 718;
        switch (action) {
            case "SetAVTransportURI":
                r.setUri(a.get("CurrentURI"), a.get("CurrentURIMetaData"));
                return 0;
            case "SetNextAVTransportURI":
                r.setNextUri(a.get("NextURI"), a.get("NextURIMetaData"));
                return 0;
            case "Play": r.play(); return 0;
            case "Pause": r.pause(); return 0;
            case "Stop": r.stop(); return 0;
            case "Next": return r.next() ? 0 : 701;
            case "Previous": r.seek(0); return 0;
            case "Seek": {
                String unit = a.getOrDefault("Unit", "REL_TIME");
                if (!unit.equals("REL_TIME") && !unit.equals("ABS_TIME")) return 711;
                r.seek(Renderer.parseTime(a.getOrDefault("Target", "0:00:00")));
                return 0;
            }
            case "GetTransportInfo":
                res.put("CurrentTransportState", r.state()).put("CurrentTransportStatus", r.status()).put("CurrentSpeed", "1");
                return 0;
            case "GetPositionInfo": {
                boolean has = !r.uri().isEmpty();
                String pos = Renderer.fmtTime(r.positionMs());
                res.put("Track", has ? "1" : "0")
                        .put("TrackDuration", Renderer.fmtTime(r.durationMs()))
                        .put("TrackMetaData", has ? metaOrNi(r.meta()) : "")
                        .put("TrackURI", r.uri())
                        .put("RelTime", pos).put("AbsTime", pos)
                        .put("RelCount", "2147483647").put("AbsCount", "2147483647");
                return 0;
            }
            case "GetMediaInfo":
                res.put("NrTracks", r.uri().isEmpty() ? "0" : "1")
                        .put("MediaDuration", Renderer.fmtTime(r.durationMs()))
                        .put("CurrentURI", r.uri()).put("CurrentURIMetaData", metaOrNi(r.meta()))
                        .put("NextURI", r.nextUri()).put("NextURIMetaData", metaOrNi(r.nextMeta()))
                        .put("PlayMedium", r.uri().isEmpty() ? "NONE" : "NETWORK")
                        .put("RecordMedium", "NOT_IMPLEMENTED").put("WriteStatus", "NOT_IMPLEMENTED");
                return 0;
            case "GetDeviceCapabilities":
                res.put("PlayMedia", "NETWORK").put("RecMedia", "NOT_IMPLEMENTED").put("RecQualityModes", "NOT_IMPLEMENTED");
                return 0;
            case "GetTransportSettings":
                res.put("PlayMode", "NORMAL").put("RecQualityMode", "NOT_IMPLEMENTED");
                return 0;
            case "GetCurrentTransportActions":
                res.put("Actions", r.actions());
                return 0;
            default: return 401;
        }
    }

    private static String metaOrNi(Renderer.Meta m) {
        return m.raw.isEmpty() ? "NOT_IMPLEMENTED" : m.raw;
    }

    private int rcs(String action, Map<String, String> a, LinkedMap res) {
        if (!"0".equals(a.getOrDefault("InstanceID", "0").trim())) return 718;
        switch (action) {
            case "GetVolume": res.put("CurrentVolume", String.valueOf(r.volume())); return 0;
            case "SetVolume":
                try { r.setVolume(Integer.parseInt(a.getOrDefault("DesiredVolume", "").trim())); } catch (NumberFormatException e) { return 402; }
                return 0;
            case "GetMute": res.put("CurrentMute", r.muted() ? "1" : "0"); return 0;
            case "SetMute": {
                String v = a.getOrDefault("DesiredMute", "0").trim();
                r.setMute(v.equals("1") || v.equalsIgnoreCase("true"));
                return 0;
            }
            case "ListPresets": res.put("CurrentPresetNameList", "FactoryDefaults"); return 0;
            case "SelectPreset": return 0;
            default: return 401;
        }
    }

    private int cms(String action, Map<String, String> a, LinkedMap res) {
        switch (action) {
            case "GetProtocolInfo": res.put("Source", "").put("Sink", SINK_PROTOCOLS); return 0;
            case "GetCurrentConnectionIDs": res.put("ConnectionIDs", "0"); return 0;
            case "GetCurrentConnectionInfo":
                res.put("RcsID", "0").put("AVTransportID", "0").put("ProtocolInfo", "")
                        .put("PeerConnectionManager", "").put("PeerConnectionID", "-1")
                        .put("Direction", "Input").put("Status", "OK");
                return 0;
            default: return 401;
        }
    }

    // ================================================================ GENA

    private void subscribe(OutputStream out, String path, Map<String, String> h) throws IOException {
        String service = path.replace("/evt/", "");
        if (!service.equals("AVTransport") && !service.equals("RenderingControl") && !service.equals("ConnectionManager")) {
            reply(out, "404 Not Found", null, "");
            return;
        }
        int timeout = 1800;
        String t = h.getOrDefault("timeout", "");
        if (t.startsWith("Second-")) {
            try { timeout = Math.max(60, Math.min(7200, Integer.parseInt(t.substring(7)))); } catch (NumberFormatException ignored) { }
        }
        String sid = h.get("sid");
        Sub sub;
        if (sid != null) { // renewal
            sub = subs.get(sid);
            if (sub == null) { reply(out, "412 Precondition Failed", null, ""); return; }
            sub.expires = System.currentTimeMillis() + timeout * 1000L;
        } else {
            String cb = h.getOrDefault("callback", "");
            Matcher m = Pattern.compile("<([^>]+)>").matcher(cb);
            if (!m.find() || !"upnp:event".equals(h.get("nt"))) { reply(out, "412 Precondition Failed", null, ""); return; }
            sid = "uuid:" + UUID.randomUUID();
            sub = new Sub(sid, service, new URL(m.group(1)), System.currentTimeMillis() + timeout * 1000L);
            subs.put(sid, sub);
        }
        HashMap<String, String> x = new HashMap<>();
        x.put("SID", sid);
        x.put("TIMEOUT", "Second-" + timeout);
        x.put("DATE", new java.util.Date().toString());
        reply(out, "200 OK", x, "");
        if (h.get("sid") == null) {
            final Sub s = sub;
            gena.execute(() -> send(s, initialEvent(s.service)));
        }
    }

    private void unsubscribe(OutputStream out, Map<String, String> h) throws IOException {
        String sid = h.get("sid");
        reply(out, sid != null && subs.remove(sid) != null ? "200 OK" : "412 Precondition Failed", null, "");
    }

    private String initialEvent(String service) {
        switch (service) {
            case "AVTransport": return lastChange(avtEvent());
            case "RenderingControl": return lastChange(rcsEvent());
            default:
                return "<e:property><SourceProtocolInfo></SourceProtocolInfo></e:property>"
                        + "<e:property><SinkProtocolInfo>" + escape(SINK_PROTOCOLS) + "</SinkProtocolInfo></e:property>"
                        + "<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property>";
        }
    }

    private static String lastChange(String eventXml) {
        return "<e:property><LastChange>" + escape(eventXml) + "</LastChange></e:property>";
    }

    private String avtEvent() {
        String meta = metaOrNi(r.meta());
        return "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">"
                + "<TransportState val=\"" + r.state() + "\"/>"
                + "<TransportStatus val=\"" + r.status() + "\"/>"
                + "<CurrentPlayMode val=\"NORMAL\"/>"
                + "<TransportPlaySpeed val=\"1\"/>"
                + "<NumberOfTracks val=\"" + (r.uri().isEmpty() ? 0 : 1) + "\"/>"
                + "<CurrentTrack val=\"" + (r.uri().isEmpty() ? 0 : 1) + "\"/>"
                + "<AVTransportURI val=\"" + escape(r.uri()) + "\"/>"
                + "<CurrentTrackURI val=\"" + escape(r.uri()) + "\"/>"
                + "<AVTransportURIMetaData val=\"" + escape(meta) + "\"/>"
                + "<CurrentTrackMetaData val=\"" + escape(meta) + "\"/>"
                + "<NextAVTransportURI val=\"" + escape(r.nextUri()) + "\"/>"
                + "<CurrentTrackDuration val=\"" + Renderer.fmtTime(r.durationMs()) + "\"/>"
                + "<CurrentMediaDuration val=\"" + Renderer.fmtTime(r.durationMs()) + "\"/>"
                + "<CurrentTransportActions val=\"" + r.actions() + "\"/>"
                + "</InstanceID></Event>";
    }

    private String rcsEvent() {
        return "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">"
                + "<Volume channel=\"Master\" val=\"" + r.volume() + "\"/>"
                + "<Mute channel=\"Master\" val=\"" + (r.muted() ? 1 : 0) + "\"/>"
                + "</InstanceID></Event>";
    }

    /** Renderer changed (main thread): push LastChange to subscribers if it differs. */
    @Override public void onRendererChanged() {
        if (subs.isEmpty()) return;
        gena.execute(() -> {
            String a = avtEvent(), c = rcsEvent();
            boolean da = !a.equals(lastAvt), dc = !c.equals(lastRcs);
            lastAvt = a;
            lastRcs = c;
            long now = System.currentTimeMillis();
            for (Iterator<Sub> it = subs.values().iterator(); it.hasNext(); ) {
                Sub s = it.next();
                if (s.expires < now) { it.remove(); continue; }
                if (da && s.service.equals("AVTransport")) send(s, lastChange(a));
                if (dc && s.service.equals("RenderingControl")) send(s, lastChange(c));
            }
        });
    }

    /** Runs on the gena thread. */
    private void send(Sub s, String props) {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" + props + "</e:propertyset>";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        int port = s.callback.getPort() == -1 ? 80 : s.callback.getPort();
        String path = s.callback.getFile().isEmpty() ? "/" : s.callback.getFile();
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(s.callback.getHost(), port), 3000);
            sock.setSoTimeout(3000);
            String head = "NOTIFY " + path + " HTTP/1.1\r\n"
                    + "HOST: " + s.callback.getHost() + ":" + port + "\r\n"
                    + "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n"
                    + "NT: upnp:event\r\nNTS: upnp:propchange\r\n"
                    + "SID: " + s.sid + "\r\nSEQ: " + (s.seq++) + "\r\n"
                    + "CONTENT-LENGTH: " + b.length + "\r\nCONNECTION: close\r\n\r\n";
            OutputStream o = sock.getOutputStream();
            o.write(head.getBytes(StandardCharsets.UTF_8));
            o.write(b);
            o.flush();
            sock.getInputStream().read(); // wait for the 200 (or timeout)
        } catch (Exception ignored) {
        }
    }

    // ================================================================ SSDP

    private String[] types() {
        return new String[]{"upnp:rootdevice", udn, DEV, AVT, RCS, CMS};
    }

    private static String usn(String udn, String nt) {
        return nt.equals(udn) ? udn : udn + "::" + nt;
    }

    private void ssdpLoop() {
        long lastAlive = 0;
        while (running) {
            try (MulticastSocket ms = new MulticastSocket(SSDP_PORT)) {
                ssdp = ms;
                ms.setReuseAddress(true);
                ms.setTimeToLive(4);
                ms.setSoTimeout(5000);
                InetAddress group = InetAddress.getByName(SSDP_ADDR);
                NetworkInterface nif = wifi();
                if (nif != null) {
                    ms.joinGroup(new InetSocketAddress(group, SSDP_PORT), nif);
                    ms.setNetworkInterface(nif);
                } else {
                    ms.joinGroup(group);
                }
                notifyAll("ssdp:alive");
                lastAlive = System.currentTimeMillis();
                byte[] buf = new byte[2048];
                while (running) {
                    if (System.currentTimeMillis() - lastAlive > 300_000) {
                        notifyAll("ssdp:alive");
                        lastAlive = System.currentTimeMillis();
                    }
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        ms.receive(p);
                    } catch (java.net.SocketTimeoutException te) {
                        continue;
                    }
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (!msg.startsWith("M-SEARCH")) continue;
                    String st = header(msg, "st");
                    if (st == null) continue;
                    int mx = 1;
                    try { mx = Math.max(1, Math.min(3, Integer.parseInt(header(msg, "mx")))); } catch (Exception ignored) { }
                    respond(p.getAddress(), p.getPort(), st.trim(), mx);
                }
            } catch (Exception e) {
                if (running) sleep(5000);
            }
        }
    }

    private void respond(final InetAddress to, final int port, final String st, final int mx) {
        final java.util.ArrayList<String> sts = new java.util.ArrayList<>();
        if (st.equals("ssdp:all")) Collections.addAll(sts, types());
        else for (String t : types()) if (t.equalsIgnoreCase(st)) sts.add(t);
        if (sts.isEmpty()) return;
        final int delay = rnd.nextInt(mx * 500 + 1);
        gena.execute(() -> {
            sleep(delay);
            String ip = localIp();
            if (ip == null) return;
            // reply from port 1900 (the M-SEARCH socket): firewalls on the searcher accept it as a reply
            java.net.DatagramSocket own = ssdp;
            try (java.net.DatagramSocket tmp = own == null || own.isClosed() ? new java.net.DatagramSocket() : null) {
                java.net.DatagramSocket ds = tmp != null ? tmp : own;
                for (String t : sts) {
                    String m = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: " + new java.util.Date() + "\r\nEXT:\r\n"
                            + "LOCATION: http://" + ip + ":" + PORT + "/desc.xml\r\nSERVER: " + SERVER + "\r\n"
                            + "ST: " + t + "\r\nUSN: " + usn(udn, t) + "\r\nBOOTID.UPNP.ORG: 1\r\nCONFIGID.UPNP.ORG: 1\r\n\r\n";
                    byte[] b = m.getBytes(StandardCharsets.UTF_8);
                    ds.send(new DatagramPacket(b, b.length, to, port));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void notifyAll(String nts) {
        String ip = localIp();
        if (ip == null) return;
        try (java.net.DatagramSocket ds = new java.net.DatagramSocket()) {
            InetAddress group = InetAddress.getByName(SSDP_ADDR);
            for (int rep = 0; rep < 2; rep++) {
                for (String t : types()) {
                    String m = "NOTIFY * HTTP/1.1\r\nHOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                            + (nts.equals("ssdp:alive") ? "CACHE-CONTROL: max-age=1800\r\nLOCATION: http://" + ip + ":" + PORT + "/desc.xml\r\nSERVER: " + SERVER + "\r\n" : "")
                            + "NT: " + t + "\r\nNTS: " + nts + "\r\nUSN: " + usn(udn, t) + "\r\n"
                            + "BOOTID.UPNP.ORG: 1\r\nCONFIGID.UPNP.ORG: 1\r\n\r\n";
                    byte[] b = m.getBytes(StandardCharsets.UTF_8);
                    ds.send(new DatagramPacket(b, b.length, group, SSDP_PORT));
                }
                sleep(150);
            }
        } catch (Exception ignored) {
        }
    }

    private static String header(String msg, String name) {
        for (String l : msg.split("\r?\n")) {
            int c = l.indexOf(':');
            if (c > 0 && l.substring(0, c).trim().equalsIgnoreCase(name)) return l.substring(c + 1).trim();
        }
        return null;
    }

    private static NetworkInterface wifi() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || !n.supportsMulticast()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) if (a instanceof Inet4Address) return n;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static String localIp() {
        NetworkInterface n = wifi();
        if (n == null) return null;
        for (InetAddress a : Collections.list(n.getInetAddresses()))
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
        return null;
    }

    // ================================================================ utils

    static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private static final Pattern P_ENTITY = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|lt|gt|amp|quot|apos);");

    /**
     * One level of XML unescaping, in a single pass: named entities and numeric character references
     * (Music Assistant sends non-ASCII titles as &#1058;&#1099;...). Single pass so "&amp;#1058;" becomes
     * "&#1058;" (one level), not "Т".
     */
    static String unescape(String s) {
        if (s == null) return "";
        if (s.indexOf('&') < 0) return s;
        Matcher m = P_ENTITY.matcher(s);
        StringBuilder b = new StringBuilder(s.length());
        int last = 0;
        while (m.find()) {
            b.append(s, last, m.start());
            String e = m.group(1);
            switch (e) {
                case "lt": b.append('<'); break;
                case "gt": b.append('>'); break;
                case "amp": b.append('&'); break;
                case "quot": b.append('"'); break;
                case "apos": b.append('\''); break;
                default:
                    try {
                        int cp = (e.charAt(1) == 'x' || e.charAt(1) == 'X')
                                ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                        if (Character.isValidCodePoint(cp)) b.appendCodePoint(cp);
                        else b.append(m.group());
                    } catch (NumberFormatException ex) {
                        b.append(m.group());
                    }
            }
            last = m.end();
        }
        return b.append(s, last, s.length()).toString();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}

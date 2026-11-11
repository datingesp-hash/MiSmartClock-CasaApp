package com.archie.casa;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics model, wire-format parsers (LRC, enhanced LRC, Apple TTML, QRC/YRC karaoke, plain) and the
 * small HTTP/JSON helpers the sources share. Ported from BitChord's lyrics package, trimmed to what
 * a lyrics panel needs: line timing plus per-syllable runs for the sweep.
 */
final class Lyrics {
    /** One sung line. runs (may be empty) split text into timed pieces; their char ends add up to text. */
    static final class Line {
        long time;          // ms
        long end = -1;      // ms when it stops being sung, -1 = unknown
        String text = "";
        long[] rs = new long[0], re = new long[0];
        int[] rc = new int[0]; // char end of each run in text
        boolean right;      // second voice of a duet: drawn on the right
        String agent;       // TTML voice id, while parsing
        boolean gap() { return text.isEmpty(); }
        boolean wordSynced() { return rs.length > 0; }
        long endOrWords() {
            if (end > 0) return end;
            return re.length > 0 ? re[re.length - 1] : -1;
        }
    }

    final String source;
    final List<Line> lines;
    final boolean synced, wordSynced;

    Lyrics(String source, List<Line> lines) {
        this.source = source;
        this.lines = lines;
        boolean s = false, w = false;
        for (Line l : lines) {
            if (l.time > 0) s = true;
            if (l.wordSynced()) w = true;
        }
        synced = s || w;
        wordSynced = w;
    }

    // ------------------------------------------------------------------ building lines from runs

    /** Accumulates timed text pieces (syllables keep their own spacing) into a Line. */
    static final class Builder {
        private final StringBuilder sb = new StringBuilder();
        private final ArrayList<long[]> times = new ArrayList<>();
        private final ArrayList<Integer> ends = new ArrayList<>();

        void run(String text, long start, long end) {
            if (text == null || text.isEmpty()) return;
            sb.append(text);
            times.add(new long[]{start, Math.max(start, end)});
            ends.add(sb.length());
        }

        /** Untimed text (spaces between spans): glued onto the previous run. */
        void text(String t) {
            if (t == null || t.isEmpty() || sb.length() == 0) return;
            sb.append(t);
            if (!ends.isEmpty()) ends.set(ends.size() - 1, sb.length());
        }

        boolean empty() { return times.isEmpty(); }

        Line build(long lineStart, long lineEnd) {
            String raw = sb.toString();
            int lead = 0;
            while (lead < raw.length() && Character.isWhitespace(raw.charAt(lead))) lead++;
            String text = raw.trim();
            Line l = new Line();
            l.text = collapse(text);
            if (!l.text.equals(text)) { // spacing changed: drop runs, keep line timing
                l.time = lineStart >= 0 ? lineStart : (times.isEmpty() ? 0 : times.get(0)[0]);
                l.end = lineEnd;
                return l;
            }
            int n = times.size();
            l.rs = new long[n];
            l.re = new long[n];
            l.rc = new int[n];
            for (int i = 0; i < n; i++) {
                l.rs[i] = times.get(i)[0];
                l.re[i] = times.get(i)[1];
                l.rc[i] = Math.max(0, Math.min(text.length(), ends.get(i) - lead));
            }
            l.rc[n - 1] = text.length();
            l.time = n > 0 ? (lineStart >= 0 ? Math.min(lineStart, l.rs[0]) : l.rs[0]) : Math.max(0, lineStart);
            l.end = lineEnd > 0 ? lineEnd : (n > 0 ? l.re[n - 1] : -1);
            return l;
        }
    }

    private static String collapse(String s) { return s.replaceAll("[ \\t]+", " "); }

    static Line plainLine(long time, String text) {
        Line l = new Line();
        l.time = time;
        l.text = text == null ? "" : text.trim();
        return l;
    }

    /** Sort, drop empties, add a gap line for long instrumental breaks (incl. the intro). */
    static List<Line> finish(List<Line> in) {
        ArrayList<Line> ls = new ArrayList<>();
        for (Line l : in) if (l != null && !l.text.isEmpty()) ls.add(l);
        Collections.sort(ls, (a, b) -> Long.compare(a.time, b.time));
        boolean timed = false;
        for (Line l : ls) if (l.time > 0) timed = true;
        if (!timed) return ls;
        ArrayList<Line> out = new ArrayList<>();
        if (!ls.isEmpty() && ls.get(0).time >= GAP_MS) out.add(gapLine(0, ls.get(0).time));
        for (int i = 0; i < ls.size(); i++) {
            Line l = ls.get(i);
            out.add(l);
            if (i + 1 < ls.size()) {
                long e = l.endOrWords(), next = ls.get(i + 1).time;
                if (e > 0 && next - e >= GAP_MS) out.add(gapLine(e, next));
            }
        }
        return out;
    }

    static final long GAP_MS = 5000;

    private static Line gapLine(long from, long to) {
        Line g = new Line();
        g.time = from;
        g.end = to;
        return g;
    }

    // ------------------------------------------------------------------ LRC (+ enhanced word stamps)

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD = Pattern.compile("<(\\d{1,3}):(\\d{2})[.:](\\d{2,3})>");

    private static long ms(String m, String s, String f) {
        long frac = 0;
        if (f != null && !f.isEmpty()) {
            frac = Long.parseLong(f);
            if (f.length() == 1) frac *= 100;
            else if (f.length() == 2) frac *= 10;
        }
        return Long.parseLong(m) * 60000 + Long.parseLong(s) * 1000 + frac;
    }

    static List<Line> parseLrc(String lrc) {
        ArrayList<Line> out = new ArrayList<>();
        if (lrc == null) return out;
        for (String raw : lrc.split("\r?\n")) {
            String line = raw.trim();
            ArrayList<Long> stamps = new ArrayList<>();
            int pos = 0;
            Matcher m = STAMP.matcher(line);
            while (m.find(pos) && m.start() == pos) {
                stamps.add(ms(m.group(1), m.group(2), m.group(3)));
                pos = m.end();
            }
            if (stamps.isEmpty()) continue;
            String body = line.substring(pos);
            if (body.startsWith("<R>")) body = body.substring(3);
            for (long t : stamps) {
                Line l = wordLine(t, body);
                if (l == null) l = plainLine(t, decode(body.replaceAll("<\\d{1,3}:\\d{2}[.:]\\d{2,3}>", "")));
                out.add(l);
            }
        }
        // a stamp after which the next stamp is the end of this line
        Collections.sort(out, (a, b) -> Long.compare(a.time, b.time));
        for (int i = 0; i < out.size(); i++) {
            Line l = out.get(i);
            if (l.wordSynced() && l.re.length > 0 && l.re[l.re.length - 1] <= l.rs[l.rs.length - 1]) {
                long next = i + 1 < out.size() ? out.get(i + 1).time : l.rs[l.rs.length - 1] + 800;
                l.re[l.re.length - 1] = Math.max(l.rs[l.rs.length - 1] + 1, next);
                l.end = l.re[l.re.length - 1];
            }
        }
        return finish(out);
    }

    /** "<00:01.00>Hel<00:01.20>lo <00:01.50>world<00:02.00>" → runs; null when no word stamps. */
    private static Line wordLine(long lineTime, String body) {
        Matcher m = WORD.matcher(body);
        ArrayList<long[]> at = new ArrayList<>();
        ArrayList<int[]> span = new ArrayList<>();
        while (m.find()) {
            at.add(new long[]{ms(m.group(1), m.group(2), m.group(3))});
            span.add(new int[]{m.start(), m.end()});
        }
        if (at.isEmpty()) return null;
        Builder b = new Builder();
        for (int i = 0; i < at.size(); i++) {
            int from = span.get(i)[1], to = i + 1 < span.size() ? span.get(i + 1)[0] : body.length();
            String t = decode(body.substring(from, to));
            long s = at.get(i)[0], e = i + 1 < at.size() ? at.get(i + 1)[0] : s;
            if (t.trim().isEmpty()) b.text(t);
            else b.run(t, s, e);
        }
        if (b.empty()) return null;
        return b.build(lineTime, -1);
    }

    // ------------------------------------------------------------------ QRC / YRC karaoke

    private static final Pattern K_LINE = Pattern.compile("^\\[(\\d{1,8}),(\\d{1,8})](.*)$");
    private static final Pattern K_PRE = Pattern.compile("\\((\\d{1,8}),(\\d{1,8})(?:,\\d{1,8})?\\)([^()]*)");
    private static final Pattern K_SUF = Pattern.compile("([^()]*)\\((\\d{1,8}),(\\d{1,8})(?:,\\d{1,8})?\\)");
    // greedy up to the tag's end: QQ leaves raw quotes inside the attribute ("The Weeknd")
    private static final Pattern K_CONTENT = Pattern.compile("LyricContent\\s*=\\s*\"(.*)\"\\s*/>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREDIT = Pattern.compile("^[^:：]{1,40}[:：].+$");

    private static String karaokeContent(String raw) {
        Matcher m = K_CONTENT.matcher(raw);
        return m.find() ? decode(m.group(1)) : raw;
    }

    static boolean looksKaraoke(String raw) {
        for (String l : karaokeContent(raw).split("\r?\n")) {
            Matcher m = K_LINE.matcher(l.trim());
            if (m.matches() && (K_PRE.matcher(m.group(3)).find() || K_SUF.matcher(m.group(3)).find())) return true;
        }
        return false;
    }

    static List<Line> parseKaraoke(String raw) {
        ArrayList<Line> out = new ArrayList<>();
        for (String src : karaokeContent(raw).split("\r?\n")) {
            Matcher m = K_LINE.matcher(src.trim());
            if (!m.matches()) continue;
            long ls = Long.parseLong(m.group(1)), ld = Long.parseLong(m.group(2));
            String body = m.group(3);
            Builder pre = new Builder(), suf = new Builder();
            int preLen = 0, sufLen = 0;
            Matcher p = K_PRE.matcher(body);
            while (p.find()) {
                long s = Long.parseLong(p.group(1));
                String t = decode(p.group(3));
                pre.run(t, s, s + Long.parseLong(p.group(2)));
                preLen += t.length();
            }
            Matcher q = K_SUF.matcher(body);
            while (q.find()) {
                long s = Long.parseLong(q.group(2));
                String t = decode(q.group(1));
                suf.run(t, s, s + Long.parseLong(q.group(3)));
                sufLen += t.length();
            }
            Builder b = preLen >= sufLen ? pre : suf;
            if (b.empty()) continue;
            out.add(b.build(ls, ld > 0 ? ls + ld : -1));
        }
        // QQ opens with "Title - Artist" and credit lines ("Lyrics by：…"): not sung
        Collections.sort(out, (a, b) -> Long.compare(a.time, b.time));
        while (!out.isEmpty()) {
            Line f = out.get(0);
            if (CREDIT.matcher(f.text).matches() || (f.time < 1000 && f.text.contains(" - "))) out.remove(0);
            else break;
        }
        return finish(out);
    }

    // ------------------------------------------------------------------ Apple TTML

    static List<Line> parseTtml(String ttml) {
        ArrayList<Line> out = new ArrayList<>();
        ArrayList<String> agents = new ArrayList<>();
        HashMap<String, String> agentType = new HashMap<>();
        try {
            XmlPullParser x = Xml.newPullParser();
            x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            x.setInput(new StringReader(ttml));
            Builder b = null;
            long pBegin = -1, pEnd = -1;
            String pAgent = null;
            StringBuilder pText = null;
            int depth = 0, skipDepth = -1, runDepth = -1;
            String runText = null;
            long runS = 0, runE = 0;
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    depth++;
                    String name = local(x.getName());
                    if ("agent".equals(name)) {
                        String id = attr(x, "id"), type = attr(x, "type");
                        if (id != null && type != null) agentType.put(id, type);
                    } else if ("p".equals(name)) {
                        b = new Builder();
                        pBegin = time(attr(x, "begin"));
                        pEnd = time(attr(x, "end"));
                        pAgent = attr(x, "agent");
                        pText = new StringBuilder();
                    } else if ("span".equals(name) && b != null && skipDepth < 0) {
                        String role = attr(x, "role");
                        if ("x-translation".equals(role) || "x-roman".equals(role) || "x-bg".equals(role)) {
                            skipDepth = depth;
                        } else {
                            long s = time(attr(x, "begin")), e = time(attr(x, "end"));
                            if (s >= 0) {
                                if (runText != null) b.run(runText, runS, runE); // container span: flush
                                runText = "";
                                runS = s;
                                runE = e >= 0 ? e : s;
                                runDepth = depth;
                            }
                        }
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (b != null && skipDepth < 0) {
                        String t = x.getText();
                        pText.append(t);
                        if (runText != null) runText += t;
                        else b.text(t.replaceAll("\\s+", " "));
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = local(x.getName());
                    if (skipDepth == depth) skipDepth = -1;
                    else if ("span".equals(name) && runDepth == depth && runText != null) {
                        b.run(runText.replaceAll("\\s+", " "), runS, runE);
                        runText = null;
                        runDepth = -1;
                    } else if ("p".equals(name) && b != null) {
                        Line l;
                        if (!b.empty()) l = b.build(pBegin, pEnd);
                        else {
                            l = plainLine(Math.max(0, pBegin), pText.toString().replaceAll("\\s+", " "));
                            l.end = pEnd;
                        }
                        if (pAgent != null) {
                            if (!agents.contains(pAgent)) agents.add(pAgent);
                            l.agent = pAgent; // sides resolved below
                        }
                        out.add(l);
                        b = null;
                    }
                    depth--;
                }
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        // duet layout: the first solo voice stays left, any other solo voice goes right
        ArrayList<String> people = new ArrayList<>();
        for (String a : agents) {
            String t = agentType.get(a);
            if (t == null || "person".equals(t)) people.add(a);
        }
        for (Line l : out) l.right = l.agent != null && people.size() > 1 && people.indexOf(l.agent) > 0;
        return finish(out);
    }

    private static String local(String n) {
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    private static String attr(XmlPullParser x, String localName) {
        for (int i = 0; i < x.getAttributeCount(); i++)
            if (localName.equals(local(x.getAttributeName(i)))) return x.getAttributeValue(i);
        return null;
    }

    /** "12.3", "1:02.345", "00:01:02.345", "123ms", "12.3s" → ms, -1 if missing. */
    static long time(String v) {
        if (v == null || v.trim().isEmpty()) return -1;
        v = v.trim();
        try {
            if (v.endsWith("ms")) return (long) Double.parseDouble(v.substring(0, v.length() - 2));
            if (v.endsWith("s")) v = v.substring(0, v.length() - 1);
            String[] p = v.split(":");
            double s = 0;
            for (String part : p) s = s * 60 + Double.parseDouble(part);
            return (long) (s * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ plain text / generic provider payloads

    static List<Line> parsePlain(String text) {
        ArrayList<Line> out = new ArrayList<>();
        if (text == null) return out;
        if (Pattern.compile("(?i)\\b(?:lyrics? (?:not found|unavailable)|error)\\b").matcher(text).find()) return out;
        for (String l : text.split("\r?\n")) {
            l = l.trim();
            if (l.isEmpty() || l.matches("\\[[A-Za-z]+:.*]")) continue;
            out.add(plainLine(0, l));
        }
        return out;
    }

    private static final String[] CONTENT_KEYS = {"ttml", "ttmlContent", "lyrics", "lrc", "content", "text",
            "plainLyrics", "syncedLyrics", "line", "lines", "lyric", "data", "result", "response"};

    /** Whatever a provider returned (TTML, LRC, karaoke, JSON envelopes around any of those). */
    static List<Line> parseAny(String raw) {
        String c = unwrap(raw);
        if (c == null) return null;
        if (c.toLowerCase().contains("&lt;tt"))
            c = c.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                    .replace("&apos;", "'").replace("&amp;", "&");
        List<Line> ls;
        if (Pattern.compile("<tt(?:\\s|>)", Pattern.CASE_INSENSITIVE).matcher(c).find()
                || c.contains("http://www.w3.org/ns/ttml")) ls = parseTtml(c);
        else if (looksKaraoke(c)) ls = parseKaraoke(c);
        else if (c.trim().startsWith("<")) ls = new ArrayList<>();
        else {
            ls = parseLrc(c);
            if (ls.isEmpty()) ls = parsePlain(c);
        }
        for (Line l : ls) if (!l.text.isEmpty()) return ls;
        return null;
    }

    static String unwrap(String raw) {
        if (raw == null) return null;
        String v = raw.replace("\uFEFF", "").trim();
        if (v.startsWith("```")) {
            int nl = v.indexOf('\n');
            v = nl < 0 ? "" : v.substring(nl + 1);
            if (v.trim().endsWith("```")) v = v.substring(0, v.lastIndexOf("```"));
            v = v.trim();
        }
        if (v.isEmpty()) return null;
        Object j = json(v);
        if (j == null || j instanceof String) return v;
        String s = extract(j);
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String extract(Object e) {
        if (e == null || e == JSONObject.NULL) return null;
        if (e instanceof String) {
            String t = ((String) e).trim();
            Object nested = (t.startsWith("{") || t.startsWith("[")) ? json(t) : null;
            return nested != null && !(nested instanceof String) ? extract(nested) : t;
        }
        if (e instanceof JSONArray) {
            JSONArray a = (JSONArray) e;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                String s = extract(a.opt(i));
                if (s != null) sb.append(sb.length() > 0 ? "\n" : "").append(s);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        if (e instanceof JSONObject) {
            JSONObject o = (JSONObject) e;
            if (o.optBoolean("isError") || (o.has("ok") && !o.optBoolean("ok", true))) return null;
            Object err = o.opt("error");
            if (err != null && err != JSONObject.NULL && !Boolean.FALSE.equals(err) && !"".equals(err)) return null;
            for (String k : CONTENT_KEYS) {
                if (!o.has(k)) continue;
                String s = extract(o.opt(k));
                if (s != null) return s;
            }
            if (o.opt("metadata") instanceof JSONObject) return extract(o.opt("metadata"));
            if (o.has("words")) return extract(o.opt("words"));
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    static Object json(String s) {
        if (s == null) return null;
        try {
            return new JSONTokener(s).nextValue();
        } catch (Exception e) {
            return null;
        }
    }

    static JSONObject obj(String s) {
        Object o = json(s);
        return o instanceof JSONObject ? (JSONObject) o : null;
    }

    static String decode(String t) {
        if (t == null || t.indexOf('&') < 0) return t;
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = '?';
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&apos;", "'").replace("&quot;", "\"").replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Query string from name/value pairs, skipping null or empty values. */
    static String qs(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null || kv[i + 1].isEmpty()) continue;
            sb.append(sb.length() == 0 ? "?" : "&").append(kv[i]).append('=').append(enc(kv[i + 1]));
        }
        return sb.toString();
    }

    static final String AGENT = "Casa/1.21 (Android smart clock)";

    /** Body of a 2xx response, or null. headers: name/value pairs. */
    static String get(String url, String... headers) { return http("GET", url, null, 4000, 7000, headers); }

    static String post(String url, String body, String... headers) { return http("POST", url, body, 4000, 8000, headers); }

    static String http(String method, String url, String body, int connectMs, int readMs, String... headers) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", AGENT);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
            if (body != null) {
                c.setDoOutput(true);
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) { o.write(b); }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    total += n;
                    if (total > 3_000_000 || Thread.currentThread().isInterrupted()) return null;
                }
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}

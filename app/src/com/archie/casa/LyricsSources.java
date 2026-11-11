package com.archie.casa;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the lyrics come from — the same providers, order and racing as BitChord:
 * every enabled source is asked at once, answers are taken in priority order, a synced answer wins
 * outright, a plain-text one is only a fallback, and Genius (a web page) is only asked when nobody
 * else had anything.
 *
 * Before the race, BiniLyrics names the recording (ISRC) so lrc.red and LyricsPlus can ask for that
 * exact recording instead of a fuzzy title match.
 *
 * Musixmatch is reached only through PaxSenix (needs "paxsenix_key" in config.json), not by scraping
 * its web client's signing key.
 */
final class LyricsSources {
    static final String LRC_RED = "lrc.red", BINI = "BiniLyrics", BETTER = "BetterLyrics",
            PORTATO = "BetterLyrics Portato", PAX_SPOTIFY = "PaxSenix: Spotify",
            PAX_MXM = "PaxSenix: Musixmatch", LYRICS_PLUS = "LyricsPlus", UNISON = "Unison",
            YT_CAPTIONS = "YouTube captions", YT_MUSIC = "YouTube Music", KUGOU = "KuGou",
            LRCLIB = "LRCLIB", GENIUS = "Genius";

    static final List<String> DEFAULT_ORDER = Arrays.asList(LRC_RED, BINI, BETTER, PORTATO, PAX_SPOTIFY,
            PAX_MXM, LYRICS_PLUS, UNISON, YT_CAPTIONS, YT_MUSIC, KUGOU, LRCLIB, GENIUS);

    /** Optional PaxSenix API key (Spotify / Musixmatch routes), from config.json. */
    static volatile String paxKey = "";
    /** Source order from config.json ("lyrics_sources"), or the default. */
    static volatile List<String> order = DEFAULT_ORDER;

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lyrics");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    static final class Query {
        final String title, artist, album;
        final long durationMs;
        String isrc;
        JSONObject biniHit;
        final ConcurrentHashMap<String, String> docs = new ConcurrentHashMap<>();
        private volatile String videoId;
        private volatile boolean videoSearched;

        Query(String title, String artist, String album, long durationMs) {
            this.title = forSearch(title);
            this.artist = artist == null ? "" : artist.replaceAll(" - Topic$", "").trim();
            this.album = album == null ? "" : album.trim();
            this.durationMs = durationMs;
        }

        long seconds() { return durationMs / 1000; }

        /** A document fetched once per lookup, however many sources want it (lrc.red ↔ BiniLyrics). */
        String doc(String url) {
            String d = docs.get(url);
            if (d != null) return d;
            d = Lyrics.get(url);
            if (d != null) docs.put(url, d);
            return d;
        }

        synchronized String videoId() {
            if (!videoSearched) {
                videoSearched = true;
                videoId = YouTube.findVideo(title, artist, durationMs);
            }
            return videoId;
        }
    }

    // ------------------------------------------------------------------ the race

    private static final LinkedHashMap<String, Lyrics> cache = new LinkedHashMap<String, Lyrics>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Lyrics> e) { return size() > 8; }
    };

    /** Blocking; call off the main thread. null = nothing anywhere. */
    static Lyrics lookup(String title, String artist, String album, long durationMs) {
        String key = title + "\u0001" + artist + "\u0001" + (durationMs / 2000);
        synchronized (cache) {
            if (cache.containsKey(key)) return cache.get(key);
        }
        Query q = new Query(title, artist, album, durationMs);
        List<String> seq = new ArrayList<>();
        for (String s : order) if (DEFAULT_ORDER.contains(s) && !seq.contains(s)) seq.add(s);

        if (seq.contains(BINI)) { // name the recording first, briefly
            Future<JSONObject> f = POOL.submit(() -> Bini.identify(q));
            try {
                q.biniHit = f.get(2500, TimeUnit.MILLISECONDS);
                if (q.biniHit != null) q.isrc = emptyToNull(q.biniHit.optString("isrc"));
            } catch (Exception e) {
                f.cancel(true);
            }
        }

        LinkedHashMap<String, Future<List<Lyrics.Line>>> racing = new LinkedHashMap<>();
        for (String s : seq) if (!GENIUS.equals(s)) racing.put(s, POOL.submit(task(s, q)));
        Lyrics fallback = null, found = null;
        long deadline = System.currentTimeMillis() + 16000;
        try {
            for (Map.Entry<String, Future<List<Lyrics.Line>>> e : racing.entrySet()) {
                List<Lyrics.Line> ls;
                try {
                    long left = Math.max(1, deadline - System.currentTimeMillis());
                    ls = e.getValue().get(left, TimeUnit.MILLISECONDS);
                } catch (Exception ex) {
                    ls = null;
                }
                if (ls == null || ls.isEmpty()) continue;
                Lyrics l = new Lyrics(e.getKey(), ls);
                if (l.synced) { found = l; break; }
                if (fallback == null) fallback = l;
            }
            if (found == null && fallback == null && seq.contains(GENIUS)) {
                try {
                    List<Lyrics.Line> ls = POOL.submit(task(GENIUS, q)).get(12, TimeUnit.SECONDS);
                    if (ls != null && !ls.isEmpty()) fallback = new Lyrics(GENIUS, ls);
                } catch (Exception ignored) {
                }
            }
        } finally {
            for (Future<?> f : racing.values()) f.cancel(true);
        }
        Lyrics r = found != null ? found : fallback;
        synchronized (cache) { cache.put(key, r); }
        return r;
    }

    private static Callable<List<Lyrics.Line>> task(String s, Query q) {
        return () -> {
            try {
                List<Lyrics.Line> r = fetch(s, q);
                return r == null || r.isEmpty() ? null : r;
            } catch (Throwable t) {
                return null;
            }
        };
    }

    private static List<Lyrics.Line> fetch(String s, Query q) throws InterruptedException {
        switch (s) {
            case LRC_RED: return LrcRed.lyrics(q);
            case BINI: return Bini.lyrics(q);
            case BETTER: return Better.lyrics(q, "https://lyrics-api.boidu.dev/getLyrics");
            case PORTATO: return Better.lyrics(q, "https://lyrics-api.boidu.dev/qq/getLyrics");
            case PAX_SPOTIFY: return Pax.spotify(q);
            case PAX_MXM: return Pax.musixmatch(q);
            case LYRICS_PLUS: return LyricsPlus.lyrics(q);
            case UNISON: return Unison.lyrics(q);
            case YT_CAPTIONS: return YouTube.captions(q.videoId());
            case YT_MUSIC: return YouTube.musicLyrics(q.videoId());
            case KUGOU: return KuGou.lyrics(q);
            case LRCLIB: return LrcLib.lyrics(q);
            case GENIUS: return Genius.lyrics(q);
            default: return null;
        }
    }

    // ------------------------------------------------------------------ title cleanup (credits/packaging only)

    private static final Pattern[] CREDITS = {
            Pattern.compile("\\s*[(\\[]\\s*(feat|ft|featuring|with)\\b[^)\\]]*[)\\]]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s+(feat|ft|featuring)\\.?\\s+.*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*[(\\[]\\s*(official\\s*)?(music\\s*)?(video|audio|visuali[sz]er|lyrics?\\s*video|lyrics?|m/?v|hd|hq|4k|full\\s*song)\\s*[)\\]]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*[(\\[]\\s*official\\s*[)\\]]", Pattern.CASE_INSENSITIVE),
    };

    static String forSearch(String t) {
        if (t == null) return "";
        String n = t;
        for (Pattern p : CREDITS) n = p.matcher(n).replaceAll(" ");
        n = n.replaceAll("\\s+", " ").trim().replaceAll("[,\\-–—]+$", "").trim();
        return n.isEmpty() ? t.trim() : n;
    }

    static String emptyToNull(String s) { return s == null || s.trim().isEmpty() ? null : s.trim(); }

    static String norm(String v) {
        String s = Normalizer.normalize(v == null ? "" : v, Normalizer.Form.NFD).replaceAll("\\p{Mn}+", "")
                .toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ lrc.red

    static final class LrcRed {
        private static final Pattern ISRC = Pattern.compile("[A-Z]{2}[A-Z0-9]{3}\\d{7}");
        private static final Pattern BRACKETED = Pattern.compile("[(\\[][^)\\]]*[)\\]]");
        private static final HashSet<String> VERSION = new HashSet<>(Arrays.asList("live", "remix", "remixed",
                "mix", "acoustic", "unplugged", "instrumental", "karaoke", "cappella", "acapella", "demo", "edit",
                "version", "cover", "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended"));
        private static final Pattern ARTIST_SEP = Pattern.compile(
                "\\s*(?:,|&|;|/|\\s+and\\s+|\\s+x\\s+|\\s+with\\s+|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+y\\s+)\\s*",
                Pattern.CASE_INSENSITIVE);

        static String url(String isrc) {
            if (isrc == null) return null;
            String i = isrc.trim().toUpperCase(Locale.ROOT);
            return ISRC.matcher(i).matches() ? "https://lrc.red/s/" + i + ".ttml" : null;
        }

        static List<Lyrics.Line> lyrics(Query q) {
            String known = url(q.isrc);
            if (known != null) {
                List<Lyrics.Line> l = doc(q, known);
                if (l != null) return l;
            }
            String body = Lyrics.get("https://lrc.red/search.json" + Lyrics.qs("q", (q.title + " " + q.artist).trim()));
            JSONObject o = Lyrics.obj(body);
            JSONArray hits = o == null ? null : o.optJSONArray("hits");
            if (hits == null) return null;
            JSONObject best = best(hits, q);
            String u = best == null ? null : url(best.optString("isrc"));
            return u == null || u.equals(known) ? null : doc(q, u);
        }

        private static List<Lyrics.Line> doc(Query q, String url) {
            String t = q.doc(url);
            if (t == null) return null;
            List<Lyrics.Line> l = Lyrics.parseTtml(t);
            return l.isEmpty() ? null : l;
        }

        /** Same title core and version words, a shared artist, length within 3 s. */
        static JSONObject best(JSONArray hits, Query q) {
            String core = core(q.title);
            HashSet<String> ver = version(q.title), artists = artists(q.artist);
            double secs = q.durationMs / 1000.0, bestD = Double.MAX_VALUE;
            JSONObject best = null;
            for (int i = 0; i < hits.length(); i++) {
                JSONObject h = hits.optJSONObject(i);
                if (h == null || url(h.optString("isrc")) == null) continue;
                String name = h.optString("title", "");
                if (name.isEmpty() || !core(name).equals(core) || !version(name).equals(ver)) continue;
                if (!artists.isEmpty()) {
                    boolean shared = false;
                    for (String a : artists(h.optString("artist", ""))) if (artists.contains(a)) shared = true;
                    if (!shared) continue;
                }
                double d = q.durationMs > 0 && h.has("duration") ? Math.abs(h.optDouble("duration") - secs) : 0;
                if (d > 3.0) continue;
                if (d < bestD) { bestD = d; best = h; }
            }
            return best;
        }

        static String coreOf(String t) { return core(t); }

        private static String core(String t) {
            String s = BRACKETED.matcher(t).replaceAll(" ");
            int i = s.indexOf(" - ");
            if (i >= 0) s = s.substring(0, i);
            String n = norm(s);
            return n.isEmpty() ? norm(t) : n;
        }

        private static HashSet<String> version(String t) {
            StringBuilder ex = new StringBuilder();
            Matcher m = BRACKETED.matcher(t);
            while (m.find()) ex.append(m.group()).append(' ');
            int i = t.indexOf(" - ");
            if (i >= 0) ex.append(t.substring(i + 3));
            HashSet<String> out = new HashSet<>();
            for (String w : norm(ex.toString()).split(" ")) if (VERSION.contains(w)) out.add(w);
            return out;
        }

        static HashSet<String> artists(String a) {
            HashSet<String> out = new HashSet<>();
            for (String s : ARTIST_SEP.split(a == null ? "" : a)) {
                String n = norm(s);
                if (!n.isEmpty()) out.add(n);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ BiniLyrics

    static final class Bini {
        static JSONObject identify(Query q) {
            String url = "https://lyrics-api.binimum.org/" + (q.isrc != null ? Lyrics.qs("isrc", q.isrc)
                    : Lyrics.qs("track", q.title, "artist", q.artist, "album", q.album,
                    "duration", q.seconds() > 0 ? String.valueOf(q.seconds()) : null));
            JSONObject o = Lyrics.obj(Lyrics.get(url));
            JSONArray r = o == null ? null : o.optJSONArray("results");
            return r == null ? null : r.optJSONObject(0);
        }

        static List<Lyrics.Line> lyrics(Query q) {
            JSONObject hit = q.biniHit != null ? q.biniHit : identify(q);
            String u = hit == null ? null : emptyToNull(hit.optString("lyricsUrl"));
            if (u == null) return null;
            String t = q.doc(u);
            if (t == null) return null;
            List<Lyrics.Line> l = Lyrics.parseTtml(t);
            return l.isEmpty() ? null : l;
        }
    }

    // ------------------------------------------------------------------ BetterLyrics (+ Portato)

    static final class Better {
        static List<Lyrics.Line> lyrics(Query q, String endpoint) {
            String body = Lyrics.get(endpoint + Lyrics.qs("s", q.title, "a", q.artist,
                    "d", q.seconds() > 0 ? String.valueOf(q.seconds()) : null, "al", q.album));
            return Lyrics.parseAny(body);
        }
    }

    // ------------------------------------------------------------------ PaxSenix (authenticated routes)

    static final class Pax {
        private static final String API = "https://api.paxsenix.org";

        private static String api(String pathAndQuery) {
            String key = paxKey;
            if (key == null || key.isEmpty()) return null;
            return Lyrics.http("GET", API + "/" + pathAndQuery, null, 10000, 15000,
                    "Authorization", "Bearer " + key, "Accept", "application/json, text/plain, */*");
        }

        static List<Lyrics.Line> spotify(Query q) {
            if (paxKey.isEmpty()) return null;
            String id = bestId(Lyrics.json(api("spotify/search" + Lyrics.qs("q", q.title + " " + q.artist))), q);
            List<Lyrics.Line> l = id == null ? null : parse(api("lyrics/spotify" + Lyrics.qs("id", id)));
            return l != null ? l : generic(q);
        }

        static List<Lyrics.Line> musixmatch(Query q) {
            if (paxKey.isEmpty()) return null;
            List<Lyrics.Line> l = parse(api("lyrics/musixmatch" + Lyrics.qs("t", q.title, "a", q.artist,
                    "d", String.valueOf(q.seconds()))));
            return l != null ? l : generic(q);
        }

        private static List<Lyrics.Line> generic(Query q) {
            String raw = api("lyrics/lrcget" + Lyrics.qs("q", q.title + " " + q.artist));
            JSONObject root = Lyrics.obj(raw);
            JSONArray docs = root == null ? null : root.optJSONArray("lyrics");
            if (docs == null || docs.length() == 0) return parse(raw);
            List<Lyrics.Line> best = null;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < docs.length(); i++) {
                Object d = docs.opt(i);
                List<Lyrics.Line> l = parse(d.toString());
                if (l == null) continue;
                int s = d instanceof JSONObject ? score(cand((JSONObject) d), q) : 0;
                if (s > bestScore) { bestScore = s; best = l; }
            }
            return best;
        }

        static List<Lyrics.Line> parse(String raw) {
            if (raw == null) return null;
            List<Lyrics.Line> l = timedApple(raw);
            return l != null ? l : Lyrics.parseAny(raw);
        }

        /** PaxSenix's structured Apple payload: content[] of {timestamp, endtime, text[] {text, part, ...}}. */
        static List<Lyrics.Line> timedApple(String raw) {
            JSONArray content = findContent(Lyrics.json(raw));
            if (content == null) return null;
            ArrayList<Lyrics.Line> out = new ArrayList<>();
            for (int i = 0; i < content.length(); i++) {
                JSONObject row = content.optJSONObject(i);
                if (row == null || !row.has("timestamp")) continue;
                long start = row.optLong("timestamp");
                JSONObject nextRow = content.optJSONObject(i + 1);
                long next = nextRow != null && nextRow.has("timestamp") ? nextRow.optLong("timestamp") : -1;
                JSONArray words = row.optJSONArray("text");
                if (words == null) continue;
                Lyrics.Builder b = new Lyrics.Builder();
                for (int w = 0; w < words.length(); w++) {
                    JSONObject word = words.optJSONObject(w);
                    if (word == null) continue;
                    String t = word.optString("text", "").trim();
                    if (t.isEmpty() || !word.has("timestamp")) continue;
                    long ws = word.optLong("timestamp");
                    JSONObject nw = words.optJSONObject(w + 1);
                    long we = word.has("endtime") ? word.optLong("endtime")
                            : nw != null && nw.has("timestamp") ? nw.optLong("timestamp") : next > 0 ? next : ws + 800;
                    b.run(word.optBoolean("part") ? t : t + " ", ws, we);
                }
                if (!b.empty()) out.add(b.build(start, next));
            }
            List<Lyrics.Line> f = Lyrics.finish(out);
            return f.isEmpty() ? null : f;
        }

        private static JSONArray findContent(Object e) {
            if (e instanceof JSONObject) {
                JSONObject o = (JSONObject) e;
                JSONArray c = o.optJSONArray("content");
                if (c != null) for (int i = 0; i < c.length(); i++) {
                    JSONObject r = c.optJSONObject(i);
                    if (r != null && r.has("timestamp")) return c;
                }
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    JSONArray f = findContent(o.opt(it.next()));
                    if (f != null) return f;
                }
            } else if (e instanceof JSONArray) {
                JSONArray a = (JSONArray) e;
                for (int i = 0; i < a.length(); i++) {
                    JSONArray f = findContent(a.opt(i));
                    if (f != null) return f;
                }
            }
            return null;
        }

        // candidates anywhere in a search response
        private static final String[] ID = {"id", "trackId", "track_id", "realId"},
                TITLE = {"name", "title", "trackName", "track_name"}, ARTIST = {"artistName", "artist_name"},
                DUR = {"durationInMillis", "durationMs", "duration_ms", "duration"};

        private static String bestId(Object root, Query q) {
            ArrayList<String[]> cands = new ArrayList<>();
            collect(root, cands);
            String best = null;
            int bs = 9;
            for (String[] c : cands) {
                int s = score(c, q);
                if (s > bs) { bs = s; best = c[0]; }
            }
            return best;
        }

        private static void collect(Object e, List<String[]> into) {
            if (e instanceof JSONArray) {
                JSONArray a = (JSONArray) e;
                for (int i = 0; i < a.length(); i++) collect(a.opt(i), into);
            } else if (e instanceof JSONObject) {
                JSONObject o = (JSONObject) e;
                String[] c = cand(o);
                if (c[0] != null && c[1] != null) into.add(c);
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) collect(o.opt(it.next()), into);
            }
        }

        /** {id, title, artist, durationMs} */
        private static String[] cand(JSONObject o) {
            JSONObject d = o.optJSONObject("attributes") != null ? o.optJSONObject("attributes") : o;
            String id = first(o, ID);
            if (id == null) id = first(d, ID);
            String artist = first(d, ARTIST);
            if (artist == null) {
                Object a = d.opt("artists") != null ? d.opt("artists") : d.opt("artist");
                if (a instanceof String) artist = (String) a;
                else if (a instanceof JSONObject) artist = first((JSONObject) a, new String[]{"name", "artistName", "title"});
                else if (a instanceof JSONArray) {
                    StringBuilder sb = new StringBuilder();
                    JSONArray arr = (JSONArray) a;
                    for (int i = 0; i < arr.length(); i++) {
                        Object x = arr.opt(i);
                        String n = x instanceof String ? (String) x
                                : x instanceof JSONObject ? first((JSONObject) x, new String[]{"name", "artistName", "title"}) : null;
                        if (n != null) sb.append(sb.length() > 0 ? ", " : "").append(n);
                    }
                    artist = sb.toString();
                }
            }
            long dur = 0;
            for (String k : DUR) if (d.opt(k) instanceof Number) { dur = d.optLong(k); break; }
            if (dur > 0 && dur < 10000) dur *= 1000;
            return new String[]{id, first(d, TITLE), artist == null ? "" : artist, String.valueOf(dur)};
        }

        private static String first(JSONObject o, String[] keys) {
            for (String k : keys) {
                Object v = o.opt(k);
                if (v instanceof String && !((String) v).trim().isEmpty()) return ((String) v).trim();
                if (v instanceof Number && ("id".equals(k) || k.toLowerCase().contains("id"))) return v.toString();
            }
            return null;
        }

        private static int score(String[] c, Query q) {
            int s = text(c[1], q.title, 20, 10) + text(c[2], q.artist, 15, 5);
            long d = Long.parseLong(c[3]);
            if (q.durationMs > 0 && d > 0) {
                long diff = Math.abs(d - q.durationMs);
                s += diff < 3000 ? 10 : diff < 10000 ? 5 : 0;
            }
            return s;
        }

        private static int text(String cand, String want, int exact, int partial) {
            if (cand == null || cand.trim().isEmpty() || want.trim().isEmpty()) return 0;
            String a = cand.toLowerCase(Locale.ROOT), b = want.toLowerCase(Locale.ROOT);
            if (a.equals(b)) return exact;
            return a.contains(b) || b.contains(a) ? partial : 0;
        }
    }

    // ------------------------------------------------------------------ LyricsPlus (volunteer mirrors, raced)

    static final class LyricsPlus {
        private static final String[] MIRRORS = {"https://lyricsplus.prjktla.my.id", "https://lyricsplus.atomix.one",
                "https://lyricsplus.binimum.org", "https://lyricsplus.prjktla.workers.dev",
                "https://lyricsplus-seven.vercel.app", "https://lyrics-plus-backend.vercel.app"};
        private static volatile String lastGood;

        static List<Lyrics.Line> lyrics(Query q) throws InterruptedException {
            ArrayList<String> hosts = new ArrayList<>();
            if (lastGood != null) hosts.add(lastGood);
            for (String m : MIRRORS) if (!m.equals(lastGood)) hosts.add(m);
            java.util.concurrent.ExecutorCompletionService<Object[]> ecs =
                    new java.util.concurrent.ExecutorCompletionService<>(POOL);
            ArrayList<Future<Object[]>> fs = new ArrayList<>();
            for (String h : hosts) fs.add(ecs.submit(() -> new Object[]{h, fetch(h, q)}));
            try {
                for (int i = 0; i < fs.size(); i++) {
                    Future<Object[]> f = ecs.poll(5, TimeUnit.SECONDS);
                    if (f == null) return null;
                    try {
                        Object[] r = f.get();
                        @SuppressWarnings("unchecked") List<Lyrics.Line> l = (List<Lyrics.Line>) r[1];
                        if (l != null && !l.isEmpty()) {
                            lastGood = (String) r[0];
                            return l;
                        }
                    } catch (java.util.concurrent.ExecutionException ignored) {
                    }
                }
                return null;
            } finally {
                for (Future<Object[]> f : fs) f.cancel(true);
            }
        }

        private static List<Lyrics.Line> fetch(String host, Query q) {
            JSONObject o = Lyrics.obj(Lyrics.http("GET", host + "/v2/lyrics/get" + Lyrics.qs("title", q.title, "artist", q.artist,
                    "duration", q.seconds() > 0 ? String.valueOf(q.seconds()) : null, "album", q.album, "isrc", q.isrc), null, 3000, 4500));
            JSONArray ls = o == null ? null : o.optJSONArray("lyrics");
            if (ls == null) return null;
            // voices: agents map (alias → type); the second "person" voice goes right
            HashSet<String> rightSingers = new HashSet<>();
            JSONObject agents = o.optJSONObject("metadata") == null ? null : o.optJSONObject("metadata").optJSONObject("agents");
            ArrayList<String> people = new ArrayList<>();
            if (agents != null) for (java.util.Iterator<String> it = agents.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject a = agents.optJSONObject(id);
                if (a == null) continue;
                String alias = a.optString("alias", id);
                if ("person".equals(a.optString("type"))) people.add(alias);
            }
            ArrayList<Lyrics.Line> out = new ArrayList<>();
            ArrayList<String> seenPeople = new ArrayList<>();
            for (int i = 0; i < ls.length(); i++) {
                JSONObject line = ls.optJSONObject(i);
                if (line == null || !line.has("time")) continue;
                long start = line.optLong("time");
                JSONArray syl = line.optJSONArray("syllabus");
                Lyrics.Line l;
                Lyrics.Builder b = new Lyrics.Builder();
                if (syl != null) for (int j = 0; j < syl.length(); j++) {
                    JSONObject s = syl.optJSONObject(j);
                    if (s == null || !s.has("time") || s.optBoolean("isBackground")) continue;
                    long t = s.optLong("time");
                    b.run(s.optString("text", ""), t, t + s.optLong("duration"));
                }
                if (!b.empty()) l = b.build(start, line.optLong("duration") > 0 ? start + line.optLong("duration") : -1);
                else if (!line.optString("text", "").trim().isEmpty()) {
                    l = Lyrics.plainLine(start, line.optString("text"));
                    if (line.optLong("duration") > 0) l.end = start + line.optLong("duration");
                } else continue;
                Object el = line.opt("element");
                String singer = el instanceof JSONObject ? emptyToNull(((JSONObject) el).optString("singer")) : null;
                if (singer != null && people.contains(singer) && !seenPeople.contains(singer)) seenPeople.add(singer);
                boolean opp = false;
                if (el instanceof JSONArray) {
                    JSONArray a = (JSONArray) el;
                    for (int k = 0; k < a.length(); k++)
                        if ("opposite".equals(a.optString(k)) || "right".equals(a.optString(k))) opp = true;
                }
                l.right = opp || (singer != null && seenPeople.indexOf(singer) > 0);
                out.add(l);
            }
            List<Lyrics.Line> f = Lyrics.finish(out);
            return f.isEmpty() ? null : f;
        }
    }

    // ------------------------------------------------------------------ Unison

    static final class Unison {
        static List<Lyrics.Line> lyrics(Query q) {
            JSONObject o = Lyrics.obj(Lyrics.get("https://unison.boidu.dev/lyrics" + Lyrics.qs("song", q.title,
                    "artist", q.artist, "album", q.album, "duration", q.seconds() > 0 ? String.valueOf(q.seconds()) : null)));
            if (o == null || !o.optBoolean("success")) return null;
            JSONObject d = o.optJSONObject("data");
            String text = d == null ? "" : d.optString("lyrics", "");
            if (text.trim().isEmpty()) return null;
            List<Lyrics.Line> l;
            if ("ttml".equalsIgnoreCase(d.optString("format"))) l = Lyrics.parseTtml(text);
            else if ("plain".equalsIgnoreCase(d.optString("syncType"))) l = Lyrics.parsePlain(text);
            else l = Lyrics.parseLrc(text);
            return l.isEmpty() ? null : l;
        }
    }

    // ------------------------------------------------------------------ KuGou

    static final class KuGou {
        private static final Pattern STAMPED = Pattern.compile("\\[\\d{2}:\\d{2}\\.\\d{2,3}].*");
        private static final Pattern CREDIT = Pattern.compile(".+][^\\[]+[:：].+");

        static List<Lyrics.Line> lyrics(Query q) {
            String kw = q.title.replaceAll("[(（].*?[)）]", "").trim() + " - " + q.artist.replaceAll("[(（].*?[)）]", "").trim()
                    + (q.album.isEmpty() ? "" : " " + q.album);
            int secs = (int) q.seconds();
            String[] cand = null;
            JSONObject s = Lyrics.obj(Lyrics.get("https://mobileservice.kugou.com/api/v3/search/song"
                    + Lyrics.qs("version", "9108", "plat", "0", "pagesize", "8", "showtype", "0", "keyword", kw)));
            JSONArray info = s == null || s.optJSONObject("data") == null ? null : s.optJSONObject("data").optJSONArray("info");
            if (info != null) {
                ArrayList<JSONObject> songs = new ArrayList<>();
                for (int i = 0; i < info.length(); i++) {
                    JSONObject x = info.optJSONObject(i);
                    if (x != null && (secs <= 0 || Math.abs(x.optInt("duration", -1) - secs) <= 8)
                            && same(q, x.optString("songname"), x.optString("singername"))) songs.add(x);
                }
                java.util.Collections.sort(songs, (a, b) -> Integer.compare(Math.abs(a.optInt("duration") - secs),
                        Math.abs(b.optInt("duration") - secs)));
                for (JSONObject x : songs) {
                    cand = search(q, "hash", x.optString("hash"), null);
                    if (cand != null) break;
                }
            }
            if (cand == null) cand = search(q, "keyword", kw, secs > 0 ? String.valueOf(secs * 1000) : null);
            if (cand == null) return null;
            JSONObject d = Lyrics.obj(Lyrics.get("https://lyrics.kugou.com/download" + Lyrics.qs("fmt", "lrc",
                    "charset", "utf8", "client", "pc", "ver", "1", "id", cand[0], "accesskey", cand[1])));
            if (d == null) return null;
            String lrc;
            try {
                lrc = new String(Base64.decode(d.optString("content", ""), Base64.DEFAULT), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
            lrc = stripCredits(lrc);
            if (instrumental(lrc)) return null;
            List<Lyrics.Line> l = Lyrics.parseLrc(lrc);
            return l.isEmpty() ? null : l;
        }

        private static String[] search(Query q, String k, String v, String durationMs) {
            JSONObject o = Lyrics.obj(Lyrics.get("https://lyrics.kugou.com/search" + Lyrics.qs("ver", "1", "man", "yes",
                    "client", "pc", k, v, "duration", durationMs)));
            JSONArray c = o == null ? null : o.optJSONArray("candidates");
            if (c == null) return null;
            for (int i = 0; i < c.length(); i++) {
                JSONObject f = c.optJSONObject(i);
                // a keyword search ranks by text: its first hit can be another song by the same artist
                if (f == null || ("keyword".equals(k) && !same(q, f.optString("song"), f.optString("singer")))) continue;
                return new String[]{f.optString("id"), f.optString("accesskey")};
            }
            return null;
        }

        /** Same song: same title (outside brackets / " - " suffixes) and at least one shared artist. */
        static boolean same(Query q, String title, String singers) {
            if (title == null || title.isEmpty()) return false;
            if (!norm(LrcRed.coreOf(title)).equals(norm(LrcRed.coreOf(q.title)))) return false;
            HashSet<String> want = LrcRed.artists(q.artist);
            if (want.isEmpty()) return true;
            for (String a : LrcRed.artists(singers == null ? "" : singers.replace('、', ','))) if (want.contains(a)) return true;
            return false;
        }

        static String stripCredits(String lrc) {
            ArrayList<String> ls = new ArrayList<>();
            for (String l : lrc.split("\r?\n")) if (STAMPED.matcher(l).matches()) ls.add(l);
            if (ls.isEmpty()) return "";
            int head = 0;
            for (int i = Math.min(30, ls.size() - 1); i >= 0; i--) if (CREDIT.matcher(ls.get(i)).matches()) { head = i + 1; break; }
            List<String> body = ls.subList(head, ls.size());
            int tail = 0;
            for (int i = 0; i <= Math.min(30, body.size() - 1); i++)
                if (CREDIT.matcher(body.get(body.size() - 1 - i)).matches()) { tail = i + 1; break; }
            return android.text.TextUtils.join("\n", body.subList(0, body.size() - tail));
        }

        private static boolean instrumental(String lrc) {
            boolean any = false;
            for (String l : lrc.split("\n")) {
                String t = l.replaceAll("\\[\\d{2}:\\d{2}\\.\\d{2,3}]", "").trim();
                if (t.isEmpty()) continue;
                any = true;
                if (!t.matches(".*纯音乐.*欣赏.*")) return false;
            }
            return any;
        }
    }

    // ------------------------------------------------------------------ LRCLIB

    static final class LrcLib {
        private static final Pattern NOISE = Pattern.compile("\\((?:from|feat\\.?|official|lyrical|video|audio|remix)[^)]*\\)|\\[[^]]*]|"
                + "\\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\\b", Pattern.CASE_INSENSITIVE);

        private static String clean(String s) {
            String c = NOISE.matcher(s).replaceAll(" ");
            int i = c.indexOf(" | ");
            if (i >= 0) c = c.substring(0, i);
            c = c.replaceAll("\\s+", " ").trim();
            return c.isEmpty() ? s : c;
        }

        static List<Lyrics.Line> lyrics(Query q) {
            String t = clean(q.title), a = clean(q.artist), synced = null;
            JSONObject exact = Lyrics.obj(Lyrics.get("https://lrclib.net/api/get" + Lyrics.qs("track_name", t,
                    "artist_name", a, "duration", String.valueOf(q.seconds()))));
            if (exact != null) synced = emptyToNull(exact.optString("syncedLyrics", null));
            if (synced == null) {
                Object r = Lyrics.json(Lyrics.get("https://lrclib.net/api/search" + Lyrics.qs("track_name", t, "artist_name", a)));
                if (r instanceof JSONArray) {
                    JSONArray hits = (JSONArray) r;
                    double best = Double.MAX_VALUE;
                    for (int i = 0; i < hits.length(); i++) {
                        JSONObject h = hits.optJSONObject(i);
                        String s = h == null ? null : emptyToNull(h.optString("syncedLyrics", null));
                        if (s == null || "null".equals(s)) continue;
                        double d = Math.abs(h.optDouble("duration", 0) - q.seconds());
                        if (d < best) { best = d; synced = s; }
                    }
                }
            }
            if (synced == null) return null;
            List<Lyrics.Line> l = Lyrics.parseLrc(synced);
            return l.isEmpty() ? null : l;
        }
    }

    // ------------------------------------------------------------------ YouTube (captions + YouTube Music lyrics tab)

    static final class YouTube {
        private static final String BASE = "https://music.youtube.com/youtubei/v1/";
        private static volatile String version;
        private static final Pattern VERSION = Pattern.compile("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"");

        private static String version() {
            if (version == null) {
                String page = Lyrics.get("https://music.youtube.com/", "Cookie", "SOCS=CAI",
                        "User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36");
                Matcher m = page == null ? null : VERSION.matcher(page);
                version = m != null && m.find() ? m.group(1) : "1.20261104.00.00";
            }
            return version;
        }

        private static JSONObject post(String endpoint, String extra) {
            String v = version();
            String body = "{\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"" + v
                    + "\",\"hl\":\"en\"}}," + extra + "}";
            return Lyrics.obj(Lyrics.post(BASE + endpoint + "?prettyPrint=false", body, "Content-Type", "application/json",
                    "Origin", "https://music.youtube.com", "Referer", "https://music.youtube.com/",
                    "X-YouTube-Client-Name", "67", "X-YouTube-Client-Version", v, "User-Agent", "Mozilla/5.0"));
        }

        /** The YouTube Music song for title + artist (the receiver only knows names, not a video id). */
        static String findVideo(String title, String artist, long durationMs) {
            JSONObject r = post("search", "\"query\":" + JSONObject.quote((title + " " + artist).trim())
                    + ",\"params\":\"EgWKAQIIAWoMEA4QChADEAQQCRAF\"");
            if (r == null) return null;
            ArrayList<JSONObject> items = new ArrayList<>();
            named(r, "musicResponsiveListItemRenderer", items);
            String wantTitle = norm(LrcRed.coreOf(title));
            HashSet<String> wantArtists = LrcRed.artists(artist);
            String fallback = null;
            for (JSONObject it : items) {
                String id = string(it, "videoId");
                if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) continue;
                // the row must be this song by this artist: never another song's lyrics
                List<String> texts = strings(it);
                String all = " " + norm(android.text.TextUtils.join(" ", texts)) + " ";
                if (wantTitle.isEmpty() || !all.contains(" " + wantTitle + " ")) continue;
                boolean byArtist = wantArtists.isEmpty();
                for (String a : wantArtists) if (all.contains(" " + a + " ")) byArtist = true;
                if (!byArtist) continue;
                if (durationMs <= 0) return id;
                if (fallback == null) fallback = id;
                for (String x : texts) {
                    Matcher m = Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(x.trim());
                    if (m.matches()) {
                        long d = (Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 1000;
                        if (Math.abs(d - durationMs) <= 4000) return id;
                    }
                }
            }
            return fallback;
        }

        static List<Lyrics.Line> musicLyrics(String videoId) {
            if (videoId == null) return null;
            JSONObject next = post("next", "\"videoId\":\"" + videoId + "\",\"isAudioOnly\":true");
            if (next == null) return null;
            ArrayList<JSONObject> tabs = new ArrayList<>();
            named(next, "tabRenderer", tabs);
            JSONObject endpoint = null;
            for (JSONObject t : tabs) {
                boolean lyr = false;
                for (String s : strings(t)) if ("Lyrics".equalsIgnoreCase(s)) lyr = true;
                if (lyr) {
                    ArrayList<JSONObject> be = new ArrayList<>();
                    named(t, "browseEndpoint", be);
                    if (!be.isEmpty()) { endpoint = be.get(0); break; }
                }
            }
            if (endpoint == null) return null;
            String browseId = endpoint.optString("browseId", "");
            if (browseId.isEmpty()) return null;
            String params = endpoint.optString("params", "");
            JSONObject page = post("browse", "\"browseId\":" + JSONObject.quote(browseId)
                    + (params.isEmpty() ? "" : ",\"params\":" + JSONObject.quote(params)));
            ArrayList<JSONObject> shelf = new ArrayList<>();
            if (page != null) named(page, "musicDescriptionShelfRenderer", shelf);
            if (shelf.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (String s : strings(shelf.get(0).opt("description"))) sb.append(s);
            List<Lyrics.Line> l = Lyrics.parsePlain(sb.toString());
            return l.isEmpty() ? null : l;
        }

        static List<Lyrics.Line> captions(String videoId) {
            if (videoId == null) return null;
            byte[] id = videoId.getBytes(StandardCharsets.UTF_8);
            byte[] p = new byte[id.length + 2];
            p[0] = 10;
            p[1] = (byte) id.length;
            System.arraycopy(id, 0, p, 2, id.length);
            JSONObject r = post("get_transcript", "\"params\":\"" + Base64.encodeToString(p, Base64.NO_WRAP) + "\"");
            if (r == null) return null;
            ArrayList<JSONObject> cues = new ArrayList<>();
            named(r, "transcriptCueRenderer", cues);
            ArrayList<Lyrics.Line> out = new ArrayList<>();
            for (JSONObject c : cues) {
                if (!c.has("startOffsetMs")) continue;
                StringBuilder sb = new StringBuilder();
                for (String s : strings(c.opt("cue"))) sb.append(s);
                String t = sb.toString().replaceAll("^[\\s♪]+|[\\s♪]+$", "");
                if (t.isEmpty()) continue;
                Lyrics.Line l = Lyrics.plainLine(c.optLong("startOffsetMs"), t);
                if (c.has("durationMs")) l.end = l.time + c.optLong("durationMs");
                out.add(l);
            }
            List<Lyrics.Line> f = Lyrics.finish(out);
            return f.isEmpty() ? null : f;
        }

        private static void named(Object e, String name, List<JSONObject> out) {
            if (e instanceof JSONObject) {
                JSONObject o = (JSONObject) e;
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    Object v = o.opt(k);
                    if (k.equals(name) && v instanceof JSONObject) out.add((JSONObject) v);
                    named(v, name, out);
                }
            } else if (e instanceof JSONArray) {
                JSONArray a = (JSONArray) e;
                for (int i = 0; i < a.length(); i++) named(a.opt(i), name, out);
            }
        }

        private static String string(Object e, String key) {
            if (e instanceof JSONObject) {
                JSONObject o = (JSONObject) e;
                if (o.opt(key) instanceof String) return o.optString(key);
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String s = string(o.opt(it.next()), key);
                    if (s != null) return s;
                }
            } else if (e instanceof JSONArray) {
                JSONArray a = (JSONArray) e;
                for (int i = 0; i < a.length(); i++) {
                    String s = string(a.opt(i), key);
                    if (s != null) return s;
                }
            }
            return null;
        }

        /** YouTube's text runs: "text"/"simpleText" leaves, in order. */
        private static List<String> strings(Object e) {
            ArrayList<String> out = new ArrayList<>();
            texts(e, out);
            return out;
        }

        private static void texts(Object e, List<String> out) {
            if (e instanceof String) out.add((String) e);
            else if (e instanceof JSONArray) {
                JSONArray a = (JSONArray) e;
                for (int i = 0; i < a.length(); i++) texts(a.opt(i), out);
            } else if (e instanceof JSONObject) {
                JSONObject o = (JSONObject) e;
                if (o.opt("text") instanceof String) out.add(o.optString("text"));
                else if (o.opt("simpleText") instanceof String) out.add(o.optString("simpleText"));
                else for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (k.endsWith("Endpoint") || k.equals("trackingParams") || k.equals("accessibility")) continue;
                    texts(o.opt(k), out);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Genius (plain text, last resort)

    static final class Genius {
        private static final Pattern NOISE = Pattern.compile("\\s*[(\\[]\\s*(?:from|feat\\.?|ft\\.?|featuring|with|prod\\.?|produced by|official|lyrical|video|audio|remix|music video|visualizer|mv|hd|4k|hq|full song)[^)\\]]*[)\\]]"
                + "|\\s*\\b(?:official\\s+(?:music\\s+)?(?:video|audio)|lyrical(?:\\s+video)?|full\\s+song|4k\\s+video|hd\\s+video|music\\s+video)\\b", Pattern.CASE_INSENSITIVE);

        private static String clean(String t) {
            String c = t.replaceAll("[♪♫★☆【】《》「」~_]", " ");
            c = NOISE.matcher(c).replaceAll(" ").replaceAll("(?i)\\b(?:prod(?:uced)?\\.?(?:\\s+by)?)\\s+.*$", " ");
            int i = c.indexOf(" | ");
            if (i >= 0) c = c.substring(0, i);
            c = c.replaceAll("\\s+", " ").trim();
            return c.isEmpty() ? t.trim() : c;
        }

        static List<Lyrics.Line> lyrics(Query q) {
            String title = clean(q.title), artist = clean(q.artist);
            String plain = title.replaceAll("\\s*[(\\[].*?[)\\]]", " ").replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                    .replaceAll("\\s+", " ").trim();
            ArrayList<String[]> tries = new ArrayList<>();
            if (!artist.isEmpty()) tries.add(new String[]{artist + " " + title, title, artist});
            if (!artist.isEmpty() && !plain.isEmpty() && !plain.equals(title))
                tries.add(new String[]{artist + " " + plain, plain, artist});
            tries.add(new String[]{plain.isEmpty() ? title : plain, plain.isEmpty() ? title : plain, artist});
            String url = null;
            HashSet<String> done = new HashSet<>();
            for (String[] t : tries) {
                if (!done.add(t[0])) continue;
                url = search(t[0], t[1], t[2]);
                if (url != null) break;
            }
            if (url == null) return null;
            String html = Lyrics.http("GET", url, null, 4000, 8000, "Accept", "text/html,application/xhtml+xml");
            return html == null ? null : parse(html);
        }

        private static String search(String query, String title, String artist) {
            JSONObject root = Lyrics.obj(Lyrics.get("https://genius.com/api/search/multi?q=" + Lyrics.enc(query)));
            JSONObject resp = root == null ? null : root.optJSONObject("response");
            JSONArray sections = resp == null ? null : resp.optJSONArray("sections");
            if (sections == null) return null;
            String nt = title.toLowerCase(Locale.ROOT), na = artist.toLowerCase(Locale.ROOT), best = null;
            int bs = 0;
            for (int i = 0; i < sections.length(); i++) {
                JSONObject s = sections.optJSONObject(i);
                if (s == null || !"song".equals(s.optString("type"))) continue;
                JSONArray hits = s.optJSONArray("hits");
                if (hits == null) continue;
                for (int j = 0; j < hits.length(); j++) {
                    JSONObject r = hits.optJSONObject(j) == null ? null : hits.optJSONObject(j).optJSONObject("result");
                    if (r == null) continue;
                    String t = r.optString("title", "").toLowerCase(Locale.ROOT), a = r.optString("artist_names", "").toLowerCase(Locale.ROOT);
                    boolean tm = !nt.isEmpty() && (t.equals(nt) || t.contains(nt) || nt.contains(t));
                    boolean am = !na.isEmpty() && (a.equals(na) || a.contains(na) || na.contains(a));
                    if (!tm && !am) continue;
                    int sc = (t.equals(nt) ? 50 : tm ? 25 : 0) + (am ? (a.equals(na) ? 40 : 20) : 0);
                    String path = r.optString("path", "").toLowerCase(Locale.ROOT);
                    if (path.contains("translation") && !nt.contains("translation")) sc -= 30;
                    if (path.contains("tracklist") || path.contains("album-art")) sc -= 50;
                    if (sc > bs) { bs = sc; best = r.optString("url", null); }
                }
                break;
            }
            return best;
        }

        private static final Pattern OPEN = Pattern.compile("<div[^>]*data-lyrics-container=\"true\"[^>]*>");
        private static final Pattern TAG = Pattern.compile("</?div\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        private static final Pattern EXCLUDE = Pattern.compile("<(\\w+)[^>]*data-exclude-from-selection=\"true\"[^>]*>");

        static List<Lyrics.Line> parse(String html) {
            StringBuilder all = new StringBuilder();
            Matcher m = OPEN.matcher(html);
            int from = 0;
            while (m.find(from)) {
                int end = balanced(html, m.end(), "div");
                String inner = html.substring(m.end(), end);
                all.append(text(inner)).append('\n');
                from = Math.min(html.length(), end + 1);
            }
            if (all.toString().trim().isEmpty()) return null;
            String raw = all.toString().replace('\u00A0', ' ').replace('\u200B', ' ').replace('\uFEFF', ' ')
                    .replaceAll("(?i)\\d*You might also like", "").trim().replaceAll("(?i)\\d*Embed\\s*$", "").trim();
            ArrayList<Lyrics.Line> out = new ArrayList<>();
            boolean lastGap = false;
            for (String l : raw.split("\n")) {
                l = l.trim();
                if (l.matches("^\\[.*]$")) continue; // [Intro], [Chorus], [Letra de "…"]
                if (l.isEmpty()) {
                    if (!lastGap && !out.isEmpty()) { out.add(Lyrics.plainLine(0, "")); lastGap = true; }
                } else {
                    out.add(Lyrics.plainLine(0, l));
                    lastGap = false;
                }
            }
            while (!out.isEmpty() && out.get(out.size() - 1).gap()) out.remove(out.size() - 1);
            // keep stanza breaks as a blank line marker the view renders as space
            for (Lyrics.Line l : out) if (l.gap()) l.text = " ";
            return out.isEmpty() ? null : out;
        }

        /** Index of the closing tag that balances an element opened just before `from`. */
        private static int balanced(String html, int from, String tag) {
            Pattern p = Pattern.compile("<(/?)" + tag + "\\b[^>]*>", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            int depth = 1, pos = from;
            while (m.find(pos)) {
                if (m.group(1).isEmpty()) depth++;
                else if (--depth == 0) return m.start();
                pos = m.end();
            }
            return html.length();
        }

        private static String text(String inner) {
            // drop the header/ads blocks Genius marks as not part of the selection
            Matcher ex = EXCLUDE.matcher(inner);
            StringBuilder sb = new StringBuilder();
            int pos = 0;
            while (ex.find(pos)) {
                sb.append(inner, pos, ex.start());
                int end = balanced(inner, ex.end(), ex.group(1));
                int close = inner.indexOf('>', end);
                pos = close < 0 ? inner.length() : close + 1;
            }
            sb.append(inner.substring(Math.min(pos, inner.length())));
            String s = sb.toString().replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)<p\\b[^>]*>", "\n")
                    .replaceAll("(?is)<(script|style|button)\\b.*?</\\1>", "").replaceAll("<[^>]+>", "");
            return Lyrics.decode(s);
        }
    }
}

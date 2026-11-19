package com.ioskeyboard.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Small, fast word completion (English + Indonesian) that learns the words you type. */
final class Suggest {
    private final Context ctx;
    private volatile String[] words = new String[0]; // sorted
    private volatile int[] rank = new int[0];
    private final HashMap<String, Integer> dictRank = new HashMap<>();
    private final TreeMap<String, Integer> user = new TreeMap<>();
    private volatile boolean dirty;

    Suggest(Context c) {
        ctx = c.getApplicationContext();
        new Thread(this::load, "dict-load").start();
    }

    private void load() {
        HashMap<String, Integer> m = new HashMap<>();
        for (String f : new String[]{"words_en.txt", "words_id.txt"}) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open(f), StandardCharsets.UTF_8))) {
                String line;
                int i = 0;
                while ((line = r.readLine()) != null) {
                    for (String w : line.trim().split("\\s+")) {
                        if (w.isEmpty()) continue;
                        w = w.toLowerCase(Locale.ROOT);
                        Integer old = m.get(w);
                        if (old == null || old > i) m.put(w, i);
                        i++;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        String[] ws = m.keySet().toArray(new String[0]);
        Arrays.sort(ws);
        int[] rk = new int[ws.length];
        for (int i = 0; i < ws.length; i++) rk[i] = m.get(ws[i]);

        TreeMap<String, Integer> u = new TreeMap<>();
        File uf = new File(ctx.getFilesDir(), "user_words.txt");
        if (uf.exists()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(uf), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int t = line.indexOf('\t');
                    if (t <= 0) continue;
                    try {
                        u.put(line.substring(0, t), Integer.parseInt(line.substring(t + 1)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        synchronized (this) {
            dictRank.clear();
            dictRank.putAll(m);
            // merge, keeping anything learned while loading
            for (Map.Entry<String, Integer> e : u.entrySet()) {
                Integer cur = user.get(e.getKey());
                user.put(e.getKey(), cur == null ? e.getValue() : cur + e.getValue());
            }
        }
        rank = rk;
        words = ws;
    }

    synchronized void learn(String w) {
        String k = w.toLowerCase(Locale.ROOT);
        if (k.length() < 2 || k.length() > 30) return;
        Integer c = user.get(k);
        user.put(k, c == null ? 1 : Math.min(c + 1, 10000));
        dirty = true;
    }

    void saveAsync() {
        if (!dirty) return;
        dirty = false;
        final List<Map.Entry<String, Integer>> snap;
        synchronized (this) {
            snap = new ArrayList<>(user.entrySet());
        }
        new Thread(() -> {
            File f = new File(ctx.getFilesDir(), "user_words.tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, Integer> e : snap) {
                    w.write(e.getKey());
                    w.write('\t');
                    w.write(String.valueOf(e.getValue()));
                    w.write('\n');
                }
            } catch (Exception ignored) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            f.renameTo(new File(ctx.getFilesDir(), "user_words.txt"));
        }, "dict-save").start();
    }

    private double score(String w, int dictIdxRank) {
        double s = dictIdxRank >= 0 ? 100000.0 / (dictIdxRank + 20) : 0;
        Integer u;
        synchronized (this) {
            u = user.get(w);
        }
        if (u != null) s += 900.0 * u + 150;
        return s;
    }

    /**
     * Fills {@code out} with up to two best candidates (case adjusted to {@code typed}).
     *
     * @return true if {@code typed} itself is a known word.
     */
    boolean lookup(String typed, List<String> out) {
        String p = typed.toLowerCase(Locale.ROOT);
        String[] ws = words;
        int[] rk = rank;
        String best1 = null, best2 = null;
        double s1 = -1, s2 = -1;
        boolean known;
        synchronized (this) {
            known = dictRank.containsKey(p) || (user.containsKey(p) && user.get(p) >= 2);
        }

        // 1) completions from the dictionary
        int lo = lowerBound(ws, p);
        int scanned = 0;
        for (int i = lo; i < ws.length && scanned < 3000; i++, scanned++) {
            String w = ws[i];
            if (!w.startsWith(p)) break;
            if (w.equals(p)) continue;
            double s = score(w, rk[i]);
            if (s > s1) { best2 = best1; s2 = s1; best1 = w; s1 = s; }
            else if (s > s2) { best2 = w; s2 = s; }
        }
        // 2) completions from learned words
        synchronized (this) {
            for (Map.Entry<String, Integer> e : user.subMap(p, true, p + '￿', true).entrySet()) {
                String w = e.getKey();
                if (w.equals(p) || w.equals(best1) || w.equals(best2)) continue;
                Integer dr = dictRank.get(w);
                double s = (dr != null ? 100000.0 / (dr + 20) : 0) + 900.0 * e.getValue() + 150;
                if (s > s1) { best2 = best1; s2 = s1; best1 = w; s1 = s; }
                else if (s > s2) { best2 = w; s2 = s; }
            }
        }
        // 3) typo corrections (one edit away) when completions are weak
        if (p.length() >= 2 && (best2 == null || !known)) {
            char first = p.charAt(0);
            int a = lowerBound(ws, String.valueOf(first));
            String c1 = null, c2 = null;
            double cs1 = -1, cs2 = -1;
            for (int i = a; i < ws.length; i++) {
                String w = ws[i];
                if (w.charAt(0) != first) break;
                if (Math.abs(w.length() - p.length()) > 1 || w.equals(p)) continue;
                if (!oneEdit(p, w)) continue;
                double s = score(w, rk[i]) * 1.5;
                if (s > cs1) { c2 = c1; cs2 = cs1; c1 = w; cs1 = s; }
                else if (s > cs2) { c2 = w; cs2 = s; }
            }
            // corrections first when the typed word is unknown
            if (!known) {
                String[] merged = pick(c1, c2, best1, best2);
                best1 = merged[0];
                best2 = merged[1];
            } else if (best2 == null) {
                String[] merged = pick(best1, c1, c2, null);
                best1 = merged[0];
                best2 = merged[1];
            }
        }
        if (best1 != null) out.add(IosKeyboardService.matchCase(typed, best1));
        if (best2 != null) out.add(IosKeyboardService.matchCase(typed, best2));
        return known;
    }

    private static String[] pick(String... c) {
        String[] r = new String[2];
        int k = 0;
        for (String s : c) {
            if (s == null || k == 2) continue;
            if (k == 1 && s.equals(r[0])) continue;
            r[k++] = s;
        }
        return r;
    }

    private static int lowerBound(String[] a, String key) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid].compareTo(key) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** true if a and b differ by one substitution, insertion, deletion or adjacent swap. */
    static boolean oneEdit(String a, String b) {
        int la = a.length(), lb = b.length();
        if (la == lb) {
            int i = 0;
            while (i < la && a.charAt(i) == b.charAt(i)) i++;
            if (i == la) return false;
            if (a.substring(i + 1).equals(b.substring(i + 1))) return true;
            return i + 1 < la && a.charAt(i) == b.charAt(i + 1) && a.charAt(i + 1) == b.charAt(i)
                    && a.substring(i + 2).equals(b.substring(i + 2));
        }
        if (la > lb) {
            String t = a; a = b; b = t;
            la = a.length(); lb = b.length();
        }
        int i = 0;
        while (i < la && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(i).equals(b.substring(i + 1));
    }
}

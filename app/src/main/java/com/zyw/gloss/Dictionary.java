package com.zyw.gloss;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Offline dictionaries: assets/dicts/&lt;src&gt;_&lt;tgt&gt;.tsv[.gz], lines "key\tgloss", sorted by UTF-8 bytes.
 * aapt2 decompresses *.gz in assets and strips the .gz suffix, so we try both .tsv.gz and .tsv.
 */
public final class Dictionary {
  public interface Opener { InputStream open(String name) throws IOException; }
  public static final String[] LANGS = {"en","zh","ja","de","fr","es","ru"};
  static final class D { byte[] b; int[] off; int n; String src; }
  private static final HashMap<String, D> cache = new HashMap<>();
  private static final Set<String> missing = new HashSet<>();
  private static final int MAX_TRANS_CACHE = 4096;
  private static final LinkedHashMap<String, String> transCache =
      new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) {
          return size() > MAX_TRANS_CACHE;
        }
      };
  /** Null-object for negative cache (no translation). */
  private static final String NONE = "\0";

  static synchronized D get(Opener o, String s, String t) {
    String k = s + "_" + t;
    D d = cache.get(k);
    if (d != null) return d;
    if (missing.contains(k)) return null;
    String used = null;
    try {
      InputStream raw = null;
      try {
        raw = o.open("dicts/" + k + ".tsv.gz");
        used = k + ".tsv.gz";
        d = parse(new GZIPInputStream(new BufferedInputStream(raw, 65536)));
      } catch (Throwable gzFail) {
        if (raw != null) try { raw.close(); } catch (Throwable ignored) {}
        raw = o.open("dicts/" + k + ".tsv");
        used = k + ".tsv";
        d = parse(new BufferedInputStream(raw, 65536));
      }
      d.src = used;
      cache.put(k, d);
      return d;
    } catch (Throwable e) {
      missing.add(k);
      return null;
    }
  }

  static D parse(InputStream in) throws IOException {
    try (InputStream input = in) {
      ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 22);
      byte[] buf = new byte[65536];
      int r;
      while ((r = input.read(buf)) > 0) bo.write(buf, 0, r);
      D d = new D();
      d.b = bo.toByteArray();
      int c = 0;
      for (byte x : d.b) if (x == '\n') c++;
      d.off = new int[c + 1];
      d.off[0] = 0;
      int j = 1;
      for (int i = 0; i < d.b.length; i++) if (d.b[i] == '\n' && j <= c) d.off[j++] = i + 1;
      d.n = c;
      return d;
    }
  }

  public static boolean has(Opener o, String s, String t) { return get(o, s, t) != null; }
  public static int size(Opener o, String s, String t) { D d = get(o, s, t); return d == null ? 0 : d.n; }
  public static String loadedVia(Opener o, String s, String t) { D d = get(o, s, t); return d == null ? null : d.src; }

  static String find(D d, String key) {
    byte[] k = key.getBytes(StandardCharsets.UTF_8);
    int lo = 0, hi = d.n - 1;
    while (lo <= hi) {
      int m = (lo + hi) >>> 1, p = d.off[m], c = 0, i = 0;
      for (;; i++) {
        int a = d.b[p + i] & 0xff;
        if (a == '\t') a = -1;
        int bb = i < k.length ? (k[i] & 0xff) : -1;
        if (a == -1 && bb == -1) { c = 0; break; }
        if (a != bb) { c = a - bb; break; }
      }
      if (c == 0) {
        int s = p + k.length + 1, e = s;
        while (e < d.b.length && d.b[e] != '\n') e++;
        return new String(d.b, s, e - s, StandardCharsets.UTF_8);
      }
      if (c < 0) lo = m + 1; else hi = m - 1;
    }
    return null;
  }

  /** Exact match, then longest CJK/kana prefix (so 这是 → 这). */
  static String findFlex(D d, String key, boolean prefixOk) {
    String r = find(d, key);
    if (r != null || !prefixOk || key.length() <= 1) return r;
    for (int len = key.length() - 1; len >= 1; len--) {
      r = find(d, key.substring(0, len));
      if (r != null) return r;
    }
    return null;
  }

  public static List<String> detect(String w) {
    boolean han = false, kana = false, hangul = false, cyr = false, latin = false;
    for (int i = 0; i < w.length(); ) {
      int cp = w.codePointAt(i);
      i += Character.charCount(cp);
      Character.UnicodeScript sc;
      try { sc = Character.UnicodeScript.of(cp); } catch (Exception e) { continue; }
      switch (sc) {
        case HAN: han = true; break;
        case HIRAGANA: case KATAKANA: kana = true; break;
        case HANGUL: hangul = true; break;
        case CYRILLIC: cyr = true; break;
        case LATIN: latin = true; break;
        default:
      }
    }
    if (kana) return Arrays.asList("ja");
    if (hangul) return Arrays.asList("ko");
    if (han) return Arrays.asList("zh", "ja");
    if (cyr) return Arrays.asList("ru");
    if (latin) return Arrays.asList("en", "de", "fr", "es");
    return Collections.emptyList();
  }

  static String pivotKey(String g) {
    g = g.split("[;,]")[0].trim().toLowerCase(Locale.ROOT);
    if (g.startsWith("to ")) g = g.substring(3);
    return g;
  }

  static boolean isCjkKey(String s) {
    return "zh".equals(s) || "ja".equals(s) || "ko".equals(s);
  }

  /** Translate; results cached. Returns null if none. */
  public static synchronized String translate(Opener o, String word, String tgt) {
    if (word == null) return null;
    word = word.trim();
    if (word.isEmpty() || word.length() > 30) return null;
    String ck = word + "\t" + tgt;
    String cached = transCache.get(ck);
    if (cached != null) return cached == NONE ? null : cached;

    String result = null;
    for (String s : detect(word)) {
      if (s.equals(tgt)) { transCache.put(ck, NONE); return null; }
      String key = isCjkKey(s) ? word : word.toLowerCase(Locale.ROOT);
      boolean prefix = isCjkKey(s);
      D d = get(o, s, tgt);
      if (d != null) {
        String r = findFlex(d, key, prefix);
        if (r != null) { result = r; break; }
        continue;
      }
      D se = get(o, s, "en"), et = get(o, "en", tgt);
      if (se != null) {
        String en = findFlex(se, key, prefix);
        if (en != null) {
          if (tgt.equals("en")) { result = en; break; }
          if (et != null) {
            String r = find(et, pivotKey(en));
            if (r != null) { result = r; break; }
          }
        }
      }
    }
    transCache.put(ck, result == null ? NONE : result);
    return result;
  }
}

package com.zyw.gloss;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Offline dictionaries: assets/dicts/&lt;src&gt;_&lt;tgt&gt;.tsv[.gz], lines "key\tgloss", sorted by UTF-8 bytes.
 * aapt2 decompresses *.gz in assets and strips the .gz suffix, so we try both .tsv.gz and .tsv.
 *
 * CJK matching (multi-char candidates):
 * 1) exact whole key
 * 2) 不/没 + rest composition
 * 3) strip leading pronoun, then exact / 不-compose / prefix≥2
 * 4) longest prefix with length ≥ 2 (never fall back to a single char)
 * 5) bipartite segment when both parts match well
 * Never show a lone first-character gloss for a multi-character candidate.
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

  private static final String[] PRONOUNS = {
      "我们", "你们", "他们", "她们", "它们", "咱们",
      "我", "你", "他", "她", "它", "您", "咱"
  };

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

  /** First sense only; strip leading "to "; trim noise; cap length. */
  static String cleanGloss(String g) {
    if (g == null) return null;
    int semi = g.indexOf(';');
    if (semi >= 0) g = g.substring(0, semi);
    int slash = g.indexOf('/');
    if (slash >= 0) g = g.substring(0, slash);
    g = g.trim();
    if (g.regionMatches(true, 0, "to ", 0, 3)) g = g.substring(3).trim();
    g = g.replaceAll("\\s*\\([^)]*\\)", "").trim();
    if (g.length() > 28) {
      int sp = g.lastIndexOf(' ', 28);
      g = (sp > 10 ? g.substring(0, sp) : g.substring(0, 28)).trim();
    }
    return g.isEmpty() ? null : g;
  }

  static String negateEn(String g) {
    String c = cleanGloss(g);
    if (c == null) return null;
    if (c.regionMatches(true, 0, "not ", 0, 4)) return c;
    return "not " + c;
  }

  /** Longest prefix match with length ≥ minLen. */
  static String findLongestPrefix(D d, String key, int minLen) {
    if (key.length() <= minLen) return null;
    for (int len = key.length() - 1; len >= minLen; len--) {
      String r = find(d, key.substring(0, len));
      if (r != null) return r;
    }
    return null;
  }

  static String stripLeadingPronoun(String key) {
    for (String p : PRONOUNS) {
      if (key.startsWith(p) && key.length() > p.length()) return key.substring(p.length());
    }
    return null;
  }

  /** 不/没 + known word → "not …" */
  static String findNegation(D d, String key) {
    if (key.length() < 2) return null;
    char c0 = key.charAt(0);
    if (c0 != '不' && c0 != '没') return null;
    String base = find(d, key.substring(1));
    if (base == null) return null;
    return negateEn(base);
  }

  /**
   * Match one segment: exact; else 不-compose; else prefix≥2 for multi-char.
   * Single-char parts: exact only.
   */
  static String matchPart(D d, String part) {
    if (part.isEmpty()) return null;
    String r = find(d, part);
    if (r != null) return r;
    r = findNegation(d, part);
    if (r != null) return r; // already cleaned via negateEn
    if (part.length() >= 3) {
      r = findLongestPrefix(d, part, 2);
      if (r != null) return r;
    }
    return null;
  }

  static boolean isCleanedNegation(String g) {
    return g != null && g.regionMatches(true, 0, "not ", 0, 4);
  }

  /**
   * Split into two parts; both must match well. Prefer splits where the
   * shorter part is as long as possible and at least one part is multi-char.
   * Returns already-cleaned joined gloss, or null.
   */
  static String findSegmented(D d, String key) {
    int n = key.length();
    if (n < 2) return null;
    String best = null;
    int bestScore = -1;
    for (int i = 1; i < n; i++) {
      String L = key.substring(0, i);
      String R = key.substring(i);
      // Two single-char parts: only when BOTH have exact dict hits (e.g. 这+是).
      boolean bothSingle = L.length() < 2 && R.length() < 2;
      String gLraw;
      String gRraw;
      if (bothSingle) {
        gLraw = find(d, L);
        gRraw = find(d, R);
      } else {
        gLraw = matchPart(d, L);
        gRraw = matchPart(d, R);
      }
      if (gLraw == null || gRraw == null) continue;
      String gL = isCleanedNegation(gLraw) ? gLraw : cleanGloss(gLraw);
      String gR = isCleanedNegation(gRraw) ? gRraw : cleanGloss(gRraw);
      if (gL == null || gR == null) continue;
      int score = Math.min(L.length(), R.length()) * 100 + L.length() + R.length();
      if (R.length() >= 2) score += 10;
      if (L.length() >= 2) score += 5;
      if (score > bestScore) {
        bestScore = score;
        best = gL + " · " + gR;
      }
    }
    return best;
  }

  /**
   * CJK-aware lookup. For multi-char keys never returns a single-char-only hit.
   * Returned string is cleaned (except segmented joins which are pre-cleaned).
   */
  static String findFlex(D d, String key, boolean cjk) {
    String r = find(d, key);
    if (r != null) return cleanGloss(r);

    if (!cjk) return null;

    // Single-char candidate: exact only (already missed)
    if (key.length() <= 1) return null;

    r = findNegation(d, key);
    if (r != null) return r;

    String stripped = stripLeadingPronoun(key);
    // Only use pronoun-strip when the remainder is still multi-char, so we never
    // collapse e.g. 我不 → lone 不, or 我不理解 → lone 我.
    if (stripped != null && stripped.length() >= 2) {
      r = find(d, stripped);
      if (r != null) return cleanGloss(r);
      r = findNegation(d, stripped);
      if (r != null) return r;
      r = findLongestPrefix(d, stripped, 2);
      if (r != null) return cleanGloss(r);
    }

    r = findLongestPrefix(d, key, 2);
    if (r != null) return cleanGloss(r);

    r = findSegmented(d, key);
    if (r != null) return r;

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
    if (g.startsWith("not ")) g = g.substring(4);
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
      boolean cjk = isCjkKey(s);
      D d = get(o, s, tgt);
      if (d != null) {
        String r = findFlex(d, key, cjk);
        if (r != null) { result = r; break; }
        continue;
      }
      D se = get(o, s, "en"), et = get(o, "en", tgt);
      if (se != null) {
        String en = findFlex(se, key, cjk);
        if (en != null) {
          if (tgt.equals("en")) { result = en; break; }
          if (et != null) {
            String r = find(et, pivotKey(en));
            if (r != null) { result = cleanGloss(r); break; }
          }
        }
      }
    }
    transCache.put(ck, result == null ? NONE : result);
    return result;
  }
}

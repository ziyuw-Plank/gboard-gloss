package io.github.ziyuw_plank.gboardgloss;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Offline dictionaries + exact glossary. Display policy (v0.3):
 * prefer exact / glossary / clean negation; never show bad segment stitches.
 * If only weak prefix/segment hits exist, return null (show nothing).
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
  private static final String NONE = "\0";

  /** Builtin + user exact glossary: key → gloss (case-sensitive for CJK). */
  private static final HashMap<String, String> glossary = new HashMap<>();
  private static boolean glossaryLoaded;
  private static int glossaryBuiltin, glossaryUser;

  private static final String[] PRONOUNS = {
      "我们", "你们", "他们", "她们", "它们", "咱们",
      "我", "你", "他", "她", "它", "您", "咱"
  };

  /** Single chars that must never dominate a multi-char compound gloss. */
  private static final String WEAK_HEAD =
      "的了吗呢吧啊哦嗯着过和与或及在有是不没很还就都也又再把被让给对从上到"
          + "一二三四五六七八九十百千万亿横纵正反大小高低长短";

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
  public static int glossaryBuiltinCount() { return glossaryBuiltin; }
  public static int glossaryUserCount() { return glossaryUser; }

  /** Load builtin assets/glossary.tsv and optional user TSV body. Safe to call again. */
  public static synchronized void loadGlossary(Opener o, String userTsv) {
    glossary.clear();
    glossaryBuiltin = 0;
    glossaryUser = 0;
    glossaryLoaded = true;
    if (o != null) {
      try (InputStream in = o.open("glossary.tsv")) {
        glossaryBuiltin = mergeGlossaryStream(in, glossary);
      } catch (Throwable ignored) {}
    }
    if (userTsv != null && !userTsv.isEmpty()) {
      glossaryUser = mergeGlossaryStream(
          new ByteArrayInputStream(userTsv.getBytes(StandardCharsets.UTF_8)), glossary);
    }
  }

  static int mergeGlossaryStream(InputStream in, Map<String, String> into) {
    int n = 0;
    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) continue;
        int tab = line.indexOf('\t');
        if (tab <= 0) continue;
        String k = line.substring(0, tab).trim();
        String v = line.substring(tab + 1).trim();
        if (k.isEmpty() || v.isEmpty()) continue;
        into.put(k, v);
        n++;
      }
    } catch (Throwable ignored) {}
    return n;
  }

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

  /** First sense; strip leading "to "; drop parentheticals; soft length cap on word boundary. */
  static String cleanGloss(String g) {
    if (g == null) return null;
    int semi = g.indexOf(';');
    if (semi >= 0) g = g.substring(0, semi);
    int slash = g.indexOf('/');
    if (slash >= 0) g = g.substring(0, slash);
    g = g.trim();
    if (g.regionMatches(true, 0, "to ", 0, 3)) g = g.substring(3).trim();
    g = g.replaceAll("\\s*\\([^)]*\\)", "").trim();
    return fitGlossToken(g, 24);
  }

  /**
   * Cap length without mid-token "...". Prefer last space / middle-dot before max;
   * ellipsis only after a complete token. Returns null if empty.
   */
  public static String fitGlossToken(String g, int maxChars) {
    if (g == null) return null;
    g = g.trim().replaceAll("\\s+", " ");
    if (g.isEmpty()) return null;
    if (g.length() <= maxChars) return g;
    // Prefer break on · or space
    int cut = -1;
    for (int i = Math.min(maxChars, g.length() - 1); i >= Math.max(4, maxChars / 3); i--) {
      char ch = g.charAt(i);
      if (ch == ' ' || ch == '·' || ch == '/' || ch == ',') { cut = i; break; }
    }
    if (cut > 0) {
      String head = g.substring(0, cut).trim();
      if (head.endsWith("·")) head = head.substring(0, head.length() - 1).trim();
      return head.isEmpty() ? null : head + "…";
    }
    // No token boundary: shrink by code points, no ellipsis (caller may shrink font)
    return g.substring(0, maxChars).trim();
  }

  static String negateEn(String g) {
    String c = cleanGloss(g);
    if (c == null) return null;
    if (c.regionMatches(true, 0, "not ", 0, 4)) return c;
    return "not " + c;
  }

  static String stripLeadingPronoun(String key) {
    for (String p : PRONOUNS) {
      if (key.startsWith(p) && key.length() > p.length()) return key.substring(p.length());
    }
    return null;
  }

  static String findNegation(D d, String key) {
    if (key.length() < 2) return null;
    char c0 = key.charAt(0);
    if (c0 != '不' && c0 != '没') return null;
    String base = find(d, key.substring(1));
    if (base == null) return null;
    return negateEn(base);
  }

  static boolean isWeakHeadChar(String key) {
    if (key == null || key.isEmpty()) return true;
    return WEAK_HEAD.indexOf(key.charAt(0)) >= 0;
  }

  /**
   * Score a candidate gloss for a multi-char key. Negative = reject.
   * Rejects stitches where a weak single-char head dominates a longer compound.
   */
  static int scoreGloss(String key, String gloss, boolean fromSegment) {
    if (gloss == null || gloss.isEmpty()) return -1;
    if (!fromSegment) return 100;
    // Segmented: require · and both sides non-empty
    int dot = gloss.indexOf('·');
    if (dot < 0) return 40;
    String L = gloss.substring(0, dot).trim();
    String R = gloss.substring(dot + 1).trim();
    if (L.isEmpty() || R.isEmpty()) return -1;
    if (key.length() >= 3 && isWeakHeadChar(key) && L.length() <= 12) {
      // e.g. 横动量 → "place flat · momentum"
      return -1;
    }
    if (key.length() >= 3 && key.length() > 2) {
      // First CJK char alone as a segment head of a 3+ compound → reject
      // Detected when left gloss is short and key starts with weak/common char
      if (isWeakHeadChar(key)) return -1;
    }
    return 30;
  }

  /**
   * High-quality display match only. No longest-prefix, no garbage bipartite stitches.
   */
  static String findFlex(D d, String key, boolean cjk) {
    String r = find(d, key);
    if (r != null) {
      String c = cleanGloss(r);
      if (c != null) return c;
    }
    if (!cjk) return null;
    if (key.length() <= 1) return null;

    r = findNegation(d, key);
    if (r != null) return r;

    String stripped = stripLeadingPronoun(key);
    if (stripped != null && stripped.length() >= 2) {
      r = find(d, stripped);
      if (r != null) {
        String c = cleanGloss(r);
        if (c != null) return c;
      }
      r = findNegation(d, stripped);
      if (r != null) return r;
    }

    // Deliberately no longest-prefix / segment fallback — prefer empty over garbage.
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

  /** First detected source lang code, or null. */
  public static String detectPrimary(String w) {
    List<String> d = detect(w);
    return d.isEmpty() ? null : d.get(0);
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

  static String glossaryLookup(String word) {
    if (!glossaryLoaded || word == null) return null;
    String g = glossary.get(word);
    if (g != null) return fitGlossToken(g, 28);
    // Latin keys: try lowercase
    if (!word.isEmpty() && word.charAt(0) < 0x2E80) {
      g = glossary.get(word.toLowerCase(Locale.ROOT));
      if (g != null) return fitGlossToken(g, 28);
    }
    return null;
  }

  /** Translate offline; results cached. Returns null if none / poor only. */
  public static synchronized String translate(Opener o, String word, String tgt) {
    if (word == null) return null;
    word = word.trim();
    if (word.isEmpty() || word.length() > 30) return null;
    if (!glossaryLoaded) loadGlossary(o, null);

    String ck = word + "\t" + tgt;
    String cached = transCache.get(ck);
    if (cached != null) return cached == NONE ? null : cached;

    // Glossary exact (seed is primarily term→en). Highest priority for English target.
    if ("en".equals(tgt)) {
      String glossHit = glossaryLookup(word);
      if (glossHit != null) { transCache.put(ck, glossHit); return glossHit; }
    }

    String result = null;
    for (String s : detect(word)) {
      if (s.equals(tgt)) { transCache.put(ck, NONE); return null; }
      String key = isCjkKey(s) ? word : word.toLowerCase(Locale.ROOT);
      boolean cjk = isCjkKey(s);
      D d = get(o, s, tgt);
      if (d != null) {
        String r = findFlex(d, key, cjk);
        if (r != null && scoreGloss(key, r, r.indexOf('·') >= 0) >= 0) { result = r; break; }
        continue;
      }
      D se = get(o, s, "en"), et = get(o, "en", tgt);
      if (se != null) {
        String en = findFlex(se, key, cjk);
        if (en != null && scoreGloss(key, en, en.indexOf('·') >= 0) >= 0) {
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


  /** Drop translation cache (call after glossary import). */
  public static synchronized void clearTransCache() {
    transCache.clear();
  }
}

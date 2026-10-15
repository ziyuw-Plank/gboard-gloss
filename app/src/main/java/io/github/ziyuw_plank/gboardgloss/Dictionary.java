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

  /** Value without trailing cost column. */
  static String find(D d, String key) {
    String v = findRaw(d, key);
    if (v == null) return null;
    int t = v.lastIndexOf('\t');
    return t >= 0 ? v.substring(0, t) : v;
  }

  /** Segmentation cost from the third column (lower = more frequent); -1 if absent. */
  static int costOf(String raw) {
    int t = raw.lastIndexOf('\t');
    if (t < 0) return -1;
    try { return Integer.parseInt(raw.substring(t + 1).trim()); } catch (Exception e) { return -1; }
  }

  static String findRaw(D d, String key) {
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

  // ---- v0.4 phrase engine: whole-entry → potential complement → DP segmentation + composition ----

  /** Sentence particles / aspect markers: dropped when composing a phrase gloss. */
  private static final String PARTICLES = "的了吗呢吧啊呀哦嘛啦么着过哈呗噢喔哇嗯";
  /** Closed-class words with a fixed short gloss (used only inside multi-segment phrases). */
  private static final HashMap<String, String> FUNC = new HashMap<>();
  static {
    String[] f = {
        "我","I", "你","you", "您","you", "他","he", "她","she", "它","it", "我们","we", "咱们","we",
        "你们","you", "他们","they", "她们","they", "大家","everyone", "自己","oneself",
        "很","very", "太","too", "也","also", "都","all", "还","still", "就","just", "又","again",
        "再","again", "真","really", "最","most", "更","more", "非常","very", "已经","already",
        "在","at", "是","is", "有","have", "要","want", "想","want", "会","can", "能","can",
        "可以","can", "这","this", "那","that", "这个","this", "那个","that", "一下","", "一点","a bit",
        "和","and", "跟","with", "给","for", "把","", "被","by", "对","right", "好","good",
        "去","go", "来","come", "到","arrive", "点","o'clock", "见","see", "走","go",
        "哪","where", "哪里","where", "哪儿","where", "什么","what", "谁","who", "怎么","how",
        "为什么","why", "多少","how many", "几","how many", "不错","not bad",
        "不会","can't", "不能","can't", "不要","don't", "不用","no need to", "不想","don't want",
        "没有","have no", "不是","isn't", "叫","be called", "个","", "本","", "张","", "件","", "条","", "位","", "种","", "杯","", "瓶","", "辆","", "双","", "台","",
    };
    for (int i = 0; i < f.length; i += 2) FUNC.put(f[i], f[i + 1]);
  }
  static boolean DEBUG = false;
  private static final Set<String> OBJ = new HashSet<>(Arrays.asList("me", "you", "him", "her", "us", "them"));
  private static final HashMap<String, String> BE = new HashMap<>();
  private static final HashMap<String, String> POSS = new HashMap<>();
  private static final Set<String> FUNC_VERBS = new HashSet<>(Arrays.asList(
      "去", "来", "走", "到", "见", "想", "要", "会", "能", "叫", "有", "是", "在"));
  private static final HashMap<String, String> INV = new HashMap<>();
  static {
    String[] v = {"I","am I", "you","are you", "he","is he", "she","is she", "it","is it", "we","are we", "they","are they"};
    for (int i = 0; i < v.length; i += 2) INV.put(v[i], v[i + 1]);
  }
  static {
    String[] p = {"我","my", "你","your", "您","your", "他","his", "她","her", "它","its",
        "我们","our", "咱们","our", "你们","your", "他们","their", "她们","their"};
    for (int i = 0; i < p.length; i += 2) POSS.put(p[i], p[i + 1]);
  }
  static {
    BE.put("I", "I'm"); BE.put("you", "you're"); BE.put("he", "he's"); BE.put("she", "she's");
    BE.put("it", "it's"); BE.put("we", "we're"); BE.put("they", "they're");
  }
  private static final String NEG = "不没别";
  private static final Set<String> SUBJ = new HashSet<>(Arrays.asList(
      "I", "you", "he", "she", "it", "we", "they", "everyone", "can", "want", "also", "already", "still", "just"));
  /** Resultative complements for X不Y when XY itself is not an entry. */
  private static final HashMap<Character, String> COMPL = new HashMap<>();
  static {
    COMPL.put('懂', "understand"); COMPL.put('见', "see"); COMPL.put('完', "finish");
    COMPL.put('清', "make out"); COMPL.put('起', "afford"); COMPL.put('动', "move");
  }

  /** Short display gloss of one dictionary value: first sense, maybe a short second. */
  static String displayGloss(String v, boolean allowSecond) {
    if (v == null) return null;
    String[] ss = v.split("; ");
    String a = stripTo(ss[0]);
    if (a == null) return null;
    if (a.length() > 20 && ss.length > 1 && isIdiomLike(ss)) { // long literal explanation: prefer the shortest reasonable sense
      for (int i = 1; i < ss.length && i < 4; i++) {
        String c = stripTo(ss[i]);
        if (c != null && c.length() >= 4 && c.length() < a.length()) a = c;
      }
    }
    if (allowSecond && ss.length > 1 && a.length() <= 10) {
      String b = stripTo(ss[1]);
      if (b != null && !b.equalsIgnoreCase(a) && a.length() + b.length() + 2 <= 22) a = a + "; " + b;
    }
    return fitGlossToken(a, 24);
  }

  /** Idiom entries: several long descriptive senses (no infinitive head). */
  static boolean isIdiomLike(String[] ss) {
    return !ss[0].startsWith("to ") || ss[0].split(" ").length > 5;
  }

  static String stripTo(String g) {
    if (g == null) return null;
    int slash = g.indexOf('/');
    if (slash >= 0) g = g.substring(0, slash);
    g = g.replaceAll("\\s*\\([^)]*\\)", "").trim();
    if (g.regionMatches(true, 0, "to ", 0, 3)) g = g.substring(3).trim();
    return g.isEmpty() ? null : g;
  }

  /** Short single-sense gloss used as a phrase segment (no "; "). */
  static String segGloss(D d, String w) { return segGloss(d, w, false); }

  static String segGloss(D d, String w, boolean preferVerb) {
    String f = FUNC.get(w);
    if (f != null) return f;
    String v = find(d, w);
    if (v == null) return null;
    String[] ss = v.split("; ");
    String pick = ss[0];
    if (preferVerb && !pick.startsWith("to ")) {
      for (String x : ss) if (x.startsWith("to ")) { pick = x; break; }
    }
    String g = shortSense(pick);
    if (g != null && g.length() <= 16) return g;
    for (String x : ss) { // first sense too long: take the next reasonably short one
      String c = shortSense(x);
      if (c != null && c.length() <= 16) return c;
    }
    return g != null && g.length() <= 22 ? g : null;
  }

  static String shortSense(String x) {
    String g = stripTo(x);
    if (g == null) return null;
    int comma = g.indexOf(", ");
    if (comma > 0) g = g.substring(0, comma);
    return g;
  }

  static boolean isParticle(String w) { return w.length() == 1 && PARTICLES.indexOf(w.charAt(0)) >= 0; }

  /** "not X" with light grammar: not+be/have handled, "not not" avoided. */
  static String negate(String g, char neg) {
    if (g == null || g.isEmpty()) return null;
    String lg = g.toLowerCase(Locale.ROOT);
    if (lg.startsWith("not ") || lg.startsWith("no ") || lg.startsWith("un") && false) return g;
    if (neg == '别') return "don't " + g;
    if (lg.startsWith("be ") && neg != '没') return "not " + g.substring(3);
    if (neg == '没') {
      if (lg.equals("have")) return "not have";
      if (lg.equals("time") || lg.equals("money") || lg.equals("problem") || lg.equals("way")
          || lg.equals("matter") || lg.equals("meaning") || lg.equals("method")) return "no " + g;
      return "not " + g;
    }
    return "not " + g;
  }

  /** Whole-entry match (incl. negation of a whole entry). */
  static String findWhole(D d, String key, boolean cjk) {
    String r = find(d, key);
    if (r != null) return displayGloss(r, true);
    if (!cjk || key.length() < 2) return null;
    char c0 = key.charAt(0);
    if (NEG.indexOf(c0) >= 0 && key.length() >= 2) {
      String rest = key.substring(1);
      String base = find(d, rest);
      if (base != null) return negate(segGloss(d, rest, true), c0);
    }
    return null;
  }

  /** X不Y potential complement (看不懂, 找不到): "can't " + gloss(XY). */
  static String potential(D d, String key) {
    int i = key.indexOf('不');
    if (key.length() != 3 || i != 1) return null;
    String xy = "" + key.charAt(0) + key.charAt(2);
    String v = find(d, xy);
    if (v == null) {
      String c = COMPL.get(key.charAt(2));
      return c == null || find(d, key.substring(0, 1)) == null ? null : "can't " + c;
    }
    String g = stripTo(v.split("; ")[0]);
    if (g == null || g.length() > 18) return null;
    return "can't " + g;
  }

  /** DP segmentation (min frequency cost) then grammatical composition; null if not confident. */
  static String segmentCompose(D d, String key) {
    int n = key.length();
    if (n < 2 || n > 12) return null;
    final int INF = Integer.MAX_VALUE / 4, SEG = 40;
    int[] best = new int[n + 1];
    int[] back = new int[n + 1];
    Arrays.fill(best, INF);
    best[0] = 0;
    for (int i = 0; i < n; i++) {
      if (best[i] >= INF) continue;
      for (int j = Math.min(n, i + 8); j > i; j--) {
        if (i == 0 && j == n) continue; // whole key already tried
        String w = key.substring(i, j);
        int c;
        if (isParticle(w)) c = 15;
        else {
          String raw = findRaw(d, w);
          String gl = glossary.get(w);
          if (raw == null && gl == null && !FUNC.containsKey(w)) continue;
          int fc = raw == null ? 60 : costOf(raw);
          if (fc < 0) fc = 120;
          c = fc + SEG + (w.length() == 1 && !FUNC.containsKey(w) ? 40 : 0);
        }
        if (best[i] + c < best[j]) { best[j] = best[i] + c; back[j] = i; }
      }
    }
    if (best[n] >= INF) return null;
    ArrayList<String> segs = new ArrayList<>();
    for (int j = n; j > 0; j = back[j]) segs.add(0, key.substring(back[j], j));

    if (DEBUG) System.err.println("segs " + segs);
    ArrayList<String> out = new ArrayList<>();
    int content = 0, weakSingles = 0;
    char pendingNeg = 0;
    for (int k = 0; k < segs.size(); k++) {
      String w = segs.get(k);
      if (isParticle(w)) continue;
      if (w.length() == 1 && NEG.indexOf(w.charAt(0)) >= 0) { pendingNeg = w.charAt(0); continue; }
      String prev = out.isEmpty() ? "" : out.get(out.size() - 1);
      String next = k + 1 < segs.size() ? segs.get(k + 1) : "";
      boolean nextIsVerb = false;
      if (!next.isEmpty() && !isParticle(next) && !FUNC.containsKey(next)) {
        String nv = find(d, next);
        nextIsVerb = nv != null && nv.startsWith("to ");
      }
      if (FUNC_VERBS.contains(next)) nextIsVerb = true;
      if (w.equals("很") && (next.equals("想") || nextIsVerb)) { out.add("really"); continue; }
      // X不X / X没X question form inside a phrase → just X
      if (w.length() == 3 && w.charAt(0) == w.charAt(2) && (w.charAt(1) == '不' || w.charAt(1) == '没'))
        w = w.substring(0, 1);
      if (w.equals("在") && nextIsVerb) continue;
      if (w.equals("想") && pendingNeg == '没') { out.add("didn't think"); pendingNeg = 0; continue; }          // progressive 在 + V
      if (w.equals("没有") && nextIsVerb) { pendingNeg = '没'; continue; }
      if (POSS.containsKey(w) && next.equals("的")) { out.add(POSS.get(w)); continue; }
      boolean subj = pendingNeg != 0;
      String g = glossary.get(w);
      if (g == null) g = segGloss(d, w, subj);
      if (g == null) return null;
      if (w.length() == 1 && !FUNC.containsKey(w)) weakSingles++;
      if (!FUNC.containsKey(w)) content++;
      if (g.isEmpty()) continue;
      if (g.endsWith("?")) g = g.substring(0, g.length() - 1).trim();
      if (!out.isEmpty()) { // object position pronouns
        if (g.equals("I")) g = "me"; else if (g.equals("he")) g = "him"; else if (g.equals("she")) g = "her";
        else if (g.equals("we")) g = "us"; else if (g.equals("they")) g = "them";
        if (prev.equals("want") && OBJ.contains(g)) out.set(out.size() - 1, "miss");
      }
      boolean wNext = next.equals("谁") || next.equals("什么") || next.equals("哪") || next.equals("哪里") || next.equals("哪儿");
      if ((g.equals("is") || g.equals("very")) && pendingNeg == 0 && BE.containsKey(prev) && !wNext) {
        out.set(out.size() - 1, BE.get(prev));
        if (g.equals("is")) continue;
      }
      if (g.startsWith("be ") && pendingNeg == 0 && BE.containsKey(prev)) {
        out.set(out.size() - 1, BE.get(prev));
        g = g.substring(3);
      }
      if (!out.isEmpty() && g.startsWith(prev + " ")) out.remove(out.size() - 1); // 去+上班 "go go to work"
      if (pendingNeg != 0) {
        boolean isVerb = FUNC_VERBS.contains(w);
        if (!isVerb) { String wv = find(d, w); isVerb = wv != null && wv.startsWith("to "); }
        if (pendingNeg == '没' && isVerb && !g.startsWith("not ")) g = "didn't " + g;
        else g = negate(g, pendingNeg);
        pendingNeg = 0;
      }
      out.add(g);
    }
    if (pendingNeg != 0) out.add("not");
    if (out.isEmpty()) return null;
    boolean anyFunc = false;
    for (String w : segs) if (FUNC.containsKey(w) || isParticle(w)) anyFunc = true;
    if (!anyFunc && content >= 3) return null;                 // opaque compound term: don't stitch
    if (n == 2 && weakSingles == 2) return null;               // two bare characters: too ambiguous
    if (content == 0 && out.size() > 4) return null;
    if (content > 4 || weakSingles > 2 || (weakSingles == 2 && content > 3)) return null;
    if (key.endsWith("吧") && !out.isEmpty() && out.get(0).equals("we")) out.set(0, "let's");
    // wh-word at the end of a question → front ("you go where" → "where you go")
    String last = out.get(out.size() - 1);
    if (out.size() >= 2 && (last.equals("where") || last.equals("what") || last.equals("who"))) {
      out.remove(out.size() - 1);
      if (out.size() == 2 && out.get(1).equals("is") && INV.containsKey(out.get(0))) {
        String subj = out.get(0); out.clear(); out.add(INV.get(subj));
      }
      out.add(0, last);
    }
    String r = String.join(" ", out).replaceAll("\\s+", " ").trim()
        .replace("where at", "where").replace("where go", "where to go");
    r = Grammar.fix(r);
    if (r.length() > 30) return null;
    composedFlag = true;
    return r;
  }

  static boolean composedFlag;
  private static final Set<String> composed = new HashSet<>();

  /** True if the last offline result for (word,tgt) was stitched from segments (not a whole entry). */
  public static synchronized boolean wasComposed(String word, String tgt) {
    return word != null && composed.contains(word.trim() + "\t" + tgt);
  }

  static String findFlex(D d, String key, boolean cjk) {
    String r = findWhole(d, key, cjk);
    if (r != null || !cjk) return r;
    if (key.length() < 2) return null;
    r = potential(d, key);
    if (r != null) return r;
    return segmentCompose(d, key);
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
    composedFlag = false;
    List<String> srcs = detect(word);
    if (srcs.size() == 2 && "zh".equals(srcs.get(0)) && "en".equals(tgt)) {
      D z = get(o, "zh", "en"), j = get(o, "ja", "en");
      if (z != null && find(z, word) == null && j != null) {
        String jw = find(j, word);
        if (jw != null) { result = displayGloss(jw, false); transCache.put(ck, result); return result; }
      }
    }
    for (String s : srcs) {
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

    if (composedFlag && result != null) composed.add(ck);
    if (result != null && !composedFlag && !isCjkKey(srcs.isEmpty() ? "" : srcs.get(0)) && word.indexOf(' ') > 0) composed.add(ck);
    if (composed.size() > 4096) composed.clear();
    transCache.put(ck, result == null ? NONE : result);
    return result;
  }


  /** Drop translation cache (call after glossary import). */
  public static synchronized void clearTransCache() {
    transCache.clear();
  }
}

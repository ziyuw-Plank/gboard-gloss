package io.github.ziyuw_plank.gboardgloss;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Light English post-fixes for dictionary-composed phrase glosses (offline fallback only). */
public final class Grammar {
  static final Set<String> VERBS = new HashSet<>(Arrays.asList(
      "know", "go", "eat", "see", "do", "be", "have", "buy", "sleep", "learn", "ask", "say", "tell",
      "make", "take", "come", "get", "find", "understand", "study", "play", "work", "read", "write",
      "watch", "listen", "help", "try", "leave", "rest", "change", "use", "sing", "travel", "drink",
      "meet", "call", "visit", "live", "stay", "wait", "talk", "speak", "think", "give", "look",
      "send", "open", "close", "start", "stop", "finish", "return", "sit", "stand", "walk", "run",
      "swim", "drive", "cook", "wash", "clean", "pay", "sell", "borrow", "lend", "marry", "die",
      "cry", "laugh", "rest", "relax", "shop", "dance", "draw", "fly", "move", "join", "win", "lose",
      "chat", "explain", "ask", "answer", "remember", "forget", "believe", "check", "fix", "share",
      "become", "keep", "let", "put", "bring", "show", "hear", "feel", "love", "like", "hate",
      "follow", "choose", "decide", "prepare", "practice", "improve", "earn", "save", "spend"));
  static final Pattern TO_INF = Pattern.compile(
      "\\b(want|wants|wanted|need|needs|hope|hopes|plan|plans|try|tries|decide|decided|"
      + "prepare|forget|remember|learn|begin|start|like|love|hate|going)\\s+([a-z]+)\\b");
  static final Pattern MODAL_TO = Pattern.compile("\\b(can|can't|will|won't|must|should|could|would|may|might|don't|didn't|doesn't) to\\b");
  static final Pattern THIRD = Pattern.compile("^(he|she|it) ([a-z]+)\\b");

  static final java.util.Map<String, String> COMMON = new java.util.HashMap<>();
  static final java.util.Map<String, String> PAST = new java.util.HashMap<>();
  static {
    String[] c = {"comprehend", "understand", "apprehend", "understand", "coin", "money", "consume", "eat",
        "return home", "go home", "purchase", "buy", "inquire", "ask", "reside", "live"};
    for (int i = 0; i < c.length; i += 2) COMMON.put(c[i], c[i + 1]);
    String[] p = {"go", "went", "eat", "ate", "see", "saw", "do", "did", "have", "had", "buy", "bought",
        "sleep", "slept", "say", "said", "tell", "told", "make", "made", "take", "took", "come", "came",
        "get", "got", "find", "found", "understand", "understood", "read", "read", "write", "wrote",
        "leave", "left", "drink", "drank", "meet", "met", "speak", "spoke", "think", "thought", "give", "gave",
        "send", "sent", "sit", "sat", "stand", "stood", "run", "ran", "swim", "swam", "drive", "drove",
        "pay", "paid", "sell", "sold", "lend", "lent", "draw", "drew", "fly", "flew", "win", "won", "lose", "lost",
        "become", "became", "keep", "kept", "put", "put", "bring", "brought", "hear", "heard", "feel", "felt",
        "forget", "forgot", "choose", "chose", "spend", "spent", "know", "knew", "sing", "sang", "be", "was"};
    for (int i = 0; i < p.length; i += 2) PAST.put(p[i], p[i + 1]);
  }
  static final Pattern SUBJ_NOT = Pattern.compile("^(I|you|we|they|he|she|it) not ([a-z]+)\\b");
  static final Pattern MODAL_Q = Pattern.compile("^(I|you|we|they|he|she|it) (can|will|should|must|could|would|may|can't|won't) (.+)$");
  static final Pattern PRES_Q = Pattern.compile("^(I|you|we|they|he|she|it) (?:don't |doesn't )?([a-z]+)(.*)$");
  static final Pattern PAST_S = Pattern.compile("^(I|you|we|they|he|she|it) ([a-z]+)\\b");
  static boolean is3(String subj) { return subj.equals("he") || subj.equals("she") || subj.equals("it"); }
  static String base(String v) { return v.replaceAll("(?<=[a-z])(ies)$", "y").replaceAll("(?<=(s|sh|ch|x|z))es$", "").replaceAll("(?<=[^s])s$", ""); }

  /**
   * Sentence-level fix using the source key for aspect/mood cues (了 → past, 吗 → yes/no question).
   * Returns null when the result is not confidently grammatical (blank beats garbage).
   */
  public static String fix(String s, String srcKey) {
    String r = fix(s);
    if (r == null || r.isEmpty()) return r;
    for (java.util.Map.Entry<String, String> e : COMMON.entrySet())
      r = r.replaceAll("\\b" + e.getKey() + "\\b", e.getValue());
    // 不/没 leftovers: "<subj> not V" → do-support with agreement
    Matcher n = SUBJ_NOT.matcher(r);
    if (n.find()) {
      String subj = n.group(1), v = n.group(2);
      if (v.equals("be") || v.equals("is") || v.equals("am") || v.equals("are")) {
        r = subj + " " + (subj.equals("I") ? "am" : is3(subj) ? "is" : "are") + " not" + r.substring(n.end());
      } else if (VERBS.contains(v) || VERBS.contains(base(v)) || v.matches("want|need|hope|plan")) {
        r = subj + (is3(subj) ? " doesn't " : " don't ") + base(v) + r.substring(n.end());
      } else return null;
    }
    // do-support takes the base form ("he doesn't likes")
    Matcher ds = Pattern.compile("\\b(don't|doesn't|didn't) ([a-z]+)\\b").matcher(r);
    StringBuffer sb = new StringBuffer();
    while (ds.find()) ds.appendReplacement(sb, ds.group(1) + " " + (VERBS.contains(base(ds.group(2))) ? base(ds.group(2)) : ds.group(2)));
    ds.appendTail(sb); r = sb.toString();
    String k = srcKey == null ? "" : srcKey.trim();
    boolean q = k.endsWith("吗") || k.endsWith("嗎") || k.endsWith("？") || k.endsWith("?");
    boolean past = !q && (k.endsWith("了") || k.contains("过")) && !k.contains("不") && !k.contains("没") && !k.contains("沒");
    if (past) {
      Matcher p = PAST_S.matcher(r);
      if (p.find() && !r.contains("not") && !r.contains("'t")) {
        String v = base(p.group(2));
        String pv = PAST.containsKey(v) ? PAST.get(v) : VERBS.contains(v) ? (v.endsWith("e") ? v + "d" : v.matches(".*[^aeiou]y") ? v.substring(0, v.length() - 1) + "ied" : v + "ed") : null;
        if (pv != null && !v.matches("can|will|must|should")) r = p.group(1) + " " + pv + r.substring(p.end());
      }
    }
    if (q) {
      Matcher m = MODAL_Q.matcher(r);
      if (m.find()) r = cap(m.group(2)) + " " + m.group(1) + " " + m.group(3);
      else {
        m = PRES_Q.matcher(r);
        if (!m.find()) return null;
        String subj = m.group(1), v = m.group(2);
        if (v.matches("am|is|are")) r = cap(v) + " " + subj + m.group(3);
        else if (VERBS.contains(base(v)) || v.matches("want|need|wants|needs")) {
          boolean neg = r.startsWith(subj + " don't ") || r.startsWith(subj + " doesn't ");
          r = (is3(subj) ? "Does" : "Do") + (neg ? "n't " : " ") + (subj.equals("I") ? "I" : subj) + " " + base(v) + m.group(3);
        } else return null;
      }
      r = r.trim();
      if (r.matches("(Do|Does|Don't|Doesn't) \\w+ (like|love|want|need|know|understand|have)")) r += " it";
      r += "?";
    }
    if (SUBJ_NOT.matcher(r).find() || r.matches(".*\\b(not|don't|doesn't|didn't)$")) return null;
    return r;
  }

  static String cap(String w) { return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1); }

  public static String fix(String s) {
    if (s == null || s.isEmpty()) return s;
    String r = s;
    Matcher m = TO_INF.matcher(r);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String head = m.group(1), v = m.group(2);
      boolean add = VERBS.contains(v);
      if ((head.equals("like") || head.equals("love") || head.equals("hate")) && !VERBS.contains(v)) add = false;
      m.appendReplacement(sb, Matcher.quoteReplacement(add ? head + " to " + v : m.group(0)));
    }
    m.appendTail(sb);
    r = sb.toString();
    r = MODAL_TO.matcher(r).replaceAll("$1");
    Matcher t = THIRD.matcher(r);
    if (t.find() && (VERBS.contains(t.group(2)) || t.group(2).matches("want|need|hope|plan"))) {
      r = t.group(1) + " " + third(t.group(2)) + r.substring(t.end());
    }
    return r.replaceAll("\\bto to\\b", "to").replaceAll("\\s+", " ").trim();
  }

  static String third(String v) {
    switch (v) {
      case "be": return "is";
      case "have": return "has";
      case "do": return "does";
      case "go": return "goes";
    }
    if (v.matches(".*(s|sh|ch|x|z)")) return v + "es";
    if (v.matches(".*[^aeiou]y")) return v.substring(0, v.length() - 1) + "ies";
    return v + "s";
  }
}

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

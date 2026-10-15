package com.zyw.gloss;
import android.content.Context; import java.io.*; import java.util.HashMap;
/** Pluggable offline dictionary: assets/dict_<lang>.tsv, lines "汉字\tgloss". */
public final class Dictionary {
  private static final HashMap<String, HashMap<String,String>> cache = new HashMap<>();
  public static synchronized HashMap<String,String> load(Context moduleCtx, String lang) {
    HashMap<String,String> m = cache.get(lang); if (m != null) return m; m = new HashMap<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(moduleCtx.getAssets().open("dict_"+lang+".tsv"), "UTF-8"))) {
      String l; while ((l = r.readLine()) != null) { int t = l.indexOf('\t'); if (t > 0 && !m.containsKey(l.substring(0,t))) m.put(l.substring(0,t), l.substring(t+1)); }
    } catch (IOException ignored) {}
    cache.put(lang, m); return m;
  }
  public static int count(Context c, String lang) { return load(c, lang).size(); }
  public static String lookup(Context c, String lang, String word) { return word == null ? null : load(c, lang).get(word); }
}

package io.github.ziyuw_plank.gboardgloss;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional online gloss when offline miss. Never touches the draw path.
 * Uses MyMemory free endpoint; results cached in-memory (LRU). Default off.
 */
public final class OnlineTranslate {
  public interface Callback { void onResult(String word, String gloss); }

  private static final int MAX_CACHE = 512;
  private static final int MAX_INFLIGHT = 2;
  private static final long MIN_GAP_MS = 350;

  private static final LinkedHashMap<String, String> cache =
      new LinkedHashMap<String, String>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) {
          return size() > MAX_CACHE;
        }
      };
  private static final String NONE = "\0";

  private static HandlerThread thread;
  private static Handler bg;
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static final AtomicInteger inflight = new AtomicInteger(0);
  private static long lastStart;

  private static synchronized void ensure() {
    if (thread != null) return;
    thread = new HandlerThread("gloss-online", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    thread.start();
    bg = new Handler(thread.getLooper());
  }

  /** Memory cache hit; null = unknown, "" = known miss. */
  public static synchronized String peek(String word, String tgt) {
    if (word == null) return null;
    String v = cache.get(word.trim() + "\t" + tgt);
    if (v == null) return null;
    return v == NONE ? "" : v;
  }

  public static void request(String word, String src, String tgt, Callback cb) {
    if (word == null || tgt == null || cb == null) return;
    word = word.trim();
    if (word.isEmpty() || word.length() > 40) return;
    if (src == null || src.isEmpty() || src.equals(tgt)) return;

    final String w0 = word;
    final String t0 = tgt;
    synchronized (OnlineTranslate.class) {
      String hit = cache.get(w0 + "\t" + t0);
      if (hit != null) {
        final String out = hit == NONE ? null : hit;
        main.post(() -> cb.onResult(w0, out));
        return;
      }
    }
    if (inflight.get() >= MAX_INFLIGHT) return;
    ensure();
    final String w = word, s = src, t = tgt;
    bg.post(() -> {
      long wait = MIN_GAP_MS - (System.currentTimeMillis() - lastStart);
      if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
      if (inflight.get() >= MAX_INFLIGHT) return;
      inflight.incrementAndGet();
      lastStart = System.currentTimeMillis();
      String gloss = null;
      try {
        gloss = fetchMyMemory(w, s, t);
        gloss = Dictionary.fitGlossToken(gloss, 22);
      } catch (Throwable ignored) {
      } finally {
        inflight.decrementAndGet();
      }
      synchronized (OnlineTranslate.class) {
        cache.put(w + "\t" + t, gloss == null ? NONE : gloss);
      }
      final String g = gloss;
      main.post(() -> cb.onResult(w, g));
    });
  }

  static String fetchMyMemory(String word, String src, String tgt) throws Exception {
    String q = URLEncoder.encode(word, "UTF-8");
    String pair = URLEncoder.encode(src + "|" + tgt, "UTF-8");
    URL url = new URL("https://api.mymemory.translated.net/get?q=" + q + "&langpair=" + pair);
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setConnectTimeout(2500);
    c.setReadTimeout(3500);
    c.setRequestProperty("User-Agent", "GboardGloss/0.3");
    c.setUseCaches(true);
    try {
      int code = c.getResponseCode();
      if (code != 200) return null;
      StringBuilder sb = new StringBuilder();
      try (BufferedReader br = new BufferedReader(
          new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8), 2048)) {
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
      }
      JSONObject json = new JSONObject(sb.toString());
      if (json.optInt("responseStatus", 0) != 200) return null;
      JSONObject data = json.optJSONObject("responseData");
      if (data == null) return null;
      String text = data.optString("translatedText", "").trim();
      if (text.isEmpty()) return null;
      // MyMemory sometimes echoes the query on miss
      if (text.equalsIgnoreCase(word)) return null;
      return text;
    } finally {
      c.disconnect();
    }
  }
}

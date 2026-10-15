package io.github.ziyuw_plank.gboardgloss;

import android.content.ContentProviderClient;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.List;

/** Gboard-side client: debounced, cached, async NMT requests to {@link MtProvider}. Never blocks drawing. */
public final class MtClient {
  static final long QUIET_MS = 150;
  static final PhraseCache mem = new PhraseCache(256, null);
  static final PickQueue<Req> queue = new PickQueue<>(3, QUIET_MS);
  static final Handler main = new Handler(Looper.getMainLooper());
  static Handler bg;
  static Context ctx;
  static long backoffUntil;
  static int logs = 20;

  static final class Req {
    final WeakReference<View> view; final String text, src, tgt;
    Req(View v, String text, String src, String tgt) { view = new WeakReference<>(v); this.text = text; this.src = src; this.tgt = tgt; }
  }

  static synchronized void ensure() {
    if (bg != null) return;
    HandlerThread t = new HandlerThread("gloss-mt", android.os.Process.THREAD_PRIORITY_BACKGROUND);
    t.start();
    bg = new Handler(t.getLooper());
  }

  public static String peek(String text, String tgt) { return mem.get(text, tgt); }

  public static void request(Context c, View v, String text, String src, String tgt) {
    if (ctx == null && c != null) ctx = c.getApplicationContext();
    if (ctx == null || SystemClock.uptimeMillis() < backoffUntil) return;
    ensure();
    queue.add(tgt + "\t" + text, new Req(v, text, src, tgt), SystemClock.uptimeMillis());
    bg.removeCallbacks(FLUSH);
    bg.postDelayed(FLUSH, QUIET_MS);
  }

  static final Runnable FLUSH = () -> {
    List<Req> rs = queue.drain(SystemClock.uptimeMillis());
    for (Req r : rs) run(r);
  };

  static void run(Req r) {
    View v0 = r.view.get();
    if (v0 == null || !r.text.equals(v0.getTag(R.id.gloss_word))) return; // superseded
    String hit = mem.get(r.text, r.tgt);
    String res = hit;
    if (res == null) {
      Bundle b = null;
      try (ContentProviderClient cl = ctx.getContentResolver()
          .acquireUnstableContentProviderClient(Uri.parse("content://" + MtProvider.AUTH))) {
        if (cl == null) { note("provider not visible (open GboardGloss once / enable System Framework scope)"); backoffUntil = SystemClock.uptimeMillis() + 60_000; return; }
        Bundle ex = new Bundle(); ex.putString("src", r.src); ex.putString("tgt", r.tgt);
        b = cl.call(MtProvider.M_TRANSLATE, r.text, ex);
      } catch (Throwable t) { note("call " + t); backoffUntil = SystemClock.uptimeMillis() + 10_000; return; }
      String s = b == null ? "null" : b.getString("s");
      if (!"ok".equals(s)) {
        note("mt '" + r.text + "' status=" + s);
        if ("nomodel".equals(s) || "off".equals(s) || "denied".equals(s)) backoffUntil = SystemClock.uptimeMillis() + 30_000;
        return;
      }
      res = b.getString("r");
      mem.put(r.text, r.tgt, res);
      note("mt '" + r.text + "' → " + res + " (" + b.getLong("ms") + "ms)");
    }
    final String g = res;
    main.post(() -> {
      View v = r.view.get();
      if (v == null || !r.text.equals(v.getTag(R.id.gloss_word))) return;
      v.setTag(R.id.gloss_text, Dictionary.fitGlossToken(g, 40));
      v.invalidate();
    });
  }

  static void note(String s) { if (logs > 0) { logs--; Hook.log(s); } }
}

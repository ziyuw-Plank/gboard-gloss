package io.github.ziyuw_plank.gboardgloss;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * On-device NMT (ML Kit) served to the Gboard hook over ContentProvider.call().
 * Only Gboard and this app may call. Never downloads models here (Settings does that).
 */
public class MtProvider extends ContentProvider {
  public static final String AUTH = "io.github.ziyuw_plank.gboardgloss.mt";
  public static final String M_TRANSLATE = "mt";
  static PhraseCache cache;
  static final HashMap<String, Translator> translators = new HashMap<>();
  static Set<String> downloaded = new HashSet<>();
  static long downloadedAt;

  static final HashMap<String, com.google.android.gms.tasks.Task<String>> inflight = new HashMap<>();
  static final java.util.concurrent.ExecutorService BG = Executors.newSingleThreadExecutor();
  static final Set<String> downloading = java.util.Collections.synchronizedSet(new HashSet<>());
  static long lastStatusAt;

  @Override public boolean onCreate() { return true; }

  /** Last provider status, shown in Settings so silent failures are visible. Throttled writes. */
  static void status(SharedPreferences sp, String s) {
    long now = System.currentTimeMillis();
    if (now - lastStatusAt < 5_000 && s.equals("ok")) return;
    lastStatusAt = now;
    sp.edit().putString(Prefs.MT_STATUS, s).putLong(Prefs.MT_STATUS_AT, now).apply();
  }

  /** Missing model: start a download (Wi-Fi only unless the user allowed mobile data). One attempt per language per process. */
  static void autoDownload(SharedPreferences sp, String... langs) {
    com.google.mlkit.common.model.DownloadConditions.Builder b = new com.google.mlkit.common.model.DownloadConditions.Builder();
    if (sp.getBoolean(Prefs.WIFI_ONLY, true)) b.requireWifi();
    for (String l : langs) {
      if (TranslateLanguage.ENGLISH.equals(l) || !downloading.add(l)) continue;
      RemoteModelManager.getInstance().download(new TranslateRemoteModel.Builder(l).build(), b.build())
          .addOnCompleteListener(BG, t -> { downloadedAt = 0; if (!t.isSuccessful()) downloading.remove(l); });
    }
  }

  synchronized PhraseCache cache() {
    if (cache == null) cache = new PhraseCache(3000, new File(getContext().getFilesDir(), "mt_cache.tsv"));
    return cache;
  }

  boolean callerAllowed() {
    int uid = Binder.getCallingUid();
    if (uid == Process.myUid()) return true;
    String[] p = getContext().getPackageManager().getPackagesForUid(uid);
    if (p != null) for (String s : p) if (Hook.GBOARD.equals(s)) return true;
    return false;
  }

  static synchronized Set<String> downloadedModels(boolean force) {
    if (!force && System.currentTimeMillis() - downloadedAt < 30_000) return downloaded;
    try {
      Set<TranslateRemoteModel> s = Tasks.await(RemoteModelManager.getInstance()
          .getDownloadedModels(TranslateRemoteModel.class), 3, TimeUnit.SECONDS);
      HashSet<String> out = new HashSet<>();
      for (TranslateRemoteModel m : s) out.add(m.getLanguage());
      out.add(TranslateLanguage.ENGLISH);
      downloaded = out;
      downloadedAt = System.currentTimeMillis();
    } catch (Exception ignored) {}
    return downloaded;
  }

  static synchronized Translator translator(String src, String tgt) {
    String k = src + ">" + tgt;
    Translator t = translators.get(k);
    if (t == null) {
      t = Translation.getClient(new TranslatorOptions.Builder()
          .setSourceLanguage(src).setTargetLanguage(tgt).build());
      translators.put(k, t);
    }
    return t;
  }

  @Override public Bundle call(String method, String arg, Bundle extras) {
    Bundle out = new Bundle();
    if (!M_TRANSLATE.equals(method) || arg == null || extras == null) { out.putString("s", "bad"); return out; }
    if (!callerAllowed()) { out.putString("s", "denied"); return out; }
    SharedPreferences sp = getContext().getSharedPreferences(Prefs.NAME, 0);
    if (!sp.getBoolean(Prefs.MT, true)) { out.putString("s", "off"); return out; }
    String src = TranslateLanguage.fromLanguageTag(extras.getString("src", ""));
    String tgt = TranslateLanguage.fromLanguageTag(extras.getString("tgt", ""));
    String text = arg.trim();
    if (src == null || tgt == null || src.equals(tgt) || text.isEmpty() || text.length() > 60) {
      out.putString("s", "bad"); return out;
    }
    PhraseCache c = cache();
    String hit = c.get(text, tgt);
    if (hit != null) { out.putString("s", "ok"); out.putString("r", hit); return out; }
    Set<String> have = downloadedModels(false);
    if (!have.contains(src) || !have.contains(tgt)) {
      autoDownload(sp, src, tgt);
      out.putString("s", downloading.isEmpty() ? "nomodel" : "downloading"); status(sp, "nomodel " + src + ">" + tgt); return out;
    }
    long t0 = System.nanoTime();
    com.google.android.gms.tasks.Task<String> task;
    String pk = tgt + "\t" + text;
    synchronized (inflight) {
      task = inflight.get(pk);
      if (task == null) {
        task = translator(src, tgt).translate(text);
        inflight.put(pk, task);
        final PhraseCache cc = c;
        task.addOnCompleteListener(BG, tk -> {           // finishes even after a timeout → next call hits cache
          synchronized (inflight) { inflight.remove(pk); }
          if (tk.isSuccessful() && tk.getResult() != null) {
            String rr = tk.getResult().trim();
            if (!rr.isEmpty() && !rr.equalsIgnoreCase(text) && cc.put(text, tgt, rr)) cc.flush();
          }
        });
      }
    }
    try {
      String r = Tasks.await(task, 1500, TimeUnit.MILLISECONDS);
      if (r != null) r = r.trim();
      if (r == null || r.isEmpty() || r.equalsIgnoreCase(text)) { out.putString("s", "empty"); return out; }
      if (c.put(text, tgt, r)) BG.execute(c::flush);
      out.putString("s", "ok");
      out.putString("r", r);
      out.putLong("ms", (System.nanoTime() - t0) / 1_000_000);
      status(sp, "ok");
    } catch (java.util.concurrent.TimeoutException e) {
      out.putString("s", "pending"); status(sp, "pending (model warming up)");
    } catch (Exception e) {
      out.putString("s", "fail"); status(sp, "fail " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    return out;
  }

  @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
  @Override public String getType(Uri u) { return null; }
  @Override public Uri insert(Uri u, ContentValues v) { return null; }
  @Override public int delete(Uri u, String s, String[] a) { return 0; }
  @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}

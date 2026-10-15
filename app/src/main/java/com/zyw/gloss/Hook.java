package com.zyw.gloss;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Additive gloss on Gboard candidate SoftKeyViews.
 * Hot path (dispatchDraw): instanceof SoftKeyView + tagged gloss string + Paint.drawText only.
 * Parent walk / dict lookup only when SoftKeyDef is bound (SoftKeyView.r).
 */
public class Hook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
  static final String GBOARD = "com.google.android.inputmethod.latin";
  static final String ME = "com.zyw.gloss";
  static final String SOFTKEY = "com.google.android.libraries.inputmethod.widgets.SoftKeyView";
  static final String SOFTKEY_DEF = "com.google.android.libraries.inputmethod.metadata.SoftKeyDef";

  static String modulePath;
  static XSharedPreferences prefs;
  static ZipFile zip;
  static Class<?> softKeyCls;
  static Field softKeyDefLabels; // SoftKeyDef.g CharSequence[]
  static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  static boolean paintInit, bannerShown, toastShown;
  static int logBudget = 40; // hierarchy / bind logs (first N, always)
  static long lastReload;
  static int lastSize = -1;
  static boolean lastNight;
  static final Set<Class<?>> hookedIme = new HashSet<>();

  static final Dictionary.Opener OPENER = Hook::openAsset;

  static void log(String s) { XposedBridge.log("[gloss] " + s); }

  static InputStream openAsset(String name) throws java.io.IOException {
    if (zip == null) zip = new ZipFile(modulePath);
    ZipEntry e = zip.getEntry("assets/" + name);
    if (e == null) throw new FileNotFoundException(name);
    return zip.getInputStream(e);
  }

  @Override public void initZygote(StartupParam p) { modulePath = p.modulePath; }

  @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
    if (lp.packageName.equals(ME)) {
      try {
        XposedHelpers.findAndHookMethod(SettingsActivity.class.getName(), lp.classLoader,
            "isActive", XC_MethodReplacement.returnConstant(true));
      } catch (Throwable t) { log("self hook " + t); }
      return;
    }
    if (!lp.packageName.equals(GBOARD) || !lp.isFirstApplication) return;

    log("loaded in " + lp.processName + " module=" + modulePath);
    prefs = new XSharedPreferences(ME, Prefs.NAME);
    prefs.makeWorldReadable();
    log("prefs readable=" + prefs.getFile().canRead()
        + " enabled=" + prefs.getBoolean(Prefs.ENABLED, true)
        + " lang=" + prefs.getString(Prefs.LANG, "en"));

    try {
      softKeyCls = XposedHelpers.findClass(SOFTKEY, lp.classLoader);
      log("found " + SOFTKEY);
    } catch (Throwable t) {
      log("SoftKeyView NOT found: " + t);
    }

    try {
      Class<?> defCls = XposedHelpers.findClass(SOFTKEY_DEF, lp.classLoader);
      softKeyDefLabels = defCls.getField("g");
      softKeyDefLabels.setAccessible(true);
    } catch (Throwable t) {
      log("SoftKeyDef.g field: " + t);
    }

    // Prefetch zh_en so first keystroke is cheap; also verifies aapt .tsv vs .tsv.gz path.
    try {
      int n = Dictionary.size(OPENER, "zh", "en");
      log("dict zh_en size=" + n + " via=" + Dictionary.loadedVia(OPENER, "zh", "en")
          + " sample这=" + Dictionary.translate(OPENER, "这", "en")
          + " sample这是=" + Dictionary.translate(OPENER, "这是", "en"));
    } catch (Throwable t) { log("dict prefetch " + t); }

    try {
      XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) {
          try {
            if (toastShown || !prefs.getBoolean(Prefs.BANNER, true)) return;
            toastShown = true;
            Context c = (Context) p.thisObject;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
              try { Toast.makeText(c, "GboardGloss 已注入 ✓", Toast.LENGTH_LONG).show(); }
              catch (Throwable t) { log("toast " + t); }
            }, 1500);
          } catch (Throwable t) { log("app.onCreate " + t); }
        }
      });
      log("hook installed: Application.onCreate");
    } catch (Throwable t) { log("hook fail Application.onCreate " + t); }

    try {
      XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreate", new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) {
          try {
            log("IME onCreate: " + p.thisObject.getClass().getName());
            hookImeClass(p.thisObject.getClass());
          } catch (Throwable t) { log("ime.onCreate " + t); }
        }
      });
      log("hook installed: InputMethodService.onCreate");
    } catch (Throwable t) { log("hook fail IMS.onCreate " + t); }

    // Bind path: SoftKeyView.r(SoftKeyDef, long) — dict + tags only when label changes.
    if (softKeyCls != null) {
      try {
        XposedHelpers.findAndHookMethod(softKeyCls, "r",
            XposedHelpers.findClass(SOFTKEY_DEF, lp.classLoader), long.class,
            new XC_MethodHook() {
              @Override protected void afterHookedMethod(MethodHookParam p) {
                try { onSoftKeyBound((View) p.thisObject, p.args[0]); }
                catch (Throwable t) {
                  if (logBudget > 0) { logBudget--; log("bind err " + t); }
                }
              }
            });
        log("hook installed: SoftKeyView.r");
      } catch (Throwable t) { log("hook fail SoftKeyView.r " + t); }
    }

    // Draw path: ViewGroup.dispatchDraw, but exit in one instanceof check for non-SoftKeyView.
    // SoftKeyView does not override dispatchDraw; hooking ViewGroup is required.
    try {
      XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchDraw", Canvas.class, new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) {
          Object self = p.thisObject;
          if (softKeyCls == null || !softKeyCls.isInstance(self)) return;
          try { drawCachedGloss((View) self, (Canvas) p.args[0]); }
          catch (Throwable t) {
            if (logBudget > 0) { logBudget--; log("draw err " + t); }
          }
        }
      });
      log("hook installed: ViewGroup.dispatchDraw (SoftKeyView-filtered)");
    } catch (Throwable t) { log("hook fail dispatchDraw " + t); }
  }

  static void hookImeClass(Class<?> c) {
    if (!hookedIme.add(c)) return;
    XC_MethodHook h = new XC_MethodHook() {
      @Override protected void afterHookedMethod(MethodHookParam p) {
        try { showBanner((InputMethodService) p.thisObject); }
        catch (Throwable t) { log("banner " + t); }
      }
    };
    for (String m : new String[]{"onStartInputView", "onWindowShown"}) {
      for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
        boolean done = false;
        for (Method mm : k.getDeclaredMethods()) {
          if (mm.getName().equals(m)) { XposedBridge.hookMethod(mm, h); done = true; }
        }
        if (done) { log("hook installed: " + k.getName() + "." + m); break; }
      }
    }
  }

  static void showBanner(InputMethodService ims) {
    if (bannerShown || !prefs.getBoolean(Prefs.BANNER, true)) return;
    final View decor = ims.getWindow().getWindow().getDecorView();
    bannerShown = true;
    decor.post(() -> {
      try {
        final float d = decor.getResources().getDisplayMetrics().density;
        Drawable dr = new Drawable() {
          final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
          final Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
          int a = 255;
          boolean night = (decor.getResources().getConfiguration().uiMode
              & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
          {
            bg.setColor(night ? 0xF03C4043 : 0xF0E8F0FE);
            tx.setColor(night ? 0xFFE8EAED : 0xFF174EA6);
            tx.setTextSize(13 * d);
            tx.setTextAlign(Paint.Align.CENTER);
          }
          @Override public void draw(Canvas c) {
            Rect b = getBounds();
            String s = "✓ GboardGloss 已注入 · 目标 " + prefs.getString(Prefs.LANG, "en");
            float w = tx.measureText(s) + 28 * d, h = 28 * d;
            float cx = b.width() / 2f, y = b.height() - h - 4 * d;
            c.drawRoundRect(cx - w / 2, y, cx + w / 2, y + h, h / 2, h / 2, bg);
            c.drawText(s, cx, y + h / 2 - (tx.ascent() + tx.descent()) / 2, tx);
          }
          @Override public void setAlpha(int x) {
            a = x; bg.setAlpha(x * 240 / 255); tx.setAlpha(x); invalidateSelf();
          }
          @Override public void setColorFilter(ColorFilter f) {}
          @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        dr.setBounds(0, 0, decor.getWidth(), decor.getHeight());
        decor.getOverlay().add(dr);
        decor.invalidate();
        log("banner shown " + decor.getWidth() + "x" + decor.getHeight());
        android.animation.ObjectAnimator an =
            android.animation.ObjectAnimator.ofInt(dr, "alpha", 255, 0);
        an.setStartDelay(4500);
        an.setDuration(700);
        an.addUpdateListener(x -> decor.invalidate());
        an.addListener(new android.animation.AnimatorListenerAdapter() {
          @Override public void onAnimationEnd(android.animation.Animator x) {
            try { decor.getOverlay().remove(dr); } catch (Throwable ignored) {}
          }
        });
        an.start();
      } catch (Throwable t) { log("banner err " + t); }
    });
  }

  /** One-time (cached) hierarchy check — loosened for *Candidates*Holder* / Suggestion strip. */
  static boolean resolveIsCandidate(View v) {
    Object cached = v.getTag(R.id.gloss_is_candidate);
    if (cached instanceof Boolean) return (Boolean) cached;
    boolean yes = false;
    StringBuilder hier = logBudget > 0 ? new StringBuilder() : null;
    for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
      Class<?> k = p.getClass();
      String n = k.getName();
      if (hier != null) {
        if (hier.length() > 0) hier.append(" < ");
        hier.append(k.getSimpleName());
      }
      if (n.startsWith("android.") || n.startsWith("androidx.")) {
        // keep walking a bit for androidx RecyclerView wrappers
        if (n.contains("RecyclerView") || n.contains("ViewPager")) continue;
        break;
      }
      String simple = k.getSimpleName();
      if (containsCand(n) || containsCand(simple)) { yes = true; break; }
      // Soft keyboard host — not a candidate strip
      if (n.contains("SoftKeyboardView") && !containsCand(n)) { yes = false; break; }
    }
    v.setTag(R.id.gloss_is_candidate, yes);
    if (hier != null && logBudget > 0) {
      logBudget--;
      log("hier cand=" + yes + " " + hier);
    }
    return yes;
  }

  static boolean containsCand(String n) {
    return n.contains("Candidate") || n.contains("Suggestion")
        || n.contains("CandidatesHolder") || n.contains("candidate");
  }

  static String extractLabel(View softKey, Object softKeyDef) {
    try {
      if (softKeyDef != null && softKeyDefLabels != null) {
        Object arr = softKeyDefLabels.get(softKeyDef);
        if (arr instanceof CharSequence[]) {
          CharSequence[] cs = (CharSequence[]) arr;
          if (cs.length > 0 && cs[0] != null) {
            String s = cs[0].toString().trim();
            if (!s.isEmpty()) return s;
          }
        }
      }
    } catch (Throwable ignored) {}
    if (softKey instanceof ViewGroup) {
      StringBuilder sb = new StringBuilder();
      collectText((ViewGroup) softKey, sb);
      if (sb.length() > 0) return sb.toString().trim();
    }
    CharSequence cd = softKey.getContentDescription();
    return cd == null ? "" : cd.toString().trim();
  }

  static void collectText(View v, StringBuilder sb) {
    if (v.getVisibility() != View.VISIBLE) return;
    if (v instanceof TextView) {
      CharSequence t = ((TextView) v).getText();
      if (t != null && t.length() > 0 && sb.length() == 0) sb.append(t);
      return;
    }
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) collectText(g.getChildAt(i), sb);
    }
  }

  static void onSoftKeyBound(View v, Object softKeyDef) {
    long now = SystemClock.uptimeMillis();
    if (now - lastReload > 2000) {
      lastReload = now;
      prefs.reload();
    }
    if (!prefs.getBoolean(Prefs.ENABLED, true)) {
      clearGloss(v);
      return;
    }
    if (!resolveIsCandidate(v)) {
      // Keep FALSE tag; ensure no stale gloss on keyboard keys.
      if (v.getTag(R.id.gloss_text) != null) clearGloss(v);
      return;
    }
    String word = extractLabel(v, softKeyDef);
    if (word.isEmpty() || word.length() > 24) {
      clearGloss(v);
      return;
    }
    // Skip non-word chrome (single letter keys rarely appear in candidate strip; still ok)
    Object prev = v.getTag(R.id.gloss_word);
    if (word.equals(prev)) return; // label unchanged — no dict work

    v.setTag(R.id.gloss_word, word);
    String lang = prefs.getString(Prefs.LANG, "en");
    String gloss = Dictionary.translate(OPENER, word, lang);
    if (gloss != null && gloss.length() > 16) gloss = gloss.substring(0, 15) + "…";
    v.setTag(R.id.gloss_text, gloss); // null = no translation
    if (logBudget > 0 || prefs.getBoolean(Prefs.DEBUG, false)) {
      if (logBudget > 0) logBudget--;
      log("bind '" + word + "' → " + gloss);
    }
    v.invalidate(); // only this SoftKeyView
  }

  static void clearGloss(View v) {
    if (v.getTag(R.id.gloss_text) != null || v.getTag(R.id.gloss_word) != null) {
      v.setTag(R.id.gloss_word, null);
      v.setTag(R.id.gloss_text, null);
      v.invalidate();
    }
  }

  /** Hot path: no parent walk, no dict, no string alloc beyond drawText. */
  static void drawCachedGloss(View v, Canvas c) {
    Object g = v.getTag(R.id.gloss_text);
    if (!(g instanceof String)) return;
    String gloss = (String) g;
    if (gloss.isEmpty()) return;

    float sd = v.getResources().getDisplayMetrics().scaledDensity;
    int size = prefs.getInt(Prefs.SIZE, 11);
    if (size < 8) size = 8;
    boolean night = (v.getResources().getConfiguration().uiMode
        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    if (!paintInit || size != lastSize || night != lastNight) {
      paintInit = true;
      lastSize = size;
      lastNight = night;
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(size * sd);
      // High-contrast: amber on dark, Google Blue on light — never match key glyph color.
      paint.setColor(night ? 0xFFFFC107 : 0xFF1A73E8);
    }
    float x = v.getWidth() / 2f;
    float y = v.getHeight() - paint.descent() - 2f * sd;
    if (y < -paint.ascent()) y = v.getHeight() / 2f - (paint.ascent() + paint.descent()) / 2f;
    c.drawText(gloss, x, y, paint);
  }
}

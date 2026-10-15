package com.zyw.gloss;
import android.app.Application; import android.content.Context; import android.graphics.*; import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService; import android.os.*; import android.view.*; import android.widget.*;
import de.robv.android.xposed.*; import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.*; import java.util.*; import java.util.zip.ZipFile;
/**
 * Additive-only: draws gloss text on top of Gboard candidate keys after Gboard has drawn them (ViewGroup.dispatchDraw),
 * and a temporary injection banner via the IME window's ViewOverlay. Never changes data, text, ranking or layout.
 * Every callback is wrapped in try/catch.
 */
public class Hook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
  static final String GBOARD = "com.google.android.inputmethod.latin", ME = "com.zyw.gloss";
  static final String SOFTKEY = "com.google.android.libraries.inputmethod.widgets.SoftKeyView";
  static String modulePath; static XSharedPreferences prefs; static ZipFile zip;
  static Class<?> softKeyCls; static Paint paint; static boolean bannerShown, toastShown, firstMatch, firstDraw;
  static final Set<String> seen = new HashSet<>(); static long lastReload;
  static final Dictionary.Opener OPENER = n -> { if (zip == null) zip = new ZipFile(modulePath); return zip.getInputStream(zip.getEntry("assets/" + n)); };
  static void log(String s) { XposedBridge.log("[gloss] " + s); }
  @Override public void initZygote(StartupParam p) { modulePath = p.modulePath; }
  @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
    if (lp.packageName.equals(ME)) { try { XposedHelpers.findAndHookMethod(SettingsActivity.class.getName(), lp.classLoader, "isActive", XC_MethodReplacement.returnConstant(true)); } catch (Throwable t) { log("self hook " + t); } return; }
    if (!lp.packageName.equals(GBOARD) || !lp.isFirstApplication) return;
    log("loaded in " + lp.processName + " module=" + modulePath);
    prefs = new XSharedPreferences(ME, Prefs.NAME); prefs.makeWorldReadable();
    log("prefs readable=" + prefs.getFile().canRead() + " enabled=" + prefs.getBoolean(Prefs.ENABLED, true) + " lang=" + prefs.getString(Prefs.LANG, "en"));
    try { XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() { @Override protected void afterHookedMethod(MethodHookParam p) {
        try { if (toastShown || !prefs.getBoolean(Prefs.BANNER, true)) return; toastShown = true; Context c = (Context) p.thisObject;
          new Handler(Looper.getMainLooper()).postDelayed(() -> { try { Toast.makeText(c, "GboardGloss 已注入 ✓", Toast.LENGTH_LONG).show(); } catch (Throwable t) { log("toast " + t); } }, 1500);
        } catch (Throwable t) { log("app.onCreate " + t); } } }); log("hook installed: Application.onCreate"); } catch (Throwable t) { log("hook fail Application.onCreate " + t); }
    try { XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreate", new XC_MethodHook() { @Override protected void afterHookedMethod(MethodHookParam p) {
        try { log("IME onCreate: " + p.thisObject.getClass().getName()); hookImeClass(p.thisObject.getClass()); } catch (Throwable t) { log("ime.onCreate " + t); } } });
      log("hook installed: InputMethodService.onCreate"); } catch (Throwable t) { log("hook fail IMS.onCreate " + t); }
    try { softKeyCls = XposedHelpers.findClass(SOFTKEY, lp.classLoader); log("found " + SOFTKEY); } catch (Throwable t) { log("SoftKeyView NOT found: " + t); }
    try { XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchDraw", Canvas.class, new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) { try { onKeyDrawn((ViewGroup) p.thisObject, (Canvas) p.args[0]); } catch (Throwable t) { if (!firstDraw) { firstDraw = true; log("draw err " + t); } } } });
      log("hook installed: ViewGroup.dispatchDraw"); } catch (Throwable t) { log("hook fail dispatchDraw " + t); }
  }
  static final Set<Class<?>> hookedIme = new HashSet<>();
  static void hookImeClass(Class<?> c) {
    if (!hookedIme.add(c)) return;
    XC_MethodHook h = new XC_MethodHook() { @Override protected void afterHookedMethod(MethodHookParam p) { try { showBanner((InputMethodService) p.thisObject); } catch (Throwable t) { log("banner " + t); } } };
    for (String m : new String[]{"onStartInputView", "onWindowShown"}) for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
      boolean done = false; for (java.lang.reflect.Method mm : k.getDeclaredMethods()) if (mm.getName().equals(m)) { XposedBridge.hookMethod(mm, h); done = true; }
      if (done) { log("hook installed: " + k.getName() + "." + m); break; } }
  }
  static void showBanner(InputMethodService ims) {
    if (bannerShown || !prefs.getBoolean(Prefs.BANNER, true)) return;
    final View decor = ims.getWindow().getWindow().getDecorView(); bannerShown = true;
    decor.post(() -> { try {
      final float d = decor.getResources().getDisplayMetrics().density;
      Drawable dr = new Drawable() {
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG);
        int a = 255; boolean night = (decor.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        { bg.setColor(night ? 0xF03C4043 : 0xF0E8F0FE); tx.setColor(night ? 0xFFE8EAED : 0xFF174EA6); tx.setTextSize(13 * d); tx.setTextAlign(Paint.Align.CENTER); }
        @Override public void draw(Canvas c) { Rect b = getBounds(); String s = "✓ GboardGloss 已注入 · 目标 " + prefs.getString(Prefs.LANG, "en");
          float w = tx.measureText(s) + 28 * d, h = 28 * d, cx = b.width() / 2f, y = b.height() - h - 4 * d;
          c.drawRoundRect(cx - w / 2, y, cx + w / 2, y + h, h / 2, h / 2, bg); c.drawText(s, cx, y + h / 2 - (tx.ascent() + tx.descent()) / 2, tx); }
        @Override public void setAlpha(int x) { a = x; bg.setAlpha(x * 240 / 255); tx.setAlpha(x); invalidateSelf(); } @Override public void setColorFilter(ColorFilter f) {} @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; } };
      dr.setBounds(0, 0, decor.getWidth(), decor.getHeight()); decor.getOverlay().add(dr); decor.invalidate();
      log("banner shown on " + decor.getClass().getName() + " " + decor.getWidth() + "x" + decor.getHeight());
      android.animation.ObjectAnimator an = android.animation.ObjectAnimator.ofInt(dr, "alpha", 255, 0); an.setStartDelay(4500); an.setDuration(700);
      an.addUpdateListener(x -> decor.invalidate()); an.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator x) { try { decor.getOverlay().remove(dr); } catch (Throwable ignored) {} } }); an.start();
    } catch (Throwable t) { log("banner err " + t); } });
  }
  static boolean underCandidateHolder(View v) {
    for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) for (Class<?> k = p.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
      String n = k.getName(); if (n.startsWith("android.")) break; if (n.contains("Candidate") || n.contains("Suggestion")) return true; }
    return false;
  }
  static String keyText(ViewGroup g) {
    StringBuilder sb = new StringBuilder(); collect(g, sb);
    if (sb.length() == 0 && g.getContentDescription() != null) sb.append(g.getContentDescription()); return sb.toString().trim();
  }
  static void collect(View v, StringBuilder sb) {
    if (v.getVisibility() != View.VISIBLE) return;
    if (v instanceof TextView) { CharSequence t = ((TextView) v).getText(); if (t != null && t.length() > 0 && sb.length() == 0) sb.append(t); return; }
    if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), sb); }
  }
  static void onKeyDrawn(ViewGroup v, Canvas c) {
    if (softKeyCls == null || !softKeyCls.isInstance(v)) return;
    long now = SystemClock.uptimeMillis(); if (now - lastReload > 2000) { lastReload = now; prefs.reload(); }
    if (!prefs.getBoolean(Prefs.ENABLED, true)) return;
    if (!underCandidateHolder(v)) return;
    String w = keyText(v); if (w.isEmpty()) return;
    if (!firstMatch) { firstMatch = true; log("first candidate match: '" + w + "' in " + v.getParent().getClass().getName()); }
    if (prefs.getBoolean(Prefs.DEBUG, false) && seen.size() < 300 && seen.add(w)) log("candidate: " + w);
    String g = Dictionary.translate(OPENER, w, prefs.getString(Prefs.LANG, "en"));
    if (g == null) return; if (g.length() > 16) g = g.substring(0, 15) + "…";
    if (paint == null) { paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextAlign(Paint.Align.CENTER); }
    float sd = v.getResources().getDisplayMetrics().scaledDensity; paint.setTextSize(prefs.getInt(Prefs.SIZE, 10) * sd);
    int col = 0xFF5F6368; for (int i = 0; i < v.getChildCount(); i++) if (v.getChildAt(i) instanceof TextView) col = ((TextView) v.getChildAt(i)).getCurrentTextColor();
    paint.setColor(col); paint.setAlpha(190);
    c.drawText(g, v.getWidth() / 2f, v.getHeight() - paint.descent() - 1, paint);
  }
}

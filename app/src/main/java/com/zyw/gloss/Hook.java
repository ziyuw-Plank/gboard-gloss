package com.zyw.gloss;
import android.content.Context; import android.graphics.*; import android.view.View; import android.widget.TextView;
import de.robv.android.xposed.*; import de.robv.android.xposed.callbacks.XC_LoadPackage;
/**
 * Additive-only overlay. We never modify candidate objects, commit text, ranking or IME input:
 * we only draw an extra small gloss line on top of candidate views after Gboard's own onDraw.
 * Any exception is swallowed so Gboard keeps working if offsets change.
 */
public class Hook implements IXposedHookLoadPackage {
  static final String GBOARD = "com.google.android.inputmethod.latin";
  static XSharedPreferences prefs; static Context moduleCtx; static Paint paint;
  @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
    if (lp.packageName.equals("com.zyw.gloss")) { XposedHelpers.findAndHookMethod(SettingsActivity.class.getName(), lp.classLoader, "isActive", XC_MethodReplacement.returnConstant(true)); return; }
    if (!lp.packageName.equals(GBOARD) || !lp.isFirstApplication) return;
    prefs = new XSharedPreferences("com.zyw.gloss", Prefs.NAME);
    XposedHelpers.findAndHookMethod(TextView.class, "onDraw", Canvas.class, new XC_MethodHook() {
      @Override protected void afterHookedMethod(MethodHookParam p) {
        try {
          TextView v = (TextView) p.thisObject; if (!Targets.isCandidateView(v)) return;
          prefs.reload(); if (!prefs.getBoolean(Prefs.ENABLED, true)) return;
          if (moduleCtx == null) moduleCtx = v.getContext().createPackageContext("com.zyw.gloss", Context.CONTEXT_IGNORE_SECURITY);
          String g = Dictionary.lookup(moduleCtx, prefs.getString(Prefs.LANG, "en"), String.valueOf(v.getText()).trim());
          if (g == null) return;
          if (paint == null) { paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextAlign(Paint.Align.CENTER); }
          float d = v.getResources().getDisplayMetrics().scaledDensity;
          paint.setTextSize(prefs.getInt(Prefs.SIZE, 10) * d); paint.setColor(v.getCurrentTextColor()); paint.setAlpha(170);
          if (g.length() > 18) g = g.substring(0, 17) + "…";
          ((Canvas) p.args[0]).drawText(g, v.getWidth() / 2f, v.getHeight() - paint.descent() - 1, paint);
        } catch (Throwable ignored) {}
      }
    });
  }
}

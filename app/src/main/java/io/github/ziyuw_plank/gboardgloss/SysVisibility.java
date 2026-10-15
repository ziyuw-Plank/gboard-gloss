package io.github.ziyuw_plank.gboardgloss;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Optional (scope "System Framework"): lets Gboard — and only Gboard — see this module's
 * provider under Android 11+ package visibility. Everything else is untouched.
 */
final class SysVisibility {
  static final ConcurrentHashMap<Class<?>, Object> acc = new ConcurrentHashMap<>();
  static final Object NONE = new Object();

  static void install(ClassLoader cl) {
    int n = 0;
    for (String cn : new String[]{"com.android.server.pm.AppsFilterBase", "com.android.server.pm.AppsFilterImpl", "com.android.server.pm.AppsFilter"}) {
      try {
        Class<?> c = Class.forName(cn, false, cl);
        for (Method m : c.getDeclaredMethods()) {
          if (!m.getName().equals("shouldFilterApplication") || m.getReturnType() != boolean.class) continue;
          XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
              try {
                Object[] a = p.args; int target = -1;
                for (int i = a.length - 1; i >= 0; i--) if (Hook.ME.equals(pkg(a[i]))) { target = i; break; }
                if (target <= 0) return;
                for (int i = 0; i < target; i++) if (Hook.GBOARD.equals(pkg(a[i]))) { p.setResult(false); return; }
              } catch (Throwable ignored) {}
            }
          });
          n++;
        }
      } catch (Throwable ignored) {}
    }
    XposedBridge.log("[gloss] system visibility hooks=" + n);
  }

  static String pkg(Object o) {
    if (o == null || o instanceof Number || o instanceof Boolean) return null;
    Class<?> k = o.getClass();
    Object h = acc.get(k);
    if (h == null) {
      h = NONE;
      try { Method m = k.getMethod("getPackageName"); m.setAccessible(true); h = m; } catch (Throwable t) {
        for (Class<?> c = k; c != null && h == NONE; c = c.getSuperclass()) {
          try { Field f = c.getDeclaredField("name"); if (f.getType() == String.class) { f.setAccessible(true); h = f; } } catch (Throwable ignored) {}
        }
      }
      acc.put(k, h);
    }
    try {
      if (h instanceof Method) { Object r = ((Method) h).invoke(o); return r instanceof String ? (String) r : null; }
      if (h instanceof Field) return (String) ((Field) h).get(o);
    } catch (Throwable ignored) {}
    return null;
  }
}

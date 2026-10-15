package com.zyw.gloss;
import android.view.View; import android.view.ViewParent;
/**
 * Located by decompiling Gboard 18.4.1.985164140 (arm64). The holder/key view classes keep real names
 * (they are inflated from XML, so R8 cannot rename them); resource ids ARE obfuscated, so no id heuristics.
 * Candidate word = TextView inside a SoftKeyView whose ancestor is one of the *CandidatesHolderView classes.
 */
final class Targets {
  static final String SOFTKEY = "com.google.android.libraries.inputmethod.widgets.SoftKeyView";
  static boolean isHolder(String n) { return n.endsWith("CandidatesHolderView") || n.endsWith("CandidateListHolderView"); }
  static boolean isCandidateView(View v) {
    boolean inKey = false; int depth = 0;
    for (ViewParent p = v.getParent(); p instanceof View && depth < 8; p = p.getParent(), depth++) {
      Class<?> c = p.getClass();
      if (!inKey) for (Class<?> k = c; k != null; k = k.getSuperclass()) if (k.getName().equals(SOFTKEY)) { inKey = true; break; }
      if (inKey) for (Class<?> k = c; k != null; k = k.getSuperclass()) if (isHolder(k.getName())) return true;
    }
    return false;
  }
}

package com.zyw.gloss;
import android.app.Activity; import android.content.*; import android.os.Bundle; import android.widget.TextView;
import com.google.android.material.chip.*; import com.google.android.material.materialswitch.MaterialSwitch; import com.google.android.material.slider.Slider;
public class SettingsActivity extends Activity {
  static boolean isActive() { return false; } // hooked to true by module itself when LSPosed active
  @SuppressWarnings("deprecation") @Override protected void onCreate(Bundle b) {
    super.onCreate(b); setContentView(R.layout.settings);
    SharedPreferences p; try { p = getSharedPreferences(Prefs.NAME, MODE_WORLD_READABLE); } catch (SecurityException e) { p = getSharedPreferences(Prefs.NAME, 0); }
    final SharedPreferences sp = p;
    ((TextView)findViewById(R.id.status)).setText(isActive() ? "✅ 模块已激活（作用域：Gboard）" : "⚠️ 模块未激活：请在 LSPosed 中启用并勾选 Gboard，然后强行停止 Gboard");
    MaterialSwitch sw = findViewById(R.id.enable); sw.setChecked(sp.getBoolean(Prefs.ENABLED, true));
    sw.setOnCheckedChangeListener((v, c) -> sp.edit().putBoolean(Prefs.ENABLED, c).apply());
    ChipGroup g = findViewById(R.id.langs); String[] n = getResources().getStringArray(R.array.langs), c = getResources().getStringArray(R.array.lang_codes);
    String cur = sp.getString(Prefs.LANG, "en");
    for (int i = 0; i < n.length; i++) { Chip ch = new Chip(this); ch.setText(n[i]); ch.setCheckable(true); ch.setTag(c[i]); ch.setId(1000+i); g.addView(ch); if (c[i].equals(cur)) ch.setChecked(true); }
    g.setOnCheckedStateChangeListener((gr, ids) -> { if (!ids.isEmpty()) { String code=(String)findViewById(ids.get(0)).getTag(); sp.edit().putString(Prefs.LANG, code).apply(); dict(code);} });
    Slider s = findViewById(R.id.size); s.setValue(sp.getInt(Prefs.SIZE, 10)); s.addOnChangeListener((sl, v, u) -> sp.edit().putInt(Prefs.SIZE, (int)v).apply());
    dict(cur);
  }
  void dict(String code) { int n = Dictionary.count(this, code); ((TextView)findViewById(R.id.dict)).setText(n > 0 ? "词典 zh→"+code+"：已内置 "+n+" 条" : "词典 zh→"+code+"：未内置（可放入 assets/dict_"+code+".tsv）"); }
}

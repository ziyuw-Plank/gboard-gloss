package com.zyw.gloss;
import android.app.Activity; import android.content.*; import android.os.Bundle; import android.widget.TextView;
import com.google.android.material.chip.*; import com.google.android.material.materialswitch.MaterialSwitch; import com.google.android.material.slider.Slider;
public class SettingsActivity extends Activity {
  static boolean isActive() { return false; }
  SharedPreferences sp;
  void bind(int id, String key, boolean def) { MaterialSwitch s = findViewById(id); s.setChecked(sp.getBoolean(key, def)); s.setOnCheckedChangeListener((v, c) -> sp.edit().putBoolean(key, c).apply()); }
  @SuppressWarnings("deprecation") @Override protected void onCreate(Bundle b) {
    super.onCreate(b); setContentView(R.layout.settings);
    try { sp = getSharedPreferences(Prefs.NAME, MODE_WORLD_READABLE); } catch (SecurityException e) { sp = getSharedPreferences(Prefs.NAME, 0); }
    ((TextView)findViewById(R.id.status)).setText(isActive() ? "✅ 模块已激活（作用域：Gboard）\n修改设置后约 2 秒内生效" : "⚠️ 模块未激活：在 LSPosed 中启用本模块并勾选 Gboard，然后强行停止 Gboard");
    bind(R.id.enable, Prefs.ENABLED, true); bind(R.id.banner, Prefs.BANNER, true); bind(R.id.debug, Prefs.DEBUG, false);
    ChipGroup g = findViewById(R.id.langs); String[] n = getResources().getStringArray(R.array.langs); String cur = sp.getString(Prefs.LANG, "en");
    for (int i = 0; i < n.length; i++) { Chip ch = new Chip(this); ch.setText(n[i]); ch.setCheckable(true); ch.setTag(Dictionary.LANGS[i]); ch.setId(1000+i); g.addView(ch); if (Dictionary.LANGS[i].equals(cur)) ch.setChecked(true); }
    g.setOnCheckedStateChangeListener((gr, ids) -> { if (!ids.isEmpty()) { String code=(String)findViewById(ids.get(0)).getTag(); sp.edit().putString(Prefs.LANG, code).apply(); dict(code);} });
    Slider s = findViewById(R.id.size); s.setValue(sp.getInt(Prefs.SIZE, 10)); s.addOnChangeListener((sl, v, u) -> sp.edit().putInt(Prefs.SIZE, (int)v).apply());
    dict(cur);
  }
  void dict(String t) {
    Dictionary.Opener o = getAssets()::open; StringBuilder sb = new StringBuilder("可用方向（→ " + t + "）：\n");
    for (String s : Dictionary.LANGS) { if (s.equals(t)) continue;
      boolean direct = Dictionary.has(o, s, t), pivot = !direct && Dictionary.has(o, s, "en") && Dictionary.has(o, "en", t);
      sb.append(s).append(" → ").append(t).append(direct ? "  直接词典" : pivot ? "  经英语中转" : "  无").append('\n'); }
    ((TextView)findViewById(R.id.dict)).setText(sb.toString().trim());
  }
}

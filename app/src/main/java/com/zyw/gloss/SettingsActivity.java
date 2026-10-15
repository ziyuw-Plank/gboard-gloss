package com.zyw.gloss;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class SettingsActivity extends Activity {
  static final int REQ_IMPORT = 42;
  static boolean isActive() { return false; }
  SharedPreferences sp;

  void bind(int id, String key, boolean def) {
    MaterialSwitch s = findViewById(id);
    s.setChecked(sp.getBoolean(key, def));
    s.setOnCheckedChangeListener((v, c) -> sp.edit().putBoolean(key, c).apply());
  }

  @SuppressWarnings("deprecation")
  @Override protected void onCreate(Bundle b) {
    com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
    super.onCreate(b);
    setContentView(R.layout.settings);
    try {
      sp = getSharedPreferences(Prefs.NAME, MODE_WORLD_READABLE);
    } catch (SecurityException e) {
      sp = getSharedPreferences(Prefs.NAME, 0);
    }
    ((TextView) findViewById(R.id.status)).setText(isActive()
        ? "✅ 模块已激活（作用域：Gboard）\n修改设置后约 2 秒内生效"
        : "⚠️ 模块未激活：在 LSPosed 中启用本模块并勾选 Gboard，然后强行停止 Gboard");

    bind(R.id.enable, Prefs.ENABLED, true);
    bind(R.id.banner, Prefs.BANNER, true);
    bind(R.id.online, Prefs.ONLINE, false);
    bind(R.id.debug, Prefs.DEBUG, false);

    ChipGroup g = findViewById(R.id.langs);
    String[] n = getResources().getStringArray(R.array.langs);
    String cur = sp.getString(Prefs.LANG, "en");
    for (int i = 0; i < n.length; i++) {
      Chip ch = new Chip(this);
      ch.setText(n[i]);
      ch.setCheckable(true);
      ch.setTag(Dictionary.LANGS[i]);
      ch.setId(1000 + i);
      g.addView(ch);
      if (Dictionary.LANGS[i].equals(cur)) ch.setChecked(true);
    }
    g.setOnCheckedStateChangeListener((gr, ids) -> {
      if (!ids.isEmpty()) {
        String code = (String) findViewById(ids.get(0)).getTag();
        sp.edit().putString(Prefs.LANG, code).apply();
        dict(code);
      }
    });

    Slider s = findViewById(R.id.size);
    s.setValue(sp.getInt(Prefs.SIZE, 10));
    s.addOnChangeListener((sl, v, u) -> sp.edit().putInt(Prefs.SIZE, (int) v).apply());

    findViewById(R.id.import_glossary).setOnClickListener(v -> {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("*/*");
      i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/tab-separated-values", "text/plain", "*/*"});
      startActivityForResult(i, REQ_IMPORT);
    });
    findViewById(R.id.clear_glossary).setOnClickListener(v -> {
      sp.edit().remove(Prefs.USER_GLOSSARY).apply();
      Dictionary.clearTransCache();
      Dictionary.loadGlossary(getAssets()::open, null);
      refreshGlossaryStatus();
      Toast.makeText(this, "已清除导入词表", Toast.LENGTH_SHORT).show();
    });

    Dictionary.loadGlossary(getAssets()::open, sp.getString(Prefs.USER_GLOSSARY, ""));
    dict(cur);
    refreshGlossaryStatus();
  }

  void refreshGlossaryStatus() {
    String user = sp.getString(Prefs.USER_GLOSSARY, "");
    int userLines = 0;
    if (user != null && !user.isEmpty()) {
      for (String line : user.split("\n")) {
        String t = line.trim();
        if (!t.isEmpty() && !t.startsWith("#") && t.indexOf('\t') > 0) userLines++;
      }
    }
    ((TextView) findViewById(R.id.glossary_status)).setText(
        "内置领域词表 " + Dictionary.glossaryBuiltinCount()
            + " 条 · 已导入 " + userLines + " 条\n"
            + "格式：词条<TAB>译文，精确匹配优先。模板见 APK assets/glossary.template.tsv");
  }

  void dict(String t) {
    Dictionary.Opener o = getAssets()::open;
    StringBuilder sb = new StringBuilder("可用方向（→ " + t + "）：\n");
    for (String s : Dictionary.LANGS) {
      if (s.equals(t)) continue;
      boolean direct = Dictionary.has(o, s, t);
      boolean pivot = !direct && Dictionary.has(o, s, "en") && Dictionary.has(o, "en", t);
      sb.append(s).append(" → ").append(t)
          .append(direct ? "  直接词典" : pivot ? "  经英语中转" : "  无").append('\n');
    }
    if (sp.getBoolean(Prefs.ONLINE, false)) {
      sb.append("在线补全：开（耗电与流量略增）");
    } else {
      sb.append("在线补全：关");
    }
    ((TextView) findViewById(R.id.dict)).setText(sb.toString().trim());
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) {
      return;
    }
    Uri uri = data.getData();
    try {
      getContentResolver().takePersistableUriPermission(uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (SecurityException ignored) {}
    try (InputStream in = getContentResolver().openInputStream(uri);
         BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      StringBuilder sb = new StringBuilder();
      String line;
      int n = 0;
      while ((line = br.readLine()) != null) {
        if (sb.length() > 200_000) break; // hard cap
        sb.append(line).append('\n');
        String t = line.trim();
        if (!t.isEmpty() && !t.startsWith("#") && t.indexOf('\t') > 0) n++;
      }
      sp.edit().putString(Prefs.USER_GLOSSARY, sb.toString()).apply();
      Dictionary.clearTransCache();
      Dictionary.loadGlossary(getAssets()::open, sb.toString());
      refreshGlossaryStatus();
      Toast.makeText(this, "已导入约 " + n + " 条", Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
      Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }
}

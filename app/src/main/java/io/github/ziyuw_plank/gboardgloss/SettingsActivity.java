package io.github.ziyuw_plank.gboardgloss;

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
    bind(R.id.mt, Prefs.MT, true);
    bind(R.id.wifi_only, Prefs.WIFI_ONLY, true);
    findViewById(R.id.mt_download).setOnClickListener(v -> downloadModels());
    android.widget.EditText in = findViewById(R.id.try_input);
    in.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
      public void onTextChanged(CharSequence a, int b, int c, int d) {}
      public void afterTextChanged(android.text.Editable e) { tryTranslate(e.toString()); }
    });

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
        refreshModels();
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
    refreshModels();
  }

  static final String[] SRC = {"zh", "en", "ja"};

  java.util.LinkedHashSet<String> neededModels() {
    java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
    String t = sp.getString(Prefs.LANG, "en");
    s.add(t);
    for (String x : SRC) if (!x.equals(t)) s.add(x);
    s.remove("en"); // English ships with ML Kit
    return s;
  }

  void refreshModels() {
    TextView st = findViewById(R.id.mt_status);
    com.google.mlkit.common.model.RemoteModelManager.getInstance()
        .getDownloadedModels(com.google.mlkit.nl.translate.TranslateRemoteModel.class)
        .addOnSuccessListener(set -> {
          java.util.HashSet<String> have = new java.util.HashSet<>();
          for (com.google.mlkit.nl.translate.TranslateRemoteModel m : set) have.add(m.getLanguage());
          StringBuilder b = new StringBuilder("模型状态：English 内置");
          for (String l : neededModels()) b.append(" · ").append(l).append(have.contains(l) ? " ✓" : " 未下载");
          b.append("\n下载后完全离线运行，翻译在本机完成，不上传输入内容。");
          st.setText(b);
        })
        .addOnFailureListener(e -> st.setText("模型状态读取失败：" + e.getMessage()));
  }

  void downloadModels() {
    com.google.mlkit.common.model.DownloadConditions.Builder cb = new com.google.mlkit.common.model.DownloadConditions.Builder();
    if (sp.getBoolean(Prefs.WIFI_ONLY, true)) cb.requireWifi();
    com.google.mlkit.common.model.DownloadConditions cond = cb.build();
    TextView st = findViewById(R.id.mt_status);
    st.setText("正在下载模型…");
    java.util.List<com.google.android.gms.tasks.Task<Void>> ts = new java.util.ArrayList<>();
    for (String l : neededModels()) {
      ts.add(com.google.mlkit.common.model.RemoteModelManager.getInstance().download(
          new com.google.mlkit.nl.translate.TranslateRemoteModel.Builder(l).build(), cond));
    }
    com.google.android.gms.tasks.Tasks.whenAllComplete(ts).addOnCompleteListener(x -> {
      MtProvider.downloadedAt = 0;
      refreshModels();
      Toast.makeText(this, "模型下载任务已完成", Toast.LENGTH_SHORT).show();
    });
  }

  final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
  Runnable pendingTry;

  /** Preview + also makes this app visible to Gboard (IME gets implicit visibility of its client). */
  void tryTranslate(String text) {
    if (pendingTry != null) ui.removeCallbacks(pendingTry);
    TextView out = findViewById(R.id.try_out);
    pendingTry = () -> new Thread(() -> {
      String t = text.trim();
      String tgt = sp.getString(Prefs.LANG, "en");
      String src = Dictionary.detectPrimary(t);
      String dict = t.isEmpty() ? null : Dictionary.translate(getAssets()::open, t, tgt);
      String mt = null;
      if (src != null && !src.equals(tgt) && !t.isEmpty()) {
        android.os.Bundle ex = new android.os.Bundle(); ex.putString("src", src); ex.putString("tgt", tgt);
        android.os.Bundle r = getContentResolver().call(android.net.Uri.parse("content://" + MtProvider.AUTH), MtProvider.M_TRANSLATE, t, ex);
        mt = r == null ? null : ("ok".equals(r.getString("s")) ? r.getString("r") : "（" + r.getString("s") + "）");
      }
      final String d = dict, m = mt;
      ui.post(() -> out.setText(t.isEmpty() ? "" : "词典：" + (d == null ? "—" : d) + "\n端侧翻译：" + (m == null ? "—" : m)));
    }).start();
    ui.postDelayed(pendingTry, 250);
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

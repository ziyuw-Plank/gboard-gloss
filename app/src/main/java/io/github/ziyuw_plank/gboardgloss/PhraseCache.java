package io.github.ziyuw_plank.gboardgloss;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe LRU of (text,tgt) → translation, optionally persisted as a small TSV. Pure Java. */
public final class PhraseCache {
  private final int max;
  private final File file;
  private int dirty;
  private final LinkedHashMap<String, String> map;

  public PhraseCache(int max, File file) {
    this.max = max;
    this.file = file;
    this.map = new LinkedHashMap<String, String>(64, 0.75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > PhraseCache.this.max; }
    };
    if (file != null) load();
  }

  static String key(String text, String tgt) { return tgt + "\u0001" + text; }
  static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim(); }

  public synchronized String get(String text, String tgt) { return map.get(key(clean(text), tgt)); }
  public synchronized int size() { return map.size(); }

  /** @return true when enough new entries accumulated that a flush is worthwhile. */
  public synchronized boolean put(String text, String tgt, String value) {
    if (text == null || tgt == null || value == null) return false;
    map.put(key(clean(text), tgt), clean(value));
    return file != null && ++dirty >= 16;
  }

  public synchronized void clear() { map.clear(); dirty = 1; }

  public void flush() {
    if (file == null) return;
    ArrayList<Map.Entry<String, String>> snap;
    synchronized (this) {
      if (dirty == 0) return;
      dirty = 0;
      snap = new ArrayList<>(map.entrySet());
    }
    File tmp = new File(file.getPath() + ".tmp");
    try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
      for (Map.Entry<String, String> e : snap) {
        int p = e.getKey().indexOf('\u0001');
        w.write(e.getKey().substring(0, p)); w.write('\t');
        w.write(e.getKey().substring(p + 1)); w.write('\t');
        w.write(e.getValue()); w.write('\n');
      }
    } catch (IOException e) { tmp.delete(); return; }
    if (!tmp.renameTo(file)) tmp.delete();
  }

  private void load() {
    if (!file.isFile()) return;
    try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        String[] f = line.split("\t", 3);
        if (f.length == 3 && !f[1].isEmpty()) map.put(key(f[1], f[0]), f[2]);
      }
    } catch (IOException ignored) {}
    dirty = 0;
  }
}

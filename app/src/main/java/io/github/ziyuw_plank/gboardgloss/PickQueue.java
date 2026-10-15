package io.github.ziyuw_plank.gboardgloss;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Debounce helper: collects candidate phrases during a typing burst and, once the burst
 * has been quiet for {@code quietMs}, releases only the first {@code limit} distinct ones
 * (the leading candidates). Pure Java, clock injected.
 */
public final class PickQueue<T> {
  private final int limit;
  private final long quietMs;
  private final LinkedHashMap<String, T> burst = new LinkedHashMap<>();
  private long lastAdd = Long.MIN_VALUE;

  public PickQueue(int limit, long quietMs) { this.limit = limit; this.quietMs = quietMs; }

  /** Adds an item; re-adding the same key updates its payload but keeps its position. */
  public synchronized void add(String key, T payload, long now) {
    if (burst.containsKey(key) || burst.size() < limit) burst.put(key, payload);
    lastAdd = now;
  }

  public synchronized boolean ready(long now) { return !burst.isEmpty() && now - lastAdd >= quietMs; }

  /** Returns the leading items if the burst is quiet, else an empty list. */
  public synchronized List<T> drain(long now) {
    if (!ready(now)) return new ArrayList<>();
    ArrayList<T> out = new ArrayList<>(burst.values());
    burst.clear();
    return out;
  }

  public synchronized int pending() { return burst.size(); }
}

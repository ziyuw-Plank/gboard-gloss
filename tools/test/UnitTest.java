import io.github.ziyuw_plank.gboardgloss.*;
import java.io.File;
import java.util.List;
public class UnitTest {
  static int fail = 0, n = 0;
  static void eq(Object a, Object b, String m) { n++; if (a == null ? b != null : !a.equals(b)) { fail++; System.out.println("FAIL " + m + ": got [" + a + "] want [" + b + "]"); } }
  public static void main(String[] x) throws Exception {
    // LRU
    PhraseCache c = new PhraseCache(2, null);
    c.put("a", "en", "1"); c.put("b", "en", "2"); c.get("a", "en"); c.put("c", "en", "3");
    eq(c.get("b", "en"), null, "lru evicts least-recent"); eq(c.get("a", "en"), "1", "lru keeps recent");
    eq(c.get("a", "zh"), null, "tgt separates");
    // disk
    File f = File.createTempFile("mtc", ".tsv"); f.delete();
    PhraseCache d = new PhraseCache(100, f);
    boolean flushHint = false;
    for (int i = 0; i < 16; i++) flushHint = d.put("我想知道" + i, "en", "I want to know\t" + i);
    eq(flushHint, true, "flush hint after 16"); d.flush();
    PhraseCache d2 = new PhraseCache(100, f);
    eq(d2.size(), 16, "reload size"); eq(d2.get("我想知道3", "en"), "I want to know 3", "reload value, tab sanitized");
    // debounce
    PickQueue<String> q = new PickQueue<>(3, 150);
    q.add("k1", "a", 0); q.add("k2", "b", 50); q.add("k3", "c", 60); q.add("k4", "d", 70); q.add("k2", "b2", 100);
    eq(q.drain(200).size(), 0, "not quiet yet");
    List<String> got = q.drain(250);
    eq(got.toString(), "[a, b2, c]", "first 3 distinct, payload updated");
    eq(q.pending(), 0, "drained");
    // grammar
    eq(Grammar.fix("I want know"), "I want to know", "want+V");
    eq(Grammar.fix("don't want go"), "don't want to go", "neg want+V");
    eq(Grammar.fix("I want you"), "I want you", "want+obj");
    eq(Grammar.fix("he go school"), "he goes school", "3sg");
    eq(Grammar.fix("can to go"), "can go", "modal to");
    eq(Grammar.fix("I need study"), "I need to study", "need+V");
    System.out.println(n - fail + "/" + n + " passed");
    if (fail > 0) System.exit(1);
  }
}

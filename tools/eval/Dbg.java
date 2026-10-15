import io.github.ziyuw_plank.gboardgloss.Dictionary;
public class Dbg{public static void main(String[] a){try{var f=Dictionary.class.getDeclaredField("DEBUG");f.setAccessible(true);f.set(null,true);}catch(Exception e){}Dictionary.Opener o=n->new java.io.FileInputStream("../../app/src/main/assets/"+n);
for(String w:a) System.out.println(w+" => "+Dictionary.translate(o,w,w.matches("[a-z ]+")?"zh":"en"));}}

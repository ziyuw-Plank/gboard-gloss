import io.github.ziyuw_plank.gboardgloss.Dictionary;
import java.io.*; import java.nio.file.*; import java.util.*;
public class Eval {
  public static void main(String[] a) throws Exception {
    String assets=a[0]; boolean verbose=a.length>1;
    Dictionary.Opener o = n -> new FileInputStream(assets+"/"+n);
    long t0=System.nanoTime(); Dictionary.translate(o,"你好","en"); Dictionary.translate(o,"hello","zh"); Dictionary.translate(o,"猫","en");
    System.out.printf("warmup(load) %.0f ms%n",(System.nanoTime()-t0)/1e6);
    String[][] sets={{"zh.tsv","en"},{"unseen.tsv","en"},{"blind.tsv","en"},{"zh_trad.tsv","en"},{"en.tsv","zh"},{"ja.tsv","en"}};
    PrintWriter fails=new PrintWriter("fails.txt");
    long tq=0; int nq=0;
    for(String[] s:sets){
      Map<String,int[]> cat=new TreeMap<>(); int line=0;
      for(String l:Files.readAllLines(Paths.get(s[0]))){
        if(l.startsWith("#")||l.isBlank())continue; String[] p=l.split("\t");
        String c=p[2]; String split=(line++%2==0)?"dev":"hold";
        long q=System.nanoTime(); String g=Dictionary.translate(o,p[0],s[1]); tq+=System.nanoTime()-q; nq++;
        boolean ok=false;
        if(g!=null){String gl=g.toLowerCase(); for(String k:p[1].split("\\|")) if(gl.matches("(?s).*"+k.toLowerCase()+".*")){ok=true;break;}}
        for(String key:new String[]{c,"ALL",split}){int[] v=cat.computeIfAbsent(key,x->new int[3]); v[0]++; if(g!=null)v[1]++; if(ok)v[2]++;}
        if(g==null) fails.println("MISS\t"+s[0]+"\t"+c+"\t"+p[0]);
        else if(!ok) fails.println("BAD\t"+s[0]+"\t"+c+"\t"+p[0]+"\t=> "+g+"\t(want "+p[1]+")");
        else if(verbose) fails.println("OK\t"+p[0]+"\t=> "+g);
      }
      System.out.println("== "+s[0]+" -> "+s[1]);
      for(var e:cat.entrySet()){int[] v=e.getValue();
        System.out.printf("  %-9s n=%3d coverage=%5.1f%% acc_on_shown=%5.1f%% garbage=%5.1f%% overall_ok=%5.1f%%%n",e.getKey(),v[0],100.0*v[1]/v[0],v[1]==0?0:100.0*v[2]/v[1],100.0*(v[1]-v[2])/v[0],100.0*v[2]/v[0]);}
    }
    fails.close();
    System.out.printf("avg lookup %.3f ms (cold, uncached)%n",tq/1e6/nq);
    Runtime r=Runtime.getRuntime(); System.gc(); System.out.printf("heap used %d MB%n",(r.totalMemory()-r.freeMemory())>>20);
  }
}

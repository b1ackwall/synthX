# Appendix — shuffle scenario simulation

This supports issues **#1 – #4** in [01-shuffle.md](01-shuffle.md).

No device or emulator was available for this audit, and Gradle was not run. To check the shuffle
findings mechanically rather than by reading alone, `QueueOrderStore`'s list logic was ported
line by line to plain Java. The port covers `captureFresh`, `captureFreshShuffled`,
`insertAfterCurrent`, `append`, `shuffle` and `unshuffle`, with records reduced to slot-id
strings. The engine's call sequence was then replayed on top of it:

* shuffle ON/OFF → `shuffle` / `unshuffle`, as in `setShuffleEnabledInternal`;
* Play Next while shuffled → `insertAfterCurrent(mirrorToBaseline = false)`, as in `playNextInternal`;
* the Shuffle button → `captureFreshShuffled([start] + shuffled(rest), listOrder)`, as in `playQueueInternal` with `shuffle = true`.

"Now playing" is moved by hand to whichever slot playback (or a tap in Up Next) would reach.
The shuffle permutation uses a fixed seed, so the run can be reproduced. Exact positions depend
on the seed, but the outcomes do not.

To run it: save the code below as `Sim.java`, then run `javac Sim.java && java Sim`
(JDK 11 or later).

## Output

```
S1: shuffle ON, Play Next X, skip to X, shuffle OFF
  after play-next X (shuffled): live=[t1, X, t5, t2, t7, t3, t4, t8, t6]  baseline=[t1, t2, t3, t4, t5, t6, t7, t8]
  after shuffle OFF while X playing: live=[X]  baseline=[X]
S2: shuffle ON, Play Next X (not played yet), shuffle OFF
  after shuffle OFF: live=[t1, t2, t3, t4, t5, t6, t7, t8, X]  baseline=[t1, t2, t3, t4, t5, t6, t7, t8, X]
S3: shuffle ON, Play Next X, X plays, move to next, shuffle OFF
  now playing t8 (X already heard)
  after shuffle OFF: live=[t8, X]  baseline=[t8, X]
S4: Shuffle button picks random start t7 of 12, user turns shuffle OFF immediately
  after shuffle OFF (0 songs played): live=[t7, t8, t9, t10, t11, t12]  baseline=[t7, t8, t9, t10, t11, t12]
S5: in-order play at t6, shuffle ON, play 2 songs, shuffle OFF, shuffle ON again
  shuffled: live=[t6, t2, t12, t11, t4, t8, t7, t9, t5, t10, t3, t1]  baseline=[t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12]
  now playing t12 after hearing 2 songs
  after OFF: live=[t12]  baseline=[t12]
  after ON again (queue has shrunk): live=[t12]  baseline=[t12]
```

How to read it:

* **S1 (#1):** the whole 8-song album disappears; only `X` is left.
* **S2 (#4):** `X` was "Play Next" but ends up after all 8 album songs.
* **S3 (#2, #4):** after `X` was heard, shuffle OFF keeps only `[t8, X]`. Six album songs that
  were never heard are deleted, and the already-heard `X` is queued again.
* **S4 (#3):** the Shuffle button started at t7; turning shuffle off with zero songs played
  deletes t1–t6.
* **S5 (#2):** two songs into a shuffle that was started at t6 of an in-order album, shuffle OFF
  leaves one song. Turning shuffle ON again cannot bring anything back.

## Source

```java
import java.util.*;
import java.util.function.*;

// Line-for-line port of app/.../playback/QueueOrderStore.kt (records reduced to slot-id strings)
// plus the engine's shuffle/play-next sequencing, to exercise shuffle scenarios.
public class Sim {
  static List<String> live = new ArrayList<>(), baseline = new ArrayList<>();
  static int idx(String s){ return live.indexOf(s); }
  static void captureFresh(List<String> r){ live=new ArrayList<>(r); baseline=new ArrayList<>(r); }
  static void captureFreshShuffled(List<String> l, List<String> b){ live=new ArrayList<>(l); baseline=new ArrayList<>(b); }
  static void insertAfterCurrent(String rec, String cur, boolean mirror){
    int li=idx(cur); int at= li>=0? li+1: live.size(); live.add(Math.max(0,Math.min(at,live.size())), rec);
    if(mirror){ int bi=baseline.indexOf(cur); int bat= bi>=0? bi+1: baseline.size(); baseline.add(Math.max(0,Math.min(bat,baseline.size())), rec);} }
  static void append(String rec, boolean mirror){ live.add(rec); if(mirror) baseline.add(rec); }
  static boolean shuffle(String cur, UnaryOperator<List<String>> sh){
    if(!live.contains(cur)) return false; List<String> rest=new ArrayList<>(baseline); rest.remove(cur);
    List<String> n=new ArrayList<>(); n.add(cur); n.addAll(sh.apply(rest)); live=n; return true; }
  static boolean unshuffle(String cur){
    if(!live.contains(cur)) return false; int bi=baseline.indexOf(cur);
    List<String> after= bi>=0? new ArrayList<>(baseline.subList(bi+1, baseline.size())): new ArrayList<>();
    Set<String> bids=new HashSet<>(baseline); List<String> extras=new ArrayList<>();
    for(String s: live) if(!s.equals(cur) && !bids.contains(s)) extras.add(s);
    List<String> t=new ArrayList<>(); t.add(cur); t.addAll(after); t.addAll(extras); live=t; baseline=new ArrayList<>(t); return true; }
  static UnaryOperator<List<String>> rnd(long seed){ return l -> { List<String> c=new ArrayList<>(l); Collections.shuffle(c,new Random(seed)); return c; }; }
  static List<String> album(int n){ List<String> l=new ArrayList<>(); for(int i=1;i<=n;i++) l.add("t"+i); return l; }
  static void show(String label){ System.out.println("  "+label+": live="+live+"  baseline="+baseline); }

  public static void main(String[] a){
    System.out.println("S1: shuffle ON, Play Next X, skip to X, shuffle OFF");
    captureFresh(album(8)); String cur="t1";
    shuffle(cur, rnd(1)); insertAfterCurrent("X", cur, false); show("after play-next X (shuffled)");
    cur="X"; unshuffle(cur); show("after shuffle OFF while X playing");

    System.out.println("S2: shuffle ON, Play Next X (not played yet), shuffle OFF");
    captureFresh(album(8)); cur="t1"; shuffle(cur, rnd(2)); insertAfterCurrent("X", cur, false);
    unshuffle(cur); show("after shuffle OFF");

    System.out.println("S3: shuffle ON, Play Next X, X plays, move to next, shuffle OFF");
    captureFresh(album(8)); cur="t1"; shuffle(cur, rnd(3)); insertAfterCurrent("X", cur, false);
    cur=live.get(2); System.out.println("  now playing "+cur+" (X already heard)"); unshuffle(cur); show("after shuffle OFF");

    System.out.println("S4: Shuffle button picks random start t7 of 12, user turns shuffle OFF immediately");
    List<String> al=album(12); List<String> rest=new ArrayList<>(al); rest.remove("t7"); rest=rnd(4).apply(rest);
    List<String> lv=new ArrayList<>(); lv.add("t7"); lv.addAll(rest); captureFreshShuffled(lv, al); cur="t7";
    unshuffle(cur); show("after shuffle OFF (0 songs played)");

    System.out.println("S5: in-order play at t6, shuffle ON, play 2 songs, shuffle OFF, shuffle ON again");
    captureFresh(album(12)); cur="t6"; shuffle(cur, rnd(5)); show("shuffled");
    cur=live.get(2); System.out.println("  now playing "+cur+" after hearing 2 songs"); unshuffle(cur); show("after OFF");
    shuffle(cur, rnd(6)); show("after ON again (queue has shrunk)");
  }
}
```

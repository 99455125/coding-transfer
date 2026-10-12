package dev.codingtransfer.plugin;
import java.util.ArrayList;
import java.util.List;
public class RdpFocusAssistTest {
 static class Fake implements RdpFocusAssist.Platform {
  String focus="remote";long stamp;boolean idle=true,supported=true,failHost,failReturn;Runnable during=()->{};
  List<Object> activations=new ArrayList<>();
  public Object foreground(){return focus;}public Object host(){return "host";}
  public boolean supported(Object target){return supported;}public boolean idle(){return idle;}
  public long inputStamp(){return stamp;}
  public boolean activate(Object target,Object expected,long token){if(!focus.equals(expected)||stamp!=token)return false;activations.add(target);if(target.equals("host")&&failHost||target.equals("remote")&&failReturn)return false;focus=(String)target;return true;}
  public void settle(){during.run();}
 }
 public static void main(String[] args)throws Exception {
  Fake normal=new Fake();check(RdpFocusAssist.cycle(normal).equals("switched")&&normal.activations.equals(List.of("host","remote")),"restore captured client");
  Fake active=new Fake();active.idle=false;check(RdpFocusAssist.cycle(active).equals("user_active")&&active.activations.isEmpty(),"do not disturb active user");
  Fake other=new Fake();other.supported=false;check(RdpFocusAssist.cycle(other).equals("not_rdp")&&other.activations.isEmpty(),"do not activate unrelated app");
  Fake changed=new Fake();changed.during=()->changed.focus="other";check(RdpFocusAssist.cycle(changed).equals("focus_interrupted")&&changed.focus.equals("other")&&changed.activations.size()==1,"do not steal focus back");
  Fake input=new Fake();input.during=()->input.idle=false;check(RdpFocusAssist.cycle(input).equals("focus_interrupted")&&input.activations.size()==1,"stop if user operates temporary host");
  Fake aged=new Fake();aged.during=()->aged.stamp++;check(RdpFocusAssist.cycle(aged).equals("focus_interrupted")&&aged.activations.size()==1,"remember input even after idle delay");
  Fake denied=new Fake();denied.failHost=true;check(RdpFocusAssist.cycle(denied).equals("os_error")&&denied.focus.equals("remote"),"activation failure keeps remote");
  Fake interrupted=new Fake();interrupted.during=()->{throw new RuntimeException("interrupted");};try{RdpFocusAssist.cycle(interrupted);throw new AssertionError("must propagate");}catch(RuntimeException expected){check(interrupted.focus.equals("remote"),"restore on interruption");}
  Fake restore=new Fake();restore.failReturn=true;try{RdpFocusAssist.cycle(restore);throw new AssertionError("restore failure must surface");}catch(IllegalStateException expected){}
  System.out.println("PASS focus recovery guards");
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

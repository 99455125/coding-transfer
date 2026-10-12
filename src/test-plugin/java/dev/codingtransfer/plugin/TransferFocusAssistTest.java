package dev.codingtransfer.plugin;
public class TransferFocusAssistTest {
 public static void main(String[] args) {
  TransferFocusAssist defaultGate=new TransferFocusAssist();defaultGate.arm("default",0);check(!defaultGate.claim(299,true),"default waits 300 ms");check(defaultGate.claim(300,true),"default recovery at 300 ms");
  TransferFocusAssist gate=new TransferFocusAssist(1000);
  gate.arm("frame1",0);
  check(!gate.claim(999,true),"first switch waits for publication to settle");
  check(!gate.claim(1000,false),"paused skip");
  check(gate.claim(1000,true),"first recovery after one second");
  gate.sent();gate.defer(1000);gate.arm("frame1",1500);
  check(!gate.claim(3999,true),"three second observation after switch");
  check(gate.claim(4000,true),"duplicate write does not postpone observation window");gate.sent();gate.defer(4000);
  check(!gate.claim(6999,true),"second switch also leaves observation time");
  check(gate.claim(7000,true),"third recovery");gate.sent();gate.defer(7000);
  gate.arm("frame1",8000);check(!gate.claim(10000,true),"duplicate cannot bypass three-attempt limit");
  gate.arm("frame2",10000);check(!gate.claim(10999,true),"new frame starts a fresh first-switch delay");check(gate.claim(11000,true),"new progress gets a new budget");
  check(TransferCompletionText.format("文件传输完成",21).equals("文件传输完成 21/21"),"completion retains total");
  System.out.println("PASS focus recovery gate");
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

package dev.codingtransfer.plugin;
public class TransferInputAssistTest {
 public static void main(String[] args) {
  TransferInputAssist gate=new TransferInputAssist();
  gate.arm("frame1",0);
  check(gate.nextLeft(),"session begins left");
  gate.sent();check(!gate.nextLeft(),"next successful key is right");
  gate.defer(0);check(!gate.nextLeft(),"skipped attempt preserves direction");
  gate.arm("frame1",0);check(!gate.nextLeft(),"publication preserves direction");
  gate.sent();check(gate.nextLeft(),"alternates back left");
  check(!gate.claim(999,true),"wait one second");
  check(!gate.claim(1000,false),"paused/blocked skip");
  check(gate.claim(1000,true),"skipped attempt preserves opportunity");
  gate.arm("frame1",3000);check(gate.claim(6000,true),"successful protocol retry rearms");
  gate.arm("frame2",6000);check(gate.claim(8000,true),"next frame allows one pair");
  check(!gate.claim(9000,true),"only one pair");
  check(TransferCompletionText.format("文件传输完成",21).equals("文件传输完成 21/21"),"completion retains total");
  System.out.println("PASS transfer input assist gate and completion counts");
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

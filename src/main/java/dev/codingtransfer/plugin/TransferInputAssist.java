package dev.codingtransfer.plugin;

/** Rate gate for waiting assistance; successful pulses and temporary skips rearm without changing clipboard. */
final class TransferInputAssist {
    private boolean armed,attempted;
    private boolean nextLeft=true;
    boolean nextLeft(){return nextLeft;}
    void sent(){nextLeft=!nextLeft;}
    private long since;
    void arm(String value,long now){
        since=now;armed=true;attempted=false;
    }
    boolean claim(long now,boolean eligible){
        if(!armed||attempted||!eligible||now-since<1000)return false;
        attempted=true;return true;
    }
    void defer(long now){
        since=now;attempted=false;
    }
}

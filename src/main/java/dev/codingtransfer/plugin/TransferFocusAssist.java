package dev.codingtransfer.plugin;

import java.util.Objects;

/** Bounded focus recovery after stalled progress; duplicate publication preserves the budget. */
final class TransferFocusAssist {
    private static final long OBSERVATION_MILLIS=3000;
    private final int firstDelayMillis;
    TransferFocusAssist(){this(dev.codingtransfer.core.Config.DEFAULT_FOCUS_DELAY_MS);}
    TransferFocusAssist(int delay){if(delay<dev.codingtransfer.core.Config.MIN_FOCUS_DELAY_MS||delay>dev.codingtransfer.core.Config.MAX_FOCUS_DELAY_MS)throw new IllegalArgumentException("Invalid focus delay");firstDelayMillis=delay;}
    private boolean armed,attempted;
    private int switches;
    private String frame;
    private long since;
    void sent(){switches++;}
    void arm(String value,long now){
        if(armed&&Objects.equals(frame,value))return;
        frame=value;since=now;armed=true;attempted=false;switches=0;
    }
    long waitedMillis(long now){return Math.max(0,now-since);}
    boolean claim(long now,boolean eligible){
        if(!armed||attempted||!eligible||switches>=3||now-since<(switches==0?firstDelayMillis:OBSERVATION_MILLIS))return false;
        attempted=true;return true;
    }
    void defer(long now){since=now;attempted=false;}
}

package dev.codingtransfer.core;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Confirms every released transfer drops callbacks/owner references and its scheduled poll. */
final class TransferSessionLeaseTest {
    private TransferSessionLeaseTest() {}

    static void run() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        try {
            for (int i=0;i<1_000;i++) {
                TransferSessionLease lease = new TransferSessionLease();
                Object owner = new Object(), handler = new Object(), listener = new Object();
                lease.bind(owner,handler,listener);
                lease.setPollTask(scheduler.scheduleWithFixedDelay(()->{},1,1,TimeUnit.HOURS));
                lease.release();
                TestSupport.check(lease.isReleased()&&lease.owner()==null&&lease.handler()==null&&lease.listener()==null
                                &&scheduler.getQueue().isEmpty(),
                        "released lease cancels polling and drops UI callback references");
            }
        } finally { scheduler.shutdownNow(); }
        System.out.println("PASS transfer lease cancellation and reference-release checks");
    }
}

package dev.codingtransfer.core;

import java.util.concurrent.ScheduledFuture;

/** Owns the scheduler handle and transient UI callbacks for one clipboard lease. */
public final class TransferSessionLease {
    private Object owner;
    private Object handler;
    private Object listener;
    private ScheduledFuture<?> pollTask;

    public synchronized void bind(Object owner, Object handler, Object listener) {
        if (this.owner != null) throw new IllegalStateException("lease already bound");
        this.owner = owner;
        this.handler = handler;
        this.listener = listener;
    }

    public synchronized boolean owns(Object candidate) { return owner != null && owner == candidate; }
    public synchronized Object owner() { return owner; }
    public synchronized Object handler() { return handler; }
    public synchronized Object listener() { return listener; }

    public synchronized void setPollTask(ScheduledFuture<?> task) {
        if (owner == null) { task.cancel(false); return; }
        if (pollTask != null) pollTask.cancel(false);
        pollTask = task;
    }

    public synchronized void cancelPolling() {
        if (pollTask != null) pollTask.cancel(false);
        pollTask = null;
    }

    public synchronized void release() {
        cancelPolling();
        owner = null;
        handler = null;
        listener = null;
    }

    public synchronized boolean isReleased() { return owner == null && handler == null && listener == null && pollTask == null; }
}

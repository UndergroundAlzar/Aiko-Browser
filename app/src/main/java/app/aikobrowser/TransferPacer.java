package app.aikobrowser;

import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Briefly yields the downloader to a foreground page navigation. */
final class TransferPacer {
    // stateLock is only acquired by control/UI callers (never by download workers), preventing priority inversion.
    private final Object stateLock = new Object();
    private final Object waitLock = new Object();
    private final AtomicInteger waiters = new AtomicInteger();
    private volatile long until;
    private volatile boolean browserFirst = true;
    private long generation;

    long protect(long milliseconds) {
        if (!browserFirst) return 0;
        synchronized (stateLock) {
            if (!browserFirst) return 0;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(Math.max(1, milliseconds), 1600));
            until = deadline == 0 ? 1L : deadline;
            return ++generation;
        }
    }

    void release(long ticket) {
        if (ticket == 0) return;
        synchronized (stateLock) {
            if (ticket != generation) return;
            until = 0;
        }
        wake();
    }

    void setBrowserFirst(boolean enabled) {
        synchronized (stateLock) {
            browserFirst = enabled;
            if (!enabled) until = 0;
        }
        if (!enabled) wake();
    }

    boolean browserFirst() { return browserFirst; }

    boolean yielding() {
        long end = until;
        return end != 0 && end - System.nanoTime() > 0;
    }

    void wake() {
        if (waiters.get() > 0) {
            synchronized (waitLock) {
                waitLock.notifyAll();
            }
        }
    }

    void await(RangeDownload.Control control) throws InterruptedIOException {
        control.check();
        if (!yielding()) return;
        control.attachPacer(this);
        waiters.incrementAndGet();
        try {
            synchronized (waitLock) {
                for (;;) {
                    control.check();
                    long end = until;
                    if (end == 0) return;
                    long remaining = end - System.nanoTime();
                    if (remaining <= 0) return;
                    try {
                        TimeUnit.NANOSECONDS.timedWait(waitLock, remaining);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("下载被中断");
                    }
                }
            }
        } finally {
            waiters.decrementAndGet();
        }
    }

    void awaitYieldQuietly() {
        if (!yielding()) return;
        waiters.incrementAndGet();
        try {
            synchronized (waitLock) {
                for (;;) {
                    long end = until;
                    if (end == 0) return;
                    long remaining = end - System.nanoTime();
                    if (remaining <= 0) return;
                    try {
                        TimeUnit.NANOSECONDS.timedWait(waitLock, remaining);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } finally {
            waiters.decrementAndGet();
        }
    }
}

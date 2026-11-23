package app.aikobrowser;

import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

/** Briefly yields the downloader to a foreground page navigation. */
final class TransferPacer {
    private final Object lock = new Object();
    private volatile long until;
    private volatile boolean browserFirst = true;
    private long generation;

    long protect(long milliseconds) {
        synchronized (lock) {
            if (!browserFirst) return 0;
            long now = System.nanoTime();
            until = now + TimeUnit.MILLISECONDS.toNanos(Math.min(Math.max(1, milliseconds), 1600));
            lock.notifyAll();
            return ++generation;
        }
    }

    void release(long ticket) {
        if (ticket == 0) return;
        synchronized (lock) {
            if (ticket != generation) return;
            until = 0;
            lock.notifyAll();
        }
    }

    void setBrowserFirst(boolean enabled) {
        synchronized (lock) {
            browserFirst = enabled;
            if (!enabled) until = 0;
            lock.notifyAll();
        }
    }

    boolean browserFirst() { return browserFirst; }

    boolean yielding() {
        long end = until;
        return end != 0 && end - System.nanoTime() > 0;
    }

    void await(RangeDownload.Control control) throws InterruptedIOException {
        control.check();
        if (!yielding()) return;
        synchronized (lock) {
            for (;;) {
                control.check();
                if (until == 0) return;
                long remaining = until - System.nanoTime();
                if (remaining <= 0) return;
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, Math.min(remaining, 80_000_000L));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("下载被中断");
                }
            }
        }
    }
}

package app.aikobrowser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class RangeDownloadCheck {
    private static final int MIB = 1024 * 1024;
    private static final Set<Long> rangeStarts = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger active = new AtomicInteger();
    private static final AtomicInteger peak = new AtomicInteger();
    private static final AtomicInteger priorityRequests = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            File folder = Path.of(args[0]).toFile();
            DownloadRecord saved = DownloadRecord.read(folder);
            require(saved != null, "child can read persisted task");
            new RangeDownload(saved.url, new File(folder, "recovery.part"), saved.lanes,
                    saved.userAgent, null).transfer();
            return;
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
        ExecutorService peer = Executors.newCachedThreadPool();
        ExecutorService task = Executors.newSingleThreadExecutor();
        server.setExecutor(peer);
        server.createContext("/many", ex -> serve(ex, 128 * MIB, true, false, 0));
        server.createContext("/slow", ex -> serve(ex, 8 * MIB, true, false, 12));
        server.createContext("/plain", ex -> serve(ex, 2 * MIB + 31, false, false, 0));
        server.createContext("/changed", ex -> serve(ex, 2 * MIB, true, true, 0));
        server.createContext("/recovery", ex -> serve(ex, 24 * MIB, true, false, 36));
        AtomicInteger partial = new AtomicInteger();
        AtomicInteger unavailable = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        server.createContext("/retry", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            if (("bytes=0-" + (MIB - 1)).equals(range) && partial.getAndIncrement() == 0) {
                serve(ex, 2 * MIB, true, false, 0, 128 * 1024);
            } else if (("bytes=" + MIB + "-" + (2 * MIB - 1)).equals(range)
                    && unavailable.getAndIncrement() == 0) {
                respondEmpty(ex, 503);
            } else {
                serve(ex, 2 * MIB, true, false, 0);
            }
        });
        server.createContext("/limited", ex -> rejectRange(ex, 429, limited, true));
        AtomicInteger busy = new AtomicInteger();
        server.createContext("/busy", ex -> rejectRange(ex, 503, busy, false));
        server.createContext("/priority", ex -> {
            priorityRequests.incrementAndGet();
            serve(ex, 2 * MIB, true, false, 0);
        });
        server.start();
        Path temp = Files.createTempDirectory("aiko-range-check-");
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            RangeDownload many = download(base + "/many", temp, "many", 128);
            require(many.transfer(), "128-lane download");
            require(many.segments.size() == 128 && rangeStarts.size() == 128 && peak.get() > 1,
                    "128 real byte-range requests");
            verify(many.part, 128 * MIB);
            System.out.println("PASS 128 ranges, peak simultaneous HTTP transfers " + peak.get()
                    + ", all bytes checked");

            rangeStarts.clear();
            RangeDownload first = download(base + "/slow", temp, "resume", 4);
            Future<Boolean> running = task.submit(first::transfer);
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (first.downloaded() < 320 * 1024 && System.nanoTime() < limit) Thread.sleep(5);
            long before = first.downloaded();
            require(before > 0 && before < 8L * MIB, "paused during actual transfer");
            first.control.stop();
            require(!running.get(25, TimeUnit.SECONDS), "paused state");
            rangeStarts.clear();
            RangeDownload resumed = download(base + "/slow", temp, "resume", 4);
            require(resumed.transfer(), "resumed");
            require(rangeStarts.stream().anyMatch(pos -> pos % (2L * MIB) != 0), "requested partially saved offsets");
            verify(resumed.part, 8 * MIB);
            System.out.println("PASS pause and resume with actual saved offsets");

            RangeDownload fallback = download(base + "/plain", temp, "plain", 128);
            require(fallback.transfer() && !fallback.resumable && fallback.segments.size() == 1,
                    "single connection fallback");
            verify(fallback.part, 2 * MIB + 31);
            System.out.println("PASS server rejected ranges, completed via one stream");

            RangeDownload changed = download(base + "/changed", temp, "changed", 2);
            try {
                changed.transfer();
                throw new AssertionError("mismatched ETag should fail");
            } catch (RangeDownload.Changed expected) {
                require(!changed.part.exists(), "discard mismatched staged bytes");
            }
            System.out.println("PASS mismatched ETag rejected and staging discarded");
            checkPriority(task, base + "/priority", temp);
            checkInFlightPriority(task, base + "/slow", temp);
            checkProcessDeath(base + "/recovery", temp);
            rangeStarts.clear();
            RangeDownload retried = download(base + "/retry", temp, "retry", 2);
            require(retried.transfer(), "recover both failed lanes");
            require(partial.get() == 1 && unavailable.get() == 2 && rangeStarts.contains(128L * 1024),
                    "retry truncated lane from its written offset and independently recover 503");
            verify(retried.part, 2 * MIB);
            expectHttpFailure(download(base + "/limited", temp, "limited", 1), 429);
            require(limited.get() == 1, "rate limit respected");
            expectHttpFailure(download(base + "/busy", temp, "busy", 1), 503);
            require(busy.get() == 3, "persistent 503 retries bounded");
            System.out.println("PASS truncated segment retried at saved offset, transient 503 recovered, rate limits respected");
        } finally {
            server.stop(0);
            peer.shutdownNow();
            task.shutdownNow();
            try (java.util.stream.Stream<Path> files = Files.list(temp)) {
                for (Path path : (Iterable<Path>) files::iterator) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(temp);
        }
    }

    private static void checkProcessDeath(String url, Path dir) throws Exception {
        File folder = dir.toFile();
        URL address = new URL(url);
        DownloadRecord newTask = new DownloadRecord(address, "recovery.bin", "Aiko-test", 2);
        newTask.write(folder);
        DownloadRecord saved = DownloadRecord.read(folder);
        require(saved != null && saved.name.equals("recovery.bin") && saved.lanes == 2
                && saved.url.toExternalForm().equals(url), "restore recorded task");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "--add-modules", "jdk.httpserver", "-cp",
                System.getProperty("java.class.path"), RangeDownloadCheck.class.getName(), dir.toString())
                .inheritIO().start();
        File file = dir.resolve("recovery.part").toFile();
        RangeDownload probe = new RangeDownload(address, file, 2, saved.userAgent, null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        try {
            do {
                require(child.isAlive(), "child still transferring before forced exit");
                probe.showSavedProgress();
                if (probe.downloaded() > 0L && probe.downloaded() < 24L * MIB) break;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            require(probe.downloaded() > 0 && probe.downloaded() < 24L * MIB, "partial file checkpoint exists");
        } finally {
            child.destroyForcibly();
            require(child.waitFor(5, TimeUnit.SECONDS), "child stopped without graceful cleanup");
        }

        long before = probe.downloaded();
        long[] offsets = probe.segments.stream().mapToLong(segment -> segment.next).toArray();
        rangeStarts.clear();
        RangeDownload resumed = new RangeDownload(address, file, 2, saved.userAgent, null);
        require(resumed.transfer(), "resume after killed process");
        for (long offset : offsets) require(rangeStarts.contains(offset), "resumed from checkpoint offset " + offset);
        verify(resumed.part, 24 * MIB);
        DownloadRecord pending = saved.publication(DownloadRecord.PUBLISHING, "content://media/external/downloads/42", 24L * MIB);
        pending.write(folder);
        require(DownloadRecord.read(folder).phase == DownloadRecord.PUBLISHING
                && DownloadRecord.read(folder).size == 24L * MIB, "pending publication can be located after restart");
        pending.publication(DownloadRecord.COMPLETE, pending.output, pending.size).write(folder);
        require(DownloadRecord.read(folder).phase == DownloadRecord.COMPLETE, "remember completed file");
        DownloadRecord.clear(folder);
        require(DownloadRecord.read(folder) == null, "remove cancelled record");
        System.out.println("PASS forced process exit, resumed from " + before + " saved bytes; all bytes checked");
    }

    private static void checkPriority(ExecutorService task, String url, Path dir) throws Exception {
        TransferPacer pacer = new TransferPacer();
        long old = pacer.protect(1600);
        long current = pacer.protect(1600);
        RangeDownload delayed = new RangeDownload(new URL(url), dir.resolve("priority.part").toFile(),
                2, "Aiko-test", null, pacer, () -> {});
        CountDownLatch started = new CountDownLatch(1);
        Future<Boolean> work = task.submit(() -> {
            started.countDown();
            return delayed.transfer();
        });
        require(started.await(5, TimeUnit.SECONDS), "priority job started");
        pacer.release(old);
        try {
            work.get(120, TimeUnit.MILLISECONDS);
            throw new AssertionError("old navigation released the active gate");
        } catch (TimeoutException expected) {
            require(priorityRequests.get() == 0 && pacer.yielding(), "no requests during navigation hold");
        }
        pacer.release(current);
        require(work.get(8, TimeUnit.SECONDS) && priorityRequests.get() == 3, "resume probe and two ranges");
        verify(delayed.part, 2 * MIB);

        pacer.protect(1600);
        pacer.setBrowserFirst(false);
        require(!pacer.yielding() && pacer.protect(1600) == 0, "full speed bypass");

        pacer.setBrowserFirst(true);
        pacer.protect(1600);
        RangeDownload cancel = new RangeDownload(new URL(url), dir.resolve("priority-stop.part").toFile(),
                2, "Aiko-test", null, pacer, () -> {});
        Future<Boolean> stopped = task.submit(cancel::transfer);
        cancel.control.stop();
        require(!stopped.get(1, TimeUnit.SECONDS), "stop does not wait for navigation deadline");
        require(priorityRequests.get() == 3, "stopped download made no requests");
        System.out.println("PASS navigation hold, stale navigation protection, full-speed mode and stop");
    }

    private static void checkInFlightPriority(ExecutorService task, String url, Path dir) throws Exception {
        TransferPacer pacer = new TransferPacer();
        RangeDownload download = new RangeDownload(new URL(url), dir.resolve("yield-live.part").toFile(),
                2, "Aiko-test", null, pacer, () -> {});
        Future<Boolean> work = task.submit(download::transfer);
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (download.downloaded() < 320 * 1024 && System.nanoTime() < limit) Thread.sleep(5);
        require(download.downloaded() > 0 && download.downloaded() < 8L * MIB, "stream already active");
        long ticket = pacer.protect(1600);
        // Let the read that was already in flight reach the next chunk boundary.
        Thread.sleep(150);
        long parked = download.downloaded();
        require(download.activeLanes() > 0 && !work.isDone(), "keep active sockets during hold");
        Thread.sleep(120);
        require(download.downloaded() == parked, "active download temporarily stopped writing bytes");
        pacer.release(ticket);
        require(work.get(10, TimeUnit.SECONDS), "existing sockets resume after navigation");
        verify(download.part, 8 * MIB);
        System.out.println("PASS in-flight streams yield and resume without restarting the file");
    }

    private static RangeDownload download(String address, Path temp, String name, int lanes) throws Exception {
        return new RangeDownload(new URL(address), temp.resolve(name + ".part").toFile(), lanes, "Aiko-test", null);
    }

    private static void serve(HttpExchange ex, int size, boolean supportsRanges, boolean change, int delay) {
        serve(ex, size, supportsRanges, change, delay, 0);
    }

    private static void serve(HttpExchange ex, int size, boolean supportsRanges, boolean change, int delay,
                              int truncateAt) {
        int lo = 0, hi = size, code = 200;
        boolean work = false;
        try {
            String range = ex.getRequestHeaders().getFirst("Range");
            ex.getResponseHeaders().set("ETag", "\"aiko-v1\"");
            if (supportsRanges && range != null) {
                String[] parts = range.substring(6).split("-");
                lo = Integer.parseInt(parts[0]);
                hi = Integer.parseInt(parts[1]) + 1;
                code = 206;
                ex.getResponseHeaders().set("Content-Range", "bytes " + lo + "-" + (hi - 1) + "/" + size);
                if (hi - lo > 1) {
                    work = true;
                    rangeStarts.add((long) lo);
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    if (change) ex.getResponseHeaders().set("ETag", "\"aiko-v2\"");
                }
            }
            if (truncateAt > 0) ex.getResponseHeaders().set("Connection", "close");
            ex.sendResponseHeaders(code, hi - lo);
            byte[] data = new byte[64 * 1024];
            if (work && size >= 128 * MIB) Thread.sleep(50);
            try (OutputStream out = ex.getResponseBody()) {
                int actualEnd = truncateAt > 0 ? Math.min(hi, lo + truncateAt) : hi;
                for (int at = lo; at < actualEnd;) {
                    int n = Math.min(data.length, actualEnd - at);
                    for (int i = 0; i < n; i++) data[i] = sample(at + i);
                    out.write(data, 0, n);
                    at += n;
                    if (delay > 0 && work) Thread.sleep(delay);
                }
            }
        } catch (IOException ignored) {
            // The probe and pause deliberately close unfinished responses.
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            if (work) active.decrementAndGet();
            ex.close();
        }
    }

    private static void rejectRange(HttpExchange ex, int code, AtomicInteger calls, boolean retryAfter) throws IOException {
        if ("bytes=0-0".equals(ex.getRequestHeaders().getFirst("Range"))) {
            serve(ex, 2 * MIB, true, false, 0);
            return;
        }
        calls.incrementAndGet();
        if (retryAfter) ex.getResponseHeaders().set("Retry-After", "10");
        respondEmpty(ex, code);
    }

    private static void respondEmpty(HttpExchange ex, int code) throws IOException {
        try { ex.sendResponseHeaders(code, -1); } finally { ex.close(); }
    }

    private static void expectHttpFailure(RangeDownload download, int code) throws IOException {
        try {
            download.transfer();
            throw new AssertionError("expected HTTP " + code);
        } catch (IOException error) {
            require(("HTTP " + code).equals(error.getMessage()), "preserve HTTP failure");
        }
    }

    private static byte sample(int offset) { return (byte) (offset * 31 ^ (offset >>> 15) ^ (offset >>> 21)); }

    private static void verify(File file, int size) throws IOException {
        require(file.length() == size, "output length");
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[256 * 1024];
            int at = 0;
            for (int n; (n = in.read(data)) != -1;) {
                for (int i = 0; i < n; i++) {
                    if (data[i] != sample(at + i)) throw new AssertionError("wrong byte at " + (at + i));
                }
                at += n;
            }
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}

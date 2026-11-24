package app.aikobrowser;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verified byte ranges written directly into a single staging file. */
final class RangeDownload {
    static final int MAX_LANES = 128;
    private static final long MIN_SEGMENT_BYTES = 1L << 20;
    private static final long CHECKPOINT_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final int MAX_RANGE_RETRIES = 2;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final int CHECKPOINT_FORMAT = 2;

    static final class Segment {
        final long start, end; // End is exclusive. -1 means unknown length.
        volatile long next, bytesPerSecond;
        volatile boolean active, retrying;

        Segment(long start, long end) {
            this.start = this.next = start;
            this.end = end;
        }
    }

    static final class Changed extends IOException {
        Changed() { super("服务器文件已变化，已丢弃旧分段"); }
    }

    private static final class RetryableResponse extends IOException {
        RetryableResponse(int code) { super("HTTP " + code); }
    }

    final URL url;
    final File part;
    final int requested;
    private final int originalRequested;
    private final int maxConcurrency;
    final Control control = new Control();
    private final File checkpoint;
    private final String userAgent, cookie;
    private final TransferPacer pacer;
    private final Runnable workerSetup;
    volatile List<Segment> segments = Collections.emptyList();
    volatile long total = -1;
    volatile boolean resumable;
    volatile String mode = "检查服务器";
    private String etag, sourceId;
    private URL source;

    static int clampLanes(int lanes) {
        return Math.min(MAX_LANES, Math.max(1, lanes));
    }

    RangeDownload(URL url, File part, int requested, String userAgent, String cookie) {
        this(url, part, requested, MAX_LANES, userAgent, cookie, new TransferPacer(), () -> {});
    }

    RangeDownload(URL url, File part, int requested, String userAgent, String cookie,
                  TransferPacer pacer, Runnable workerSetup) {
        this(url, part, requested, MAX_LANES, userAgent, cookie, pacer, workerSetup);
    }

    RangeDownload(URL url, File part, int requested, int maxLanesLimit, String userAgent, String cookie,
                  TransferPacer pacer, Runnable workerSetup) {
        int cap = clampLanes(maxLanesLimit);
        this.url = url;
        this.part = part;
        this.originalRequested = clampLanes(requested);
        this.maxConcurrency = cap;
        this.requested = Math.min(cap, this.originalRequested);
        this.userAgent = userAgent;
        this.cookie = cookie;
        this.pacer = pacer;
        this.workerSetup = workerSetup;
        control.attachPacer(pacer);
        checkpoint = new File(part.getPath() + ".state");
    }

    long downloaded() {
        long n = 0;
        for (Segment segment : segments) n += segment.next - segment.start;
        return n;
    }

    int activeLanes() {
        int n = 0;
        for (Segment segment : segments) if (segment.active) n++;
        return n;
    }

    boolean transfer() throws IOException {
        try {
            prepare();
            control.check();
            if (resumable) rangedTransfer();
            else singleTransfer();
            return true;
        } catch (IOException e) {
            if (e instanceof Changed) {
                discard();
                segments = Collections.emptyList();
                total = -1;
                resumable = false;
            }
            if (control.stopped) return false;
            throw e;
        }
    }

    void discard() throws IOException {
        Files.deleteIfExists(checkpoint.toPath());
        Files.deleteIfExists(draftFile(checkpoint).toPath());
        Files.deleteIfExists(part.toPath());
    }

    private void prepare() throws IOException {
        HttpURLConnection probe = open("bytes=0-0", null);
        try {
            int code = probe.getResponseCode();
            long[] range = parseRange(probe.getHeaderField("Content-Range"));
            etag = probe.getHeaderField("ETag");
            resumable = code == 206 && range != null && range[0] == 0 && range[1] == 1
                    && strongTag(etag) && identity(probe);
            total = resumable ? range[2] : code == 200 ? probe.getContentLengthLong() : -1;
            if (code != 200 && code != 206 && code != 416) throw new IOException("HTTP " + code);
            if (!resumable) {
                Files.deleteIfExists(checkpoint.toPath());
                segments = Collections.singletonList(new Segment(0, total));
                mode = "单连接 · 未验证安全分段";
                return;
            }
            source = probe.getURL();
            sourceId = sourceId(source);
        } finally {
            close(probe);
        }
        int count = (int) Math.min(requested, Math.max(1, total / MIN_SEGMENT_BYTES));
        int origCount = (int) Math.min(originalRequested, Math.max(1, total / MIN_SEGMENT_BYTES));
        SavedRanges recovered = readSavedRanges();
        if (recovered != null && recovered.source.equals(sourceId) && recovered.etag.equals(etag)
                && recovered.total == total
                && (recovered.segments.size() == count || recovered.segments.size() == origCount)) {
            segments = recovered.segments;
        } else {
            Files.deleteIfExists(checkpoint.toPath());
            List<Segment> planned = new ArrayList<>(count);
            long base = total / count, tail = total % count, start = 0;
            for (int i = 0; i < count; i++) {
                long end = start + base + (i < tail ? 1 : 0);
                planned.add(new Segment(start, end));
                start = end;
            }
            segments = Collections.unmodifiableList(planned);
        }
        mode = count > 1 ? "并行分段 · 可续传" : "单连接 · 可续传";
    }

    private void rangedTransfer() throws IOException {
        int poolSize = Math.max(1, Math.min(segments.size(), maxConcurrency));
        ExecutorService workers = Executors.newFixedThreadPool(poolSize, runnable ->
                new Thread(() -> {
                    workerSetup.run();
                    runnable.run();
                }, "aiko-download-range"));
        AtomicReference<IOException> failure = new AtomicReference<>();
        Semaphore retrySlots = new Semaphore(4);
        try (RandomAccessFile file = new RandomAccessFile(part, "rw")) {
            file.setLength(total);
            FileChannel channel = file.getChannel();
            CompletionService<Void> tasks = new ExecutorCompletionService<>(workers);
            int remaining = 0;
            long saved = downloaded();
            long deadline = System.nanoTime() + CHECKPOINT_NANOS;
            try {
                for (Segment s : segments) {
                    if (s.next == s.end) continue;
                    tasks.submit(() -> {
                        if (failure.get() != null || control.stopped) return null;
                        try {
                            copyRange(s, channel, failure, retrySlots);
                        } catch (IOException e) {
                            if (e instanceof Changed) {
                                if (failure.getAndSet(e) == null) control.interruptConnections();
                            } else if (!control.stopped && failure.compareAndSet(null, e)) {
                                control.interruptConnections();
                            }
                        }
                        return null;
                    });
                    remaining++;
                }
                while (remaining > 0) {
                    try {
                        Future<Void> completed = tasks.poll(Math.max(1, deadline - System.nanoTime()),
                                TimeUnit.NANOSECONDS);
                        if (completed != null) {
                            remaining--;
                            completed.get();
                        }
                        if (remaining > 0 && System.nanoTime() - deadline >= 0) {
                            if (failure.get() == null && !control.stopped && downloaded() != saved) {
                                if (pacer.yielding()) {
                                    deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
                                    continue;
                                }
                                try {
                                    saved = save(channel);
                                } catch (IOException error) {
                                    if (failure.compareAndSet(null, error)) control.interruptConnections();
                                }
                            }
                            deadline = System.nanoTime() + CHECKPOINT_NANOS;
                        }
                    } catch (ExecutionException e) {
                        failure.compareAndSet(null, new IOException(e.getCause()));
                        control.interruptConnections();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("下载被中断", e);
                    }
                }
                IOException error = failure.get();
                if (!(error instanceof Changed)) save(channel);
                if (error != null) throw error;
                control.check();
            } finally {
                workers.shutdownNow();
            }
        }
    }

    private void copyRange(Segment segment, FileChannel channel, AtomicReference<IOException> failure,
                           Semaphore retrySlots) throws IOException {
        try {
            copy(segment, channel, true);
            return;
        } catch (RetryableResponse | EOFException | SocketException | SocketTimeoutException initial) {
            if (failure.get() != null || control.stopped) throw initial;
        }
        segment.retrying = true;
        boolean acquired = false;
        try {
            while (!acquired) {
                checkRetry(failure);
                acquired = retrySlots.tryAcquire(50, TimeUnit.MILLISECONDS);
            }
            for (int attempt = 0; attempt < MAX_RANGE_RETRIES; attempt++) {
                int delay = (180 << attempt) + ThreadLocalRandom.current().nextInt(60);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
                while (System.nanoTime() - deadline < 0) {
                    checkRetry(failure);
                    Thread.sleep(30);
                }
                checkRetry(failure);
                try {
                    copy(segment, channel, true);
                    return;
                } catch (RetryableResponse | EOFException | SocketException | SocketTimeoutException error) {
                    if (attempt + 1 == MAX_RANGE_RETRIES) throw error;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("重试被中断");
        } finally {
            if (acquired) retrySlots.release();
            segment.retrying = false;
        }
    }

    private void checkRetry(AtomicReference<IOException> failure) throws IOException {
        control.check();
        if (failure.get() != null) throw new IOException("下载任务已停止");
    }

    private void singleTransfer() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(part, "rw")) {
            file.setLength(0);
            copy(segments.get(0), file.getChannel(), false);
            file.getChannel().force(true);
        }
    }

    private void copy(Segment s, FileChannel channel, boolean ranged) throws IOException {
        long start = s.next;
        HttpURLConnection connection = open(ranged ? "bytes=" + start + "-" + (s.end - 1) : null,
                ranged ? etag : null);
        s.active = true;
        try {
            int code = connection.getResponseCode();
            if (ranged) {
                if (code == 200 || code == 412 || code == 416) throw new Changed();
                if (code != 206) {
                    if ((code == 408 || code == 502 || code == 503 || code == 504)
                            && connection.getHeaderField("Retry-After") == null) throw new RetryableResponse(code);
                    throw new IOException("HTTP " + code);
                }
                long[] r = parseRange(connection.getHeaderField("Content-Range"));
                if (r == null || r[0] != start || r[1] != s.end || r[2] != total
                        || !sameOrigin(source, connection.getURL())
                        || !source.getFile().equals(connection.getURL().getFile())
                        || !etag.equals(connection.getHeaderField("ETag")) || !identity(connection)) {
                    throw new Changed();
                }
            } else {
                if (code != 200) throw new IOException("HTTP " + code);
                total = identity(connection) ? connection.getContentLengthLong() : -1;
                if (total != s.end) {
                    s.active = false;
                    s = new Segment(0, total);
                    segments = Collections.singletonList(s);
                    s.active = true;
                }
            }
            int bufSize = segments.size() <= 16 ? 256 * 1024 : 128 * 1024;
            if (s.end >= 0L) {
                long spanLeft = s.end - s.next;
                if (spanLeft > 0L && spanLeft < bufSize) bufSize = (int) Math.max(8192L, spanLeft);
            }
            byte[] bytes = new byte[bufSize];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            int buffered = 0;
            long lastFlush = System.nanoTime();
            long lastTime = lastFlush, lastOffset = start;
            try (InputStream input = connection.getInputStream()) {
                boolean completedCleanly = false;
                try {
                    while (s.end < 0 || s.next + buffered < s.end) {
                        if (pacer.yielding() || control.stopped) {
                            if (buffered > 0) {
                                flushSegment(s, channel, buffer, buffered);
                                buffered = 0;
                                lastFlush = System.nanoTime();
                            }
                            pacer.await(control);
                        }
                        int free = bytes.length - buffered;
                        int limit = s.end < 0L ? free : (int) Math.min(free, s.end - (s.next + buffered));
                        if (limit <= 0) break;
                        int n = input.read(bytes, buffered, limit);
                        if (n < 0) break;
                        buffered += n;
                        long now = System.nanoTime();
                        boolean segmentDone = s.end >= 0L && s.next + buffered >= s.end;
                        if (buffered >= bytes.length || segmentDone || pacer.yielding() || control.stopped
                                || now - lastFlush >= 250_000_000L) {
                            long pos = flushSegment(s, channel, buffer, buffered);
                            buffered = 0;
                            lastFlush = now;
                            if (now - lastTime >= 300_000_000L) {
                                s.bytesPerSecond = (long) ((pos - lastOffset) * 1e9 / (now - lastTime));
                                lastOffset = pos;
                                lastTime = now;
                            }
                        }
                    }
                    if (buffered > 0) {
                        flushSegment(s, channel, buffer, buffered);
                        buffered = 0;
                    }
                    completedCleanly = true;
                } finally {
                    if (!completedCleanly && buffered > 0) {
                        try { flushSegment(s, channel, buffer, buffered); }
                        catch (IOException ignored) { }
                    }
                }
            }
            if (s.end >= 0 && s.next != s.end) throw new EOFException("文件分段接收不完整");
            if (!ranged && total < 0) total = s.next;
        } finally {
            s.active = false;
            s.bytesPerSecond = 0;
            close(connection);
        }
    }

    private static long flushSegment(Segment s, FileChannel channel, ByteBuffer buffer, int length) throws IOException {
        long pos = s.next;
        buffer.position(0);
        buffer.limit(length);
        while (buffer.hasRemaining()) pos += channel.write(buffer, pos);
        s.next = pos;
        return pos;
    }

    private HttpURLConnection open(String range, String condition) throws IOException {
        URL current = url;
        for (int redirects = 0; redirects <= 6; redirects++) {
            pacer.await(control);
            requireHttp(current);
            HttpURLConnection c = (HttpURLConnection) current.openConnection();
            c.setConnectTimeout(12_000);
            c.setReadTimeout(18_000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (userAgent != null && !userAgent.isEmpty()) c.setRequestProperty("User-Agent", userAgent);
            if (cookie != null && !cookie.isEmpty() && sameOrigin(url, current)) c.setRequestProperty("Cookie", cookie);
            if (range != null) c.setRequestProperty("Range", range);
            if (condition != null) c.setRequestProperty("If-Range", condition);
            control.connections.add(c);
            try {
                control.check();
                int code = c.getResponseCode();
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) return c;
                String location = c.getHeaderField("Location");
                if (location == null) throw new IOException("下载跳转地址无效");
                URL next = new URL(current, location);
                if (current.getProtocol().equalsIgnoreCase("https") && !next.getProtocol().equalsIgnoreCase("https"))
                    throw new IOException("下载链接尝试降级为非 HTTPS");
                current = next;
                close(c);
            } catch (IOException | RuntimeException e) {
                close(c);
                throw e;
            }
        }
        throw new IOException("下载重定向次数过多");
    }

    static void requireHttp(URL url) throws IOException {
        if ((!url.getProtocol().equalsIgnoreCase("http") && !url.getProtocol().equalsIgnoreCase("https"))
                || url.getHost().isEmpty() || url.getUserInfo() != null) {
            throw new IOException("只接受 HTTP 或 HTTPS 文件链接");
        }
    }

    private static boolean sameOrigin(URL a, URL b) {
        return a.getProtocol().equalsIgnoreCase(b.getProtocol()) && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    private static int port(URL url) { return url.getPort() < 0 ? url.getDefaultPort() : url.getPort(); }

    private static String sourceId(URL url) {
        try {
            String value = url.getProtocol() + "://" + url.getHost() + ":" + port(url) + url.getFile();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean strongTag(String tag) {
        return tag != null && tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"");
    }

    private static boolean identity(HttpURLConnection c) {
        String encoding = c.getContentEncoding();
        return encoding == null || "identity".equalsIgnoreCase(encoding);
    }

    private static long[] parseRange(String header) {
        Matcher m = CONTENT_RANGE.matcher(header == null ? "" : header);
        try {
            if (m.matches()) {
                long start = Long.parseLong(m.group(1)), last = Long.parseLong(m.group(2));
                long size = Long.parseLong(m.group(3));
                if (start <= last && last < size) return new long[]{start, last + 1, size};
            }
        } catch (NumberFormatException ignored) { }
        return null;
    }

    void showSavedProgress() {
        SavedRanges saved = readSavedRanges();
        if (saved == null) return;
        total = saved.total;
        segments = saved.segments;
        resumable = true;
        mode = "已保存的分段";
    }

    private static final class SavedRanges {
        final String source, etag;
        final long total;
        final List<Segment> segments;

        SavedRanges(String source, String etag, long total, List<Segment> segments) {
            this.source = source;
            this.etag = etag;
            this.total = total;
            this.segments = Collections.unmodifiableList(segments);
        }
    }

    private SavedRanges readSavedRanges() {
        if (!part.isFile() || !checkpoint.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(checkpoint)))) {
            if (in.readInt() != CHECKPOINT_FORMAT) return null;
            String savedSource = in.readUTF(), savedTag = in.readUTF();
            long savedTotal = in.readLong();
            if (!strongTag(savedTag) || savedTotal <= 0 || part.length() != savedTotal) return null;
            int count = in.readInt();
            if (count < 1 || count > MAX_LANES) return null;
            List<Segment> loaded = new ArrayList<>(count);
            long start = 0;
            for (int i = 0; i < count; i++) {
                long end = in.readLong(), next = in.readLong();
                if (end <= start || end > savedTotal || next < start || next > end) return null;
                Segment s = new Segment(start, end);
                s.next = next;
                loaded.add(s);
                start = end;
            }
            return start == savedTotal ? new SavedRanges(savedSource, savedTag, savedTotal, loaded) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private long save(FileChannel channel) throws IOException {
        // Capture offsets before syncing; the checkpoint must never refer to newer buffered writes.
        long[] offsets = new long[segments.size()];
        long bytes = 0;
        for (int i = 0; i < offsets.length; i++) {
            Segment s = segments.get(i);
            offsets[i] = s.next;
            bytes += offsets[i] - s.start;
        }
        channel.force(false);
        File draft = draftFile(checkpoint);
        try (FileOutputStream file = new FileOutputStream(draft);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(file))) {
            out.writeInt(CHECKPOINT_FORMAT);
            out.writeUTF(sourceId);
            out.writeUTF(etag);
            out.writeLong(total);
            out.writeInt(segments.size());
            for (int i = 0; i < offsets.length; i++) {
                out.writeLong(segments.get(i).end);
                out.writeLong(offsets[i]);
            }
            out.flush();
            file.getFD().sync();
        }
        Files.move(draft.toPath(), checkpoint.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return bytes;
    }

    private static File draftFile(File file) { return new File(file.getPath() + ".tmp"); }

    private void close(HttpURLConnection connection) {
        control.connections.remove(connection);
        connection.disconnect();
    }

    static final class Control {
        private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
        private volatile TransferPacer pacer;
        volatile boolean stopped;
        void attachPacer(TransferPacer target) { pacer = target; }
        void stop() { stopped = true; interruptConnections(); }
        void check() throws InterruptedIOException {
            if (stopped || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已暂停");
        }
        void interruptConnections() {
            TransferPacer target = pacer;
            if (target != null) target.wake();
            for (HttpURLConnection c : connections) c.disconnect();
        }
    }
}

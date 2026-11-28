package app.aikobrowser;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.webkit.CookieManager;

import java.io.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

public final class DownloadService extends Service {
    private static final String CHANNEL = "aiko_transfers";
    private static final String RUN = "app.aikobrowser.download.RUN";
    private static final String PAUSE = "app.aikobrowser.download.PAUSE";
    private static final int NOTIFICATION_ID = 7;
    private static final long PUBLISH_CHUNK_BYTES = 2L * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(() -> {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
            catch (SecurityException ignored) { }
            task.run();
        }, "aiko-download-io");
        thread.setDaemon(true);
        return thread;
    });
    static final TransferPacer PACER = new TransferPacer();
    private static volatile int maxLanesLimit = RangeDownload.MAX_LANES;
    static volatile Job current;

    /** Sets the performance-profile cap on concurrent download lanes (clamped 1..128). */
    public static void setMaxLanesLimit(int maxLanes) {
        maxLanesLimit = RangeDownload.clampLanes(maxLanes);
    }

    public static int getMaxLanesLimit() {
        return maxLanesLimit;
    }

    static final class Job {
        final String id, name, userAgent;
        final int lanes;
        final URL url;
        final File part;
        volatile RangeDownload transfer;
        volatile String state = "等待启动";
        volatile boolean busy, saving, finished, cancelled, pauseRequested;
        boolean executing; // metadata executor only
        volatile Uri output;
        volatile DownloadRecord record;

        Job(DownloadQueue.Entry entry, File folder) {
            id = entry.id;
            DownloadRecord record = entry.record;
            this.record = record;
            this.url = record.url;
            this.name = record.name;
            this.userAgent = record.userAgent;
            this.lanes = record.lanes;
            this.part = entry.part(folder);
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
        catch (SecurityException ignored) { }
        task.run();
    }, "aiko-download-worker"));
    private volatile boolean alive = true;
    private volatile int lastStartId;
    private HandlerThread notifThread;
    private Handler notifHandler;
    private NotificationManager notificationManager;
    private PendingIntent openIntent, pauseIntent;
    private String pauseId;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            Job job = current;
            if (job == null || !job.busy) return;
            NotificationManager nm = notificationManager;
            if (nm != null) nm.notify(NOTIFICATION_ID, notification(job));
            Handler nh = notifHandler;
            if (nh != null) nh.postDelayed(this, 1000);
        }
    };

    // Queue and dispatch decisions are confined to IO. The network worker never holds up submissions.
    private static DownloadQueue queue;
    static volatile List<DownloadQueue.Entry> pending = Collections.emptyList(), history = Collections.emptyList();

    private static void load(Context app) throws IOException {
        if (queue != null) return;
        DownloadQueue restored = new DownloadQueue(new File(app.getFilesDir(), "transfers"));
        DownloadQueue.Entry head = restored.head();
        if (head != null && head.record.phase == DownloadRecord.PUBLISHING) {
            // Recovery also handles old builds killed between MediaStore.update and the local commit.
            try {
                if (publicationState(app.getContentResolver(), Uri.parse(head.record.output)) == 0) {
                    restored.complete(head.id);
                    discardPart(head, restored.folder);
                }
            } catch (IOException e) {
                // Keep the URI attached. A manual retry queries the same row before writing anything.
                restored.setRunning(false);
            }
        }
        queue = restored;
        refresh();
    }

    private static void refresh() {
        pending = queue.pending();
        history = queue.history();
        DownloadQueue.Entry entry = queue.head();
        if (entry == null && !history.isEmpty()) entry = history.get(0);
        if (entry == null) { current = null; return; }
        Job job = current;
        if (job == null || !job.id.equals(entry.id)) {
            job = new Job(entry, queue.folder);
            if (entry.record.phase != DownloadRecord.COMPLETE) {
                job.transfer = new RangeDownload(job.url, job.part, job.lanes, job.userAgent, null);
                job.transfer.showSavedProgress();
                job.state = job.transfer.resumable ? "已暂停 · 可续传" : "等待继续";
            }
        }
        job.record = entry.record;
        if (entry.record.phase == DownloadRecord.COMPLETE) {
            job.output = Uri.parse(entry.record.output);
            job.finished = true;
            job.state = "已完成 · 下载目录";
        }
        current = job;
    }

    private interface Command { void run() throws IOException; }

    private static void command(Context context, Command action) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try { load(app); action.run(); }
            catch (IOException | RuntimeException e) {
                String msg = errorMessage("下载队列操作失败", e);
                MAIN.post(() -> android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    static void start(Context context, String address, String name, String ua, int lanes, Runnable onQueued)
            throws IOException {
        URL url = new URL(address);
        RangeDownload.requireHttp(url);
        DownloadRecord record = new DownloadRecord(url, safeName(name), ua, RangeDownload.clampLanes(lanes));
        Context app = context.getApplicationContext();
        command(app, () -> {
            queue.enqueue(record);
            refresh();
            MAIN.post(onQueued);
            requestRun(app);
        });
    }

    static void restore(Context context) {
        Context app = context.getApplicationContext();
        command(app, () -> requestRun(app));
    }

    static void launch(Context context, Job job) {
        Context app = context.getApplicationContext();
        command(app, () -> {
            if (job != current || job.busy || job.finished) return;
            queue.setRunning(true);
            requestRun(app);
        });
    }

    private static void requestRun(Context app) throws IOException {
        Job job = current;
        if (!queue.running() || queue.head() == null || job.busy) return;
        job.busy = true;
        job.state = "准备下载";
        try {
            app.startForegroundService(new Intent(app, DownloadService.class).setAction(RUN));
        } catch (RuntimeException e) {
            job.busy = false;
            job.state = "无法启动后台下载 · 点继续重试";
            queue.setRunning(false);
            throw new IOException(job.state, e);
        }
    }

    static void pause(Context context, Job job) {
        command(context, () -> {
            if (job != current || job == null || !job.busy || job.saving || job.finished) return;
            queue.setRunning(false);
            stopTransfer(job);
        });
    }

    private static void stopTransfer(Job job) {
        job.pauseRequested = true;
        job.state = "正在暂停";
        RangeDownload transfer = job.transfer;
        if (transfer != null) {
            transfer.control.stopped = true;
            new Thread(transfer.control::interruptConnections, "aiko-download-stop").start();
        }
        PACER.wake();
    }

    static void dropWaiting(Context context, String id) {
        command(context, () -> {
            if (!queue.dropWaiting(id)) throw new IOException("任务已移出等待队列");
            refresh();
        });
    }

    static void cancel(Context context, Job job) {
        Context app = context.getApplicationContext();
        command(app, () -> {
            if (job != current || job.finished || job.saving || job.cancelled) return;
            queue.setRunning(false);
            job.cancelled = true;
            if (job.executing) stopTransfer(job);
            else {
                try { discardJob(app, job); }
                finally { job.cancelled = false; job.busy = false; }
                refresh();
                requestRun(app);
            }
        });
    }

    // Transfer thread barriers for the two points at which publication must be serialized with UI actions.
    private static <T> T metadata(Callable<T> action) throws Exception {
        try { return IO.submit(action).get(); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "文件下载", NotificationManager.IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);
        openIntent = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class).putExtra("downloads", true)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notifThread = new HandlerThread("aiko-download-notif", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        notifThread.start();
        notifHandler = new Handler(notifThread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        startForeground(NOTIFICATION_ID, notification(current));
        notifHandler.removeCallbacks(update);
        notifHandler.postDelayed(update, 1000);
        if (intent != null && PAUSE.equals(intent.getAction())) {
            String requested = intent.getIdentifier();
            IO.execute(() -> {
                try {
                    load(this);
                    Job job = current;
                    if (job != null && job.id.equals(requested) && !job.finished && !job.saving) {
                        queue.setRunning(false);
                        if (job.executing) stopTransfer(job);
                    }
                    if (job == null || !job.executing) dispatch();
                } catch (IOException e) { stopIdle(); }
            });
            return START_STICKY;
        }
        IO.execute(() -> {
            try { load(this); dispatch(); }
            catch (Exception e) { stopIdle(); }
        });
        return START_STICKY;
    }

    private void dispatch() {
        Job job = current;
        if (job != null && job.executing) return;
        if (!alive || !queue.running() || queue.head() == null) {
            if (job != null && !job.executing) {
                job.busy = false;
                if (job.pauseRequested) job.state = "已暂停";
            }
            stopIdle();
            return;
        }
        job.executing = true;
        job.busy = true;
        job.pauseRequested = false;
        job.state = "准备下载";
        WORKER.execute(() -> {
            Exception failure = null;
            try { perform(job); } catch (Exception e) { failure = e; }
            Exception result = failure;
            IO.execute(() -> finish(job, result));
        });
    }

    private void finish(Job job, Exception error) {
        try {
            if (job.cancelled) {
                discardJob(this, job);
            } else if (job.output != null) {
                // Keep the persisted publishing URI until the cursor advances atomically with history.
                job.transfer.discard();
                queue.complete(job.id);
            } else {
                queue.setRunning(false);
                job.state = job.pauseRequested || error == null ? "已暂停" : errorMessage("下载失败", error);
            }
        } catch (Exception e) {
            job.state = errorMessage("下载队列更新失败", e);
            try { queue.setRunning(false); } catch (IOException ignored) { }
            // Even a full disk must not spin or retransfer a published file.
            resetWorker(job);
            stopIdle();
            return;
        }
        resetWorker(job);
        refresh();
        if (alive) dispatch();
        else {
            try { requestRun(getApplicationContext()); } catch (IOException ignored) { }
        }
    }

    private static void resetWorker(Job job) {
        job.executing = false;
        job.busy = false;
        job.saving = false;
        job.cancelled = false;
        job.pauseRequested = false;
    }

    private void stopIdle() {
        int id = lastStartId;
        MAIN.post(() -> {
            if (id == lastStartId && (current == null || !current.busy) && stopSelfResult(id)) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                if (notifHandler != null) notifHandler.removeCallbacks(update);
            }
        });
    }

    private static String errorMessage(String prefix, Exception error) {
        String msg = error.getMessage();
        return prefix + (msg == null || msg.contains("://") ? "" : " · " + msg);
    }

    private void perform(Job job) throws Exception {
        if (job.record.phase == DownloadRecord.PUBLISHING) {
            if (beginSaving(job)) job.output = publish(job);
            return;
        }
        String cookies = null;
        try { cookies = CookieManager.getInstance().getCookie(job.url.toExternalForm()); }
        catch (RuntimeException ignored) { }
        RangeDownload transfer = new RangeDownload(job.url, job.part, job.lanes, maxLanesLimit,
                job.userAgent, cookies, PACER, () -> {
                    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
                    catch (SecurityException ignored) { }
                });
        job.transfer = transfer;
        if (job.pauseRequested) return;
        job.state = "下载中";
        if (transfer.transfer() && beginSaving(job)) job.output = publish(job);
    }

    private static boolean beginSaving(Job job) throws Exception {
        return metadata(() -> {
            if (job.pauseRequested || job.cancelled) return false;
            job.saving = true;
            job.state = "正在保存到下载目录";
            return true;
        });
    }

    private Uri publish(Job job) throws Exception {
        ContentResolver resolver = getContentResolver();
        Uri uri = null;
        if (job.record.phase == DownloadRecord.PUBLISHING) {
            uri = Uri.parse(job.record.output);
            int state = publicationState(resolver, uri);
            if (state == 0) return uri;
            if (!job.part.isFile() || job.part.length() != job.record.size) {
                throw new IOException("待保存文件不完整，请取消后重新下载");
            }
            if (state == -1) uri = null; // Confirmed absent. Query failures must never create another row.
        }
        if (uri == null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, job.name);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Aiko Browser");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri inserted = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (inserted == null) throw new IOException("无法写入下载目录");
            try {
                metadata(() -> {
                    queue.publishing(job.id, inserted.toString(), job.part.length());
                    job.record = queue.head().record;
                    return null;
                });
            } catch (Exception error) {
                resolver.delete(inserted, null, null);
                throw error;
            }
            uri = inserted;
        }
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rwt");
        if (descriptor == null) throw new IOException("无法打开下载文件");
        try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
             FileInputStream in = new FileInputStream(job.part)) {
            copyPublished(in.getChannel(), out.getChannel());
            if (out.getChannel().size() != job.record.size) throw new IOException("下载文件写入不完整");
            out.getFD().sync();
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (resolver.update(uri, values, null, null) != 1) throw new IOException("无法发布下载文件");
        return uri;
    }

    private static void copyPublished(FileChannel src, FileChannel dst) throws IOException {
        long size = src.size();
        long pos = 0;
        boolean useTransferTo = true;
        while (pos < size && useTransferTo) {
            PACER.awaitYieldQuietly();
            long count = Math.min(PUBLISH_CHUNK_BYTES, size - pos);
            try {
                long moved = src.transferTo(pos, count, dst);
                if (moved > 0) {
                    pos += moved;
                } else {
                    useTransferTo = false;
                }
            } catch (IOException unsupported) {
                if (pos == 0) {
                    useTransferTo = false;
                } else {
                    throw unsupported;
                }
            }
        }
        if (pos < size) {
            src.position(pos);
            dst.position(pos);
            ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
            long sinceYield = 0;
            while (pos < size) {
                if (sinceYield >= PUBLISH_CHUNK_BYTES) {
                    PACER.awaitYieldQuietly();
                    sinceYield = 0;
                }
                buffer.clear();
                int n = src.read(buffer);
                if (n < 0) break;
                buffer.flip();
                while (buffer.hasRemaining()) pos += dst.write(buffer);
                sinceYield += n;
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static int publicationState(ContentResolver resolver, Uri uri) throws IOException {
        String[] columns = {MediaStore.MediaColumns.IS_PENDING};
        Bundle pending = new Bundle();
        pending.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = Build.VERSION.SDK_INT >= 30
                ? resolver.query(uri, columns, pending, null)
                : resolver.query(MediaStore.setIncludePending(uri), columns, null, null, null)) {
            if (cursor == null) throw new IOException("无法确认下载文件状态");
            return cursor.moveToFirst() ? cursor.getInt(0) : -1;
        } catch (RuntimeException e) {
            throw new IOException("无法确认下载文件状态", e);
        }
    }

    private static void discardJob(Context context, Job job) throws IOException {
        if (job.record.phase == DownloadRecord.PUBLISHING) {
            Uri output = Uri.parse(job.record.output);
            ContentResolver resolver = context.getContentResolver();
            try {
                int state = publicationState(resolver, output);
                if (state == 0) {
                    // This explicit cancellation requests advancement, even when the queue was paused.
                    queue.setRunning(true);
                    queue.complete(job.id);
                    discardPart(new DownloadQueue.Entry(job.id, job.record), queue.folder);
                    return;
                }
                if (state == 1 && resolver.delete(output, null, null) != 1) throw new IOException("无法清理待发布文件");
            } catch (RuntimeException error) {
                throw new IOException("无法清理下载文件", error);
            }
        }
        if (job.transfer != null) job.transfer.discard();
        queue.cancelHead(job.id);
    }

    private static void discardPart(DownloadQueue.Entry entry, File folder) {
        DownloadRecord r = entry.record;
        try { new RangeDownload(r.url, entry.part(folder), r.lanes, r.userAgent, null).discard(); }
        catch (IOException ignored) { }
    }

    private synchronized Notification notification(Job job) {
        RangeDownload download = job == null ? null : job.transfer;
        long total = download == null ? 0 : download.total;
        int percent = total > 0 ? (int) Math.min(100, 100.0 * download.downloaded() / total) : 0;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(job == null ? "下载队列" : job.name).setContentText(job == null ? "恢复下载" : job.state)
                .setContentIntent(openIntent).setProgress(100, percent, total <= 0).setOnlyAlertOnce(true)
                .setOngoing(true);
        if (job != null && !job.saving && !job.finished) {
            if (!job.id.equals(pauseId)) {
                pauseId = job.id;
                pauseIntent = PendingIntent.getService(this, 2, new Intent(this, DownloadService.class)
                        .setAction(PAUSE).setIdentifier(job.id), PendingIntent.FLAG_IMMUTABLE);
            }
            builder.addAction(new Notification.Action.Builder(null, "暂停", pauseIntent).build());
        }
        return builder.build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onTimeout(int startId, int fgsType) {
        alive = false;
        command(this, () -> {
            queue.setRunning(false);
            Job job = current;
            if (job != null && job.executing && !job.saving) stopTransfer(job);
        });
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        Handler nh = notifHandler;
        if (nh != null) nh.removeCallbacks(update);
        if (notifThread != null) {
            notifThread.quitSafely();
            notifThread = null;
            notifHandler = null;
        }
        alive = false;
        super.onDestroy();
    }

    private static String safeName(String name) {
        String clean = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) clean = "download";
        if (clean.length() > 130) clean = clean.substring(0, 130);
        return clean;
    }
}

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DownloadService extends Service {
    private static final String CHANNEL = "aiko_transfers";
    private static final String RUN = "app.aikobrowser.download.RUN";
    private static final String PAUSE = "app.aikobrowser.download.PAUSE";
    private static final int NOTIFICATION_ID = 7;
    private static final long PUBLISH_CHUNK_BYTES = 2L * 1024 * 1024;
    // LOAD_LOCK guards only in-memory `current` transitions (never held across disk/IPC I/O).
    private static final Object LOAD_LOCK = new Object();
    private static final Object DISK_LOAD_LOCK = new Object();
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
        final String name, userAgent;
        final int lanes;
        final URL url;
        final File part;
        volatile RangeDownload transfer;
        volatile String state = "等待启动";
        volatile boolean busy, saving, finished, cancelled, pauseRequested, needsRecordWrite;
        volatile Uri output;
        volatile DownloadRecord record;

        Job(DownloadRecord record, File part) {
            this.record = record;
            this.url = record.url;
            this.name = record.name;
            this.userAgent = record.userAgent;
            this.lanes = record.lanes;
            this.part = part;
        }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
        catch (SecurityException ignored) { }
        task.run();
    }, "aiko-download-worker"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private HandlerThread notifThread;
    private Handler notifHandler;
    private NotificationManager notificationManager;
    private PendingIntent openIntent, pauseIntent;
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

    static Job start(Context context, String address, String name, String ua, int lanes)
            throws Exception {
        URL url = new URL(address);
        RangeDownload.requireHttp(url);
        Context app = context.getApplicationContext();
        File folder = new File(app.getFilesDir(), "transfers");
        int effectiveLanes = Math.min(RangeDownload.clampLanes(lanes), maxLanesLimit);
        DownloadRecord record = new DownloadRecord(url, safeName(name), ua, effectiveLanes);
        Job job = new Job(record, partFile(folder, url));
        job.needsRecordWrite = true;
        job.state = "准备下载";
        job.busy = true;
        Job previous;
        synchronized (LOAD_LOCK) {
            previous = current;
            if (previous != null && (previous.busy || (!previous.finished && !previous.cancelled))) {
                throw new IOException("请先完成或取消当前任务");
            }
            current = job;
        }
        try {
            context.startForegroundService(new Intent(context, DownloadService.class).setAction(RUN));
        } catch (RuntimeException e) {
            job.busy = false;
            job.state = "无法启动后台下载";
            synchronized (LOAD_LOCK) {
                if (current == job) current = previous;
            }
            throw e;
        }
        return job;
    }

    static void loadCurrentAsync(Context context, Runnable onReady) {
        if (current != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) onReady.run();
            else MAIN.post(onReady);
            return;
        }
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            loadCurrent(app);
            MAIN.post(onReady);
        });
    }

    static Job loadCurrent(Context context) {
        Job existing = current;
        if (existing != null) return existing;
        synchronized (DISK_LOAD_LOCK) {
            existing = current;
            if (existing != null) return existing;
            File folder = new File(context.getFilesDir(), "transfers");
            DownloadRecord record = DownloadRecord.read(folder);
            if (record == null) return current;
            Job job = new Job(record, partFile(folder, record.url));
            job.transfer = new RangeDownload(job.url, job.part, job.lanes, job.userAgent, null);
            job.transfer.showSavedProgress();
            if (record.phase != DownloadRecord.TRANSFERRING) {
                Uri output = Uri.parse(record.output);
                if (record.phase == DownloadRecord.COMPLETE || isPublished(context.getContentResolver(), output)) {
                    job.output = output;
                    job.finished = true;
                    job.state = "已完成 · 下载目录";
                }
            }
            if (!job.finished) {
                job.state = job.transfer.resumable ? "已恢复任务 · 可续传"
                        : record.phase == DownloadRecord.PUBLISHING ? "写入中断 · 点继续保存" : "已恢复任务 · 等待继续";
            }
            synchronized (LOAD_LOCK) {
                if (current != null) return current;
                current = job;
                return job;
            }
        }
    }

    private static File partFile(File folder, URL url) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(url.toExternalForm().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
        StringBuilder key = new StringBuilder(64);
        for (byte b : hash) {
            key.append(Character.forDigit((b & 255) >>> 4, 16));
            key.append(Character.forDigit(b & 15, 16));
        }
        return new File(folder, key + ".part");
    }

    static void launch(Context context, Job job) {
        synchronized (LOAD_LOCK) {
            if (job.busy || job.finished || job.cancelled) return;
            job.pauseRequested = false;
            job.state = "准备下载";
            job.busy = true;
            current = job;
        }
        try {
            context.startForegroundService(new Intent(context, DownloadService.class).setAction(RUN));
        } catch (RuntimeException e) {
            job.busy = false;
            job.state = "无法启动后台下载";
            throw e;
        }
    }

    static void pause(Job job) {
        if (job == null || !job.busy || job.saving) return;
        job.pauseRequested = true;
        job.state = "正在暂停";
        RangeDownload transfer = job.transfer;
        if (transfer != null) {
            RangeDownload.Control control = transfer.control;
            control.stopped = true;
            // Some HTTP implementations can block in disconnect; keep it off the UI thread.
            new Thread(() -> {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
                catch (SecurityException ignored) { }
                control.interruptConnections();
            }, "aiko-download-stop").start();
        }
        PACER.wake();
    }

    static void cancel(Context context, Job job) {
        if (job == null || job.finished || job.saving) return;
        job.cancelled = true;
        if (job.busy) {
            pause(job);
        } else {
            job.state = "已取消";
            job.busy = true;
            Context app = context.getApplicationContext();
            new Thread(() -> {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
                catch (SecurityException ignored) { }
                try { discardJob(app, job); }
                catch (IOException error) { job.state = "清理失败"; job.cancelled = false; }
                finally { job.busy = false; }
            }, "aiko-download-discard").start();
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
        pauseIntent = PendingIntent.getService(this, 2,
                new Intent(this, DownloadService.class).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE);
        notifThread = new HandlerThread("aiko-download-notif", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        notifThread.start();
        notifHandler = new Handler(notifThread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Job job = current;
        if (intent != null && PAUSE.equals(intent.getAction()) && job != null) {
            pause(job);
            return START_NOT_STICKY;
        }
        if (intent == null || !RUN.equals(intent.getAction()) || job == null || !job.busy) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification(job));
        Handler nh = notifHandler;
        if (nh != null) {
            nh.removeCallbacks(update);
            nh.postDelayed(update, 1000);
        }
        worker.execute(() -> {
            perform(job);
            handler.post(() -> {
                Handler bg = notifHandler;
                if (bg != null) bg.removeCallbacks(update);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelfResult(startId);
                job.busy = false;
            });
        });
        return START_NOT_STICKY;
    }

    private void perform(Job job) {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
        catch (SecurityException ignored) { }
        try {
            if (job.pauseRequested || job.cancelled) {
                job.state = "已暂停";
                return;
            }
            if (job.needsRecordWrite) {
                File folder = job.part.getParentFile();
                if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
                    throw new IOException("无法创建下载目录");
                }
                if (folder != null) {
                    DownloadRecord previous = DownloadRecord.read(folder);
                    if (previous != null) {
                        File oldPart = partFile(folder, previous.url);
                        if (!oldPart.equals(job.part)) {
                            try {
                                new RangeDownload(previous.url, oldPart, previous.lanes,
                                        previous.userAgent, null).discard();
                            } catch (IOException ignored) { }
                        }
                    }
                    job.record.write(folder);
                }
                job.needsRecordWrite = false;
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
            if (job.pauseRequested || job.cancelled) {
                transfer.control.stopped = true;
                job.state = "已暂停";
                return;
            }
            job.state = "下载中";
            boolean staged = job.record.phase == DownloadRecord.PUBLISHING
                    && job.part.isFile() && job.part.length() == job.record.size;
            if (!staged && job.record.phase == DownloadRecord.PUBLISHING) {
                throw new IOException("待保存文件不完整，请取消后重新下载");
            }
            if (staged || transfer.transfer()) {
                job.saving = true;
                if (transfer.control.stopped || job.pauseRequested || job.cancelled) {
                    job.state = "已暂停";
                    return;
                }
                job.state = "正在保存到下载目录";
                job.output = publish(job);
                job.finished = true;
                job.state = "已完成 · 下载目录";
                try { transfer.discard(); } catch (IOException ignored) { }
            } else {
                job.state = "已暂停";
            }
        } catch (Exception e) {
            String message = e.getMessage();
            job.state = "下载失败" + (message == null || message.contains("://") ? "" : " · " + message);
        } finally {
            job.saving = false;
            if (job.cancelled) {
                try {
                    discardJob(this, job);
                    job.state = "已取消";
                } catch (IOException error) {
                    job.state = "清理失败";
                    job.cancelled = false;
                }
            }
        }
    }

    private Uri publish(Job job) throws IOException {
        ContentResolver resolver = getContentResolver();
        File folder = job.part.getParentFile();
        Uri uri = job.record.phase == DownloadRecord.PUBLISHING ? Uri.parse(job.record.output) : null;
        if (uri != null && isPublished(resolver, uri)) {
            completeRecord(job, folder, uri);
            return uri;
        }
        if (uri == null || publicationState(resolver, uri) != 1) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, job.name);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Aiko Browser");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("无法写入下载目录");
            DownloadRecord pending = job.record.publication(DownloadRecord.PUBLISHING, uri.toString(), job.part.length());
            try {
                pending.write(folder);
                job.record = pending;
            } catch (IOException error) {
                resolver.delete(uri, null, null);
                throw error;
            }
        }
        try {
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rwt");
            if (descriptor == null) throw new IOException("无法打开下载文件");
            try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
                 FileInputStream in = new FileInputStream(job.part)) {
                copyPublished(in.getChannel(), out.getChannel());
                out.getFD().sync();
            }
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) throw new IOException("无法发布下载文件");
            completeRecord(job, folder, uri);
            return uri;
        } catch (RuntimeException error) {
            throw new IOException("无法保存下载文件", error);
        }
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

    private static void completeRecord(Job job, File folder, Uri uri) {
        DownloadRecord complete = job.record.publication(DownloadRecord.COMPLETE, uri.toString(), job.part.length());
        try {
            complete.write(folder);
            job.record = complete;
        } catch (IOException ignored) {
            // A published item with a PUBLISHING record is detected on the next launch.
        }
    }

    private static boolean isPublished(ContentResolver resolver, Uri uri) {
        return publicationState(resolver, uri) == 0;
    }

    @SuppressWarnings("deprecation")
    private static int publicationState(ContentResolver resolver, Uri uri) {
        String[] columns = {MediaStore.MediaColumns.IS_PENDING};
        Bundle pending = new Bundle();
        pending.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        try (Cursor cursor = Build.VERSION.SDK_INT >= 30
                ? resolver.query(uri, columns, pending, null)
                : resolver.query(MediaStore.setIncludePending(uri), columns, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getInt(0) : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static void discardJob(Context context, Job job) throws IOException {
        if (job.record.phase == DownloadRecord.PUBLISHING) {
            Uri output = Uri.parse(job.record.output);
            ContentResolver resolver = context.getContentResolver();
            try {
                if (publicationState(resolver, output) == 1) resolver.delete(output, null, null);
            } catch (RuntimeException error) {
                throw new IOException("无法清理下载文件", error);
            }
        }
        if (job.transfer != null) job.transfer.discard();
        DownloadRecord.clear(job.part.getParentFile());
    }

    private Notification notification(Job job) {
        RangeDownload download = job.transfer;
        long total = download == null ? 0 : download.total;
        int percent = total > 0 ? (int) Math.min(100, 100.0 * download.downloaded() / total) : 0;
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(job.name).setContentText(job.state)
                .setContentIntent(openIntent).setProgress(100, percent, total <= 0).setOnlyAlertOnce(true)
                .setOngoing(true).addAction(new Notification.Action.Builder(null, "暂停", pauseIntent).build()).build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onTimeout(int startId, int fgsType) {
        pause(current);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    @Override public void onDestroy() {
        Handler nh = notifHandler;
        if (nh != null) nh.removeCallbacks(update);
        if (notifThread != null) {
            notifThread.quitSafely();
            notifThread = null;
            notifHandler = null;
        }
        worker.shutdown();
        super.onDestroy();
    }

    private static String safeName(String name) {
        String clean = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) clean = "download";
        if (clean.length() > 130) clean = clean.substring(0, 130);
        return clean;
    }
}

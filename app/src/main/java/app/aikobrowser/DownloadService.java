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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DownloadService extends Service {
    private static final String CHANNEL = "aiko_transfers";
    private static final String RUN = "app.aikobrowser.download.RUN";
    private static final String PAUSE = "app.aikobrowser.download.PAUSE";
    private static final int NOTIFICATION_ID = 7;
    static final TransferPacer PACER = new TransferPacer();
    static volatile Job current;

    static final class Job {
        final String name, userAgent;
        final int lanes;
        final URL url;
        final File part;
        volatile RangeDownload transfer;
        volatile String state = "等待启动";
        volatile boolean busy, saving, finished, cancelled;
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

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable update = new Runnable() {
        @Override public void run() {
            Job job = current;
            if (job == null || !job.busy) return;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(job));
            handler.postDelayed(this, 1000);
        }
    };

    static Job start(Context context, String address, String name, String ua, int lanes)
            throws Exception {
        Job previous = loadCurrent(context);
        if (previous != null && (previous.busy || (!previous.finished && !previous.cancelled))) {
            throw new IOException("请先完成或取消当前任务");
        }
        URL url = new URL(address);
        RangeDownload.requireHttp(url);
        File folder = new File(context.getFilesDir(), "transfers");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建下载目录");
        DownloadRecord record = new DownloadRecord(url, safeName(name), ua, lanes);
        Job job = new Job(record, partFile(folder, url));
        record.write(folder);
        current = job;
        launch(context, job);
        return job;
    }

    static Job loadCurrent(Context context) {
        if (current != null) return current;
        File folder = new File(context.getFilesDir(), "transfers");
        DownloadRecord record = DownloadRecord.read(folder);
        if (record == null) return null;
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
        current = job;
        return job;
    }

    private static File partFile(File folder, URL url) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(url.toExternalForm().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
        StringBuilder key = new StringBuilder();
        for (byte b : hash) {
            key.append(Character.forDigit((b & 255) >>> 4, 16));
            key.append(Character.forDigit(b & 15, 16));
        }
        return new File(folder, key + ".part");
    }

    static void launch(Context context, Job job) {
        if (job.busy || job.finished || job.cancelled) return;
        String cookies = CookieManager.getInstance().getCookie(job.url.toExternalForm());
        job.transfer = new RangeDownload(job.url, job.part, job.lanes, job.userAgent, cookies,
                PACER, () -> {
                    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
                    catch (SecurityException ignored) { }
                });
        job.state = "准备下载";
        job.busy = true;
        current = job;
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
        job.state = "正在暂停";
        RangeDownload.Control control = job.transfer.control;
        control.stopped = true;
        // Some HTTP implementations can block in disconnect; keep it off the UI thread.
        new Thread(control::interruptConnections, "aiko-download-stop").start();
    }

    static void cancel(Context context, Job job) {
        if (job == null || job.finished || job.saving) return;
        job.cancelled = true;
        if (job.busy) {
            pause(job);
        } else {
            job.state = "已取消";
            job.busy = true;
            new Thread(() -> {
                try { discardJob(context, job); }
                catch (IOException error) { job.state = "清理失败"; job.cancelled = false; }
                finally { job.busy = false; }
            }, "aiko-download-discard").start();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "文件下载", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
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
        handler.removeCallbacks(update);
        handler.postDelayed(update, 1000);
        worker.execute(() -> {
            perform(job);
            handler.post(() -> {
                handler.removeCallbacks(update);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelfResult(startId);
                job.busy = false;
            });
        });
        return START_NOT_STICKY;
    }

    private void perform(Job job) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        try {
            job.state = "下载中";
            boolean staged = job.record.phase == DownloadRecord.PUBLISHING
                    && job.part.isFile() && job.part.length() == job.record.size;
            if (!staged && job.record.phase == DownloadRecord.PUBLISHING) {
                throw new IOException("待保存文件不完整，请取消后重新下载");
            }
            if (staged || job.transfer.transfer()) {
                job.saving = true;
                if (job.transfer.control.stopped || job.cancelled) {
                    job.state = "已暂停";
                    return;
                }
                job.state = "正在保存到下载目录";
                job.output = publish(job);
                job.finished = true;
                job.state = "已完成 · 下载目录";
                try { job.transfer.discard(); } catch (IOException ignored) { }
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
                 InputStream in = new FileInputStream(job.part)) {
                byte[] buffer = new byte[256 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
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
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class).putExtra("downloads", true)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause = PendingIntent.getService(this, 2,
                new Intent(this, DownloadService.class).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE);
        RangeDownload download = job.transfer;
        long total = download == null ? 0 : download.total;
        int percent = total > 0 ? (int) Math.min(100, 100.0 * download.downloaded() / total) : 0;
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(job.name).setContentText(job.state)
                .setContentIntent(open).setProgress(100, percent, total <= 0).setOnlyAlertOnce(true)
                .setOngoing(true).addAction(new Notification.Action.Builder(null, "暂停", pause).build()).build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onTimeout(int startId, int fgsType) {
        pause(current);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(update);
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

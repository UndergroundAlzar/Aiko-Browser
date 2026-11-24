package app.aikobrowser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;

import java.lang.ref.WeakReference;

/**
 * Foreground MediaPlaybackService managing background web audio playback,
 * Android MediaSession lockscreen transport controls, and Notification.MediaStyle shade controls.
 */
public final class MediaPlaybackService extends Service {
    public static final String CHANNEL_ID = "aiko_media";
    public static final String ACTION_START = "app.aikobrowser.media.START";
    public static final String ACTION_UPDATE_STATE = "app.aikobrowser.media.UPDATE_STATE";
    public static final String ACTION_TOGGLE_PLAY = "app.aikobrowser.media.TOGGLE_PLAY";
    public static final String ACTION_STOP = "app.aikobrowser.media.STOP";

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PLAYING = "playing";
    private static final String EXTRA_FROM_USER = "from_user";

    private static final int NOTIFICATION_ID = 19;
    private static final String MAIN_ACTIVITY_CLASS = "app.aikobrowser.MainActivity";

    public interface PlaybackListener {
        void onTogglePlayPause();
        void onStopBackgroundAudio();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<PlaybackListener> listenerRef = new WeakReference<>(null);
    private static volatile boolean running;

    private NotificationManager notificationManager;
    private MediaSession mediaSession;
    private PendingIntent openAppIntent;
    private PendingIntent togglePlayIntent;
    private PendingIntent stopFromUserIntent;
    private boolean noisyRegistered;

    private String mediaTitle = "网页媒体播放";
    private String mediaHost = "Aiko Browser";
    private boolean isPlaying = true;
    private boolean foregroundStarted;

    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent != null ? intent.getAction() : null)) {
                if (isPlaying) {
                    isPlaying = false;
                    updateSessionAndNotification();
                    notifyTogglePlayPause();
                }
            }
        }
    };

    public static void setPlaybackListener(PlaybackListener listener) {
        listenerRef = new WeakReference<>(listener);
    }

    public static void clearPlaybackListener(PlaybackListener listener) {
        PlaybackListener current = listenerRef.get();
        if (current == null || current == listener) {
            listenerRef = new WeakReference<>(null);
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context, String title, String host, boolean playing) {
        if (context == null) return;
        Intent intent = new Intent(context, MediaPlaybackService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title == null ? "" : title)
                .putExtra(EXTRA_HOST, host == null ? "" : host)
                .putExtra(EXTRA_PLAYING, playing);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException ignored) {
            // Android 12+ background FGS launch restriction — fail gracefully without crashing.
        }
    }

    public static void updateState(Context context, boolean playing) {
        updateState(context, null, null, playing);
    }

    public static void updateState(Context context, String title, String host, boolean playing) {
        if (context == null || !running) return;
        Intent intent = new Intent(context, MediaPlaybackService.class)
                .setAction(ACTION_UPDATE_STATE)
                .putExtra(EXTRA_PLAYING, playing);
        if (title != null) intent.putExtra(EXTRA_TITLE, title);
        if (host != null) intent.putExtra(EXTRA_HOST, host);
        try {
            context.startService(intent);
        } catch (RuntimeException ignored) {
        }
    }

    public static void stop(Context context) {
        if (context == null || !running) return;
        Intent intent = new Intent(context, MediaPlaybackService.class)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_FROM_USER, false);
        try {
            context.startService(intent);
        } catch (RuntimeException ignored) {
            try {
                context.stopService(new Intent(context, MediaPlaybackService.class));
            } catch (RuntimeException ignored2) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AikoTheme.applyTheme(this);
        notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "后台媒体播放", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("网页音视频后台播放与锁屏媒体控制");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }

        Intent open = new Intent().setClassName(getPackageName(), MAIN_ACTIVITY_CLASS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openAppIntent = PendingIntent.getActivity(this, 101, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        togglePlayIntent = PendingIntent.getService(this, 102,
                new Intent(this, MediaPlaybackService.class).setAction(ACTION_TOGGLE_PLAY),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        stopFromUserIntent = PendingIntent.getService(this, 103,
                new Intent(this, MediaPlaybackService.class)
                        .setAction(ACTION_STOP)
                        .putExtra(EXTRA_FROM_USER, true),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        mediaSession = new MediaSession(this, "AikoMediaSession");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (!isPlaying) {
                    isPlaying = true;
                    updateSessionAndNotification();
                    notifyTogglePlayPause();
                }
            }

            @Override
            public void onPause() {
                if (isPlaying) {
                    isPlaying = false;
                    updateSessionAndNotification();
                    notifyTogglePlayPause();
                }
            }

            @Override
            public void onStop() {
                shutdownService(true);
            }

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                if (mediaButtonIntent != null) {
                    KeyEvent keyEvent = extractKeyEvent(mediaButtonIntent);
                    if (keyEvent != null && keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                        int code = keyEvent.getKeyCode();
                        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK) {
                            isPlaying = !isPlaying;
                            updateSessionAndNotification();
                            notifyTogglePlayPause();
                            return true;
                        } else if (code == KeyEvent.KEYCODE_MEDIA_PLAY) {
                            onPlay();
                            return true;
                        } else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                            onPause();
                            return true;
                        } else if (code == KeyEvent.KEYCODE_MEDIA_STOP) {
                            onStop();
                            return true;
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
        mediaSession.setActive(true);

        try {
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(becomingNoisyReceiver, filter);
            }
            noisyRegistered = true;
        } catch (RuntimeException ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static KeyEvent extractKeyEvent(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            boolean fromUser = intent.getBooleanExtra(EXTRA_FROM_USER, true);
            shutdownService(fromUser);
            return START_NOT_STICKY;
        }

        if (ACTION_TOGGLE_PLAY.equals(action)) {
            if (!running) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            isPlaying = !isPlaying;
            updateSessionAndNotification();
            notifyTogglePlayPause();
            return START_NOT_STICKY;
        }

        if (ACTION_UPDATE_STATE.equals(action)) {
            if (!running) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            applyExtras(intent);
            updateSessionAndNotification();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            applyExtras(intent);
            updateMediaSessionMetadataAndState();
            Notification notif = buildNotification();
            if (!enterForeground(notif)) {
                running = false;
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            running = true;
            return START_NOT_STICKY;
        }

        if (!running) {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void applyExtras(Intent intent) {
        if (intent == null) return;
        if (intent.hasExtra(EXTRA_TITLE)) {
            String t = intent.getStringExtra(EXTRA_TITLE);
            if (t != null && !t.trim().isEmpty()) {
                mediaTitle = t.trim();
            } else if (mediaTitle == null || mediaTitle.isEmpty()) {
                mediaTitle = "网页媒体播放";
            }
        }
        if (intent.hasExtra(EXTRA_HOST)) {
            String h = intent.getStringExtra(EXTRA_HOST);
            if (h != null && !h.trim().isEmpty()) {
                mediaHost = h.trim();
            } else if (mediaHost == null || mediaHost.isEmpty()) {
                mediaHost = "Aiko Browser";
            }
        }
        if (intent.hasExtra(EXTRA_PLAYING)) {
            isPlaying = intent.getBooleanExtra(EXTRA_PLAYING, isPlaying);
        }
    }

    private boolean enterForeground(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
            return true;
        } catch (RuntimeException e) {
            // Handles ForegroundServiceStartNotAllowedException / SecurityException cleanly on Android 12–16
            foregroundStarted = false;
            return false;
        }
    }

    private void updateSessionAndNotification() {
        updateMediaSessionMetadataAndState();
        Notification notif = buildNotification();
        if (!foregroundStarted) {
            enterForeground(notif);
        } else if (notificationManager != null) {
            try {
                notificationManager.notify(NOTIFICATION_ID, notif);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void updateMediaSessionMetadataAndState() {
        MediaSession session = mediaSession;
        if (session == null) return;
        MediaMetadata metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, mediaTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, mediaHost)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, mediaTitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, mediaHost)
                .build();
        session.setMetadata(metadata);

        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP;
        int state = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState playbackState = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, isPlaying ? 1.0f : 0.0f)
                .build();
        session.setPlaybackState(playbackState);
    }

    private Notification buildNotification() {
        int toggleIconRes = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String toggleLabel = isPlaying ? "暂停" : "播放";

        Notification.Action toggleAction = new Notification.Action.Builder(
                Icon.createWithResource(this, toggleIconRes), toggleLabel, togglePlayIntent).build();
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "停止", stopFromUserIntent).build();

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setShowActionsInCompactView(0, 1);
        if (mediaSession != null) {
            style.setMediaSession(mediaSession.getSessionToken());
        }

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(isPlaying ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setContentTitle(mediaTitle)
                .setContentText(mediaHost + (isPlaying ? " · 后台播放中" : " · 已暂停"))
                .setSubText("Aiko 后台音频")
                .setColor(AikoTheme.ACCENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openAppIntent)
                .setDeleteIntent(stopFromUserIntent)
                .setOngoing(isPlaying)
                .setOnlyAlertOnce(true)
                .addAction(toggleAction)
                .addAction(stopAction)
                .setStyle(style)
                .build();
    }

    private void shutdownService(boolean notifyListener) {
        running = false;
        foregroundStarted = false;
        if (notifyListener) {
            notifyStopAudio();
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException ignored) {
        }
        if (notificationManager != null) {
            try {
                notificationManager.cancel(NOTIFICATION_ID);
            } catch (RuntimeException ignored) {
            }
        }
        stopSelf();
    }

    private static void notifyTogglePlayPause() {
        PlaybackListener l = listenerRef.get();
        if (l == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            l.onTogglePlayPause();
        } else {
            MAIN.post(() -> {
                PlaybackListener latest = listenerRef.get();
                if (latest != null) latest.onTogglePlayPause();
            });
        }
    }

    private static void notifyStopAudio() {
        PlaybackListener l = listenerRef.get();
        if (l == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            l.onStopBackgroundAudio();
        } else {
            MAIN.post(() -> {
                PlaybackListener latest = listenerRef.get();
                if (latest != null) latest.onStopBackgroundAudio();
            });
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        shutdownService(true);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        shutdownService(false);
    }

    @Override
    public void onDestroy() {
        running = false;
        foregroundStarted = false;
        if (noisyRegistered) {
            noisyRegistered = false;
            try {
                unregisterReceiver(becomingNoisyReceiver);
            } catch (RuntimeException ignored) {
            }
        }
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (RuntimeException ignored) {
            }
            mediaSession = null;
        }
        super.onDestroy();
    }
}

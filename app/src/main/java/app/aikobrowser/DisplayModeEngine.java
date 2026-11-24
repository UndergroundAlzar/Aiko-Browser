package app.aikobrowser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.FrameMetrics;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

/**
 * High-refresh-rate (120-185Hz) display mode engine, Android 15+ ARR controller,
 * Smart Video Yield, and opt-in zero-idle-overhead live frame-pacing HUD.
 */
public final class DisplayModeEngine {
    public static final int POLICY_MAX = 0;
    public static final int POLICY_AUTO = 1;
    public static final int POLICY_POWER = 2;

    private static final String PREFS = "display_mode";
    private static final String KEY_POLICY = "policy";
    private static final String KEY_HUD = "hud_enabled";
    private static final float RATE_EPSILON = 0.5f;
    private static final float ALT_RATE_EPSILON = 0.01f;

    public interface HudListener {
        void onMetrics(float activeHz, float maxHz, float renderedFps,
                       float p99Ms, float jankPct, boolean oemCapped);
    }

    private final Activity activity;
    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<WeakReference<View>> attachedViews = new ArrayList<>(4);
    private DisplayManager displayManager;
    private boolean listenerRegistered;
    private boolean profileApplied;
    private int policy;
    private boolean adpfEnabled;
    private boolean fullscreenVideo;
    private boolean siteForceMaxHz;
    private volatile float cachedActiveHz;
    private volatile float cachedMaxHz;
    private volatile HudListener hudListener;
    private int uiTid;
    // Holds AdpfHelper on API 31+ only; typed as Object so API 29-30 never loads PerformanceHintManager.
    private Object adpfController;
    private final Runnable adpfSettleTask = this::settleAdpfBurst;

    // Live frame-pacing telemetry state (allocated ONLY when HUD is enabled).
    private HandlerThread hudThread;
    private Handler hudHandler;
    private Window.OnFrameMetricsAvailableListener frameMetricsListener;
    private final long[] frameDurations = new long[512];
    private int frameCount;
    private int jankCount;
    private long windowStartMs;
    private Runnable idleDecayTask;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { refreshAndApplyIfOwn(displayId); }
        @Override public void onDisplayRemoved(int displayId) { refreshAndApplyIfOwn(displayId); }
        @Override public void onDisplayChanged(int displayId) { refreshAndApplyIfOwn(displayId); }
    };

    public DisplayModeEngine(Activity activity) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
        this.policy = loadPolicy(appContext);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            this.uiTid = Process.myTid();
        }
        this.displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        updateCachedRates(currentDisplay());
    }

    /**
     * Pure-Java testable display mode selector.
     * Strictly matches current physical resolution, preserves HDR capability when available,
     * prefers seamless alternative refresh rates on ties, and honors policy semantics:
     * - POLICY_AUTO: returns 0 (let OS ARR / LTPO manage frequency organically)
     * - POLICY_MAX: picks highest refresh rate at current resolution
     * - POLICY_POWER: picks lowest refresh rate >= 60Hz (or 0 if unavailable)
     */
    public static int selectModeId(
            int curId, int curW, int curH, float curHz,
            int[] modeIds, int[] widths, int[] heights, float[] rates,
            float[] altRates, int[] hdrCounts, int curHdrCount, int policy) {
        if (policy == POLICY_AUTO) return 0;
        if (curW <= 0 || curH <= 0 || modeIds == null || widths == null || heights == null || rates == null) {
            return 0;
        }
        int n = Math.min(Math.min(modeIds.length, widths.length), Math.min(heights.length, rates.length));
        if (n <= 0) return 0;

        int chosen = selectPass(curId, curW, curH, modeIds, widths, heights, rates, altRates,
                hdrCounts, curHdrCount, policy, true, n);
        if (chosen != 0) return chosen;
        // Fallback if no resolution-matched mode advertises >= curHdrCount.
        return selectPass(curId, curW, curH, modeIds, widths, heights, rates, altRates,
                hdrCounts, 0, policy, false, n);
    }

    private static int selectPass(
            int curId, int curW, int curH,
            int[] modeIds, int[] widths, int[] heights, float[] rates,
            float[] altRates, int[] hdrCounts, int curHdrCount,
            int policy, boolean enforceHdr, int n) {
        int bestIdx = -1;
        boolean bestSeamless = false;
        int bestHdr = -1;
        float bestRate = 0f;

        for (int i = 0; i < n; i++) {
            if (widths[i] != curW || heights[i] != curH) continue;
            float r = rates[i];
            if (r <= 0f || Float.isNaN(r) || Float.isInfinite(r)) continue;
            if (policy == POLICY_POWER && r < 59.0f) continue;

            int hdr = (hdrCounts != null && i < hdrCounts.length) ? hdrCounts[i] : 0;
            if (enforceHdr && curHdrCount > 0 && hdr < curHdrCount) continue;

            boolean seamless = (modeIds[i] == curId) || containsRate(altRates, r);
            if (bestIdx < 0) {
                bestIdx = i;
                bestSeamless = seamless;
                bestHdr = hdr;
                bestRate = r;
                continue;
            }
            if (isBetter(policy, r, seamless, hdr, modeIds[i] == curId,
                    bestRate, bestSeamless, bestHdr, modeIds[bestIdx] == curId)) {
                bestIdx = i;
                bestSeamless = seamless;
                bestHdr = hdr;
                bestRate = r;
            }
        }
        return bestIdx >= 0 ? modeIds[bestIdx] : 0;
    }

    private static boolean isBetter(
            int policy, float r, boolean seamless, int hdr, boolean isCur,
            float bestR, boolean bestSeamless, int bestHdr, boolean bestIsCur) {
        if (policy == POLICY_POWER) {
            if (r < bestR - RATE_EPSILON) return true;
            if (r > bestR + RATE_EPSILON) return false;
        } else {
            if (r > bestR + RATE_EPSILON) return true;
            if (r < bestR - RATE_EPSILON) return false;
        }
        if (seamless != bestSeamless) return seamless;
        if (hdr != bestHdr) return hdr > bestHdr;
        if (isCur != bestIsCur) return isCur;
        return policy == POLICY_POWER ? r < bestR : r > bestR;
    }

    private static boolean containsRate(float[] rates, float target) {
        if (rates == null) return false;
        for (int i = 0; i < rates.length; i++) {
            if (Math.abs(rates[i] - target) <= ALT_RATE_EPSILON) return true;
        }
        return false;
    }

    public void start() {
        if (!profileApplied) policy = loadPolicy(appContext);
        if (uiTid <= 0 && Looper.myLooper() == Looper.getMainLooper()) {
            uiTid = Process.myTid();
        }
        if (!listenerRegistered && displayManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= 36) {
                    long filter = DisplayManager.EVENT_TYPE_DISPLAY_REFRESH_RATE
                            | DisplayManager.EVENT_TYPE_DISPLAY_CHANGED
                            | DisplayManager.EVENT_TYPE_DISPLAY_ADDED
                            | DisplayManager.EVENT_TYPE_DISPLAY_REMOVED;
                    displayManager.registerDisplayListener(activity.getMainExecutor(), filter, displayListener);
                } else {
                    displayManager.registerDisplayListener(displayListener, main);
                }
                listenerRegistered = true;
            } catch (RuntimeException ignored) { }
        }
        apply();
        if (adpfEnabled) ensureAdpfSession();
        if (hudListener != null) startFrameMetrics();
    }

    public void stop() {
        if (listenerRegistered && displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); }
            catch (RuntimeException ignored) { }
            listenerRegistered = false;
        }
        closeAdpfSession();
        stopFrameMetrics();
    }

    /**
     * Applies a PerfProfiles.Profile: updates display refresh policy and manages
     * the Android 12+ (API 31+) ADPF PerformanceHintManager session cleanly.
     */
    public void applyProfile(PerfProfiles.Profile p) {
        if (p == null) return;
        profileApplied = true;
        policy = clampPolicy(p.refreshPolicy);
        adpfEnabled = p.adpfBoost;
        apply();
        if (adpfEnabled) {
            if (listenerRegistered) ensureAdpfSession();
        } else {
            closeAdpfSession();
        }
    }

    /**
     * Event-driven burst-only ADPF interaction boost (touch-down / fling start / page nav start).
     * Never called per-frame; rate-limited against HAL preferred update rate and decays automatically.
     */
    public void boostInteraction(long durationMs) {
        if (!adpfEnabled || fullscreenVideo || !listenerRegistered || Build.VERSION.SDK_INT < 31) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> boostInteraction(durationMs));
            return;
        }
        if (uiTid <= 0) uiTid = Process.myTid();
        long targetNs = targetWorkDurationNs();
        long clampedMs = Math.max(40L, Math.min(durationMs, 1500L));
        AdpfHelper helper = adpfHelper();
        if (helper.boost(appContext, uiTid, targetNs, clampedMs)) {
            main.removeCallbacks(adpfSettleTask);
            main.postDelayed(adpfSettleTask, clampedMs);
        }
    }

    /** Returns true when an API 31+ ADPF hint session is currently open. */
    public boolean isAdpfActive() {
        return Build.VERSION.SDK_INT >= 31
                && adpfController != null
                && ((AdpfHelper) adpfController).isActive();
    }

    /** Concise display panel + active refresh rate & resolution summary for About / Perf sheets. */
    public String hardwareSummary() {
        Display d = currentDisplay();
        if (d != null) updateCachedRates(d);
        int active = Math.round(cachedActiveHz > 0f ? cachedActiveHz : 60f);
        int max = Math.round(cachedMaxHz > 0f ? cachedMaxHz : active);
        if (max < active) max = active;
        StringBuilder sb = new StringBuilder(40);
        sb.append(max).append("Hz 面板 · 当前 ").append(active).append("Hz");
        if (d != null) {
            try {
                Display.Mode mode = d.getMode();
                if (mode != null && mode.getPhysicalWidth() > 0 && mode.getPhysicalHeight() > 0) {
                    sb.append(" (").append(mode.getPhysicalWidth())
                            .append('×').append(mode.getPhysicalHeight()).append(')');
                }
            } catch (RuntimeException ignored) { }
        }
        return sb.toString();
    }

    public void setPolicy(int newPolicy) {
        int clamped = clampPolicy(newPolicy);
        if (policy != clamped) {
            policy = clamped;
            apply();
        }
    }

    public int getPolicy() { return policy; }

    public void setFullscreenVideo(boolean fullscreen) {
        if (fullscreenVideo != fullscreen) {
            fullscreenVideo = fullscreen;
            apply();
        }
    }

    public void setSiteForceMaxHz(boolean forceMax) {
        if (siteForceMaxHz != forceMax) {
            siteForceMaxHz = forceMax;
            apply();
        }
    }

    public void attachView(View view) {
        if (view == null) return;
        boolean found = false;
        Iterator<WeakReference<View>> it = attachedViews.iterator();
        while (it.hasNext()) {
            View existing = it.next().get();
            if (existing == null) it.remove();
            else if (existing == view) found = true;
        }
        if (!found) attachedViews.add(new WeakReference<>(view));
        applyViewFrameRate(view);
    }

    public void apply() {
        Window window = activity.getWindow();
        if (window == null) return;
        Display display = currentDisplay();
        updateCachedRates(display);
        int effectivePolicy = effectivePolicy();
        applyToWindow(window, display, effectivePolicy, fullscreenVideo);
        if (Build.VERSION.SDK_INT >= 35) {
            applyViewFrameRate(window.peekDecorView());
            Iterator<WeakReference<View>> it = attachedViews.iterator();
            while (it.hasNext()) {
                View v = it.next().get();
                if (v == null) it.remove();
                else applyViewFrameRate(v);
            }
        }
        if (adpfEnabled && listenerRegistered && Build.VERSION.SDK_INT >= 31 && adpfController != null) {
            ((AdpfHelper) adpfController).updateTargetIfOpen(targetWorkDurationNs());
        }
    }

    public void applyToDialog(Dialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        Display display = currentDisplay();
        applyToWindow(window, display, effectivePolicy(), fullscreenVideo);
        if (Build.VERSION.SDK_INT >= 35) applyViewFrameRate(window.peekDecorView());
    }

    private int effectivePolicy() {
        if (fullscreenVideo) return POLICY_AUTO;
        if (siteForceMaxHz) return POLICY_MAX;
        return policy;
    }

    private void applyToWindow(Window window, Display display, int effectivePolicy, boolean videoYield) {
        WindowManager.LayoutParams attrs = window.getAttributes();
        if (attrs == null) return;
        boolean changed = false;

        int targetCutout = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (attrs.layoutInDisplayCutoutMode != targetCutout) {
            attrs.layoutInDisplayCutoutMode = targetCutout;
            changed = true;
        }

        int desiredModeId = computePreferredModeId(display, effectivePolicy);
        if (attrs.preferredDisplayModeId != desiredModeId) {
            attrs.preferredDisplayModeId = desiredModeId;
            changed = true;
        }
        // Keep legacy preferredRefreshRate cleared so preferredDisplayModeId / ARR take precedence.
        if (attrs.preferredRefreshRate != 0f) {
            attrs.preferredRefreshRate = 0f;
            changed = true;
        }

        if (Build.VERSION.SDK_INT >= 35) {
            boolean wantMax = !videoYield && effectivePolicy == POLICY_MAX;
            boolean wantPower = !videoYield && effectivePolicy == POLICY_POWER;
            boolean boostOnTouch = !wantPower;
            boolean powerBalanced = !wantMax;
            if (window.getFrameRateBoostOnTouchEnabled() != boostOnTouch) {
                window.setFrameRateBoostOnTouchEnabled(boostOnTouch);
            }
            if (window.isFrameRatePowerSavingsBalanced() != powerBalanced) {
                window.setFrameRatePowerSavingsBalanced(powerBalanced);
            }
        }

        if (changed) {
            try { window.setAttributes(attrs); } catch (RuntimeException ignored) { }
        }
    }

    private void applyViewFrameRate(View view) {
        if (view == null || Build.VERSION.SDK_INT < 35) return;
        float category;
        if (fullscreenVideo) {
            category = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT;
        } else {
            int eff = effectivePolicy();
            if (eff == POLICY_POWER) category = View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL;
            else category = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH;
        }
        if (view.getRequestedFrameRate() != category) {
            view.setRequestedFrameRate(category);
        }
    }

    private static int computePreferredModeId(Display display, int effectivePolicy) {
        if (display == null || effectivePolicy == POLICY_AUTO) return 0;
        Display.Mode current = display.getMode();
        Display.Mode[] supported = display.getSupportedModes();
        if (current == null || supported == null || supported.length == 0) return 0;

        int n = supported.length;
        int[] ids = new int[n];
        int[] widths = new int[n];
        int[] heights = new int[n];
        float[] rates = new float[n];
        int[] hdrCounts = new int[n];
        for (int i = 0; i < n; i++) {
            Display.Mode m = supported[i];
            ids[i] = m.getModeId();
            widths[i] = m.getPhysicalWidth();
            heights[i] = m.getPhysicalHeight();
            rates[i] = m.getRefreshRate();
            if (Build.VERSION.SDK_INT >= 34) {
                int[] hdr = m.getSupportedHdrTypes();
                hdrCounts[i] = hdr == null ? 0 : hdr.length;
            }
        }
        float[] altRates = Build.VERSION.SDK_INT >= 31 ? current.getAlternativeRefreshRates() : null;
        int curHdr = 0;
        if (Build.VERSION.SDK_INT >= 34) {
            int[] hdr = current.getSupportedHdrTypes();
            curHdr = hdr == null ? 0 : hdr.length;
        }
        return selectModeId(
                current.getModeId(), current.getPhysicalWidth(), current.getPhysicalHeight(),
                current.getRefreshRate(), ids, widths, heights, rates, altRates, hdrCounts, curHdr,
                effectivePolicy);
    }

    private Display currentDisplay() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Display d = activity.getDisplay();
                if (d != null) return d;
            }
            WindowManager wm = activity.getWindowManager();
            return wm != null ? wm.getDefaultDisplay() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void updateCachedRates(Display display) {
        if (display == null) return;
        float active = display.getRefreshRate();
        if (active > 0f) cachedActiveHz = active;
        Display.Mode cur = display.getMode();
        Display.Mode[] modes = display.getSupportedModes();
        float max = active;
        if (cur != null && modes != null) {
            int w = cur.getPhysicalWidth();
            int h = cur.getPhysicalHeight();
            for (Display.Mode m : modes) {
                if (m.getPhysicalWidth() == w && m.getPhysicalHeight() == h) {
                    float r = m.getRefreshRate();
                    if (r > max) max = r;
                }
            }
        }
        if (max > 0f) cachedMaxHz = max;
    }

    private void refreshAndApplyIfOwn(int displayId) {
        if (activity.isDestroyed() || activity.isFinishing()) return;
        Display current = currentDisplay();
        if (current == null || current.getDisplayId() == displayId) {
            apply();
            HudListener listener = hudListener;
            if (listener != null) {
                float active = cachedActiveHz;
                float max = cachedMaxHz;
                boolean capped = isOemCapped();
                main.post(() -> {
                    HudListener l = hudListener;
                    if (l != null) l.onMetrics(active, max, 0f, 0f, 0f, capped);
                });
            }
        }
    }

    public float activeRefreshRate() {
        Display d = currentDisplay();
        if (d != null) updateCachedRates(d);
        return cachedActiveHz;
    }

    public float maxSupportedRefreshRate() {
        Display d = currentDisplay();
        if (d != null) updateCachedRates(d);
        return cachedMaxHz;
    }

    public boolean isOemCapped() {
        if (fullscreenVideo) return false;
        if (effectivePolicy() != POLICY_MAX) return false;
        float max = cachedMaxHz;
        float active = cachedActiveHz;
        return max > 65f && active > 0f && active < max - 5f;
    }

    /** Attaches or detaches live frame-pacing telemetry. 0% overhead when listener is null. */
    public void setHudListener(HudListener listener) {
        this.hudListener = listener;
        if (listener == null) {
            stopFrameMetrics();
        } else if (listenerRegistered) {
            startFrameMetrics();
            float active = activeRefreshRate();
            float max = maxSupportedRefreshRate();
            boolean capped = isOemCapped();
            main.post(() -> {
                HudListener l = hudListener;
                if (l != null) l.onMetrics(active, max, 0f, 0f, 0f, capped);
            });
        }
    }

    private void startFrameMetrics() {
        if (frameMetricsListener != null) return;
        Window window = activity.getWindow();
        if (window == null) return;
        hudThread = new HandlerThread("aiko-frame-hud", Process.THREAD_PRIORITY_BACKGROUND);
        hudThread.start();
        hudHandler = new Handler(hudThread.getLooper());
        frameCount = 0;
        jankCount = 0;
        windowStartMs = SystemClock.uptimeMillis();
        idleDecayTask = () -> {
            HudListener l = hudListener;
            if (l == null) return;
            float active = cachedActiveHz;
            float max = cachedMaxHz;
            boolean capped = isOemCapped();
            main.post(() -> {
                HudListener cur = hudListener;
                if (cur != null) cur.onMetrics(active, max, 0f, 0f, 0f, capped);
            });
        };
        frameMetricsListener = (w, metrics, dropCount) -> onFrameSample(metrics);
        try {
            window.addOnFrameMetricsAvailableListener(frameMetricsListener, hudHandler);
        } catch (RuntimeException ignored) {
            stopFrameMetrics();
        }
    }

    private void stopFrameMetrics() {
        Window window = activity.getWindow();
        if (frameMetricsListener != null && window != null) {
            try { window.removeOnFrameMetricsAvailableListener(frameMetricsListener); }
            catch (RuntimeException ignored) { }
        }
        frameMetricsListener = null;
        if (hudHandler != null && idleDecayTask != null) {
            hudHandler.removeCallbacks(idleDecayTask);
        }
        idleDecayTask = null;
        hudHandler = null;
        if (hudThread != null) {
            hudThread.quitSafely();
            hudThread = null;
        }
    }

    private void onFrameSample(FrameMetrics metrics) {
        long totalNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
        if (totalNs <= 0L) return;
        long deadlineNs = 0L;
        if (Build.VERSION.SDK_INT >= 31) {
            deadlineNs = metrics.getMetric(FrameMetrics.DEADLINE);
        }
        if (deadlineNs <= 0L) {
            float hz = cachedActiveHz > 0f ? cachedActiveHz : 60f;
            deadlineNs = (long) (1_000_000_000.0 / hz);
        }
        if (frameCount < frameDurations.length) {
            frameDurations[frameCount] = totalNs;
        } else {
            frameDurations[frameCount % frameDurations.length] = totalNs;
        }
        frameCount++;
        if (totalNs > deadlineNs) jankCount++;

        Handler h = hudHandler;
        Runnable decay = idleDecayTask;
        if (h != null && decay != null) {
            h.removeCallbacks(decay);
            h.postDelayed(decay, 1400L);
        }

        long now = SystemClock.uptimeMillis();
        long elapsed = now - windowStartMs;
        if (elapsed < 500L) return;

        int samples = Math.min(frameCount, frameDurations.length);
        long[] copy = Arrays.copyOf(frameDurations, samples);
        Arrays.sort(copy);
        int p99Idx = Math.max(0, Math.min(samples - 1, (int) Math.ceil(samples * 0.99) - 1));
        float p99Ms = copy[p99Idx] / 1_000_000f;
        float fps = (frameCount * 1000f) / Math.max(1L, elapsed);
        float jankPct = (100f * jankCount) / Math.max(1, frameCount);
        float active = cachedActiveHz;
        float max = cachedMaxHz;
        boolean capped = isOemCapped();

        frameCount = 0;
        jankCount = 0;
        windowStartMs = now;

        main.post(() -> {
            HudListener l = hudListener;
            if (l != null) l.onMetrics(active, max, fps, p99Ms, jankPct, capped);
        });
    }

    public static int loadPolicy(Context ctx) {
        if (ctx == null) return POLICY_MAX;
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return clampPolicy(prefs.getInt(KEY_POLICY, POLICY_MAX));
    }

    public static void savePolicy(Context ctx, int policy) {
        if (ctx == null) return;
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_POLICY, clampPolicy(policy)).apply();
    }

    public static String policyLabel(int policy) {
        switch (clampPolicy(policy)) {
            case POLICY_AUTO: return "自适应";
            case POLICY_POWER: return "省电 60Hz";
            default: return "极速高刷";
        }
    }

    public static boolean loadHudEnabled(Context ctx) {
        if (ctx == null) return false;
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_HUD, false);
    }

    public static void saveHudEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HUD, enabled).apply();
    }

    public static void openRefreshRateSettings(Context ctx) {
        if (ctx == null) return;
        Intent[] candidates = new Intent[]{
                new Intent(Settings.ACTION_DISPLAY_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent intent : candidates) {
            if (!(ctx instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(intent);
                return;
            } catch (RuntimeException ignored) { }
        }
    }

    private static int clampPolicy(int p) {
        return (p == POLICY_AUTO || p == POLICY_POWER) ? p : POLICY_MAX;
    }

    private long targetWorkDurationNs() {
        float hz = Math.max(60f, cachedActiveHz > 0f ? cachedActiveHz : 60f);
        return (long) (1_000_000_000.0 / hz);
    }

    private AdpfHelper adpfHelper() {
        if (adpfController == null) adpfController = new AdpfHelper();
        return (AdpfHelper) adpfController;
    }

    private void ensureAdpfSession() {
        if (!adpfEnabled || Build.VERSION.SDK_INT < 31) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::ensureAdpfSession);
            return;
        }
        if (uiTid <= 0) uiTid = Process.myTid();
        adpfHelper().openOrUpdate(appContext, uiTid, targetWorkDurationNs());
    }

    private void settleAdpfBurst() {
        if (Build.VERSION.SDK_INT < 31 || adpfController == null) return;
        ((AdpfHelper) adpfController).settle(targetWorkDurationNs());
    }

    private void closeAdpfSession() {
        main.removeCallbacks(adpfSettleTask);
        if (Build.VERSION.SDK_INT < 31 || adpfController == null) return;
        ((AdpfHelper) adpfController).close();
    }

    /** Isolated API 31+ ADPF wrapper so API 29-30 never loads PerformanceHintManager classes. */
    private static final class AdpfHelper {
        private static final long MIN_BURST_INTERVAL_NS = 80_000_000L;

        private PerformanceHintManager.Session session;
        private long lastTargetNs;
        private long minUpdateRateNs = MIN_BURST_INTERVAL_NS;
        private long lastBoostElapsedNs;
        private boolean unsupported;

        void openOrUpdate(Context appContext, int uiTid, long targetNs) {
            if (unsupported || uiTid <= 0 || targetNs <= 0L) return;
            if (session != null) {
                updateTargetIfOpen(targetNs);
                return;
            }
            try {
                PerformanceHintManager phm = (PerformanceHintManager) appContext
                        .getSystemService(Context.PERFORMANCE_HINT_SERVICE);
                if (phm == null) {
                    unsupported = true;
                    return;
                }
                long preferred = phm.getPreferredUpdateRateNanos();
                minUpdateRateNs = Math.max(MIN_BURST_INTERVAL_NS, preferred > 0L ? preferred : 16_000_000L);
                PerformanceHintManager.Session created = phm.createHintSession(new int[]{uiTid}, targetNs);
                if (created == null) {
                    unsupported = true;
                    return;
                }
                session = created;
                lastTargetNs = targetNs;
            } catch (Throwable ignored) {
                unsupported = true;
                session = null;
            }
        }

        void updateTargetIfOpen(long targetNs) {
            if (session == null || targetNs <= 0L) return;
            if (Math.abs(targetNs - lastTargetNs) < 50_000L) return;
            try {
                session.updateTargetWorkDuration(targetNs);
                lastTargetNs = targetNs;
            } catch (RuntimeException ignored) { }
        }

        boolean boost(Context appContext, int uiTid, long targetNs, long durationMs) {
            long now = SystemClock.elapsedRealtimeNanos();
            if (lastBoostElapsedNs != 0L && now - lastBoostElapsedNs < minUpdateRateNs) return false;
            openOrUpdate(appContext, uiTid, targetNs);
            if (session == null) return false;
            lastBoostElapsedNs = now;
            long factor = durationMs >= 350L ? 140L : 125L;
            long reportNs = Math.max(targetNs + 500_000L, (targetNs * factor) / 100L);
            try {
                session.reportActualWorkDuration(reportNs);
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        void settle(long targetNs) {
            if (session == null || targetNs <= 0L) return;
            long relaxedNs = Math.max(1_000_000L, (targetNs * 65L) / 100L);
            try {
                session.reportActualWorkDuration(relaxedNs);
            } catch (RuntimeException ignored) { }
        }

        boolean isActive() {
            return session != null;
        }

        void close() {
            PerformanceHintManager.Session s = session;
            session = null;
            lastTargetNs = 0L;
            lastBoostElapsedNs = 0L;
            if (s != null) {
                try { s.close(); } catch (RuntimeException ignored) { }
            }
        }
    }
}

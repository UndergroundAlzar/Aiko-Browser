package app.aikobrowser;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-page WebView long screenshot engine with OOM-safe adaptive scaling (capped <= 16384px),
 * PixelCopy + software-draw fallback, fixed-header suppression on continuation slices,
 * optional Aiko attribution footer with mini QR code, and Android 10+ MediaStore scoped export.
 */
public final class LongShotCapture {
    public interface Callback {
        void onSaved(Uri imageUri, String displayName, int width, int height);
        void onError(String message);
    }

    private static final int MAX_STITCHED_HEIGHT_PX = 16384;
    private static final long MAX_TOTAL_PIXELS = 14_500_000L; // ~58 MB ARGB_8888 hard safety ceiling
    private static final int MAX_STEPS = 48;
    private static final long SCROLL_SETTLE_DELAY_MS = 95L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final ExecutorService SAVE_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
            catch (SecurityException ignored) { }
            r.run();
        }, "aiko-longshot-io");
        t.setDaemon(true);
        return t;
    });

    private static final String JS_MEASURE_HEIGHT =
            "(function(){try{var d=document.documentElement,b=document.body;"
                    + "var h=Math.max(d?d.scrollHeight:0,b?b.scrollHeight:0,window.innerHeight||0);"
                    + "var r=window.devicePixelRatio||1;return Math.round(h*r);}catch(e){return 0;}})()";

    private static final String JS_HIDE_FIXED =
            "(function(){try{var els=document.querySelectorAll('*');for(var i=0;i<els.length;i++){"
                    + "var el=els[i],st=window.getComputedStyle(el);if(!st)continue;"
                    + "if(st.position==='fixed'||st.position==='sticky'){"
                    + "el.setAttribute('data-aiko-ls-vis',el.style.visibility||'');"
                    + "el.style.setProperty('visibility','hidden','important');}}}catch(e){}})()";

    private static final String JS_RESTORE_FIXED =
            "(function(){try{var els=document.querySelectorAll('[data-aiko-ls-vis]');for(var i=0;i<els.length;i++){"
                    + "var el=els[i],prev=el.getAttribute('data-aiko-ls-vis');"
                    + "if(prev){el.style.visibility=prev;}else{el.style.removeProperty('visibility');}"
                    + "el.removeAttribute('data-aiko-ls-vis');}}catch(e){}})()";

    private LongShotCapture() {}

    public static void capture(Activity activity, WebView webView, String pageTitle, String pageUrl,
                               boolean includeFooter, Callback cb) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || webView == null) {
            deliverError(cb, "当前页面不可用，无法截取长图");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            deliverError(cb, "正在生成长图，请稍候");
            return;
        }

        Runnable start = () -> beginCapture(activity, webView,
                pageTitle == null ? "" : pageTitle.trim(),
                pageUrl == null ? "" : pageUrl.trim(),
                includeFooter, cb);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            start.run();
        } else {
            MAIN.post(start);
        }
    }

    private static void beginCapture(Activity activity, WebView webView, String pageTitle, String pageUrl,
                                     boolean includeFooter, Callback cb) {
        int vw = webView.getWidth();
        int vh = webView.getHeight();
        if (vw <= 0 || vh <= 0 || !webView.isAttachedToWindow()) {
            BUSY.set(false);
            deliverError(cb, "网页尚未渲染完成，无法截取长图");
            return;
        }

        AikoTheme.applyTheme(activity);
        final int origScrollX = webView.getScrollX();
        final int origScrollY = webView.getScrollY();
        final boolean origVScrollBar = webView.isVerticalScrollBarEnabled();
        final boolean origHScrollBar = webView.isHorizontalScrollBarEnabled();

        @SuppressWarnings("deprecation")
        float webScale = webView.getScale();
        int fallbackHeight = Math.max(vh, Math.round(webView.getContentHeight() * (webScale > 0f ? webScale : 1f)));

        webView.evaluateJavascript(JS_MEASURE_HEIGHT, value -> {
            if (activity.isFinishing() || activity.isDestroyed() || !webView.isAttachedToWindow()) {
                BUSY.set(false);
                deliverError(cb, "页面已关闭，截长图已取消");
                return;
            }
            int jsHeight = parseIntSafe(value, 0);
            int contentHeight = Math.max(vh, jsHeight > 0 ? jsHeight : fallbackHeight);
            int cappedContentH = Math.min(contentHeight, MAX_STITCHED_HEIGHT_PX);

            int footerUnscaledH = includeFooter ? Math.max(108, Math.round(vw * 0.14f)) : 0;
            int totalUnscaledH = Math.min(MAX_STITCHED_HEIGHT_PX, cappedContentH + footerUnscaledH);
            int captureContentH = Math.max(vh, totalUnscaledH - footerUnscaledH);

            float outScale = computeSafeScale(vw, totalUnscaledH);
            int outW = Math.max(1, Math.round(vw * outScale));
            int outContentH = Math.max(1, Math.round(captureContentH * outScale));
            int outFooterH = includeFooter ? Math.max(76, Math.round(footerUnscaledH * outScale)) : 0;
            int outTotalH = Math.min(MAX_STITCHED_HEIGHT_PX, outContentH + outFooterH);

            Bitmap stitched = allocateStitchedBitmap(outW, outTotalH);
            if (stitched == null) {
                // Retry once with half scale to guarantee OOM resilience
                outScale *= 0.65f;
                outW = Math.max(1, Math.round(vw * outScale));
                outContentH = Math.max(1, Math.round(captureContentH * outScale));
                outFooterH = includeFooter ? Math.max(68, Math.round(footerUnscaledH * outScale)) : 0;
                outTotalH = Math.min(MAX_STITCHED_HEIGHT_PX, outContentH + outFooterH);
                stitched = allocateStitchedBitmap(outW, outTotalH);
            }
            Bitmap tile = stitched != null ? allocateStitchedBitmap(vw, vh) : null;
            if (stitched == null || tile == null) {
                if (stitched != null) stitched.recycle();
                if (tile != null) tile.recycle();
                BUSY.set(false);
                deliverError(cb, "设备可用内存不足，无法拼接长图");
                return;
            }

            webView.setVerticalScrollBarEnabled(false);
            webView.setHorizontalScrollBarEnabled(false);

            CaptureSession session = new CaptureSession(
                    activity, webView, pageTitle, pageUrl, includeFooter, cb,
                    origScrollX, origScrollY, origVScrollBar, origHScrollBar,
                    vw, vh, captureContentH, outScale, outW, outFooterH, stitched, tile);
            session.start();
        });
    }

    private static float computeSafeScale(int vw, int totalH) {
        float scale = 1.0f;
        if (vw > 1080) {
            scale = 1080f / vw;
        }
        double pixels = (vw * (double) scale) * (totalH * (double) scale);
        if (pixels > MAX_TOTAL_PIXELS) {
            scale *= (float) Math.sqrt(MAX_TOTAL_PIXELS / pixels);
        }
        return Math.max(0.35f, Math.min(1.0f, scale));
    }

    private static Bitmap allocateStitchedBitmap(int w, int h) {
        try {
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError | IllegalArgumentException e) {
            return null;
        }
    }

    private static final class CaptureSession {
        final Activity activity;
        final WebView webView;
        final String pageTitle, pageUrl;
        final boolean includeFooter;
        final Callback cb;
        final int origScrollX, origScrollY;
        final boolean origVScrollBar, origHScrollBar;
        final int vw, vh, targetContentH;
        final float outScale;
        final int outW, outFooterH;
        final Bitmap stitched, tile;
        final Canvas stitchCanvas;
        final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Rect srcRect = new Rect();
        final RectF dstRect = new RectF();
        final int[] windowLoc = new int[2];

        PopupWindow progressPill;
        TextView progressText;
        int capturedContentBottom;
        int lastScrollY = -1;
        int stepIndex;
        boolean fixedHidden;
        boolean finished;

        CaptureSession(Activity activity, WebView webView, String pageTitle, String pageUrl,
                       boolean includeFooter, Callback cb,
                       int origScrollX, int origScrollY, boolean origVScrollBar, boolean origHScrollBar,
                       int vw, int vh, int targetContentH, float outScale, int outW, int outFooterH,
                       Bitmap stitched, Bitmap tile) {
            this.activity = activity;
            this.webView = webView;
            this.pageTitle = pageTitle;
            this.pageUrl = pageUrl;
            this.includeFooter = includeFooter;
            this.cb = cb;
            this.origScrollX = origScrollX;
            this.origScrollY = origScrollY;
            this.origVScrollBar = origVScrollBar;
            this.origHScrollBar = origHScrollBar;
            this.vw = vw;
            this.vh = vh;
            this.targetContentH = targetContentH;
            this.outScale = outScale;
            this.outW = outW;
            this.outFooterH = outFooterH;
            this.stitched = stitched;
            this.tile = tile;
            this.stitchCanvas = new Canvas(stitched);
            this.stitchCanvas.drawColor(AikoTheme.BG);
        }

        void start() {
            showProgressPill();
            scrollAndSchedule(0);
        }

        private void showProgressPill() {
            try {
                float d = activity.getResources().getDisplayMetrics().density;
                TextView pill = new TextView(activity);
                pill.setText("正在拼接长图…");
                pill.setTextSize(12);
                pill.setTextColor(AikoTheme.TEXT);
                pill.setTypeface(AikoTheme.MEDIUM);
                pill.setPadding(AikoTheme.dp(d, 14), AikoTheme.dp(d, 8), AikoTheme.dp(d, 14), AikoTheme.dp(d, 8));
                pill.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.STROKE_ACCENT, d, AikoTheme.dp(d, 18)));
                pill.setElevation(AikoTheme.dp(d, 8));
                progressText = pill;
                progressPill = new PopupWindow(pill, LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, false);
                progressPill.setTouchable(false);
                View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
                if (decor != null && decor.getWindowToken() != null) {
                    progressPill.showAtLocation(decor, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, AikoTheme.dp(d, 56));
                }
            } catch (RuntimeException ignored) {
                progressPill = null;
            }
        }

        private void dismissProgressPill() {
            PopupWindow p = progressPill;
            progressPill = null;
            if (p != null && p.isShowing()) {
                try { p.dismiss(); } catch (RuntimeException ignored) { }
            }
        }

        private void scrollAndSchedule(int targetY) {
            if (finished || activity.isFinishing() || activity.isDestroyed() || !webView.isAttachedToWindow()) {
                abort("页面已关闭，截长图已取消");
                return;
            }
            if (progressText != null) {
                int estTotal = Math.max(1, (targetContentH + vh - 1) / vh);
                progressText.setText("正在拼接长图… " + Math.min(stepIndex + 1, estTotal) + "/" + estTotal);
            }
            webView.scrollTo(origScrollX, targetY);
            // Wait for Chromium compositor to paint the newly exposed viewport tiles
            MAIN.postDelayed(this::captureCurrentViewport, SCROLL_SETTLE_DELAY_MS);
        }

        private void captureCurrentViewport() {
            if (finished || activity.isFinishing() || activity.isDestroyed() || !webView.isAttachedToWindow()) {
                abort("页面已关闭，截长图已取消");
                return;
            }

            final int actualScrollY = webView.getScrollY();
            if (stepIndex > 0 && actualScrollY <= lastScrollY) {
                // Reached real bottom of scrollable WebView content
                finalizeAndSave();
                return;
            }

            Window window = activity.getWindow();
            webView.getLocationInWindow(windowLoc);
            Rect copyRect = new Rect(windowLoc[0], windowLoc[1], windowLoc[0] + vw, windowLoc[1] + vh);

            if (window != null && copyRect.width() == vw && copyRect.height() == vh && copyRect.left >= 0 && copyRect.top >= 0) {
                try {
                    PixelCopy.request(window, copyRect, tile, result -> {
                        if (finished) return;
                        if (result == PixelCopy.SUCCESS) {
                            onTileCaptured(actualScrollY);
                        } else {
                            drawTileSoftware();
                            onTileCaptured(actualScrollY);
                        }
                    }, MAIN);
                    return;
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    // Surface unavailable — fall through to software draw
                }
            }
            drawTileSoftware();
            onTileCaptured(actualScrollY);
        }

        private void drawTileSoftware() {
            Canvas tc = new Canvas(tile);
            tc.drawColor(AikoTheme.BG);
            tc.save();
            tc.translate(-webView.getScrollX(), -webView.getScrollY());
            webView.draw(tc);
            tc.restore();
        }

        private void onTileCaptured(int actualScrollY) {
            int srcTop;
            int destContentTop;
            if (stepIndex == 0) {
                srcTop = 0;
                destContentTop = 0;
            } else {
                int overlap = capturedContentBottom - actualScrollY;
                srcTop = Math.max(0, Math.min(vh, overlap));
                destContentTop = capturedContentBottom;
            }

            int sliceH = Math.min(vh - srcTop, targetContentH - destContentTop);
            if (sliceH > 0) {
                srcRect.set(0, srcTop, vw, srcTop + sliceH);
                dstRect.set(0f, destContentTop * outScale, outW, (destContentTop + sliceH) * outScale);
                stitchCanvas.drawBitmap(tile, srcRect, dstRect, drawPaint);
                capturedContentBottom = destContentTop + sliceH;
            }

            lastScrollY = actualScrollY;
            stepIndex++;

            if (capturedContentBottom >= targetContentH || stepIndex >= MAX_STEPS) {
                finalizeAndSave();
                return;
            }

            if (stepIndex == 1 && !fixedHidden) {
                fixedHidden = true;
                // Hide position:fixed / sticky bars for continuation slices to prevent repeated navbars
                webView.evaluateJavascript(JS_HIDE_FIXED, ignored -> scrollAndSchedule(capturedContentBottom));
            } else {
                scrollAndSchedule(capturedContentBottom);
            }
        }

        private void restoreWebView() {
            dismissProgressPill();
            if (fixedHidden) {
                fixedHidden = false;
                try { webView.evaluateJavascript(JS_RESTORE_FIXED, null); }
                catch (RuntimeException ignored) { }
            }
            try {
                webView.scrollTo(origScrollX, origScrollY);
                webView.setVerticalScrollBarEnabled(origVScrollBar);
                webView.setHorizontalScrollBarEnabled(origHScrollBar);
            } catch (RuntimeException ignored) {
            }
        }

        private void abort(String errorMessage) {
            if (finished) return;
            finished = true;
            restoreWebView();
            if (!tile.isRecycled()) tile.recycle();
            if (!stitched.isRecycled()) stitched.recycle();
            BUSY.set(false);
            deliverError(cb, errorMessage);
        }

        private void finalizeAndSave() {
            if (finished) return;
            finished = true;
            restoreWebView();
            if (!tile.isRecycled()) tile.recycle();

            final int usedContentPx = Math.max(1, Math.min(
                    stitched.getHeight() - (includeFooter ? outFooterH : 0),
                    Math.round(capturedContentBottom * outScale)));
            final int finalH = Math.min(stitched.getHeight(), usedContentPx + (includeFooter ? outFooterH : 0));

            if (includeFooter && outFooterH > 0) {
                drawAttributionFooter(stitchCanvas, outW, usedContentPx, outFooterH, pageTitle, pageUrl);
            }

            final Bitmap outputBitmap;
            if (finalH < stitched.getHeight() - 2) {
                Bitmap cropped = null;
                try {
                    cropped = Bitmap.createBitmap(stitched, 0, 0, outW, finalH);
                } catch (OutOfMemoryError | IllegalArgumentException ignored) {
                }
                if (cropped != null) {
                    stitched.recycle();
                    outputBitmap = cropped;
                } else {
                    outputBitmap = stitched;
                }
            } else {
                outputBitmap = stitched;
            }

            final int savedW = outputBitmap.getWidth();
            final int savedH = outputBitmap.getHeight();
            final Context appCtx = activity.getApplicationContext();

            SAVE_IO.execute(() -> {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String displayName = "Aiko_LongShot_" + timestamp + ".png";
                try {
                    Uri savedUri = saveBitmapToMediaStore(appCtx, outputBitmap, displayName);
                    outputBitmap.recycle();
                    BUSY.set(false);
                    MAIN.post(() -> {
                        showSavedFeedback(activity, savedUri, savedW, savedH);
                        if (cb != null) cb.onSaved(savedUri, displayName, savedW, savedH);
                    });
                } catch (IOException | RuntimeException e) {
                    if (!outputBitmap.isRecycled()) outputBitmap.recycle();
                    BUSY.set(false);
                    String msg = e.getMessage();
                    deliverError(cb, msg == null || msg.isEmpty() ? "保存长图失败" : "保存长图失败：" + msg);
                }
            });
        }
    }

    private static void drawAttributionFooter(Canvas canvas, int width, int topY, int footerH,
                                              String pageTitle, String pageUrl) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        // Dark obsidian elevated footer background
        paint.setColor(AikoTheme.SURFACE);
        canvas.drawRect(0f, topY, width, topY + footerH, paint);

        // Crisp accent-to-cyan top divider gradient hairline
        float lineH = Math.max(2f, footerH * 0.025f);
        paint.setShader(new LinearGradient(0f, topY, width, topY,
                AikoTheme.ACCENT, AikoTheme.ACCENT_CYAN, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, topY, width, topY + lineH, paint);
        paint.setShader(null);

        float padX = Math.max(18f, width * 0.04f);
        float padY = Math.max(12f, footerH * 0.14f);
        int qrCardSize = Math.round(footerH - padY * 2f);
        boolean hasQr = false;

        // Render subtle mini QR code on right side if URL fits cleanly
        if (pageUrl != null && !pageUrl.isEmpty()
                && pageUrl.getBytes(StandardCharsets.UTF_8).length <= 210
                && qrCardSize >= 52) {
            try {
                int qrPad = Math.max(4, Math.round(qrCardSize * 0.08f));
                int qrPx = qrCardSize - qrPad * 2;
                Bitmap qr = QrEngine.encode(pageUrl, Math.max(qrPx, 128), 0xFF101318, 0xFFFFFFFF);
                if (qr != null) {
                    float qrLeft = width - padX - qrCardSize;
                    float qrTop = topY + (footerH - qrCardSize) * 0.5f;
                    RectF cardRect = new RectF(qrLeft, qrTop, qrLeft + qrCardSize, qrTop + qrCardSize);
                    paint.setColor(0xFFFFFFFF);
                    canvas.drawRoundRect(cardRect, Math.max(6f, qrCardSize * 0.12f),
                            Math.max(6f, qrCardSize * 0.12f), paint);
                    RectF qrDst = new RectF(qrLeft + qrPad, qrTop + qrPad,
                            qrLeft + qrPad + qrPx, qrTop + qrPad + qrPx);
                    canvas.drawBitmap(qr, null, qrDst, paint);
                    qr.recycle();
                    hasQr = true;
                }
            } catch (RuntimeException ignored) {
                hasQr = false;
            }
        }

        float textRight = hasQr ? (width - padX - qrCardSize - padX * 0.7f) : (width - padX);
        float maxTextW = Math.max(80f, textRight - padX);

        // Brand tag
        TextPaint tagPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tagPaint.setColor(AikoTheme.ACCENT);
        tagPaint.setTypeface(AikoTheme.BOLD);
        tagPaint.setTextSize(Math.max(13f, footerH * 0.145f));
        float tagY = topY + padY + tagPaint.getTextSize();
        canvas.drawText("AIKO BROWSER · 长图快照", padX, tagY, tagPaint);

        // Page title
        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(AikoTheme.TEXT);
        titlePaint.setTypeface(AikoTheme.MEDIUM);
        titlePaint.setTextSize(Math.max(17f, footerH * 0.21f));
        String titleStr = (pageTitle == null || pageTitle.isEmpty())
                ? (pageUrl == null || pageUrl.isEmpty() ? "网页截图" : HttpUrlUtil.extractHost(pageUrl))
                : pageTitle;
        CharSequence clippedTitle = TextUtils.ellipsize(titleStr, titlePaint, maxTextW, TextUtils.TruncateAt.END);
        float titleY = tagY + titlePaint.getTextSize() * 1.28f;
        canvas.drawText(clippedTitle.toString(), padX, titleY, titlePaint);

        // URL subtitle
        if (pageUrl != null && !pageUrl.isEmpty()) {
            TextPaint urlPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            urlPaint.setColor(AikoTheme.TEXT_MUTED);
            urlPaint.setTypeface(AikoTheme.MONO);
            urlPaint.setTextSize(Math.max(12f, footerH * 0.145f));
            CharSequence clippedUrl = TextUtils.ellipsize(pageUrl, urlPaint, maxTextW, TextUtils.TruncateAt.MIDDLE);
            float urlY = Math.min(topY + footerH - padY * 0.65f, titleY + urlPaint.getTextSize() * 1.45f);
            canvas.drawText(clippedUrl.toString(), padX, urlY, urlPaint);
        }
    }

    private static Uri saveBitmapToMediaStore(Context context, Bitmap bitmap, String displayName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Aiko");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("无法创建相册图片条目");
        }
        try {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("无法打开输出流");
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IOException("PNG 编码失败");
                }
                out.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        } catch (IOException | RuntimeException e) {
            try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { }
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
    }

    private static void showSavedFeedback(Activity activity, Uri uri, int w, int h) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            float d = activity.getResources().getDisplayMetrics().density;
            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(AikoTheme.dp(d, 14), AikoTheme.dp(d, 10), AikoTheme.dp(d, 10), AikoTheme.dp(d, 10));
            bar.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.STROKE_ACCENT, d, AikoTheme.dp(d, 18)));
            bar.setElevation(AikoTheme.dp(d, 12));

            TextView msg = new TextView(activity);
            msg.setText("长图已保存 · " + w + "×" + h);
            msg.setTextSize(13);
            msg.setTextColor(AikoTheme.TEXT);
            msg.setTypeface(AikoTheme.MEDIUM);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mlp.rightMargin = AikoTheme.dp(d, 10);
            bar.addView(msg, mlp);

            PopupWindow popup = new PopupWindow(bar,
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, false);
            popup.setOutsideTouchable(true);

            TextView viewBtn = makeMiniPill(activity, d, "查看", false, () -> {
                popup.dismiss();
                openImageUri(activity, uri);
            });
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            vlp.rightMargin = AikoTheme.dp(d, 6);
            bar.addView(viewBtn, vlp);

            TextView shareBtn = makeMiniPill(activity, d, "分享", true, () -> {
                popup.dismiss();
                shareImageUri(activity, uri);
            });
            bar.addView(shareBtn);

            View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            if (decor != null && decor.getWindowToken() != null) {
                popup.showAtLocation(decor, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, AikoTheme.dp(d, 84));
                MAIN.postDelayed(() -> {
                    if (popup.isShowing()) {
                        try { popup.dismiss(); } catch (RuntimeException ignored) { }
                    }
                }, 4200L);
                return;
            }
        } catch (RuntimeException ignored) {
        }
        Toast.makeText(activity, "长图已保存至 Pictures/Aiko (" + w + "×" + h + ")", Toast.LENGTH_SHORT).show();
    }

    private static TextView makeMiniPill(Activity activity, float d, String text, boolean accent, Runnable onClick) {
        TextView btn = new TextView(activity);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTypeface(AikoTheme.MEDIUM);
        btn.setTextColor(accent ? AikoTheme.onAccentColor() : AikoTheme.TEXT);
        btn.setPadding(AikoTheme.dp(d, 10), AikoTheme.dp(d, 5), AikoTheme.dp(d, 10), AikoTheme.dp(d, 5));
        btn.setBackground(AikoTheme.card(accent ? AikoTheme.ACCENT : AikoTheme.PILL,
                accent ? 0 : AikoTheme.STROKE, d, AikoTheme.dp(d, 12)));
        btn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, AikoTheme.dp(d, 12)));
        AikoTheme.attachPressFeedback(btn);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private static void openImageUri(Activity activity, Uri uri) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/png")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(view);
        } catch (RuntimeException e) {
            Toast.makeText(activity, "请在系统相册 Pictures/Aiko 查看", Toast.LENGTH_SHORT).show();
        }
    }

    private static void shareImageUri(Activity activity, Uri uri) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(share, "分享长图"));
        } catch (RuntimeException e) {
            Toast.makeText(activity, "无法打开系统分享", Toast.LENGTH_SHORT).show();
        }
    }

    private static void deliverError(Callback cb, String msg) {
        if (cb == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cb.onError(msg);
        } else {
            MAIN.post(() -> cb.onError(msg));
        }
    }

    private static int parseIntSafe(String jsVal, int fallback) {
        if (jsVal == null) return fallback;
        String clean = jsVal.trim();
        if (clean.length() >= 2 && clean.startsWith("\"") && clean.endsWith("\"")) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        if (clean.isEmpty() || "null".equals(clean) || "undefined".equals(clean)) return fallback;
        try {
            return (int) Math.round(Double.parseDouble(clean));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

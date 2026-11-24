package app.aikobrowser;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 185Hz Native Image Gallery Viewer with responsive 2-column thumbnail grid,
 * pinch-to-zoom / double-tap / pan / horizontal-fling single-image viewer,
 * bounded LRU background loader, and Android 10+ MediaStore scoped batch save.
 */
public final class GallerySheet {
    public static final class ImageItem {
        public final String url;
        public final String alt;
        public final int width;
        public final int height;

        public ImageItem(String url, String alt, int width, int height) {
            this.url = url == null ? "" : url.trim();
            this.alt = alt == null ? "" : alt.trim();
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
        }
    }

    public interface Actions {
        void toast(String msg);
        void downloadUrl(String url, String filename);
        void openUrl(String url);
    }

    private static final int MAX_IMAGE_BYTES = 24 * 1024 * 1024;
    private static final int THUMB_MAX_DIM = 560;
    private static final int FULL_MAX_DIM = 2400;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private GallerySheet() {}

    public static void show(Activity activity, String pageTitle, List<ImageItem> images, Actions actions) {
        show(activity, pageTitle, null, images, actions);
    }

    public static void show(Activity activity, String pageTitle, String pageUrl,
                            List<ImageItem> images, Actions actions) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        ArrayList<ImageItem> valid = new ArrayList<>();
        if (images != null) {
            for (ImageItem item : images) {
                if (item != null && !item.url.isEmpty()) {
                    valid.add(item);
                }
            }
        }
        if (valid.isEmpty()) {
            notifyToast(activity, actions, "当前页面未发现可预览图片");
            return;
        }
        AikoTheme.applyTheme(activity);
        new Session(activity, pageTitle == null ? "" : pageTitle.trim(), pageUrl, valid, actions).present();
    }

    private static void notifyToast(Activity activity, Actions actions, String msg) {
        if (actions != null) {
            actions.toast(msg);
        } else if (activity != null && !activity.isFinishing()) {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class Session {
        final Activity activity;
        final String pageTitle;
        final String pageUrl;
        final List<ImageItem> items;
        final Actions actions;
        final float density;
        final String userAgent;
        final Handler main = new Handler(Looper.getMainLooper());
        final ExecutorService loader;
        final LruCache<String, Bitmap> cache;
        final Map<Integer, int[]> naturalDims = new ConcurrentHashMap<>();
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicBoolean batchSaving = new AtomicBoolean(false);

        Dialog dialog;
        FrameLayout root;
        LinearLayout topBar;
        LinearLayout bottomBar;
        BrowserIcon modeBtn;
        TextView counterText;
        TextView resBadge;
        TextView batchSaveBtn;
        GridView gridView;
        FrameLayout viewerStage;
        ZoomImageView zoomView;
        ProgressBar viewerSpinner;
        TextView viewerStatus;
        TextView captionText;
        GridAdapter gridAdapter;

        boolean gridMode;
        int currentIndex;
        boolean chromeVisible = true;
        int loadGeneration;

        Session(Activity activity, String pageTitle, String pageUrl, List<ImageItem> items, Actions actions) {
            this.activity = activity;
            this.pageTitle = pageTitle;
            this.pageUrl = pageUrl;
            this.items = items;
            this.actions = actions;
            this.density = activity.getResources().getDisplayMetrics().density;
            String ua;
            try {
                ua = WebSettings.getDefaultUserAgent(activity);
            } catch (RuntimeException ignored) {
                ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36";
            }
            this.userAgent = ua;
            this.gridMode = items.size() > 1;
            this.currentIndex = 0;

            for (int i = 0; i < items.size(); i++) {
                ImageItem item = items.get(i);
                if (item.width > 0 && item.height > 0) {
                    naturalDims.put(i, new int[]{item.width, item.height});
                }
            }

            final AtomicInteger threadSeq = new AtomicInteger(1);
            this.loader = Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(() -> {
                    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
                    catch (SecurityException ignored) { }
                    r.run();
                }, "aiko-gallery-" + threadSeq.getAndIncrement());
                t.setDaemon(true);
                return t;
            });

            int maxKb = (int) Math.min(32L * 1024L, Math.max(8L * 1024L, Runtime.getRuntime().maxMemory() / 8192L));
            this.cache = new LruCache<String, Bitmap>(maxKb) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getByteCount() / 1024);
                }
            };
        }

        void present() {
            dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar) {
                @Override
                public void onBackPressed() {
                    if (!gridMode && items.size() > 1) {
                        switchToGrid();
                    } else {
                        dismiss();
                    }
                }
            };
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            root = new FrameLayout(activity);
            root.setBackgroundColor(0xFF07090E);

            buildGridView();
            buildViewerStage();
            buildTopBar();
            buildBottomBar();

            // Apply safe system bar insets so chrome never overlaps cutout or gesture bar
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int topInset;
                int bottomInset;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
                    topInset = sys.top;
                    bottomInset = sys.bottom;
                } else {
                    @SuppressWarnings("deprecation")
                    int t = insets.getSystemWindowInsetTop();
                    @SuppressWarnings("deprecation")
                    int b = insets.getSystemWindowInsetBottom();
                    topInset = t;
                    bottomInset = b;
                }
                topBar.setPadding(dp(12), topInset + dp(8), dp(12), dp(10));
                bottomBar.setPadding(dp(12), dp(10), dp(12), bottomInset + dp(12));
                gridView.setPadding(dp(10), topInset + dp(66), dp(10), bottomInset + dp(18));
                return insets;
            });

            dialog.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
            Window win = dialog.getWindow();
            if (win != null) {
                win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                win.setBackgroundDrawableResource(android.R.color.transparent);
                win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                win.setStatusBarColor(0xCC07090E);
                win.setNavigationBarColor(0xCC07090E);
            }

            dialog.setOnDismissListener(d -> {
                closed.set(true);
                loader.shutdownNow();
                cache.evictAll();
            });

            dialog.show();
            applyModeUi();
            if (!gridMode) {
                loadViewerImage(currentIndex, 0);
            }
        }

        private void buildGridView() {
            gridView = new GridView(activity);
            int sw = activity.getResources().getConfiguration().screenWidthDp;
            gridView.setNumColumns(sw >= 640 ? 3 : 2);
            gridView.setHorizontalSpacing(dp(10));
            gridView.setVerticalSpacing(dp(10));
            gridView.setClipToPadding(false);
            gridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            gridView.setVerticalScrollBarEnabled(false);
            gridView.setPadding(dp(10), dp(68), dp(10), dp(20));

            gridAdapter = new GridAdapter();
            gridView.setAdapter(gridAdapter);
            gridView.setOnItemClickListener((parent, view, position, id) -> openSingle(position));

            root.addView(gridView, new FrameLayout.LayoutParams(-1, -1));
        }

        private void buildViewerStage() {
            viewerStage = new FrameLayout(activity);
            viewerStage.setBackgroundColor(0xFF05070B);

            zoomView = new ZoomImageView(activity, new ZoomImageView.Listener() {
                @Override
                public void onSingleTap() {
                    toggleChrome();
                }

                @Override
                public void onNavigate(int delta) {
                    stepImage(delta);
                }
            });
            viewerStage.addView(zoomView, new FrameLayout.LayoutParams(-1, -1));

            viewerSpinner = new ProgressBar(activity);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER);
            viewerStage.addView(viewerSpinner, slp);

            viewerStatus = new TextView(activity);
            viewerStatus.setTextSize(13);
            viewerStatus.setTextColor(AikoTheme.TEXT_MUTED);
            viewerStatus.setGravity(Gravity.CENTER);
            viewerStatus.setPadding(dp(18), dp(10), dp(18), dp(10));
            viewerStatus.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(14)));
            viewerStatus.setVisibility(View.GONE);
            viewerStatus.setOnClickListener(v -> loadViewerImage(currentIndex, 0));
            viewerStage.addView(viewerStatus, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

            root.addView(viewerStage, new FrameLayout.LayoutParams(-1, -1));
        }

        private void buildTopBar() {
            topBar = new LinearLayout(activity);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            topBar.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable topGrad = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xE807090E, 0xB807090E, 0x0007090E});
            topBar.setBackground(topGrad);

            modeBtn = new BrowserIcon(activity, BrowserIcon.Kind.GRID);
            modeBtn.tint(AikoTheme.TEXT);
            modeBtn.setContentDescription("切换网格视图");
            modeBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(20)));
            AikoTheme.attachPressFeedback(modeBtn);
            modeBtn.setOnClickListener(v -> {
                if (gridMode) openSingle(currentIndex);
                else switchToGrid();
            });
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(40), dp(40));
            mlp.rightMargin = dp(6);
            topBar.addView(modeBtn, mlp);

            LinearLayout infoCol = new LinearLayout(activity);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1f);
            ilp.rightMargin = dp(8);
            topBar.addView(infoCol, ilp);

            LinearLayout rowTop = new LinearLayout(activity);
            rowTop.setOrientation(LinearLayout.HORIZONTAL);
            rowTop.setGravity(Gravity.CENTER_VERTICAL);
            infoCol.addView(rowTop);

            counterText = new TextView(activity);
            counterText.setTextSize(15);
            counterText.setTextColor(AikoTheme.TEXT);
            counterText.setTypeface(AikoTheme.BOLD);
            counterText.setSingleLine(true);
            rowTop.addView(counterText);

            resBadge = AikoTheme.badge(activity, density, "", AikoTheme.ACCENT_CYAN,
                    AikoTheme.CARD, AikoTheme.STROKE);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.leftMargin = dp(8);
            rowTop.addView(resBadge, blp);

            if (!pageTitle.isEmpty()) {
                TextView sub = new TextView(activity);
                sub.setText(pageTitle);
                sub.setTextSize(11);
                sub.setTextColor(AikoTheme.TEXT_MUTED);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                infoCol.addView(sub);
            }

            batchSaveBtn = new TextView(activity);
            batchSaveBtn.setText("批量保存全部");
            batchSaveBtn.setTextSize(12);
            batchSaveBtn.setTextColor(AikoTheme.onAccentColor());
            batchSaveBtn.setTypeface(AikoTheme.MEDIUM);
            batchSaveBtn.setSingleLine(true);
            batchSaveBtn.setGravity(Gravity.CENTER);
            batchSaveBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
            batchSaveBtn.setBackground(AikoTheme.rounded(AikoTheme.ACCENT, dp(16)));
            batchSaveBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(16)));
            AikoTheme.attachPressFeedback(batchSaveBtn);
            batchSaveBtn.setOnClickListener(v -> startBatchSave());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, dp(34));
            slp.rightMargin = dp(6);
            topBar.addView(batchSaveBtn, slp);

            BrowserIcon closeBtn = new BrowserIcon(activity, BrowserIcon.Kind.CLOSE);
            closeBtn.tint(AikoTheme.TEXT);
            closeBtn.setContentDescription("关闭画廊");
            closeBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(20)));
            AikoTheme.attachPressFeedback(closeBtn);
            closeBtn.setOnClickListener(v -> dialog.dismiss());
            topBar.addView(closeBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

            root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        }

        private void buildBottomBar() {
            bottomBar = new LinearLayout(activity);
            bottomBar.setOrientation(LinearLayout.VERTICAL);
            bottomBar.setPadding(dp(12), dp(10), dp(12), dp(14));
            GradientDrawable botGrad = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xF007090E, 0xCC07090E, 0x0007090E});
            bottomBar.setBackground(botGrad);

            captionText = new TextView(activity);
            captionText.setTextSize(12);
            captionText.setTextColor(AikoTheme.TEXT_MUTED);
            captionText.setMaxLines(2);
            captionText.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.bottomMargin = dp(10);
            clp.leftMargin = dp(4);
            clp.rightMargin = dp(4);
            bottomBar.addView(captionText, clp);

            LinearLayout btnRow = new LinearLayout(activity);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.CENTER_VERTICAL);
            bottomBar.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));

            addBottomAction(btnRow, BrowserIcon.Kind.DOWNLOAD, "保存原图", true, this::saveCurrentImage);
            addBottomAction(btnRow, BrowserIcon.Kind.COPY, "复制链接", false, this::copyCurrentUrl);
            addBottomAction(btnRow, BrowserIcon.Kind.SEARCH, "以图搜图", false, this::showReverseSearchPicker);
            addBottomAction(btnRow, BrowserIcon.Kind.SHARE, "分享链接", false, this::shareCurrentUrl);

            root.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        }

        private void addBottomAction(LinearLayout row, BrowserIcon.Kind iconKind, String label,
                                     boolean primary, Runnable onClick) {
            LinearLayout btn = new LinearLayout(activity);
            btn.setOrientation(LinearLayout.VERTICAL);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(6), dp(8), dp(6), dp(8));
            btn.setBackground(AikoTheme.card(
                    primary ? AikoTheme.HIGHLIGHT : AikoTheme.CARD,
                    primary ? AikoTheme.STROKE_ACCENT : AikoTheme.STROKE,
                    density, dp(14)));
            btn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
            AikoTheme.attachPressFeedback(btn, 0.94f);

            BrowserIcon icon = new BrowserIcon(activity, iconKind);
            icon.tint(primary ? AikoTheme.ACCENT : AikoTheme.TEXT);
            btn.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

            TextView tv = new TextView(activity);
            tv.setText(label);
            tv.setTextSize(11);
            tv.setTextColor(primary ? AikoTheme.ACCENT : AikoTheme.TEXT);
            tv.setTypeface(AikoTheme.MEDIUM);
            tv.setSingleLine(true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.topMargin = dp(4);
            btn.addView(tv, tlp);

            btn.setOnClickListener(v -> onClick.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            row.addView(btn, lp);
        }

        private void applyModeUi() {
            if (gridMode) {
                gridView.setVisibility(View.VISIBLE);
                viewerStage.setVisibility(View.GONE);
                bottomBar.setVisibility(View.GONE);
                topBar.setAlpha(1f);
                topBar.setVisibility(View.VISIBLE);
                chromeVisible = true;
                modeBtn.symbol(BrowserIcon.Kind.GALLERY);
                modeBtn.setVisibility(items.size() > 1 ? View.VISIBLE : View.GONE);
                counterText.setText("共 " + items.size() + " 张图片");
                resBadge.setVisibility(View.GONE);
            } else {
                gridView.setVisibility(View.GONE);
                viewerStage.setVisibility(View.VISIBLE);
                bottomBar.setVisibility(View.VISIBLE);
                modeBtn.symbol(BrowserIcon.Kind.GRID);
                modeBtn.setVisibility(items.size() > 1 ? View.VISIBLE : View.GONE);
                updateViewerMetadata();
            }
        }

        private void openSingle(int index) {
            currentIndex = Math.max(0, Math.min(items.size() - 1, index));
            gridMode = false;
            chromeVisible = true;
            topBar.setAlpha(1f);
            bottomBar.setAlpha(1f);
            applyModeUi();
            loadViewerImage(currentIndex, 0);
        }

        private void switchToGrid() {
            gridMode = true;
            applyModeUi();
            if (currentIndex >= 0 && currentIndex < items.size()) {
                gridView.smoothScrollToPosition(currentIndex);
            }
        }

        private void toggleChrome() {
            if (gridMode) return;
            chromeVisible = !chromeVisible;
            animateChromeBar(topBar, chromeVisible);
            animateChromeBar(bottomBar, chromeVisible);
        }

        private void animateChromeBar(View bar, boolean show) {
            bar.animate().cancel();
            bar.setLayerType(View.LAYER_TYPE_NONE, null);
            if (show) {
                bar.setVisibility(View.VISIBLE);
                bar.animate()
                        .alpha(1f)
                        .setDuration(150)
                        .setInterpolator(AikoTheme.EASE_OUT)
                        .withLayer()
                        .withEndAction(() -> bar.setLayerType(View.LAYER_TYPE_NONE, null))
                        .start();
            } else {
                bar.animate()
                        .alpha(0f)
                        .setDuration(140)
                        .setInterpolator(AikoTheme.FAST_OUT)
                        .withLayer()
                        .withEndAction(() -> {
                            bar.setLayerType(View.LAYER_TYPE_NONE, null);
                            if (!chromeVisible) bar.setVisibility(View.GONE);
                        })
                        .start();
            }
        }

        private void stepImage(int delta) {
            if (items.size() <= 1) return;
            int next = currentIndex + delta;
            if (next < 0 || next >= items.size()) {
                // Soft rubber-band snap back at edges
                zoomView.animate().translationX(0f).setDuration(140).setInterpolator(AikoTheme.SPRING_SETTLE).start();
                notifyToast(activity, actions, next < 0 ? "已经是第一张" : "已经是最后一张");
                return;
            }
            currentIndex = next;
            loadViewerImage(currentIndex, delta);
        }

        private void updateViewerMetadata() {
            counterText.setText((currentIndex + 1) + " / " + items.size());
            ImageItem item = items.get(currentIndex);
            int[] dims = naturalDims.get(currentIndex);
            int w = dims != null ? dims[0] : item.width;
            int h = dims != null ? dims[1] : item.height;
            if (w > 0 && h > 0) {
                resBadge.setText(w + "×" + h);
                resBadge.setVisibility(View.VISIBLE);
            } else {
                resBadge.setVisibility(View.GONE);
            }
            String cap = !item.alt.isEmpty() ? item.alt : HttpUrlUtil.extractHost(item.url);
            if (cap.isEmpty()) {
                captionText.setVisibility(View.GONE);
            } else {
                captionText.setText(cap);
                captionText.setVisibility(View.VISIBLE);
            }
        }

        private void loadViewerImage(int index, int slideDir) {
            final int gen = ++loadGeneration;
            final ImageItem item = items.get(index);
            updateViewerMetadata();
            viewerStatus.setVisibility(View.GONE);

            String fullKey = "full:" + item.url;
            Bitmap cachedFull = cache.get(fullKey);
            if (cachedFull != null && !cachedFull.isRecycled()) {
                viewerSpinner.setVisibility(View.GONE);
                setZoomBitmapWithSlide(cachedFull, slideDir);
                prefetchAdjacent(index);
                return;
            }

            // Show cached thumbnail immediately as crisp placeholder while full image decodes
            Bitmap cachedThumb = cache.get("thumb:" + item.url);
            if (cachedThumb != null && !cachedThumb.isRecycled()) {
                setZoomBitmapWithSlide(cachedThumb, slideDir);
            } else {
                zoomView.setBitmap(null);
            }
            if (closed.get()) return;
            viewerSpinner.setVisibility(View.VISIBLE);

            try {
                loader.execute(() -> {
                    if (closed.get()) return;
                    DecodedImage decoded = fetchAndDecode(item.url, FULL_MAX_DIM);
                    if (closed.get()) return;
                    if (decoded != null && decoded.bitmap != null) {
                        cache.put(fullKey, decoded.bitmap);
                        if (decoded.naturalW > 0 && decoded.naturalH > 0) {
                            naturalDims.put(index, new int[]{decoded.naturalW, decoded.naturalH});
                        }
                        main.post(() -> {
                            if (closed.get() || gen != loadGeneration) return;
                            viewerSpinner.setVisibility(View.GONE);
                            viewerStatus.setVisibility(View.GONE);
                            setZoomBitmapWithSlide(decoded.bitmap, cachedThumb == null ? slideDir : 0);
                            updateViewerMetadata();
                            prefetchAdjacent(index);
                        });
                    } else {
                        main.post(() -> {
                            if (closed.get() || gen != loadGeneration) return;
                            viewerSpinner.setVisibility(View.GONE);
                            if (cachedThumb == null) {
                                viewerStatus.setText("图片加载失败 · 点击重试");
                                viewerStatus.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                });
            } catch (RejectedExecutionException ignored) {
                viewerSpinner.setVisibility(View.GONE);
            }
        }

        private void setZoomBitmapWithSlide(Bitmap bmp, int slideDir) {
            zoomView.animate().cancel();
            zoomView.setBitmap(bmp);
            if (slideDir != 0) {
                float startX = slideDir > 0 ? dp(48) : -dp(48);
                zoomView.setTranslationX(startX);
                zoomView.setAlpha(0.55f);
                zoomView.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(155)
                        .setInterpolator(AikoTheme.EMPHASIZED_DECEL)
                        .withLayer()
                        .withEndAction(() -> zoomView.setLayerType(View.LAYER_TYPE_NONE, null))
                        .start();
            } else {
                zoomView.setTranslationX(0f);
                zoomView.setAlpha(1f);
            }
        }

        private void prefetchAdjacent(int center) {
            int[] neighbors = {center + 1, center - 1};
            for (int n : neighbors) {
                if (n < 0 || n >= items.size()) continue;
                ImageItem item = items.get(n);
                String key = "full:" + item.url;
                if (cache.get(key) != null) continue;
                final int idx = n;
                try {
                    loader.execute(() -> {
                        if (closed.get() || cache.get(key) != null) return;
                        DecodedImage dec = fetchAndDecode(item.url, FULL_MAX_DIM);
                        if (dec != null && dec.bitmap != null && !closed.get()) {
                            cache.put(key, dec.bitmap);
                            if (dec.naturalW > 0 && dec.naturalH > 0) {
                                naturalDims.put(idx, new int[]{dec.naturalW, dec.naturalH});
                            }
                        }
                    });
                } catch (RuntimeException ignored) {
                }
            }
        }

        private void saveCurrentImage() {
            if (closed.get()) return;
            ImageItem item = items.get(currentIndex);
            notifyToast(activity, actions, "正在保存原图…");
            Context appCtx = activity.getApplicationContext();
            try {
                loader.execute(() -> {
                    if (closed.get()) return;
                    boolean ok = saveOriginalToMediaStore(appCtx, item.url, currentIndex + 1);
                    main.post(() -> {
                        if (ok) {
                            notifyToast(activity, actions, "已保存原图至 Pictures/Aiko");
                        } else if (actions != null && HttpUrlUtil.isHttp(item.url)) {
                            actions.downloadUrl(item.url, guessImageFilename(item.url, "image/jpeg", currentIndex + 1));
                        } else {
                            notifyToast(activity, actions, "保存图片失败");
                        }
                    });
                });
            } catch (RejectedExecutionException ignored) {
            }
        }

        private void startBatchSave() {
            if (closed.get() || !batchSaving.compareAndSet(false, true)) return;
            final int total = items.size();
            batchSaveBtn.setEnabled(false);
            batchSaveBtn.setText("保存中 0/" + total);
            Context appCtx = activity.getApplicationContext();

            try {
                loader.execute(() -> {
                    int saved = 0;
                    for (int i = 0; i < total; i++) {
                        if (closed.get()) break;
                        if (saveOriginalToMediaStore(appCtx, items.get(i).url, i + 1)) {
                            saved++;
                        }
                        final int done = i + 1;
                        main.post(() -> {
                            if (!closed.get()) {
                                batchSaveBtn.setText("保存中 " + done + "/" + total);
                            }
                        });
                    }
                    final int finalSaved = saved;
                    batchSaving.set(false);
                    main.post(() -> {
                        if (!closed.get()) {
                            batchSaveBtn.setEnabled(true);
                            batchSaveBtn.setText("批量保存全部");
                        }
                        notifyToast(activity, actions,
                                "批量保存完成：" + finalSaved + " / " + total + " 张已存入 Pictures/Aiko");
                    });
                });
            } catch (RejectedExecutionException ignored) {
                batchSaving.set(false);
                batchSaveBtn.setEnabled(true);
                batchSaveBtn.setText("批量保存全部");
            }
        }

        private void copyCurrentUrl() {
            String url = items.get(currentIndex).url;
            try {
                ClipboardManager cm = activity.getSystemService(ClipboardManager.class);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("图片链接", url));
                }
                notifyToast(activity, actions, "已复制图片链接");
            } catch (RuntimeException e) {
                notifyToast(activity, actions, "复制失败");
            }
        }

        private void shareCurrentUrl() {
            String url = items.get(currentIndex).url;
            try {
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, url);
                activity.startActivity(Intent.createChooser(share, "分享图片链接"));
            } catch (RuntimeException e) {
                notifyToast(activity, actions, "无法打开分享菜单");
            }
        }

        private void showReverseSearchPicker() {
            String imgUrl = items.get(currentIndex).url;
            if (!HttpUrlUtil.isHttp(imgUrl)) {
                notifyToast(activity, actions, "仅支持对网络图片执行以图搜图");
                return;
            }
            Dialog picker = new Dialog(activity);
            picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout panel = AikoTheme.sheetContainer(activity, density, "以图搜图", picker::dismiss);

            FlowTagLayout chips = new FlowTagLayout(activity, dp(8), dp(8));
            String[][] engines = {
                    {"Google Lens", "https://lens.google.com/uploadbyurl?url="},
                    {"Yandex 识图", "https://yandex.com/images/search?rpt=imageview&url="},
                    {"SauceNAO", "https://saucenao.com/search.php?url="},
                    {"Bing 视觉搜索", "https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIVSP&q=imgurl:"}
            };
            String encoded = urlEncodeUtf8(imgUrl);
            for (String[] eng : engines) {
                TextView chip = new TextView(activity);
                chip.setText(eng[0]);
                chip.setTextSize(13);
                chip.setTextColor(AikoTheme.TEXT);
                chip.setTypeface(AikoTheme.MEDIUM);
                chip.setPadding(dp(14), dp(10), dp(14), dp(10));
                chip.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(14)));
                chip.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));
                AikoTheme.attachPressFeedback(chip);
                String targetUrl = eng[1] + encoded;
                chip.setOnClickListener(v -> {
                    picker.dismiss();
                    dialog.dismiss();
                    if (actions != null) actions.openUrl(targetUrl);
                });
                chips.addView(chip);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            lp.bottomMargin = dp(8);
            panel.addView(chips, lp);

            AikoTheme.presentSheet(picker, panel, null, density);
        }

        private DecodedImage fetchAndDecode(String url, int maxDim) {
            RawImage raw = fetchImageBytes(url);
            if (raw == null || raw.bytes.length == 0) return null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.length, bounds);
                int nw = bounds.outWidth;
                int nh = bounds.outHeight;
                if (nw <= 0 || nh <= 0) return null;

                int sample = 1;
                while ((nw / sample) > maxDim * 1.4 || (nh / sample) > maxDim * 1.4) {
                    sample *= 2;
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.length, opts);
                if (bmp == null) return null;
                return new DecodedImage(bmp, nw, nh);
            } catch (OutOfMemoryError | RuntimeException ignored) {
                return null;
            }
        }

        private RawImage fetchImageBytes(String urlStr) {
            if (urlStr == null || urlStr.isEmpty()) return null;
            if (urlStr.startsWith("data:")) {
                int comma = urlStr.indexOf(',');
                if (comma < 0) return null;
                String meta = urlStr.substring(5, comma).toLowerCase(Locale.ROOT);
                String mime = "image/png";
                int semi = meta.indexOf(';');
                if (semi > 0) mime = meta.substring(0, semi);
                try {
                    String payload = urlStr.substring(comma + 1);
                    byte[] data = meta.contains(";base64")
                            ? Base64.decode(payload, Base64.DEFAULT)
                            : payload.getBytes("UTF-8");
                    return new RawImage(data, mime);
                } catch (IllegalArgumentException | UnsupportedEncodingException ignored) {
                    return null;
                }
            }
            if (!HttpUrlUtil.isHttp(urlStr)) return null;

            HttpURLConnection conn = null;
            try {
                URL u = new URL(urlStr);
                conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                String referer = (pageUrl != null && HttpUrlUtil.isHttp(pageUrl))
                        ? pageUrl : HttpUrlUtil.extractOrigin(urlStr);
                if (referer != null && !referer.isEmpty()) {
                    conn.setRequestProperty("Referer", referer);
                }
                try {
                    String cookie = CookieManager.getInstance().getCookie(urlStr);
                    if (cookie != null && !cookie.isEmpty()) {
                        conn.setRequestProperty("Cookie", cookie);
                    }
                } catch (RuntimeException ignored) {
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) return null;
                String ct = conn.getContentType();
                String mime = normalizeImageMime(ct, urlStr);
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream(32768)) {
                    byte[] buf = new byte[16384];
                    int total = 0;
                    for (int n; (n = in.read(buf)) >= 0; ) {
                        total += n;
                        if (total > MAX_IMAGE_BYTES) return null;
                        out.write(buf, 0, n);
                    }
                    return new RawImage(out.toByteArray(), mime);
                }
            } catch (IOException | RuntimeException ignored) {
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private boolean saveOriginalToMediaStore(Context context, String urlStr, int seq) {
            RawImage raw = fetchImageBytes(urlStr);
            if (raw == null || raw.bytes.length == 0) return false;

            String mime = raw.mime;
            String filename = guessImageFilename(urlStr, mime, seq);
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Aiko");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = null;
            try {
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IOException("No output stream");
                    out.write(raw.bytes);
                    out.flush();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
                return true;
            } catch (IOException | RuntimeException e) {
                if (uri != null) {
                    try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { }
                }
                return false;
            }
        }

        private final class GridAdapter extends BaseAdapter {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int pos) { return items.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                ThumbHolder holder;
                FrameLayout card;
                if (convertView instanceof FrameLayout && convertView.getTag() instanceof ThumbHolder) {
                    card = (FrameLayout) convertView;
                    holder = (ThumbHolder) convertView.getTag();
                } else {
                    card = new FrameLayout(activity);
                    card.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, density, dp(14)));
                    card.setClipToOutline(true);
                    card.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(14)));

                    ImageView img = new ImageView(activity);
                    img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    card.addView(img, new FrameLayout.LayoutParams(-1, dp(176)));

                    TextView badge = new TextView(activity);
                    badge.setTextSize(10);
                    badge.setTextColor(0xFFF3F6FC);
                    badge.setTypeface(AikoTheme.MEDIUM);
                    badge.setPadding(dp(7), dp(3), dp(7), dp(3));
                    badge.setBackground(AikoTheme.rounded(0xB307090E, dp(8)));
                    FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
                    blp.setMargins(dp(6), dp(6), dp(6), dp(6));
                    card.addView(badge, blp);

                    holder = new ThumbHolder(img, badge);
                    card.setTag(holder);
                }

                ImageItem item = items.get(pos);
                holder.boundUrl = item.url;
                int[] dims = naturalDims.get(pos);
                int w = dims != null ? dims[0] : item.width;
                int h = dims != null ? dims[1] : item.height;
                if (w > 0 && h > 0) {
                    holder.badge.setText(w + "×" + h);
                    holder.badge.setVisibility(View.VISIBLE);
                } else {
                    holder.badge.setVisibility(View.GONE);
                }

                String thumbKey = "thumb:" + item.url;
                Bitmap cached = cache.get(thumbKey);
                if (cached == null) cached = cache.get("full:" + item.url);
                if (cached != null && !cached.isRecycled()) {
                    holder.image.setImageBitmap(cached);
                } else {
                    holder.image.setImageDrawable(null);
                    final String targetUrl = item.url;
                    try {
                        loader.execute(() -> {
                            if (closed.get()) return;
                            DecodedImage dec = fetchAndDecode(targetUrl, THUMB_MAX_DIM);
                            if (dec != null && dec.bitmap != null && !closed.get()) {
                                cache.put(thumbKey, dec.bitmap);
                                if (dec.naturalW > 0 && dec.naturalH > 0) {
                                    naturalDims.put(pos, new int[]{dec.naturalW, dec.naturalH});
                                }
                                main.post(() -> {
                                    if (!closed.get() && targetUrl.equals(holder.boundUrl)) {
                                        holder.image.setImageBitmap(dec.bitmap);
                                        if (dec.naturalW > 0 && dec.naturalH > 0) {
                                            holder.badge.setText(dec.naturalW + "×" + dec.naturalH);
                                            holder.badge.setVisibility(View.VISIBLE);
                                        }
                                    }
                                });
                            }
                        });
                    } catch (RuntimeException ignored) {
                    }
                }
                return card;
            }
        }

        private int dp(int v) {
            return AikoTheme.dp(density, v);
        }
    }

    private static final class ThumbHolder {
        final ImageView image;
        final TextView badge;
        String boundUrl;

        ThumbHolder(ImageView image, TextView badge) {
            this.image = image;
            this.badge = badge;
        }
    }

    private static final class RawImage {
        final byte[] bytes;
        final String mime;

        RawImage(byte[] bytes, String mime) {
            this.bytes = bytes;
            this.mime = mime;
        }
    }

    private static final class DecodedImage {
        final Bitmap bitmap;
        final int naturalW;
        final int naturalH;

        DecodedImage(Bitmap bitmap, int naturalW, int naturalH) {
            this.bitmap = bitmap;
            this.naturalW = naturalW;
            this.naturalH = naturalH;
        }
    }

    private static String normalizeImageMime(String contentType, String url) {
        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            int semi = ct.indexOf(';');
            if (semi > 0) ct = ct.substring(0, semi).trim();
            if (ct.startsWith("image/")) return ct;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".webp")) return "image/webp";
        if (lower.contains(".gif")) return "image/gif";
        if (lower.contains(".avif")) return "image/avif";
        return "image/jpeg";
    }

    private static String guessImageFilename(String url, String mime, int seq) {
        String ext = ".jpg";
        if ("image/png".equals(mime)) ext = ".png";
        else if ("image/webp".equals(mime)) ext = ".webp";
        else if ("image/gif".equals(mime)) ext = ".gif";
        else if ("image/avif".equals(mime)) ext = ".avif";

        String guessed = HttpUrlUtil.isHttp(url) ? URLUtil.guessFileName(url, null, mime) : "";
        String clean = guessed == null ? "" : guessed.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty() || clean.equals("downloadfile.bin") || clean.length() > 96) {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            return "Aiko_Img_" + ts + "_" + seq + ext;
        }
        int dot = clean.lastIndexOf('.');
        if (dot <= 0) clean = clean + ext;
        return clean;
    }

    private static String urlEncodeUtf8(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    /**
     * Zero-onDraw-allocation 185Hz pinch-to-zoom, double-tap, pan, and horizontal-fling viewer.
     */
    private static final class ZoomImageView extends View {
        interface Listener {
            void onSingleTap();
            void onNavigate(int delta);
        }

        private static final float MIN_SCALE = 1.0f;
        private static final float MID_SCALE = 2.5f;
        private static final float MAX_SCALE = 5.0f;

        private final Listener listener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix drawMatrix = new Matrix();
        private final float[] matrixVals = new float[9];
        private final RectF imageBounds = new RectF();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestureDetector;
        private final float swipeThresholdPx;

        private Bitmap bitmap;
        private float baseScale = 1f;
        private float zoomFactor = 1f;
        private float swipeDragX;
        private boolean draggingSwipe;
        private ValueAnimator zoomAnimator;

        ZoomImageView(Context ctx, Listener listener) {
            super(ctx);
            this.listener = listener;
            float d = ctx.getResources().getDisplayMetrics().density;
            this.swipeThresholdPx = Math.max(dp(d, 64), ViewConfiguration.get(ctx).getScaledTouchSlop() * 4f);

            scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    if (bitmap == null) return false;
                    cancelZoomAnim();
                    float target = clamp(zoomFactor * detector.getScaleFactor(), MIN_SCALE, MAX_SCALE);
                    float ratio = target / zoomFactor;
                    zoomFactor = target;
                    drawMatrix.postScale(ratio, ratio, detector.getFocusX(), detector.getFocusY());
                    clampMatrixToBounds();
                    invalidate();
                    return true;
                }
            });

            gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (listener != null) listener.onSingleTap();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (bitmap == null) return false;
                    float target = zoomFactor > 1.35f ? MIN_SCALE : MID_SCALE;
                    animateZoomTo(target, e.getX(), e.getY());
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (bitmap == null) return false;
                    if (zoomFactor > 1.02f) {
                        drawMatrix.postTranslate(-dx, -dy);
                        clampMatrixToBounds();
                        invalidate();
                        return true;
                    }
                    // At 1x scale, track horizontal swipe for prev/next photo
                    if (!draggingSwipe && Math.abs(dx) > Math.abs(dy) * 1.15f) {
                        draggingSwipe = true;
                    }
                    if (draggingSwipe) {
                        swipeDragX -= dx;
                        setTranslationX(swipeDragX * 0.72f);
                        return true;
                    }
                    return false;
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    if (bitmap != null && zoomFactor <= 1.02f && Math.abs(vx) > 650f && Math.abs(vx) > Math.abs(vy)) {
                        draggingSwipe = false;
                        swipeDragX = 0f;
                        if (listener != null) listener.onNavigate(vx < 0 ? 1 : -1);
                        return true;
                    }
                    return false;
                }
            });
        }

        void setBitmap(Bitmap next) {
            cancelZoomAnim();
            this.bitmap = next;
            this.zoomFactor = 1f;
            this.swipeDragX = 0f;
            this.draggingSwipe = false;
            fitToView();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            fitToView();
        }

        private void fitToView() {
            int vw = getWidth();
            int vh = getHeight();
            if (bitmap == null || bitmap.isRecycled() || vw <= 0 || vh <= 0) {
                drawMatrix.reset();
                return;
            }
            float bw = bitmap.getWidth();
            float bh = bitmap.getHeight();
            baseScale = Math.min(vw / bw, vh / bh);
            zoomFactor = 1f;
            float dx = (vw - bw * baseScale) * 0.5f;
            float dy = (vh - bh * baseScale) * 0.5f;
            drawMatrix.reset();
            drawMatrix.postScale(baseScale, baseScale);
            drawMatrix.postTranslate(dx, dy);
        }

        private void animateZoomTo(float targetZoom, float focusX, float focusY) {
            cancelZoomAnim();
            final float startZoom = zoomFactor;
            final float[] prev = {startZoom};
            zoomAnimator = ValueAnimator.ofFloat(startZoom, targetZoom);
            zoomAnimator.setDuration(190);
            zoomAnimator.setInterpolator(AikoTheme.EMPHASIZED_DECEL);
            zoomAnimator.addUpdateListener(anim -> {
                float cur = (float) anim.getAnimatedValue();
                float step = cur / prev[0];
                prev[0] = cur;
                zoomFactor = cur;
                if (Math.abs(cur - MIN_SCALE) < 0.01f && targetZoom == MIN_SCALE) {
                    fitToView();
                } else {
                    drawMatrix.postScale(step, step, focusX, focusY);
                    clampMatrixToBounds();
                }
                invalidate();
            });
            zoomAnimator.start();
        }

        private void cancelZoomAnim() {
            if (zoomAnimator != null) {
                zoomAnimator.cancel();
                zoomAnimator = null;
            }
        }

        private void clampMatrixToBounds() {
            if (bitmap == null || bitmap.isRecycled()) return;
            imageBounds.set(0f, 0f, bitmap.getWidth(), bitmap.getHeight());
            drawMatrix.mapRect(imageBounds);
            float vw = getWidth();
            float vh = getHeight();
            float dx = 0f;
            float dy = 0f;

            if (imageBounds.width() <= vw) {
                dx = (vw - imageBounds.width()) * 0.5f - imageBounds.left;
            } else if (imageBounds.left > 0f) {
                dx = -imageBounds.left;
            } else if (imageBounds.right < vw) {
                dx = vw - imageBounds.right;
            }

            if (imageBounds.height() <= vh) {
                dy = (vh - imageBounds.height()) * 0.5f - imageBounds.top;
            } else if (imageBounds.top > 0f) {
                dy = -imageBounds.top;
            } else if (imageBounds.bottom < vh) {
                dy = vh - imageBounds.bottom;
            }

            if (dx != 0f || dy != 0f) {
                drawMatrix.postTranslate(dx, dy);
            }
            drawMatrix.getValues(matrixVals);
            if (baseScale > 0f) {
                zoomFactor = clamp(matrixVals[Matrix.MSCALE_X] / baseScale, MIN_SCALE, MAX_SCALE);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            boolean handled = scaleDetector.onTouchEvent(event);
            if (!scaleDetector.isInProgress()) {
                handled = gestureDetector.onTouchEvent(event) || handled;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (draggingSwipe) {
                    float dragged = swipeDragX;
                    draggingSwipe = false;
                    swipeDragX = 0f;
                    if (Math.abs(dragged) >= swipeThresholdPx && listener != null) {
                        listener.onNavigate(dragged < 0 ? 1 : -1);
                    } else {
                        animate().translationX(0f).setDuration(140).setInterpolator(AikoTheme.SPRING_SETTLE).start();
                    }
                }
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bitmap != null && !bitmap.isRecycled()) {
                canvas.drawBitmap(bitmap, drawMatrix, paint);
            }
        }

        private static float clamp(float v, float min, float max) {
            return v < min ? min : (v > max ? max : v);
        }

        private static float dp(float d, int v) {
            return v * d;
        }
    }
}

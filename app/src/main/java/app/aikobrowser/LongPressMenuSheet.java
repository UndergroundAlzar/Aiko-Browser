package app.aikobrowser;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Super Link & Image Material Context Menu Sheet replacing basic AlertDialog menus.
 * Conditionally builds Link actions, Image actions, Reverse Image Search chips (using FlowTagLayout),
 * and Share/QR/Copy chips matching Aiko's dynamic Obsidian theme and 185Hz motion grammar.
 */
public final class LongPressMenuSheet {
    public static final class Target {
        public final String linkUrl;
        public final String imageUrl;
        public final String extraText;
        public final int hitType;

        public Target(String linkUrl, String imageUrl, String extraText, int hitType) {
            this.linkUrl = normalize(linkUrl);
            this.imageUrl = normalize(imageUrl);
            this.extraText = normalize(extraText);
            this.hitType = hitType;
        }

        private static String normalize(String s) {
            if (s == null) return "";
            String t = s.trim();
            return t;
        }
    }

    public interface Callbacks {
        void openInBackgroundTab(String url);
        void openInPeekSheet(String url);
        void openInPrivate(String url);
        void copyText(String label, String text);
        void shareUrl(String url);
        void showQr(String title, String url);
        void saveImage(String imageUrl);
        void reverseImageSearch(String engineName, String searchUrl);
        void downloadLink(String url);
        void translateLinkText(String text);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_PREVIEW_BYTES = 4 * 1024 * 1024;

    private LongPressMenuSheet() {}

    public static void show(Activity activity, Target target, Callbacks cb) {
        show(activity, target, null, cb);
    }

    public static void show(Activity activity, Target target, DisplayModeEngine displayMode, Callbacks cb) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || target == null || cb == null) {
            return;
        }

        final boolean hasLink = HttpUrlUtil.isHttp(target.linkUrl);
        final boolean hasImage = HttpUrlUtil.isHttp(target.imageUrl) || target.imageUrl.startsWith("data:image/");
        final boolean hasHttpImage = HttpUrlUtil.isHttp(target.imageUrl);
        final boolean hasText = !target.extraText.isEmpty()
                && !target.extraText.equals(target.linkUrl)
                && !target.extraText.equals(target.imageUrl);

        if (!hasLink && !hasImage && !hasText) return;

        AikoTheme.applyTheme(activity);
        final float d = activity.getResources().getDisplayMetrics().density;
        final String primaryUrl = hasLink ? target.linkUrl : (hasImage ? target.imageUrl : "");
        final String primaryHost = HttpUrlUtil.extractHost(primaryUrl);
        final String sheetTitle = (hasLink && hasImage) ? "链接与图片"
                : hasLink ? "链接操作"
                : hasImage ? "图片操作" : "选中文本";

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final AtomicBoolean dismissed = new AtomicBoolean(false);
        final ExecutorService previewExec = hasHttpImage ? Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> {
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
                catch (SecurityException ignored) { }
                r.run();
            }, "aiko-lp-thumb");
            t.setDaemon(true);
            return t;
        }) : null;

        LinearLayout panel = AikoTheme.sheetContainer(activity, d, sheetTitle, dialog::dismiss);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));

        int maxScrollH = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.72f);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t > maxScrollH) {
                v.getLayoutParams().height = maxScrollH;
                v.requestLayout();
            }
        });

        // 1. Target Header Preview Card
        ImageView previewThumb = buildHeaderCard(activity, body, d, target, hasLink, hasHttpImage, hasText,
                primaryUrl, primaryHost);
        if (previewThumb != null && previewExec != null) {
            loadPreviewThumbAsync(activity, previewExec, dismissed, target.imageUrl, previewThumb);
        }

        // 2. Link Actions Section
        if (hasLink) {
            body.addView(AikoTheme.sectionHeader(activity, d, "链接快捷打开"));

            addTilePair(activity, body, d,
                    AikoTheme.toggleTile(activity, d, BrowserIcon.Kind.TABS,
                            "后台标签打开", "不打断当前阅读", true, true, AikoTheme.ACCENT,
                            () -> { dialog.dismiss(); cb.openInBackgroundTab(target.linkUrl); }),
                    AikoTheme.toggleTile(activity, d, BrowserIcon.Kind.PEEK,
                            "浮窗快览", "半屏即看即关", false, true, AikoTheme.ACCENT_CYAN,
                            () -> { dialog.dismiss(); cb.openInPeekSheet(target.linkUrl); }));

            addTilePair(activity, body, d,
                    AikoTheme.toggleTile(activity, d, BrowserIcon.Kind.PRIVATE,
                            "无痕模式打开", "独立隐私进程", false, true, AikoTheme.TEXT,
                            () -> { dialog.dismiss(); cb.openInPrivate(target.linkUrl); }),
                    AikoTheme.toggleTile(activity, d, BrowserIcon.Kind.DOWNLOAD,
                            "多线程下载", "高速分段保存", false, true, AikoTheme.EMERALD,
                            () -> { dialog.dismiss(); cb.downloadLink(target.linkUrl); }));
        }

        if (hasText) {
            LinearLayout translateTile = AikoTheme.toggleTile(
                    activity, d, BrowserIcon.Kind.TRANSLATE,
                    "翻译链接文本", truncate(target.extraText, 36),
                    false, true, AikoTheme.ACCENT_CYAN,
                    () -> { dialog.dismiss(); cb.translateLinkText(target.extraText); });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp(d, 8);
            body.addView(translateTile, tlp);
        }

        // 3. Image Actions + Reverse Image Search Section
        if (hasImage) {
            body.addView(AikoTheme.sectionHeader(activity, d, "图片操作"));

            addTilePair(activity, body, d,
                    AikoTheme.toggleTile(activity, d, BrowserIcon.Kind.DOWNLOAD,
                            "保存原图", "存入系统相册", true, true, AikoTheme.EMERALD,
                            () -> { dialog.dismiss(); cb.saveImage(target.imageUrl); }),
                    AikoTheme.toggleTile(activity, d, BrowserIcon.Kind.IMAGE,
                            "后台查看原图", "新标签打开大图", false, true, AikoTheme.ACCENT,
                            () -> { dialog.dismiss(); cb.openInBackgroundTab(target.imageUrl); }));

            if (hasHttpImage) {
                body.addView(AikoTheme.sectionHeader(activity, d, "以图搜图"));
                FlowTagLayout searchChips = new FlowTagLayout(activity, dp(d, 8), dp(d, 8));
                String encoded = urlEncodeUtf8(target.imageUrl);
                String[][] engines = {
                        {"Google Lens", "https://lens.google.com/uploadbyurl?url="},
                        {"Yandex 识图", "https://yandex.com/images/search?rpt=imageview&url="},
                        {"SauceNAO", "https://saucenao.com/search.php?url="},
                        {"Bing 视觉搜索", "https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIVSP&q=imgurl:"}
                };
                for (String[] eng : engines) {
                    String name = eng[0];
                    String searchUrl = eng[1] + encoded;
                    searchChips.addView(buildActionChip(activity, d, BrowserIcon.Kind.SEARCH, name,
                            AikoTheme.ACCENT_CYAN, () -> {
                                dialog.dismiss();
                                cb.reverseImageSearch(name, searchUrl);
                            }));
                }
                LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(-1, -2);
                sclp.bottomMargin = dp(d, 4);
                body.addView(searchChips, sclp);
            }
        }

        // 4. Share / QR / Copy Utility Chips Section
        body.addView(AikoTheme.sectionHeader(activity, d, "复制与分享"));
        FlowTagLayout utilChips = new FlowTagLayout(activity, dp(d, 8), dp(d, 8));

        if (hasLink) {
            utilChips.addView(buildActionChip(activity, d, BrowserIcon.Kind.COPY, "复制链接", AikoTheme.TEXT,
                    () -> { dialog.dismiss(); cb.copyText("链接", target.linkUrl); }));
        }
        if (hasImage) {
            utilChips.addView(buildActionChip(activity, d, BrowserIcon.Kind.COPY, "复制图片地址", AikoTheme.TEXT,
                    () -> { dialog.dismiss(); cb.copyText("图片链接", target.imageUrl); }));
        }
        if (hasText) {
            utilChips.addView(buildActionChip(activity, d, BrowserIcon.Kind.COPY, "复制文本", AikoTheme.TEXT,
                    () -> { dialog.dismiss(); cb.copyText("链接文本", target.extraText); }));
        }
        if (!primaryUrl.isEmpty()) {
            String qrTitle = hasText ? target.extraText : (primaryHost.isEmpty() ? sheetTitle : primaryHost);
            utilChips.addView(buildActionChip(activity, d, BrowserIcon.Kind.QR, "生成二维码", AikoTheme.ACCENT,
                    () -> { dialog.dismiss(); cb.showQr(qrTitle, primaryUrl); }));
            utilChips.addView(buildActionChip(activity, d, BrowserIcon.Kind.SHARE, "分享链接", AikoTheme.TEXT,
                    () -> { dialog.dismiss(); cb.shareUrl(primaryUrl); }));
        }

        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, -2);
        ulp.bottomMargin = dp(d, 6);
        body.addView(utilChips, ulp);

        dialog.setOnDismissListener(dlg -> {
            dismissed.set(true);
            if (previewExec != null) previewExec.shutdownNow();
        });

        AikoTheme.presentSheet(dialog, panel, displayMode, d);
    }

    private static ImageView buildHeaderCard(Activity activity, LinearLayout parent, float d,
                                             Target target, boolean hasLink, boolean hasHttpImage,
                                             boolean hasText, String primaryUrl, String primaryHost) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(d, 12), dp(d, 11), dp(d, 12), dp(d, 11));
        card.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, d, dp(d, 16)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(d, 4);
        clp.bottomMargin = dp(d, 4);
        parent.addView(card, clp);

        ImageView thumb = null;
        if (hasHttpImage) {
            FrameLayout frame = new FrameLayout(activity);
            frame.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, d, dp(d, 12)));
            frame.setClipToOutline(true);

            BrowserIcon placeholder = new BrowserIcon(activity, BrowserIcon.Kind.IMAGE);
            placeholder.tint(AikoTheme.TEXT_DIM);
            frame.addView(placeholder, new FrameLayout.LayoutParams(dp(d, 22), dp(d, 22), Gravity.CENTER));

            thumb = new ImageView(activity);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(thumb, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(d, 52), dp(d, 52));
            flp.rightMargin = dp(d, 12);
            card.addView(frame, flp);
        } else {
            FrameLayout iconBadge = new FrameLayout(activity);
            iconBadge.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, d, dp(d, 12)));
            BrowserIcon icon = new BrowserIcon(activity, hasLink ? BrowserIcon.Kind.ENGINE : BrowserIcon.Kind.READER);
            icon.tint(AikoTheme.ACCENT);
            iconBadge.addView(icon, new FrameLayout.LayoutParams(dp(d, 22), dp(d, 22), Gravity.CENTER));
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(d, 44), dp(d, 44));
            flp.rightMargin = dp(d, 12);
            card.addView(iconBadge, flp);
        }

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        card.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        String headline = hasText ? target.extraText : (!primaryHost.isEmpty() ? primaryHost : "网页目标");
        TextView headTv = new TextView(activity);
        headTv.setText(headline);
        headTv.setTextSize(14);
        headTv.setTextColor(AikoTheme.TEXT);
        headTv.setTypeface(AikoTheme.MEDIUM);
        headTv.setSingleLine(true);
        headTv.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(headTv);

        String sub = !primaryUrl.isEmpty() ? primaryUrl : target.extraText;
        TextView subTv = new TextView(activity);
        subTv.setText(sub);
        subTv.setTextSize(11);
        subTv.setTextColor(AikoTheme.TEXT_MUTED);
        subTv.setTypeface(AikoTheme.MONO);
        subTv.setMaxLines(2);
        subTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(d, 3);
        textCol.addView(subTv, slp);

        return thumb;
    }

    private static void addTilePair(Activity activity, LinearLayout parent, float d, View left, View right) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(0, -2, 1f);
        lpL.rightMargin = dp(d, 4);
        row.addView(left, lpL);

        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(0, -2, 1f);
        lpR.leftMargin = dp(d, 4);
        row.addView(right, lpR);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = dp(d, 8);
        parent.addView(row, rlp);
    }

    private static LinearLayout buildActionChip(Activity activity, float d, BrowserIcon.Kind kind,
                                                String label, int iconTint, Runnable onClick) {
        LinearLayout chip = new LinearLayout(activity);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(d, 12), dp(d, 9), dp(d, 13), dp(d, 9));
        chip.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, d, dp(d, 14)));
        chip.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(d, 14)));
        AikoTheme.attachPressFeedback(chip, 0.94f);

        BrowserIcon icon = new BrowserIcon(activity, kind);
        icon.tint(iconTint);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(d, 16), dp(d, 16));
        ilp.rightMargin = dp(d, 7);
        chip.addView(icon, ilp);

        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(AikoTheme.TEXT);
        tv.setTypeface(AikoTheme.MEDIUM);
        tv.setSingleLine(true);
        chip.addView(tv);

        chip.setOnClickListener(v -> onClick.run());
        return chip;
    }

    private static void loadPreviewThumbAsync(Activity activity, ExecutorService exec,
                                              AtomicBoolean dismissed, String imageUrl, ImageView target) {
        String ua;
        try {
            ua = WebSettings.getDefaultUserAgent(activity);
        } catch (RuntimeException ignored) {
            ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36";
        }
        final String finalUa = ua;
        try {
            exec.execute(() -> {
                if (dismissed.get()) return;
                Bitmap bmp = fetchThumbnail(imageUrl, finalUa, 160);
                if (bmp == null || dismissed.get()) {
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                    return;
                }
                MAIN.post(() -> {
                    if (dismissed.get()) {
                        if (!bmp.isRecycled()) bmp.recycle();
                    } else {
                        target.setImageBitmap(bmp);
                    }
                });
            });
        } catch (RuntimeException ignored) {
        }
    }

    private static Bitmap fetchThumbnail(String urlStr, String ua, int maxDim) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", ua);
            String origin = HttpUrlUtil.extractOrigin(urlStr);
            if (!origin.isEmpty()) conn.setRequestProperty("Referer", origin);
            try {
                String cookie = CookieManager.getInstance().getCookie(urlStr);
                if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            } catch (RuntimeException ignored) {
            }
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 400) return null;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream(16384)) {
                byte[] buf = new byte[8192];
                int total = 0;
                for (int n; (n = in.read(buf)) >= 0; ) {
                    total += n;
                    if (total > MAX_PREVIEW_BYTES) return null;
                    out.write(buf, 0, n);
                }
                byte[] data = out.toByteArray();
                BitmapFactory.Options b = new BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, b);
                if (b.outWidth <= 0 || b.outHeight <= 0) return null;
                int sample = 1;
                while ((b.outWidth / sample) > maxDim * 2 || (b.outHeight / sample) > maxDim * 2) {
                    sample *= 2;
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o);
            }
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String urlEncodeUtf8(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static int dp(float d, int v) {
        return AikoTheme.dp(d, v);
    }
}

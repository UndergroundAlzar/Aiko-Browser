package app.aikobrowser;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Themed QR code generator sheet (powered by {@link QrEngine#encodeStyled}) and
 * 100% crash-proof QR scanner / gallery decode / clipboard extractor sheet.
 */
public final class QrSheet {
    public static final int REQ_SCAN_EXTERNAL = 0x41B1;
    public static final int REQ_PICK_QR_IMAGE = 0x41B2;

    public interface ScanCallback {
        void onQrScanned(String content);
        void toast(String msg);
    }

    private static volatile Handler mainHandler;

    private static void postMain(Runnable r) {
        Handler h = mainHandler;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            mainHandler = h;
        }
        h.post(r);
    }
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
            catch (SecurityException ignored) { }
            r.run();
        }, "aiko-qr-io");
        t.setDaemon(true);
        return t;
    });

    private static final Pattern URL_EXTRACT = Pattern.compile(
            "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)", Pattern.CASE_INSENSITIVE);

    private static volatile WeakReference<ScanCallback> pendingScanCallback = new WeakReference<>(null);

    private QrSheet() {}

    /**
     * Displays the elevated Material QR code generator bottom sheet for {@code content}.
     */
    public static void showGenerator(Activity activity, String title, String content,
                                     Runnable onCopy, ScanCallback cb) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        String payload = content == null ? "" : content.trim();
        if (payload.isEmpty()) {
            notifyToast(activity, cb, "当前没有可生成二维码的内容");
            return;
        }

        AikoTheme.applyTheme(activity);
        float d = activity.getResources().getDisplayMetrics().density;
        String safeTitle = (title == null || title.trim().isEmpty()) ? "页面二维码" : title.trim();

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = AikoTheme.sheetContainer(activity, d, "分享二维码", dialog::dismiss);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(0, dp(d, 4), 0, dp(d, 4));
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        // Elevated crisp white QR showcase card
        int qrDisplayPx = Math.max(dp(d, 224), Math.min(dp(d, 268),
                Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.64f)));
        Bitmap qrBmp = null;
        try {
            qrBmp = QrEngine.encodeStyled(payload, qrDisplayPx, 0xFF101318, AikoTheme.ACCENT, 0xFFFFFFFF);
        } catch (RuntimeException ignored) {
        }

        FrameLayout qrCard = new FrameLayout(activity);
        qrCard.setPadding(dp(d, 14), dp(d, 14), dp(d, 14), dp(d, 14));
        qrCard.setBackground(AikoTheme.card(0xFFFFFFFF, AikoTheme.STROKE_ACCENT, d * 1.5f, dp(d, 24)));
        qrCard.setElevation(dp(d, 6));

        ImageView qrImage = new ImageView(activity);
        qrImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (qrBmp != null) {
            qrImage.setImageBitmap(qrBmp);
        }
        qrCard.addView(qrImage, new FrameLayout.LayoutParams(qrDisplayPx, qrDisplayPx, Gravity.CENTER));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-2, -2);
        cardLp.topMargin = dp(d, 6);
        cardLp.bottomMargin = dp(d, 14);
        body.addView(qrCard, cardLp);

        // Title + URL preview card
        LinearLayout metaCard = new LinearLayout(activity);
        metaCard.setOrientation(LinearLayout.VERTICAL);
        metaCard.setPadding(dp(d, 14), dp(d, 11), dp(d, 14), dp(d, 11));
        metaCard.setBackground(AikoTheme.card(AikoTheme.CARD, AikoTheme.STROKE, d, dp(d, 16)));

        TextView titleView = new TextView(activity);
        titleView.setText(safeTitle);
        titleView.setTextSize(14);
        titleView.setTextColor(AikoTheme.TEXT);
        titleView.setTypeface(AikoTheme.MEDIUM);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        metaCard.addView(titleView);

        TextView urlView = new TextView(activity);
        urlView.setText(payload);
        urlView.setTextSize(12);
        urlView.setTextColor(AikoTheme.TEXT_MUTED);
        urlView.setTypeface(AikoTheme.MONO);
        urlView.setMaxLines(2);
        urlView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, -2);
        ulp.topMargin = dp(d, 4);
        metaCard.addView(urlView, ulp);

        if (payload.getBytes(StandardCharsets.UTF_8).length > QrEngine.MAX_UTF8_BYTES) {
            TextView warn = new TextView(activity);
            warn.setText("内容超过 " + QrEngine.MAX_UTF8_BYTES + " 字节，二维码已自动截断以保证扫码识别率");
            warn.setTextSize(11);
            warn.setTextColor(AikoTheme.AMBER);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
            wlp.topMargin = dp(d, 5);
            metaCard.addView(warn, wlp);
        }

        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
        metaLp.bottomMargin = dp(d, 14);
        body.addView(metaCard, metaLp);

        // Primary 3-action row: 保存二维码图片 / 分享 / 复制链接
        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(actionRow, new LinearLayout.LayoutParams(-1, -2));

        addActionButton(actionRow, activity, d, BrowserIcon.Kind.DOWNLOAD, "保存二维码图片", true, () ->
                saveQrCardAsync(activity, safeTitle, payload, cb));

        addActionButton(actionRow, activity, d, BrowserIcon.Kind.SHARE, "分享", false, () -> {
            try {
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, safeTitle)
                        .putExtra(Intent.EXTRA_TEXT, payload);
                activity.startActivity(Intent.createChooser(share, "分享链接"));
            } catch (RuntimeException e) {
                notifyToast(activity, cb, "无法打开系统分享");
            }
        });

        addActionButton(actionRow, activity, d, BrowserIcon.Kind.COPY, "复制链接", false, () -> {
            copyToClipboard(activity, safeTitle, payload);
            if (onCopy != null) {
                onCopy.run();
            } else {
                notifyToast(activity, cb, "已复制链接");
            }
        });

        // Secondary quick switch to QR scanner / decoder
        if (cb != null) {
            LinearLayout scanBtn = new LinearLayout(activity);
            scanBtn.setOrientation(LinearLayout.HORIZONTAL);
            scanBtn.setGravity(Gravity.CENTER);
            scanBtn.setPadding(dp(d, 14), dp(d, 10), dp(d, 14), dp(d, 10));
            scanBtn.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, d, dp(d, 14)));
            scanBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(d, 14)));
            AikoTheme.attachPressFeedback(scanBtn, 0.96f);

            BrowserIcon icon = new BrowserIcon(activity, BrowserIcon.Kind.QR);
            icon.tint(AikoTheme.ACCENT);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(d, 18), dp(d, 18));
            ilp.rightMargin = dp(d, 8);
            scanBtn.addView(icon, ilp);

            TextView label = new TextView(activity);
            label.setText("扫码 / 从相册识别二维码");
            label.setTextSize(13);
            label.setTextColor(AikoTheme.TEXT);
            label.setTypeface(AikoTheme.MEDIUM);
            scanBtn.addView(label);

            scanBtn.setOnClickListener(v -> {
                dialog.dismiss();
                showScannerHintOrImageDecode(activity, cb);
            });
            LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(-1, -2);
            sblp.topMargin = dp(d, 10);
            body.addView(scanBtn, sblp);
        }

        final Bitmap finalQr = qrBmp;
        dialog.setOnDismissListener(dlg -> {
            qrImage.setImageDrawable(null);
            if (finalQr != null && !finalQr.isRecycled()) {
                finalQr.recycle();
            }
        });

        AikoTheme.presentSheet(dialog, panel, null, d);
    }

    /**
     * Displays a 100% crash-proof QR entry sheet supporting external system scanner intents,
     * gallery QR screenshot decoding (pure-Java decoder), smart clipboard URL extraction,
     * and direct manual input/paste.
     */
    public static void showScannerHintOrImageDecode(Activity activity, ScanCallback cb) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        AikoTheme.applyTheme(activity);
        float d = activity.getResources().getDisplayMetrics().density;
        pendingScanCallback = new WeakReference<>(cb);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = AikoTheme.sheetContainer(activity, d, "扫码与二维码识别", dialog::dismiss);

        TextView subtitle = new TextView(activity);
        subtitle.setText("支持调用系统扫码器、识别相册二维码图片，或从剪贴板智能提取链接");
        subtitle.setTextSize(12);
        subtitle.setTextColor(AikoTheme.TEXT_MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.bottomMargin = dp(d, 12);
        subLp.leftMargin = dp(d, 4);
        panel.addView(subtitle, subLp);

        // Two flagship launch tiles: System/Camera Scanner & Gallery QR Image Decode
        LinearLayout tileRow = new LinearLayout(activity);
        tileRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.bottomMargin = dp(d, 12);
        panel.addView(tileRow, rowLp);

        LinearLayout scanTile = AikoTheme.toggleTile(
                activity, d, BrowserIcon.Kind.QR,
                "调用扫码器", "系统 / ZXing 扫码",
                true, true, AikoTheme.ACCENT, () -> {
                    if (launchExternalScanner(activity, cb)) {
                        dialog.dismiss();
                    }
                });
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, -2, 1f);
        t1.rightMargin = dp(d, 6);
        tileRow.addView(scanTile, t1);

        LinearLayout pickTile = AikoTheme.toggleTile(
                activity, d, BrowserIcon.Kind.IMAGE,
                "相册识图", "解析二维码截图",
                false, true, AikoTheme.ACCENT_CYAN, () -> {
                    if (launchGalleryPicker(activity, cb)) {
                        dialog.dismiss();
                    }
                });
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, -2, 1f);
        t2.leftMargin = dp(d, 6);
        tileRow.addView(pickTile, t2);

        // Smart clipboard extractor tile
        LinearLayout clipTile = AikoTheme.toggleTile(
                activity, d, BrowserIcon.Kind.BOLT,
                "智能识别剪贴板链接", "自动从分享文案中提取 URL 或文本",
                false, true, AikoTheme.EMERALD, () -> {
                    String clip = readClipboardText(activity);
                    if (clip.isEmpty()) {
                        notifyToast(activity, cb, "剪贴板为空");
                        return;
                    }
                    String extracted = extractSmartContent(clip);
                    dialog.dismiss();
                    if (cb != null) cb.onQrScanned(extracted);
                });
        LinearLayout.LayoutParams clipLp = new LinearLayout.LayoutParams(-1, -2);
        clipLp.bottomMargin = dp(d, 12);
        panel.addView(clipTile, clipLp);

        // Direct input / paste card
        TextView inputHeader = AikoTheme.sectionHeader(activity, d, "直接粘贴或输入二维码内容");
        panel.addView(inputHeader);

        LinearLayout inputBox = new LinearLayout(activity);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setPadding(dp(d, 12), dp(d, 4), dp(d, 6), dp(d, 4));
        inputBox.setBackground(AikoTheme.card(AikoTheme.PILL, AikoTheme.STROKE, d, dp(d, 14)));

        EditText input = new EditText(activity);
        input.setHint("粘贴链接、文本或扫码结果…");
        input.setHintTextColor(AikoTheme.TEXT_DIM);
        input.setTextColor(AikoTheme.TEXT);
        input.setTextSize(14);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        inputBox.addView(input, new LinearLayout.LayoutParams(0, dp(d, 42), 1f));

        TextView pasteBtn = new TextView(activity);
        pasteBtn.setText("粘贴");
        pasteBtn.setTextSize(12);
        pasteBtn.setTextColor(AikoTheme.ACCENT);
        pasteBtn.setTypeface(AikoTheme.MEDIUM);
        pasteBtn.setPadding(dp(d, 10), dp(d, 6), dp(d, 10), dp(d, 6));
        pasteBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_ACCENT, dp(d, 10)));
        AikoTheme.attachPressFeedback(pasteBtn);
        pasteBtn.setOnClickListener(v -> {
            String clip = readClipboardText(activity);
            if (clip.isEmpty()) {
                notifyToast(activity, cb, "剪贴板为空");
            } else {
                input.setText(extractSmartContent(clip));
                input.setSelection(input.getText().length());
            }
        });
        inputBox.addView(pasteBtn, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(-1, -2);
        ibLp.bottomMargin = dp(d, 12);
        panel.addView(inputBox, ibLp);

        // Confirm accent button
        TextView confirmBtn = new TextView(activity);
        confirmBtn.setText("打开识别结果");
        confirmBtn.setTextSize(14);
        confirmBtn.setTextColor(AikoTheme.onAccentColor());
        confirmBtn.setTypeface(AikoTheme.MEDIUM);
        confirmBtn.setGravity(Gravity.CENTER);
        confirmBtn.setBackground(AikoTheme.rounded(AikoTheme.ACCENT, dp(d, 14)));
        confirmBtn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, dp(d, 14)));
        AikoTheme.attachPressFeedback(confirmBtn, 0.96f);
        confirmBtn.setOnClickListener(v -> {
            String val = input.getText() == null ? "" : input.getText().toString().trim();
            if (val.isEmpty()) {
                notifyToast(activity, cb, "请输入或粘贴有效内容");
                return;
            }
            dialog.dismiss();
            if (cb != null) cb.onQrScanned(extractSmartContent(val));
        });
        panel.addView(confirmBtn, new LinearLayout.LayoutParams(-1, dp(d, 46)));

        AikoTheme.presentSheet(dialog, panel, null, d);
    }

    /**
     * Optional Activity result bridge for {@link #REQ_SCAN_EXTERNAL} and {@link #REQ_PICK_QR_IMAGE}.
     * Returns true if the requestCode belonged to QrSheet.
     */
    public static boolean handleActivityResult(Activity activity, int requestCode, int resultCode,
                                               Intent data, ScanCallback fallbackCb) {
        if (requestCode != REQ_SCAN_EXTERNAL && requestCode != REQ_PICK_QR_IMAGE) {
            return false;
        }
        ScanCallback cb = fallbackCb != null ? fallbackCb : pendingScanCallback.get();
        if (resultCode != Activity.RESULT_OK || data == null) {
            return true;
        }
        if (requestCode == REQ_SCAN_EXTERNAL) {
            String result = data.getStringExtra("SCAN_RESULT");
            if (result == null) result = data.getStringExtra("SCAN_RESULT_TEXT");
            if (result == null) result = data.getStringExtra(Intent.EXTRA_TEXT);
            if (result == null && data.getDataString() != null) result = data.getDataString();
            if (result != null && !result.trim().isEmpty()) {
                if (cb != null) cb.onQrScanned(result.trim());
            } else {
                notifyToast(activity, cb, "未获取到扫码结果");
            }
            return true;
        }
        Uri uri = data.getData();
        if (uri != null) {
            decodeImageUri(activity, uri, cb);
        }
        return true;
    }

    /**
     * Decodes a clean QR code image from a content URI off the main thread.
     */
    public static void decodeImageUri(Activity activity, Uri uri, ScanCallback cb) {
        if (activity == null || uri == null) return;
        Context appCtx = activity.getApplicationContext();
        notifyToast(activity, cb, "正在解析二维码图片…");
        IO.execute(() -> {
            String decoded = null;
            Bitmap bmp = null;
            try {
                bmp = decodeSampledUri(appCtx, uri, 1280);
                if (bmp != null) {
                    decoded = decodeCleanQrBitmap(bmp);
                }
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            } finally {
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            }
            final String result = decoded;
            postMain(() -> {
                if (result != null && !result.isEmpty()) {
                    if (cb != null) cb.onQrScanned(result);
                } else {
                    notifyToast(activity, cb, "未能识别该图片中的二维码（建议使用清晰无畸变的二维码截图）");
                }
            });
        });
    }

    private static boolean launchExternalScanner(Activity activity, ScanCallback cb) {
        PackageManager pm = activity.getPackageManager();
        Intent[] candidates = new Intent[]{
                new Intent("com.google.zxing.client.android.SCAN").putExtra("SCAN_MODE", "QR_CODE_MODE"),
                new Intent("com.huawei.hms.hmsscankit.ScanKitActivity"),
                new Intent("miui.intent.action.scanbarcode"),
                new Intent("com.xiaomi.scanner.ACTION_SCAN")
        };
        for (Intent intent : candidates) {
            try {
                if (pm != null && intent.resolveActivity(pm) != null) {
                    activity.startActivityForResult(intent, REQ_SCAN_EXTERNAL);
                    return true;
                }
            } catch (RuntimeException ignored) {
            }
        }
        notifyToast(activity, cb, "未检测到兼容的第三方扫码应用，可使用相册识图或剪贴板提取");
        return false;
    }

    private static boolean launchGalleryPicker(Activity activity, ScanCallback cb) {
        try {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            activity.startActivityForResult(Intent.createChooser(pick, "选择二维码图片"), REQ_PICK_QR_IMAGE);
            return true;
        } catch (RuntimeException e) {
            notifyToast(activity, cb, "无法打开系统相册");
            return false;
        }
    }

    private static void saveQrCardAsync(Activity activity, String title, String content, ScanCallback cb) {
        Context appCtx = activity.getApplicationContext();
        final int accent = AikoTheme.ACCENT;
        notifyToast(activity, cb, "正在保存二维码…");
        IO.execute(() -> {
            Bitmap cardBmp = null;
            Bitmap qr = null;
            try {
                int qrSize = 860;
                qr = QrEngine.encodeStyled(content, qrSize, 0xFF101318, accent, 0xFFFFFFFF);
                int pad = 72;
                int cardW = qrSize + pad * 2;
                int footerH = 156;
                int cardH = qrSize + pad * 2 + footerH;
                cardBmp = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(cardBmp);
                canvas.drawColor(0xFFFFFFFF);

                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(qr, pad, pad, paint);

                // Subtle divider
                paint.setColor(0x18101318);
                canvas.drawRect(pad, qrSize + pad + 12, cardW - pad, qrSize + pad + 14, paint);

                TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(0xFF101318);
                tp.setTypeface(AikoTheme.BOLD);
                tp.setTextSize(34f);
                CharSequence clippedTitle = TextUtils.ellipsize(title, tp, cardW - pad * 2f, TextUtils.TruncateAt.END);
                canvas.drawText(clippedTitle.toString(), pad, qrSize + pad + 68, tp);

                TextPaint up = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                up.setColor(0xFF64748B);
                up.setTypeface(AikoTheme.MONO);
                up.setTextSize(22f);
                CharSequence clippedUrl = TextUtils.ellipsize(content, up, cardW - pad * 2f, TextUtils.TruncateAt.MIDDLE);
                canvas.drawText(clippedUrl.toString(), pad, qrSize + pad + 114, up);

                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String name = "Aiko_QR_" + ts + ".png";
                savePngToMediaStore(appCtx, cardBmp, name);
                postMain(() -> notifyToast(activity, cb, "二维码已保存至 Pictures/Aiko/" + name));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                postMain(() -> notifyToast(activity, cb, "保存二维码失败"));
            } finally {
                if (qr != null && !qr.isRecycled()) qr.recycle();
                if (cardBmp != null && !cardBmp.isRecycled()) cardBmp.recycle();
            }
        });
    }

    private static void savePngToMediaStore(Context context, Bitmap bitmap, String displayName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Aiko");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法写入相册");
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
        } catch (IOException | RuntimeException e) {
            try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { }
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
    }

    private static void addActionButton(LinearLayout row, Activity activity, float d,
                                        BrowserIcon.Kind kind, String text, boolean accent, Runnable onClick) {
        LinearLayout btn = new LinearLayout(activity);
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(d, 6), dp(d, 10), dp(d, 6), dp(d, 10));
        btn.setBackground(AikoTheme.card(
                accent ? AikoTheme.ACCENT : AikoTheme.CARD,
                accent ? 0 : AikoTheme.STROKE,
                d, dp(d, 14)));
        btn.setForeground(AikoTheme.ripple(accent ? AikoTheme.RIPPLE_LIGHT : AikoTheme.RIPPLE_ACCENT, dp(d, 14)));
        AikoTheme.attachPressFeedback(btn, 0.94f);

        int fg = accent ? AikoTheme.onAccentColor() : AikoTheme.TEXT;
        BrowserIcon icon = new BrowserIcon(activity, kind);
        icon.tint(fg);
        btn.addView(icon, new LinearLayout.LayoutParams(dp(d, 20), dp(d, 20)));

        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(fg);
        tv.setTypeface(AikoTheme.MEDIUM);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.topMargin = dp(d, 5);
        btn.addView(tv, tlp);

        btn.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(d, 3), 0, dp(d, 3), 0);
        row.addView(btn, lp);
    }

    private static String extractSmartContent(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        Matcher m = URL_EXTRACT.matcher(trimmed);
        if (m.find()) {
            String candidate = m.group(1);
            if (candidate != null) {
                while (candidate.endsWith(".") || candidate.endsWith(",") || candidate.endsWith(")")
                        || candidate.endsWith("]") || candidate.endsWith("。") || candidate.endsWith("，")) {
                    candidate = candidate.substring(0, candidate.length() - 1);
                }
                if (HttpUrlUtil.isHttp(candidate)) return candidate;
            }
        }
        return trimmed;
    }

    private static String readClipboardText(Context context) {
        try {
            ClipboardManager cm = context.getSystemService(ClipboardManager.class);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence seq = clip.getItemAt(0).coerceToText(context);
            return seq == null ? "" : seq.toString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static void copyToClipboard(Context context, String label, String text) {
        try {
            ClipboardManager cm = context.getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
        } catch (RuntimeException ignored) {
        }
    }

    private static void notifyToast(Activity activity, ScanCallback cb, String msg) {
        if (cb != null) {
            cb.toast(msg);
        } else if (activity != null && !activity.isFinishing()) {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
        }
    }

    private static Bitmap decodeSampledUri(Context ctx, Uri uri, int maxDim) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while ((bounds.outWidth / sample) > maxDim || (bounds.outHeight / sample) > maxDim) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return null;
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    /**
     * Lightweight pure-Java decoder for clean axis-aligned digital QR images (e.g., generated QR PNGs
     * and clean screenshots, ISO 18004 Versions 1–15, Byte / Alphanumeric / Numeric mode).
     * Returns null safely if image is skewed, blurred, or unsupported.
     */
    static String decodeCleanQrBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w < 21 || h < 21) return null;

        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        return decodeCleanQrPixels(pixels, w, h);
    }

    /** Pure-Java pixel-array entry point (host-JVM testable) for clean axis-aligned QR images. */
    static String decodeCleanQrPixels(int[] pixels, int w, int h) {
        if (pixels == null || w < 21 || h < 21 || pixels.length < w * h) return null;

        // Compute adaptive luma range from image min/max luminance
        int minLuma = 255, maxLuma = 0;
        int stride = Math.max(1, (w * h) / 4096);
        for (int i = 0; i < pixels.length; i += stride) {
            int l = pixelLuma(pixels[i]);
            if (l < minLuma) minLuma = l;
            if (l > maxLuma) maxLuma = l;
        }
        int span = maxLuma - minLuma;
        if (span < 40) return null;

        // Try standard dark-biased threshold first, then a higher threshold for light/colored finder rings
        float[] ratios = {0.58f, 0.72f, 0.48f};
        for (float ratio : ratios) {
            int threshold = minLuma + Math.round(span * ratio);
            String res = decodeCleanQrWithThreshold(pixels, w, h, threshold);
            if (res != null && !res.isEmpty()) return res;
        }
        return null;
    }

    private static String decodeCleanQrWithThreshold(int[] pixels, int w, int h, int threshold) {
        // Locate 1:1:3:1:1 finder pattern centers horizontally and verify vertically
        List<float[]> candidates = new ArrayList<>();
        int rowStep = Math.max(1, h / 240);
        for (int y = 0; y < h; y += rowStep) {
            int[] runs = new int[5];
            int state = 0;
            for (int x = 0; x < w; x++) {
                boolean dark = pixelLuma(pixels[y * w + x]) < threshold;
                if (dark) {
                    if ((state & 1) == 1) state++;
                    runs[state]++;
                } else {
                    if ((state & 1) == 0) {
                        if (state == 4) {
                            if (matchesFinderRatio(runs)) {
                                float moduleEst = (runs[0] + runs[1] + runs[2] + runs[3] + runs[4]) / 7f;
                                float cx = x - runs[4] - runs[3] - runs[2] * 0.5f;
                                float cy = verifyVerticalFinder(pixels, w, h, threshold, Math.round(cx), y, moduleEst);
                                if (cy >= 0f) {
                                    mergeCandidate(candidates, cx, cy, moduleEst);
                                }
                            }
                            runs[0] = runs[2];
                            runs[1] = runs[3];
                            runs[2] = runs[4];
                            runs[3] = 1;
                            runs[4] = 0;
                            state = 3;
                        } else {
                            state++;
                            runs[state]++;
                        }
                    } else {
                        runs[state]++;
                    }
                }
            }
        }

        if (candidates.size() < 3) return null;
        // Sort by cluster weight descending and take top 3 finders
        candidates.sort((a, b) -> Float.compare(b[3], a[3]));
        float[] p0 = candidates.get(0);
        float[] p1 = candidates.get(1);
        float[] p2 = candidates.get(2);

        // Identify top-left (corner nearest origin), top-right (max X), bottom-left (max Y)
        float[][] pts = {p0, p1, p2};
        float[] tl = pts[0], tr = pts[1], bl = pts[2];
        float bestDiag = -1f;
        for (int i = 0; i < 3; i++) {
            float[] a = pts[(i + 1) % 3];
            float[] b = pts[(i + 2) % 3];
            float d2 = (a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]);
            if (d2 > bestDiag) {
                bestDiag = d2;
                tl = pts[i];
                if (a[0] > b[0]) { tr = a; bl = b; } else { tr = b; bl = a; }
            }
        }

        float dx = tr[0] - tl[0];
        float dy = bl[1] - tl[1];
        if (dx < 20f || dy < 20f) return null;
        if (Math.abs(tr[1] - tl[1]) > dx * 0.12f || Math.abs(bl[0] - tl[0]) > dy * 0.12f) return null;

        float modEst = (tl[2] + tr[2] + bl[2]) / 3f;
        if (modEst <= 0.5f) return null;

        float rawSpan = ((dx + dy) * 0.5f) / modEst;
        int version = Math.round((rawSpan - 10f) / 4f);
        if (version < 1 || version > 15) return null;
        int n = version * 4 + 17;
        float stepX = dx / (n - 7);
        float stepY = dy / (n - 7);
        float originX = tl[0] - 3.0f * stepX;
        float originY = tl[1] - 3.0f * stepY;

        boolean[][] matrix = new boolean[n][n];
        for (int r = 0; r < n; r++) {
            int sy = Math.round(originY + r * stepY);
            if (sy < 0 || sy >= h) return null;
            for (int c = 0; c < n; c++) {
                int sx = Math.round(originX + c * stepX);
                if (sx < 0 || sx >= w) return null;
                matrix[r][c] = pixelLuma(pixels[sy * w + sx]) < threshold;
            }
        }

        return decodeMatrixPayload(matrix, version);
    }

    private static String decodeMatrixPayload(boolean[][] m, int version) {
        int n = m.length;
        // Read format info Copy 1 (top-left perimeter) AND Copy 2 (top-right + bottom-left)
        int fmtBits1 = 0;
        for (int i = 0; i <= 5; i++) if (m[i][8]) fmtBits1 |= (1 << i);
        if (m[7][8]) fmtBits1 |= (1 << 6);
        if (m[8][8]) fmtBits1 |= (1 << 7);
        if (m[8][7]) fmtBits1 |= (1 << 8);
        for (int i = 9; i < 15; i++) if (m[8][14 - i]) fmtBits1 |= (1 << i);

        int fmtBits2 = 0;
        for (int i = 0; i < 8; i++) if (m[8][n - 1 - i]) fmtBits2 |= (1 << i);
        for (int i = 8; i < 15; i++) if (m[n - 15 + i][8]) fmtBits2 |= (1 << i);

        int bestFmt = -1;
        int bestHamming = 4;
        for (int ec = 0; ec < 4; ec++) {
            for (int mask = 0; mask < 8; mask++) {
                int cand = computeFormatBitsAnyEc(ec, mask);
                int d1 = Integer.bitCount(fmtBits1 ^ cand);
                if (d1 < bestHamming) {
                    bestHamming = d1;
                    bestFmt = (ec << 3) | mask;
                }
                int d2 = Integer.bitCount(fmtBits2 ^ cand);
                if (d2 < bestHamming) {
                    bestHamming = d2;
                    bestFmt = (ec << 3) | mask;
                }
            }
        }
        if (bestFmt < 0) return null;
        int ecLevel = (bestFmt >>> 3) & 3;
        int mask = bestFmt & 7;

        boolean[][] fn = buildFunctionMask(n, version);

        // Extract raw codewords in standard zigzag order
        List<Integer> rawBytes = new ArrayList<>();
        int curByte = 0;
        int bitCount = 0;
        boolean upward = true;
        for (int right = n - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int step = 0; step < n; step++) {
                int r = upward ? (n - 1 - step) : step;
                for (int dx = 0; dx < 2; dx++) {
                    int c = right - dx;
                    if (fn[r][c]) continue;
                    boolean dark = m[r][c];
                    if (maskApplies(mask, r, c)) dark = !dark;
                    curByte = (curByte << 1) | (dark ? 1 : 0);
                    bitCount++;
                    if (bitCount == 8) {
                        rawBytes.add(curByte);
                        curByte = 0;
                        bitCount = 0;
                    }
                }
            }
            upward = !upward;
        }

        // De-interleave data blocks for Level M (used by QrEngine) or single-block versions
        byte[] dataStream = deinterleaveData(rawBytes, version, ecLevel);
        if (dataStream == null || dataStream.length < 2) return null;

        return parseQrBitStream(dataStream, version);
    }

    private static byte[] deinterleaveData(List<Integer> raw, int version, int ecLevel) {
        // EC_M_FORMAT_BITS == 0 in ISO 18004 (00=M, 01=L, 10=H, 11=Q)
        int[][] mTable = {
                null,
                {26, 10, 1, 16, 0, 0}, {44, 16, 1, 28, 0, 0}, {70, 26, 1, 44, 0, 0},
                {100, 18, 2, 32, 0, 0}, {134, 24, 2, 43, 0, 0}, {172, 16, 4, 27, 0, 0},
                {196, 18, 4, 31, 0, 0}, {242, 22, 2, 38, 2, 39}, {292, 22, 3, 36, 2, 37},
                {346, 26, 4, 43, 1, 44}, {404, 30, 1, 50, 4, 51}, {466, 22, 6, 36, 2, 37},
                {532, 22, 8, 37, 1, 38}, {581, 24, 4, 40, 5, 41}, {655, 24, 5, 41, 5, 42}
        };
        if (ecLevel == 0 && version >= 1 && version < mTable.length) {
            int[] spec = mTable[version];
            int g1B = spec[2], g1D = spec[3], g2B = spec[4], g2D = spec[5];
            int totalBlocks = g1B + g2B;
            int totalData = g1B * g1D + g2B * g2D;
            if (raw.size() < totalData) return null;
            byte[][] blocks = new byte[totalBlocks][];
            for (int b = 0; b < totalBlocks; b++) blocks[b] = new byte[b < g1B ? g1D : g2D];
            int src = 0;
            int maxLen = g2B > 0 ? g2D : g1D;
            for (int i = 0; i < maxLen; i++) {
                for (int b = 0; b < totalBlocks; b++) {
                    if (i < blocks[b].length && src < raw.size()) {
                        blocks[b][i] = (byte) (int) raw.get(src++);
                    }
                }
            }
            byte[] out = new byte[totalData];
            int pos = 0;
            for (byte[] blk : blocks) {
                System.arraycopy(blk, 0, out, pos, blk.length);
                pos += blk.length;
            }
            return out;
        }
        // Single-block fallback for V1-V3 L/Q/H
        byte[] linear = new byte[raw.size()];
        for (int i = 0; i < raw.size(); i++) linear[i] = (byte) (int) raw.get(i);
        return linear;
    }

    private static String parseQrBitStream(byte[] data, int version) {
        BitReader br = new BitReader(data);
        StringBuilder sb = new StringBuilder();
        while (br.remaining() >= 4) {
            int mode = br.readBits(4);
            if (mode == 0) break; // Terminator
            if (mode == 4) { // Byte mode (UTF-8)
                int countBits = version <= 9 ? 8 : 16;
                if (br.remaining() < countBits) return null;
                int len = br.readBits(countBits);
                if (len <= 0 || br.remaining() < len * 8) return null;
                byte[] buf = new byte[len];
                for (int i = 0; i < len; i++) buf[i] = (byte) br.readBits(8);
                sb.append(new String(buf, StandardCharsets.UTF_8));
            } else if (mode == 2) { // Alphanumeric
                String table = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
                int countBits = version <= 9 ? 9 : 11;
                if (br.remaining() < countBits) return null;
                int len = br.readBits(countBits);
                while (len >= 2) {
                    if (br.remaining() < 11) return null;
                    int val = br.readBits(11);
                    if (val / 45 >= table.length() || val % 45 >= table.length()) return null;
                    sb.append(table.charAt(val / 45)).append(table.charAt(val % 45));
                    len -= 2;
                }
                if (len == 1) {
                    if (br.remaining() < 6) return null;
                    int val = br.readBits(6);
                    if (val >= table.length()) return null;
                    sb.append(table.charAt(val));
                }
            } else if (mode == 1) { // Numeric
                int countBits = version <= 9 ? 10 : 12;
                if (br.remaining() < countBits) return null;
                int len = br.readBits(countBits);
                while (len >= 3) {
                    if (br.remaining() < 10) return null;
                    int val = br.readBits(10);
                    if (val >= 1000) return null;
                    sb.append(String.format(Locale.US, "%03d", val));
                    len -= 3;
                }
                if (len == 2) {
                    if (br.remaining() < 7) return null;
                    int val = br.readBits(7);
                    if (val >= 100) return null;
                    sb.append(String.format(Locale.US, "%02d", val));
                } else if (len == 1) {
                    if (br.remaining() < 4) return null;
                    int val = br.readBits(4);
                    if (val >= 10) return null;
                    sb.append(val);
                }
            } else {
                return null;
            }
        }
        String res = sb.toString().trim();
        return res.isEmpty() ? null : res;
    }

    private static final class BitReader {
        final byte[] data;
        int bitPos;

        BitReader(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length * 8 - bitPos;
        }

        int readBits(int n) {
            int val = 0;
            for (int i = 0; i < n; i++) {
                int bit = ((data[bitPos >>> 3] & 0xFF) >>> (7 - (bitPos & 7))) & 1;
                val = (val << 1) | bit;
                bitPos++;
            }
            return val;
        }
    }

    private static boolean[][] buildFunctionMask(int n, int version) {
        boolean[][] fn = new boolean[n][n];
        for (int i = 0; i < n; i++) { fn[6][i] = true; fn[i][6] = true; }
        markRect(fn, 0, 0, 9, 9);
        markRect(fn, 0, n - 8, 9, n);
        markRect(fn, n - 8, 0, n, 9);
        int[][] alignPos = {
                null, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
                {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50},
                {6, 30, 54}, {6, 32, 58}, {6, 34, 62}, {6, 26, 46, 66}, {6, 26, 48, 70}
        };
        if (version >= 2 && version < alignPos.length) {
            int[] pos = alignPos[version];
            for (int i = 0; i < pos.length; i++) {
                for (int j = 0; j < pos.length; j++) {
                    if ((i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0))
                        continue;
                    markRect(fn, pos[i] - 2, pos[j] - 2, pos[i] + 3, pos[j] + 3);
                }
            }
        }
        if (version >= 7) {
            markRect(fn, n - 11, 0, n - 8, 6);
            markRect(fn, 0, n - 11, 6, n - 8);
        }
        return fn;
    }

    private static void markRect(boolean[][] fn, int r0, int c0, int r1, int c1) {
        int n = fn.length;
        for (int r = Math.max(0, r0); r < Math.min(n, r1); r++) {
            for (int c = Math.max(0, c0); c < Math.min(n, c1); c++) {
                fn[r][c] = true;
            }
        }
    }

    private static int computeFormatBitsAnyEc(int ec, int mask) {
        int data = ((ec & 3) << 3) | (mask & 7);
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        return ((data << 10) | rem) ^ 0x5412;
    }

    private static boolean maskApplies(int mask, int r, int c) {
        switch (mask) {
            case 0: return ((r + c) & 1) == 0;
            case 1: return (r & 1) == 0;
            case 2: return (c % 3) == 0;
            case 3: return ((r + c) % 3) == 0;
            case 4: return (((r / 2) + (c / 3)) & 1) == 0;
            case 5: return ((r * c) & 1) + ((r * c) % 3) == 0;
            case 6: return ((((r * c) & 1) + ((r * c) % 3)) & 1) == 0;
            case 7: return ((((r + c) & 1) + ((r * c) % 3)) & 1) == 0;
            default: return false;
        }
    }

    private static int pixelLuma(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r * 77 + g * 150 + b * 29) >>> 8;
    }

    private static boolean matchesFinderRatio(int[] runs) {
        int total = runs[0] + runs[1] + runs[2] + runs[3] + runs[4];
        if (total < 7) return false;
        float mod = total / 7f;
        float tol = mod * 0.55f;
        return Math.abs(mod - runs[0]) < tol
                && Math.abs(mod - runs[1]) < tol
                && Math.abs(3f * mod - runs[2]) < 1.5f * tol
                && Math.abs(mod - runs[3]) < tol
                && Math.abs(mod - runs[4]) < tol;
    }

    private static float verifyVerticalFinder(int[] pixels, int w, int h, int threshold,
                                              int cx, int startY, float modEst) {
        if (cx < 0 || cx >= w) return -1f;
        int[] v = new int[5];
        int y = startY;
        while (y >= 0 && pixelLuma(pixels[y * w + cx]) < threshold) { v[2]++; y--; }
        if (y < 0) return -1f;
        while (y >= 0 && pixelLuma(pixels[y * w + cx]) >= threshold) { v[1]++; y--; }
        if (y < 0) return -1f;
        while (y >= 0 && pixelLuma(pixels[y * w + cx]) < threshold) { v[0]++; y--; }
        y = startY + 1;
        while (y < h && pixelLuma(pixels[y * w + cx]) < threshold) { v[2]++; y++; }
        if (y >= h) return -1f;
        while (y < h && pixelLuma(pixels[y * w + cx]) >= threshold) { v[3]++; y++; }
        if (y >= h) return -1f;
        while (y < h && pixelLuma(pixels[y * w + cx]) < threshold) { v[4]++; y++; }
        if (!matchesFinderRatio(v)) return -1f;
        float total = v[0] + v[1] + v[2] + v[3] + v[4];
        if (Math.abs(total / 7f - modEst) > modEst * 0.45f) return -1f;
        return y - v[4] - v[3] - v[2] * 0.5f;
    }

    private static void mergeCandidate(List<float[]> list, float cx, float cy, float mod) {
        for (float[] c : list) {
            if (Math.abs(c[0] - cx) < mod * 2.5f && Math.abs(c[1] - cy) < mod * 2.5f) {
                float count = c[3] + 1f;
                c[0] = (c[0] * c[3] + cx) / count;
                c[1] = (c[1] * c[3] + cy) / count;
                c[2] = (c[2] * c[3] + mod) / count;
                c[3] = count;
                return;
            }
        }
        list.add(new float[]{cx, cy, mod, 1f});
    }

    private static int dp(float d, int v) {
        return AikoTheme.dp(d, v);
    }
}

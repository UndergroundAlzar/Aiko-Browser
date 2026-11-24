package app.aikobrowser;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Icon;
import android.net.Uri;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Standalone fullscreen PWA & web-app home-screen shortcut helper.
 * Uses Android's native ShortcutManager with adaptive-ready Material monogram or favicon icons.
 */
public final class PwaHelper {
    public static final String EXTRA_PWA_URL = "aiko_pwa_url";
    public static final String EXTRA_PWA_STANDALONE = "aiko_pwa_standalone";
    public static final String EXTRA_PWA_TITLE = "aiko_pwa_title";

    private static final String MAIN_ACTIVITY_CLASS = "app.aikobrowser.MainActivity";
    private static final int ICON_CANVAS_PX = 216; // 108dp @ 2x adaptive icon canvas

    private PwaHelper() {}

    /** Returns true when the current launcher supports pinning home-screen shortcuts. */
    public static boolean isSupported(Context context) {
        if (context == null) return false;
        try {
            ShortcutManager sm = context.getSystemService(ShortcutManager.class);
            return sm != null && sm.isRequestPinShortcutSupported();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Pins a home-screen shortcut targeting MainActivity.
     * When {@code standalone} is true, launches in its own document card with standalone PWA flags.
     */
    public static boolean pinStandaloneShortcut(Activity activity, String url, String title,
                                                Bitmap favicon, boolean standalone) {
        if (activity == null || url == null) return false;
        String cleanUrl = url.trim();
        if (cleanUrl.isEmpty()) return false;

        ShortcutManager sm;
        try {
            sm = activity.getSystemService(ShortcutManager.class);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            return false;
        }

        AikoTheme.applyTheme(activity);
        String displayTitle = resolveTitle(title, cleanUrl);
        String shortLabel = truncate(displayTitle, 24);
        String longLabel = truncate(displayTitle, 64);

        Uri uri;
        try {
            uri = Uri.parse(cleanUrl);
        } catch (RuntimeException ignored) {
            return false;
        }

        Intent launch = new Intent(Intent.ACTION_VIEW, uri);
        launch.setClassName(activity.getPackageName(), MAIN_ACTIVITY_CLASS);
        launch.putExtra(EXTRA_PWA_URL, cleanUrl);
        launch.putExtra(EXTRA_PWA_STANDALONE, standalone);
        launch.putExtra(EXTRA_PWA_TITLE, displayTitle);
        if (standalone) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        } else {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }

        Icon icon = buildAdaptiveIcon(displayTitle, cleanUrl, favicon);
        String shortcutId = "aiko_pwa_" + (standalone ? "s_" : "t_") + shortHash(cleanUrl);

        try {
            ShortcutInfo info = new ShortcutInfo.Builder(activity, shortcutId)
                    .setShortLabel(shortLabel)
                    .setLongLabel(longLabel)
                    .setIcon(icon)
                    .setIntent(launch)
                    .build();
            return sm.requestPinShortcut(info, null);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException ignored) {
            return false;
        }
    }

    /**
     * Builds an adaptive-ready 216x216 Icon (safe zone centered within inner 60% diameter)
     * from a page favicon or a Material obsidian/accent monogram badge.
     */
    public static Icon buildAdaptiveIcon(String title, String url, Bitmap favicon) {
        Bitmap canvasBmp = Bitmap.createBitmap(ICON_CANVAS_PX, ICON_CANVAS_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(canvasBmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        int bgStart = AikoTheme.GLOW_START;
        int bgEnd = AikoTheme.BG;
        int accent = AikoTheme.ACCENT;

        boolean hasFavicon = favicon != null && !favicon.isRecycled()
                && favicon.getWidth() >= 8 && favicon.getHeight() >= 8;

        if (hasFavicon) {
            // Sleek obsidian gradient canvas
            paint.setShader(new LinearGradient(0f, 0f, ICON_CANVAS_PX, ICON_CANVAS_PX,
                    bgStart, bgEnd, Shader.TileMode.CLAMP));
            c.drawRect(0f, 0f, ICON_CANVAS_PX, ICON_CANVAS_PX, paint);
            paint.setShader(null);

            // Soft inner plate so transparent dark or light favicons remain ultra-legible
            boolean darkTransparent = isMostlyDarkWithTransparency(favicon);
            float plateInset = ICON_CANVAS_PX * 0.22f; // 56% inner box (well inside 66% adaptive safe circle)
            RectF plate = new RectF(plateInset, plateInset,
                    ICON_CANVAS_PX - plateInset, ICON_CANVAS_PX - plateInset);
            paint.setColor(darkTransparent ? 0xFFF3F6FC : AikoTheme.CARD);
            c.drawRoundRect(plate, ICON_CANVAS_PX * 0.11f, ICON_CANVAS_PX * 0.11f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            paint.setColor(darkTransparent ? 0x33000000 : ((accent & 0x00FFFFFF) | 0x55000000));
            c.drawRoundRect(plate, ICON_CANVAS_PX * 0.11f, ICON_CANVAS_PX * 0.11f, paint);
            paint.setStyle(Paint.Style.FILL);

            // Fit favicon cleanly inside plate with comfortable breathing room
            float pad = plate.width() * 0.16f;
            float maxW = plate.width() - pad * 2f;
            float maxH = plate.height() - pad * 2f;
            float scale = Math.min(maxW / favicon.getWidth(), maxH / favicon.getHeight());
            float drawW = favicon.getWidth() * scale;
            float drawH = favicon.getHeight() * scale;
            float dx = (ICON_CANVAS_PX - drawW) * 0.5f;
            float dy = (ICON_CANVAS_PX - drawH) * 0.5f;
            Rect src = new Rect(0, 0, favicon.getWidth(), favicon.getHeight());
            RectF dst = new RectF(dx, dy, dx + drawW, dy + drawH);
            c.drawBitmap(favicon, src, dst, paint);
        } else {
            // Material monogram badge tinted with active AikoTheme.ACCENT
            paint.setShader(new LinearGradient(0f, 0f, ICON_CANVAS_PX, ICON_CANVAS_PX,
                    accent, blend(accent, AikoTheme.SURFACE, 0.35f), Shader.TileMode.CLAMP));
            c.drawRect(0f, 0f, ICON_CANVAS_PX, ICON_CANVAS_PX, paint);
            paint.setShader(null);

            // Subtle luminous inner halo ring
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor((AikoTheme.onAccentColor() & 0x00FFFFFF) | 0x26000000);
            c.drawCircle(ICON_CANVAS_PX * 0.5f, ICON_CANVAS_PX * 0.5f, ICON_CANVAS_PX * 0.27f, paint);
            paint.setStyle(Paint.Style.FILL);

            String glyph = extractMonogram(title, url);
            paint.setColor(AikoTheme.onAccentColor());
            paint.setTypeface(AikoTheme.BOLD);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(glyph.length() > 1 ? ICON_CANVAS_PX * 0.25f : ICON_CANVAS_PX * 0.31f);
            float baseline = ICON_CANVAS_PX * 0.5f - (paint.ascent() + paint.descent()) * 0.5f;
            c.drawText(glyph, ICON_CANVAS_PX * 0.5f, baseline, paint);
        }

        return Icon.createWithAdaptiveBitmap(canvasBmp);
    }

    private static boolean isMostlyDarkWithTransparency(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int stepX = Math.max(1, w / 12);
        int stepY = Math.max(1, h / 12);
        int transparent = 0;
        int opaque = 0;
        double lumSum = 0.0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int px = bmp.getPixel(x, y);
                int a = (px >>> 24) & 0xFF;
                if (a < 40) {
                    transparent++;
                } else {
                    opaque++;
                    lumSum += AikoTheme.Palette.relativeLuminance(px);
                }
            }
        }
        return transparent > 0 && opaque > 0 && (lumSum / opaque) < 0.22;
    }

    private static String resolveTitle(String title, String url) {
        if (title != null) {
            String trimmed = title.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        String host = HttpUrlUtil.extractHost(url);
        if (!host.isEmpty()) {
            if (host.startsWith("www.") && host.length() > 4) host = host.substring(4);
            return host;
        }
        return "Aiko 网页应用";
    }

    private static String extractMonogram(String title, String url) {
        String host = HttpUrlUtil.extractHost(url);
        if (host.startsWith("www.") && host.length() > 4) host = host.substring(4);
        String source = (title != null && !title.trim().isEmpty()) ? title.trim() : host;
        if (source == null || source.isEmpty()) return "A";
        for (int i = 0; i < source.length(); ) {
            int cp = source.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
            }
            i += Character.charCount(cp);
        }
        return "A";
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static int blend(int c1, int c2, float ratio) {
        float inv = 1f - ratio;
        int r = Math.round(((c1 >> 16) & 0xFF) * inv + ((c2 >> 16) & 0xFF) * ratio);
        int g = Math.round(((c1 >> 8) & 0xFF) * inv + ((c2 >> 8) & 0xFF) * ratio);
        int b = Math.round((c1 & 0xFF) * inv + (c2 & 0xFF) * ratio);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String shortHash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((digest[i] & 0xFF) >>> 4, 16));
                sb.append(Character.forDigit(digest[i] & 0x0F, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}

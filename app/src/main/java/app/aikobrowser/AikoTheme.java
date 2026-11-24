package app.aikobrowser;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Dynamic Material / Obsidian Flagship design system and 185Hz-safe motion helpers.
 * Pure programmatic Android Views, zero XML inflation, minimal draw allocations.
 *
 * Palette tokens are initialized to Obsidian Indigo defaults and dynamically updated via
 * {@link #applyTheme(Context)} with zero restart required.
 */
final class AikoTheme {
    static final String PREFS_NAME = "aiko_theme";
    static final String KEY_MODE = "mode";
    static final String KEY_CUSTOM_SEED = "custom_seed";
    static final String KEY_SURFACE_TINT = "surface_tint";

    static final String MODE_OBSIDIAN = "obsidian";
    static final String MODE_DYNAMIC = "dynamic";
    static final String MODE_EMERALD = "emerald";
    static final String MODE_SAKURA = "sakura";
    static final String MODE_AMBER = "amber";
    static final String MODE_VIOLET = "violet";
    static final String MODE_OLED = "oled";
    static final String MODE_CUSTOM = "custom";

    static final String[] ALL_MODES = {
            MODE_OBSIDIAN, MODE_DYNAMIC, MODE_EMERALD, MODE_SAKURA,
            MODE_AMBER, MODE_VIOLET, MODE_OLED, MODE_CUSTOM
    };

    static final int SEED_OBSIDIAN = 0xFF7C9BFF;
    static final int SEED_EMERALD = 0xFF34D399;
    static final int SEED_SAKURA = 0xFFF472B6;
    static final int SEED_AMBER = 0xFFFB923C;
    static final int SEED_VIOLET = 0xFFA78BFA;
    static final int SEED_OLED = 0xFFE2E8F0;

    // Live dynamic palette tokens (Obsidian Indigo defaults at class init for zero-config callers)
    static int BG = 0xFF0B0D12;             // Deep tinted obsidian canvas
    static int SURFACE = 0xFF111520;        // Elevated panel / bottom bar / sheet
    static int CARD = 0xFF171D2B;           // Glass card / elevated tile
    static int CAPSULE = 0xFF181E2E;        // Floating obsidian-glass URL capsule
    static int PILL = 0xFF1E2538;           // Omnibox / input / action pill
    static int HIGHLIGHT = 0xFF25314C;      // Active / selected highlight
    static int STROKE_ACCENT = 0x667C9BFF;  // Luminous active ring
    static int ACCENT = 0xFF7C9BFF;         // Primary dynamic accent
    static int ACCENT_CYAN = 0xFF5CE1E6;    // Analogous secondary accent (gradient progress bar)
    static int CYAN = 0xFF5CE1E6;           // Alias kept strictly equal to ACCENT_CYAN
    static int RIPPLE = 0x287C9BFF;         // Refined accent ripple
    static int RIPPLE_ACCENT = 0x287C9BFF;  // Alias kept strictly equal to RIPPLE
    static int GLOW_START = 0xFF1A233B;     // Telemetry banner gradient start
    static int GLOW_MID = 0xFF121724;       // Telemetry banner gradient mid
    static int GLOW_END = 0xFF0E1624;       // Telemetry banner gradient end

    // Constant high-contrast neutrals & semantic status colors
    static final int STROKE = 0x22FFFFFF;         // Crisp 1dp hairline border
    static final int CAPSULE_STROKE = 0x26FFFFFF; // Luminous capsule hairline
    static final int DIVIDER = 0x1AFFFFFF;        // Architectural 1dp deck separator
    static final int HANDLE = 0x38FFFFFF;         // Sheet grabber pill

    static final int TEXT = 0xFFF3F6FC;           // Primary text
    static final int TEXT_MUTED = 0xFF9AA5BD;     // Secondary text
    static final int TEXT_DIM = 0xFF69758E;       // Tertiary / caption text

    static final int EMERALD = 0xFF4ADE80;        // Success / HTTPS / 185Hz ok
    static final int AMBER = 0xFFFBBF24;          // Warn / OEM cap / paused
    static final int CORAL = 0xFFF87171;          // Danger / HTTP / error

    static final int RIPPLE_LIGHT = 0x1AFFFFFF;   // Understated light ripple
    static final int SCRIM = 0xA304060A;          // Velvety obsidian backdrop scrim

    static final Typeface MEDIUM;
    static final Typeface BOLD;
    static final Typeface MONO;

    // 185Hz-safe flagship motion curves
    static final PathInterpolator EASE_OUT;
    static final PathInterpolator EASE_STD;
    static final PathInterpolator EMPHASIZED_DECEL;
    static final PathInterpolator SPRING_SETTLE;
    static final PathInterpolator FAST_OUT;

    private static final float DEFAULT_PRESS_SCALE = 0.94f;
    private static final float TILE_PRESS_SCALE = 0.96f;

    /** Shared zero-alloc touch listeners for crisp tactile icon/tile press feedback. */
    private static final View.OnTouchListener PRESS_FEEDBACK = createPressListener(DEFAULT_PRESS_SCALE);
    private static final View.OnTouchListener PRESS_FEEDBACK_TILE = createPressListener(TILE_PRESS_SCALE);

    static {
        Typeface med = null, bold = null, mono = null;
        PathInterpolator eo = null, es = null, ed = null, ss = null, fo = null;
        try {
            med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            bold = Typeface.create("sans-serif-medium", Typeface.BOLD);
            mono = Typeface.MONOSPACE;
            eo = new PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f);
            es = new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f);
            ed = new PathInterpolator(0.05f, 0.70f, 0.10f, 1.0f);
            ss = new PathInterpolator(0.18f, 0.92f, 0.30f, 1.04f);
            fo = new PathInterpolator(0.30f, 0.0f, 0.80f, 0.15f);
        } catch (RuntimeException ignored) {
            // Host JVM unit tests running against android.jar stubs
        }
        MEDIUM = med;
        BOLD = bold;
        MONO = mono;
        EASE_OUT = eo;
        EASE_STD = es;
        EMPHASIZED_DECEL = ed;
        SPRING_SETTLE = ss;
        FAST_OUT = fo;
    }

    private AikoTheme() {}

    /**
     * Pure-Java immutable OLED dark tonal palette derived from any RGB seed color.
     * Contains zero android.* runtime dependencies so host JVM tests can verify color math & WCAG contrast directly.
     */
    public static final class Palette {
        public final int bg;
        public final int surface;
        public final int card;
        public final int capsule;
        public final int pill;
        public final int highlight;
        public final int accent;
        public final int accentCyan;
        public final int strokeAccent;
        public final int ripple;
        public final int rippleAccent;
        public final int glowStart;
        public final int glowMid;
        public final int glowEnd;

        public Palette(int bg, int surface, int card, int capsule, int pill, int highlight,
                       int accent, int accentCyan, int glowStart, int glowMid, int glowEnd) {
            this.bg = bg | 0xFF000000;
            this.surface = surface | 0xFF000000;
            this.card = card | 0xFF000000;
            this.capsule = capsule | 0xFF000000;
            this.pill = pill | 0xFF000000;
            this.highlight = highlight | 0xFF000000;
            this.accent = accent | 0xFF000000;
            this.accentCyan = accentCyan | 0xFF000000;
            this.strokeAccent = (this.accent & 0x00FFFFFF) | 0x66000000;
            this.ripple = (this.accent & 0x00FFFFFF) | 0x28000000;
            this.rippleAccent = this.ripple;
            this.glowStart = glowStart | 0xFF000000;
            this.glowMid = glowMid | 0xFF000000;
            this.glowEnd = glowEnd | 0xFF000000;
        }

        public static final Palette OBSIDIAN = new Palette(
                0xFF0B0D12, 0xFF111520, 0xFF171D2B, 0xFF181E2E, 0xFF1E2538, 0xFF25314C,
                0xFF7C9BFF, 0xFF5CE1E6, 0xFF1A233B, 0xFF121724, 0xFF0E1624);

        public static final Palette OLED = new Palette(
                0xFF000000, 0xFF08080A, 0xFF101014, 0xFF121216, 0xFF18181E, 0xFF24262F,
                0xFFE2E8F0, 0xFF94A3B8, 0xFF14151B, 0xFF0A0A0D, 0xFF050507);

        /**
         * Derives a harmonious OLED dark tonal palette from an arbitrary seed RGB color.
         * Guarantees WCAG AA+ readable contrast for primary text, muted text, accent, and analogous secondary accent
         * across all hues (0..360) and arbitrary dark/saturated seeds.
         */
        public static Palette derive(int seedRgb, float surfaceTint, boolean pureOled) {
            if (pureOled) return OLED;
            int rgb = seedRgb | 0xFF000000;
            float tint = clamp(Float.isNaN(surfaceTint) ? 1.0f : surfaceTint, 0.0f, 1.5f);
            if (rgb == 0xFF7C9BFF && Math.abs(tint - 1.0f) < 0.01f) {
                return OBSIDIAN;
            }

            float[] hsv = rgbToHsv(rgb);
            float h = hsv[0];
            float s = hsv[1];
            float v = hsv[2];

            int bg, surface, card, capsule, pill, highlight;
            int glowStart, glowMid, glowEnd;
            int accent, accentCyan;

            if (s < 0.08f) {
                // Neutral achromatic seed -> sleek graphite/silver obsidian surfaces
                float t = Math.min(1.0f, tint);
                bg = hsvToRgb(220f, 0.08f * t, 0.04f + 0.03f * t);
                surface = hsvToRgb(220f, 0.10f * t, 0.085f + 0.035f * t);
                card = hsvToRgb(220f, 0.10f * t, 0.125f + 0.04f * t);
                capsule = hsvToRgb(220f, 0.11f * t, 0.135f + 0.042f * t);
                pill = hsvToRgb(220f, 0.11f * t, 0.17f + 0.045f * t);
                highlight = hsvToRgb(220f, 0.14f * t, 0.23f + 0.055f * t);
                glowStart = hsvToRgb(220f, 0.12f * t, 0.16f + 0.04f * t);
                glowMid = surface;
                glowEnd = bg;
                accent = ensureReadableAccent(0xFFD8E1EE, card, bg);
                accentCyan = ensureReadableAccent(0xFF94A3B8, card, bg);
                return new Palette(bg, surface, card, capsule, pill, highlight, accent, accentCyan,
                        glowStart, glowMid, glowEnd);
            }

            float sNorm = clamp(s, 0.30f, 1.0f);
            float tSoft = Math.min(1.0f, tint);
            float bgSat = clamp(0.30f * sNorm * tint, 0f, 0.40f);
            float surfSat = clamp(0.38f * sNorm * tint, 0f, 0.48f);
            float cardSat = clamp(0.42f * sNorm * tint, 0f, 0.50f);
            float capSat = clamp(0.44f * sNorm * tint, 0f, 0.52f);
            float pillSat = clamp(0.42f * sNorm * tint, 0f, 0.52f);
            float hiSat = clamp(0.48f * sNorm * tint, 0f, 0.56f);

            bg = hsvToRgb(h, bgSat, 0.045f + 0.026f * tSoft);
            surface = hsvToRgb(h, surfSat, 0.092f + 0.032f * tSoft);
            card = hsvToRgb(h, cardSat, 0.132f + 0.036f * tSoft);
            capsule = hsvToRgb(h, capSat, 0.142f + 0.038f * tSoft);
            pill = hsvToRgb(h, pillSat, 0.175f + 0.044f * tSoft);
            highlight = hsvToRgb(h, hiSat, 0.225f + 0.068f * tSoft);

            // Preserve curated luminous seeds when already comfortably legible; otherwise lift cleanly
            int candidateAccent;
            if (v >= 0.72f && s >= 0.25f && s <= 0.85f && contrastRatio(rgb, card) >= 5.5) {
                candidateAccent = rgb;
            } else {
                candidateAccent = hsvToRgb(h, clamp(s, 0.38f, 0.78f), Math.max(v, 0.88f));
            }
            accent = ensureReadableAccent(candidateAccent, card, bg);

            // Analogous secondary accent for gradient progress bar & status badges
            float shift = (h >= 120f && h <= 205f) ? 38f : -42f;
            float h2 = (h + shift + 360f) % 360f;
            float[] accHsv = rgbToHsv(accent);
            int candidateCyan = hsvToRgb(h2, clamp(accHsv[1] * 0.92f, 0.36f, 0.76f), Math.max(accHsv[2], 0.88f));
            accentCyan = ensureReadableAccent(candidateCyan, card, bg);

            glowStart = hsvToRgb(h, clamp(0.50f * sNorm * tint, 0f, 0.56f), 0.18f + 0.045f * tSoft);
            glowMid = hsvToRgb(h, surfSat, 0.105f + 0.03f * tSoft);
            glowEnd = hsvToRgb(h2, clamp(0.40f * sNorm * tint, 0f, 0.46f), 0.09f + 0.03f * tSoft);

            return new Palette(bg, surface, card, capsule, pill, highlight, accent, accentCyan,
                    glowStart, glowMid, glowEnd);
        }

        public static Palette preset(String mode) {
            if (MODE_OLED.equals(mode)) return OLED;
            if (MODE_EMERALD.equals(mode)) return derive(SEED_EMERALD, 1.0f, false);
            if (MODE_SAKURA.equals(mode)) return derive(SEED_SAKURA, 1.0f, false);
            if (MODE_AMBER.equals(mode)) return derive(SEED_AMBER, 1.0f, false);
            if (MODE_VIOLET.equals(mode)) return derive(SEED_VIOLET, 1.0f, false);
            return OBSIDIAN;
        }

        /** Raises lightness and gently lifts toward white until WCAG AA+ contrast is guaranteed. */
        private static int ensureReadableAccent(int color, int cardBg, int canvasBg) {
            int c = color | 0xFF000000;
            if (contrastRatio(c, cardBg) >= 5.5 && contrastRatio(c, canvasBg) >= 6.0) return c;
            float[] hsv = rgbToHsv(c);
            for (int step = 0; step < 24; step++) {
                if (hsv[2] < 0.98f) {
                    hsv[2] = Math.min(1.0f, hsv[2] + 0.06f);
                } else {
                    hsv[1] = Math.max(0.18f, hsv[1] - 0.055f);
                }
                c = hsvToRgb(hsv[0], hsv[1], hsv[2]);
                if (contrastRatio(c, cardBg) >= 5.5 && contrastRatio(c, canvasBg) >= 6.0) return c;
            }
            return c;
        }

        public static int onAccentInk(int accentArgb) {
            return contrastRatio(0xFF0B0D12, accentArgb) >= contrastRatio(0xFFF3F6FC, accentArgb)
                    ? 0xFF0B0D12 : 0xFFF3F6FC;
        }

        /** WCAG 2.1 relative luminance in [0.0, 1.0]. */
        public static double relativeLuminance(int argb) {
            double r = linearize(((argb >> 16) & 0xFF) / 255.0);
            double g = linearize(((argb >> 8) & 0xFF) / 255.0);
            double b = linearize((argb & 0xFF) / 255.0);
            return 0.2126 * r + 0.7152 * g + 0.0722 * b;
        }

        private static double linearize(double channel) {
            return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
        }

        /** WCAG 2.1 contrast ratio in [1.0, 21.0]. */
        public static double contrastRatio(int argb1, int argb2) {
            double l1 = relativeLuminance(argb1);
            double l2 = relativeLuminance(argb2);
            double bright = Math.max(l1, l2);
            double dark = Math.min(l1, l2);
            return (bright + 0.05) / (dark + 0.05);
        }

        public static float[] rgbToHsv(int argb) {
            float r = ((argb >> 16) & 0xFF) / 255f;
            float g = ((argb >> 8) & 0xFF) / 255f;
            float b = (argb & 0xFF) / 255f;
            float max = Math.max(r, Math.max(g, b));
            float min = Math.min(r, Math.min(g, b));
            float delta = max - min;
            float h;
            if (delta < 1e-6f) {
                h = 0f;
            } else if (max == r) {
                h = 60f * (((g - b) / delta) % 6f);
            } else if (max == g) {
                h = 60f * (((b - r) / delta) + 2f);
            } else {
                h = 60f * (((r - g) / delta) + 4f);
            }
            if (h < 0f) h += 360f;
            float s = max < 1e-6f ? 0f : delta / max;
            return new float[]{h, s, max};
        }

        public static int hsvToRgb(float h, float s, float v) {
            float hh = ((h % 360f) + 360f) % 360f;
            float ss = clamp(s, 0f, 1f);
            float vv = clamp(v, 0f, 1f);
            float c = vv * ss;
            float x = c * (1f - Math.abs(((hh / 60f) % 2f) - 1f));
            float m = vv - c;
            float r1, g1, b1;
            if (hh < 60f) { r1 = c; g1 = x; b1 = 0f; }
            else if (hh < 120f) { r1 = x; g1 = c; b1 = 0f; }
            else if (hh < 180f) { r1 = 0f; g1 = c; b1 = x; }
            else if (hh < 240f) { r1 = 0f; g1 = x; b1 = c; }
            else if (hh < 300f) { r1 = x; g1 = 0f; b1 = c; }
            else { r1 = c; g1 = 0f; b1 = x; }
            int r = clampInt(Math.round((r1 + m) * 255f), 0, 255);
            int g = clampInt(Math.round((g1 + m) * 255f), 0, 255);
            int b = clampInt(Math.round((b1 + m) * 255f), 0, 255);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        public static String formatHex(int color) {
            return String.format(Locale.ROOT, "#%06X", color & 0x00FFFFFF);
        }

        public static int parseHex(String text, int fallback) {
            if (text == null) return fallback;
            String s = text.trim();
            if (s.startsWith("#")) s = s.substring(1);
            if (s.length() == 8) s = s.substring(2);
            if (s.length() != 6) return fallback;
            try {
                return 0xFF000000 | Integer.parseInt(s, 16);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static float clamp(float val, float min, float max) {
            return val < min ? min : (val > max ? max : val);
        }

        private static int clampInt(int val, int min, int max) {
            return val < min ? min : (val > max ? max : val);
        }
    }

    /** Convenience pure-Java entry point delegating to {@link Palette#derive(int, float, boolean)}. */
    static Palette derivePalette(int seedRgb, float surfaceTint, boolean pureOled) {
        return Palette.derive(seedRgb, surfaceTint, pureOled);
    }

    static boolean supportsDynamicColor() {
        return Build.VERSION.SDK_INT >= 31;
    }

    static String loadMode(Context ctx) {
        if (ctx == null) return MODE_OBSIDIAN;
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_MODE, MODE_OBSIDIAN);
        return isKnownMode(mode) ? mode : MODE_OBSIDIAN;
    }

    static int loadCustomSeed(Context ctx) {
        if (ctx == null) return SEED_OBSIDIAN;
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_CUSTOM_SEED, SEED_OBSIDIAN) | 0xFF000000;
    }

    static float loadSurfaceTint(Context ctx) {
        if (ctx == null) return 1.0f;
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float t = prefs.getFloat(KEY_SURFACE_TINT, 1.0f);
        return Float.isNaN(t) ? 1.0f : Math.max(0f, Math.min(1.5f, t));
    }

    private static boolean isKnownMode(String mode) {
        if (mode == null) return false;
        for (String m : ALL_MODES) if (m.equals(mode)) return true;
        return false;
    }

    static int resolveDynamicSeed(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < 31) return SEED_OBSIDIAN;
        try {
            int color = ctx.getColor(android.R.color.system_accent1_300);
            if ((color & 0x00FFFFFF) != 0) return color | 0xFF000000;
        } catch (RuntimeException ignored) { }
        try {
            int color = ctx.getColor(android.R.color.system_accent1_200);
            if ((color & 0x00FFFFFF) != 0) return color | 0xFF000000;
        } catch (RuntimeException ignored) { }
        return SEED_OBSIDIAN;
    }

    static Palette resolvePalette(Context ctx) {
        String mode = loadMode(ctx);
        if (MODE_OLED.equals(mode)) return Palette.OLED;
        if (MODE_CUSTOM.equals(mode)) {
            return Palette.derive(loadCustomSeed(ctx), loadSurfaceTint(ctx), false);
        }
        if (MODE_DYNAMIC.equals(mode)) {
            if (supportsDynamicColor()) {
                return Palette.derive(resolveDynamicSeed(ctx), 1.0f, false);
            }
            return Palette.OBSIDIAN;
        }
        return Palette.preset(mode);
    }

    static void applyTheme(Context ctx) {
        applyPalette(resolvePalette(ctx));
    }

    static void saveAndApply(Context ctx, String mode, int customSeed, float surfaceTint) {
        String safeMode = isKnownMode(mode) ? mode : MODE_OBSIDIAN;
        int safeSeed = customSeed | 0xFF000000;
        float safeTint = Float.isNaN(surfaceTint) ? 1.0f : Math.max(0f, Math.min(1.5f, surfaceTint));
        if (ctx != null) {
            ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_MODE, safeMode)
                    .putInt(KEY_CUSTOM_SEED, safeSeed)
                    .putFloat(KEY_SURFACE_TINT, safeTint)
                    .apply();
        }
        if (MODE_OLED.equals(safeMode)) applyPalette(Palette.OLED);
        else if (MODE_CUSTOM.equals(safeMode)) applyPalette(Palette.derive(safeSeed, safeTint, false));
        else if (MODE_DYNAMIC.equals(safeMode)) {
            applyPalette(supportsDynamicColor() ? Palette.derive(resolveDynamicSeed(ctx), 1.0f, false) : Palette.OBSIDIAN);
        } else {
            applyPalette(Palette.preset(safeMode));
        }
    }

    static void applyPalette(Palette p) {
        if (p == null) return;
        BG = p.bg;
        SURFACE = p.surface;
        CARD = p.card;
        CAPSULE = p.capsule;
        PILL = p.pill;
        HIGHLIGHT = p.highlight;
        ACCENT = p.accent;
        ACCENT_CYAN = p.accentCyan;
        CYAN = p.accentCyan;
        STROKE_ACCENT = p.strokeAccent;
        RIPPLE = p.ripple;
        RIPPLE_ACCENT = p.rippleAccent;
        GLOW_START = p.glowStart;
        GLOW_MID = p.glowMid;
        GLOW_END = p.glowEnd;
    }

    static String modeLabel(String mode) {
        if (MODE_DYNAMIC.equals(mode)) return "Material You 壁纸取色";
        if (MODE_EMERALD.equals(mode)) return "赛博翡翠";
        if (MODE_SAKURA.equals(mode)) return "樱花粉霞";
        if (MODE_AMBER.equals(mode)) return "熔岩琥珀";
        if (MODE_VIOLET.equals(mode)) return "极光紫罗兰";
        if (MODE_OLED.equals(mode)) return "纯黑 OLED 极简";
        if (MODE_CUSTOM.equals(mode)) return "自定义调色";
        return "黑曜靛蓝";
    }

    static String modeShortLabel(String mode) {
        if (MODE_DYNAMIC.equals(mode)) return "Material You";
        if (MODE_EMERALD.equals(mode)) return "赛博翡翠";
        if (MODE_SAKURA.equals(mode)) return "樱花粉霞";
        if (MODE_AMBER.equals(mode)) return "熔岩琥珀";
        if (MODE_VIOLET.equals(mode)) return "极光紫罗兰";
        if (MODE_OLED.equals(mode)) return "纯黑 OLED";
        if (MODE_CUSTOM.equals(mode)) return "自定义";
        return "黑曜靛蓝";
    }

    static String modeDescription(Context ctx, String mode) {
        if (MODE_DYNAMIC.equals(mode)) {
            return supportsDynamicColor() ? "实时读取 Android Monet 壁纸色板" : "需 Android 12+，当前优雅回退黑曜靛蓝";
        }
        if (MODE_EMERALD.equals(mode)) return "赛博翡翠绿 · 冷静通透";
        if (MODE_SAKURA.equals(mode)) return "樱花粉霞调 · 柔和质感";
        if (MODE_AMBER.equals(mode)) return "熔岩琥珀金 · 温暖明快";
        if (MODE_VIOLET.equals(mode)) return "极光紫罗兰 · 深邃灵动";
        if (MODE_OLED.equals(mode)) return "纯黑 #000000 画布 · 极简省电";
        if (MODE_CUSTOM.equals(mode)) return "色相 / 饱和度 / 底色浸染自由定制";
        return "旗舰默认 · 深邃黑曜靛蓝";
    }

    static int modePreviewColor(Context ctx, String mode) {
        if (MODE_DYNAMIC.equals(mode)) return supportsDynamicColor() ? resolveDynamicSeed(ctx) : SEED_OBSIDIAN;
        if (MODE_EMERALD.equals(mode)) return SEED_EMERALD;
        if (MODE_SAKURA.equals(mode)) return SEED_SAKURA;
        if (MODE_AMBER.equals(mode)) return SEED_AMBER;
        if (MODE_VIOLET.equals(mode)) return SEED_VIOLET;
        if (MODE_OLED.equals(mode)) return SEED_OLED;
        if (MODE_CUSTOM.equals(mode)) return loadCustomSeed(ctx);
        return SEED_OBSIDIAN;
    }

    static int onAccentColor() {
        return Palette.onAccentInk(ACCENT);
    }

    static int dp(float density, int value) {
        return Math.round(value * density);
    }

    static GradientDrawable rounded(int fill, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static GradientDrawable card(int fill, int stroke, float strokePx, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (stroke != 0 && strokePx > 0) d.setStroke(Math.max(1, Math.round(strokePx)), stroke);
        return d;
    }

    static GradientDrawable topRounded(int fill, int stroke, float strokePx, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadii(new float[]{radiusPx, radiusPx, radiusPx, radiusPx, 0, 0, 0, 0});
        if (stroke != 0 && strokePx > 0) d.setStroke(Math.max(1, Math.round(strokePx)), stroke);
        return d;
    }

    /** Signature floating obsidian-glass capsule surface for URL/omnibox bars. */
    static GradientDrawable capsule(float density, float radiusPx) {
        return card(CAPSULE, CAPSULE_STROKE, density, radiusPx);
    }

    /** Luminous horizontal accent-to-secondary gradient hairline for top-edge page load progress. */
    static GradientDrawable progressBarDrawable(float radiusPx) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACCENT, ACCENT_CYAN});
        if (radiusPx > 0f) d.setCornerRadius(radiusPx);
        return d;
    }

    static GradientDrawable glowBanner(float radiusPx) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{GLOW_START, GLOW_MID, GLOW_END});
        d.setCornerRadius(radiusPx);
        d.setStroke(1, STROKE);
        return d;
    }

    static RippleDrawable ripple(int color, float radiusPx) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(color), null, mask);
    }

    /**
     * Attaches zero-alloc GPU press-scale micro-feedback (0.94f on ACTION_DOWN, crisp spring settle on release).
     * Never consumes touch events, preserving clicks, long-presses, ripples, and parent gesture detection.
     */
    static void attachPressFeedback(View view) {
        if (view != null) view.setOnTouchListener(PRESS_FEEDBACK);
    }

    /** Attaches GPU press-scale micro-feedback with a custom pressed scale (e.g., 0.96f for wider tiles). */
    static void attachPressFeedback(View view, float pressedScale) {
        if (view == null) return;
        if (Math.abs(pressedScale - DEFAULT_PRESS_SCALE) < 0.001f) {
            view.setOnTouchListener(PRESS_FEEDBACK);
        } else if (Math.abs(pressedScale - TILE_PRESS_SCALE) < 0.001f) {
            view.setOnTouchListener(PRESS_FEEDBACK_TILE);
        } else {
            view.setOnTouchListener(createPressListener(pressedScale));
        }
    }

    private static View.OnTouchListener createPressListener(float pressedScale) {
        return (v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (!v.isEnabled()) return false;
                v.animate().cancel();
                v.setLayerType(View.LAYER_TYPE_NONE, null);
                v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(75)
                        .setInterpolator(EASE_OUT)
                        .withLayer()
                        .start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().cancel();
                v.setLayerType(View.LAYER_TYPE_NONE, null);
                if (v.getScaleX() != 1f || v.getScaleY() != 1f) {
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(155)
                            .setInterpolator(SPRING_SETTLE)
                            .withLayer()
                            .start();
                }
            }
            return false;
        };
    }

    /** Creates the standard 24dp-top-rounded sheet container with centered drag handle. */
    static LinearLayout sheetContainer(Context ctx, float density, String title, Runnable onClose) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(density, 16), dp(density, 8), dp(density, 16), dp(density, 16));
        panel.setBackground(topRounded(SURFACE, STROKE, density, dp(density, 24)));
        panel.setElevation(dp(density, 16));

        // Centered drag handle pill
        View handle = new View(ctx);
        handle.setBackground(rounded(HANDLE, dp(density, 2)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(density, 36), dp(density, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(density, 4);
        hlp.bottomMargin = dp(density, 10);
        panel.addView(handle, hlp);

        // Header row
        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(density, 44)));

        TextView name = new TextView(ctx);
        name.setText(title);
        name.setTextSize(18);
        name.setTextColor(TEXT);
        name.setTypeface(MEDIUM);
        name.setLetterSpacing(-0.01f);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setPadding(dp(density, 4), 0, 0, 0);
        header.addView(name, new LinearLayout.LayoutParams(0, -1, 1));

        BrowserIcon close = new BrowserIcon(ctx, BrowserIcon.Kind.CLOSE);
        close.tint(TEXT_MUTED);
        close.setContentDescription("关闭" + title);
        close.setForeground(ripple(RIPPLE_LIGHT, dp(density, 20)));
        close.setFocusable(true);
        attachPressFeedback(close);
        close.setOnClickListener(v -> onClose.run());
        header.addView(close, new LinearLayout.LayoutParams(dp(density, 48), dp(density, 48)));

        return panel;
    }

    /** Configures and presents a bottom sheet Dialog cleanly with smooth dimmed scrim and GPU entrance. */
    static void presentSheet(Dialog dialog, View panel, DisplayModeEngine displayMode, float density) {
        FrameLayout wrapper = new FrameLayout(dialog.getContext());
        wrapper.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        dialog.setContentView(wrapper);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setDimAmount(0.62f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if (displayMode != null) displayMode.applyToDialog(dialog);

        // Ultra-crisp 170ms GPU-composited slide-up using emphasized decelerate with safe layer cleanup
        panel.animate().cancel();
        panel.setLayerType(View.LAYER_TYPE_NONE, null);
        panel.setTranslationY(dp(density, 24));
        panel.setAlpha(0f);
        panel.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(170)
                .setInterpolator(EMPHASIZED_DECEL)
                .withLayer()
                .withEndAction(() -> panel.setLayerType(View.LAYER_TYPE_NONE, null))
                .start();
    }

    /** GPU-composited Y-translation with safe layer cleanup (zero layout mutations during scroll). */
    static void animateTranslationY(View view, float targetY, long durationMs) {
        view.animate().cancel();
        view.setLayerType(View.LAYER_TYPE_NONE, null);
        if (Math.abs(view.getTranslationY() - targetY) < 0.5f) {
            view.setTranslationY(targetY);
            return;
        }
        view.animate()
                .translationY(targetY)
                .setDuration(durationMs)
                .setInterpolator(EASE_OUT)
                .withLayer()
                .withEndAction(() -> view.setLayerType(View.LAYER_TYPE_NONE, null))
                .start();
    }

    /** GPU-composited alpha fade with safe layer cleanup. */
    static void animateAlpha(View view, float targetAlpha, long durationMs) {
        view.animate().cancel();
        view.setLayerType(View.LAYER_TYPE_NONE, null);
        if (Math.abs(view.getAlpha() - targetAlpha) < 0.01f) {
            view.setAlpha(targetAlpha);
            return;
        }
        view.animate()
                .alpha(targetAlpha)
                .setDuration(durationMs)
                .setInterpolator(targetAlpha < view.getAlpha() ? FAST_OUT : EASE_OUT)
                .withLayer()
                .withEndAction(() -> view.setLayerType(View.LAYER_TYPE_NONE, null))
                .start();
    }

    static TextView sectionHeader(Context ctx, float density, String text) {
        TextView label = new TextView(ctx);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(TEXT_DIM);
        label.setTypeface(MEDIUM);
        label.setLetterSpacing(0.04f);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(density, 4), dp(density, 12), 0, dp(density, 6));
        return label;
    }

    static TextView badge(Context ctx, float density, String text, int textColor, int bgColor, int strokeColor) {
        TextView pill = new TextView(ctx);
        pill.setText(text);
        pill.setTextSize(11);
        pill.setTextColor(textColor);
        pill.setTypeface(MEDIUM);
        pill.setSingleLine(true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(density, 10), dp(density, 4), dp(density, 10), dp(density, 4));
        pill.setBackground(card(bgColor, strokeColor, density, dp(density, 14)));
        return pill;
    }

    /** Builds a sleek 2-column flagship toggle/action tile for Shield & Tools sheets. */
    static LinearLayout toggleTile(Context ctx, float density, BrowserIcon.Kind kind,
                                   String title, String subtitle, boolean active, boolean available,
                                   int activeColor, Runnable onClick) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(density, 12), dp(density, 11), dp(density, 10), dp(density, 11));
        tile.setBackground(card(active ? HIGHLIGHT : CARD, active ? STROKE_ACCENT : STROKE, density, dp(density, 15)));
        tile.setForeground(ripple(RIPPLE_ACCENT, dp(density, 15)));
        tile.setMinimumHeight(dp(density, 56));

        BrowserIcon glyph = new BrowserIcon(ctx, kind);
        glyph.tint(active ? activeColor : TEXT_MUTED);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(density, 22), dp(density, 22));
        glp.rightMargin = dp(density, 10);
        tile.addView(glyph, glp);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        tile.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));

        TextView main = new TextView(ctx);
        main.setText(title);
        main.setTextSize(13);
        main.setTextColor(TEXT);
        main.setTypeface(MEDIUM);
        main.setSingleLine(true);
        main.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(main);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(subtitle);
            sub.setTextSize(11);
            sub.setTextColor(active ? activeColor : TEXT_DIM);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(sub);
        }

        tile.setContentDescription(subtitle == null || subtitle.isEmpty() ? title : title + "，" + subtitle);
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        tile.setEnabled(available);
        tile.setFocusable(available);
        tile.setAlpha(available ? 1f : 0.38f);
        attachPressFeedback(tile, 0.96f);
        tile.setOnClickListener(v -> onClick.run());
        return tile;
    }
}

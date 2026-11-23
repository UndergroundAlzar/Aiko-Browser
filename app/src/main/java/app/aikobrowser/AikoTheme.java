package app.aikobrowser;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Obsidian Flagship design system and 185Hz-safe motion helpers.
 * Pure programmatic Android Views, zero XML inflation, minimal draw allocations.
 */
final class AikoTheme {
    // Obsidian Flagship Palette
    static final int BG = 0xFF0B0D12;          // Deep obsidian canvas
    static final int SURFACE = 0xFF131722;     // Elevated panel / bottom bar / sheet
    static final int CARD = 0xFF1B2130;        // Glass card / elevated tile
    static final int PILL = 0xFF21283B;        // Omnibox / input / action pill
    static final int HIGHLIGHT = 0xFF283450;   // Active / selected highlight
    static final int STROKE = 0x24FFFFFF;      // Crisp 1dp hairline border
    static final int STROKE_ACCENT = 0x557C9BFF;
    static final int DIVIDER = 0x16FFFFFF;

    static final int ACCENT = 0xFF7C9BFF;      // Electric periwinkle
    static final int CYAN = 0xFF5CE1E6;        // Secondary cyan highlight
    static final int TEXT = 0xFFF3F6FC;        // Primary text
    static final int TEXT_MUTED = 0xFF9AA5BD;  // Secondary text
    static final int TEXT_DIM = 0xFF69758E;    // Tertiary / caption text

    static final int EMERALD = 0xFF4ADE80;     // Success / HTTPS / 185Hz ok
    static final int AMBER = 0xFFFBBF24;       // Warn / OEM cap / paused
    static final int CORAL = 0xFFF87171;       // Danger / HTTP / error

    static final int RIPPLE_ACCENT = 0x307C9BFF;
    static final int RIPPLE_LIGHT = 0x22FFFFFF;
    static final int SCRIM = 0x9904060A;

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif-medium", Typeface.BOLD);
    static final Typeface MONO = Typeface.MONOSPACE;

    static final PathInterpolator EASE_OUT = new PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f);
    static final PathInterpolator EASE_STD = new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f);

    private AikoTheme() {}

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

    static GradientDrawable glowBanner(float radiusPx) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1A2238, 0xFF131722, 0xFF101926});
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

    /** Creates the standard 24dp-top-rounded obsidian sheet container with centered drag handle. */
    static LinearLayout sheetContainer(Context ctx, float density, String title, Runnable onClose) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(density, 16), dp(density, 8), dp(density, 16), dp(density, 14));
        panel.setBackground(topRounded(SURFACE, STROKE, density, dp(density, 24)));

        // Centered drag handle pill
        View handle = new View(ctx);
        handle.setBackground(rounded(0x40FFFFFF, dp(density, 3)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(density, 36), dp(density, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(density, 2);
        hlp.bottomMargin = dp(density, 8);
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
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setPadding(dp(density, 4), 0, 0, 0);
        header.addView(name, new LinearLayout.LayoutParams(0, -1, 1));

        BrowserIcon close = new BrowserIcon(ctx, BrowserIcon.Kind.CLOSE);
        close.tint(TEXT_MUTED);
        close.setContentDescription("关闭" + title);
        close.setForeground(ripple(RIPPLE_LIGHT, dp(density, 20)));
        close.setFocusable(true);
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
            window.setDimAmount(0.58f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if (displayMode != null) displayMode.applyToDialog(dialog);

        // Ultra-crisp 140ms GPU-composited slide-up with layer explicitly cleared on end/cancel
        panel.setTranslationY(dp(density, 28));
        panel.setAlpha(0f);
        panel.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(140)
                .setInterpolator(EASE_OUT)
                .withLayer()
                .withEndAction(() -> panel.setLayerType(View.LAYER_TYPE_NONE, null))
                .start();
    }

    /** GPU-composited Y-translation with safe layer cleanup (zero layout mutations during scroll). */
    static void animateTranslationY(View view, float targetY, long durationMs) {
        view.animate().cancel();
        if (Math.abs(view.getTranslationY() - targetY) < 0.5f) {
            view.setTranslationY(targetY);
            view.setLayerType(View.LAYER_TYPE_NONE, null);
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

    static TextView sectionHeader(Context ctx, float density, String text) {
        TextView label = new TextView(ctx);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(TEXT_DIM);
        label.setTypeface(MEDIUM);
        label.setLetterSpacing(0.03f);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(density, 4), dp(density, 10), 0, dp(density, 6));
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
        tile.setPadding(dp(density, 12), dp(density, 10), dp(density, 10), dp(density, 10));
        tile.setBackground(card(active ? HIGHLIGHT : CARD, active ? STROKE_ACCENT : STROKE, density, dp(density, 14)));
        tile.setForeground(ripple(RIPPLE_ACCENT, dp(density, 14)));
        tile.setMinimumHeight(dp(density, 54));

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
        main.setTextColor(active ? TEXT : TEXT);
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
        tile.setOnClickListener(v -> onClick.run());
        return tile;
    }
}

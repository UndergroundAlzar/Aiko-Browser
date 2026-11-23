package app.aikobrowser;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 185Hz GPU-composited gesture & chrome controller:
 * 1. Bottom-bar horizontal swipe to switch prev/next tab with floating ghost preview pill.
 * 2. Bottom-bar swipe-up to open Tab switcher sheet.
 * 3. Zero-consume left/right edge Back/Forward swipe with dynamic system-gesture exclusion.
 * 4. GPU-only translationY auto-hide bottom bar on scroll (zero LayoutParams mutations).
 */
final class GestureChrome {
    interface Callbacks {
        boolean isEditingAddress();
        boolean isHomeVisible();
        boolean canGoBack();
        boolean canGoForward();
        boolean canWebScrollHorizontally(int direction);
        int tabCount();
        int currentTabIndex();
        String tabPreviewLabel(int index);
        void onSwitchTab(int delta);
        void onOpenTabs();
        void onEdgeBack();
        void onEdgeForward();
    }

    private final Callbacks cb;
    private final float density;
    private final int touchSlop;
    private final float edgeZonePx;
    private final float edgeArmPx;
    private final float barSwitchArmPx;
    private final float barUpArmPx;

    // Floating GPU indicators (hosted in root FrameLayout, GONE/alpha=0 when idle)
    private final LinearLayout ghostPill;
    private final TextView ghostDirection, ghostTitle, ghostCounter;
    private final GradientDrawable ghostBg;

    private final FrameLayout edgeBadge;
    private final BrowserIcon edgeIcon;
    private final GradientDrawable edgeBg;

    // Auto-hide bottom deck state
    private View bottomDeck;
    private boolean barHidden;
    private int scrollAccum;
    private boolean autoHideEnabled = true;
    private boolean edgeGesturesEnabled = true;

    // Bottom bar gesture state
    private int barMode; // 0=none, 1=horizontal, 2=up
    private float barDownX, barDownY;
    private boolean barArmed;
    private int barPreviewDelta;

    // Edge gesture state (zero-consume observer)
    private int edgeSide; // 0=none, -1=left(back), 1=right(forward)
    private float edgeDownX, edgeDownY;
    private boolean edgeActive, edgeArmed, edgeCancelled;

    // Reusable exclusion rects (zero allocation on updates)
    private final Rect leftExcl = new Rect();
    private final Rect rightExcl = new Rect();
    private final ArrayList<Rect> exclList = new ArrayList<>(2);
    private boolean lastExclBack, lastExclFwd, lastExclEnabled;
    private int lastExclW, lastExclH;

    GestureChrome(Context ctx, FrameLayout overlayHost, Callbacks cb) {
        this.cb = cb;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        this.edgeZonePx = 28f * density;
        this.edgeArmPx = 68f * density;
        this.barSwitchArmPx = 56f * density;
        this.barUpArmPx = 42f * density;

        // Ghost preview pill for bottom-bar tab switch / swipe-up tabs
        ghostBg = AikoTheme.card(AikoTheme.SURFACE, AikoTheme.STROKE, density, AikoTheme.dp(density, 20));
        ghostPill = new LinearLayout(ctx);
        ghostPill.setOrientation(LinearLayout.HORIZONTAL);
        ghostPill.setGravity(Gravity.CENTER_VERTICAL);
        ghostPill.setPadding(AikoTheme.dp(density, 14), AikoTheme.dp(density, 9),
                AikoTheme.dp(density, 14), AikoTheme.dp(density, 9));
        ghostPill.setBackground(ghostBg);
        ghostPill.setElevation(AikoTheme.dp(density, 8));
        ghostPill.setVisibility(View.GONE);
        ghostPill.setAlpha(0f);

        ghostDirection = new TextView(ctx);
        ghostDirection.setTextSize(14);
        ghostDirection.setTypeface(AikoTheme.BOLD);
        ghostDirection.setTextColor(AikoTheme.ACCENT);
        ghostDirection.setPadding(0, 0, AikoTheme.dp(density, 8), 0);
        ghostPill.addView(ghostDirection);

        ghostTitle = new TextView(ctx);
        ghostTitle.setTextSize(13);
        ghostTitle.setTypeface(AikoTheme.MEDIUM);
        ghostTitle.setTextColor(AikoTheme.TEXT);
        ghostTitle.setSingleLine(true);
        ghostTitle.setMaxWidth(AikoTheme.dp(density, 190));
        ghostTitle.setEllipsize(TextUtils.TruncateAt.END);
        ghostPill.addView(ghostTitle);

        ghostCounter = new TextView(ctx);
        ghostCounter.setTextSize(11);
        ghostCounter.setTypeface(AikoTheme.MONO);
        ghostCounter.setTextColor(AikoTheme.TEXT_MUTED);
        ghostCounter.setPadding(AikoTheme.dp(density, 8), 0, 0, 0);
        ghostPill.addView(ghostCounter);

        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        glp.bottomMargin = AikoTheme.dp(density, 74);
        overlayHost.addView(ghostPill, glp);

        // Edge Back/Forward floating pill indicator
        edgeBg = AikoTheme.card(AikoTheme.SURFACE, AikoTheme.STROKE, density, AikoTheme.dp(density, 22));
        edgeBadge = new FrameLayout(ctx);
        edgeBadge.setBackground(edgeBg);
        edgeBadge.setElevation(AikoTheme.dp(density, 8));
        edgeBadge.setVisibility(View.GONE);
        edgeBadge.setAlpha(0f);

        edgeIcon = new BrowserIcon(ctx, BrowserIcon.Kind.BACK);
        edgeIcon.tint(AikoTheme.TEXT_MUTED);
        int iconSz = AikoTheme.dp(density, 22);
        edgeBadge.addView(edgeIcon, new FrameLayout.LayoutParams(iconSz, iconSz, Gravity.CENTER));

        int badgeSz = AikoTheme.dp(density, 44);
        overlayHost.addView(edgeBadge, new FrameLayout.LayoutParams(badgeSz, badgeSz, Gravity.CENTER_VERTICAL | Gravity.START));
    }

    void attachBottomDeck(View deck) {
        this.bottomDeck = deck;
    }

    void setAutoHideEnabled(boolean enabled) {
        if (autoHideEnabled == enabled) return;
        autoHideEnabled = enabled;
        if (!enabled) showBar(true);
    }

    void setEdgeGesturesEnabled(boolean enabled) {
        edgeGesturesEnabled = enabled;
        if (!enabled) cancelEdge();
    }

    boolean isBarHidden() {
        return barHidden;
    }

    /** GPU-only bar reveal/hide — never modifies LayoutParams. */
    void showBar(boolean animate) {
        scrollAccum = 0;
        if (bottomDeck == null) return;
        barHidden = false;
        if (animate) AikoTheme.animateTranslationY(bottomDeck, 0f, 150);
        else {
            bottomDeck.animate().cancel();
            bottomDeck.setTranslationY(0f);
            bottomDeck.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    void hideBar() {
        if (bottomDeck == null || !autoHideEnabled || cb.isEditingAddress() || cb.isHomeVisible()) return;
        int h = bottomDeck.getHeight();
        if (h <= 0) return;
        barHidden = true;
        AikoTheme.animateTranslationY(bottomDeck, h + AikoTheme.dp(density, 8), 160);
    }

    void onPageScroll(int scrollY, int oldScrollY, int maxScrollY) {
        if (!autoHideEnabled || bottomDeck == null || cb.isEditingAddress() || cb.isHomeVisible()) return;
        int dy = scrollY - oldScrollY;
        if (dy == 0) return;
        if (scrollY <= AikoTheme.dp(density, 18)) {
            if (barHidden) showBar(true);
            scrollAccum = 0;
            return;
        }
        // Reset accumulator on direction reversal
        if ((dy > 0 && scrollAccum < 0) || (dy < 0 && scrollAccum > 0)) scrollAccum = 0;
        scrollAccum += dy;

        int hideThreshold = AikoTheme.dp(density, 28);
        int showThreshold = -AikoTheme.dp(density, 20);
        if (!barHidden && scrollAccum > hideThreshold && maxScrollY > AikoTheme.dp(density, 80)) {
            hideBar();
            scrollAccum = 0;
        } else if (barHidden && scrollAccum < showThreshold) {
            showBar(true);
            scrollAccum = 0;
        }
    }

    /**
     * Intercepts bottom-bar horizontal swipes (switch tab) & upward swipes (open tabs)
     * without interfering with normal taps or address editing.
     */
    boolean onBottomBarIntercept(MotionEvent ev) {
        if (cb.isEditingAddress()) {
            resetBarGesture();
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                barDownX = ev.getRawX();
                barDownY = ev.getRawY();
                barMode = 0;
                barArmed = false;
                barPreviewDelta = 0;
                return false;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getRawX() - barDownX;
                float dy = ev.getRawY() - barDownY;
                float adx = Math.abs(dx), ady = Math.abs(dy);
                if (adx > touchSlop * 1.1f && adx > ady * 1.35f && cb.tabCount() > 1) {
                    barMode = 1;
                    return true;
                }
                if (dy < -touchSlop * 1.35f && ady > adx * 1.45f) {
                    barMode = 2;
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                resetBarGesture();
                return false;
            default:
                return false;
        }
    }

    boolean onBottomBarTouch(View host, MotionEvent ev) {
        if (barMode == 0) return false;
        float dx = ev.getRawX() - barDownX;
        float dy = ev.getRawY() - barDownY;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (barMode == 1) {
                    int delta = dx > 0 ? -1 : 1; // Swipe right -> previous tab; swipe left -> next tab
                    int count = cb.tabCount();
                    int cur = cb.currentTabIndex();
                    int target = (cur + delta + count) % count;
                    boolean armed = Math.abs(dx) >= barSwitchArmPx;
                    if (armed != barArmed || delta != barPreviewDelta) {
                        if (armed && (!barArmed || delta != barPreviewDelta)) {
                            host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                        barArmed = armed;
                        barPreviewDelta = delta;
                        updateGhostPill(delta < 0 ? "←" : "→", cb.tabPreviewLabel(target),
                                (target + 1) + "/" + count, armed);
                    }
                    float progress = Math.min(1f, Math.abs(dx) / barSwitchArmPx);
                    float travel = Math.signum(dx) * Math.min(AikoTheme.dp(density, 28), Math.abs(dx) * 0.28f);
                    ghostPill.setTranslationX(travel);
                    ghostPill.setTranslationY(0f);
                    ghostPill.setAlpha(0.35f + 0.65f * progress);
                } else if (barMode == 2) {
                    boolean armed = -dy >= barUpArmPx;
                    if (armed != barArmed || ghostPill.getVisibility() != View.VISIBLE) {
                        if (armed && !barArmed) {
                            host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                        barArmed = armed;
                        updateGhostPill("↑", "标签页", cb.tabCount() + " 个", armed);
                    }
                    float progress = Math.min(1f, Math.max(0f, -dy) / barUpArmPx);
                    ghostPill.setTranslationX(0f);
                    ghostPill.setTranslationY(-Math.min(AikoTheme.dp(density, 24), Math.max(0f, -dy) * 0.35f));
                    ghostPill.setAlpha(0.35f + 0.65f * progress);
                }
                return true;
            case MotionEvent.ACTION_UP: {
                int mode = barMode;
                boolean armed = barArmed;
                int delta = barPreviewDelta;
                hideGhostPill();
                resetBarGesture();
                if (armed) {
                    if (mode == 1 && delta != 0) cb.onSwitchTab(delta);
                    else if (mode == 2) cb.onOpenTabs();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                hideGhostPill();
                resetBarGesture();
                return true;
            default:
                return true;
        }
    }

    private void updateGhostPill(String dir, String label, String counter, boolean armed) {
        if (ghostPill.getVisibility() != View.VISIBLE) {
            ghostPill.animate().cancel();
            ghostPill.setVisibility(View.VISIBLE);
        }
        if (!TextUtils.equals(ghostDirection.getText(), dir)) ghostDirection.setText(dir);
        if (!TextUtils.equals(ghostTitle.getText(), label)) ghostTitle.setText(label);
        if (!TextUtils.equals(ghostCounter.getText(), counter)) ghostCounter.setText(counter);
        ghostBg.setColor(armed ? AikoTheme.HIGHLIGHT : AikoTheme.SURFACE);
        ghostBg.setStroke(Math.max(1, Math.round(density)), armed ? AikoTheme.ACCENT : AikoTheme.STROKE);
        ghostDirection.setTextColor(armed ? AikoTheme.CYAN : AikoTheme.ACCENT);
    }

    private void hideGhostPill() {
        if (ghostPill.getVisibility() != View.VISIBLE) return;
        ghostPill.animate().cancel();
        ghostPill.animate()
                .alpha(0f)
                .translationY(AikoTheme.dp(density, 8))
                .setDuration(110)
                .setInterpolator(AikoTheme.EASE_OUT)
                .withLayer()
                .withEndAction(() -> {
                    ghostPill.setLayerType(View.LAYER_TYPE_NONE, null);
                    ghostPill.setVisibility(View.GONE);
                    ghostPill.setTranslationX(0f);
                    ghostPill.setTranslationY(0f);
                })
                .start();
    }

    private void resetBarGesture() {
        barMode = 0;
        barArmed = false;
        barPreviewDelta = 0;
    }

    /**
     * Zero-consume screen-edge Back/Forward observer.
     * Call from Activity.dispatchTouchEvent; ALWAYS returns false so WebView touches are never stolen.
     */
    void observeEdgeTouch(View host, MotionEvent ev) {
        if (!edgeGesturesEnabled || cb.isEditingAddress() || cb.isHomeVisible()) {
            if (edgeSide != 0) cancelEdge();
            return;
        }
        int width = host.getWidth();
        int height = host.getHeight();
        if (width <= 0 || height <= 0) return;

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                edgeSide = 0;
                edgeActive = false;
                edgeArmed = false;
                edgeCancelled = false;
                float x = ev.getX();
                float y = ev.getY();
                // Ignore bottom bar area & status bar top edge
                if (y < AikoTheme.dp(density, 44) || y > height - AikoTheme.dp(density, 72)) return;
                if (x <= edgeZonePx && cb.canGoBack() && !cb.canWebScrollHorizontally(-1)) {
                    edgeSide = -1;
                    edgeDownX = x;
                    edgeDownY = y;
                } else if (x >= width - edgeZonePx && cb.canGoForward() && !cb.canWebScrollHorizontally(1)) {
                    edgeSide = 1;
                    edgeDownX = x;
                    edgeDownY = y;
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (edgeSide != 0) cancelEdge();
                break;
            case MotionEvent.ACTION_MOVE: {
                if (edgeSide == 0 || edgeCancelled) return;
                float dx = ev.getX() - edgeDownX;
                float dy = ev.getY() - edgeDownY;
                float inward = edgeSide < 0 ? dx : -dx;
                if (!edgeActive) {
                    if (Math.abs(dy) > touchSlop * 1.15f && Math.abs(dy) > Math.abs(inward) * 0.9f) {
                        edgeCancelled = true;
                        return;
                    }
                    if (cb.canWebScrollHorizontally(edgeSide < 0 ? -1 : 1)) {
                        edgeCancelled = true;
                        return;
                    }
                    if (inward > touchSlop * 1.25f && inward > Math.abs(dy) * 1.35f) {
                        edgeActive = true;
                        edgeBadge.animate().cancel();
                        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) edgeBadge.getLayoutParams();
                        lp.gravity = Gravity.CENTER_VERTICAL | (edgeSide < 0 ? Gravity.START : Gravity.END);
                        edgeBadge.setLayoutParams(lp);
                        edgeIcon.symbol(edgeSide < 0 ? BrowserIcon.Kind.BACK : BrowserIcon.Kind.FORWARD);
                        edgeBadge.setVisibility(View.VISIBLE);
                    } else {
                        return;
                    }
                }
                float clamped = Math.max(0f, inward);
                boolean armed = clamped >= edgeArmPx;
                if (armed != edgeArmed) {
                    edgeArmed = armed;
                    if (armed) host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    edgeBg.setColor(armed ? AikoTheme.HIGHLIGHT : AikoTheme.SURFACE);
                    edgeBg.setStroke(Math.max(1, Math.round(density)), armed ? AikoTheme.ACCENT : AikoTheme.STROKE);
                    edgeIcon.tint(armed ? AikoTheme.CYAN : AikoTheme.TEXT_MUTED);
                }
                float progress = Math.min(1f, clamped / edgeArmPx);
                float maxSlide = AikoTheme.dp(density, 26);
                float slide = Math.min(maxSlide, clamped * 0.34f);
                edgeBadge.setTranslationX(edgeSide < 0 ? slide : -slide);
                edgeBadge.setAlpha(0.25f + 0.75f * progress);
                float scale = armed ? 1.06f : (0.86f + 0.14f * progress);
                edgeBadge.setScaleX(scale);
                edgeBadge.setScaleY(scale);
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (edgeActive && edgeArmed && !edgeCancelled) {
                    int side = edgeSide;
                    cancelEdge();
                    if (side < 0) cb.onEdgeBack();
                    else if (side > 0) cb.onEdgeForward();
                } else if (edgeSide != 0) {
                    cancelEdge();
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                if (edgeSide != 0) cancelEdge();
                break;
            default:
                break;
        }
    }

    private void cancelEdge() {
        edgeSide = 0;
        edgeActive = false;
        edgeArmed = false;
        edgeCancelled = false;
        if (edgeBadge.getVisibility() == View.VISIBLE) {
            edgeBadge.animate().cancel();
            edgeBadge.animate()
                    .alpha(0f)
                    .translationX(0f)
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(110)
                    .setInterpolator(AikoTheme.EASE_OUT)
                    .withLayer()
                    .withEndAction(() -> {
                        edgeBadge.setLayerType(View.LAYER_TYPE_NONE, null);
                        edgeBadge.setVisibility(View.GONE);
                    })
                    .start();
        }
    }

    /**
     * Updates API 29+ system gesture exclusion rects ONLY for edges that currently
     * have Back/Forward history, staying within Android's 200dp per-edge cap.
     */
    void updateExclusionRects(View target, boolean canBack, boolean canForward) {
        if (Build.VERSION.SDK_INT < 29 || target == null) return;
        int w = target.getWidth(), h = target.getHeight();
        if (w <= 0 || h <= 0) return;
        boolean back = edgeGesturesEnabled && !cb.isHomeVisible() && canBack;
        boolean fwd = edgeGesturesEnabled && !cb.isHomeVisible() && canForward;
        if (back == lastExclBack && fwd == lastExclFwd && edgeGesturesEnabled == lastExclEnabled
                && w == lastExclW && h == lastExclH) {
            return;
        }
        lastExclBack = back;
        lastExclFwd = fwd;
        lastExclEnabled = edgeGesturesEnabled;
        lastExclW = w;
        lastExclH = h;

        if (!back && !fwd) {
            target.setSystemGestureExclusionRects(Collections.emptyList());
            return;
        }
        int stripW = AikoTheme.dp(density, 28);
        int stripH = Math.min(AikoTheme.dp(density, 196), Math.max(AikoTheme.dp(density, 120), h / 3));
        int top = Math.max(AikoTheme.dp(density, 56), (h - stripH) / 2);
        int bottom = Math.min(h - AikoTheme.dp(density, 64), top + stripH);
        exclList.clear();
        if (back) {
            leftExcl.set(0, top, stripW, bottom);
            exclList.add(leftExcl);
        }
        if (fwd) {
            rightExcl.set(w - stripW, top, w, bottom);
            exclList.add(rightExcl);
        }
        List<Rect> copy = exclList.isEmpty() ? Collections.emptyList() : new ArrayList<>(exclList);
        target.setSystemGestureExclusionRects(copy);
    }
}

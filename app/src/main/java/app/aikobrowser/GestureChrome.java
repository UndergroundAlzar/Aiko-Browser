package app.aikobrowser;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
import android.view.animation.PathInterpolator;
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
 * 4. Discrete-transition GPU auto-hide bottom bar + pure-Java viewport layout calculator
 *    (zero per-scroll-pixel LayoutParams mutations, zero bottom-bar overlap when docked).
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
        void onBarLayoutChangeNeeded();
    }

    /**
     * Pure-Java immutable layout snapshot for bottomDeck, viewport, and omniboxOverlay.
     * Contains zero Android runtime calls so host JVM unit tests can verify every state directly.
     */
    static final class ChromeLayout {
        final boolean deckVisible;
        final int deckPaddingBottom;
        final int deckTranslationY;
        final int viewportBottomMargin;
        final int omniboxBottomMargin;

        ChromeLayout(boolean deckVisible, int deckPaddingBottom, int deckTranslationY,
                     int viewportBottomMargin, int omniboxBottomMargin) {
            this.deckVisible = deckVisible;
            this.deckPaddingBottom = deckPaddingBottom;
            this.deckTranslationY = deckTranslationY;
            this.viewportBottomMargin = viewportBottomMargin;
            this.omniboxBottomMargin = omniboxBottomMargin;
        }
    }

    /**
     * Pure-Java chrome/viewport layout state calculator covering all 8 states:
     * 1. Fullscreen video -> deck hidden, viewport margin = 0 (true edge-to-edge).
     * 2. Editing omnibox -> deck floats above IME/nav, viewport stays steady at docked height
     *    (zero WebView reflow while typing URL), omniboxOverlay sits omniboxGapPx above deck.
     * 3. Find-in-page open -> deck floats above IME/nav, viewport sits strictly above deck.
     * 4. WebView IME open (!onHome && (imeOpen || legacyWebImeOpen)) -> deck hidden,
     *    viewport sized directly above IME (maximum room for web chat/forms, zero obstruction).
     * 5. Home start page -> deck visible with nav padding, viewport reserves exact visible deck height.
     * 6. Normal webpage auto-hidden -> deck slid offscreen by +(deckH + nav), viewport margin = nav.
     * 7. Normal webpage revealing (barSettlingShow) -> viewport stays at nav during 160ms GPU slide-in,
     *    then snaps once to deckH + nav when settled (zero mid-fling relayout).
     * 8. Normal webpage docked -> viewport ends strictly at top edge of bottomDeck (ZERO overlap).
     */
    static ChromeLayout computeChromeLayout(
            boolean fullscreen,
            boolean onHome,
            boolean editingOmnibox,
            boolean findOpen,
            boolean barHidden,
            boolean barSettlingShow,
            boolean legacyWebImeOpen,
            int insetBottom,
            int imeBottom,
            int deckContentHeight,
            int omniboxGapPx) {
        int nav = Math.max(0, insetBottom);
        int ime = Math.max(0, imeBottom);
        int deckH = Math.max(0, deckContentHeight);
        int gap = Math.max(0, omniboxGapPx);
        int dockedTotal = deckH + nav;
        boolean imeOpen = ime > nav;

        if (fullscreen) {
            return new ChromeLayout(false, 0, 0, 0, 0);
        }
        if (editingOmnibox) {
            int deckPad = imeOpen ? 0 : nav;
            int deckTransY = imeOpen ? -ime : 0;
            int baseBottom = imeOpen ? ime : nav;
            return new ChromeLayout(true, deckPad, deckTransY, dockedTotal, baseBottom + deckH + gap);
        }
        if (findOpen) {
            int deckPad = imeOpen ? 0 : nav;
            int deckTransY = imeOpen ? -ime : 0;
            int baseBottom = imeOpen ? ime : nav;
            return new ChromeLayout(true, deckPad, deckTransY, baseBottom + deckH, 0);
        }
        if (!onHome && (imeOpen || legacyWebImeOpen)) {
            return new ChromeLayout(false, 0, 0, imeOpen ? ime : 0, 0);
        }
        if (onHome) {
            int vpMargin = Math.max(dockedTotal, imeOpen ? ime : 0);
            return new ChromeLayout(true, nav, 0, vpMargin, 0);
        }
        if (barHidden) {
            return new ChromeLayout(true, nav, dockedTotal, nav, 0);
        }
        if (barSettlingShow) {
            return new ChromeLayout(true, nav, 0, nav, 0);
        }
        return new ChromeLayout(true, nav, 0, dockedTotal, 0);
    }

    static boolean canAutoHideForScroll(int maxScrollY, int deckContentHeight, int minExtraScrollPx) {
        return maxScrollY > Math.max(0, deckContentHeight) + Math.max(0, minExtraScrollPx);
    }

    /** Zero-alloc tactile press-scale GPU feedback, preserving optional finger-down hook (e.g. preconnect). */
    static void attachPressScale(View target, float pressedScale, Runnable onActionDown) {
        final PathInterpolator curve = new PathInterpolator(0.2f, 0f, 0f, 1f);
        final PathInterpolator settle = new PathInterpolator(0.18f, 0.92f, 0.30f, 1.04f);
        final AnimatorListenerAdapter clearLayer = new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                target.setLayerType(View.LAYER_TYPE_NONE, null);
                target.animate().setListener(null);
            }
            @Override public void onAnimationCancel(Animator animation) {
                target.setLayerType(View.LAYER_TYPE_NONE, null);
                target.animate().setListener(null);
            }
        };
        target.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (onActionDown != null) onActionDown.run();
                if (!v.isEnabled()) return false;
                v.animate().setListener(null).cancel();
                v.setLayerType(View.LAYER_TYPE_NONE, null);
                v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(75)
                        .setInterpolator(curve)
                        .withLayer()
                        .setListener(clearLayer)
                        .start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().setListener(null).cancel();
                v.setLayerType(View.LAYER_TYPE_NONE, null);
                if (v.getScaleX() != 1f || v.getScaleY() != 1f) {
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150)
                            .setInterpolator(settle)
                            .withLayer()
                            .setListener(clearLayer)
                            .start();
                }
            }
            return false;
        });
    }

    private final Callbacks cb;
    private final float density;
    private final int touchSlop;
    private final float edgeZonePx;
    private final float edgeArmPx;
    private final float barSwitchArmPx;
    private final float barUpArmPx;
    private final PathInterpolator emphasizedDecel;

    // Floating GPU indicators (hosted in root FrameLayout, GONE/alpha=0 when idle)
    private final LinearLayout ghostPill;
    private final TextView ghostDirection, ghostTitle, ghostCounter;
    private final GradientDrawable ghostBg;

    private final FrameLayout edgeBadge;
    private final BrowserIcon edgeIcon;
    private final GradientDrawable edgeBg;

    // Auto-hide bottom deck state (discrete state transitions only)
    private View bottomDeck;
    private boolean barHidden;
    private boolean barSettlingShow;
    private boolean barAnimating;
    private int barAnimGen;
    private int scrollAccum;
    private boolean autoHideEnabled = true;
    private boolean edgeGesturesEnabled = true;
    private int topSafePx;
    private int bottomSafePx;

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
        this.emphasizedDecel = new PathInterpolator(0.2f, 0f, 0f, 1f);
        this.topSafePx = AikoTheme.dp(density, 44);
        this.bottomSafePx = AikoTheme.dp(density, 72);

        // Ghost preview pill for bottom-bar tab switch / swipe-up tabs
        ghostBg = AikoTheme.card(AikoTheme.SURFACE, AikoTheme.CAPSULE_STROKE, density, AikoTheme.dp(density, 20));
        ghostPill = new LinearLayout(ctx);
        ghostPill.setOrientation(LinearLayout.HORIZONTAL);
        ghostPill.setGravity(Gravity.CENTER_VERTICAL);
        ghostPill.setPadding(AikoTheme.dp(density, 14), AikoTheme.dp(density, 9),
                AikoTheme.dp(density, 14), AikoTheme.dp(density, 9));
        ghostPill.setBackground(ghostBg);
        ghostPill.setElevation(AikoTheme.dp(density, 10));
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
        ghostTitle.setMaxWidth(AikoTheme.dp(density, 196));
        ghostTitle.setEllipsize(TextUtils.TruncateAt.END);
        ghostPill.addView(ghostTitle);

        ghostCounter = new TextView(ctx);
        ghostCounter.setTextSize(11);
        ghostCounter.setTypeface(AikoTheme.MONO);
        ghostCounter.setTextColor(AikoTheme.TEXT_MUTED);
        ghostCounter.setPadding(AikoTheme.dp(density, 8), 0, 0, 0);
        ghostPill.addView(ghostCounter);

        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        glp.bottomMargin = AikoTheme.dp(density, 72);
        overlayHost.addView(ghostPill, glp);

        // Edge Back/Forward floating pill indicator
        edgeBg = AikoTheme.card(AikoTheme.SURFACE, AikoTheme.CAPSULE_STROKE, density, AikoTheme.dp(density, 22));
        edgeBadge = new FrameLayout(ctx);
        edgeBadge.setBackground(edgeBg);
        edgeBadge.setElevation(AikoTheme.dp(density, 10));
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

    void setSafeInsets(int topInsetPx, int deckTotalHeightPx) {
        topSafePx = Math.max(AikoTheme.dp(density, 44), topInsetPx);
        bottomSafePx = Math.max(AikoTheme.dp(density, 64), deckTotalHeightPx);
        int desiredGhostMargin = Math.max(AikoTheme.dp(density, 68), deckTotalHeightPx + AikoTheme.dp(density, 12));
        FrameLayout.LayoutParams glp = (FrameLayout.LayoutParams) ghostPill.getLayoutParams();
        if (glp != null && glp.bottomMargin != desiredGhostMargin) {
            glp.bottomMargin = desiredGhostMargin;
            ghostPill.setLayoutParams(glp);
        }
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

    boolean isBarSettlingShow() {
        return barSettlingShow;
    }

    boolean isBarAnimating() {
        return barAnimating;
    }

    /** Cancels any active slide animation & clears GPU layer safely before state overrides. */
    void cancelBarAnimation() {
        barAnimGen++;
        barAnimating = false;
        barSettlingShow = false;
        if (bottomDeck != null) {
            bottomDeck.animate().setListener(null).cancel();
            bottomDeck.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    /**
     * Discrete-transition bar reveal:
     * - animate=true: GPU-slides bottomDeck from +deckHeight -> 0 while keeping viewport steady
     *   during the 160ms fling, then notifies onBarLayoutChangeNeeded() ONCE when settled.
     * - animate=false: snaps immediately and notifies layout immediately if visibility changed.
     */
    void showBar(boolean animate) {
        scrollAccum = 0;
        if (bottomDeck == null) {
            barHidden = false;
            barSettlingShow = false;
            barAnimating = false;
            return;
        }
        boolean wasHidden = barHidden;
        boolean wasSettling = barSettlingShow;
        barHidden = false;

        float curY = bottomDeck.getTranslationY();
        if (!animate || curY <= 0.5f) {
            barAnimGen++;
            barAnimating = false;
            barSettlingShow = false;
            bottomDeck.animate().setListener(null).cancel();
            bottomDeck.setLayerType(View.LAYER_TYPE_NONE, null);
            if (curY > 0f) bottomDeck.setTranslationY(0f);
            if (wasHidden || wasSettling) cb.onBarLayoutChangeNeeded();
            return;
        }

        final int gen = ++barAnimGen;
        barSettlingShow = true;
        barAnimating = true;
        bottomDeck.animate().setListener(null).cancel();
        bottomDeck.setLayerType(View.LAYER_TYPE_NONE, null);
        bottomDeck.animate()
                .translationY(0f)
                .setDuration(160)
                .setInterpolator(emphasizedDecel)
                .withLayer()
                .setListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;
                    @Override public void onAnimationCancel(Animator animation) {
                        cancelled = true;
                        bottomDeck.setLayerType(View.LAYER_TYPE_NONE, null);
                        bottomDeck.animate().setListener(null);
                    }
                    @Override public void onAnimationEnd(Animator animation) {
                        bottomDeck.setLayerType(View.LAYER_TYPE_NONE, null);
                        bottomDeck.animate().setListener(null);
                        if (!cancelled && gen == barAnimGen) {
                            barAnimating = false;
                            barSettlingShow = false;
                            cb.onBarLayoutChangeNeeded();
                        }
                    }
                })
                .start();
    }

    /**
     * Discrete-transition bar hide:
     * Expands viewport ONCE immediately (zero flash because bottomDeck still covers the bottom strip)
     * and GPU-slides bottomDeck smoothly offscreen (0 -> +deckHeight).
     */
    void hideBar() {
        if (bottomDeck == null || !autoHideEnabled || cb.isEditingAddress() || cb.isHomeVisible()) return;
        if (bottomDeck.getVisibility() != View.VISIBLE) return;
        int h = bottomDeck.getHeight();
        if (h <= 0) return;
        if (barHidden && !barAnimating) return;

        final int gen = ++barAnimGen;
        barHidden = true;
        barSettlingShow = false;
        barAnimating = true;
        cb.onBarLayoutChangeNeeded();

        // Abort if an overriding state (Find-in-Page, IME, Fullscreen, Home) cancelled auto-hide during layout
        if (!barAnimating || !barHidden || gen != barAnimGen || bottomDeck.getVisibility() != View.VISIBLE) {
            return;
        }

        bottomDeck.animate().setListener(null).cancel();
        bottomDeck.setLayerType(View.LAYER_TYPE_NONE, null);
        bottomDeck.animate()
                .translationY(h)
                .setDuration(170)
                .setInterpolator(emphasizedDecel)
                .withLayer()
                .setListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;
                    @Override public void onAnimationCancel(Animator animation) {
                        cancelled = true;
                        bottomDeck.setLayerType(View.LAYER_TYPE_NONE, null);
                        bottomDeck.animate().setListener(null);
                    }
                    @Override public void onAnimationEnd(Animator animation) {
                        bottomDeck.setLayerType(View.LAYER_TYPE_NONE, null);
                        bottomDeck.animate().setListener(null);
                        if (!cancelled && gen == barAnimGen) {
                            barAnimating = false;
                        }
                    }
                })
                .start();
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
        int deckH = Math.max(0, bottomDeck.getHeight() - bottomDeck.getPaddingBottom());
        if (!barHidden && scrollAccum > hideThreshold
                && canAutoHideForScroll(maxScrollY, deckH, AikoTheme.dp(density, 96))) {
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
                    float scale = armed ? 1.03f : (0.94f + 0.06f * progress);
                    ghostPill.setTranslationX(travel);
                    ghostPill.setTranslationY(0f);
                    ghostPill.setScaleX(scale);
                    ghostPill.setScaleY(scale);
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
                    float scale = armed ? 1.03f : (0.94f + 0.06f * progress);
                    ghostPill.setTranslationX(0f);
                    ghostPill.setTranslationY(-Math.min(AikoTheme.dp(density, 24), Math.max(0f, -dy) * 0.35f));
                    ghostPill.setScaleX(scale);
                    ghostPill.setScaleY(scale);
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
            ghostPill.animate().setListener(null).cancel();
            ghostPill.setLayerType(View.LAYER_TYPE_NONE, null);
            ghostPill.setVisibility(View.VISIBLE);
        }
        if (!TextUtils.equals(ghostDirection.getText(), dir)) ghostDirection.setText(dir);
        if (!TextUtils.equals(ghostTitle.getText(), label)) ghostTitle.setText(label);
        if (!TextUtils.equals(ghostCounter.getText(), counter)) ghostCounter.setText(counter);
        ghostBg.setColor(armed ? AikoTheme.HIGHLIGHT : AikoTheme.SURFACE);
        ghostBg.setStroke(Math.max(1, Math.round(density)), armed ? AikoTheme.ACCENT : AikoTheme.CAPSULE_STROKE);
        ghostDirection.setTextColor(armed ? AikoTheme.CYAN : AikoTheme.ACCENT);
    }

    private void hideGhostPill() {
        if (ghostPill.getVisibility() != View.VISIBLE) return;
        ghostPill.animate().setListener(null).cancel();
        ghostPill.animate()
                .alpha(0f)
                .translationY(AikoTheme.dp(density, 8))
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(120)
                .setInterpolator(emphasizedDecel)
                .withLayer()
                .setListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;
                    @Override public void onAnimationCancel(Animator animation) {
                        cancelled = true;
                        ghostPill.setLayerType(View.LAYER_TYPE_NONE, null);
                        ghostPill.animate().setListener(null);
                    }
                    @Override public void onAnimationEnd(Animator animation) {
                        ghostPill.setLayerType(View.LAYER_TYPE_NONE, null);
                        ghostPill.animate().setListener(null);
                        if (!cancelled) {
                            ghostPill.setVisibility(View.GONE);
                            ghostPill.setTranslationX(0f);
                            ghostPill.setTranslationY(0f);
                            ghostPill.setScaleX(1f);
                            ghostPill.setScaleY(1f);
                        }
                    }
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
     * Call from Activity.dispatchTouchEvent; never steals WebView touches.
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
                if (y < topSafePx || y > height - bottomSafePx) return;
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
                        edgeBadge.animate().setListener(null).cancel();
                        edgeBadge.setLayerType(View.LAYER_TYPE_NONE, null);
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
                    edgeBg.setStroke(Math.max(1, Math.round(density)), armed ? AikoTheme.ACCENT : AikoTheme.CAPSULE_STROKE);
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
            edgeBadge.animate().setListener(null).cancel();
            edgeBadge.animate()
                    .alpha(0f)
                    .translationX(0f)
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(120)
                    .setInterpolator(emphasizedDecel)
                    .withLayer()
                    .setListener(new AnimatorListenerAdapter() {
                        private boolean cancelled;
                        @Override public void onAnimationCancel(Animator animation) {
                            cancelled = true;
                            edgeBadge.setLayerType(View.LAYER_TYPE_NONE, null);
                            edgeBadge.animate().setListener(null);
                        }
                        @Override public void onAnimationEnd(Animator animation) {
                            edgeBadge.setLayerType(View.LAYER_TYPE_NONE, null);
                            edgeBadge.animate().setListener(null);
                            if (!cancelled) edgeBadge.setVisibility(View.GONE);
                        }
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

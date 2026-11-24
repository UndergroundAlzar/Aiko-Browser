package app.aikobrowser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-site settings applied to a top-level site's WebView.
 * Encoded compactly into a backwards-compatible 32-bit integer bitmask:
 * - bit 0: desktop mode
 * - bit 1: block images
 * - bits 2..9: text zoom percentage (50..200)
 * - bit 10: UNLOCK_COPY (defeat copy/selection blocks)
 * - bit 11: BABEL_AUTO (auto-translate site)
 * - bit 12: BABEL_NEVER (never auto-translate site)
 * - bit 13: FORCE_ZOOM (override viewport user-scalable=no)
 */
public final class SiteProfile {
    static final int DEFAULT_ZOOM = 100;
    private static final int ZOOM_SHIFT = 2;
    private static final int ZOOM_MASK = 0xFF;

    public static final int UNLOCK_COPY = 1 << 10;
    public static final int BABEL_AUTO = 1 << 11;
    public static final int BABEL_NEVER = 1 << 12;
    public static final int FORCE_ZOOM = 1 << 13;
    private static final int EXTRA_FLAGS_MASK = UNLOCK_COPY | BABEL_AUTO | BABEL_NEVER | FORCE_ZOOM;

    static final SiteProfile DEFAULT = new SiteProfile(false, false, DEFAULT_ZOOM, 0);
    private static final Pattern CHROME = Pattern.compile("\\bChrome/([0-9]{1,5})(?:\\.|$)");

    final boolean desktop, blockImages;
    final int zoom;
    public final int flags;
    public final boolean unlockCopy, babelAuto, babelNever, forceZoom;

    SiteProfile(boolean desktop, boolean blockImages, int zoom) {
        this(desktop, blockImages, zoom, 0);
    }

    public SiteProfile(boolean desktop, boolean blockImages, int zoom, int flags) {
        this.desktop = desktop;
        this.blockImages = blockImages;
        this.zoom = zoom >= 50 && zoom <= 200 ? zoom : DEFAULT_ZOOM;
        int cleanFlags = flags & EXTRA_FLAGS_MASK;
        if ((cleanFlags & BABEL_AUTO) != 0 && (cleanFlags & BABEL_NEVER) != 0) {
            cleanFlags &= ~BABEL_AUTO;
        }
        this.flags = cleanFlags;
        this.unlockCopy = (cleanFlags & UNLOCK_COPY) != 0;
        this.babelAuto = (cleanFlags & BABEL_AUTO) != 0;
        this.babelNever = (cleanFlags & BABEL_NEVER) != 0;
        this.forceZoom = (cleanFlags & FORCE_ZOOM) != 0;
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public SiteProfile withFlag(int flag, boolean enabled) {
        int masked = flag & EXTRA_FLAGS_MASK;
        int next = enabled ? (flags | masked) : (flags & ~masked);
        if (enabled && (masked & BABEL_AUTO) != 0) next &= ~BABEL_NEVER;
        if (enabled && (masked & BABEL_NEVER) != 0) next &= ~BABEL_AUTO;
        return new SiteProfile(desktop, blockImages, zoom, next);
    }

    public SiteProfile withDesktop(boolean nextDesktop) {
        return new SiteProfile(nextDesktop, blockImages, zoom, flags);
    }

    public SiteProfile withBlockImages(boolean nextBlockImages) {
        return new SiteProfile(desktop, nextBlockImages, zoom, flags);
    }

    public SiteProfile withZoom(int nextZoom) {
        return new SiteProfile(desktop, blockImages, nextZoom, flags);
    }

    int encode() {
        if (!desktop && !blockImages && zoom == DEFAULT_ZOOM && flags == 0) return 0;
        return ((zoom & ZOOM_MASK) << ZOOM_SHIFT) | (desktop ? 1 : 0) | (blockImages ? 2 : 0) | flags;
    }

    static SiteProfile decode(int stored) {
        if (stored == 0) return DEFAULT;
        int text = (stored >>> ZOOM_SHIFT) & ZOOM_MASK;
        if (text < 50 || text > 200) return DEFAULT;
        return new SiteProfile((stored & 1) != 0, (stored & 2) != 0, text, stored & EXTRA_FLAGS_MASK);
    }

    static String desktopAgent(String mobileAgent) {
        if (mobileAgent == null) return null;
        Matcher match = CHROME.matcher(mobileAgent);
        if (!match.find()) return null;
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + match.group(1) + ".0.0.0 Safari/537.36";
    }
}

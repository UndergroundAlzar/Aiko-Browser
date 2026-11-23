package app.aikobrowser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The settings applied to a top-level site's WebView. */
final class SiteProfile {
    static final int DEFAULT_ZOOM = 100;
    static final SiteProfile DEFAULT = new SiteProfile(false, false, DEFAULT_ZOOM);
    private static final Pattern CHROME = Pattern.compile("\\bChrome/([0-9]{1,5})(?:\\.|$)");

    final boolean desktop, blockImages;
    final int zoom;

    SiteProfile(boolean desktop, boolean blockImages, int zoom) {
        this.desktop = desktop;
        this.blockImages = blockImages;
        this.zoom = zoom >= 50 && zoom <= 200 ? zoom : DEFAULT_ZOOM;
    }

    int encode() {
        if (!desktop && !blockImages && zoom == DEFAULT_ZOOM) return 0;
        return (zoom << 2) | (desktop ? 1 : 0) | (blockImages ? 2 : 0);
    }

    static SiteProfile decode(int stored) {
        int text = stored >>> 2;
        if (text < 50 || text > 200) return DEFAULT;
        return new SiteProfile((stored & 1) != 0, (stored & 2) != 0, text);
    }

    static String desktopAgent(String mobileAgent) {
        if (mobileAgent == null) return null;
        Matcher match = CHROME.matcher(mobileAgent);
        if (!match.find()) return null;
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + match.group(1) + ".0.0.0 Safari/537.36";
    }
}

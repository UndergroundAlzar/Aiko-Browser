package app.aikobrowser;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java URL tracking parameter cleaner (zero android.* imports).
 * Strips unambiguous global and CN tracking query parameters while preserving
 * all functional parameters, raw percent-encoding, and URL fragments verbatim.
 */
public final class UrlCleaner {
    private static final Set<String> TRACKING_PARAMS = new HashSet<>(Arrays.asList(
            // Global ad / analytics / newsletter click IDs
            "fbclid", "fb_action_ids", "fb_action_types", "fb_source", "fb_ref",
            "gclid", "gclsrc", "gbraid", "wbraid", "dclid",
            "msclkid", "yclid", "ymclid", "twclid", "ttclid", "li_fat_id",
            "mc_cid", "mc_eid",
            "_hsenc", "_hsmi", "hsctatracking",
            "igshid", "igsh",
            "si",
            "ref_src", "ref_url",
            "s_kwcid", "ef_id", "vero_id", "vero_conv",
            "oly_enc_id", "oly_anon_id", "_openstat",
            "wickedid", "mkt_tok", "rb_clickid", "epik", "scid",
            "_ga", "_gl", "_bhlid",
            "guccounter", "guce_referrer", "guce_referrer_sig",
            // Chinese ecosystem unambiguous share / telemetry tracking params
            "spm", "spm_id_from", "from_spmid",
            "vd_source", "bbid", "b_lsid",
            "share_source", "share_medium", "share_plat",
            "share_session_id", "share_tag", "share_times", "share_from",
            "ali_trackid", "bd_vid"
    ));

    private UrlCleaner() {}

    /**
     * Removes well-known tracking query parameters from an HTTP(S) URL.
     * Non-HTTP(S) URLs, malformed inputs, and URLs without tracking parameters
     * are returned untouched (preserving exact reference when unchanged).
     */
    public static String clean(String url) {
        if (url == null || url.isEmpty() || !HttpUrlUtil.isHttp(url)) return url;

        int hash = url.indexOf('#');
        int end = hash >= 0 ? hash : url.length();
        int q = url.indexOf('?');
        if (q < 0 || q >= end) return url;

        int queryStart = q + 1;
        if (queryStart >= end) return url;

        StringBuilder kept = null;
        boolean removedAny = false;
        int segStart = queryStart;
        int keptCount = 0;

        while (segStart <= end) {
            int amp = url.indexOf('&', segStart);
            int segEnd = (amp < 0 || amp > end) ? end : amp;

            boolean drop = false;
            if (segEnd > segStart) {
                int eq = url.indexOf('=', segStart);
                int keyEnd = (eq < 0 || eq > segEnd) ? segEnd : eq;
                if (keyEnd > segStart && isTrackingKey(url, segStart, keyEnd)) {
                    drop = true;
                }
            }

            if (drop) {
                if (!removedAny) {
                    removedAny = true;
                    kept = new StringBuilder(url.length());
                    kept.append(url, 0, q);
                    // Replay previously accepted segments prior to segStart.
                    int prevStart = queryStart;
                    while (prevStart < segStart) {
                        int prevAmp = url.indexOf('&', prevStart);
                        int prevEnd = (prevAmp < 0 || prevAmp > segStart - 1) ? (segStart - 1) : prevAmp;
                        if (prevEnd > prevStart) {
                            kept.append(keptCount == 0 ? '?' : '&');
                            kept.append(url, prevStart, prevEnd);
                            keptCount++;
                        }
                        if (prevAmp < 0 || prevAmp >= segStart - 1) break;
                        prevStart = prevAmp + 1;
                    }
                }
            } else if (removedAny && segEnd > segStart) {
                kept.append(keptCount == 0 ? '?' : '&');
                kept.append(url, segStart, segEnd);
                keptCount++;
            }

            if (amp < 0 || amp >= end) break;
            segStart = amp + 1;
        }

        if (!removedAny) return url;
        if (hash >= 0) kept.append(url, hash, url.length());
        return kept.toString();
    }

    /** Returns true if cleaning changed the URL. */
    public static boolean wasCleaned(String raw, String cleaned) {
        return raw != null && cleaned != null && !raw.equals(cleaned);
    }

    private static boolean isTrackingKey(String url, int start, int end) {
        int len = end - start;
        if (len < 2 || len > 48) return false;
        String key = url.substring(start, end).toLowerCase(Locale.ROOT);
        if (key.startsWith("utm_") || key.startsWith("hsa_")) return true;
        return TRACKING_PARAMS.contains(key);
    }
}

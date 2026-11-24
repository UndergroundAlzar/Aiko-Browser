package app.aikobrowser;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Host JVM verification suite for Aiko Browser v0.4.0 Core Platform Engines:
 * - QrEngine (ISO 18004 finder/timing/alignment/format/version bits, RS GF(256) syndromes,
 *   full unmask + de-interleave + UTF-8 Byte-mode round-trip decode across lengths 10..360!)
 * - BangCatalog (20 built-in !bangs, custom bangs CRUD, /command shortcuts)
 * - BackupManager (.aiko ZIP export/import round-trip, WebView skip, path-traversal/zip-bomb rejection)
 * - StartPageConfig (presets, URL resolution, app-jump guard, AMOLED night overlay ARGB, DoH)
 * - SiteProfile (v0.3 legacy compat + v0.4 UNLOCK_COPY/BABEL_AUTO/BABEL_NEVER/FORCE_ZOOM bitflags)
 * - SiteExtras & SiteFilter pure helpers (auto-refresh clamp, host rule listing & exact line undo)
 * - WebViewPerf.maxActiveTabsForProfile & VideoBooster fullscreen/background scripts
 */
public final class V040EnginesCheck {
    // Mirrors QrEngine ISO 18004 Table 9 EC-M table for full independent round-trip decoding verification
    private static final int[][] EC_M_TABLE = {
            null,
            {  26, 10, 1, 16, 0,  0 },
            {  44, 16, 1, 28, 0,  0 },
            {  70, 26, 1, 44, 0,  0 },
            { 100, 18, 2, 32, 0,  0 },
            { 134, 24, 2, 43, 0,  0 },
            { 172, 16, 4, 27, 0,  0 },
            { 196, 18, 4, 31, 0,  0 },
            { 242, 22, 2, 38, 2, 39 },
            { 292, 22, 3, 36, 2, 37 },
            { 346, 26, 4, 43, 1, 44 },
            { 404, 30, 1, 50, 4, 51 },
            { 466, 22, 6, 36, 2, 37 },
            { 532, 22, 8, 37, 1, 38 },
            { 581, 24, 4, 40, 5, 41 },
            { 655, 24, 5, 41, 5, 42 }
    };

    private static final int[][] ALIGN_POS = {
            null, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50},
            {6, 30, 54}, {6, 32, 58}, {6, 34, 62}, {6, 26, 46, 66}, {6, 26, 48, 70}
    };

    public static void main(String[] args) throws Exception {
        checkQrEngine();
        checkBangCatalog();
        checkBackupManager();
        checkStartPageConfig();
        checkSiteProfile();
        checkSiteExtrasAndFilterHelpers();
        checkWebViewPerfAndVideoBooster();
        System.out.println("ALL V040EnginesCheck PASS");
    }

    private static void checkQrEngine() {
        // 1. Verify Reed-Solomon syndromes over GF(256) for every EC length used in V1..V15
        int[] ecDegrees = {10, 16, 18, 22, 24, 26, 30};
        byte[] sampleData = "Aiko Browser v0.4.0 QR Reed-Solomon Test Vector!".getBytes(StandardCharsets.UTF_8);
        for (int deg : ecDegrees) {
            byte[] gen = QrEngine.reedSolomonGenerator(deg);
            byte[] ec = QrEngine.reedSolomonRemainder(sampleData, gen);
            byte[] codeword = new byte[sampleData.length + ec.length];
            System.arraycopy(sampleData, 0, codeword, 0, sampleData.length);
            System.arraycopy(ec, 0, codeword, sampleData.length, ec.length);
            verifyReedSolomonSyndrome(codeword, deg, "RS deg=" + deg);
        }

        // 2. Verify ISO 18004 known BCH format & version constants
        check(QrEngine.computeFormatBits(0) == 0x5412, "Format M mask 0 == 0x5412");
        check(QrEngine.computeFormatBits(1) == 0x5125, "Format M mask 1 == 0x5125");
        check(QrEngine.computeVersionBits(7) == 0x07C94, "Version 7 BCH == 0x07C94");

        // 3. Full matrix + structural + format/version + unmask + RS-syndrome + UTF-8 decode round-trip!
        String[] payloads = {
                "https://a.co", // 12 B -> V1
                "https://go.itab.link/", // 21 B -> V2
                "https://github.com/UndergroundAlzar/Aiko", // 40 B -> V3
                "https://linux.do/t/topic/123456?utm_test=ok&page=2&lang=zh-CN", // 61 B -> V4
                "https://zh.wikipedia.org/wiki/巴别塔?a=1&b=2&title=沉浸式双语翻译测试", // UTF-8 multi-byte -> V5
                repeatUrl(100), // V6
                repeatUrl(120), // V7 (first version with version info + 6 alignments!)
                repeatUrl(150), // V8 (2 groups!)
                repeatUrl(180), // V9
                repeatUrl(210), // V10 (16-bit byte count indicator!)
                repeatUrl(320)  // V13
        };
        for (String text : payloads) {
            boolean[][] m = QrEngine.encodeMatrix(text);
            int n = m.length;
            check((n - 17) % 4 == 0 && n >= 21 && n <= 77, "Valid QR dimension: " + n);
            int version = (n - 17) / 4;
            check(m[0].length == n, "Square matrix");

            verifyFinderPatterns(m);
            verifyTimingPatterns(m);
            verifyAlignmentPatterns(m, version);
            check(m[n - 8][8], "Dark module at (n-8, 8) for v" + version);

            int mask = verifyAndExtractFormatMask(m, version);
            if (version >= 7) verifyVersionInfo(m, version);

            String decoded = unmaskAndDecodeUtf8(m, version, mask);
            check(text.equals(decoded), "Full ISO 18004 round-trip decode mismatch for v" + version
                    + ": expected len=" + text.length() + " got len=" + decoded.length());
        }

        // 4. Reject > MAX_UTF8_BYTES cleanly
        boolean threw = false;
        try {
            QrEngine.encodeMatrix(repeatUrl(QrEngine.MAX_UTF8_BYTES + 20));
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "QrEngine rejects payload exceeding MAX_UTF8_BYTES");

        // 5. Verify styled QR finder ring contrast across ALL AikoTheme presets (including bright OLED #E2E8F0!)
        String[] themeModes = {
                AikoTheme.MODE_OBSIDIAN, AikoTheme.MODE_EMERALD, AikoTheme.MODE_SAKURA,
                AikoTheme.MODE_AMBER, AikoTheme.MODE_VIOLET, AikoTheme.MODE_OLED
        };
        for (String mode : themeModes) {
            int accent = AikoTheme.Palette.preset(mode).accent;
            int finderColor = QrEngine.resolveContrastFinderColor(accent, 0xFF101318, 0xFFFFFFFF);
            int luma = QrEngine.perceivedLuma(finderColor);
            double cr = QrEngine.contrastRatio(finderColor, 0xFFFFFFFF);
            check(luma <= 95 && cr >= 6.0, "Styled QR finder contrast failed for theme " + mode
                    + ": color=0x" + Integer.toHexString(finderColor) + " luma=" + luma + " cr=" + cr);
        }
        int whiteFinder = QrEngine.resolveContrastFinderColor(0xFFFFFFFF, 0xFF101318, 0xFFFFFFFF);
        check(QrEngine.perceivedLuma(whiteFinder) <= 95 && QrEngine.contrastRatio(whiteFinder, 0xFFFFFFFF) >= 6.0,
                "Pure white accent on white bg still enforces >=6:1 contrast");

        System.out.println("PASS QrEngine ISO 18004 matrix structure, BCH format/version, RS GF(256) syndromes, styled finder contrast, and full UTF-8 decode round-trip (V1..V13)");
    }

    private static String repeatUrl(int targetBytes) {
        StringBuilder sb = new StringBuilder("https://example.com/aiko?");
        int idx = 0;
        while (sb.toString().getBytes(StandardCharsets.UTF_8).length < targetBytes) {
            sb.append("k").append(idx++ % 10).append("=v&");
        }
        String s = sb.toString();
        return s.length() > targetBytes ? s.substring(0, targetBytes) : s;
    }

    private static void verifyFinderPatterns(boolean[][] m) {
        int n = m.length;
        int[][] centers = {{3, 3}, {3, n - 4}, {n - 4, 3}};
        for (int[] ctr : centers) {
            for (int dr = -4; dr <= 4; dr++) {
                for (int dc = -4; dc <= 4; dc++) {
                    int r = ctr[0] + dr, c = ctr[1] + dc;
                    if (r < 0 || r >= n || c < 0 || c >= n) continue;
                    int dist = Math.max(Math.abs(dr), Math.abs(dc));
                    boolean expect = dist != 2 && dist != 4;
                    check(m[r][c] == expect, "Finder/separator mismatch at (" + r + "," + c + ")");
                }
            }
        }
    }

    private static void verifyTimingPatterns(boolean[][] m) {
        int n = m.length;
        for (int i = 8; i < n - 8; i++) {
            boolean expect = (i & 1) == 0;
            check(m[6][i] == expect, "Horizontal timing mismatch at col " + i);
            check(m[i][6] == expect, "Vertical timing mismatch at row " + i);
        }
    }

    private static void verifyAlignmentPatterns(boolean[][] m, int version) {
        int[] pos = ALIGN_POS[version];
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0)) {
                    continue;
                }
                int ar = pos[i], ac = pos[j];
                for (int dr = -2; dr <= 2; dr++) {
                    for (int dc = -2; dc <= 2; dc++) {
                        boolean expect = Math.max(Math.abs(dr), Math.abs(dc)) != 1;
                        check(m[ar + dr][ac + dc] == expect, "Alignment mismatch at (" + (ar + dr) + "," + (ac + dc) + ")");
                    }
                }
            }
        }
    }

    private static int verifyAndExtractFormatMask(boolean[][] m, int version) {
        int n = m.length;
        int copy1 = 0;
        for (int i = 0; i <= 5; i++) if (m[i][8]) copy1 |= (1 << i);
        if (m[7][8]) copy1 |= (1 << 6);
        if (m[8][8]) copy1 |= (1 << 7);
        if (m[8][7]) copy1 |= (1 << 8);
        for (int i = 9; i < 15; i++) if (m[8][14 - i]) copy1 |= (1 << i);

        int copy2 = 0;
        for (int i = 0; i < 8; i++) if (m[8][n - 1 - i]) copy2 |= (1 << i);
        for (int i = 8; i < 15; i++) if (m[n - 15 + i][8]) copy2 |= (1 << i);

        check(copy1 == copy2, "Format info copy1 != copy2 for v" + version);
        int unmasked = copy1 ^ 0x5412;
        int ecBits = (unmasked >>> 13) & 3;
        int mask = (unmasked >>> 10) & 7;
        check(ecBits == 0, "Format EC bits must be M (00) for v" + version);
        check(copy1 == QrEngine.computeFormatBits(mask), "Format BCH exact match for mask " + mask);
        return mask;
    }

    private static void verifyVersionInfo(boolean[][] m, int version) {
        int n = m.length;
        int expect = QrEngine.computeVersionBits(version);
        int bl = 0, tr = 0;
        for (int i = 0; i < 18; i++) {
            int a = n - 11 + (i % 3);
            int b = i / 3;
            if (m[a][b]) bl |= (1 << i);
            if (m[b][a]) tr |= (1 << i);
        }
        check(bl == expect && tr == expect, "Version info mismatch for v" + version);
    }

    private static String unmaskAndDecodeUtf8(boolean[][] m, int version, int mask) {
        int n = m.length;
        boolean[][] fn = buildFunctionMask(n, version);
        int[] spec = EC_M_TABLE[version];
        byte[] all = new byte[spec[0]];
        int bitIdx = 0;
        boolean upward = true;
        for (int right = n - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int step = 0; step < n; step++) {
                int r = upward ? (n - 1 - step) : step;
                for (int dx = 0; dx < 2; dx++) {
                    int c = right - dx;
                    if (!fn[r][c]) {
                        boolean v = m[r][c];
                        if (maskCondition(mask, r, c)) v = !v;
                        if (bitIdx < all.length * 8) {
                            if (v) all[bitIdx >>> 3] |= (byte) (1 << (7 - (bitIdx & 7)));
                        }
                        bitIdx++;
                    }
                }
            }
            upward = !upward;
        }
        check(bitIdx >= all.length * 8 && bitIdx < (all.length + 1) * 8,
                "Exact data bit capacity match for v" + version + ": bits=" + bitIdx + ", codewords=" + all.length);

        // De-interleave blocks & verify RS syndrome on every single block!
        int ecLen = spec[1], g1Blocks = spec[2], g1Data = spec[3], g2Blocks = spec[4], g2Data = spec[5];
        int totalBlocks = g1Blocks + g2Blocks;
        byte[][] blocks = new byte[totalBlocks][];
        for (int b = 0; b < totalBlocks; b++) {
            blocks[b] = new byte[(b < g1Blocks ? g1Data : g2Data) + ecLen];
        }
        int src = 0;
        int maxData = g2Blocks > 0 ? g2Data : g1Data;
        for (int i = 0; i < maxData; i++) {
            for (int b = 0; b < totalBlocks; b++) {
                int dLen = b < g1Blocks ? g1Data : g2Data;
                if (i < dLen) blocks[b][i] = all[src++];
            }
        }
        for (int i = 0; i < ecLen; i++) {
            for (int b = 0; b < totalBlocks; b++) {
                int dLen = b < g1Blocks ? g1Data : g2Data;
                blocks[b][dLen + i] = all[src++];
            }
        }
        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        for (int b = 0; b < totalBlocks; b++) {
            verifyReedSolomonSyndrome(blocks[b], ecLen, "v" + version + " block " + b);
            int dLen = b < g1Blocks ? g1Data : g2Data;
            dataStream.write(blocks[b], 0, dLen);
        }
        byte[] data = dataStream.toByteArray();
        int mode = readBits(data, 0, 4);
        check(mode == 4, "Byte mode 0100");
        int countBits = version <= 9 ? 8 : 16;
        int byteCount = readBits(data, 4, countBits);
        byte[] decoded = new byte[byteCount];
        int pos = 4 + countBits;
        for (int i = 0; i < byteCount; i++) {
            decoded[i] = (byte) readBits(data, pos + i * 8, 8);
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static int readBits(byte[] buf, int bitPos, int count) {
        int val = 0;
        for (int i = 0; i < count; i++) {
            int p = bitPos + i;
            int bit = ((buf[p >>> 3] & 0xFF) >>> (7 - (p & 7))) & 1;
            val = (val << 1) | bit;
        }
        return val;
    }

    private static boolean[][] buildFunctionMask(int n, int version) {
        boolean[][] fn = new boolean[n][n];
        for (int i = 0; i < n; i++) { fn[6][i] = true; fn[i][6] = true; }
        for (int r = 0; r < 9; r++) for (int c = 0; c < 9; c++) fn[r][c] = true;
        for (int r = 0; r < 9; r++) for (int c = n - 8; c < n; c++) fn[r][c] = true;
        for (int r = n - 8; r < n; r++) for (int c = 0; c < 9; c++) fn[r][c] = true;
        int[] pos = ALIGN_POS[version];
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0)) continue;
                for (int dr = -2; dr <= 2; dr++) for (int dc = -2; dc <= 2; dc++) fn[pos[i] + dr][pos[j] + dc] = true;
            }
        }
        if (version >= 7) {
            for (int i = 0; i < 18; i++) {
                int a = n - 11 + (i % 3), b = i / 3;
                fn[a][b] = true;
                fn[b][a] = true;
            }
        }
        return fn;
    }

    private static boolean maskCondition(int mask, int r, int c) {
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

    private static void verifyReedSolomonSyndrome(byte[] codeword, int ecCount, String label) {
        int root = 1;
        for (int i = 0; i < ecCount; i++) {
            int acc = 0;
            for (byte b : codeword) {
                acc = QrEngine.gfMul(acc, root) ^ (b & 0xFF);
            }
            check(acc == 0, label + " non-zero RS syndrome at root alpha^" + i + ": " + acc);
            root = QrEngine.gfMul(root, 2);
        }
    }

    private static void checkBangCatalog() {
        SharedPreferences prefs = mockPrefs();
        BangCatalog catalog = new BangCatalog(prefs);
        check(BangCatalog.builtinBangs().size() >= 16, "At least 16 built-in bangs");

        String[] required = {"gh", "gf", "b", "yt", "zh", "w", "we", "r", "so", "npm", "mdn", "x", "db", "tb", "jd", "ld"};
        for (String b : required) {
            BangCatalog.Match m = catalog.resolveBang("!" + b + " aiko 浏览器");
            check(m != null && b.equals(m.bang) && HttpUrlUtil.isHttp(m.url), "Built-in bang !" + b + " resolves");
            BangCatalog.Match home = catalog.resolveBang("!" + b);
            check(home != null && HttpUrlUtil.isHttp(home.url), "Bare bang !" + b + " resolves home");
        }

        // Case-insensitive + trailing bang
        BangCatalog.Match trailing = catalog.resolveBang("android webview !SO");
        check(trailing != null && "so".equals(trailing.bang) && trailing.url.contains("android+webview"),
                "Trailing bang resolution");

        // Custom bang add/override/persist/remove + unsafe javascript rejection
        check(!catalog.addCustomBang("evil", "Evil", "javascript:alert(%s)"), "Reject unsafe custom bang template");
        check(catalog.addCustomBang("!mywiki", "My Wiki", "https://wiki.example.com/search?q={searchTerms}"),
                "Add custom bang");
        BangCatalog.Match custom = catalog.resolveBang("!mywiki hello world");
        check(custom != null && "https://wiki.example.com/search?q=hello+world".equals(custom.url),
                "Resolve custom bang");

        // Reload from persisted prefs
        BangCatalog reloaded = new BangCatalog(prefs);
        check(reloaded.listCustomBangs().size() == 1, "Custom bang persisted");
        check(reloaded.removeCustomBang("mywiki") && reloaded.listCustomBangs().isEmpty(), "Custom bang removed");

        // Slash commands
        check(BangCatalog.resolveSlashCommand("/babel") == BangCatalog.SlashCmd.BABEL, "/babel");
        check(BangCatalog.resolveSlashCommand("/翻译") == BangCatalog.SlashCmd.BABEL, "/翻译 alias");
        check(BangCatalog.resolveSlashCommand("/devtools") == BangCatalog.SlashCmd.DEVTOOLS, "/devtools");
        check(BangCatalog.resolveSlashCommand("/gallery") == BangCatalog.SlashCmd.GALLERY, "/gallery");
        check(BangCatalog.resolveSlashCommand("/longshot") == BangCatalog.SlashCmd.LONGSHOT, "/longshot");
        check(BangCatalog.resolveSlashCommand("/qr") == BangCatalog.SlashCmd.QR, "/qr");
        check(BangCatalog.resolveSlashCommand("/backup") == BangCatalog.SlashCmd.BACKUP, "/backup");
        check(BangCatalog.resolveSlashCommand("/unlock") == BangCatalog.SlashCmd.UNLOCK, "/unlock");
        check(BangCatalog.resolveSlashCommand("/dark") == BangCatalog.SlashCmd.DARK, "/dark");
        check(BangCatalog.resolveSlashCommand("/clean") == BangCatalog.SlashCmd.CLEAN, "/clean");
        check(BangCatalog.resolveSlashCommand("/settings") == BangCatalog.SlashCmd.SETTINGS, "/settings");
        check(BangCatalog.resolveSlashCommand("/unknowncmd") == null, "Unknown /cmd -> null");
        check(BangCatalog.resolveSlashCommand("https://example.com/qr") == null, "URL not treated as /cmd");

        System.out.println("PASS BangCatalog built-in 20 !bangs, custom bangs CRUD, and /command shortcuts");
    }

    private static void checkBackupManager() throws Exception {
        Path tmpRoot = Files.createTempDirectory("aiko-backup-test");
        try {
            File srcDir = tmpRoot.resolve("src").toFile();
            File prefsDir = new File(srcDir, "shared_prefs");
            File filesDir = new File(srcDir, "files");
            File fontsDir = new File(filesDir, "fonts");
            check(prefsDir.mkdirs() && fontsDir.mkdirs(), "Create test dirs");

            Files.writeString(new File(prefsDir, "aiko_theme.xml").toPath(), "<map><string name=\"mode\">emerald</string></map>");
            Files.writeString(new File(prefsDir, "start_page_config.xml").toPath(), "<map><string name=\"home_url\">https://go.itab.link/</string></map>");
            Files.writeString(new File(prefsDir, "babel_config.xml").toPath(), "<map><string name=\"engine\">GOOGLE_FREE</string></map>");
            Files.writeString(new File(prefsDir, "userscript_values.xml").toPath(), "<map><string name=\"v\">ok</string></map>");
            Files.writeString(new File(prefsDir, "userscript_connects.xml").toPath(), "<map><string name=\"c\">ok</string></map>");
            Files.writeString(new File(prefsDir, "search_engines.xml").toPath(), "<map><string name=\"id\">bing</string></map>");
            Files.writeString(new File(prefsDir, "reading_shelf.xml").toPath(), "<map><string name=\"s\">[]</string></map>");
            Files.writeString(new File(prefsDir, "reader_prefs.xml").toPath(), "<map><int name=\"font\" value=\"18\"/></map>");
            Files.writeString(new File(prefsDir, "WebViewChromiumPrefs.xml").toPath(), "<map><string name=\"internal\">SKIP_ME</string></map>");
            Files.writeString(new File(filesDir, "filters-user.txt").toPath(), "example.com##.annoying-banner\n||ads.bad.example^\n");
            byte[] fakeFont = new byte[]{0, 1, 0, 0, 0x41, 0x49, 0x4B, 0x4F};
            Files.write(new File(fontsDir, "LXGWWenKai.ttf").toPath(), fakeFont);

            ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
            BackupManager.ExportReport exp = BackupManager.exportFromDir(srcDir, zipOut);
            check(exp.prefsExported == 8 && exp.filesExported == 2,
                    "Expected 8 prefs + 2 files exported, got " + exp.prefsExported + "/" + exp.filesExported);

            // Inspect manifest & verify WebViewChromiumPrefs.xml was skipped
            byte[] archive = zipOut.toByteArray();
            boolean sawManifest = false;
            boolean sawWebViewInternal = false;
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if ("manifest.json".equals(e.getName())) {
                        sawManifest = true;
                        JSONObject man = new JSONObject(new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                        check(man.getInt("formatVersion") == 1 && "0.4.0".equals(man.getString("appVersion")),
                                "Valid manifest metadata");
                    }
                    if (e.getName().contains("WebViewChromiumPrefs")) sawWebViewInternal = true;
                }
            }
            check(sawManifest && !sawWebViewInternal, "Manifest present and WebView internals excluded");

            // Full restore round-trip
            File dstDir = tmpRoot.resolve("dst").toFile();
            check(dstDir.mkdirs(), "Create restore dir");
            BackupManager.RestoreReport rep = BackupManager.importToDir(dstDir, new ByteArrayInputStream(archive));
            check(rep.prefsRestored == 8 && rep.filesRestored == 2 && "0.4.0".equals(rep.appVersion),
                    "RestoreReport counts & version match");
            check(Files.readString(new File(dstDir, "shared_prefs/aiko_theme.xml").toPath()).contains("emerald"),
                    "Restored theme prefs match");
            check(Files.readString(new File(dstDir, "shared_prefs/userscript_values.xml").toPath()).contains("ok"),
                    "Restored userscript_values prefs match");
            check(Files.readString(new File(dstDir, "shared_prefs/reading_shelf.xml").toPath()).contains("[]"),
                    "Restored reading_shelf prefs match");
            check(Files.readString(new File(dstDir, "files/filters-user.txt").toPath()).contains("annoying-banner"),
                    "Restored user filters match");
            check(Arrays.equals(fakeFont, Files.readAllBytes(new File(dstDir, "files/fonts/LXGWWenKai.ttf").toPath())),
                    "Restored font binary matches");

            // Security: path traversal, absolute path, backslash, missing manifest, oversized entry
            assertImportRejected(dstDir, makeSingleEntryZip("../evil.xml", "bad"), "path traversal ..");
            assertImportRejected(dstDir, makeSingleEntryZip("shared_prefs/../../escape.xml", "bad"), "nested traversal");
            assertImportRejected(dstDir, makeSingleEntryZip("/etc/passwd", "root"), "absolute path");
            assertImportRejected(dstDir, makeSingleEntryZip("shared_prefs\\aiko_theme.xml", "bad"), "backslash path");
            assertImportRejected(dstDir, makeSingleEntryZip("shared_prefs/aiko_theme.xml", "<map/>"), "missing manifest");
            assertImportRejected(dstDir, makeSingleEntryZip("manifest.json", "{\"formatVersion\":99}"), "bad formatVersion");
            assertImportRejected(dstDir, makeOversizedZip(), "oversized >20MiB entry");
            check(!new File(tmpRoot.toFile(), "evil.xml").exists(), "No traversal file written");
            // Existing restored files remain intact after rejected imports (two-phase staging)
            check(Files.readString(new File(dstDir, "shared_prefs/aiko_theme.xml").toPath()).contains("emerald"),
                    "Existing files preserved after rejected import");

            System.out.println("PASS BackupManager .aiko ZIP round-trip, WebView exclusion, and security rejection");
        } finally {
            deleteRecursive(tmpRoot.toFile());
        }
    }

    private static byte[] makeOversizedZip() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("{\"formatVersion\":1,\"appVersion\":\"0.4.0\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("files/filters-user.txt"));
            byte[] zeros = new byte[65536];
            long written = 0;
            while (written <= BackupManager.MAX_ENTRY_BYTES + 65536L) {
                zos.write(zeros);
                written += zeros.length;
            }
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static byte[] makeSingleEntryZip(String entryName, String body) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(body.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static void assertImportRejected(File dstDir, byte[] badZip, String label) {
        boolean rejected = false;
        try {
            BackupManager.importToDir(dstDir, new ByteArrayInputStream(badZip));
        } catch (IOException expected) {
            rejected = true;
        }
        check(rejected, "BackupManager must reject " + label);
    }

    private static void checkStartPageConfig() {
        check(StartPageConfig.presets().size() == 5, "5 built-in start page presets");
        check("https://go.itab.link/".equals(StartPageConfig.presets().get(1).url), "iTab preset URL");

        SharedPreferences prefs = mockPrefs();
        StartPageConfig cfg = new StartPageConfig(prefs);
        check(cfg.resolveHomeUrl() == null && cfg.resolveNewTabUrl() == null, "Default resolves to native (null)");
        check(!cfg.isShowHomeButton(), "Default showHomeButton is false");

        // Set iTab homepage -> auto-enables showHomeButton, newTab follows home
        check(cfg.applyHomePresetOrUrl("https://go.itab.link/"), "Apply iTab preset");
        check("https://go.itab.link/".equals(cfg.resolveHomeUrl()), "Home resolves to iTab");
        check("https://go.itab.link/".equals(cfg.resolveNewTabUrl()), "NewTab follows iTab");
        check(cfg.isShowHomeButton(), "Custom homepage auto-suggests showHomeButton=true");
        check(cfg.shouldPreloadNewTab(), "Preload enabled for custom URL");

        // Independent new tab URL
        cfg.setNewTabFollowsHome(false);
        check(cfg.setNewTab(StartPageConfig.PageMode.CUSTOM_URL, "about:blank"), "Set about:blank new tab");
        check("about:blank".equals(cfg.resolveNewTabUrl()), "NewTab resolves about:blank");
        check(!cfg.shouldPreloadNewTab(), "about:blank skips preload");

        // URL validation
        check("https://wetab.link/".equals(StartPageConfig.normalizeStartUrl("wetab.link/")), "Bare domain promoted");
        check("file:///sdcard/start.html".equals(StartPageConfig.normalizeStartUrl("file:///sdcard/start.html")), "file:// accepted");
        check(StartPageConfig.normalizeStartUrl("javascript:alert(1)") == null, "javascript: rejected");
        check(StartPageConfig.normalizeStartUrl("intent://foo#Intent;end") == null, "intent: rejected");

        // App-jump deep-link guard
        String[] extSchemes = {
                "intent://scan/#Intent;scheme=zxing;end", "zhihu://questions/123", "bilibili://video/BV1",
                "tbopen://m.taobao.com/tbopen/index.html", "snssdk1128://aweme/detail/1",
                "weixin://dl/business/?t=1", "alipays://platformapi/startapp",
                "xhsdiscover://item/123", "sinaweibo://detail/1", "baiduboxapp://easybrowse"
        };
        for (String u : extSchemes) {
            check(StartPageConfig.isExternalAppScheme(u), "Detect external app scheme: " + u);
        }
        String[] normalUrls = {
                "https://zhihu.com/", "http://localhost:8080/", "about:blank",
                "file:///sdcard/a.html", "mailto:hi@aiko.app", "tel:10086", null, ""
        };
        for (String u : normalUrls) {
            check(!StartPageConfig.isExternalAppScheme(u), "Non-app scheme allowed: " + u);
        }
        cfg.setHostAppJumpPolicy("zhihu.com", StartPageConfig.HOST_POLICY_BLOCK);
        check(cfg.hostAppJumpPolicy("ZHIHU.COM.") == -1 && cfg.effectiveAppJumpDecision("zhihu.com") == -1,
                "Per-host block policy persisted & normalized");

        // AMOLED Night Comfort overlay ARGB calculation & night schedule window
        check(StartPageConfig.computeOverlayColor(false, 40, 30) == 0, "Disabled overlay == 0");
        check(StartPageConfig.computeOverlayColor(true, 0, 0) == 0, "Zero dim+warmth == 0");
        int dimOnly = StartPageConfig.computeOverlayColor(true, 50, 0);
        check((dimOnly & 0x00FFFFFF) == 0 && (dimOnly >>> 24) >= 120 && (dimOnly >>> 24) <= 135,
                "Dim-only is pure black translucent overlay: 0x" + Integer.toHexString(dimOnly));
        int warmCombo = StartPageConfig.computeOverlayColor(true, 25, 30);
        int r = (warmCombo >> 16) & 0xFF, g = (warmCombo >> 8) & 0xFF, b = warmCombo & 0xFF;
        check((warmCombo >>> 24) > 60 && r > g && g > b,
                "Warm+dim overlay has warm amber RGB tint: 0x" + Integer.toHexString(warmCombo));
        check(StartPageConfig.isInNightWindow(23, 22, 7) && StartPageConfig.isInNightWindow(3, 22, 7)
                && !StartPageConfig.isInNightWindow(7, 22, 7) && !StartPageConfig.isInNightWindow(14, 22, 7),
                "Overnight 22..7 window math");

        // Reload round-trip check
        StartPageConfig reopened = new StartPageConfig(prefs);
        check("https://go.itab.link/".equals(reopened.resolveHomeUrl()), "Reloaded homeUrl matches");
        check(reopened.hostAppJumpPolicy("zhihu.com") == -1, "Reloaded host policy matches");
        check(StartPageConfig.clampMinFontSize(8) == 12 && StartPageConfig.clampMinFontSize(0) == 0
                && StartPageConfig.clampMinFontSize(25) == 20, "MinFontSize clamping");
        check("https://dns.alidns.com/dns-query".equals(StartPageConfig.resolveDohUrl("alidns", "")), "DoH AliDNS URL");

        System.out.println("PASS StartPageConfig presets, URL resolution, App-Jump Guard, AMOLED overlay ARGB, and persistence");
    }

    private static void checkSiteProfile() {
        // Legacy v0.3 encoded integer: desktop=true, blockImages=false, zoom=125 -> (125 << 2) | 1 = 501
        SiteProfile legacy = SiteProfile.decode((125 << 2) | 1);
        check(legacy.desktop && !legacy.blockImages && legacy.zoom == 125 && legacy.flags == 0,
                "v0.3 legacy integer decodes identically");
        check(SiteProfile.DEFAULT.encode() == 0, "DEFAULT encodes to 0");
        check(SiteProfile.decode(0) == SiteProfile.DEFAULT, "0 decodes to DEFAULT");

        // New v0.4 bitflags
        SiteProfile p = SiteProfile.DEFAULT
                .withFlag(SiteProfile.UNLOCK_COPY, true)
                .withFlag(SiteProfile.BABEL_AUTO, true)
                .withFlag(SiteProfile.FORCE_ZOOM, true)
                .withZoom(115);
        int encoded = p.encode();
        check(encoded != 0, "Profile with v0.4 flags encodes non-zero");
        SiteProfile decoded = SiteProfile.decode(encoded);
        check(decoded.zoom == 115 && !decoded.desktop && !decoded.blockImages
                && decoded.unlockCopy && decoded.babelAuto && !decoded.babelNever && decoded.forceZoom,
                "v0.4 bitflags round-trip cleanly");

        // BABEL_AUTO and BABEL_NEVER mutual exclusivity
        SiteProfile switched = decoded.withFlag(SiteProfile.BABEL_NEVER, true);
        check(!switched.babelAuto && switched.babelNever && switched.unlockCopy && switched.forceZoom,
                "BABEL_NEVER clears BABEL_AUTO while preserving other flags");

        System.out.println("PASS SiteProfile v0.3 legacy binary compat and v0.4 bitflags round-trip");
    }

    private static void checkSiteExtrasAndFilterHelpers() {
        check(SiteExtras.normalizeAutoRefreshSec(-5) == 0
                && SiteExtras.normalizeAutoRefreshSec(10) == 15
                && SiteExtras.normalizeAutoRefreshSec(30) == 30
                && SiteExtras.normalizeAutoRefreshSec(60) == 60
                && SiteExtras.normalizeAutoRefreshSec(999) == 300,
                "SiteExtras auto-refresh preset clamping");

        String userRules = "! Aiko User Rules\n"
                + "example.com##.ad-banner\n"
                + "news.example.com,other.org##div#popup\n"
                + "||ads.example.com^\n"
                + "unrelated.net##.sidebar\n";
        List<String> matched = SiteFilter.filterUserRulesForHost(userRules, "news.example.com");
        check(matched.size() == 2
                && matched.contains("example.com##.ad-banner")
                && matched.contains("news.example.com,other.org##div#popup"),
                "userRulesForHost matches exact & parent-domain rules: " + matched);
        String afterRemove = SiteFilter.removeExactRuleLine(userRules, "example.com##.ad-banner");
        check(afterRemove != null && !afterRemove.contains("example.com##.ad-banner")
                && afterRemove.contains("unrelated.net##.sidebar"),
                "removeExactRuleLine removes only the targeted rule");
        check(SiteFilter.removeExactRuleLine(userRules, "nonexistent##.x") == null,
                "removeExactRuleLine returns null when missing");

        System.out.println("PASS SiteExtras auto-refresh normalization and SiteFilter host rule listing/undo");
    }

    private static void checkWebViewPerfAndVideoBooster() {
        check(WebViewPerf.maxActiveTabsForProfile(PerfProfiles.preset(PerfProfiles.ID_RAMPAGE)) == 12, "Rampage -> 12");
        check(WebViewPerf.maxActiveTabsForProfile(PerfProfiles.preset(PerfProfiles.ID_BALANCED)) == 6, "Balanced -> 6");
        check(WebViewPerf.maxActiveTabsForProfile(PerfProfiles.preset(PerfProfiles.ID_ECO)) == 3, "Eco -> 3");
        check(WebViewPerf.maxActiveTabsForProfile(null) == 6, "null -> 6");
        PerfProfiles.Profile custom9 = PerfProfiles.sanitized("custom-9", "My9", false, 0, false, 9, 3, 1, false, 64, false);
        check(WebViewPerf.maxActiveTabsForProfile(custom9) == 9, "Custom maxHotTabs=9 -> 9");
        PerfProfiles.Profile custom2 = PerfProfiles.sanitized("custom-2", "Tiny", false, 2, false, 2, 0, 0, false, 16, false);
        check(WebViewPerf.maxActiveTabsForProfile(custom2) == 3, "Custom maxHotTabs=2 clamps to 3");

        String fsJs = VideoBooster.fullscreenGestureScript();
        check(fsJs.contains("__aikoFsGesturesInstalled") && fsJs.contains("2.0x")
                && fsJs.contains("pointerdown") && fsJs.contains("350"),
                "VideoBooster fullscreen gesture script valid");
        String bgJs = VideoBooster.backgroundPlaybackScript();
        check(bgJs.contains("visibilityState") && bgJs.contains("visibilitychange") && bgJs.contains("__aikoBgAudio=true"),
                "VideoBooster background playback script valid");

        System.out.println("PASS WebViewPerf.maxActiveTabsForProfile and VideoBooster fullscreen/background JS");
    }

    private static SharedPreferences mockPrefs() {
        Map<String, Object> store = new ConcurrentHashMap<>();
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, values) -> {
                    String name = method.getName();
                    if ("getString".equals(name)) {
                        Object v = store.get(values[0]);
                        return v instanceof String ? v : values[1];
                    }
                    if ("getBoolean".equals(name)) {
                        Object v = store.get(values[0]);
                        return v instanceof Boolean ? v : values[1];
                    }
                    if ("getInt".equals(name)) {
                        Object v = store.get(values[0]);
                        return v instanceof Integer ? v : values[1];
                    }
                    if ("getAll".equals(name)) {
                        return new HashMap<>(store);
                    }
                    if ("contains".equals(name)) {
                        return store.containsKey(values[0]);
                    }
                    if ("edit".equals(name)) {
                        return Proxy.newProxyInstance(
                                SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class},
                                (editor, action, params) -> {
                                    String op = action.getName();
                                    if ("putString".equals(op) || "putBoolean".equals(op) || "putInt".equals(op)) {
                                        if (params[1] == null) store.remove(params[0]);
                                        else store.put((String) params[0], params[1]);
                                        return editor;
                                    }
                                    if ("remove".equals(op)) {
                                        store.remove(params[0]);
                                        return editor;
                                    }
                                    if ("apply".equals(op)) return null;
                                    if ("commit".equals(op)) return true;
                                    return editor;
                                });
                    }
                    throw new AssertionError("Unexpected SharedPreferences call: " + name);
                });
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError("FAILED: " + label);
    }
}

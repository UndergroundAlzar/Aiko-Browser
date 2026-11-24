package app.aikobrowser;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.nio.charset.StandardCharsets;

/**
 * Pure-Java ISO/IEC 18004 QR Code encoder (Byte mode UTF-8, Error Correction Level M,
 * Versions 1–15 up to 412 UTF-8 bytes) with zero external library dependencies.
 *
 * {@link #encodeMatrix(String)} is 100% pure Java (no Android runtime calls) for host JVM testing.
 */
public final class QrEngine {
    /** Maximum UTF-8 byte length supported by Version 15 EC Level M. */
    public static final int MAX_UTF8_BYTES = 412;
    private static final int MIN_VERSION = 1;
    private static final int MAX_VERSION = 15;
    private static final int QUIET_ZONE = 4;

    // Error Correction Level M format indicator bits = 0b00 (ISO 18004 Table 12).
    private static final int EC_M_FORMAT_BITS = 0;

    // ISO/IEC 18004 Table 9 for Error Correction Level M, Versions 1..15:
    // { totalCodewords, ecPerBlock, g1Blocks, g1Data, g2Blocks, g2Data }
    private static final int[][] EC_M_TABLE = {
            null, // 0 unused
            {  26, 10, 1, 16, 0,  0 }, // V1  (21x21) max 14 B
            {  44, 16, 1, 28, 0,  0 }, // V2  (25x25) max 26 B
            {  70, 26, 1, 44, 0,  0 }, // V3  (29x29) max 42 B
            { 100, 18, 2, 32, 0,  0 }, // V4  (33x33) max 62 B
            { 134, 24, 2, 43, 0,  0 }, // V5  (37x37) max 84 B
            { 172, 16, 4, 27, 0,  0 }, // V6  (41x41) max 106 B
            { 196, 18, 4, 31, 0,  0 }, // V7  (45x45) max 122 B
            { 242, 22, 2, 38, 2, 39 }, // V8  (49x49) max 152 B
            { 292, 22, 3, 36, 2, 37 }, // V9  (53x53) max 180 B
            { 346, 26, 4, 43, 1, 44 }, // V10 (57x57) max 213 B
            { 404, 30, 1, 50, 4, 51 }, // V11 (61x61) max 251 B
            { 466, 22, 6, 36, 2, 37 }, // V12 (65x65) max 287 B
            { 532, 22, 8, 37, 1, 38 }, // V13 (69x69) max 331 B
            { 581, 24, 4, 40, 5, 41 }, // V14 (73x73) max 362 B
            { 655, 24, 5, 41, 5, 42 }  // V15 (77x77) max 412 B
    };

    // ISO/IEC 18004 Annex E alignment pattern center coordinates for Versions 1..15.
    private static final int[][] ALIGN_POS = {
            null,
            {},
            { 6, 18 },
            { 6, 22 },
            { 6, 26 },
            { 6, 30 },
            { 6, 34 },
            { 6, 22, 38 },
            { 6, 24, 42 },
            { 6, 26, 46 },
            { 6, 28, 50 },
            { 6, 30, 54 },
            { 6, 32, 58 },
            { 6, 34, 62 },
            { 6, 26, 46, 66 },
            { 6, 26, 48, 70 }
    };

    // Precomputed GF(256) log/exp tables for primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11d).
    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) {
            GF_EXP[i] = GF_EXP[i - 255];
        }
    }

    private QrEngine() {}

    /**
     * Pure-Java QR code encoder returning an N x N boolean dark-module matrix (true = dark).
     * Encodes UTF-8 bytes using Byte mode and Error Correction Level M.
     *
     * @throws IllegalArgumentException if UTF-8 encoded text exceeds {@link #MAX_UTF8_BYTES}
     */
    public static boolean[][] encodeMatrix(String text) {
        byte[] payload = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        int version = selectVersion(payload.length);
        if (version < 0) {
            throw new IllegalArgumentException("QR payload too long: " + payload.length
                    + " UTF-8 bytes (max " + MAX_UTF8_BYTES + ")");
        }
        int[] spec = EC_M_TABLE[version];
        int totalDataCodewords = spec[2] * spec[3] + spec[4] * spec[5];

        byte[] dataCodewords = encodeByteModeData(payload, version, totalDataCodewords);
        byte[] allCodewords = addEcAndInterleave(dataCodewords, spec);

        int size = version * 4 + 17;
        boolean[][] base = new boolean[size][size];
        boolean[][] isFunction = new boolean[size][size];

        drawFunctionPatterns(base, isFunction, version);
        drawCodewords(base, isFunction, allCodewords);

        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        boolean[][] bestMatrix = null;

        for (int mask = 0; mask < 8; mask++) {
            boolean[][] candidate = copyMatrix(base);
            applyMask(candidate, isFunction, mask);
            drawFormatBits(candidate, mask);
            int penalty = computePenalty(candidate);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestMask = mask;
                bestMatrix = candidate;
            }
        }
        // Keep bestMask referenced cleanly (format bits already written into bestMatrix).
        if (bestMatrix == null) {
            bestMatrix = copyMatrix(base);
            applyMask(bestMatrix, isFunction, bestMask);
            drawFormatBits(bestMatrix, bestMask);
        }
        return bestMatrix;
    }

    /** Renders a crisp pixel-aligned square QR code Bitmap with standard 4-module quiet zone. */
    public static Bitmap encode(String text, int sizePx, int fgColor, int bgColor) {
        int dim = Math.max(64, sizePx);
        boolean[][] matrix = encodeMatrix(clampUtf8ForUi(text));
        int n = matrix.length;
        int totalModules = n + QUIET_ZONE * 2;

        Bitmap bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(bgColor);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fgColor);

        float cell = (float) dim / totalModules;
        for (int r = 0; r < n; r++) {
            float top = (r + QUIET_ZONE) * cell;
            float bottom = (r + QUIET_ZONE + 1) * cell;
            for (int c = 0; c < n; c++) {
                if (!matrix[r][c]) continue;
                float left = (c + QUIET_ZONE) * cell;
                float right = (c + QUIET_ZONE + 1) * cell;
                canvas.drawRect(left, top, right + 0.25f, bottom + 0.25f, paint);
            }
        }
        return bmp;
    }

    /**
     * Renders a Material-styled QR code Bitmap with smooth rounded data dots and accent-tinted
     * finder outer rings while maintaining full ISO 18004 scanner reliability.
     */
    public static Bitmap encodeStyled(String text, int sizePx, int fgColor, int accentColor, int bgColor) {
        int dim = Math.max(64, sizePx);
        boolean[][] matrix = encodeMatrix(clampUtf8ForUi(text));
        int n = matrix.length;
        int totalModules = n + QUIET_ZONE * 2;
        float cell = (float) dim / totalModules;

        Bitmap bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(bgColor);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        RectF rect = new RectF();

        // 1. Draw all non-finder dark modules as crisp rounded dots.
        paint.setColor(fgColor);
        float inset = cell * 0.08f;
        float dotRadius = cell * 0.34f;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (!matrix[r][c] || isInsideFinder(r, c, n)) continue;
                float left = (c + QUIET_ZONE) * cell + inset;
                float top = (r + QUIET_ZONE) * cell + inset;
                float right = (c + QUIET_ZONE + 1) * cell - inset;
                float bottom = (r + QUIET_ZONE + 1) * cell - inset;
                rect.set(left, top, right, bottom);
                canvas.drawRoundRect(rect, dotRadius, dotRadius, paint);
            }
        }

        // 2. Draw the three 7x7 finder patterns with high-contrast darkened accent outer rings and fg inner eyes.
        int finderAccent = resolveContrastFinderColor(accentColor, fgColor, bgColor);
        drawStyledFinder(canvas, paint, rect, QUIET_ZONE * cell, QUIET_ZONE * cell,
                cell, fgColor, finderAccent, bgColor);
        drawStyledFinder(canvas, paint, rect, (n - 7 + QUIET_ZONE) * cell, QUIET_ZONE * cell,
                cell, fgColor, finderAccent, bgColor);
        drawStyledFinder(canvas, paint, rect, QUIET_ZONE * cell, (n - 7 + QUIET_ZONE) * cell,
                cell, fgColor, finderAccent, bgColor);

        return bmp;
    }

    /**
     * Ensures styled QR finder outer rings retain theme hue while guaranteeing high scanner contrast
     * and clean grayscale binarization (even for bright/OLED themes like #E2E8F0 on #FFFFFF).
     */
    static int resolveContrastFinderColor(int accentColor, int fgColor, int bgColor) {
        int acc = accentColor | 0xFF000000;
        int fg = fgColor | 0xFF000000;
        int bg = bgColor | 0xFF000000;
        int bgLuma = perceivedLuma(bg);
        if (bgLuma > 160) {
            if (perceivedLuma(acc) <= 95 && contrastRatio(acc, bg) >= 6.0) {
                return acc;
            }
            int ar = (acc >> 16) & 0xFF;
            int ag = (acc >> 8) & 0xFF;
            int ab = acc & 0xFF;
            // First scale brightness down proportionally to preserve rich theme hue
            for (int step = 92; step >= 16; step -= 4) {
                float s = step / 100f;
                int c = 0xFF000000
                        | (Math.round(ar * s) << 16)
                        | (Math.round(ag * s) << 8)
                        | Math.round(ab * s);
                if (perceivedLuma(c) <= 95 && contrastRatio(c, bg) >= 6.0) {
                    return c;
                }
            }
            // Fallback: blend toward fgColor until contrast & luma thresholds are satisfied
            for (int step = 1; step <= 10; step++) {
                int c = blendRgb(acc, fg, step / 10f);
                if (perceivedLuma(c) <= 95 && contrastRatio(c, bg) >= 6.0) {
                    return c;
                }
            }
            return fg;
        }
        if (contrastRatio(acc, bg) >= 5.5) return acc;
        for (int step = 1; step <= 10; step++) {
            int c = blendRgb(acc, fg, step / 10f);
            if (contrastRatio(c, bg) >= 5.5) return c;
        }
        return fg;
    }

    static int perceivedLuma(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (299 * r + 587 * g + 114 * b) / 1000;
    }

    static double contrastRatio(int argb1, int argb2) {
        double l1 = relativeLuminance(argb1);
        double l2 = relativeLuminance(argb2);
        double hi = Math.max(l1, l2);
        double lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    private static double relativeLuminance(int argb) {
        double r = linearizeChannel(((argb >> 16) & 0xFF) / 255.0);
        double g = linearizeChannel(((argb >> 8) & 0xFF) / 255.0);
        double b = linearizeChannel((argb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double linearizeChannel(double c) {
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int blendRgb(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) * (1f - t) + ((to >> 16) & 0xFF) * t);
        int g = Math.round(((from >> 8) & 0xFF) * (1f - t) + ((to >> 8) & 0xFF) * t);
        int b = Math.round((from & 0xFF) * (1f - t) + (to & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void drawStyledFinder(Canvas canvas, Paint paint, RectF rect,
                                         float x, float y, float cell,
                                         int fgColor, int accentColor, int bgColor) {
        // Outer 7x7 accent ring
        paint.setColor(accentColor);
        rect.set(x, y, x + 7f * cell, y + 7f * cell);
        canvas.drawRoundRect(rect, cell * 1.75f, cell * 1.75f, paint);

        // Middle 5x5 background cutout
        paint.setColor(bgColor);
        rect.set(x + cell, y + cell, x + 6f * cell, y + 6f * cell);
        canvas.drawRoundRect(rect, cell * 1.15f, cell * 1.15f, paint);

        // Inner 3x3 high-contrast eye
        paint.setColor(fgColor);
        rect.set(x + 2f * cell, y + 2f * cell, x + 5f * cell, y + 5f * cell);
        canvas.drawRoundRect(rect, cell * 0.85f, cell * 0.85f, paint);
    }

    private static boolean isInsideFinder(int r, int c, int n) {
        return (r < 7 && c < 7) || (r < 7 && c >= n - 7) || (r >= n - 7 && c < 7);
    }

    private static String clampUtf8ForUi(String text) {
        if (text == null) return "";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_UTF8_BYTES) return text;
        int len = MAX_UTF8_BYTES;
        // Back off from UTF-8 continuation bytes (10xxxxxx) to preserve a valid codepoint boundary.
        while (len > 0 && (bytes[len] & 0xC0) == 0x80) len--;
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    static int selectVersion(int byteLen) {
        for (int v = MIN_VERSION; v <= MAX_VERSION; v++) {
            int[] spec = EC_M_TABLE[v];
            int dataCodewords = spec[2] * spec[3] + spec[4] * spec[5];
            int headerBits = 4 + (v <= 9 ? 8 : 16);
            int maxBytes = (dataCodewords * 8 - headerBits) / 8;
            if (byteLen <= maxBytes) return v;
        }
        return -1;
    }

    private static byte[] encodeByteModeData(byte[] payload, int version, int totalDataCodewords) {
        int capacityBits = totalDataCodewords * 8;
        byte[] out = new byte[totalDataCodewords];
        int bitPos = 0;

        // 4-bit Byte mode indicator: 0100
        bitPos = appendBits(out, bitPos, 0x4, 4);
        // Character count indicator: 8 bits for V1..V9, 16 bits for V10..V26
        int countBits = version <= 9 ? 8 : 16;
        bitPos = appendBits(out, bitPos, payload.length, countBits);
        // UTF-8 payload bytes
        for (byte b : payload) {
            bitPos = appendBits(out, bitPos, b & 0xFF, 8);
        }
        // Terminator (up to 4 zero bits)
        int terminator = Math.min(4, capacityBits - bitPos);
        bitPos += terminator;
        // Pad to byte boundary
        int rem = bitPos & 7;
        if (rem != 0) bitPos += (8 - rem);

        // Alternating pad codewords 0xEC, 0x11
        int bytePos = bitPos >>> 3;
        boolean alt = false;
        while (bytePos < totalDataCodewords) {
            out[bytePos++] = (byte) (alt ? 0x11 : 0xEC);
            alt = !alt;
        }
        return out;
    }

    private static int appendBits(byte[] dst, int bitPos, int value, int numBits) {
        for (int i = numBits - 1; i >= 0; i--) {
            if (((value >>> i) & 1) != 0) {
                dst[bitPos >>> 3] |= (byte) (1 << (7 - (bitPos & 7)));
            }
            bitPos++;
        }
        return bitPos;
    }

    private static byte[] addEcAndInterleave(byte[] data, int[] spec) {
        int totalCodewords = spec[0];
        int ecLen = spec[1];
        int g1Blocks = spec[2];
        int g1Data = spec[3];
        int g2Blocks = spec[4];
        int g2Data = spec[5];
        int totalBlocks = g1Blocks + g2Blocks;

        byte[] gen = reedSolomonGenerator(ecLen);
        byte[][] dataBlocks = new byte[totalBlocks][];
        byte[][] ecBlocks = new byte[totalBlocks][];

        int offset = 0;
        for (int b = 0; b < totalBlocks; b++) {
            int len = b < g1Blocks ? g1Data : g2Data;
            byte[] block = new byte[len];
            System.arraycopy(data, offset, block, 0, len);
            offset += len;
            dataBlocks[b] = block;
            ecBlocks[b] = reedSolomonRemainder(block, gen);
        }

        byte[] interleaved = new byte[totalCodewords];
        int dst = 0;
        int maxDataLen = g2Blocks > 0 ? g2Data : g1Data;
        for (int i = 0; i < maxDataLen; i++) {
            for (int b = 0; b < totalBlocks; b++) {
                if (i < dataBlocks[b].length) {
                    interleaved[dst++] = dataBlocks[b][i];
                }
            }
        }
        for (int i = 0; i < ecLen; i++) {
            for (int b = 0; b < totalBlocks; b++) {
                interleaved[dst++] = ecBlocks[b][i];
            }
        }
        return interleaved;
    }

    /** Builds generator polynomial coefficients (excluding leading x^deg term). */
    static byte[] reedSolomonGenerator(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                int prod = gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) {
                    prod ^= (result[j + 1] & 0xFF);
                }
                result[j] = (byte) prod;
            }
            root = gfMul(root, 2);
        }
        return result;
    }

    /** Computes Reed-Solomon remainder of data(x) * x^deg mod generator(x) over GF(256). */
    static byte[] reedSolomonRemainder(byte[] data, byte[] generator) {
        int ecLen = generator.length;
        byte[] rem = new byte[ecLen];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ (rem[0] & 0xFF);
            System.arraycopy(rem, 1, rem, 0, ecLen - 1);
            rem[ecLen - 1] = 0;
            if (factor != 0) {
                for (int j = 0; j < ecLen; j++) {
                    rem[j] = (byte) ((rem[j] & 0xFF) ^ gfMul(generator[j] & 0xFF, factor));
                }
            }
        }
        return rem;
    }

    static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return GF_EXP[GF_LOG[a] + GF_LOG[b]];
    }

    private static void drawFunctionPatterns(boolean[][] m, boolean[][] fn, int version) {
        int n = m.length;

        // 1. Timing patterns (row 6 and col 6)
        for (int i = 0; i < n; i++) {
            boolean dark = (i & 1) == 0;
            setFunction(m, fn, 6, i, dark);
            setFunction(m, fn, i, 6, dark);
        }

        // 2. Three 7x7 finder patterns + 1-module separators
        drawFinder(m, fn, 3, 3);
        drawFinder(m, fn, 3, n - 4);
        drawFinder(m, fn, n - 4, 3);

        // 3. Alignment patterns (Version >= 2)
        int[] pos = ALIGN_POS[version];
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                boolean topLeft = (i == 0 && j == 0);
                boolean topRight = (i == 0 && j == pos.length - 1);
                boolean bottomLeft = (i == pos.length - 1 && j == 0);
                if (topLeft || topRight || bottomLeft) continue;
                drawAlignment(m, fn, pos[i], pos[j]);
            }
        }

        // 4. Reserve format information modules + mandatory dark module at (n - 8, 8)
        drawFormatReservation(m, fn);

        // 5. Version information blocks (Version >= 7)
        if (version >= 7) {
            drawVersionBits(m, fn, version);
        }
    }

    private static void setFunction(boolean[][] m, boolean[][] fn, int r, int c, boolean dark) {
        m[r][c] = dark;
        fn[r][c] = true;
    }

    private static void drawFinder(boolean[][] m, boolean[][] fn, int centerR, int centerC) {
        int n = m.length;
        for (int dr = -4; dr <= 4; dr++) {
            for (int dc = -4; dc <= 4; dc++) {
                int r = centerR + dr;
                int c = centerC + dc;
                if (r < 0 || r >= n || c < 0 || c >= n) continue;
                int dist = Math.max(Math.abs(dr), Math.abs(dc));
                boolean dark = dist != 2 && dist != 4;
                setFunction(m, fn, r, c, dark);
            }
        }
    }

    private static void drawAlignment(boolean[][] m, boolean[][] fn, int centerR, int centerC) {
        for (int dr = -2; dr <= 2; dr++) {
            for (int dc = -2; dc <= 2; dc++) {
                int dist = Math.max(Math.abs(dr), Math.abs(dc));
                setFunction(m, fn, centerR + dr, centerC + dc, dist != 1);
            }
        }
    }

    private static void drawFormatReservation(boolean[][] m, boolean[][] fn) {
        int n = m.length;
        for (int i = 0; i <= 5; i++) setFunction(m, fn, i, 8, false);
        setFunction(m, fn, 7, 8, false);
        setFunction(m, fn, 8, 8, false);
        setFunction(m, fn, 8, 7, false);
        for (int i = 9; i < 15; i++) setFunction(m, fn, 8, 14 - i, false);

        for (int i = 0; i < 8; i++) setFunction(m, fn, 8, n - 1 - i, false);
        for (int i = 8; i < 15; i++) setFunction(m, fn, n - 15 + i, 8, false);

        // Mandatory dark module (ISO 18004 Section 7.9)
        setFunction(m, fn, n - 8, 8, true);
    }

    static int computeFormatBits(int mask) {
        int data = (EC_M_FORMAT_BITS << 3) | (mask & 7);
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        return ((data << 10) | rem) ^ 0x5412;
    }

    private static void drawFormatBits(boolean[][] m, int mask) {
        int n = m.length;
        int bits = computeFormatBits(mask);

        // Copy 1 (top-left finder perimeter)
        for (int i = 0; i <= 5; i++) m[i][8] = ((bits >>> i) & 1) != 0;
        m[7][8] = ((bits >>> 6) & 1) != 0;
        m[8][8] = ((bits >>> 7) & 1) != 0;
        m[8][7] = ((bits >>> 8) & 1) != 0;
        for (int i = 9; i < 15; i++) m[8][14 - i] = ((bits >>> i) & 1) != 0;

        // Copy 2 (top-right & bottom-left)
        for (int i = 0; i < 8; i++) m[8][n - 1 - i] = ((bits >>> i) & 1) != 0;
        for (int i = 8; i < 15; i++) m[n - 15 + i][8] = ((bits >>> i) & 1) != 0;

        m[n - 8][8] = true;
    }

    static int computeVersionBits(int version) {
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        return (version << 12) | rem;
    }

    private static void drawVersionBits(boolean[][] m, boolean[][] fn, int version) {
        int n = m.length;
        int bits = computeVersionBits(version);
        for (int i = 0; i < 18; i++) {
            boolean bit = ((bits >>> i) & 1) != 0;
            int a = n - 11 + (i % 3);
            int b = i / 3;
            setFunction(m, fn, a, b, bit);
            setFunction(m, fn, b, a, bit);
        }
    }

    private static void drawCodewords(boolean[][] m, boolean[][] fn, byte[] data) {
        int n = m.length;
        int bitIdx = 0;
        int totalBits = data.length * 8;
        boolean upward = true;

        for (int right = n - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5; // Skip vertical timing column
            for (int step = 0; step < n; step++) {
                int r = upward ? (n - 1 - step) : step;
                for (int dx = 0; dx < 2; dx++) {
                    int c = right - dx;
                    if (!fn[r][c]) {
                        boolean dark = false;
                        if (bitIdx < totalBits) {
                            dark = (((data[bitIdx >>> 3] & 0xFF) >>> (7 - (bitIdx & 7))) & 1) != 0;
                        }
                        m[r][c] = dark;
                        bitIdx++;
                    }
                }
            }
            upward = !upward;
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] fn, int mask) {
        int n = m.length;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (fn[r][c]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = ((r + c) & 1) == 0; break;
                    case 1: invert = (r & 1) == 0; break;
                    case 2: invert = (c % 3) == 0; break;
                    case 3: invert = ((r + c) % 3) == 0; break;
                    case 4: invert = (((r / 2) + (c / 3)) & 1) == 0; break;
                    case 5: invert = ((r * c) & 1) + ((r * c) % 3) == 0; break;
                    case 6: invert = ((((r * c) & 1) + ((r * c) % 3)) & 1) == 0; break;
                    case 7: invert = ((((r + c) & 1) + ((r * c) % 3)) & 1) == 0; break;
                    default: invert = false; break;
                }
                if (invert) m[r][c] = !m[r][c];
            }
        }
    }

    /** ISO/IEC 18004 Section 7.8.3.1 standard four-rule mask penalty score. */
    private static int computePenalty(boolean[][] m) {
        int n = m.length;
        int penalty = 0;

        // Rule 1 & Rule 3 across rows
        for (int r = 0; r < n; r++) {
            boolean runColor = m[r][0];
            int runLen = 1;
            int[] runHist = new int[7];
            for (int c = 1; c < n; c++) {
                if (m[r][c] == runColor) {
                    runLen++;
                    if (runLen == 5) penalty += 3;
                    else if (runLen > 5) penalty += 1;
                } else {
                    penalty += countFinderPatternPenalty(runHist, runLen, runColor);
                    runColor = m[r][c];
                    runLen = 1;
                }
            }
            penalty += countFinderPatternPenaltyEnd(runHist, runLen, runColor);
        }

        // Rule 1 & Rule 3 across columns
        for (int c = 0; c < n; c++) {
            boolean runColor = m[0][c];
            int runLen = 1;
            int[] runHist = new int[7];
            for (int r = 1; r < n; r++) {
                if (m[r][c] == runColor) {
                    runLen++;
                    if (runLen == 5) penalty += 3;
                    else if (runLen > 5) penalty += 1;
                } else {
                    penalty += countFinderPatternPenalty(runHist, runLen, runColor);
                    runColor = m[r][c];
                    runLen = 1;
                }
            }
            penalty += countFinderPatternPenaltyEnd(runHist, runLen, runColor);
        }

        // Rule 2: 2x2 blocks of same color
        for (int r = 0; r < n - 1; r++) {
            for (int c = 0; c < n - 1; c++) {
                boolean v = m[r][c];
                if (v == m[r][c + 1] && v == m[r + 1][c] && v == m[r + 1][c + 1]) {
                    penalty += 3;
                }
            }
        }

        // Rule 4: balance of dark and light modules
        int dark = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (m[r][c]) dark++;
            }
        }
        int total = n * n;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        if (k > 0) penalty += k * 10;

        return penalty;
    }

    private static int countFinderPatternPenalty(int[] hist, int runLen, boolean runColor) {
        if (!runColor) {
            pushHistory(hist, runLen);
            return checkFinderRatio(hist) ? 40 : 0;
        }
        pushHistory(hist, runLen);
        return 0;
    }

    private static int countFinderPatternPenaltyEnd(int[] hist, int runLen, boolean runColor) {
        int score = 0;
        if (runColor) {
            pushHistory(hist, runLen);
            pushHistory(hist, 0);
            if (checkFinderRatio(hist)) score += 40;
        } else {
            pushHistory(hist, runLen);
            if (checkFinderRatio(hist)) score += 40;
        }
        return score;
    }

    private static void pushHistory(int[] hist, int len) {
        System.arraycopy(hist, 0, hist, 1, hist.length - 1);
        hist[0] = len;
    }

    // Checks 1:1:3:1:1 dark/light ratio flanked by at least 4 light modules on either side.
    private static boolean checkFinderRatio(int[] h) {
        int n = h[1];
        if (n <= 0) return false;
        boolean core = (h[2] == n && h[3] == 3 * n && h[4] == n && h[5] == n);
        if (!core) return false;
        return h[0] >= 4 * n || h[6] >= 4 * n;
    }

    private static boolean[][] copyMatrix(boolean[][] src) {
        int n = src.length;
        boolean[][] copy = new boolean[n][n];
        for (int r = 0; r < n; r++) {
            System.arraycopy(src[r], 0, copy[r], 0, n);
        }
        return copy;
    }
}

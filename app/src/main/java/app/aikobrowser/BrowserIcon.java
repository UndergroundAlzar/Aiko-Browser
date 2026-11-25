package app.aikobrowser;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

import java.util.EnumMap;

/**
 * Monochrome 24dp vector controls with pre-built static Paths and ZERO onDraw allocations.
 * Optically balanced on a 24x24 grid (1.85 stroke, round caps/joins) for 185Hz (5.4ms) frame budgets.
 */
final class BrowserIcon extends View {
    enum Kind {
        BACK, FORWARD, DOWN, SEARCH, SHIELD, TABS, MENU, REFRESH, STOP, GO, ADD, CLOSE,
        STAR, HISTORY, FIND, SHARE, DOWNLOAD, PRIVATE, HOME, SPEED, COPY, CODE,
        DESKTOP, FONT, IMAGE, READER, MUTE, UNMUTE, PIN, ENGINE, PICKER, DARK,
        PDF, BOLT, GESTURE, CHECK, PALETTE, INFO, VIDEO,
        TRANSLATE, QR, GALLERY, BACKUP, UNLOCK, DEVTOOLS, PWA, SUN_MOON, GRID,
        SNIFF, UNDO, FLASH_CMD, SPEAKER, PEEK, TIMER,
        SETTINGS, TRASH, EDIT, UP
    }

    static final float STROKE_WEIGHT = 1.85f;

    private static final EnumMap<Kind, Path> STROKE_PATHS = new EnumMap<>(Kind.class);
    private static final EnumMap<Kind, Path> FILL_PATHS = new EnumMap<>(Kind.class);

    static {
        buildCatalog();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path scaledStroke = new Path();
    private final Path scaledFill = new Path();
    private final Matrix scaleMatrix = new Matrix();
    private final float density;
    private Kind kind;
    private int tint = AikoTheme.TEXT;
    private String count = "";
    private float textX, textY, textSizePx, strokeWidthPx;
    private boolean dirtyGeometry = true;

    BrowserIcon(Context context, Kind kind) {
        super(context);
        this.kind = kind;
        density = getResources().getDisplayMetrics().density;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(AikoTheme.MEDIUM);
    }

    static RippleDrawable feedback(int color, float radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xffffffff);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(color), null, mask);
    }

    void symbol(Kind value) {
        if (kind != value) {
            kind = value;
            dirtyGeometry = true;
            invalidate();
        }
    }

    void tint(int value) {
        if (tint != value) {
            tint = value;
            invalidate();
        }
    }

    void count(int value) {
        String next = value > 99 ? "99" : Integer.toString(value);
        if (!count.equals(next)) {
            count = next;
            dirtyGeometry = true;
            invalidate();
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        dirtyGeometry = true;
    }

    private void rebuildScaledGeometry() {
        dirtyGeometry = false;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float grid = Math.min(Math.min(w, h), 22.5f * density);
        float scale = grid / 24f;
        float tx = (w - grid) * 0.5f;
        float ty = (h - grid) * 0.5f;
        scaleMatrix.setScale(scale, scale);
        scaleMatrix.postTranslate(tx, ty);
        strokeWidthPx = STROKE_WEIGHT * scale;

        scaledStroke.rewind();
        Path baseStroke = STROKE_PATHS.get(kind);
        if (baseStroke != null) baseStroke.transform(scaleMatrix, scaledStroke);

        scaledFill.rewind();
        Path baseFill = FILL_PATHS.get(kind);
        if (baseFill != null) baseFill.transform(scaleMatrix, scaledFill);

        if (kind == Kind.TABS) {
            textSizePx = (count.length() >= 2 ? 8.2f : 9.6f) * scale;
            paint.setTextSize(textSizePx);
            textX = tx + 12f * scale;
            textY = ty + 12f * scale - (paint.ascent() + paint.descent()) * 0.5f;
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (dirtyGeometry) rebuildScaledGeometry();
        paint.setColor(tint);
        if (!scaledStroke.isEmpty()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidthPx);
            c.drawPath(scaledStroke, paint);
        }
        if (!scaledFill.isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            c.drawPath(scaledFill, paint);
        }
        if (kind == Kind.TABS && !count.isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(textSizePx);
            c.drawText(count, textX, textY, paint);
        }
    }

    private static Path stroke(Kind k) {
        Path p = new Path();
        STROKE_PATHS.put(k, p);
        return p;
    }

    private static Path fill(Kind k) {
        Path p = new Path();
        FILL_PATHS.put(k, p);
        return p;
    }

    private static void poly(Path p, float... pts) {
        p.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]);
    }

    private static void buildCatalog() {
        // Optically centered 45-degree navigation chevrons
        poly(stroke(Kind.BACK), 14.75f, 5.5f, 8.25f, 12f, 14.75f, 18.5f);
        poly(stroke(Kind.FORWARD), 9.25f, 5.5f, 15.75f, 12f, 9.25f, 18.5f);
        poly(stroke(Kind.DOWN), 6.0f, 9.25f, 12.0f, 15.25f, 18.0f, 9.25f);

        // Search / Find: exact tangent join between lens and handle at +45 deg
        Path search = stroke(Kind.SEARCH);
        search.addCircle(10.5f, 10.5f, 5.75f, Path.Direction.CW);
        poly(search, 14.57f, 14.57f, 19.5f, 19.5f);
        STROKE_PATHS.put(Kind.FIND, search);

        // Shield: smooth cubic Bezier silhouette + optically centered checkmark
        Path shield = stroke(Kind.SHIELD);
        shield.moveTo(12f, 3.6f);
        shield.lineTo(18.5f, 6.1f);
        shield.lineTo(18.5f, 11.4f);
        shield.cubicTo(18.5f, 15.6f, 15.7f, 18.8f, 12f, 20.4f);
        shield.cubicTo(8.3f, 18.8f, 5.5f, 15.6f, 5.5f, 11.4f);
        shield.lineTo(5.5f, 6.1f);
        shield.close();
        poly(shield, 9.2f, 12.0f, 11.1f, 13.9f, 15.0f, 9.8f);

        // Tabs badge: 14x14 squircle-proportioned frame matching optical weight of BACK and MENU
        Path tabs = stroke(Kind.TABS);
        tabs.addRoundRect(5.0f, 5.0f, 19.0f, 19.0f, 3.8f, 3.8f, Path.Direction.CW);

        // Menu / More: three balanced filled dots spanning 12dp center-to-center
        Path menu = fill(Kind.MENU);
        menu.addCircle(6.0f, 12f, 1.45f, Path.Direction.CW);
        menu.addCircle(12.0f, 12f, 1.45f, Path.Direction.CW);
        menu.addCircle(18.0f, 12f, 1.45f, Path.Direction.CW);

        // Refresh: textbook 291-deg clockwise arc from 4:05 over top ending at ~1:50 (-35 deg),
        // with open chevron arrowhead cleanly anchored at exact arc endpoint (17.73, 7.99)
        Path refresh = stroke(Kind.REFRESH);
        refresh.addArc(5.0f, 5.0f, 19.0f, 19.0f, 34f, 291f);
        poly(refresh, 17.73f, 4.14f, 17.73f, 7.99f, 13.88f, 7.99f);

        // Stop: crisp rounded square balanced against Refresh
        Path stop = stroke(Kind.STOP);
        stop.addRoundRect(6.25f, 6.25f, 17.75f, 17.75f, 2.5f, 2.5f, Path.Direction.CW);

        // Go: right-pointing arrow
        Path go = stroke(Kind.GO);
        poly(go, 5.25f, 12f, 18.75f, 12f);
        poly(go, 13.25f, 6.5f, 18.75f, 12f, 13.25f, 17.5f);

        // Add: balanced plus
        Path add = stroke(Kind.ADD);
        poly(add, 12f, 5.5f, 12f, 18.5f);
        poly(add, 5.5f, 12f, 18.5f, 12f);

        // Close: crisp 45-deg cross
        Path close = stroke(Kind.CLOSE);
        poly(close, 6.5f, 6.5f, 17.5f, 17.5f);
        poly(close, 17.5f, 6.5f, 6.5f, 17.5f);

        // Star: true regular 5-point geometry (outer R=8.2, inner r=3.55), cleanly closed
        Path star = stroke(Kind.STAR);
        star.moveTo(12.00f, 4.10f);
        star.lineTo(14.09f, 9.43f);
        star.lineTo(19.80f, 9.77f);
        star.lineTo(15.38f, 13.40f);
        star.lineTo(16.82f, 18.93f);
        star.lineTo(12.00f, 15.85f);
        star.lineTo(7.18f, 18.93f);
        star.lineTo(8.62f, 13.40f);
        star.lineTo(4.20f, 9.77f);
        star.lineTo(9.91f, 9.43f);
        star.close();

        // History: mirror-twin CCW arc ending at 10:10 (-145 deg) with zero-gap arrowhead + clock hands
        Path history = stroke(Kind.HISTORY);
        history.addArc(4.75f, 4.75f, 19.25f, 19.25f, 150f, -295f);
        poly(history, 6.06f, 4.29f, 6.06f, 7.84f, 9.61f, 7.84f);
        poly(history, 12f, 8.5f, 12f, 12.25f, 14.7f, 13.85f);

        // Share: exact circumference-to-circumference connectors
        Path share = stroke(Kind.SHARE);
        share.addCircle(6.5f, 12.0f, 2.1f, Path.Direction.CW);
        share.addCircle(17.5f, 6.0f, 2.1f, Path.Direction.CW);
        share.addCircle(17.5f, 18.0f, 2.1f, Path.Direction.CW);
        poly(share, 8.34f, 10.99f, 15.66f, 7.01f);
        poly(share, 8.34f, 13.01f, 15.66f, 16.99f);

        // Download: clean down arrow into soft-cornered tray
        Path download = stroke(Kind.DOWNLOAD);
        poly(download, 12f, 4.5f, 12f, 14.75f);
        poly(download, 7.75f, 10.75f, 12f, 14.75f, 16.25f, 10.75f);
        download.moveTo(5.0f, 15.75f);
        download.lineTo(5.0f, 17.75f);
        download.quadTo(5.0f, 19.5f, 6.75f, 19.5f);
        download.lineTo(17.25f, 19.5f);
        download.quadTo(19.0f, 19.5f, 19.0f, 17.75f);
        download.lineTo(19.0f, 15.75f);

        // Private (Incognito): smooth hat silhouette + connected curved-bridge glasses
        Path priv = stroke(Kind.PRIVATE);
        poly(priv, 4.2f, 10.5f, 19.8f, 10.5f);
        priv.moveTo(6.8f, 10.5f);
        priv.lineTo(8.3f, 5.4f);
        priv.quadTo(8.7f, 4.3f, 9.8f, 4.6f);
        priv.lineTo(12.0f, 5.3f);
        priv.lineTo(14.2f, 4.6f);
        priv.quadTo(15.3f, 4.3f, 15.7f, 5.4f);
        priv.lineTo(17.2f, 10.5f);
        priv.addCircle(8.0f, 16.0f, 2.6f, Path.Direction.CW);
        priv.addCircle(16.0f, 16.0f, 2.6f, Path.Direction.CW);
        priv.moveTo(10.6f, 15.7f);
        priv.quadTo(12.0f, 14.8f, 13.4f, 15.7f);

        // Home: minimalist roof + soft-cornered body + doorway
        Path home = stroke(Kind.HOME);
        poly(home, 4.25f, 10.75f, 12.0f, 4.25f, 19.75f, 10.75f);
        home.moveTo(6.0f, 9.28f);
        home.lineTo(6.0f, 18.25f);
        home.quadTo(6.0f, 19.75f, 7.5f, 19.75f);
        home.lineTo(16.5f, 19.75f);
        home.quadTo(18.0f, 19.75f, 18.0f, 18.25f);
        home.lineTo(18.0f, 9.28f);
        poly(home, 10.0f, 19.75f, 10.0f, 14.5f, 14.0f, 14.5f, 14.0f, 19.75f);

        // Speed / 185Hz gauge: centered 230-deg dial + needle + crisp filled pivot dot
        Path speed = stroke(Kind.SPEED);
        speed.addArc(4.25f, 5.75f, 19.75f, 21.25f, 155f, 230f);
        poly(speed, 12.0f, 14.25f, 16.1f, 9.8f);
        fill(Kind.SPEED).addCircle(12.0f, 14.25f, 1.35f, Path.Direction.CW);

        // Copy: paired rounded sheets with cohesive corner radii
        Path copy = stroke(Kind.COPY);
        copy.addRoundRect(8.5f, 8.5f, 19.5f, 19.5f, 2.4f, 2.4f, Path.Direction.CW);
        copy.moveTo(5.75f, 15.5f);
        copy.quadTo(4.5f, 15.5f, 4.5f, 14.25f);
        copy.lineTo(4.5f, 6.9f);
        copy.quadTo(4.5f, 4.5f, 6.9f, 4.5f);
        copy.lineTo(14.25f, 4.5f);
        copy.quadTo(15.5f, 4.5f, 15.5f, 5.75f);

        // Code / Userscripts: balanced angle brackets + slash
        Path code = stroke(Kind.CODE);
        poly(code, 7.75f, 8.0f, 3.75f, 12.0f, 7.75f, 16.0f);
        poly(code, 16.25f, 8.0f, 20.25f, 12.0f, 16.25f, 16.0f);
        poly(code, 13.75f, 5.75f, 10.25f, 18.25f);

        // Desktop: sleek monitor + stand
        Path desktop = stroke(Kind.DESKTOP);
        desktop.addRoundRect(3.75f, 5.0f, 20.25f, 15.75f, 2.2f, 2.2f, Path.Direction.CW);
        poly(desktop, 12.0f, 15.75f, 12.0f, 19.25f);
        poly(desktop, 8.25f, 19.25f, 15.75f, 19.25f);

        // Font: editorial serif T
        Path font = stroke(Kind.FONT);
        poly(font, 5.5f, 8.25f, 5.5f, 6.0f, 18.5f, 6.0f, 18.5f, 8.25f);
        poly(font, 12.0f, 6.0f, 12.0f, 18.5f);
        poly(font, 9.0f, 18.5f, 15.0f, 18.5f);

        // Image: rounded frame + crisp filled sun dot + wall-anchored landscape
        Path image = stroke(Kind.IMAGE);
        image.addRoundRect(4.0f, 5.0f, 20.0f, 19.0f, 2.5f, 2.5f, Path.Direction.CW);
        poly(image, 4.4f, 16.8f, 9.1f, 12.5f, 12.6f, 15.8f, 15.4f, 13.0f, 19.6f, 16.8f);
        fill(Kind.IMAGE).addCircle(8.5f, 9.1f, 1.15f, Path.Direction.CW);

        // Reader: clean article sheet with generous internal margins
        Path reader = stroke(Kind.READER);
        reader.addRoundRect(5.0f, 4.0f, 19.0f, 20.0f, 2.5f, 2.5f, Path.Direction.CW);
        poly(reader, 8.25f, 8.5f, 15.75f, 8.5f);
        poly(reader, 8.25f, 12.0f, 15.75f, 12.0f);
        poly(reader, 8.25f, 15.5f, 13.25f, 15.5f);

        // Mute: closed speaker cone + crisp 45-deg cross
        Path mute = stroke(Kind.MUTE);
        mute.moveTo(4.0f, 9.5f);
        mute.lineTo(7.5f, 9.5f);
        mute.lineTo(12.0f, 5.75f);
        mute.lineTo(12.0f, 18.25f);
        mute.lineTo(7.5f, 14.5f);
        mute.lineTo(4.0f, 14.5f);
        mute.close();
        poly(mute, 15.6f, 9.8f, 20.0f, 14.2f);
        poly(mute, 20.0f, 9.8f, 15.6f, 14.2f);

        // Unmute: matching speaker cone + true concentric circular acoustic waves
        Path unmute = stroke(Kind.UNMUTE);
        unmute.moveTo(4.0f, 9.5f);
        unmute.lineTo(7.5f, 9.5f);
        unmute.lineTo(12.0f, 5.75f);
        unmute.lineTo(12.0f, 18.25f);
        unmute.lineTo(7.5f, 14.5f);
        unmute.lineTo(4.0f, 14.5f);
        unmute.close();
        unmute.addArc(8.4f, 8.2f, 16.0f, 15.8f, -48f, 96f);
        unmute.addArc(4.9f, 4.7f, 19.5f, 19.3f, -48f, 96f);

        // Pin / Reading bookmark ribbon: soft-top ribbon with crisp chevron notch
        Path pin = stroke(Kind.PIN);
        pin.moveTo(6.5f, 19.5f);
        pin.lineTo(6.5f, 6.25f);
        pin.quadTo(6.5f, 4.25f, 8.5f, 4.25f);
        pin.lineTo(15.5f, 4.25f);
        pin.quadTo(17.5f, 4.25f, 17.5f, 6.25f);
        pin.lineTo(17.5f, 19.5f);
        pin.lineTo(12.0f, 15.75f);
        pin.close();

        // Engine / Globe: exact meridian tangency and equator contacts
        Path engine = stroke(Kind.ENGINE);
        engine.addCircle(12.0f, 12.0f, 7.75f, Path.Direction.CW);
        engine.addOval(8.6f, 4.25f, 15.4f, 19.75f, Path.Direction.CW);
        poly(engine, 4.25f, 12.0f, 19.75f, 12.0f);

        // Element Picker: precision reticle + filled target center dot
        Path picker = stroke(Kind.PICKER);
        picker.addCircle(12.0f, 12.0f, 6.0f, Path.Direction.CW);
        poly(picker, 12.0f, 3.75f, 12.0f, 7.5f);
        poly(picker, 12.0f, 16.5f, 12.0f, 20.25f);
        poly(picker, 3.75f, 12.0f, 7.5f, 12.0f);
        poly(picker, 16.5f, 12.0f, 20.25f, 12.0f);
        fill(Kind.PICKER).addCircle(12.0f, 12.0f, 1.15f, Path.Direction.CW);

        // Dark mode: pure two-circle geometric crescent moon
        Path dark = stroke(Kind.DARK);
        dark.arcTo(4.8f, 4.8f, 19.6f, 19.6f, 10.37f, 249.26f, true);
        dark.arcTo(9.8f, 2.2f, 22.2f, 14.6f, 214.15f, -158.30f, false);
        dark.close();

        // PDF / Print: clean open-slot body so paper emerges without line collisions
        Path pdf = stroke(Kind.PDF);
        poly(pdf, 7.25f, 9.0f, 7.25f, 4.75f, 16.75f, 4.75f, 16.75f, 9.0f);
        pdf.moveTo(7.5f, 16.25f);
        pdf.lineTo(6.0f, 16.25f);
        pdf.quadTo(4.25f, 16.25f, 4.25f, 14.5f);
        pdf.lineTo(4.25f, 10.75f);
        pdf.quadTo(4.25f, 9.0f, 6.0f, 9.0f);
        pdf.lineTo(18.0f, 9.0f);
        pdf.quadTo(19.75f, 9.0f, 19.75f, 10.75f);
        pdf.lineTo(19.75f, 14.5f);
        pdf.quadTo(19.75f, 16.25f, 18.0f, 16.25f);
        pdf.lineTo(16.5f, 16.25f);
        pdf.addRoundRect(7.5f, 13.75f, 16.5f, 19.5f, 1.2f, 1.2f, Path.Direction.CW);

        // Bolt: clean open-waist lightning silhouette
        Path bolt = stroke(Kind.BOLT);
        bolt.moveTo(13.2f, 3.8f);
        bolt.lineTo(6.0f, 13.0f);
        bolt.lineTo(11.6f, 13.0f);
        bolt.lineTo(10.8f, 20.2f);
        bolt.lineTo(18.0f, 11.0f);
        bolt.lineTo(12.4f, 11.0f);
        bolt.close();

        // Gesture: crisp bi-directional horizontal swipe arrows
        Path gesture = stroke(Kind.GESTURE);
        poly(gesture, 4.25f, 12.0f, 19.75f, 12.0f);
        poly(gesture, 8.0f, 8.25f, 4.25f, 12.0f, 8.0f, 15.75f);
        poly(gesture, 16.0f, 8.25f, 19.75f, 12.0f, 16.0f, 15.75f);

        // Check: crisp confirmation mark
        poly(stroke(Kind.CHECK), 5.5f, 12.5f, 9.8f, 16.8f, 18.5f, 7.5f);

        // Palette: artist palette outline with recessed thumb notch + 4 balanced paint dots
        Path palette = stroke(Kind.PALETTE);
        palette.arcTo(4.25f, 4.25f, 19.75f, 19.75f, 90f, 270f, true);
        palette.cubicTo(19.75f, 14.2f, 18.1f, 15.0f, 15.8f, 15.0f);
        palette.cubicTo(14.3f, 15.0f, 14.0f, 16.0f, 14.4f, 17.2f);
        palette.cubicTo(14.8f, 18.5f, 13.8f, 19.75f, 12.0f, 19.75f);
        palette.close();
        Path paletteDots = fill(Kind.PALETTE);
        paletteDots.addCircle(8.2f, 14.6f, 1.1f, Path.Direction.CW);
        paletteDots.addCircle(7.7f, 10.6f, 1.1f, Path.Direction.CW);
        paletteDots.addCircle(10.7f, 7.6f, 1.1f, Path.Direction.CW);
        paletteDots.addCircle(15.0f, 8.5f, 1.1f, Path.Direction.CW);

        // Info: crisp circle + lowercase i with filled dot
        Path info = stroke(Kind.INFO);
        info.addCircle(12.0f, 12.0f, 7.75f, Path.Direction.CW);
        poly(info, 12.0f, 11.3f, 12.0f, 15.8f);
        fill(Kind.INFO).addCircle(12.0f, 8.2f, 1.15f, Path.Direction.CW);

        // Video: rounded player frame with optically centered play triangle
        Path video = stroke(Kind.VIDEO);
        video.addRoundRect(3.75f, 5.5f, 20.25f, 18.5f, 2.8f, 2.8f, Path.Direction.CW);
        video.moveTo(10.0f, 9.2f);
        video.lineTo(15.6f, 12.0f);
        video.lineTo(10.0f, 14.8f);
        video.close();

        // Translate (Babel): crisp 文 glyph on upper-left + A glyph on lower-right
        Path translate = stroke(Kind.TRANSLATE);
        poly(translate, 4.25f, 7.25f, 12.75f, 7.25f);
        poly(translate, 8.5f, 4.5f, 8.5f, 7.25f);
        translate.moveTo(11.2f, 7.25f);
        translate.quadTo(10.2f, 11.4f, 5.2f, 14.2f);
        translate.moveTo(6.4f, 9.4f);
        translate.quadTo(8.5f, 11.8f, 11.6f, 13.6f);
        poly(translate, 12.4f, 19.5f, 16.0f, 10.5f, 19.6f, 19.5f);
        poly(translate, 13.7f, 16.5f, 18.3f, 16.5f);

        // QR: three crisp finder squares + minimal data blocks
        Path qr = stroke(Kind.QR);
        qr.addRoundRect(4.25f, 4.25f, 10.25f, 10.25f, 1.3f, 1.3f, Path.Direction.CW);
        qr.addRoundRect(13.75f, 4.25f, 19.75f, 10.25f, 1.3f, 1.3f, Path.Direction.CW);
        qr.addRoundRect(4.25f, 13.75f, 10.25f, 19.75f, 1.3f, 1.3f, Path.Direction.CW);
        poly(qr, 14.25f, 14.25f, 14.25f, 19.5f);
        poly(qr, 14.25f, 16.5f, 17.5f, 16.5f, 17.5f, 19.5f);
        Path qrFill = fill(Kind.QR);
        qrFill.addCircle(7.25f, 7.25f, 1.05f, Path.Direction.CW);
        qrFill.addCircle(16.75f, 7.25f, 1.05f, Path.Direction.CW);
        qrFill.addCircle(7.25f, 16.75f, 1.05f, Path.Direction.CW);
        qrFill.addCircle(19.0f, 14.4f, 1.0f, Path.Direction.CW);

        // Gallery: layered twin photo frames + mountain silhouette + sun dot
        Path gallery = stroke(Kind.GALLERY);
        gallery.addRoundRect(3.75f, 6.75f, 17.25f, 19.5f, 2.2f, 2.2f, Path.Direction.CW);
        poly(gallery, 4.2f, 17.2f, 8.2f, 13.4f, 11.4f, 16.2f, 13.8f, 14.0f, 16.8f, 17.2f);
        gallery.moveTo(7.25f, 6.75f);
        gallery.lineTo(7.25f, 5.75f);
        gallery.quadTo(7.25f, 4.25f, 8.75f, 4.25f);
        gallery.lineTo(18.75f, 4.25f);
        gallery.quadTo(20.25f, 4.25f, 20.25f, 5.75f);
        gallery.lineTo(20.25f, 15.25f);
        gallery.quadTo(20.25f, 16.75f, 18.75f, 16.75f);
        gallery.lineTo(17.25f, 16.75f);
        fill(Kind.GALLERY).addCircle(7.6f, 10.2f, 1.05f, Path.Direction.CW);

        // Backup: crisp cloud silhouette with centered upward sync arrow
        Path backup = stroke(Kind.BACKUP);
        backup.moveTo(7.0f, 18.5f);
        backup.cubicTo(4.2f, 18.5f, 3.5f, 14.3f, 6.4f, 13.0f);
        backup.cubicTo(6.3f, 8.2f, 12.8f, 5.5f, 15.7f, 9.8f);
        backup.cubicTo(19.6f, 9.6f, 21.0f, 14.2f, 18.8f, 17.1f);
        backup.cubicTo(18.2f, 18.0f, 17.2f, 18.5f, 16.0f, 18.5f);
        poly(backup, 12.0f, 19.25f, 12.0f, 12.0f);
        poly(backup, 9.25f, 14.5f, 12.0f, 11.75f, 14.75f, 14.5f);

        // Unlock: rounded padlock body + open shackle + filled keyhole
        Path unlock = stroke(Kind.UNLOCK);
        unlock.addRoundRect(5.25f, 11.0f, 18.75f, 19.75f, 2.4f, 2.4f, Path.Direction.CW);
        unlock.moveTo(8.5f, 11.0f);
        unlock.lineTo(8.5f, 7.75f);
        unlock.arcTo(8.5f, 4.25f, 15.5f, 11.25f, 180f, 150f, false);
        fill(Kind.UNLOCK).addCircle(12.0f, 15.3f, 1.25f, Path.Direction.CW);

        // DevTools: rounded terminal frame with > prompt and _ cursor
        Path devtools = stroke(Kind.DEVTOOLS);
        devtools.addRoundRect(3.75f, 5.0f, 20.25f, 19.0f, 2.6f, 2.6f, Path.Direction.CW);
        poly(devtools, 7.5f, 9.5f, 10.75f, 12.25f, 7.5f, 15.0f);
        poly(devtools, 12.5f, 15.0f, 16.75f, 15.0f);

        // PWA: standalone app tile with top-right + install mark
        Path pwa = stroke(Kind.PWA);
        pwa.moveTo(12.5f, 5.25f);
        pwa.lineTo(7.25f, 5.25f);
        pwa.quadTo(4.75f, 5.25f, 4.75f, 7.75f);
        pwa.lineTo(4.75f, 16.75f);
        pwa.quadTo(4.75f, 19.25f, 7.25f, 19.25f);
        pwa.lineTo(16.25f, 19.25f);
        pwa.quadTo(18.75f, 19.25f, 18.75f, 16.75f);
        pwa.lineTo(18.75f, 11.75f);
        poly(pwa, 17.5f, 4.25f, 17.5f, 9.75f);
        poly(pwa, 14.75f, 7.0f, 20.25f, 7.0f);

        // Sun/Moon: balanced day-night contrast dial (outer ring + filled right semicircle + sun rays)
        Path sunMoon = stroke(Kind.SUN_MOON);
        sunMoon.addCircle(12.0f, 12.0f, 4.8f, Path.Direction.CW);
        poly(sunMoon, 12.0f, 4.0f, 12.0f, 5.2f);
        poly(sunMoon, 12.0f, 18.8f, 12.0f, 20.0f);
        poly(sunMoon, 4.0f, 12.0f, 5.2f, 12.0f);
        poly(sunMoon, 6.3f, 6.3f, 7.2f, 7.2f);
        poly(sunMoon, 6.3f, 17.7f, 7.2f, 16.8f);
        Path smFill = fill(Kind.SUN_MOON);
        smFill.arcTo(7.2f, 7.2f, 16.8f, 16.8f, -90f, 180f, true);
        smFill.close();

        // Grid: crisp 2x2 rounded tab cards
        Path grid = stroke(Kind.GRID);
        grid.addRoundRect(4.5f, 4.5f, 10.5f, 10.5f, 1.8f, 1.8f, Path.Direction.CW);
        grid.addRoundRect(13.5f, 4.5f, 19.5f, 10.5f, 1.8f, 1.8f, Path.Direction.CW);
        grid.addRoundRect(4.5f, 13.5f, 10.5f, 19.5f, 1.8f, 1.8f, Path.Direction.CW);
        grid.addRoundRect(13.5f, 13.5f, 19.5f, 19.5f, 1.8f, 1.8f, Path.Direction.CW);

        // Sniff: media radar arcs + directional sweep needle + center node
        Path sniff = stroke(Kind.SNIFF);
        sniff.addArc(4.25f, 4.25f, 19.75f, 19.75f, -55f, 290f);
        sniff.addArc(7.75f, 7.75f, 16.25f, 16.25f, -35f, 250f);
        poly(sniff, 12.0f, 12.0f, 17.4f, 6.6f);
        fill(Kind.SNIFF).addCircle(12.0f, 12.0f, 1.35f, Path.Direction.CW);

        // Undo: clean counter-clockwise curved return arrow
        Path undo = stroke(Kind.UNDO);
        poly(undo, 8.75f, 5.25f, 5.25f, 8.75f, 8.75f, 12.25f);
        undo.moveTo(5.75f, 8.75f);
        undo.lineTo(14.0f, 8.75f);
        undo.arcTo(9.5f, 8.75f, 18.75f, 18.0f, -90f, 180f, false);
        undo.lineTo(9.0f, 18.0f);

        // Flash Command: rounded keycap housing a crisp quick-command slash '/'
        Path flashCmd = stroke(Kind.FLASH_CMD);
        flashCmd.addRoundRect(4.5f, 4.5f, 19.5f, 19.5f, 3.2f, 3.2f, Path.Direction.CW);
        poly(flashCmd, 14.0f, 8.0f, 10.0f, 16.0f);

        // Speaker: crisp speaker cone + twin concentric broadcast waves
        Path speaker = stroke(Kind.SPEAKER);
        speaker.moveTo(4.0f, 9.5f);
        speaker.lineTo(7.5f, 9.5f);
        speaker.lineTo(12.0f, 5.75f);
        speaker.lineTo(12.0f, 18.25f);
        speaker.lineTo(7.5f, 14.5f);
        speaker.lineTo(4.0f, 14.5f);
        speaker.close();
        speaker.addArc(8.4f, 8.2f, 16.0f, 15.8f, -48f, 96f);
        speaker.addArc(4.9f, 4.7f, 19.5f, 19.3f, -48f, 96f);

        // Peek: clean eye silhouette + centered pupil ring
        Path peek = stroke(Kind.PEEK);
        peek.moveTo(3.5f, 12.0f);
        peek.cubicTo(6.2f, 7.2f, 17.8f, 7.2f, 20.5f, 12.0f);
        peek.cubicTo(17.8f, 16.8f, 6.2f, 16.8f, 3.5f, 12.0f);
        peek.close();
        peek.addCircle(12.0f, 12.0f, 2.75f, Path.Direction.CW);
        fill(Kind.PEEK).addCircle(12.0f, 12.0f, 1.0f, Path.Direction.CW);

        // Timer: stopwatch body + crown + angled hand + pivot dot
        Path timer = stroke(Kind.TIMER);
        timer.addCircle(12.0f, 13.25f, 6.75f, Path.Direction.CW);
        poly(timer, 9.5f, 4.0f, 14.5f, 4.0f);
        poly(timer, 12.0f, 4.0f, 12.0f, 6.5f);
        poly(timer, 17.0f, 8.25f, 18.75f, 6.5f);
        poly(timer, 12.0f, 13.25f, 15.0f, 10.25f);
        fill(Kind.TIMER).addCircle(12.0f, 13.25f, 1.15f, Path.Direction.CW);

        // Settings: precision 6-tooth gear ring + optical center bore
        Path settings = stroke(Kind.SETTINGS);
        settings.addCircle(12.0f, 12.0f, 2.85f, Path.Direction.CW);
        settings.addCircle(12.0f, 12.0f, 6.45f, Path.Direction.CW);
        poly(settings, 12.0f, 3.35f, 12.0f, 5.55f);
        poly(settings, 12.0f, 18.45f, 12.0f, 20.65f);
        poly(settings, 3.35f, 12.0f, 5.55f, 12.0f);
        poly(settings, 18.45f, 12.0f, 20.65f, 12.0f);
        poly(settings, 5.9f, 5.9f, 7.45f, 7.45f);
        poly(settings, 16.55f, 16.55f, 18.1f, 18.1f);
        poly(settings, 18.1f, 5.9f, 16.55f, 7.45f);
        poly(settings, 7.45f, 16.55f, 5.9f, 18.1f);

        // Trash: clean bin silhouette + lid + twin interior flutes
        Path trash = stroke(Kind.TRASH);
        poly(trash, 4.75f, 6.75f, 19.25f, 6.75f);
        poly(trash, 9.25f, 6.75f, 9.25f, 4.75f, 14.75f, 4.75f, 14.75f, 6.75f);
        trash.moveTo(6.5f, 6.75f);
        trash.lineTo(7.4f, 18.25f);
        trash.quadTo(7.55f, 19.75f, 9.05f, 19.75f);
        trash.lineTo(14.95f, 19.75f);
        trash.quadTo(16.45f, 19.75f, 16.6f, 18.25f);
        trash.lineTo(17.5f, 6.75f);
        poly(trash, 10.25f, 10.25f, 10.25f, 16.25f);
        poly(trash, 13.75f, 10.25f, 13.75f, 16.25f);

        // Edit: diagonal pencil + crisp nib + baseline
        Path edit = stroke(Kind.EDIT);
        edit.moveTo(15.2f, 4.6f);
        edit.lineTo(19.4f, 8.8f);
        edit.lineTo(9.0f, 19.2f);
        edit.lineTo(4.6f, 19.4f);
        edit.lineTo(4.8f, 15.0f);
        edit.close();
        poly(edit, 13.2f, 6.6f, 17.4f, 10.8f);

        // Up: optically balanced upward chevron
        poly(stroke(Kind.UP), 6.0f, 14.75f, 12.0f, 8.75f, 18.0f, 14.75f);
    }
}

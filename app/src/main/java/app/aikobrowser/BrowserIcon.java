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
 * Designed for 185Hz (5.4ms) frame budgets.
 */
final class BrowserIcon extends View {
    enum Kind {
        BACK, FORWARD, SEARCH, SHIELD, TABS, MENU, REFRESH, STOP, GO, ADD, CLOSE,
        STAR, HISTORY, FIND, SHARE, DOWNLOAD, PRIVATE, HOME, SPEED, COPY, CODE,
        DESKTOP, FONT, IMAGE, READER, MUTE, UNMUTE, PIN, ENGINE, PICKER, DARK,
        PDF, BOLT, GESTURE, CHECK
    }

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
        strokeWidthPx = 1.75f * scale;

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
        poly(stroke(Kind.BACK), 14.5f, 5, 7.5f, 12, 14.5f, 19);
        poly(stroke(Kind.FORWARD), 9.5f, 5, 16.5f, 12, 9.5f, 19);

        Path search = stroke(Kind.SEARCH);
        search.addCircle(10.5f, 10.5f, 5.5f, Path.Direction.CW);
        poly(search, 15, 15, 20, 20);
        STROKE_PATHS.put(Kind.FIND, search);

        Path shield = stroke(Kind.SHIELD);
        poly(shield, 12, 3.6f, 19, 6.5f, 18.6f, 13.5f, 16.8f, 17, 12, 20.2f, 7.2f, 17, 5.4f, 13.5f, 5, 6.5f, 12, 3.6f);
        poly(shield, 9, 12.2f, 11, 14.3f, 15.1f, 10.1f);

        Path tabs = stroke(Kind.TABS);
        tabs.addRoundRect(4.5f, 4.5f, 19.5f, 19.5f, 4.2f, 4.2f, Path.Direction.CW);

        Path menu = fill(Kind.MENU);
        for (int x = 5; x <= 19; x += 7) menu.addCircle(x, 12, 1.55f, Path.Direction.CW);

        Path refresh = stroke(Kind.REFRESH);
        refresh.addArc(5, 5, 19, 19, -45, 285);
        poly(refresh, 17, 3, 17, 8, 12, 8);

        Path stop = stroke(Kind.STOP);
        stop.addRoundRect(6.5f, 6.5f, 17.5f, 17.5f, 2, 2, Path.Direction.CW);

        Path go = stroke(Kind.GO);
        poly(go, 5, 12, 19, 12);
        poly(go, 13, 6, 19, 12, 13, 18);

        Path add = stroke(Kind.ADD);
        poly(add, 12, 5, 12, 19);
        poly(add, 5, 12, 19, 12);

        Path close = stroke(Kind.CLOSE);
        poly(close, 7, 7, 17, 17);
        poly(close, 17, 7, 7, 17);

        poly(stroke(Kind.STAR),
                12, 3, 14.8f, 8.7f, 21, 9.7f, 16.5f, 14.1f, 17.6f, 20.4f,
                12, 17.4f, 6.4f, 20.4f, 7.5f, 14.1f, 3, 9.7f, 9.2f, 8.7f, 12, 3);

        Path history = stroke(Kind.HISTORY);
        history.addArc(4.5f, 4.5f, 19.5f, 19.5f, -150, 310);
        poly(history, 4, 4.9f, 4, 10, 9.2f, 10);
        poly(history, 12, 8, 12, 12.5f, 15, 14);

        Path share = stroke(Kind.SHARE);
        share.addCircle(6, 12, 2, Path.Direction.CW);
        share.addCircle(18, 5.5f, 2, Path.Direction.CW);
        share.addCircle(18, 18.5f, 2, Path.Direction.CW);
        poly(share, 7.9f, 11, 16, 6.5f);
        poly(share, 7.9f, 13, 16, 17.5f);

        Path download = stroke(Kind.DOWNLOAD);
        poly(download, 12, 4, 12, 15);
        poly(download, 7.5f, 10.5f, 12, 15, 16.5f, 10.5f);
        poly(download, 5, 16, 5, 19, 19, 19, 19, 16);

        Path priv = stroke(Kind.PRIVATE);
        poly(priv, 4, 10, 20, 10);
        poly(priv, 7, 9.8f, 8.5f, 4, 12, 5.5f, 15.5f, 4, 17, 9.8f);
        priv.addCircle(8, 15.5f, 2.7f, Path.Direction.CW);
        priv.addCircle(16, 15.5f, 2.7f, Path.Direction.CW);
        poly(priv, 11, 15.5f, 13, 15.5f);

        Path home = stroke(Kind.HOME);
        poly(home, 3.5f, 11, 12, 4, 20.5f, 11, 18.3f, 11, 18.3f, 20, 5.7f, 20, 5.7f, 11, 3.5f, 11);
        poly(home, 10, 20, 10, 14, 14, 14, 14, 20);

        Path speed = stroke(Kind.SPEED);
        speed.addArc(4, 5, 20, 21, 164, 212);
        poly(speed, 12, 14, 16.2f, 9);
        speed.addCircle(12, 14, 1.1f, Path.Direction.CW);

        Path copy = stroke(Kind.COPY);
        copy.addRoundRect(8, 8, 20, 20, 2, 2, Path.Direction.CW);
        poly(copy, 16, 5, 16, 4, 4, 4, 4, 16, 5, 16);

        Path code = stroke(Kind.CODE);
        poly(code, 7.5f, 7, 3, 12, 7.5f, 17);
        poly(code, 16.5f, 7, 21, 12, 16.5f, 17);
        poly(code, 14, 5.5f, 10, 18.5f);

        Path desktop = stroke(Kind.DESKTOP);
        desktop.addRoundRect(3, 5, 21, 16, 2, 2, Path.Direction.CW);
        poly(desktop, 12, 16, 12, 20);
        poly(desktop, 8, 20, 16, 20);

        Path font = stroke(Kind.FONT);
        poly(font, 4, 7, 20, 7);
        poly(font, 12, 7, 12, 19);
        poly(font, 9, 19, 15, 19);

        Path image = stroke(Kind.IMAGE);
        image.addRoundRect(3, 5, 21, 19, 2, 2, Path.Direction.CW);
        image.addCircle(8, 9, 1, Path.Direction.CW);
        poly(image, 4, 18, 10, 13, 13, 16, 16, 12, 20, 17);

        // New flagship icons
        Path reader = stroke(Kind.READER);
        reader.addRoundRect(4.5f, 3.5f, 19.5f, 20.5f, 2.2f, 2.2f, Path.Direction.CW);
        poly(reader, 8, 8, 16, 8);
        poly(reader, 8, 12, 16, 12);
        poly(reader, 8, 16, 13.5f, 16);

        Path mute = stroke(Kind.MUTE);
        poly(mute, 4, 9.5f, 8, 9.5f, 12.5f, 5.5f, 12.5f, 18.5f, 8, 14.5f, 4, 14.5f, 4, 9.5f);
        poly(mute, 16, 9, 20.5f, 15);
        poly(mute, 20.5f, 9, 16, 15);

        Path unmute = stroke(Kind.UNMUTE);
        poly(unmute, 4, 9.5f, 8, 9.5f, 12.5f, 5.5f, 12.5f, 18.5f, 8, 14.5f, 4, 14.5f, 4, 9.5f);
        unmute.addArc(13, 8.5f, 18, 15.5f, -55, 110);
        unmute.addArc(14.5f, 6, 21, 18, -55, 110);

        Path pin = stroke(Kind.PIN);
        poly(pin, 6, 4.5f, 18, 4.5f, 18, 19.5f, 12, 15.5f, 6, 19.5f, 6, 4.5f);

        Path engine = stroke(Kind.ENGINE);
        engine.addCircle(12, 12, 8.2f, Path.Direction.CW);
        engine.addOval(8.4f, 3.8f, 15.6f, 20.2f, Path.Direction.CW);
        poly(engine, 4.2f, 12, 19.8f, 12);

        Path picker = stroke(Kind.PICKER);
        picker.addCircle(12, 12, 6.2f, Path.Direction.CW);
        poly(picker, 12, 3, 12, 7.2f);
        poly(picker, 12, 16.8f, 12, 21);
        poly(picker, 3, 12, 7.2f, 12);
        poly(picker, 16.8f, 12, 21, 12);

        Path dark = stroke(Kind.DARK);
        dark.moveTo(18.5f, 14.2f);
        dark.cubicTo(17.2f, 17.8f, 13.4f, 20.1f, 9.6f, 19.2f);
        dark.cubicTo(5.8f, 18.3f, 3.6f, 14.4f, 4.7f, 10.6f);
        dark.cubicTo(5.6f, 7.4f, 8.4f, 5.2f, 11.7f, 5.0f);
        dark.cubicTo(10.0f, 8.0f, 10.7f, 12.0f, 13.5f, 13.9f);
        dark.cubicTo(15.0f, 14.9f, 16.9f, 15.0f, 18.5f, 14.2f);

        Path pdf = stroke(Kind.PDF);
        poly(pdf, 6, 9, 6, 4.5f, 18, 4.5f, 18, 9);
        pdf.addRoundRect(4, 9, 20, 16.5f, 2, 2, Path.Direction.CW);
        poly(pdf, 7.5f, 15, 7.5f, 19.5f, 16.5f, 19.5f, 16.5f, 15);

        Path bolt = stroke(Kind.BOLT);
        poly(bolt, 13.5f, 3.5f, 6.5f, 13, 12, 13, 10.5f, 20.5f, 17.5f, 11, 12, 11, 13.5f, 3.5f);

        Path gesture = stroke(Kind.GESTURE);
        poly(gesture, 4.5f, 12, 19.5f, 12);
        poly(gesture, 8.5f, 8, 4.5f, 12, 8.5f, 16);
        poly(gesture, 15.5f, 8, 19.5f, 12, 15.5f, 16);

        poly(stroke(Kind.CHECK), 5.5f, 12.5f, 10, 17, 18.5f, 7.5f);
    }
}

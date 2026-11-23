package app.aikobrowser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** Monochrome controls with a common 24 dp drawing grid and native touch feedback. */
final class BrowserIcon extends View {
    enum Kind { BACK, FORWARD, SEARCH, SHIELD, TABS, MENU, REFRESH, STOP, GO, ADD, CLOSE,
                STAR, HISTORY, FIND, SHARE, DOWNLOAD, PRIVATE, HOME, SPEED, COPY, CODE, DESKTOP, FONT, IMAGE }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private Kind kind;
    private int tint = MainActivity.TEXT;
    private String count = "";

    BrowserIcon(Context context, Kind kind) {
        super(context);
        this.kind = kind;
        density = getResources().getDisplayMetrics().density;
        paint.setStrokeWidth(1.75f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
        String next = Integer.toString(value);
        if (!count.equals(next)) {
            count = next;
            invalidate();
        }
    }

    @Override protected void onDraw(Canvas c) {
        float grid = Math.min(Math.min(getWidth(), getHeight()), 23 * density);
        c.save();
        c.translate((getWidth() - grid) / 2, (getHeight() - grid) / 2);
        c.scale(grid / 24, grid / 24);
        paint.setColor(tint);
        paint.setStyle(Paint.Style.STROKE);
        switch (kind) {
            case BACK: line(c, 14.5f, 5, 7.5f, 12, 14.5f, 19); break;
            case FORWARD: line(c, 9.5f, 5, 16.5f, 12, 9.5f, 19); break;
            case SEARCH: case FIND:
                c.drawCircle(10.5f, 10.5f, 5.5f, paint); line(c, 15, 15, 20, 20); break;
            case SHIELD:
                line(c, 12, 3.8f, 19, 6.6f, 18.6f, 13.5f, 16.8f, 17, 12, 20.1f, 7.2f, 17,
                        5.4f, 13.5f, 5, 6.6f, 12, 3.8f);
                line(c, 9, 12.2f, 11, 14.3f, 15.1f, 10.1f); break;
            case TABS:
                c.drawRoundRect(4.5f, 4.5f, 19.5f, 19.5f, 4, 4, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(count.length() == 2 ? 8 : 9.5f);
                c.drawText(count, 12, 12 - (paint.ascent() + paint.descent()) / 2, paint);
                break;
            case MENU:
                paint.setStyle(Paint.Style.FILL);
                for (int x = 5; x <= 19; x += 7) c.drawCircle(x, 12, 1.5f, paint);
                break;
            case REFRESH:
                c.drawArc(5, 5, 19, 19, -45, 285, false, paint);
                line(c, 17, 3, 17, 8, 12, 8); break;
            case STOP:
                c.drawRoundRect(6.5f, 6.5f, 17.5f, 17.5f, 2, 2, paint); break;
            case GO:
                line(c, 5, 12, 19, 12); line(c, 13, 6, 19, 12, 13, 18); break;
            case ADD:
                line(c, 12, 5, 12, 19); line(c, 5, 12, 19, 12); break;
            case CLOSE:
                line(c, 7, 7, 17, 17); line(c, 17, 7, 7, 17); break;
            case STAR:
                line(c, 12, 3, 14.8f, 8.7f, 21, 9.7f, 16.5f, 14.1f, 17.6f, 20.4f,
                        12, 17.4f, 6.4f, 20.4f, 7.5f, 14.1f, 3, 9.7f, 9.2f, 8.7f, 12, 3); break;
            case HISTORY:
                c.drawArc(4.5f, 4.5f, 19.5f, 19.5f, -150, 310, false, paint);
                line(c, 4, 4.9f, 4, 10, 9.2f, 10);
                line(c, 12, 8, 12, 12.5f, 15, 14); break;
            case SHARE:
                c.drawCircle(6, 12, 2, paint); c.drawCircle(18, 5.5f, 2, paint);
                c.drawCircle(18, 18.5f, 2, paint);
                line(c, 7.9f, 11, 16, 6.5f); line(c, 7.9f, 13, 16, 17.5f); break;
            case DOWNLOAD:
                line(c, 12, 4, 12, 15); line(c, 7.5f, 10.5f, 12, 15, 16.5f, 10.5f);
                line(c, 5, 16, 5, 19, 19, 19, 19, 16); break;
            case PRIVATE:
                line(c, 4, 10, 20, 10); line(c, 7, 9.8f, 8.5f, 4, 12, 5.5f, 15.5f, 4, 17, 9.8f);
                c.drawCircle(8, 15.5f, 2.7f, paint); c.drawCircle(16, 15.5f, 2.7f, paint);
                line(c, 11, 15.5f, 13, 15.5f); break;
            case HOME:
                line(c, 3.5f, 11, 12, 4, 20.5f, 11, 18.3f, 11, 18.3f, 20, 5.7f, 20, 5.7f, 11, 3.5f, 11);
                line(c, 10, 20, 10, 14, 14, 14, 14, 20); break;
            case SPEED:
                c.drawArc(4, 5, 20, 21, 164, 212, false, paint);
                line(c, 12, 14, 16, 9); c.drawCircle(12, 14, 1, paint); break;
            case COPY:
                c.drawRoundRect(8, 8, 20, 20, 2, 2, paint);
                line(c, 16, 5, 16, 4, 4, 4, 4, 16, 5, 16); break;
            case CODE:
                line(c, 7.5f, 7, 3, 12, 7.5f, 17);
                line(c, 16.5f, 7, 21, 12, 16.5f, 17);
                line(c, 14, 5.5f, 10, 18.5f); break;
            case DESKTOP:
                c.drawRoundRect(3, 5, 21, 16, 2, 2, paint);
                line(c, 12, 16, 12, 20); line(c, 8, 20, 16, 20); break;
            case FONT:
                line(c, 4, 7, 20, 7); line(c, 12, 7, 12, 19); line(c, 9, 19, 15, 19); break;
            case IMAGE:
                c.drawRoundRect(3, 5, 21, 19, 2, 2, paint);
                c.drawCircle(8, 9, 1, paint);
                line(c, 4, 18, 10, 13, 13, 16, 16, 12, 20, 17); break;
            default: break;
        }
        c.restore();
    }

    private void line(Canvas c, float... points) {
        path.rewind();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        c.drawPath(path, paint);
    }
}

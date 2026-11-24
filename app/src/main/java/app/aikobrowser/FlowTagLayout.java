package app.aikobrowser;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Clean left-to-right wrapping ViewGroup for variable-width pills, chips, and badges.
 * Supports EXACTLY / AT_MOST / UNSPECIFIED measure specs, per-child MarginLayoutParams,
 * vertical row centering, optional maxLines clamping, and RTL layout mirroring.
 * Zero per-frame draw allocations; safe for 185Hz UI sheets.
 */
public class FlowTagLayout extends ViewGroup {
    private int hGapPx;
    private int vGapPx;
    private int maxLines = Integer.MAX_VALUE;

    public FlowTagLayout(Context ctx) {
        this(ctx, Math.round(6f * ctx.getResources().getDisplayMetrics().density),
                Math.round(6f * ctx.getResources().getDisplayMetrics().density));
    }

    public FlowTagLayout(Context ctx, int hGapPx, int vGapPx) {
        super(ctx);
        this.hGapPx = Math.max(0, hGapPx);
        this.vGapPx = Math.max(0, vGapPx);
    }

    public void setGap(int hGapPx, int vGapPx) {
        int nh = Math.max(0, hGapPx);
        int nv = Math.max(0, vGapPx);
        if (this.hGapPx != nh || this.vGapPx != nv) {
            this.hGapPx = nh;
            this.vGapPx = nv;
            requestLayout();
        }
    }

    public int getHorizontalGap() {
        return hGapPx;
    }

    public int getVerticalGap() {
        return vGapPx;
    }

    public void setMaxLines(int maxLines) {
        int next = maxLines <= 0 ? Integer.MAX_VALUE : maxLines;
        if (this.maxLines != next) {
            this.maxLines = next;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int padLeft = getPaddingLeft();
        int padRight = getPaddingRight();
        int padTop = getPaddingTop();
        int padBottom = getPaddingBottom();

        boolean constrainWidth = widthMode != MeasureSpec.UNSPECIFIED;
        int availWidth = constrainWidth ? Math.max(0, widthSize - padLeft - padRight) : Integer.MAX_VALUE;

        int childWidthSpec = constrainWidth
                ? MeasureSpec.makeMeasureSpec(availWidth, MeasureSpec.AT_MOST)
                : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int childHeightSpec = heightMode == MeasureSpec.UNSPECIFIED
                ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                : MeasureSpec.makeMeasureSpec(Math.max(0, heightSize - padTop - padBottom), MeasureSpec.AT_MOST);

        int lineWidth = 0;
        int lineHeight = 0;
        int maxUsedWidth = 0;
        int totalHeight = 0;
        int lineCount = 0;
        boolean hasAnyOnLine = false;

        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;

            LayoutParams lp = child.getLayoutParams();
            int mLeft = 0, mRight = 0, mTop = 0, mBottom = 0;
            if (lp instanceof MarginLayoutParams) {
                MarginLayoutParams mlp = (MarginLayoutParams) lp;
                mLeft = mlp.leftMargin;
                mRight = mlp.rightMargin;
                mTop = mlp.topMargin;
                mBottom = mlp.bottomMargin;
            }

            int cwSpec = resolveChildSpec(childWidthSpec, mLeft + mRight, lp != null ? lp.width : LayoutParams.WRAP_CONTENT);
            int chSpec = resolveChildSpec(childHeightSpec, mTop + mBottom, lp != null ? lp.height : LayoutParams.WRAP_CONTENT);
            child.measure(cwSpec, chSpec);

            int cw = child.getMeasuredWidth() + mLeft + mRight;
            int ch = child.getMeasuredHeight() + mTop + mBottom;

            int needed = hasAnyOnLine ? (lineWidth + hGapPx + cw) : cw;
            if (constrainWidth && hasAnyOnLine && needed > availWidth) {
                // Commit current line and start a new one
                maxUsedWidth = Math.max(maxUsedWidth, lineWidth);
                totalHeight += lineHeight;
                lineCount++;
                if (lineCount >= maxLines) {
                    lineWidth = 0;
                    lineHeight = 0;
                    hasAnyOnLine = false;
                    break;
                }
                totalHeight += vGapPx;
                lineWidth = cw;
                lineHeight = ch;
                hasAnyOnLine = true;
            } else {
                lineWidth = needed;
                lineHeight = Math.max(lineHeight, ch);
                hasAnyOnLine = true;
            }
        }

        if (hasAnyOnLine) {
            maxUsedWidth = Math.max(maxUsedWidth, lineWidth);
            totalHeight += lineHeight;
        }

        int desiredW = Math.max(getSuggestedMinimumWidth(), maxUsedWidth + padLeft + padRight);
        int desiredH = Math.max(getSuggestedMinimumHeight(), totalHeight + padTop + padBottom);

        int finalW;
        if (widthMode == MeasureSpec.EXACTLY) {
            finalW = widthSize;
        } else if (widthMode == MeasureSpec.AT_MOST) {
            finalW = Math.min(desiredW, widthSize);
        } else {
            finalW = desiredW;
        }

        int finalH;
        if (heightMode == MeasureSpec.EXACTLY) {
            finalH = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            finalH = Math.min(desiredH, heightSize);
        } else {
            finalH = desiredH;
        }

        setMeasuredDimension(finalW, finalH);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int padLeft = getPaddingLeft();
        int padRight = getPaddingRight();
        int padTop = getPaddingTop();
        int availWidth = Math.max(0, width - padLeft - padRight);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;

        int count = getChildCount();
        int y = padTop;
        int lineStart = 0;
        int lineIndex = 0;

        while (lineStart < count) {
            int lineWidth = 0;
            int lineHeight = 0;
            int visibleInLine = 0;
            int lineEnd = lineStart;

            for (int i = lineStart; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    lineEnd = i + 1;
                    continue;
                }
                int cw = childTotalWidth(child);
                int ch = childTotalHeight(child);
                int nextWidth = visibleInLine == 0 ? cw : (lineWidth + hGapPx + cw);
                if (visibleInLine > 0 && nextWidth > availWidth) {
                    break;
                }
                lineWidth = nextWidth;
                lineHeight = Math.max(lineHeight, ch);
                visibleInLine++;
                lineEnd = i + 1;
            }

            if (visibleInLine == 0) break;

            if (lineIndex >= maxLines) {
                for (int i = lineStart; i < count; i++) {
                    View child = getChildAt(i);
                    if (child.getVisibility() != GONE) {
                        child.layout(0, 0, 0, 0);
                    }
                }
                break;
            }

            int cursorX = rtl ? (width - padRight) : padLeft;
            for (int i = lineStart; i < lineEnd; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;

                LayoutParams lp = child.getLayoutParams();
                int mLeft = 0, mRight = 0, mTop = 0, mBottom = 0;
                if (lp instanceof MarginLayoutParams) {
                    MarginLayoutParams mlp = (MarginLayoutParams) lp;
                    mLeft = mlp.leftMargin;
                    mRight = mlp.rightMargin;
                    mTop = mlp.topMargin;
                    mBottom = mlp.bottomMargin;
                }

                int mw = child.getMeasuredWidth();
                int mh = child.getMeasuredHeight();
                int totalH = mh + mTop + mBottom;
                int topOffset = y + ((lineHeight - totalH) / 2) + mTop;

                if (rtl) {
                    int childRight = cursorX - mRight;
                    int childLeft = childRight - mw;
                    child.layout(childLeft, topOffset, childRight, topOffset + mh);
                    cursorX = childLeft - mLeft - hGapPx;
                } else {
                    int childLeft = cursorX + mLeft;
                    int childRight = childLeft + mw;
                    child.layout(childLeft, topOffset, childRight, topOffset + mh);
                    cursorX = childRight + mRight + hGapPx;
                }
            }

            y += lineHeight + vGapPx;
            lineIndex++;
            lineStart = lineEnd;
        }
    }

    private static int resolveChildSpec(int parentSpec, int margin, int childDim) {
        int parentMode = MeasureSpec.getMode(parentSpec);
        int parentSize = Math.max(0, MeasureSpec.getSize(parentSpec) - margin);
        if (childDim >= 0) {
            return MeasureSpec.makeMeasureSpec(childDim, MeasureSpec.EXACTLY);
        }
        if (childDim == LayoutParams.MATCH_PARENT && parentMode != MeasureSpec.UNSPECIFIED) {
            return MeasureSpec.makeMeasureSpec(parentSize, MeasureSpec.AT_MOST);
        }
        if (parentMode == MeasureSpec.UNSPECIFIED) {
            return MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        }
        return MeasureSpec.makeMeasureSpec(parentSize, MeasureSpec.AT_MOST);
    }

    private static int childTotalWidth(View child) {
        LayoutParams lp = child.getLayoutParams();
        if (lp instanceof MarginLayoutParams) {
            MarginLayoutParams mlp = (MarginLayoutParams) lp;
            return child.getMeasuredWidth() + mlp.leftMargin + mlp.rightMargin;
        }
        return child.getMeasuredWidth();
    }

    private static int childTotalHeight(View child) {
        LayoutParams lp = child.getLayoutParams();
        if (lp instanceof MarginLayoutParams) {
            MarginLayoutParams mlp = (MarginLayoutParams) lp;
            return child.getMeasuredHeight() + mlp.topMargin + mlp.bottomMargin;
        }
        return child.getMeasuredHeight();
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams ? new MarginLayoutParams((MarginLayoutParams) p)
                : new MarginLayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }
}

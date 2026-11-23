package app.aikobrowser;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class DownloadPanel {
    private static final int CANVAS = 0xFF0B0D12;
    private static final int SURFACE = 0xFF131722;
    private static final int ELEVATED = 0xFF1B2130;
    private static final int PILL = 0xFF21283B;
    private static final int STROKE = 0x24FFFFFF;
    private static final int HANDLE = 0x38FFFFFF;
    private static final int TEXT = 0xFFF3F6FC;
    private static final int MUTED = 0xFF9AA5BD;
    private static final int ACCENT = 0xFF7C9BFF;
    private static final int SUCCESS = 0xFF4ADE80;
    private static final int WARN = 0xFFFBBF24;
    private static final int TRACK = 0xFF242D42;
    private static final int DONE_SEGMENT = 0xFF4C63B6;
    private static final int RIPPLE = 0x307C9BFF;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final MainActivity activity;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::render;
    private Dialog dialog;
    private TextView file, status, metric, rate, remaining, section, details, priority, pause, cancel, finish;
    private View metricsRow, segmentCard;
    private ProgressBar overall;
    private MapView map;
    private ListView lanes;
    private SegmentAdapter adapter;
    private DownloadService.Job lastJob;
    private long lastTime, lastBytes;
    private double speed;
    private int selected = -1;
    private int lastProgress = -1;
    private boolean lastIndeterminate, lastFinishedTint, lastYieldTint;
    private Boolean lastBrowserFirst;

    DownloadPanel(MainActivity activity) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    void show() {
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(activity);
        card.setPadding(dp(20), dp(10), dp(20), dp(18));
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(sheetBackground());

        // Centered drag handle pill
        View handle = new View(activity);
        handle.setBackground(pill(HANDLE, 2, 0));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(38), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(12);
        card.addView(handle, handleLp);

        // Header row: title + priority toggle pill
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(-1, -2);
        headerLp.bottomMargin = dp(14);
        card.addView(header, headerLp);

        TextView title = label("下载", 20, TEXT);
        title.setTypeface(MEDIUM, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        priority = label("", 12, SUCCESS);
        priority.setTypeface(MEDIUM);
        priority.setGravity(Gravity.CENTER);
        priority.setPadding(dp(12), dp(6), dp(12), dp(6));
        priority.setBackground(ripplePill(PILL, 16, STROKE));
        priority.setOnClickListener(v -> {
            activity.toggleTransferPriority();
            render();
        });
        header.addView(priority, new LinearLayout.LayoutParams(-2, dp(32)));

        // Primary job status card
        LinearLayout infoCard = new LinearLayout(activity);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        infoCard.setBackground(pill(ELEVATED, 16, STROKE));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.bottomMargin = dp(12);
        card.addView(infoCard, infoLp);

        file = label("", 15, TEXT);
        file.setTypeface(MEDIUM);
        file.setSingleLine(true);
        file.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        infoCard.addView(file, new LinearLayout.LayoutParams(-1, -2));

        status = label("", 12, MUTED);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(4);
        infoCard.addView(status, statusLp);

        overall = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        overall.setMax(1000);
        overall.setProgressTintList(ColorStateList.valueOf(ACCENT));
        overall.setProgressBackgroundTintList(ColorStateList.valueOf(TRACK));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(8));
        barLp.topMargin = dp(12);
        barLp.bottomMargin = dp(10);
        infoCard.addView(overall, barLp);

        metric = label("", 13, TEXT);
        metric.setSingleLine(true);
        metric.setEllipsize(TextUtils.TruncateAt.END);
        infoCard.addView(metric, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout metrics = new LinearLayout(activity);
        metricsRow = metrics;
        LinearLayout.LayoutParams metricsLp = new LinearLayout.LayoutParams(-1, -2);
        metricsLp.topMargin = dp(6);
        infoCard.addView(metrics, metricsLp);
        rate = metricLabel(metrics, ACCENT, Gravity.START);
        rate.setTypeface(MEDIUM);
        remaining = metricLabel(metrics, MUTED, Gravity.END);

        // Segment overview + map + collapsible lane list
        LinearLayout segBox = new LinearLayout(activity);
        segmentCard = segBox;
        segBox.setOrientation(LinearLayout.VERTICAL);
        segBox.setPadding(dp(14), dp(12), dp(14), dp(10));
        segBox.setBackground(pill(ELEVATED, 16, STROKE));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(-1, -2);
        segLp.bottomMargin = dp(14);
        card.addView(segBox, segLp);

        LinearLayout segHeader = new LinearLayout(activity);
        segHeader.setGravity(Gravity.CENTER_VERTICAL);
        segBox.addView(segHeader, new LinearLayout.LayoutParams(-1, -2));

        section = label("", 12, MUTED);
        section.setSingleLine(true);
        section.setEllipsize(TextUtils.TruncateAt.END);
        segHeader.addView(section, new LinearLayout.LayoutParams(0, -2, 1));

        details = label("分段详情 ▾", 12, ACCENT);
        details.setTypeface(MEDIUM);
        details.setPadding(dp(10), dp(4), dp(10), dp(4));
        details.setBackground(ripplePill(PILL, 12, STROKE));
        details.setOnClickListener(v -> toggleLanes());
        segHeader.addView(details, new LinearLayout.LayoutParams(-2, -2));

        map = new MapView();
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(-1, dp(36));
        mapLp.topMargin = dp(6);
        segBox.addView(map, mapLp);

        adapter = new SegmentAdapter();
        lanes = new ListView(activity);
        lanes.setAdapter(adapter);
        lanes.setDivider(null);
        lanes.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams lanesLp = new LinearLayout.LayoutParams(-1,
                dp(Math.min(200, Math.max(60, activity.getResources().getConfiguration().screenHeightDp - 420))));
        lanesLp.topMargin = dp(6);
        segBox.addView(lanes, lanesLp);
        lanes.setVisibility(View.GONE);

        map.setOnClickListener(v -> {
            int target = map.touchedIndex;
            map.touchedIndex = -1;
            if (target < 0) toggleLanes();
            else jumpToSegment(target);
        });
        lanes.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= adapter.getCount()) return;
            RangeDownload.Segment s = (RangeDownload.Segment) adapter.getItem(position);
            String range = s.end < 0 ? "从字节 " + s.start + " 开始"
                    : "字节 " + s.start + "–" + (s.end - 1);
            new android.app.AlertDialog.Builder(activity).setTitle("分段 " + (position + 1))
                    .setMessage(range + "\n已接收 " + size(s.next - s.start))
                    .setPositiveButton("关闭", null).show();
        });

        // Bottom control actions
        LinearLayout controls = new LinearLayout(activity);
        card.addView(controls, new LinearLayout.LayoutParams(-1, dp(46)));
        button(controls, "新建", false, () -> {
            DownloadService.Job job = DownloadService.current;
            if (job != null && (job.busy || (!job.finished && !job.cancelled))) {
                activity.toast("请先完成或取消当前任务");
                return;
            }
            dialog.dismiss();
            activity.showNewDownload("", "", null);
        });
        pause = button(controls, "暂停", false, () -> {
            DownloadService.Job job = DownloadService.current;
            if (job == null || job.finished || job.cancelled) return;
            try {
                if (job.busy) DownloadService.pause(job);
                else DownloadService.launch(activity, job);
            } catch (RuntimeException e) {
                activity.toast("无法启动后台下载");
            }
            render();
        });
        cancel = button(controls, "取消", false, () -> {
            DownloadService.Job job = DownloadService.current;
            if (job == null || job.finished || job.cancelled || job.saving) return;
            new android.app.AlertDialog.Builder(activity).setTitle("取消此下载？")
                    .setMessage("已下载的分段将被删除。")
                    .setNegativeButton("保留", null)
                    .setPositiveButton("取消任务", (d, which) -> {
                        DownloadService.cancel(activity.getApplicationContext(), job);
                        render();
                    }).show();
        });
        finish = button(controls, "关闭", true, () -> {
            DownloadService.Job job = DownloadService.current;
            if (job != null && job.finished && job.output != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW).setData(job.output)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { activity.startActivity(intent); } catch (RuntimeException e) {
                    activity.toast("请在系统下载目录打开此文件");
                }
            } else {
                dialog.dismiss();
            }
        });

        dialog.setOnDismissListener(d -> handler.removeCallbacks(tick));
        activity.showSheet(dialog, card);
        render();
        if (DownloadService.current == null) {
            DownloadService.loadCurrentAsync(activity, this::render);
        }
    }

    private void toggleLanes() {
        boolean open = lanes.getVisibility() == View.VISIBLE;
        setVisibility(lanes, open ? View.GONE : View.VISIBLE);
        render();
    }

    private void jumpToSegment(int target) {
        selected = target;
        setVisibility(lanes, View.VISIBLE);
        render();
        if (target < adapter.getCount()) lanes.setSelectionFromTop(target, dp(3));
    }

    private void render() {
        handler.removeCallbacks(tick);
        if (dialog == null || !dialog.isShowing()) return;
        DownloadService.Job job = DownloadService.current;
        RangeDownload d = job == null ? null : job.transfer;
        List<RangeDownload.Segment> segments = d == null ? Collections.emptyList() : d.segments;
        if (job != lastJob || selected >= segments.size()) selected = -1;
        long total = job != null && job.finished && job.record.size >= 0 ? job.record.size : d == null ? -1 : d.total;
        long bytes = job != null && job.finished && total >= 0 ? total : d == null ? 0 : d.downloaded();
        if (job != lastJob || bytes < lastBytes || d == null || !job.busy || job.saving) {
            speed = 0;
            lastBytes = bytes;
            lastTime = SystemClock.elapsedRealtime();
            lastJob = job;
        } else {
            long now = SystemClock.elapsedRealtime();
            if (now - lastTime >= 400) {
                double next = 1000.0 * (bytes - lastBytes) / (now - lastTime);
                speed = speed == 0 || next == 0 ? next : 0.65 * next + 0.35 * speed;
                lastBytes = bytes;
                lastTime = now;
            }
        }

        boolean yielding = d != null && job != null && job.busy && !job.saving && DownloadService.PACER.yielding();
        boolean browserFirst = DownloadService.PACER.browserFirst();
        setText(file, job == null ? "还没有下载任务" : job.name);
        setText(status, job == null ? "点新建，输入文件链接开始多线程下载" : job.state
                + (d == null || !job.busy || job.saving ? "" : yielding ? " · 网页优先" : " · " + d.mode));
        if (job != null && job.finished) {
            if (!lastFinishedTint) {
                status.setTextColor(SUCCESS);
                overall.setProgressTintList(ColorStateList.valueOf(SUCCESS));
                lastFinishedTint = true;
                lastYieldTint = false;
            }
        } else if (yielding) {
            if (!lastYieldTint || lastFinishedTint) {
                status.setTextColor(WARN);
                overall.setProgressTintList(ColorStateList.valueOf(WARN));
                lastYieldTint = true;
                lastFinishedTint = false;
            }
        } else if (lastFinishedTint || lastYieldTint) {
            status.setTextColor(MUTED);
            overall.setProgressTintList(ColorStateList.valueOf(ACCENT));
            lastFinishedTint = false;
            lastYieldTint = false;
        }

        if (lastBrowserFirst == null || lastBrowserFirst != browserFirst) {
            lastBrowserFirst = browserFirst;
            setText(priority, browserFirst ? "浏览优先" : "下载全速");
            priority.setTextColor(browserFirst ? SUCCESS : WARN);
        }

        String time = speed > 0 && total > bytes ? "剩余 " + eta((long) Math.ceil((total - bytes) / speed)) : "";
        String percent = total > 0 ? String.format(Locale.ROOT, " (%.1f%%)", 100.0 * bytes / total) : "";
        setText(metric, job == null ? "" : size(bytes) + (total > 0 ? " / " + size(total) : "") + percent);
        setText(rate, speed > 0 ? size((long) speed) + "/s" : "");
        setText(remaining, time);
        setVisibility(metricsRow, job == null || !job.busy ? View.GONE : View.VISIBLE);

        boolean indeterminate = job != null && job.busy && total <= 0;
        if (indeterminate != lastIndeterminate) {
            overall.setIndeterminate(indeterminate);
            lastIndeterminate = indeterminate;
        }
        int progress = total > 0 ? (int) Math.min(1000, 1000.0 * bytes / total) : 0;
        if (progress != lastProgress) {
            overall.setProgress(progress);
            lastProgress = progress;
        }

        boolean hasSegments = !segments.isEmpty();
        setVisibility(segmentCard, hasSegments ? View.VISIBLE : View.GONE);
        if (hasSegments) {
            setText(section, segments.size() + " 分段 · " + (d == null ? 0 : d.activeLanes())
                    + " 路活动 · 上限 " + job.lanes + " 路");
            map.segments = segments;
            map.total = total;
            map.finished = job.finished;
            map.invalidate();
            adapter.segments = segments;
            if (lanes.getVisibility() == View.VISIBLE) adapter.notifyDataSetChanged();
            setText(details, lanes.getVisibility() == View.GONE ? "分段详情 ▾"
                    : selected < 0 ? "收起 ▴" : "分段 " + (selected + 1) + " ▴");
        }

        boolean canPause = job != null && !job.finished && !job.cancelled && !job.saving
                && !"正在暂停".equals(job.state);
        setInteractive(pause, canPause);
        setText(pause, job == null || job.busy ? "暂停" : "继续");
        boolean canCancel = job != null && !job.finished && !job.cancelled && !job.saving;
        setInteractive(cancel, canCancel);
        setText(finish, job != null && job.finished ? "打开文件" : "关闭");
        if (job != null && job.busy) handler.postDelayed(tick, 250);
    }

    private final class MapView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float padY, radius, markHeight;
        List<RangeDownload.Segment> segments = Collections.emptyList();
        long total;
        boolean finished;
        int touchedIndex = -1;
        int downIndex = -1;

        MapView() {
            super(activity);
            padY = dp(10);
            radius = dp(4);
            markHeight = dp(3);
            setContentDescription("文件分段地图，点按跳到相应分段");
        }

        @Override protected void onDraw(Canvas c) {
            float width = getWidth(), top = padY, bottom = getHeight() - padY;
            paint.setColor(TRACK);
            c.drawRoundRect(0f, top, width, bottom, radius, radius, paint);
            if (total <= 0) return;
            for (int i = 0; i < segments.size(); i++) {
                RangeDownload.Segment s = segments.get(i);
                float x = (float) (width * ((double) s.start / total));
                float full = (float) (width * ((double) (s.end < 0 ? total : s.end) / total));
                float done = (float) (width * ((double) s.next / total));
                paint.setColor(finished ? SUCCESS : s.active ? ACCENT : DONE_SEGMENT);
                if (done > x) c.drawRect(x, top, Math.min(full, done), bottom, paint);
                if (x > 0 && segments.size() < width) {
                    paint.setColor(ELEVATED);
                    c.drawRect(x, top, x + 1f, bottom, paint);
                }
                if (i == selected) {
                    paint.setColor(TEXT);
                    c.drawRect(x, top - markHeight, full, top, paint);
                    c.drawRect(x, bottom, full, bottom + markHeight, paint);
                }
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downIndex = indexAt(event.getX());
                touchedIndex = -1;
            } else if (action == MotionEvent.ACTION_UP) {
                int end = indexAt(event.getX());
                touchedIndex = end == downIndex ? end : -1;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                downIndex = touchedIndex = -1;
            }
            return super.onTouchEvent(event);
        }

        private int indexAt(float x) {
            if (segments.isEmpty() || getWidth() <= 0 || x < 0 || x >= getWidth()) return -1;
            if (total <= 0) return segments.size() == 1 ? 0 : -1;
            long offset = (long) ((double) x / getWidth() * total);
            for (int i = 0; i < segments.size(); i++) {
                RangeDownload.Segment part = segments.get(i);
                if (offset >= part.start && (part.end < 0 || offset < part.end)) return i;
            }
            return -1;
        }
    }

    private final class SegmentAdapter extends BaseAdapter {
        List<RangeDownload.Segment> segments = Collections.emptyList();
        @Override public int getCount() { return segments.size(); }
        @Override public Object getItem(int i) { return segments.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View view, ViewGroup parent) {
            TextView row;
            if (view instanceof TextView) row = (TextView) view;
            else {
                row = label("", 12, TEXT);
                row.setTypeface(Typeface.MONOSPACE);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinHeight(dp(34));
                row.setPadding(dp(8), 0, dp(8), 0);
            }
            RangeDownload.Segment s = segments.get(i);
            long span = s.end < 0 ? 0 : s.end - s.start;
            long progress = s.next - s.start;
            boolean complete = span > 0 && progress == span;
            String state = s.active ? size(s.bytesPerSecond) + "/s"
                    : complete ? "完成" : s.retrying ? "正在重试" : "等待";
            setText(row, String.format(Locale.ROOT, "%03d   %s%s   %s", i + 1,
                    size(progress), span > 0 ? " / " + size(span) : "", state));
            row.setTextColor(s.active ? ACCENT : complete ? SUCCESS : s.retrying ? WARN : MUTED);
            row.setBackgroundColor(i == selected ? PILL : 0);
            return row;
        }
    }

    private TextView metricLabel(LinearLayout parent, int color, int alignment) {
        TextView text = label("", 13, color);
        text.setGravity(Gravity.CENTER_VERTICAL | alignment);
        text.setSingleLine();
        text.setEllipsize(TextUtils.TruncateAt.END);
        parent.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        return text;
    }

    private static void setText(TextView field, String value) {
        if (!TextUtils.equals(field.getText(), value)) field.setText(value);
    }

    private static void setVisibility(View view, int visibility) {
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    private static void setInteractive(View view, boolean enabled) {
        if (view.isEnabled() != enabled) view.setEnabled(enabled);
        float alpha = enabled ? 1f : 0.36f;
        if (view.getAlpha() != alpha) view.setAlpha(alpha);
    }

    private TextView label(String text, float size, int color) {
        TextView v = new TextView(activity);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private TextView button(LinearLayout row, String title, boolean accent, Runnable action) {
        TextView v = label(title, 13, accent ? CANVAS : TEXT);
        v.setTypeface(MEDIUM);
        v.setGravity(Gravity.CENTER);
        v.setBackground(ripplePill(accent ? ACCENT : PILL, 12, accent ? 0 : STROKE));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(42), 1);
        size.setMargins(dp(3), dp(2), dp(3), 0);
        row.addView(v, size);
        v.setOnClickListener(w -> action.run());
        return v;
    }

    private Drawable sheetBackground() {
        GradientDrawable bg = new GradientDrawable();
        float top = dp(24);
        float bottom = dp(20);
        bg.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        bg.setColor(SURFACE);
        bg.setStroke(Math.max(1, dp(1)), STROKE);
        return bg;
    }

    private GradientDrawable pill(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radiusDp));
        d.setColor(color);
        if (strokeColor != 0) d.setStroke(Math.max(1, dp(1)), strokeColor);
        return d;
    }

    private Drawable ripplePill(int color, int radiusDp, int strokeColor) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), pill(color, radiusDp, strokeColor), null);
    }

    private int dp(int v) { return Math.round(v * density); }

    private static String size(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", n / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", n / 1073741824.0);
    }

    private static String eta(long s) {
        return s >= 60 ? s / 60 + " 分 " + s % 60 + " 秒" : s + " 秒";
    }
}

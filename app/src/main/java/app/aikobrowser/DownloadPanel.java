package app.aikobrowser;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private static final int TRACK = 0xff442d3b;
    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::render;
    private Dialog dialog;
    private TextView file, status, metric, rate, remaining, section, details, priority, pause, cancel, finish;
    private ProgressBar overall;
    private MapView map;
    private ListView lanes;
    private SegmentAdapter adapter;
    private DownloadService.Job lastJob;
    private long lastTime, lastBytes;
    private double speed;
    private int selected = -1;

    DownloadPanel(MainActivity activity) { this.activity = activity; }

    void show() {
        DownloadService.loadCurrent(activity);
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(activity);
        card.setPadding(dp(20), dp(16), dp(20), dp(12));
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(activity.shape(MainActivity.PANEL, 24));
        add(card, "下载", 20, MainActivity.TEXT, 34).setTypeface(null, Typeface.BOLD);
        file = add(card, "", 16, MainActivity.TEXT, 28);
        file.setSingleLine(true);
        file.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        status = add(card, "", 13, MainActivity.MUTED, 24);
        metric = add(card, "", 14, MainActivity.TEXT, 28);
        overall = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        overall.setMax(1000);
        overall.setProgressTintList(android.content.res.ColorStateList.valueOf(MainActivity.RUBY));
        overall.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(TRACK));
        card.addView(overall, new LinearLayout.LayoutParams(-1, dp(10)));
        LinearLayout metrics = new LinearLayout(activity);
        card.addView(metrics, new LinearLayout.LayoutParams(-1, dp(34)));
        rate = metricLabel(metrics, MainActivity.RUBY, Gravity.START);
        remaining = metricLabel(metrics, MainActivity.MUTED, Gravity.END);
        section = add(card, "", 13, MainActivity.MUTED, 26);
        map = new MapView();
        card.addView(map, new LinearLayout.LayoutParams(-1, dp(42)));
        details = add(card, "分段详情  ▾", 14, MainActivity.RUBY, 39);
        adapter = new SegmentAdapter();
        lanes = new ListView(activity);
        lanes.setAdapter(adapter);
        lanes.setDivider(null);
        card.addView(lanes, new LinearLayout.LayoutParams(-1,
                dp(Math.min(208, Math.max(48, activity.getResources().getConfiguration().screenHeightDp - 380)))));
        lanes.setVisibility(View.GONE);
        details.setOnClickListener(v -> toggleLanes());
        map.setOnClickListener(v -> {
            int target = map.touchedIndex;
            map.touchedIndex = -1;
            if (target < 0) toggleLanes();
            else jumpToSegment(target);
        });
        priority = add(card, "", 13, MainActivity.RUBY, 35);
        priority.setOnClickListener(v -> {
            activity.toggleTransferPriority();
            render();
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

        LinearLayout controls = new LinearLayout(activity);
        card.addView(controls, new LinearLayout.LayoutParams(-1, dp(46)));
        button(controls, "新建", () -> {
            DownloadService.Job job = DownloadService.current;
            if (job != null && (job.busy || (!job.finished && !job.cancelled))) {
                activity.toast("请先完成或取消当前任务");
                return;
            }
            dialog.dismiss();
            activity.showNewDownload("", "", null);
        });
        pause = button(controls, "暂停", () -> {
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
        cancel = button(controls, "取消任务", () -> {
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
        finish = button(controls, "关闭", () -> {
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
    }

    private void toggleLanes() {
        boolean open = lanes.getVisibility() == View.VISIBLE;
        lanes.setVisibility(open ? View.GONE : View.VISIBLE);
        render();
    }

    private void jumpToSegment(int target) {
        selected = target;
        lanes.setVisibility(View.VISIBLE);
        render();
        if (target < adapter.getCount()) lanes.setSelectionFromTop(target, dp(3));
    }

    private void render() {
        handler.removeCallbacks(tick);
        if (!dialog.isShowing()) return;
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
        file.setText(job == null ? "还没有下载任务" : job.name);
        status.setText(job == null ? "点新建，输入文件链接" : job.state
                + (d == null || !job.busy || job.saving ? "" : DownloadService.PACER.yielding()
                        ? " · 网页优先" : " · " + d.mode));
        priority.setText(DownloadService.PACER.browserFirst() ? "浏览优先 · 点按切换" : "下载全速 · 点按切换");
        String time = speed > 0 && total > bytes ? "剩余 " + eta((long) Math.ceil((total - bytes) / speed)) : "";
        String percent = total > 0 ? String.format(Locale.ROOT, " (%.1f%%)", 100.0 * bytes / total) : "";
        setText(metric, job == null ? "" : size(bytes) + (total > 0 ? " / " + size(total) : "") + percent);
        setText(rate, speed > 0 ? size((long) speed) + "/s" : "");
        setText(remaining, time);
        ((View) rate.getParent()).setVisibility(job == null || !job.busy ? View.GONE : View.VISIBLE);
        overall.setIndeterminate(job != null && job.busy && total <= 0);
        overall.setProgress(total > 0 ? (int) Math.min(1000, 1000.0 * bytes / total) : 0);
        section.setText(job == null ? "" : segments.size() + " 个分段  ·  "
                + (d == null ? 0 : d.activeLanes()) + " 路活动  ·  上限 " + job.lanes + " 路");
        map.segments = segments;
        map.total = total;
        map.invalidate();
        adapter.segments = segments;
        if (lanes.getVisibility() == View.VISIBLE) adapter.notifyDataSetChanged();
        details.setVisibility(segments.isEmpty() ? View.GONE : View.VISIBLE);
        map.setVisibility(segments.isEmpty() ? View.GONE : View.VISIBLE);
        setText(details, lanes.getVisibility() == View.GONE ? "分段详情  ▾"
                : selected < 0 ? "收起分段  ▴" : "分段 " + (selected + 1) + "  ·  收起  ▴");
        pause.setEnabled(job != null && !job.finished && !job.cancelled && !job.saving
                && !job.state.equals("正在暂停"));
        pause.setAlpha(pause.isEnabled() ? 1 : 0.35f);
        pause.setText(job == null || job.busy ? "暂停" : "继续");
        cancel.setEnabled(job != null && !job.finished && !job.cancelled && !job.saving);
        cancel.setAlpha(cancel.isEnabled() ? 1 : 0.35f);
        finish.setText(job != null && job.finished ? "打开文件" : "关闭");
        if (job != null && job.busy) handler.postDelayed(tick, 250);
    }

    private final class MapView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        List<RangeDownload.Segment> segments = Collections.emptyList();
        long total;
        int touchedIndex = -1;
        int downIndex = -1;
        MapView() {
            super(activity);
            setContentDescription("文件分段地图，点按跳到相应分段");
        }
        @Override protected void onDraw(Canvas c) {
            float width = getWidth(), bottom = getHeight() - dp(9), top = dp(9);
            paint.setColor(TRACK);
            c.drawRoundRect(0f, top, width, bottom, dp(2), dp(2), paint);
            if (total <= 0) return;
            for (int i = 0; i < segments.size(); i++) {
                RangeDownload.Segment s = segments.get(i);
                float x = (float) (width * ((double) s.start / total));
                float full = (float) (width * ((double) (s.end < 0 ? total : s.end) / total));
                float done = (float) (width * ((double) s.next / total));
                paint.setColor(s.active ? MainActivity.RUBY : 0xffca829b);
                if (done > x) c.drawRect(x, top, Math.min(full, done), bottom, paint);
                if (x > 0 && segments.size() < width) {
                    paint.setColor(MainActivity.PANEL);
                    c.drawRect(x, top, x + 1f, bottom, paint);
                }
                if (i == selected) {
                    paint.setColor(MainActivity.TEXT);
                    c.drawRect(x, top - dp(3), full, top, paint);
                    c.drawRect(x, bottom, full, bottom + dp(3), paint);
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
                row = activity.label("", 12, MainActivity.TEXT);
                row.setTypeface(Typeface.MONOSPACE);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinHeight(dp(34));
                row.setPadding(dp(8), 0, dp(8), 0);
            }
            RangeDownload.Segment s = segments.get(i);
            long span = s.end < 0 ? 0 : s.end - s.start;
            long progress = s.next - s.start;
            String state = s.active ? size(s.bytesPerSecond) + "/s"
                    : span > 0 && progress == span ? "完成" : s.retrying ? "正在重试" : "等待";
            row.setText(String.format(Locale.ROOT, "%03d   %s%s   %s", i + 1,
                    size(progress), span > 0 ? " / " + size(span) : "", state));
            row.setTextColor(s.active ? MainActivity.RUBY : MainActivity.TEXT);
            row.setBackgroundColor(i == selected ? MainActivity.FIELD : 0);
            return row;
        }
    }

    private TextView metricLabel(LinearLayout parent, int color, int alignment) {
        TextView text = activity.label("", 14, color);
        text.setGravity(Gravity.CENTER_VERTICAL | alignment);
        text.setSingleLine();
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        parent.addView(text, new LinearLayout.LayoutParams(0, -1, 1));
        return text;
    }

    private static void setText(TextView field, String value) {
        if (!android.text.TextUtils.equals(field.getText(), value)) field.setText(value);
    }

    private TextView add(LinearLayout parent, String value, int sp, int color, int height) {
        TextView text = activity.label(value, sp, color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(text, new LinearLayout.LayoutParams(-1, dp(height)));
        return text;
    }

    private TextView button(LinearLayout row, String title, Runnable action) {
        TextView v = activity.label(title, 14, MainActivity.TEXT);
        v.setGravity(Gravity.CENTER);
        v.setBackground(activity.shape(MainActivity.FIELD, 10));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(38), 1);
        size.setMargins(dp(2), dp(4), dp(2), 0);
        row.addView(v, size);
        v.setOnClickListener(w -> action.run());
        return v;
    }

    private int dp(int v) { return activity.dp(v); }

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

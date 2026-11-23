package app.aikobrowser;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Installation is possible only through browser chrome and always asks for review. */
final class ScriptTools {
    static final int PICK_FILE = 12;
    private final MainActivity activity;
    private final UserScripts scripts;
    private boolean importing;

    ScriptTools(MainActivity activity, UserScripts scripts) {
        this.activity = activity;
        this.scripts = scripts;
    }

    void show() {
        List<UserScripts.Entry> entries = scripts.items();
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("油猴脚本")
                .setNegativeButton("关闭", null).setPositiveButton("导入脚本", (d, w) -> showImport());
        if (entries.isEmpty()) {
            dialog.setMessage("可粘贴脚本、打开本地 .user.js 文件，或从 HTTPS 源码地址导入。\n\n" + scripts.status());
        } else {
            CharSequence[] names = new CharSequence[entries.size()];
            for (int i = 0; i < names.length; i++) {
                UserScripts.Entry entry = entries.get(i);
                names[i] = (entry.enabled ? "已启用 · " : "已停用 · ") + entry.script.name;
            }
            dialog.setItems(names, (d, index) -> details(entries.get(index)));
        }
        dialog.show();
    }

    private void showImport() {
        String[] choices = {"粘贴或编写脚本", "打开 .user.js 文件", "从 HTTPS 网址导入"};
        new AlertDialog.Builder(activity).setTitle("导入油猴脚本")
                .setItems(choices, (dialog, choice) -> {
                    if (choice == 0) editor(null);
                    else if (choice == 1) openFile();
                    else urlDialog();
                })
                .setNegativeButton("返回", (d, w) -> show()).show();
    }

    private void openFile() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { activity.startActivityForResult(pick, PICK_FILE); }
        catch (android.content.ActivityNotFoundException unavailable) { activity.toast("找不到文件选择器"); }
    }

    void importFile(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return;
        importInBackground(() -> {
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                return new UserScriptInput.Downloaded(UserScriptInput.read(input, UserScript.MAX_BYTES), "本地文件");
            }
        });
    }

    private void urlDialog() {
        EditText address = new EditText(activity);
        address.setSingleLine();
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://…/script.user.js");
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("从网址导入")
                .setView(inset(address)).setNegativeButton("取消", null).setPositiveButton("读取脚本", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String url = address.getText().toString().trim();
            if (!url.regionMatches(true, 0, "https://", 0, 8) || UserScript.address(url) == null) {
                address.setError("请输入有效的 HTTPS 源码地址");
                return;
            }
            dialog.dismiss();
            importInBackground(() -> UserScriptInput.download(url));
        });
    }

    private interface ImportSource { UserScriptInput.Downloaded read() throws IOException; }

    private void importInBackground(ImportSource source) {
        if (importing) { activity.toast("正在读取另一个脚本"); return; }
        importing = true;
        activity.toast("正在读取脚本");
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                UserScriptInput.Downloaded downloaded = source.read();
                UserScript parsed = UserScript.parse(downloaded.source);
                activity.runOnUiThread(() -> {
                    importing = false;
                    if (!activity.isDestroyed() && !activity.isFinishing())
                        review(parsed, scripts.duplicate(parsed), downloaded.site);
                });
            } catch (IOException | IllegalArgumentException | SecurityException invalid) {
                String detail = invalid instanceof IllegalArgumentException ? invalid.getMessage()
                        : "无法读取脚本。请检查链接或 UTF-8 文件。";
                activity.runOnUiThread(() -> {
                    importing = false;
                    if (!activity.isDestroyed() && !activity.isFinishing())
                        new AlertDialog.Builder(activity).setTitle("导入失败").setMessage(detail)
                                .setPositiveButton("关闭", null).show();
                });
            }
        }, "aiko-script-import").start();
    }

    private void editor(UserScripts.Entry entry) {
        EditText code = new EditText(activity);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setGravity(Gravity.TOP | Gravity.START);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(13);
        code.setMinLines(7);
        code.setMaxLines(12);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(UserScript.MAX_BYTES)});
        code.setHint("// ==UserScript==\n// @name 我的脚本\n// @match https://example.com/*\n// @grant none\n// ==/UserScript==");
        if (entry != null) code.setText(entry.script.source);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(entry == null ? "粘贴脚本" : "编辑脚本")
                .setView(inset(code)).setNegativeButton("取消", null).setPositiveButton("检查脚本", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                UserScript parsed = UserScript.parse(code.getText().toString());
                UserScripts.Entry duplicate = scripts.duplicate(parsed);
                if (entry != null && duplicate != null && duplicate != entry) {
                    code.setError("已有同名、同命名空间的脚本");
                    return;
                }
                review(parsed, entry == null ? duplicate : entry, null, dialog::dismiss);
            } catch (IllegalArgumentException invalid) {
                new AlertDialog.Builder(activity).setTitle("脚本无法导入").setMessage(invalid.getMessage())
                        .setPositiveButton("返回编辑", null).show();
            }
        });
    }

    private void review(UserScript script, UserScripts.Entry previous, String source) {
        review(script, previous, source, () -> {});
    }

    private void review(UserScript script, UserScripts.Entry previous, String source, Runnable closeEditor) {
        String message = (source == null ? "" : "来源  " + source + "\n\n") + summary(script)
                + (previous == null ? "\n\n导入后默认停用，可在脚本详情中启用。"
                : "\n\n将替换已有脚本。新源码默认停用，并保留该脚本已保存的值。");
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(script.name).setMessage(message)
                .setNegativeButton("取消", null).setPositiveButton(previous == null ? "导入" : "替换", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                UserScripts.Entry installed = scripts.save(script, previous);
                dialog.dismiss();
                closeEditor.run();
                details(installed);
            } catch (IllegalArgumentException blocked) {
                activity.toast(blocked.getMessage());
            }
        });
    }

    private void details(UserScripts.Entry entry) {
        String status = scripts.compatibility(entry);
        String[] actions = {entry.enabled ? "停用脚本" : "启用脚本", "编辑源码", "删除脚本"};
        new AlertDialog.Builder(activity).setTitle(entry.script.name)
                .setMessage((entry.enabled ? "已启用" : "已停用") + "\n\n" + summary(entry.script)
                        + (status.isEmpty() ? "" : "\n\n" + status))
                .setNegativeButton("返回列表", (d, w) -> show())
                .setNeutralButton("管理", (d, w) -> new AlertDialog.Builder(activity).setTitle("管理脚本")
                        .setItems(actions, (choice, index) -> {
                            if (index == 0) toggle(entry);
                            else if (index == 1) editor(entry);
                            else delete(entry);
                        }).setNegativeButton("取消", (back, cancel) -> details(entry)).show())
                .setPositiveButton(entry.enabled ? "停用" : "启用", (d, w) -> toggle(entry))
                .show();
    }

    private void toggle(UserScripts.Entry entry) {
        String status = scripts.compatibility(entry);
        boolean enable = !entry.enabled;
        if (enable && !status.isEmpty()) {
            new AlertDialog.Builder(activity).setTitle("当前 WebView 无法运行此脚本").setMessage(status)
                    .setPositiveButton("返回", (d, w) -> details(entry)).show();
            return;
        }
        scripts.setEnabled(entry, enable);
        activity.toast(enable ? "已启用，刷新或重新打开匹配网页生效" : "已停用，刷新已打开的网页停止运行");
        show();
    }

    private void delete(UserScripts.Entry entry) {
        new AlertDialog.Builder(activity).setTitle("删除脚本？").setMessage(entry.script.name)
                .setNegativeButton("取消", (d, w) -> details(entry))
                .setPositiveButton("删除", (d, w) -> { scripts.delete(entry); show(); }).show();
    }

    private LinearLayout inset(EditText input) {
        LinearLayout around = new LinearLayout(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        int padding = Math.round(22 * density);
        around.setPadding(padding, 0, padding, Math.round(6 * density));
        around.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return around;
    }

    private static String summary(UserScript script) {
        StringBuilder message = new StringBuilder();
        if (!script.version.isEmpty()) message.append("版本  ").append(script.version).append('\n');
        if (!script.namespace.isEmpty()) message.append("命名空间  ").append(script.namespace).append('\n');
        message.append("运行时机  ").append(script.runAt).append("\n\n匹配网址");
        for (UserScript.UrlPattern row : script.includes) message.append('\n').append(row.original);
        if (!script.excludes.isEmpty()) {
            message.append("\n\n排除网址");
            for (UserScript.UrlPattern row : script.excludes) message.append('\n').append(row.original);
        }
        message.append("\n\n授权  ").append(script.grants.isEmpty() ? "无 GM 授权" : String.join("、", script.grants));
        return message.toString();
    }
}

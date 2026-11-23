package app.aikobrowser;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebMessagePortCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Locale;

/**
 * On-demand visual "点选屏蔽" (Tap-to-Hide Element Picker).
 * Uses WebMessageChannel + postWebMessage so IPC works immediately on already-loaded pages,
 * with a always-reachable evaluateJavascript fallback and strict ABP-safe selector generation.
 */
final class ElementPicker {
    interface Callback {
        void onSaveRule(String abpRule);
        void onToast(String message);
        void onStateChanged(boolean active);
    }

    private final Context ctx;
    private final FrameLayout hostOverlay;
    private final float density;
    private final Callback callback;

    private LinearLayout bar;
    private TextView selectorView, countView, previewBtn, saveBtn, widerBtn;

    private WebView activeWeb;
    private String activeHost;
    private WebMessagePortCompat localPort;
    private String currentSelector = "";
    private boolean previewHidden;

    ElementPicker(Context ctx, FrameLayout hostOverlay, Callback callback) {
        this.ctx = ctx;
        this.hostOverlay = hostOverlay;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.callback = callback;
    }

    boolean isActive() {
        return activeWeb != null;
    }

    void start(WebView web, String normalizedHost, String pageUrl) {
        stop();
        if (web == null || normalizedHost == null || normalizedHost.isEmpty()) return;
        activeWeb = web;
        activeHost = normalizedHost;
        currentSelector = "";
        previewHidden = false;

        ensureBar();
        selectorView.setText("点按页面上的广告或悬浮栏");
        countView.setText(normalizedHost);
        updateButtons();
        bar.setVisibility(View.VISIBLE);
        bar.setTranslationY(AikoTheme.dp(density, 20));
        bar.setAlpha(0f);
        bar.animate().translationY(0f).alpha(1f).setDuration(140)
                .setInterpolator(AikoTheme.EASE_OUT)
                .withLayer()
                .withEndAction(() -> bar.setLayerType(View.LAYER_TYPE_NONE, null))
                .start();

        final String nonce = "aiko-pick-" + Long.toHexString(System.nanoTime() ^ System.identityHashCode(this));
        final String origin = pageOrigin(pageUrl);
        final String installJs = "(" + PICKER_INSTALL_JS + ")(" + JSONObject.quote(nonce) + ");";

        web.evaluateJavascript(installJs, ignored -> {
            if (activeWeb != web) return;
            attachLiveMessageChannel(web, nonce, origin);
        });
        callback.onStateChanged(true);
    }

    private void attachLiveMessageChannel(WebView web, String nonce, String origin) {
        if (origin == null || origin.isEmpty() || !supportsWebMessageChannel()) return;
        try {
            WebMessagePortCompat[] channel = WebViewCompat.createWebMessageChannel(web);
            if (channel == null || channel.length < 2) return;
            localPort = channel[0];
            localPort.setWebMessageCallback(new WebMessagePortCompat.WebMessageCallbackCompat() {
                @Override public void onMessage(WebMessagePortCompat port, WebMessageCompat message) {
                    if (activeWeb == web && message != null && message.getType() == WebMessageCompat.TYPE_STRING) {
                        handleBridgeMessage(message.getData());
                    }
                }
            });
            WebMessageCompat init = new WebMessageCompat(nonce, new WebMessagePortCompat[]{channel[1]});
            WebViewCompat.postWebMessage(web, init, Uri.parse(origin));
        } catch (RuntimeException | LinkageError ignored) {
            closeLocalPort();
        }
    }

    private void closeLocalPort() {
        WebMessagePortCompat port = localPort;
        localPort = null;
        if (port != null) {
            try { port.close(); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    void stop() {
        WebView web = activeWeb;
        activeWeb = null;
        activeHost = null;
        currentSelector = "";
        previewHidden = false;

        closeLocalPort();
        if (web != null) {
            try { web.evaluateJavascript("window.__aikoPicker && window.__aikoPicker.stop();", null); }
            catch (RuntimeException ignored) { }
        }
        if (bar != null && bar.getVisibility() == View.VISIBLE) {
            bar.animate().cancel();
            bar.setLayerType(View.LAYER_TYPE_NONE, null);
            bar.setVisibility(View.GONE);
        }
        callback.onStateChanged(false);
    }

    private void ensureBar() {
        if (bar != null) return;
        bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(AikoTheme.dp(density, 14), AikoTheme.dp(density, 10),
                AikoTheme.dp(density, 14), AikoTheme.dp(density, 10));
        bar.setBackground(AikoTheme.card(AikoTheme.SURFACE, AikoTheme.STROKE_ACCENT, density, AikoTheme.dp(density, 20)));
        bar.setElevation(AikoTheme.dp(density, 10));

        LinearLayout infoRow = new LinearLayout(ctx);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(infoRow, new LinearLayout.LayoutParams(-1, -2));

        selectorView = new TextView(ctx);
        selectorView.setTextSize(13);
        selectorView.setTypeface(AikoTheme.MONO);
        selectorView.setTextColor(AikoTheme.TEXT);
        selectorView.setSingleLine(true);
        selectorView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        infoRow.addView(selectorView, new LinearLayout.LayoutParams(0, -2, 1));

        countView = new TextView(ctx);
        countView.setTextSize(11);
        countView.setTypeface(AikoTheme.MEDIUM);
        countView.setTextColor(AikoTheme.CYAN);
        countView.setPadding(AikoTheme.dp(density, 8), 0, 0, 0);
        infoRow.addView(countView, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = AikoTheme.dp(density, 8);
        bar.addView(actions, alp);

        widerBtn = actionPill("扩大选区", AikoTheme.PILL, AikoTheme.TEXT, this::widenSelection);
        previewBtn = actionPill("预览隐藏", AikoTheme.PILL, AikoTheme.TEXT, this::togglePreview);
        saveBtn = actionPill("保存屏蔽", AikoTheme.ACCENT, 0xFF0B0D12, this::saveCurrentRule);
        TextView exitBtn = actionPill("退出", AikoTheme.CARD, AikoTheme.TEXT_MUTED, this::stop);

        addPill(actions, widerBtn, true);
        addPill(actions, previewBtn, true);
        addPill(actions, saveBtn, true);
        addPill(actions, exitBtn, false);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        int m = AikoTheme.dp(density, 10);
        blp.setMargins(m, 0, m, AikoTheme.dp(density, 68));
        hostOverlay.addView(bar, blp);
    }

    private TextView actionPill(String label, int bg, int fg, Runnable onClick) {
        TextView btn = new TextView(ctx);
        btn.setText(label);
        btn.setTextSize(12);
        btn.setTypeface(AikoTheme.MEDIUM);
        btn.setTextColor(fg);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setBackground(AikoTheme.rounded(bg, AikoTheme.dp(density, 12)));
        btn.setForeground(AikoTheme.ripple(AikoTheme.RIPPLE_LIGHT, AikoTheme.dp(density, 12)));
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void addPill(LinearLayout row, View pill, boolean rightGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, AikoTheme.dp(density, 38), 1);
        if (rightGap) lp.rightMargin = AikoTheme.dp(density, 6);
        row.addView(pill, lp);
    }

    private void widenSelection() {
        if (activeWeb == null) return;
        activeWeb.evaluateJavascript("window.__aikoPicker ? window.__aikoPicker.widen() : ''", this::handleEvalJson);
    }

    private void togglePreview() {
        if (activeWeb == null) return;
        boolean next = !previewHidden;
        activeWeb.evaluateJavascript(
                "window.__aikoPicker ? window.__aikoPicker.preview(" + next + ") : ''",
                raw -> {
                    handleEvalJson(raw);
                    if (currentSelector.isEmpty()) callback.onToast("请先点选页面上的元素");
                });
    }

    private void saveCurrentRule() {
        if (activeWeb == null || activeHost == null) return;
        if (!currentSelector.isEmpty()) {
            commitSelector(currentSelector);
            return;
        }
        // Always-reachable fallback query if MessagePort wasn't supported or hasn't delivered yet
        activeWeb.evaluateJavascript("window.__aikoPicker ? window.__aikoPicker.current() : ''", value -> {
            handleEvalJson(value);
            if (!currentSelector.isEmpty()) commitSelector(currentSelector);
            else callback.onToast("请先点选页面上的元素");
        });
    }

    private void commitSelector(String rawSelector) {
        String clean = sanitizeSelector(rawSelector);
        if (clean == null) {
            callback.onToast("此元素无法生成安全过滤规则，请尝试扩大选区");
            return;
        }
        String rule = activeHost + "##" + clean;
        stop();
        callback.onSaveRule(rule);
    }

    private void handleEvalJson(String jsStringResult) {
        if (jsStringResult == null || jsStringResult.equals("null") || jsStringResult.length() < 2) return;
        try {
            Object parsed = new JSONTokener(jsStringResult).nextValue();
            if (parsed instanceof String && !((String) parsed).isEmpty()) {
                handleBridgeMessage((String) parsed);
            }
        } catch (JSONException ignored) { }
    }

    private void handleBridgeMessage(String payload) {
        if (payload == null || payload.isEmpty() || bar == null) return;
        try {
            JSONObject obj = new JSONObject(payload);
            String sel = obj.optString("selector", "");
            int matches = obj.optInt("matches", 0);
            currentSelector = sel;
            previewHidden = obj.optBoolean("hidden", false);
            if (sel.isEmpty()) {
                selectorView.setText("无法选择根文档，请点按具体区块");
                countView.setText("");
            } else {
                selectorView.setText(sel);
                countView.setText("匹配 " + matches + " 处");
            }
            updateButtons();
        } catch (JSONException ignored) { }
    }

    private void updateButtons() {
        boolean hasSel = !currentSelector.isEmpty();
        // Keep buttons clickable so evaluateJavascript fallback is always reachable even without MessagePort
        widerBtn.setAlpha(hasSel ? 1f : 0.62f);
        previewBtn.setAlpha(hasSel ? 1f : 0.62f);
        previewBtn.setText(previewHidden ? "恢复显示" : "预览隐藏");
        saveBtn.setAlpha(hasSel ? 1f : 0.72f);
    }

    /** Rejects unsafe characters (including backslash '\' rejected by FilterRules.isUnsafeSelector). */
    static String sanitizeSelector(String selector) {
        if (selector == null) return null;
        String s = selector.trim();
        if (s.isEmpty() || s.length() > 180) return null;
        if (s.equalsIgnoreCase("html") || s.equalsIgnoreCase("body") || s.equals("*")) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == '{' || c == '}' || c == '<' || c == '>' && i == 0
                    || c == ';' || c == '\\' || c == '\n' || c == '\r' || c == '"' || c == '\'') {
                return null;
            }
        }
        return s;
    }

    private static String pageOrigin(String url) {
        if (!HttpUrlUtil.isHttp(url)) return "";
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        String host = HttpUrlUtil.extractHost(url);
        if (scheme == null || host.isEmpty()) return "";
        int port = uri.getPort();
        boolean defaultPort = port < 0
                || ("https".equalsIgnoreCase(scheme) && port == 443)
                || ("http".equalsIgnoreCase(scheme) && port == 80);
        return scheme.toLowerCase(Locale.ROOT) + "://" + host + (defaultPort ? "" : ":" + port);
    }

    private static boolean supportsWebMessageChannel() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static final String PICKER_INSTALL_JS =
            "function(initNonce){"
            + "if(window.__aikoPicker){window.__aikoPicker.stop();}"
            + "var box=document.createElement('div');"
            + "box.id='__aiko_picker_box';"
            + "box.style.cssText='position:fixed;z-index:2147483647;pointer-events:none;border:2px solid #7C9BFF;background:rgba(124,155,255,0.20);border-radius:6px;transition:all 80ms ease-out;display:none;box-sizing:border-box;';"
            + "(document.documentElement||document.body).appendChild(box);"
            + "var pStyle=null,target=null,sel='',hidden=false,port=null;"
            + "var safeTok=/^[a-zA-Z_-][a-zA-Z0-9_-]*$/;"
            + "function goodId(id){return id&&id.length<48&&safeTok.test(id)&&!/[0-9]{5,}/.test(id)&&id.indexOf('__aiko')!==0;}"
            + "function nthOfType(el){"
            + "  var idx=1,sib=el.previousElementSibling,tag=el.tagName;"
            + "  while(sib){if(sib.tagName===tag)idx++;sib=sib.previousElementSibling;}"
            + "  return idx;"
            + "}"
            + "function part(el){"
            + "  var tag=el.tagName.toLowerCase();"
            + "  if(!safeTok.test(tag))return '';"
            + "  if(goodId(el.id))return '#'+el.id;"
            + "  var cls=[];"
            + "  if(el.classList){for(var i=0;i<el.classList.length&&cls.length<2;i++){"
            + "    var c=el.classList[i];if(c&&c.length>=2&&c.length<36&&safeTok.test(c)&&!/[0-9]{5,}/.test(c))cls.push('.'+c);}}"
            + "  if(cls.length)return tag+cls.join('');"
            + "  return tag+':nth-of-type('+nthOfType(el)+')';"
            + "}"
            + "function compute(el){"
            + "  if(!el||el===document.body||el===document.documentElement)return '';"
            + "  var parts=[],cur=el;"
            + "  for(var d=0;d<3&&cur&&cur!==document.body&&cur!==document.documentElement;d++){"
            + "    var p=part(cur);if(!p)break;parts.unshift(p);"
            + "    var cand=parts.join(' > ');"
            + "    try{if(p.charAt(0)==='#'||document.querySelectorAll(cand).length<=4)return cand;}catch(e){}"
            + "    cur=cur.parentElement;"
            + "  }"
            + "  return parts.join(' > ');"
            + "}"
            + "function clearPreview(){if(pStyle&&pStyle.parentNode)pStyle.parentNode.removeChild(pStyle);pStyle=null;hidden=false;}"
            + "function emit(){"
            + "  var m=0;if(sel){try{m=document.querySelectorAll(sel).length;}catch(e){m=0;}}"
            + "  var msg=JSON.stringify({selector:sel,matches:m,hidden:hidden});"
            + "  try{if(port)port.postMessage(msg);}catch(e){}"
            + "  return msg;"
            + "}"
            + "function highlight(el){"
            + "  clearPreview();target=el;sel=compute(el);"
            + "  if(!sel){box.style.display='none';return emit();}"
            + "  var r=el.getBoundingClientRect();"
            + "  box.style.left=Math.max(0,r.left)+'px';box.style.top=Math.max(0,r.top)+'px';"
            + "  box.style.width=Math.max(4,r.width)+'px';box.style.height=Math.max(4,r.height)+'px';"
            + "  box.style.display='block';"
            + "  return emit();"
            + "}"
            + "function onClick(e){"
            + "  if(e.target===box)return;"
            + "  e.preventDefault();e.stopImmediatePropagation();"
            + "  highlight(e.target);"
            + "}"
            + "function onInitPort(e){"
            + "  if(e&&e.data===initNonce&&e.ports&&e.ports[0]){"
            + "    port=e.ports[0];"
            + "    window.removeEventListener('message',onInitPort);"
            + "    if(sel)emit();"
            + "  }"
            + "}"
            + "window.addEventListener('message',onInitPort);"
            + "document.addEventListener('click',onClick,true);"
            + "window.__aikoPicker={"
            + "  widen:function(){if(target&&target.parentElement&&target.parentElement!==document.body&&target.parentElement!==document.documentElement){return highlight(target.parentElement);}return emit();},"
            + "  preview:function(on){clearPreview();if(on&&sel){pStyle=document.createElement('style');pStyle.id='__aiko_picker_preview';pStyle.textContent=sel+'{display:none!important;}';(document.head||document.documentElement).appendChild(pStyle);box.style.display='none';hidden=true;}else if(target){var r=target.getBoundingClientRect();box.style.left=Math.max(0,r.left)+'px';box.style.top=Math.max(0,r.top)+'px';box.style.width=Math.max(4,r.width)+'px';box.style.height=Math.max(4,r.height)+'px';box.style.display='block';}return emit();},"
            + "  current:function(){return emit();},"
            + "  stop:function(){window.removeEventListener('message',onInitPort);document.removeEventListener('click',onClick,true);clearPreview();try{if(port)port.close();}catch(e){}port=null;if(box.parentNode)box.parentNode.removeChild(box);delete window.__aikoPicker;}"
            + "};"
            + "}";
}

package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

/**
 * On-demand distraction-free Reader Mode injector.
 * Zero idle overhead: injects Shadow-DOM reader overlay ONLY when toggled on,
 * supports Dark Obsidian / Warm Sepia / Crisp Paper themes and font scaling,
 * and restores the original page instantaneously with zero reload.
 */
final class ReaderMode {
    private static final String PREFS = "reader_prefs";

    private ReaderMode() {}

    static void toggle(Context ctx, WebView web, ValueCallback<String> onResult) {
        if (web == null) {
            if (onResult != null) onResult.onReceiveValue("unavailable");
            return;
        }
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String theme = prefs.getString("theme", "dark");
        int fontPx = prefs.getInt("fontPx", 18);
        String js = "(" + READER_JS + ")(" + JSONObject.quote(theme) + "," + fontPx + ");";
        web.evaluateJavascript(js, raw -> {
            String state = "unavailable";
            if (raw != null) {
                if (raw.contains("opened")) state = "opened";
                else if (raw.contains("closed")) state = "closed";
                else if (raw.contains("empty")) state = "empty";
            }
            if (onResult != null) onResult.onReceiveValue(state);
        });
    }

    static void closeIfOpen(WebView web) {
        if (web == null) return;
        web.evaluateJavascript(
                "(function(){var r=document.getElementById('__aiko_reader_root');"
                + "if(r){r.remove();document.documentElement.style.overflow='';document.body&&(document.body.style.overflow='');}})();",
                null);
    }

    private static final String READER_JS =
            "function(initTheme,initSize){"
            + "var existing=document.getElementById('__aiko_reader_root');"
            + "if(existing){"
            + "  existing.remove();"
            + "  document.documentElement.style.overflow='';"
            + "  if(document.body)document.body.style.overflow='';"
            + "  return 'closed';"
            + "}"
            + "var badRe=/nav|sidebar|footer|header|comment|advert|promo|banner|share|related|recommend|popup|modal|cookie|menu|breadcrumb|toolbar/i;"
            + "function textLen(el){return (el.innerText||'').replace(/\\s+/g,' ').trim().length;}"
            + "function linkDensity(el){"
            + "  var t=textLen(el);if(!t)return 1;"
            + "  var links=el.querySelectorAll('a'),lt=0;"
            + "  for(var i=0;i<links.length;i++)lt+=textLen(links[i]);"
            + "  return lt/t;"
            + "}"
            + "function score(el){"
            + "  var tag=el.tagName;"
            + "  if(tag==='NAV'||tag==='ASIDE'||tag==='FOOTER'||tag==='HEADER'||tag==='FORM')return -1000;"
            + "  var idc=(el.id||'')+' '+(el.className&&typeof el.className==='string'?el.className:'');"
            + "  var s=0;"
            + "  if(tag==='ARTICLE')s+=80;else if(tag==='MAIN')s+=60;"
            + "  if(/article|post|entry|content|story|detail|markdown|prose/i.test(idc))s+=45;"
            + "  if(badRe.test(idc))s-=90;"
            + "  var ps=el.querySelectorAll('p,pre,blockquote,li');"
            + "  var chars=0;"
            + "  for(var i=0;i<ps.length;i++){"
            + "    var l=textLen(ps[i]);if(l>=24){chars+=l;s+=Math.min(40,l/18);}"
            + "  }"
            + "  if(chars<160)chars=textLen(el);"
            + "  if(chars<160)return -500;"
            + "  var ld=linkDensity(el);"
            + "  if(ld>0.45)s-=chars*0.4;"
            + "  return s+Math.min(600,chars*0.18)*(1-Math.min(0.85,ld));"
            + "}"
            + "var cands=document.querySelectorAll('article,main,[role=\"main\"],section,div');"
            + "var best=null,bestScore=-1;"
            + "var limit=Math.min(cands.length,260);"
            + "for(var i=0;i<limit;i++){"
            + "  var c=cands[i];if(!c||c.offsetHeight===0)continue;"
            + "  var sc=score(c);if(sc>bestScore){bestScore=sc;best=c;}"
            + "}"
            + "if(!best||bestScore<40){"
            + "  if(document.body&&textLen(document.body)>=220)best=document.body;else return 'empty';"
            + "}"
            + "var clone=best.cloneNode(true);"
            + "var kill=clone.querySelectorAll('script,style,noscript,iframe,object,embed,form,button,input,select,textarea,nav,aside,footer,svg,canvas');"
            + "for(var i=0;i<kill.length;i++)kill[i].remove();"
            + "var all=clone.querySelectorAll('*');"
            + "for(var i=all.length-1;i>=0;i--){"
            + "  var n=all[i];"
            + "  var m=(n.id||'')+' '+(n.className&&typeof n.className==='string'?n.className:'');"
            + "  if(badRe.test(m)&&textLen(n)<260){n.remove();continue;}"
            + "  n.removeAttribute('style');n.removeAttribute('class');n.removeAttribute('width');n.removeAttribute('height');"
            + "  if(n.tagName==='IMG'){"
            + "    var src=n.getAttribute('data-src')||n.getAttribute('data-original')||n.getAttribute('src');"
            + "    if(!src||src.indexOf('data:image/gif')===0){n.remove();}else{n.setAttribute('src',src);n.setAttribute('loading','lazy');}"
            + "  }"
            + "}"
            + "var rawText=(clone.innerText||'').replace(/\\s+/g,' ').trim();"
            + "if(rawText.length<140)return 'empty';"
            + "var h1=document.querySelector('article h1,main h1,h1');"
            + "var title=(h1&&h1.innerText&&h1.innerText.trim().length>3)?h1.innerText.trim():(document.title||location.hostname);"
            + "var mins=Math.max(1,Math.round(rawText.length/420));"
            + "var host=document.createElement('div');"
            + "host.id='__aiko_reader_root';"
            + "host.style.cssText='position:fixed;inset:0;z-index:2147483646;display:block;';"
            + "var shadow=host.attachShadow?host.attachShadow({mode:'open'}):host;"
            + "var themes={"
            + "  dark:{bg:'#0B0D12',bar:'#131722',card:'#1B2130',fg:'#E6EAF2',mut:'#9AA5BD',acc:'#7C9BFF',bd:'rgba(255,255,255,0.12)'},"
            + "  sepia:{bg:'#F5EEE0',bar:'#EBE1CE',card:'#E5D9C3',fg:'#2B231B',mut:'#6F6253',acc:'#9A5B2A',bd:'rgba(43,35,27,0.14)'},"
            + "  light:{bg:'#FAFBFD',bar:'#F0F2F7',card:'#E8ECF4',fg:'#181C24',mut:'#5B6475',acc:'#3B63F6',bd:'rgba(24,28,36,0.12)'}"
            + "};"
            + "var curTheme=themes[initTheme]?initTheme:'dark';"
            + "var curSize=Math.max(14,Math.min(26,initSize||18));"
            + "var style=document.createElement('style');"
            + "var wrap=document.createElement('div');wrap.className='wrap';"
            + "function renderCss(){"
            + "  var t=themes[curTheme];"
            + "  style.textContent="
            + "    '.wrap{position:fixed;inset:0;overflow-y:auto;overscroll-behavior:contain;-webkit-overflow-scrolling:touch;background:'+t.bg+';color:'+t.fg+';font-family:-apple-system,BlinkMacSystemFont,\"Noto Sans SC\",sans-serif;}'"
            + "    +'.bar{position:sticky;top:0;display:flex;align-items:center;gap:6px;padding:10px 14px;background:'+t.bar+';border-bottom:1px solid '+t.bd+';z-index:2;}'"
            + "    +'.btn{border:1px solid '+t.bd+';background:'+t.card+';color:'+t.fg+';border-radius:10px;padding:6px 10px;font-size:12px;font-weight:500;}'"
            + "    +'.btn.on{border-color:'+t.acc+';color:'+t.acc+';}'"
            + "    +'.sp{flex:1;}'"
            + "    +'.art{max-width:680px;margin:0 auto;padding:22px 20px 88px;font-size:'+curSize+'px;line-height:1.78;word-break:break-word;}'"
            + "    +'.meta{font-size:12px;color:'+t.mut+';margin-bottom:18px;}'"
            + "    +'.ttl{font-size:1.55em;line-height:1.32;font-weight:700;margin:0 0 10px;}'"
            + "    +'.art p{margin:0 0 1.05em;}'"
            + "    +'.art h1,.art h2,.art h3,.art h4{line-height:1.35;margin:1.35em 0 0.55em;}'"
            + "    +'.art a{color:'+t.acc+';text-decoration:none;}'"
            + "    +'.art img{max-width:100%;height:auto;border-radius:10px;margin:14px 0;display:block;}'"
            + "    +'.art blockquote{margin:1em 0;padding:10px 14px;border-left:3px solid '+t.acc+';background:'+t.card+';border-radius:0 10px 10px 0;color:'+t.mut+';}'"
            + "    +'.art pre{overflow-x:auto;padding:12px 14px;border-radius:10px;background:'+t.card+';font-size:0.86em;line-height:1.55;}'"
            + "    +'.art code{font-family:monospace;font-size:0.9em;}'"
            + "    +'.art table{width:100%;border-collapse:collapse;margin:14px 0;font-size:0.9em;}'"
            + "    +'.art td,.art th{border:1px solid '+t.bd+';padding:6px 8px;}';"
            + "}"
            + "renderCss();"
            + "var bar=document.createElement('div');bar.className='bar';"
            + "function mkBtn(txt,fn){var b=document.createElement('button');b.className='btn';b.textContent=txt;b.onclick=fn;bar.appendChild(b);return b;}"
            + "var bDark,bSepia,bLight;"
            + "function syncBtns(){bDark.className='btn'+(curTheme==='dark'?' on':'');bSepia.className='btn'+(curTheme==='sepia'?' on':'');bLight.className='btn'+(curTheme==='light'?' on':'');}"
            + "bDark=mkBtn('暗黑',function(){curTheme='dark';renderCss();syncBtns();});"
            + "bSepia=mkBtn('护眼',function(){curTheme='sepia';renderCss();syncBtns();});"
            + "bLight=mkBtn('浅色',function(){curTheme='light';renderCss();syncBtns();});"
            + "syncBtns();"
            + "mkBtn('A-',function(){curSize=Math.max(14,curSize-2);renderCss();});"
            + "mkBtn('A+',function(){curSize=Math.min(26,curSize+2);renderCss();});"
            + "var sp=document.createElement('div');sp.className='sp';bar.appendChild(sp);"
            + "mkBtn('退出',function(){host.remove();document.documentElement.style.overflow='';if(document.body)document.body.style.overflow='';});"
            + "var art=document.createElement('article');art.className='art';"
            + "var h=document.createElement('h1');h.className='ttl';h.textContent=title;art.appendChild(h);"
            + "var meta=document.createElement('div');meta.className='meta';meta.textContent=location.hostname+'  ·  约 '+mins+' 分钟阅读  ·  '+rawText.length+' 字';art.appendChild(meta);"
            + "var body=document.createElement('div');body.innerHTML=clone.innerHTML;art.appendChild(body);"
            + "wrap.appendChild(bar);wrap.appendChild(art);"
            + "shadow.appendChild(style);shadow.appendChild(wrap);"
            + "document.documentElement.style.overflow='hidden';"
            + "if(document.body)document.body.style.overflow='hidden';"
            + "(document.documentElement||document.body).appendChild(host);"
            + "return 'opened';"
            + "}";
}

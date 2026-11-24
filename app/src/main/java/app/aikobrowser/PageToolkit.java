package app.aikobrowser;

import java.util.Locale;

/**
 * Pure-Java JS engine for Aiko Browser's "网页自由 & 独家增强" PageToolkit:
 * copy/selection unlock, gallery image extractor, media sniffer, custom CSS/font & force zoom,
 * anti-adblock defensive scriptlets, and automatic form draft capture/restore.
 */
public final class PageToolkit {
    private PageToolkit() {}

    /**
     * Defeats user-select:none, inline copy/contextmenu/selectstart/paste blockers,
     * and installs capture-phase guards so users can freely select, copy, and paste on any page.
     */
    public static String unlockCopyScript() {
        return "(function(){"
                + "'use strict';"
                + "var sid='__aiko_unlock_copy_style';"
                + "if(!document.getElementById(sid)){"
                + "var st=document.createElement('style');"
                + "st.id=sid;"
                + "st.textContent='*,*::before,*::after{-webkit-user-select:text!important;user-select:text!important;-webkit-touch-callout:default!important;}';"
                + "(document.head||document.documentElement).appendChild(st);"
                + "}"
                + "var props=['oncopy','oncut','onselectstart','oncontextmenu','onpaste','ondragstart'];"
                + "function clearProps(t){if(!t)return;for(var i=0;i<props.length;i++){try{t[props[i]]=null;}catch(e){}}}"
                + "clearProps(window);clearProps(document);clearProps(document.documentElement);clearProps(document.body);"
                + "try{"
                + "var nodes=document.querySelectorAll('[oncopy],[oncut],[onselectstart],[oncontextmenu],[onpaste],[style*=\"user-select\"]');"
                + "for(var i=0;i<nodes.length;i++){"
                + "var el=nodes[i];clearProps(el);"
                + "for(var j=0;j<props.length;j++){if(el.hasAttribute(props[j]))el.removeAttribute(props[j]);}"
                + "if(el.style){el.style.userSelect='text';el.style.webkitUserSelect='text';}"
                + "}"
                + "}catch(e){}"
                + "if(!window.__aikoCopyUnlocked){"
                + "window.__aikoCopyUnlocked=true;"
                + "var evts=['copy','cut','selectstart','contextmenu','paste','dragstart'];"
                + "var stop=function(e){e.stopImmediatePropagation();};"
                + "for(var k=0;k<evts.length;k++){"
                + "window.addEventListener(evts[k],stop,true);"
                + "document.addEventListener(evts[k],stop,true);"
                + "}"
                + "['onselectstart','oncopy','oncut','oncontextmenu','onpaste'].forEach(function(p){"
                + "try{Object.defineProperty(document,p,{configurable:true,get:function(){return null;},set:function(){}});}catch(e){}"
                + "});"
                + "}"
                + "return 'ok';"
                + "})();";
    }

    /**
     * Extracts visible and lazy-loaded high-res images (img, data-src/original, srcset largest candidate,
     * picture source, and large CSS background-image), filtering tiny icons/tracking pixels and deduplicating.
     * Returns JSON array string: [{url, alt, width, height}].
     */
    public static String extractGalleryImagesScript() {
        return "(function(){"
                + "'use strict';"
                + "var MIN_DIM=120,MAX_ITEMS=200;"
                + "var byCanon=new Map();"
                + "function resolveUrl(raw){"
                + "if(!raw||typeof raw!=='string')return '';"
                + "var s=raw.trim();if(!s||/^data:image\\/(svg\\+xml|gif)/i.test(s))return '';"
                + "try{var u=new URL(s,location.href);if(u.protocol!=='http:'&&u.protocol!=='https:')return '';return u.href;}catch(e){return '';}"
                + "}"
                + "function canonicalKey(href){"
                + "try{var u=new URL(href);u.hash='';return u.href;}catch(e){return href;}"
                + "}"
                + "function isIconUrl(href){return /\\.svg(\\?|#|$)/i.test(href);}"
                + "function bestFromSrcset(ss){"
                + "if(!ss||typeof ss!=='string')return '';"
                + "var best='',bestScore=-1;"
                + "ss.split(',').forEach(function(part){"
                + "var seg=part.trim().split(/\\s+/);if(!seg[0])return;"
                + "var score=1;if(seg[1]){var m=seg[1].match(/^([\\d.]+)(w|x)$/i);if(m)score=parseFloat(m[1])*(m[2].toLowerCase()==='x'?1000:1);}"
                + "if(score>=bestScore){bestScore=score;best=seg[0];}"
                + "});"
                + "return best;"
                + "}"
                + "function record(rawUrl,alt,w,h,skipMinCheck){"
                + "var href=resolveUrl(rawUrl);if(!href||isIconUrl(href))return;"
                + "var ww=Math.round(Number(w)||0),hh=Math.round(Number(h)||0);"
                + "if(!skipMinCheck){"
                + "if((ww>0&&ww<MIN_DIM)||(hh>0&&hh<MIN_DIM))return;"
                + "if(ww===0&&hh===0)return;"
                + "}"
                + "var key=canonicalKey(href);"
                + "var prev=byCanon.get(key);"
                + "var area=ww*hh;"
                + "if(!prev||area>(prev.width*prev.height)||(!prev.alt&&alt)){"
                + "byCanon.set(key,{url:href,alt:String(alt||'').trim().slice(0,200),width:ww,height:hh});"
                + "}"
                + "}"
                + "var lazyAttrs=['data-original','data-highres','data-full-src','data-large-src','data-src','data-lazy-src','data-actualsrc','data-url'];"
                + "var imgs=document.querySelectorAll('img');"
                + "for(var i=0;i<imgs.length;i++){"
                + "var img=imgs[i];"
                + "var r=img.getBoundingClientRect();"
                + "var attrW=parseInt(img.getAttribute('width'),10)||0;"
                + "var attrH=parseInt(img.getAttribute('height'),10)||0;"
                + "var natW=img.naturalWidth||0,natH=img.naturalHeight||0;"
                + "var w=Math.max(natW,Math.round(r.width||0),attrW);"
                + "var h=Math.max(natH,Math.round(r.height||0),attrH);"
                + "var alt=img.getAttribute('alt')||img.getAttribute('title')||'';"
                + "var picked='',lazy=false;"
                + "for(var a=0;a<lazyAttrs.length;a++){var v=img.getAttribute(lazyAttrs[a]);if(v&&v.trim()){picked=v.trim();lazy=true;break;}}"
                + "if(!picked){var ss=img.getAttribute('data-srcset')||img.getAttribute('srcset');if(ss){picked=bestFromSrcset(ss);lazy=true;}}"
                + "if(!picked){picked=img.currentSrc||img.getAttribute('src')||'';}"
                + "var explicitTiny=(attrW>0&&attrW<MIN_DIM)||(attrH>0&&attrH<MIN_DIM);"
                + "var skipMin=lazy&&!explicitTiny&&(natW<=2||natH<=2);"
                + "record(picked,alt,w,h,skipMin);"
                + "}"
                + "var sources=document.querySelectorAll('picture source[srcset], picture source[data-srcset]');"
                + "for(var p=0;p<sources.length;p++){"
                + "var sc=sources[p],pic=sc.parentElement,pImg=pic?pic.querySelector('img'):null;"
                + "var pr=pImg?pImg.getBoundingClientRect():{width:0,height:0};"
                + "var pw=Math.max(pImg?(pImg.naturalWidth||0):0,pr.width||0);"
                + "var ph=Math.max(pImg?(pImg.naturalHeight||0):0,pr.height||0);"
                + "var best=bestFromSrcset(sc.getAttribute('data-srcset')||sc.getAttribute('srcset'));"
                + "record(best,pImg?(pImg.alt||''):'',pw,ph,true);"
                + "}"
                + "if(window.getComputedStyle){"
                + "var all=document.querySelectorAll('[style*=\"background\"],div,figure,section,a,span');"
                + "var cap=Math.min(all.length,600);"
                + "for(var b=0;b<cap;b++){"
                + "var el=all[b],br=el.getBoundingClientRect();"
                + "if(br.width<MIN_DIM||br.height<MIN_DIM)continue;"
                + "var bg=getComputedStyle(el).backgroundImage;"
                + "if(!bg||bg==='none'||bg.indexOf('url(')<0)continue;"
                + "var m=bg.match(/url\\([\"']?([^\"')]+)[\"']?\\)/i);"
                + "if(m&&m[1])record(m[1],el.getAttribute('aria-label')||el.getAttribute('title')||'',br.width,br.height,false);"
                + "}"
                + "}"
                + "var list=Array.from(byCanon.values());"
                + "list.sort(function(x,y){return (y.width*y.height)-(x.width*x.height);});"
                + "return JSON.stringify(list.slice(0,MAX_ITEMS));"
                + "})();";
    }

    /**
     * Installs lightweight media hooks (fetch, XHR, video/audio/source, PerformanceResourceTiming)
     * for .mp4/.m3u8/.webm/.mpd/.mp3/.m4a/.flac/.ogg streams and exposes window.__aikoSniffedMedia().
     * Also returns the current JSON array [{url, type, title, sizeHint}] immediately.
     */
    public static String sniffMediaScript() {
        return "(function(){"
                + "'use strict';"
                + "var EXT_RE=/\\.(mp4|m3u8|webm|mpd|mp3|m4a|flac|ogg)(?:[?#]|$)/i;"
                + "var store=window.__aikoSniffedMediaStore||(window.__aikoSniffedMediaStore=new Map());"
                + "function guessType(url,hint){"
                + "var m=String(url||'').match(EXT_RE);"
                + "if(m)return m[1].toLowerCase();"
                + "var h=String(hint||'').toLowerCase();"
                + "if(h.indexOf('mpegurl')>=0)return 'm3u8';"
                + "if(h.indexOf('dash+xml')>=0)return 'mpd';"
                + "if(h.indexOf('mp4')>=0)return 'mp4';"
                + "if(h.indexOf('webm')>=0)return 'webm';"
                + "if(h.indexOf('audio/mpeg')>=0)return 'mp3';"
                + "if(h.indexOf('flac')>=0)return 'flac';"
                + "if(h.indexOf('ogg')>=0)return 'ogg';"
                + "return '';"
                + "}"
                + "function addMedia(rawUrl,mimeHint,title,sizeHint){"
                + "if(!rawUrl||typeof rawUrl!=='string')return;"
                + "var href;try{var u=new URL(rawUrl,location.href);if(u.protocol!=='http:'&&u.protocol!=='https:')return;href=u.href;}catch(e){return;}"
                + "var tp=guessType(href,mimeHint);if(!tp)return;"
                + "var prev=store.get(href);"
                + "var sz=Number(sizeHint)||0;"
                + "if(!prev||(sz>0&&!prev.sizeHint)){"
                + "store.set(href,{url:href,type:tp,title:String(title||document.title||'').trim().slice(0,140),sizeHint:sz});"
                + "}"
                + "}"
                + "if(!window.__aikoMediaSnifferInstalled){"
                + "window.__aikoMediaSnifferInstalled=true;"
                + "if(typeof window.fetch==='function'){"
                + "var origFetch=window.fetch;"
                + "window.fetch=function(input){"
                + "var reqUrl=typeof input==='string'?input:(input&&input.url?input.url:'');"
                + "addMedia(reqUrl,'','',0);"
                + "return origFetch.apply(this,arguments).then(function(resp){"
                + "try{var ct=resp.headers&&resp.headers.get('content-type')||'';"
                + "var cl=Number(resp.headers&&resp.headers.get('content-length'))||0;"
                + "addMedia(resp.url||reqUrl,ct,'',cl);}catch(e){}"
                + "return resp;"
                + "});"
                + "};"
                + "}"
                + "if(typeof XMLHttpRequest==='function'){"
                + "var origOpen=XMLHttpRequest.prototype.open,origSend=XMLHttpRequest.prototype.send;"
                + "XMLHttpRequest.prototype.open=function(m,u){this.__aikoSniffUrl=String(u||'');addMedia(this.__aikoSniffUrl,'','',0);return origOpen.apply(this,arguments);};"
                + "XMLHttpRequest.prototype.send=function(){"
                + "var xhr=this;"
                + "xhr.addEventListener('load',function(){"
                + "try{addMedia(xhr.responseURL||xhr.__aikoSniffUrl,xhr.getResponseHeader('content-type')||'','',Number(xhr.getResponseHeader('content-length'))||0);}catch(e){}"
                + "},{once:true});"
                + "return origSend.apply(this,arguments);"
                + "};"
                + "}"
                + "document.addEventListener('play',function(e){"
                + "var t=e.target;if(t&&(t.currentSrc||t.src))addMedia(t.currentSrc||t.src,t.tagName==='AUDIO'?'audio/mpeg':'video/mp4',t.getAttribute('title')||document.title,0);"
                + "},true);"
                + "}"
                + "function scanDomAndPerf(){"
                + "var els=document.querySelectorAll('video,audio,source');"
                + "for(var i=0;i<els.length;i++){"
                + "var el=els[i];"
                + "var u=el.currentSrc||el.src||el.getAttribute('src')||'';"
                + "var tp=el.getAttribute('type')||'';"
                + "addMedia(u,tp,el.getAttribute('title')||document.title,0);"
                + "}"
                + "try{"
                + "if(window.performance&&typeof performance.getEntriesByType==='function'){"
                + "var entries=performance.getEntriesByType('resource')||[];"
                + "for(var j=0;j<entries.length;j++){"
                + "var en=entries[j];addMedia(en.name,'','',en.transferSize||en.encodedBodySize||0);"
                + "}"
                + "}"
                + "}catch(e){}"
                + "}"
                + "window.__aikoSniffedMedia=function(){"
                + "scanDomAndPerf();"
                + "return JSON.stringify(Array.from(store.values()).slice(0,100));"
                + "};"
                + "return window.__aikoSniffedMedia();"
                + "})();";
    }

    /**
     * Idempotently injects/updates <style id="__aiko_user_style"> with custom per-site CSS,
     * @font-face declarations, minimum readable font size floor, and optional viewport pinch-zoom unlock.
     */
    public static String customCssAndFontScript(String siteCss, String fontFaceCss, int minFontPx, boolean forceZoom) {
        StringBuilder css = new StringBuilder();
        if (fontFaceCss != null && !fontFaceCss.trim().isEmpty()) {
            css.append(fontFaceCss.trim()).append('\n');
        }
        int clampedMin = minFontPx <= 0 ? 0 : Math.max(8, Math.min(48, minFontPx));
        if (clampedMin > 0) {
            css.append("html{-webkit-text-size-adjust:100%!important;}\n")
                    .append("body,p,li,article,dd,td,blockquote{font-size:max(1em,")
                    .append(clampedMin)
                    .append("px);}\n");
        }
        if (siteCss != null && !siteCss.trim().isEmpty()) {
            css.append(siteCss.trim()).append('\n');
        }
        String quotedCss = jsQuote(css.toString());
        return "(function(){"
                + "'use strict';"
                + "var cssText=" + quotedCss + ";"
                + "var id='__aiko_user_style';"
                + "var existing=document.getElementById(id);"
                + "if(!cssText){if(existing)existing.remove();}"
                + "else{"
                + "if(!existing){existing=document.createElement('style');existing.id=id;(document.head||document.documentElement).appendChild(existing);}"
                + "existing.textContent=cssText;"
                + "}"
                + (forceZoom
                ? "function unlockViewport(){"
                + "var metas=document.querySelectorAll('meta[name=\"viewport\" i]');"
                + "if(!metas.length){"
                + "var m=document.createElement('meta');m.name='viewport';m.content='width=device-width,initial-scale=1,user-scalable=yes,maximum-scale=5.0';"
                + "(document.head||document.documentElement).appendChild(m);return;"
                + "}"
                + "for(var i=0;i<metas.length;i++){"
                + "var c=metas[i].getAttribute('content')||'';"
                + "var parts=c.split(',').map(function(s){return s.trim();}).filter(function(s){"
                + "return s&&!/^user-scalable\\s*=/i.test(s)&&!/^maximum-scale\\s*=/i.test(s);"
                + "});"
                + "parts.push('user-scalable=yes','maximum-scale=5.0');"
                + "var next=parts.join(', ');"
                + "if(metas[i].getAttribute('content')!==next)metas[i].setAttribute('content',next);"
                + "}"
                + "}"
                + "unlockViewport();"
                + "if(!window.__aikoForceZoomObs&&typeof MutationObserver==='function'&&document.head){"
                + "window.__aikoForceZoomObs=true;"
                + "new MutationObserver(unlockViewport).observe(document.head,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});"
                + "}"
                : "")
                + "return 'ok';"
                + "})();";
    }

    /**
     * Injects safe, defensive anti-adblock scriptlets (neutralizes common adblock detection globals
     * and full-screen anti-adblock modal traps without breaking legitimate site functionality).
     */
    public static String antiAdblockScriptlets(String host) {
        String normalizedHost = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        return "(function(){"
                + "'use strict';"
                + "var host=" + jsQuote(normalizedHost) + ";"
                + "if(window.__aikoAntiAdblockApplied)return 'ok';"
                + "window.__aikoAntiAdblockApplied=true;"
                + "function defFlag(name,val){"
                + "try{"
                + "var d=Object.getOwnPropertyDescriptor(window,name);"
                + "if(d&&!d.configurable)return;"
                + "Object.defineProperty(window,name,{configurable:true,enumerable:false,get:function(){return val;},set:function(){}});"
                + "}catch(e){}"
                + "}"
                + "defFlag('canRunAds',true);"
                + "defFlag('isAdBlockActive',false);"
                + "defFlag('adblockDetected',false);"
                + "defFlag('adsBlocked',false);"
                + "defFlag('hasAdblock',false);"
                + "defFlag('adBlockEnabled',false);"
                + "var noopBab={"
                + "on:function(detected,fn){if(!detected&&typeof fn==='function')try{fn();}catch(e){}return this;},"
                + "onDetected:function(){return this;},"
                + "onNotDetected:function(fn){if(typeof fn==='function')try{fn();}catch(e){}return this;},"
                + "check:function(){return false;},"
                + "emitEvent:function(){return this;},"
                + "clearEvent:function(){return this;},"
                + "setOption:function(){return this;}"
                + "};"
                + "['blockAdBlock','fuckAdBlock','sniffAdBlock'].forEach(function(k){"
                + "try{if(window[k]===undefined)Object.defineProperty(window,k,{configurable:true,get:function(){return noopBab;},set:function(){}});}catch(e){}"
                + "});"
                + "var HINT_RE=/(adblock|ad-block|anti-ad|disable.*ad\\s*block|whitelist.*site|广告拦截|屏蔽广告|拦截插件|关闭拦截)/i;"
                + "function sweepOverlays(){"
                + "if(!document.body)return;"
                + "var removed=false;"
                + "var cands=document.querySelectorAll('.fc-ab-root,.fc-dialog-overlay,[id*=\"adblock\" i],[class*=\"adblock\" i],[class*=\"anti-ad\" i],[id*=\"anti-ad\" i]');"
                + "for(var i=0;i<cands.length;i++){"
                + "var el=cands[i];if(!el||!el.getBoundingClientRect)continue;"
                + "var cs=window.getComputedStyle?getComputedStyle(el):null;"
                + "if(!cs)continue;"
                + "var pos=cs.position,z=parseInt(cs.zIndex,10)||0;"
                + "var r=el.getBoundingClientRect();"
                + "var covers=(r.width>=window.innerWidth*0.45&&r.height>=window.innerHeight*0.35);"
                + "var isModal=(pos==='fixed'||pos==='sticky')&&(z>=100||covers);"
                + "var txt=(el.textContent||'').slice(0,600);"
                + "if(isModal&&(el.classList.contains('fc-ab-root')||el.classList.contains('fc-dialog-overlay')||HINT_RE.test(txt))){"
                + "el.remove();removed=true;"
                + "}"
                + "}"
                + "if(removed){"
                + "try{"
                + "if(document.documentElement.style.overflow==='hidden')document.documentElement.style.overflow='';"
                + "if(document.body.style.overflow==='hidden')document.body.style.overflow='';"
                + "}catch(e){}"
                + "}"
                + "}"
                + "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',sweepOverlays,{once:true});"
                + "else sweepOverlays();"
                + "setTimeout(sweepOverlays,1200);"
                + "return host?'ok:'+host:'ok';"
                + "})();";
    }

    /**
     * Installs automatic non-sensitive form draft tracking and exposes window.__aikoCollectDraft(),
     * window.__aikoRestoreDraft(json), and window.__aikoDraftDirty() to prevent lost posts/chats on reload/OOM.
     */
    public static String formDraftHookScript() {
        return "(function(){"
                + "'use strict';"
                + "var EXCLUDED_TYPES={password:1,hidden:1,file:1,submit:1,button:1,reset:1,image:1,checkbox:1,radio:1};"
                + "var SENSITIVE_RE=/(passw|pwd|secret|token|otp|cvv|cvc|card|cc-|credit|ssn|pin)/i;"
                + "function isTrackable(el){"
                + "if(!el||el.nodeType!==1)return false;"
                + "var tag=el.tagName;"
                + "if(tag==='TEXTAREA')return !el.disabled&&!el.readOnly;"
                + "if(tag==='INPUT'){"
                + "var tp=String(el.type||'text').toLowerCase();"
                + "if(EXCLUDED_TYPES[tp]||el.disabled||el.readOnly)return false;"
                + "var meta=(el.name||'')+' '+(el.id||'')+' '+(el.getAttribute('autocomplete')||'');"
                + "if(SENSITIVE_RE.test(meta))return false;"
                + "return true;"
                + "}"
                + "if(el.isContentEditable||el.getAttribute('contenteditable')==='true')return true;"
                + "return false;"
                + "}"
                + "function fieldKey(el,idx){"
                + "if(el.id)return 'id:'+el.id;"
                + "if(el.name)return 'name:'+el.tagName.toLowerCase()+':'+el.name;"
                + "var ph=el.getAttribute('placeholder')||el.getAttribute('aria-label')||'';"
                + "if(ph)return 'lbl:'+el.tagName.toLowerCase()+':'+ph.slice(0,48);"
                + "return 'idx:'+el.tagName.toLowerCase()+':'+idx;"
                + "}"
                + "function allFields(){"
                + "var list=document.querySelectorAll('textarea,input,[contenteditable=\"true\"]');"
                + "var out=[];for(var i=0;i<list.length;i++){if(isTrackable(list[i]))out.push(list[i]);}"
                + "return out;"
                + "}"
                + "var dirty=false;"
                + "if(!window.__aikoFormDraftInstalled){"
                + "window.__aikoFormDraftInstalled=true;"
                + "var onEdit=function(e){if(isTrackable(e.target))dirty=true;};"
                + "document.addEventListener('input',onEdit,true);"
                + "document.addEventListener('change',onEdit,true);"
                + "}"
                + "window.__aikoDraftDirty=function(){return dirty;};"
                + "window.__aikoCollectDraft=function(){"
                + "var els=allFields(),fields=[],totalChars=0;"
                + "for(var i=0;i<els.length&&fields.length<64;i++){"
                + "var el=els[i],ce=!(el.tagName==='TEXTAREA'||el.tagName==='INPUT');"
                + "var val=ce?(el.innerText||el.textContent||''):(el.value||'');"
                + "if(!val||!val.trim())continue;"
                + "if(totalChars+val.length>131072)break;"
                + "totalChars+=val.length;"
                + "fields.push({key:fieldKey(el,i),tag:el.tagName.toLowerCase(),type:ce?'contenteditable':String(el.type||'text').toLowerCase(),id:el.id||'',name:el.name||'',value:val,ce:ce});"
                + "}"
                + "return JSON.stringify({url:location.href,ts:Date.now(),fields:fields});"
                + "};"
                + "window.__aikoRestoreDraft=function(raw){"
                + "var data;try{data=typeof raw==='string'?JSON.parse(raw):raw;}catch(e){return 0;}"
                + "if(!data||!Array.isArray(data.fields))return 0;"
                + "var els=allFields(),byKey=new Map(),byId=new Map(),byName=new Map();"
                + "for(var i=0;i<els.length;i++){"
                + "var el=els[i];byKey.set(fieldKey(el,i),el);"
                + "if(el.id&&!byId.has(el.id))byId.set(el.id,el);"
                + "if(el.name&&!byName.has(el.name))byName.set(el.name,el);"
                + "}"
                + "var restored=0;"
                + "for(var j=0;j<data.fields.length;j++){"
                + "var item=data.fields[j];if(!item||typeof item.value!=='string')continue;"
                + "var target=(item.id&&byId.get(item.id))||(item.name&&byName.get(item.name))||byKey.get(item.key);"
                + "if(!target)continue;"
                + "if(item.ce&&!(target.tagName==='TEXTAREA'||target.tagName==='INPUT')){"
                + "target.textContent=item.value;"
                + "}else{"
                + "var proto=target.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var desc=Object.getOwnPropertyDescriptor(proto,'value');"
                + "if(desc&&desc.set)desc.set.call(target,item.value);else target.value=item.value;"
                + "}"
                + "target.dispatchEvent(new Event('input',{bubbles:true}));"
                + "target.dispatchEvent(new Event('change',{bubbles:true}));"
                + "restored++;"
                + "}"
                + "if(restored>0)dirty=true;"
                + "return restored;"
                + "};"
                + "return 'ok';"
                + "})();";
    }

    static String jsQuote(String s) {
        if (s == null) return "\"\"";
        StringBuilder out = new StringBuilder(s.length() + 16);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '<': out.append("\\u003c"); break;
                case '>': out.append("\\u003e"); break;
                case '\u2028': out.append("\\u2028"); break;
                case '\u2029': out.append("\\u2029"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
        return out.toString();
    }
}

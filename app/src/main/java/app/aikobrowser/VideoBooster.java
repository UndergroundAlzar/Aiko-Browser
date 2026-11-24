package app.aikobrowser;

import java.util.Locale;

/**
 * Zero-idle-overhead on-demand JavaScript helpers for HTML5 <video> playback control.
 * Nothing is injected into pages until the user explicitly opens or invokes Video Booster.
 */
public final class VideoBooster {
    public static final float MIN_RATE = 0.25f;
    public static final float MAX_RATE = 8.0f;

    /** Shared compact video collector across document, open shadow roots (bounded), and same-origin iframes. */
    private static final String COLLECT_VIDEOS_FN =
            "function _collect(root,out,depth){"
            + "if(!root||depth>4)return;"
            + "try{var vs=root.querySelectorAll?root.querySelectorAll('video'):[];"
            + "for(var i=0;i<vs.length;i++)out.push(vs[i]);}catch(e){}"
            + "try{var all=root.querySelectorAll?root.querySelectorAll('*'):[];"
            + "for(var j=0;j<all.length;j++){var sr=all[j].shadowRoot;if(sr)_collect(sr,out,depth+1);}}catch(e){}"
            + "if(depth===0&&root.querySelectorAll){try{var fs=root.querySelectorAll('iframe');"
            + "for(var k=0;k<fs.length;k++){try{var d=fs[k].contentDocument;if(d)_collect(d,out,1);}catch(e){}}}catch(e){}}"
            + "}"
            + "function _pick(list){"
            + "if(!list.length)return null;"
            + "for(var i=0;i<list.length;i++){if(!list[i].paused&&!list[i].ended)return list[i];}"
            + "var best=list[0],bestArea=-1;"
            + "for(var j=0;j<list.length;j++){try{var r=list[j].getBoundingClientRect();"
            + "var a=Math.max(0,r.width)*Math.max(0,r.height);"
            + "if(a>bestArea){bestArea=a;best=list[j];}}catch(e){}}"
            + "return best;"
            + "}";

    private static final String INSPECT_JS =
            "(function(){"
            + COLLECT_VIDEOS_FN
            + "var list=[];_collect(document,list,0);"
            + "var v=_pick(list);"
            + "var pip=false;"
            + "try{pip=!!(document.pictureInPictureEnabled&&typeof HTMLVideoElement!=='undefined'"
            + "&&HTMLVideoElement.prototype&&HTMLVideoElement.prototype.requestPictureInPicture);}catch(e){}"
            + "var rate=v?(Number(v.playbackRate)||1):(Number(window.__aikoVideoRate)||1);"
            + "return {count:list.length,rate:Math.round(rate*100)/100,"
            + "paused:v?!!v.paused:true,muted:v?!!v.muted:false,pipSupported:pip};"
            + "})();";

    private static final String TOGGLE_MUTE_JS =
            "(function(){"
            + COLLECT_VIDEOS_FN
            + "var list=[];_collect(document,list,0);"
            + "var v=_pick(list);"
            + "if(!v)return {count:0,muted:false};"
            + "var next=!v.muted;"
            + "for(var i=0;i<list.length;i++){try{list[i].muted=next;}catch(e){}}"
            + "return {count:list.length,muted:next};"
            + "})();";

    private static final String REQUEST_PIP_JS =
            "(function(){"
            + COLLECT_VIDEOS_FN
            + "try{"
            + "if(document.pictureInPictureElement){"
            + "document.exitPictureInPicture().catch(function(){});return 'exited';}"
            + "var list=[];_collect(document,list,0);"
            + "var v=_pick(list);"
            + "if(!v||!document.pictureInPictureEnabled||typeof v.requestPictureInPicture!=='function')return 'unsupported';"
            + "try{v.disablePictureInPicture=false;v.removeAttribute('disablepictureinpicture');}catch(e){}"
            + "v.requestPictureInPicture().catch(function(){});"
            + "return 'requested';"
            + "}catch(e){return 'unsupported';}"
            + "})();";

    private VideoBooster() {}

    /**
     * Inspects active <video> elements on the page.
     * When executed via WebView.evaluateJavascript, Chromium returns JSON:
     * {@code {"count":1,"rate":1.5,"paused":false,"muted":false,"pipSupported":true}}.
     */
    public static String inspectVideoJs() {
        return INSPECT_JS;
    }

    /**
     * Applies playbackRate to all <video> elements on the page.
     * When rate != 1.0, installs a lightweight page-local hook for newly inserted or started videos;
     * when reset to 1.0, tears down the hook cleanly so idle overhead returns to zero.
     */
    public static String setRateJs(float rate) {
        float clamped = clampRate(rate);
        String rateLiteral = String.format(Locale.ROOT, "%.2f", clamped);
        boolean active = Math.abs(clamped - 1.0f) >= 0.005f;
        return "(function(){"
                + COLLECT_VIDEOS_FN
                + "var r=" + rateLiteral + ";"
                + "var active=" + (active ? "true" : "false") + ";"
                + "window.__aikoVideoRate=active?r:0;"
                + "function _apply(v){try{if(Math.abs((v.playbackRate||1)-r)>0.005){v.playbackRate=r;}v.defaultPlaybackRate=r;}catch(e){}}"
                + "function _bind(v){if(!v||v.__aikoHooked)return;v.__aikoHooked=true;"
                + "var fn=function(){var t=window.__aikoVideoRate;if(t&&Math.abs((v.playbackRate||1)-t)>0.005){try{v.playbackRate=t;}catch(e){}}};"
                + "v.addEventListener('play',fn,true);v.addEventListener('loadedmetadata',fn,true);}"
                + "var list=[];_collect(document,list,0);"
                + "for(var i=0;i<list.length;i++){_apply(list[i]);if(active)_bind(list[i]);}"
                + "if(active){"
                + "if(!window.__aikoVideoObs&&typeof MutationObserver!=='undefined'&&document.documentElement){"
                + "var obs=new MutationObserver(function(muts){"
                + "var t=window.__aikoVideoRate;if(!t)return;"
                + "for(var m=0;m<muts.length;m++){var nodes=muts[m].addedNodes;if(!nodes)continue;"
                + "for(var n=0;n<nodes.length;n++){var el=nodes[n];if(!el||el.nodeType!==1)continue;"
                + "if(el.tagName==='VIDEO'){try{el.playbackRate=t;el.defaultPlaybackRate=t;}catch(e){}_bind(el);}"
                + "else if(el.querySelectorAll){var sub=el.querySelectorAll('video');"
                + "for(var s=0;s<sub.length;s++){try{sub[s].playbackRate=t;sub[s].defaultPlaybackRate=t;}catch(e){}_bind(sub[s]);}}"
                + "}}});"
                + "obs.observe(document.documentElement,{childList:true,subtree:true});"
                + "window.__aikoVideoObs=obs;}"
                + "}else if(window.__aikoVideoObs){"
                + "try{window.__aikoVideoObs.disconnect();}catch(e){}"
                + "window.__aikoVideoObs=null;}"
                + "return {count:list.length,rate:r};"
                + "})();";
    }

    /** Toggles mute state across all <video> elements on the page. */
    public static String toggleMuteJs() {
        return TOGGLE_MUTE_JS;
    }

    /** Requests (or exits) Picture-in-Picture for the primary active <video> element. */
    public static String requestPipJs() {
        return REQUEST_PIP_JS;
    }

    private static final String FULLSCREEN_GESTURE_JS =
            "(function(){"
            + "if(window.__aikoFsGesturesInstalled)return 'active';"
            + "window.__aikoFsGesturesInstalled=true;"
            + COLLECT_VIDEOS_FN
            + "var pill=null,dim=null,pillTimer=0,bright=1.0;"
            + "function _fsEl(){return document.fullscreenElement||document.webkitFullscreenElement||null;}"
            + "function _host(){return _fsEl()||document.body||document.documentElement;}"
            + "function _ensureUi(){"
            + "var host=_host();if(!host)return;"
            + "if(!dim){dim=document.createElement('div');dim.id='__aiko_fs_dim';"
            + "dim.style.cssText='position:fixed;inset:0;background:#000;opacity:0;pointer-events:none;z-index:2147483646;transition:opacity 60ms linear;';}"
            + "if(dim.parentNode!==host)host.appendChild(dim);"
            + "if(!pill){pill=document.createElement('div');pill.id='__aiko_fs_pill';"
            + "pill.style.cssText='position:fixed;top:14%;left:50%;transform:translateX(-50%);padding:8px 16px;border-radius:999px;"
            + "background:rgba(15,18,28,0.86);color:#F3F6FC;font:600 14px/1.2 -apple-system,sans-serif;letter-spacing:0.01em;"
            + "border:1px solid rgba(255,255,255,0.18);pointer-events:none;z-index:2147483647;opacity:0;transition:opacity 120ms ease;white-space:nowrap;';}"
            + "if(pill.parentNode!==host)host.appendChild(pill);"
            + "}"
            + "function _showPill(text,persist){"
            + "_ensureUi();if(!pill)return;"
            + "pill.textContent=text;pill.style.opacity='1';"
            + "clearTimeout(pillTimer);"
            + "if(!persist)pillTimer=setTimeout(function(){if(pill)pill.style.opacity='0';},900);"
            + "}"
            + "function _hidePill(){clearTimeout(pillTimer);if(pill)pill.style.opacity='0';}"
            + "function _fmt(sec){if(!isFinite(sec)||sec<0)sec=0;var s=Math.floor(sec);var m=Math.floor(s/60);var h=Math.floor(m/60);"
            + "var rs=s%60,rm=m%60;var p=function(n){return n<10?'0'+n:''+n;};return h>0?(h+':'+p(rm)+':'+p(rs)):(p(rm)+':'+p(rs));}"
            + "var mode='',sx=0,sy=0,st=0,sv=1,sb=1,targetV=null,lpTimer=0,lpActive=false,prevRate=1,seekTarget=0;"
            + "function _endLongPress(){"
            + "clearTimeout(lpTimer);lpTimer=0;"
            + "if(lpActive&&targetV){try{targetV.playbackRate=prevRate;}catch(e){}}"
            + "if(lpActive){lpActive=false;_hidePill();}"
            + "}"
            + "function _findVideo(){"
            + "var fs=_fsEl();var list=[];"
            + "if(fs){if(fs.tagName==='VIDEO')return fs;_collect(fs,list,0);}"
            + "if(!list.length)_collect(document,list,0);"
            + "return _pick(list);"
            + "}"
            + "document.addEventListener('pointerdown',function(e){"
            + "if(!e.isPrimary||e.pointerType==='mouse'&&e.button!==0)return;"
            + "if(!_fsEl())return;"
            + "var v=_findVideo();if(!v)return;"
            + "targetV=v;mode='pending';sx=e.clientX;sy=e.clientY;st=v.currentTime||0;sv=isFinite(v.volume)?v.volume:1;sb=bright;"
            + "clearTimeout(lpTimer);"
            + "lpTimer=setTimeout(function(){"
            + "if(mode!=='pending'||!targetV)return;"
            + "mode='boost';lpActive=true;prevRate=Number(targetV.playbackRate)||1;"
            + "try{targetV.playbackRate=2.0;}catch(ex){}"
            + "_showPill('\\u26a1 2.0x',true);"
            + "},350);"
            + "},true);"
            + "document.addEventListener('pointermove',function(e){"
            + "if(!e.isPrimary||!mode||mode==='boost'||!targetV)return;"
            + "var dx=e.clientX-sx,dy=e.clientY-sy;"
            + "if(mode==='pending'){"
            + "if(Math.abs(dx)<14&&Math.abs(dy)<14)return;"
            + "clearTimeout(lpTimer);lpTimer=0;"
            + "var vw=window.innerWidth||360;"
            + "if(Math.abs(dx)>Math.abs(dy)*1.25){mode='seek';}"
            + "else if(sx<vw*0.45){mode='bright';}"
            + "else{mode='vol';}"
            + "}"
            + "if(mode==='seek'){"
            + "var dur=isFinite(targetV.duration)&&targetV.duration>0?targetV.duration:0;"
            + "var delta=Math.round((dx/(window.innerWidth||360))*90);"
            + "seekTarget=Math.max(0,dur>0?Math.min(dur,st+delta):st+delta);"
            + "var sign=delta>=0?'+':'';"
            + "_showPill(sign+delta+'s \\u00b7 '+_fmt(seekTarget)+(dur>0?' / '+_fmt(dur):''),true);"
            + "}else if(mode==='bright'){"
            + "var vh=Math.max(240,window.innerHeight||640);"
            + "bright=Math.max(0.2,Math.min(1.0,sb+(-dy/(vh*0.65))));"
            + "_ensureUi();if(dim)dim.style.opacity=String(Math.round((1-bright)*75)/100);"
            + "try{if(window.__aikoVideoBridge&&window.__aikoVideoBridge.onBrightness)window.__aikoVideoBridge.onBrightness(bright);}catch(ex){}"
            + "_showPill('\\u2600 '+Math.round(bright*100)+'%',true);"
            + "}else if(mode==='vol'){"
            + "var vh2=Math.max(240,window.innerHeight||640);"
            + "var nv=Math.max(0,Math.min(1,sv+(-dy/(vh2*0.65))));"
            + "try{targetV.muted=false;targetV.volume=nv;}catch(ex){}"
            + "try{if(window.__aikoVideoBridge&&window.__aikoVideoBridge.onVolume)window.__aikoVideoBridge.onVolume(nv);}catch(ex){}"
            + "_showPill('\\ud83d\\udd0a '+Math.round(nv*100)+'%',true);"
            + "}"
            + "},true);"
            + "function _finish(){"
            + "if(mode==='seek'&&targetV){try{targetV.currentTime=seekTarget;}catch(ex){}_showPill(_fmt(seekTarget),false);}"
            + "else if(mode==='bright'||mode==='vol'){_showPill(pill?pill.textContent:'',false);}"
            + "_endLongPress();mode='';targetV=null;"
            + "}"
            + "document.addEventListener('pointerup',_finish,true);"
            + "document.addEventListener('pointercancel',_finish,true);"
            + "var _onFsChange=function(){if(!_fsEl()){_endLongPress();mode='';targetV=null;bright=1.0;if(dim)dim.style.opacity='0';_hidePill();}};"
            + "document.addEventListener('fullscreenchange',_onFsChange,true);"
            + "document.addEventListener('webkitfullscreenchange',_onFsChange,true);"
            + "return 'installed';"
            + "})();";

    public static String fullscreenGestureScript() {
        return FULLSCREEN_GESTURE_JS;
    }

    /**
     * Enables background playback by overriding Document visibility getters and suppressing
     * visibilitychange/blur/pagehide/freeze auto-pause hooks cleanly.
     */
    public static String backgroundPlaybackScript() {
        return backgroundPlaybackScript(true);
    }

    public static String backgroundPlaybackScript(boolean enable) {
        return "(function(){"
                + "window.__aikoBgAudio=" + (enable ? "true" : "false") + ";"
                + "if(window.__aikoBgAudioHooked)return window.__aikoBgAudio?'enabled':'disabled';"
                + "window.__aikoBgAudioHooked=true;"
                + "try{"
                + "var proto=Document.prototype||document;"
                + "var hidDesc=Object.getOwnPropertyDescriptor(proto,'hidden')||Object.getOwnPropertyDescriptor(document,'hidden');"
                + "var visDesc=Object.getOwnPropertyDescriptor(proto,'visibilityState')||Object.getOwnPropertyDescriptor(document,'visibilityState');"
                + "if(!hidDesc||hidDesc.configurable){"
                + "Object.defineProperty(document,'hidden',{configurable:true,get:function(){return window.__aikoBgAudio?false:(hidDesc&&hidDesc.get?hidDesc.get.call(document):false);}});"
                + "}"
                + "if(!visDesc||visDesc.configurable){"
                + "Object.defineProperty(document,'visibilityState',{configurable:true,get:function(){return window.__aikoBgAudio?'visible':(visDesc&&visDesc.get?visDesc.get.call(document):'visible');}});"
                + "}"
                + "}catch(e){}"
                + "var stop=function(e){if(window.__aikoBgAudio&&e&&e.stopImmediatePropagation){e.stopImmediatePropagation();}};"
                + "var evs=['visibilitychange','webkitvisibilitychange','pagehide','freeze'];"
                + "for(var i=0;i<evs.length;i++){document.addEventListener(evs[i],stop,true);window.addEventListener(evs[i],stop,true);}"
                + "window.addEventListener('blur',stop,true);"
                + "return window.__aikoBgAudio?'enabled':'disabled';"
                + "})();";
    }

    public static float clampRate(float rate) {
        if (Float.isNaN(rate) || Float.isInfinite(rate)) return 1.0f;
        return Math.max(MIN_RATE, Math.min(MAX_RATE, rate));
    }
}

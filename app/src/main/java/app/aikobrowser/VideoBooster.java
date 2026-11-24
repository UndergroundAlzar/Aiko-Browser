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

    public static float clampRate(float rate) {
        if (Float.isNaN(rate) || Float.isInfinite(rate)) return 1.0f;
        return Math.max(MIN_RATE, Math.min(MAX_RATE, rate));
    }
}

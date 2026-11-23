import fs from 'node:fs';
import {performance} from 'node:perf_hooks';
import vm from 'node:vm';

const runtimePath = process.argv[2]
    ? new URL(`file:///${process.argv[2].replace(/\\/g, '/')}`)
    : new URL('../app/src/main/assets/userscripts/runtime.js', import.meta.url);
const program = fs.readFileSync(runtimePath, 'utf8');

// Realistic config with ~4 KB of stored GM values + 3 @match scopes
const bigValues = {};
for (let k = 0; k < 24; k++) bigValues[`key_${k}`] = `stored-value-${k}-` + 'x'.repeat(140);
const staticConfig = {
    id: 'bench-script',
    release: '0.2.0',
    runAt: 'document-start',
    includes: [
        ['*', 'forum.example', true, -1, '/wiki/*'],
        ['https', 'linux.do', true, -1, '/t/*'],
        ['https', 'janitorai.com', false, -1, '/*']
    ],
    excludes: [['https', 'admin.forum.example', false, -1, '/*']],
    grants: ['GM_addStyle', 'GM_getValue', 'GM.setValue', 'GM_listValues', 'GM_deleteValue'],
    storage: true,
    script: {name: 'Bench Script', namespace: 'bench', version: '1.0'}
};
const fullConfig = {...staticConfig, values: bigValues};
const payloadJson = JSON.stringify(fullConfig);
const quotedConfigJson = JSON.stringify(payloadJson);
const quotedStaticJson = JSON.stringify(JSON.stringify(staticConfig));
const quotedValuesJson = JSON.stringify(JSON.stringify(bigValues));
const bodyJson = JSON.stringify("window.__benchRan = GM_getValue('key_0', 0);");

function makeContext(url, isIframe) {
    const location = new URL(url);
    const document = {
        readyState: 'loading',
        addEventListener() {},
        head: {appendChild() {}},
        createElement(tag) { return {tag}; }
    };
    const ctx = vm.createContext({
        location,
        document,
        console: {log() {}, warn() {}},
        __aikoScriptValues: {postMessage() {}},
        requestIdleCallback(fn) { fn(); },
        setTimeout(fn) { fn(); }
    });
    ctx.window = ctx;
    ctx.top = isIframe ? {} : ctx;
    return ctx;
}

function pct(sorted, p) {
    const i = Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * p) - 1));
    return sorted[i];
}

function measureBurst(script, ctxs, warmup, iters) {
    for (let w = 0; w < warmup; w++) {
        for (let j = 0; j < ctxs.length; j++) script.runInContext(ctxs[j]);
    }
    const samples = [];
    for (let w = 0; w < iters; w++) {
        const t0 = performance.now();
        for (let j = 0; j < ctxs.length; j++) script.runInContext(ctxs[j]);
        samples.push((performance.now() - t0) * 1000);
    }
    samples.sort((a, b) => a - b);
    return samples;
}

const iframeCtxs = Array.from({length: 10}, (_, i) =>
    makeContext(`https://embed-${i}.cdn.example/frame?id=${i}`, true));
const nonMatchCtx = [makeContext('https://news.unrelated.example/article/123', false)];

// Mode A: v0.1.9 actual WebView injection wrapper (JSON.parse evaluated BEFORE calling runtime.js)
const v019Wrapper = new vm.Script(`(${program})(JSON.parse(${quotedConfigJson}),${bodyJson});`);
const v019Iframes = measureBurst(v019Wrapper, iframeCtxs, 100, 500);
const v019NonMatch = measureBurst(v019Wrapper, nonMatchCtx, 200, 1000);

// Mode B: v0.2.0 actual WebView pre-guard wrapper (exact UserScripts.java:649-701 structure)
const v020Guard =
    'if(location.protocol!=="https:"&&location.protocol!=="http:")return;' +
    'var h=location.hostname.toLowerCase();if(h.endsWith("."))h=h.slice(0,-1);' +
    'if(!(h==="forum.example"||h.endsWith(".forum.example")||h==="linux.do"||h.endsWith(".linux.do")||h==="janitorai.com"))return;';
const v020Wrapper = new vm.Script(
    `(function(){if(window.top!==window)return;${v020Guard}(${program})(Object.assign(JSON.parse(${quotedStaticJson}),{values:JSON.parse(${quotedValuesJson})}),${bodyJson});})();`
);
const v020Iframes = measureBurst(v020Wrapper, iframeCtxs, 100, 500);
const v020NonMatch = measureBurst(v020Wrapper, nonMatchCtx, 200, 1000);

console.log(
    `USERSCRIPT_V019_WRAPPER_10_IFRAMES_US p50=${pct(v019Iframes, 0.5).toFixed(1)} ` +
    `p95=${pct(v019Iframes, 0.95).toFixed(1)} p99=${pct(v019Iframes, 0.99).toFixed(1)} ` +
    `config_bytes=${payloadJson.length}`
);
console.log(
    `USERSCRIPT_V019_WRAPPER_NONMATCH_TOP_US p50=${pct(v019NonMatch, 0.5).toFixed(1)} ` +
    `p95=${pct(v019NonMatch, 0.95).toFixed(1)}`
);
console.log(
    `USERSCRIPT_V020_GUARD_10_IFRAMES_US p50=${pct(v020Iframes, 0.5).toFixed(1)} ` +
    `p95=${pct(v020Iframes, 0.95).toFixed(1)} p99=${pct(v020Iframes, 0.99).toFixed(1)}`
);
console.log(
    `USERSCRIPT_V020_GUARD_NONMATCH_TOP_US p50=${pct(v020NonMatch, 0.5).toFixed(1)} ` +
    `p95=${pct(v020NonMatch, 0.95).toFixed(1)}`
);

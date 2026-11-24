import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const program = fs.readFileSync(new URL('../app/src/main/assets/userscripts/runtime.js', import.meta.url), 'utf8');
const config = {
    id: 'fixture', release: '0.1.8', runAt: 'document-start',
    includes: [['*', 'forum.example', true, -1, '/wiki/*']],
    excludes: [['https', 'admin.forum.example', false, -1, '/*']],
    grants: ['GM_addStyle', 'GM_getValue', 'GM.setValue', 'GM_listValues', 'GM_deleteValue'],
    storage: true, script: {name: 'Fixture', namespace: 'test', version: '1'},
    values: {counter: 3}
};
function fixture(url, frame = false) {
    const state = {messages: [], styles: [], logs: [], ready: new Map(), idle: []};
    const location = new URL(url);
    const document = {
        readyState: 'loading',
        addEventListener(type, action) { state.ready.set(type, action); },
        head: {appendChild(style) { state.styles.push(style.textContent); }},
        createElement(tag) { return {tag}; }
    };
    const ctx = vm.createContext({
        state, location, document,
        console: {log(...message) { state.logs.push(message); }, warn(...message) { state.logs.push(message); }},
        __aikoScriptValues: {postMessage(message) { state.messages.push(JSON.parse(message)); }},
        requestIdleCallback(run) { state.idle.push(run); },
        setTimeout(run) { state.idle.push(run); }
    });
    ctx.window = ctx;
    ctx.top = frame ? {} : ctx;
    return {
        state,
        ctx,
        run(data, code) { return vm.runInContext(`(${program})(${JSON.stringify(data)},${JSON.stringify(code)});`, ctx); },
        ready() { document.readyState = 'interactive'; state.ready.get('DOMContentLoaded')?.(); }
    };
}

const page = fixture('https://chat.forum.example/wiki/example#jump');
page.run(config, `
    window.countAtStart = GM_getValue('counter', 0);
    window.manager = GM_info.scriptHandler;
    GM_addStyle('.test { display: none; }');
    window.write = GM.setValue('counter', 4);
    window.listed = GM_listValues();
    GM_deleteValue('counter');
    window.afterDelete = GM_getValue('counter', 'missing');
    window.noGrant = typeof GM_setValue;
`);
assert.equal(page.ctx.countAtStart, 3);
assert.equal(page.ctx.manager, 'Aiko Browser');
await page.ctx.write;
assert.deepEqual(Array.from(page.ctx.listed), ['counter']);
assert.equal(page.ctx.afterDelete, 'missing');
assert.equal(page.ctx.noGrant, 'undefined');
assert.deepEqual(page.state.styles, ['.test { display: none; }']);
assert.equal(page.state.messages.length, 2);
assert.deepEqual(page.state.messages[0].slice(1), ['set', 'counter', '4']);
assert.deepEqual(page.state.messages[1].slice(1), ['delete', 'counter']);
page.run(config, 'window.ranTwice = true;');
assert.equal(page.ctx.ranTwice, undefined);

const end = fixture('https://forum.example/wiki/long');
end.run({...config, runAt: 'document-end'}, 'window.wasReady = document.readyState;');
assert.equal(end.ctx.wasReady, undefined);
end.ready();
assert.equal(end.ctx.wasReady, 'interactive');

const idle = fixture('https://forum.example/wiki/long');
idle.run({...config, runAt: 'document-idle', storage: false, grants: []}, 'window.idleRan = true;');
idle.ready();
assert.equal(idle.ctx.idleRan, undefined);
idle.state.idle.shift()();
assert.equal(idle.ctx.idleRan, true);
assert.equal(idle.state.messages.length, 0);

for (const [url, frame] of [
    ['https://admin.forum.example/wiki/hidden', false],
    ['https://outside.example/wiki/example', false],
    ['https://forum.example/no-scope', false],
    ['https://forum.example/wiki/example', true]
]) {
    const denied = fixture(url, frame);
    denied.run(config, 'window.executed = true;');
    denied.run(config, '}); window.escaped = true; (function(){');
    assert.equal(denied.ctx.executed, undefined, url);
    assert.equal(denied.ctx.escaped, undefined, url);
    assert.deepEqual(denied.state.logs, [], url);
}

// Verify GM_addValueChangeListener local + cross-tab remote sync + token-authenticated ephemeral bridge
{
    const remoteListeners = [];
    const posted = [];
    const document = {
        readyState: 'complete',
        addEventListener() {},
        head: {appendChild() {}},
        body: {appendChild() {}},
        createElement(tag) { return {tag, attrs: {}, setAttribute(k, v) { this.attrs[k] = v; }, appendChild() {}}; }
    };
    const bridgeObj = {
        postMessage(msg) { posted.push(JSON.parse(msg)); },
        addEventListener(type, fn) { if (type === 'message') remoteListeners.push(fn); }
    };
    const ctx = vm.createContext({
        location: new URL('https://janitorai.com/zh/chats/1373717782'),
        document,
        console: {log() {}, warn() {}},
        __aikoSv_deadbeef0001: bridgeObj,
        requestIdleCallback(fn) { fn(); },
        setTimeout(fn) { fn(); }
    });
    ctx.window = ctx;
    ctx.top = ctx;
    const cfg = {
        id: 'janitor-test', release: '0.3.1', runAt: 'document-start',
        includes: [['https', 'janitorai.com', false, -1, '/*']],
        excludes: [],
        grants: ['GM_getValue', 'GM_setValue', 'GM_addValueChangeListener', 'GM_removeValueChangeListener', 'GM_addElement'],
        storage: true,
        bridge: '__aikoSv_deadbeef0001',
        token: 'secret-token-1234',
        ephemeralBridge: true,
        script: {name: 'Aiko Janitor', namespace: 'aiko.janitor', version: '1.0.1'},
        values: {'aiko-janitor.settings.v1': {enabled: false}}
    };
    vm.runInContext(`(${program})(${JSON.stringify(cfg)},${JSON.stringify(`
        window.bridgeLeaked = typeof __aikoSv_deadbeef0001 !== 'undefined';
        window.events = [];
        const lid = GM_addValueChangeListener('aiko-janitor.settings.v1', (k, oldV, newV, remote) => {
            window.events.push({k, oldEnabled: oldV?.enabled, newEnabled: newV?.enabled, remote});
        });
        GM_setValue('aiko-janitor.settings.v1', {enabled: true});
        window.el = GM_addElement('meta', {name: 'aiko-test', content: 'ok'});
        window.lid = lid;
    `)});`, ctx);
    assert.equal(ctx.bridgeLeaked, false, 'ephemeral bridge deleted before body runs');
    assert.equal(posted.length, 2, 'hello + set messages posted');
    assert.deepEqual(posted[0].slice(1), ['hello', 'secret-token-1234']);
    assert.equal(posted[1][1], 'set');
    assert.equal(posted[1][4], 'secret-token-1234');
    assert.equal(ctx.events.length, 1);
    assert.equal(ctx.events[0].remote, false);
    assert.equal(ctx.events[0].newEnabled, true);
    assert.equal(ctx.el.attrs.content, 'ok');

    // Simulate remote cross-tab push via JavaScriptReplyProxy
    for (const fn of remoteListeners) {
        fn({data: JSON.stringify(['aiko-janitor.settings.v1', true, {enabled: true}, true, {enabled: false}])});
    }
    assert.equal(ctx.events.length, 2);
    assert.equal(ctx.events[1].remote, true);
    assert.equal(ctx.events[1].newEnabled, false);
}

// Verify v0.4.0 Tampermonkey APIs: GM_xmlhttpRequest, GM.xmlHttpRequest, GM_getResourceText/URL,
// bidirectional GM_registerMenuCommand / GM_unregisterMenuCommand, and GM_download!
{
    const remoteListeners = [];
    const posted = [];
    const document = {
        readyState: 'complete',
        addEventListener() {},
        head: {appendChild() {}},
        body: {appendChild() {}},
        createElement(tag) { return {tag, attrs: {}, setAttribute(k, v) { this.attrs[k] = v; }, appendChild() {}}; }
    };
    const bridgeObj = {
        postMessage(msg) { posted.push(JSON.parse(msg)); },
        addEventListener(type, fn) { if (type === 'message') remoteListeners.push(fn); }
    };
    const sendNative = (obj) => {
        const raw = JSON.stringify(obj);
        for (const fn of remoteListeners) fn({data: raw});
    };
    const ctx = vm.createContext({
        location: new URL('https://app.example.com/dashboard'),
        URL, TextEncoder, TextDecoder, atob, btoa,
        document,
        console: {log() {}, warn() {}},
        __aikoScriptValues: bridgeObj,
        requestIdleCallback(fn) { fn(); },
        setTimeout(fn) { fn(); }
    });
    ctx.window = ctx;
    ctx.top = ctx;

    const pngB64 = Buffer.from([0x89, 0x50, 0x4E, 0x47]).toString('base64');
    const cfg = {
        id: 'tm-v040', release: '0.4.0', runAt: 'document-start',
        includes: [['https', 'app.example.com', false, -1, '/*']],
        excludes: [],
        grants: [
            'GM_xmlhttpRequest', 'GM.xmlHttpRequest',
            'GM_getResourceText', 'GM.getResourceText',
            'GM_getResourceURL', 'GM.getResourceURL',
            'GM_registerMenuCommand', 'GM_unregisterMenuCommand',
            'GM_download'
        ],
        storage: false,
        bridgeEnabled: true,
        script: {name: 'TM Full', namespace: 'aiko.test', version: '2.0.0'},
        resources: {
            themeCss: {mime: 'text/css', text: 'body { color: #7C9BFF; } /* 中文 */'},
            iconBin: {mime: 'image/png', b64: pngB64}
        },
        values: {}
    };

    vm.runInContext(`(${program})(${JSON.stringify(cfg)},${JSON.stringify(`
        window.resText = GM_getResourceText('themeCss');
        window.resUrl = GM_getResourceURL('themeCss');
        window.resBinUrl = GM_getResourceURL('iconBin');
        window.resMissing = GM_getResourceText('nope');
        window.resAsyncText = GM.getResourceText('themeCss');
        window.resAsyncUrl = GM.getResourceURL('iconBin');

        window.menuClicks = 0;
        window.cmd1 = GM_registerMenuCommand('Open Settings', () => { window.menuClicks++; });
        window.cmd2 = GM_registerMenuCommand('Temp Action', () => { window.menuClicks += 100; });
        GM_unregisterMenuCommand(window.cmd2);

        window.xhrEvents = [];
        window.xhrHandle1 = GM_xmlhttpRequest({
            method: 'POST',
            url: 'https://api.example.com/data',
            headers: {'X-Test': '1'},
            data: '{"ping":true}',
            responseType: 'json',
            context: 'ctx-1',
            onreadystatechange(r) { window.xhrEvents.push('rs:' + r.readyState); },
            onload(r) { window.xhrResult1 = r; window.xhrEvents.push('load'); },
            onloadend(r) { window.xhrEvents.push('end:' + r.status); }
        });

        window.xhrAbortFired = false;
        window.xhrHandle2 = GM_xmlhttpRequest({
            method: 'GET',
            url: '/relative-bin',
            responseType: 'arraybuffer',
            onabort() { window.xhrAbortFired = true; }
        });
        window.xhrHandle2.abort();

        window.xhrBinPromise = GM.xmlHttpRequest({
            method: 'GET',
            url: 'https://api.example.com/bin',
            responseType: 'arraybuffer'
        });

        window.xhrErrPromise = GM.xmlHttpRequest({
            method: 'GET',
            url: 'https://blocked.example.com/secret'
        }).catch(err => ({caught: true, error: err.error}));

        window.dlLoaded = false;
        GM_download({url: 'https://cdn.example.com/file.zip', name: 'file.zip', onload() { window.dlLoaded = true; }});
    `)});`, ctx);

    // 1. Resources checks
    assert.equal(ctx.resText, 'body { color: #7C9BFF; } /* 中文 */');
    assert.ok(ctx.resUrl.startsWith('data:text/css;base64,'));
    assert.equal(Buffer.from(ctx.resUrl.split(',')[1], 'base64').toString('utf8'), ctx.resText);
    assert.equal(ctx.resBinUrl, 'data:image/png;base64,' + pngB64);
    assert.equal(ctx.resMissing, null);
    assert.equal(await ctx.resAsyncText, ctx.resText);
    assert.equal(await ctx.resAsyncUrl, ctx.resBinUrl);

    // 2. Menu commands bidirectional registration + invocation + unregister
    const menuRegs = posted.filter(m => m[1] === 'menu_reg');
    const menuUnregs = posted.filter(m => m[1] === 'menu_unreg');
    assert.equal(menuRegs.length, 2);
    assert.equal(menuRegs[0][2], ctx.cmd1);
    assert.equal(menuRegs[0][3], 'Open Settings');
    assert.equal(menuUnregs.length, 1);
    assert.equal(menuUnregs[0][2], ctx.cmd2);

    sendNative({type: 'menu_invoke', cmdId: ctx.cmd1});
    sendNative({type: 'menu_invoke', cmdId: ctx.cmd2}); // unregistered -> must not run!
    assert.equal(ctx.menuClicks, 1, 'invoking registered command calls handler, unregistered ignored');

    // 3. GM_xmlhttpRequest POST JSON + callbacks + abort + binary arraybuffer + error rejection
    const xhrPosts = posted.filter(m => m[1] === 'xhr').map(m => JSON.parse(m[2]));
    const xhrAborts = posted.filter(m => m[1] === 'xhr_abort');
    assert.equal(xhrPosts.length, 4);
    assert.equal(xhrPosts[0].method, 'POST');
    assert.equal(xhrPosts[0].url, 'https://api.example.com/data');
    assert.equal(xhrPosts[1].url, 'https://app.example.com/relative-bin', 'relative URL resolved');
    assert.equal(xhrAborts.length, 1);
    assert.equal(xhrAborts[0][2], xhrPosts[1].id);
    assert.equal(ctx.xhrAbortFired, true);

    sendNative({
        type: 'xhr_done',
        id: xhrPosts[0].id,
        status: 200,
        statusText: 'OK',
        finalUrl: 'https://api.example.com/data',
        responseHeaders: 'Content-Type: application/json\r\n',
        contentType: 'application/json',
        binary: false,
        body: '{"ok":true,"n":42}'
    });
    assert.equal(ctx.xhrResult1.status, 200);
    assert.equal(ctx.xhrResult1.context, 'ctx-1');
    assert.deepEqual({...ctx.xhrResult1.response}, {ok: true, n: 42});
    assert.deepEqual(Array.from(ctx.xhrEvents), ['rs:4', 'load', 'end:200']);

    const binPayload = Buffer.from([1, 2, 3, 255]).toString('base64');
    sendNative({
        type: 'xhr_done',
        id: xhrPosts[2].id,
        status: 200,
        statusText: 'OK',
        finalUrl: 'https://api.example.com/bin',
        responseHeaders: 'Content-Type: application/octet-stream\r\n',
        contentType: 'application/octet-stream',
        binary: true,
        body: binPayload
    });
    const binRes = await ctx.xhrBinPromise;
    assert.deepEqual(Array.from(new Uint8Array(binRes.response)), [1, 2, 3, 255]);

    sendNative({
        type: 'xhr_error',
        id: xhrPosts[3].id,
        status: 0,
        statusText: '',
        finalUrl: 'https://blocked.example.com/secret',
        error: '@connect denied for host: blocked.example.com'
    });
    const errRes = await ctx.xhrErrPromise;
    assert.equal(errRes.caught, true);
    assert.ok(errRes.error.includes('@connect denied'));

    // 4. GM_download check
    const downloads = posted.filter(m => m[1] === 'download');
    assert.equal(downloads.length, 1);
    assert.equal(downloads[0][2], 'https://cdn.example.com/file.zip');
    assert.equal(downloads[0][3], 'file.zip');
    assert.equal(ctx.dlLoaded, true);
}

console.log('PASS script runtime timing, document deduplication, privileges, storage messages, value listeners, XHR, resources, menu commands, downloads and URL/frame scope');

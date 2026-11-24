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
console.log('PASS script runtime timing, document deduplication, privileges, storage messages, value listeners and URL/frame scope');

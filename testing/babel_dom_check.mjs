import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { readFile, mkdtemp, realpath, rm } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { basename, dirname, resolve } from 'node:path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const repoRoot = resolve(__dirname, '..');
const workDir = resolve(repoRoot, '..');
const babelJsPath = resolve(repoRoot, 'app/src/main/assets/babel/babel.js');
const chromePath = process.env.AIKO_CHROMIUM || resolve(workDir, 'work/chrome-test/chrome-headless-shell-win64/chrome-headless-shell.exe');

assert.ok(existsSync(babelJsPath), 'babel.js must exist');
assert.ok(existsSync(chromePath), 'chrome-headless-shell.exe must exist at ' + chromePath);

const babelSource = await readFile(babelJsPath, 'utf8');

const fixtureHtml = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Babel DOM Verification Fixture</title>
  <style>
    body { font-family: system-ui, sans-serif; font-size: 16px; line-height: 1.5; margin: 16px; }
    .spacer { height: 2600px; }
  </style>
</head>
<body>
  <nav id="site-nav"><a href="#home">Home</a><a href="#docs">Docs</a></nav>
  <main>
    <h1 id="h1-title">Welcome to Aiko Browser Babel Engine</h1>
    <p id="p-intro">Immersive bilingual translation preserves page layout cleanly.</p>
    <ul>
      <li id="li-item">Supports five customizable bilingual reading styles.</li>
    </ul>
    <blockquote>
      <p id="bq-text">Knowledge grows when language barriers disappear.</p>
    </blockquote>
    <table>
      <tr><td id="td-cell">Fast zero-key translation engine</td></tr>
    </table>
    <div id="tweet" data-testid="tweetText">Shipping Aiko v0.4.0 with Babel bilingual mode!</div>

    <pre id="pre-block"><code id="code-block">const babel = window.__aikoBabel;</code></pre>
    <p id="kbd-only"><kbd>Ctrl</kbd> + <kbd>Shift</kbd></p>
    <svg id="svg-el" width="120" height="24"><text x="0" y="16">SVG Diagram Label</text></svg>
    <math id="math-el"><mi>x</mi><mo>+</mo><mi>y</mi></math>
    <div id="notranslate-div" class="notranslate"><p id="notranslate-p">This paragraph must never be translated.</p></div>
    <p id="translate-no" translate="no">Attribute translate=no must be skipped.</p>
    <button id="btn-skip" type="button">Submit Form</button>
    <p id="already-zh">这是一段已经是简体中文的段落，不应重复触发翻译。</p>

    <section id="inputs">
      <input id="txt-input" type="text" value="" />
      <input id="pwd-input" type="password" value="" />
      <textarea id="txt-area"></textarea>
      <div id="ce-box" contenteditable="true"></div>
    </section>

    <div class="spacer"></div>
    <p id="lazy-below">This far-below paragraph should wait for lazy intersection.</p>
  </main>
</body>
</html>`;

const server = createServer((req, res) => {
    res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Content-Security-Policy': "script-src 'none'; style-src 'unsafe-inline'"
    }).end(fixtureHtml);
}).listen(0, '127.0.0.1');
await once(server, 'listening');
const port = server.address().port;

const tempRoot = await realpath((await import('node:os')).tmpdir());
const userDataDir = await realpath(await mkdtemp(resolve(tempRoot, 'aiko-babel-chromium-')));
assert.equal(dirname(userDataDir), tempRoot);
assert.ok(basename(userDataDir).startsWith('aiko-babel-chromium-'));
const child = spawn(chromePath, [
    '--disable-gpu',
    '--disable-background-networking',
    '--no-first-run',
    '--disable-extensions',
    '--window-size=412,900',
    '--remote-debugging-port=0',
    '--user-data-dir=' + userDataDir,
    'about:blank'
], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });

let socket;
const watchdog = setTimeout(() => {
    try { child.kill(); } catch (_) {}
}, 30000);

try {
    const debugPort = await new Promise((done, fail) => {
        child.once('error', fail);
        child.once('exit', code => fail(new Error('Chromium exited early: ' + code)));
        let stderr = '';
        child.stderr.on('data', chunk => {
            stderr += chunk.toString();
            const m = stderr.match(/DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)\//);
            if (m) done(Number(m[1]));
        });
    });

    const targets = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json();
    const pageTarget = targets.find(t => t.type === 'page');
    assert.ok(pageTarget, 'CDP page target available');

    socket = new WebSocket(pageTarget.webSocketDebuggerUrl);
    await once(socket, 'open');

    let cdpId = 0;
    const inflight = new Map();
    const eventWaiters = new Map();
    socket.addEventListener('message', ({ data }) => {
        const msg = JSON.parse(data);
        if (msg.id && inflight.has(msg.id)) {
            inflight.get(msg.id)(msg);
            inflight.delete(msg.id);
        }
        if (msg.method && eventWaiters.has(msg.method)) {
            const fn = eventWaiters.get(msg.method);
            eventWaiters.delete(msg.method);
            fn(msg.params);
        }
    });

    async function cdp(method, params = {}) {
        const id = ++cdpId;
        const p = new Promise(res => inflight.set(id, res));
        socket.send(JSON.stringify({ id, method, params }));
        const resp = await p;
        if (resp.error) throw new Error('CDP error: ' + JSON.stringify(resp.error));
        return resp.result;
    }

    async function evalJson(expression) {
        const out = await cdp('Runtime.evaluate', {
            expression,
            returnByValue: true,
            awaitPromise: true,
            allowUnsafeEvalBlockedByCSP: false
        });
        if (out.exceptionDetails) {
            throw new Error('Page JS exception: ' + JSON.stringify(out.exceptionDetails));
        }
        return out.result.value;
    }

    await cdp('Page.enable');
    const loaded = new Promise(res => eventWaiters.set('Page.loadEventFired', res));
    await cdp('Page.navigate', { url: `http://127.0.0.1:${port}/` });
    await loaded;

    // Install mock native bridge before injecting babel.js
    await evalJson(`(() => {
        window.__bridgeLog = [];
        window.__aikoBabelBridge = {
            post(raw) {
                const msg = JSON.parse(raw);
                window.__bridgeLog.push(msg);
                if (msg.type === 'translate_batch') {
                    if (window.__holdBatches) return;
                    const prefix = msg.targetLang === 'ja' ? '【JA】' : '【译】';
                    const out = msg.items.map(it => prefix + it.text);
                    setTimeout(() => window.__aikoBabel.applyBatchResult(msg.reqId, out), 15);
                } else if (msg.type === 'translate_selection') {
                    setTimeout(() => window.__aikoBabel.applySelectionResult(msg.reqId, '【划词译】' + msg.text), 15);
                } else if (msg.type === 'translate_input') {
                    setTimeout(() => window.__aikoBabel.applyInputResult(msg.reqId, 'EN:' + msg.text), 15);
                }
            }
        };
        return true;
    })()`);

    // Inject babel.js TWICE to prove strict idempotency under strict CSP
    await evalJson(babelSource);
    await evalJson(babelSource);

    // 1. Start bilingual translation (BILINGUAL_SOFT) & verify lazy discovery + strict skipping
    const initial = await evalJson(`(async () => {
        window.__aikoBabel.configure({
            enabled: true,
            style: 'soft',
            targetLang: 'zh-CN',
            inputTargetLang: 'en',
            selectionTranslate: true,
            tripleSpaceInputTranslate: true,
            skipCodeAndNav: true,
            showFloatingFab: true,
            theme: { accent: '#34D399' }
        });
        await new Promise(r => setTimeout(r, 320));
        const hasTr = id => !!document.querySelector('#' + id + ' > font.aiko-babel-tr');
        const trText = id => document.querySelector('#' + id + ' > font.aiko-babel-tr')?.textContent || '';
        return {
            active: window.__aikoBabel.isActive(),
            h1: trText('h1-title'),
            intro: trText('p-intro'),
            li: trText('li-item'),
            bq: trText('bq-text'),
            td: trText('td-cell'),
            tweet: trText('tweet'),
            // Strictly skipped nodes:
            skipNav: !!document.querySelector('#site-nav font.aiko-babel-tr'),
            skipPre: !!document.querySelector('#pre-block font.aiko-babel-tr'),
            skipCode: !!document.querySelector('#code-block font.aiko-babel-tr'),
            skipKbd: hasTr('kbd-only'),
            skipSvg: !!document.querySelector('#svg-el font.aiko-babel-tr'),
            skipMath: !!document.querySelector('#math-el font.aiko-babel-tr'),
            skipNotranslate: hasTr('notranslate-p'),
            skipAttrNo: hasTr('translate-no'),
            skipBtn: hasTr('btn-skip'),
            skipZh: hasTr('already-zh'),
            lazyBeforeScroll: hasTr('lazy-below'),
            fabVisible: !!document.querySelector('.aiko-babel-fab'),
            origIntroUntouched: document.getElementById('p-intro').firstChild.nodeValue
        };
    })()`);

    assert.equal(initial.active, true, 'Babel active');
    assert.ok(initial.h1.startsWith('【译】Welcome'), 'h1 translated: ' + initial.h1);
    assert.ok(initial.intro.startsWith('【译】Immersive'), 'p translated');
    assert.ok(initial.li.startsWith('【译】Supports'), 'li translated');
    assert.ok(initial.bq.startsWith('【译】Knowledge'), 'blockquote p translated');
    assert.ok(initial.td.startsWith('【译】Fast'), 'td translated');
    assert.ok(initial.tweet.startsWith('【译】Shipping'), 'tweetText translated');
    assert.equal(initial.skipNav, false, 'nav skipped');
    assert.equal(initial.skipPre, false, 'pre skipped');
    assert.equal(initial.skipCode, false, 'code skipped');
    assert.equal(initial.skipKbd, false, 'kbd-only skipped');
    assert.equal(initial.skipSvg, false, 'svg skipped');
    assert.equal(initial.skipMath, false, 'math skipped');
    assert.equal(initial.skipNotranslate, false, '.notranslate skipped');
    assert.equal(initial.skipAttrNo, false, 'translate=no skipped');
    assert.equal(initial.skipBtn, false, 'button skipped');
    assert.equal(initial.skipZh, false, 'already-Chinese skipped');
    assert.equal(initial.lazyBeforeScroll, false, 'far below viewport deferred until scroll');
    assert.equal(initial.fabVisible, true, 'floating FAB shown while translating');
    assert.equal(initial.origIntroUntouched, 'Immersive bilingual translation preserves page layout cleanly.',
        'original React/DOM text node untouched');

    // 2. Verify all 5 DisplayStyles (soft, quote, dashed, blur + tap reveal, replace without removing text node)
    const stylesResult = await evalJson(`(() => {
        const p = document.getElementById('p-intro');
        const tr = p.querySelector('font.aiko-babel-tr');
        const checks = {};

        window.__aikoBabel.setStyle('quote');
        checks.quoteAttr = tr.getAttribute('data-style');
        checks.quoteBorder = getComputedStyle(tr).borderLeftStyle;

        window.__aikoBabel.setStyle('dashed');
        checks.dashedAttr = tr.getAttribute('data-style');
        checks.dashedDeco = getComputedStyle(tr).textDecorationStyle;

        window.__aikoBabel.setStyle('blur');
        checks.blurBeforeClick = getComputedStyle(tr).filter;
        tr.click();
        checks.blurRevealedClass = tr.classList.contains('aiko-babel-revealed');
        checks.blurAfterClick = getComputedStyle(tr).filter;

        window.__aikoBabel.setStyle('replace');
        checks.replaceAttr = p.getAttribute('data-aiko-babel-replace');
        checks.replaceParentFs = getComputedStyle(p).fontSize;
        checks.replaceChildFs = getComputedStyle(tr).fontSize;
        checks.replaceTextNodeStillExists = p.firstChild.nodeValue;

        window.__aikoBabel.setStyle('soft');
        checks.softRestoredAttr = p.hasAttribute('data-aiko-babel-replace');
        return checks;
    })()`);

    assert.equal(stylesResult.quoteAttr, 'quote');
    assert.equal(stylesResult.quoteBorder, 'solid');
    assert.equal(stylesResult.dashedAttr, 'dashed');
    assert.equal(stylesResult.dashedDeco, 'dashed');
    assert.ok(stylesResult.blurBeforeClick.includes('blur'), 'blur style applies filter: ' + stylesResult.blurBeforeClick);
    assert.equal(stylesResult.blurRevealedClass, true, 'clicking blurred translation toggles reveal');
    assert.equal(stylesResult.blurAfterClick, 'none', 'revealed blur clears filter');
    assert.equal(stylesResult.replaceAttr, '1');
    assert.equal(stylesResult.replaceParentFs, '0px', 'replace mode hides original cleanly');
    assert.equal(stylesResult.replaceChildFs, '16px', 'replace mode preserves original font size on translation');
    assert.equal(stylesResult.replaceTextNodeStillExists, 'Immersive bilingual translation preserves page layout cleanly.');
    assert.equal(stylesResult.softRestoredAttr, false, 'leaving replace mode clears replace attribute');

    // A quiet block must finish while its neighbor keeps changing. Offscreen edits stay lazy.
    const stream = await evalJson(`(async () => {
        const a = document.getElementById('li-item');
        const b = document.getElementById('td-cell');
        const c = document.getElementById('tweet');
        const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
        const translation = el => el.querySelector('font.aiko-babel-tr')?.textContent;
        const since = window.__bridgeLog.length;
        let bodyScans = 0;
        let documentScans = 0;
        const query = document.body.querySelectorAll;
        const documentQuery = document.querySelectorAll;
        window.__aikoBabel.setStyle('replace');
        document.body.querySelectorAll = function (...args) { bodyScans++; return query.apply(this, args); };
        document.querySelectorAll = function (...args) { documentScans++; return documentQuery.apply(this, args); };
        try {
            document.getElementById('lazy-below').firstChild.appendData(' Updated offscreen.');
            b.textContent = 'Quiet replacement source'; // removes the font as well as the original Text
            const quietNode = b.firstChild;
            const inline = document.createElement('em');
            inline.append(' with an inline suffix');
            c.append(inline); // the source can grow after our font
            const cSource = c.firstChild.data + inline.textContent;
            const aNode = a.firstChild;
            aNode.data = 'Streaming reply';
            for (let i = 0; i < 14; i++) {
                aNode.appendData(' token');
                await delay(50);
            }
            const quietDuringStream = translation(b);
            const aSource = aNode.data;
            await delay(500);
            const sent = window.__bridgeLog.slice(since).filter(m => m.type === 'translate_batch')
                .flatMap(m => m.items.map(it => it.text));
            return { quietDuringStream, aSource, translated: translation(a), cSource, appended: translation(c),
                sent, bodyScans, documentScans, sourcePreserved: quietNode === b.firstChild && aNode === a.firstChild && c.contains(inline),
                fontLast: c.lastChild === c.querySelector('font.aiko-babel-tr'),
                replace: [a, b, c].every(el => el.dataset.aikoBabelReplace === '1' &&
                    el.querySelectorAll('font.aiko-babel-tr[data-style="replace"]').length === 1) };
        } finally {
            delete document.body.querySelectorAll;
            delete document.querySelectorAll;
            window.__aikoBabel.setStyle('soft');
        }
    })()`);
    assert.equal(stream.quietDuringStream, '【译】Quiet replacement source', 'textContent replacement refreshes independently');
    assert.equal(stream.translated, '【译】' + stream.aSource, 'characterData edits alone refresh a translated block');
    assert.equal(stream.appended, '【译】' + stream.cSource, 'inline additions refresh only their source block');
    assert.deepEqual(stream.sent.sort(), ['Quiet replacement source', stream.aSource, stream.cSource].sort(), 'one request per settled source');
    assert.equal(stream.bodyScans + stream.documentScans, 0, 'stream never rescans document or body');
    assert.ok(stream.sourcePreserved && stream.fontLast && stream.replace, 'replace style keeps source nodes and one trailing translation');

    // Hold native requests and reply out of order, including a callback in the same task as the edit.
    const races = await evalJson(`(async () => {
        const api = window.__aikoBabel;
        const p = document.getElementById('bq-text');
        const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
        const font = () => p.querySelector('font.aiko-babel-tr');
        const requestFor = async text => {
            const deadline = performance.now() + 2500;
            do {
                const batch = window.__bridgeLog.find(m => m.type === 'translate_batch' &&
                    m.items.some(item => item.text === text));
                if (batch) return batch;
                await delay(15);
            } while (performance.now() < deadline);
            throw new Error('Missing request for ' + text);
        };
        const reply = (batch, value) => api.applyBatchResult(batch.reqId, batch.items.map(() => value));
        window.__holdBatches = true;
        try {
            p.textContent = 'Pending before edit';
            const immediate = await requestFor('Pending before edit');
            p.firstChild.appendData(' in the same task');
            reply(immediate, 'stale synchronous result');
            const beforeObserver = font()?.textContent;
            const lateSuccess = await requestFor('Pending before edit in the same task');

            p.replaceChildren('Pending after replacement');
            const lateError = await requestFor('Pending after replacement');
            p.firstChild.data = 'Settled final source';
            const current = await requestFor('Settled final source');
            api.setStyle('quote');
            reply(current, 'accepted final translation');
            reply(lateSuccess, 'late stale translation');
            api.applyBatchError(lateError.reqId, 'late stale error');
            const finalText = font()?.textContent;
            const finalStyle = font()?.dataset.style;
            const fab = document.querySelector('.aiko-babel-fab').textContent;

            api.setStyle('replace');
            p.firstChild.data = '这段已经是中文';
            await delay(420);
            return { beforeObserver, finalText, finalStyle, fab,
                sourceOnly: !font() && !p.hasAttribute('data-aiko-babel-replace') &&
                    !p.hasAttribute('data-aiko-babel-state') && getComputedStyle(p).fontSize !== '0px' };
        } finally {
            window.__holdBatches = false;
            api.setStyle('soft');
        }
    })()`);
    assert.notEqual(races.beforeObserver, 'stale synchronous result', 'stale reply rejected before observer delivery');
    assert.equal(races.finalText, 'accepted final translation', 'late success and error leave latest translation intact');
    assert.equal(races.finalStyle, 'quote', 'refresh uses current display style');
    assert.equal(races.fab, '译 · 双语', 'stale failure cannot change FAB status');
    assert.ok(races.sourceOnly, 'editing into target language restores source visibility in replace mode');

    // 3. Scroll down -> lazy IntersectionObserver translates far-below paragraph, AND MutationObserver translates dynamic post
    const lazyAndDynamic = await evalJson(`(async () => {
        document.getElementById('lazy-below').scrollIntoView();
        const dyn = document.createElement('p');
        dyn.id = 'dynamic-post';
        dyn.textContent = 'Dynamically loaded infinite-scroll comment.';
        document.getElementById('lazy-below').after(dyn);
        await new Promise(r => setTimeout(r, 520));
        return {
            lazyTr: document.querySelector('#lazy-below > font.aiko-babel-tr')?.textContent || '',
            dynTr: document.querySelector('#dynamic-post > font.aiko-babel-tr')?.textContent || ''
        };
    })()`);
    assert.ok(lazyAndDynamic.lazyTr.startsWith('【译】This far-below'), 'lazy element translated after scroll');
    assert.ok(lazyAndDynamic.lazyTr.endsWith('Updated offscreen.'), 'lazy translation uses latest source');
    assert.ok(lazyAndDynamic.dynTr.startsWith('【译】Dynamically loaded'), 'dynamic MutationObserver node translated');

    // 4. Stop cleanly restores DOM with zero leftover translation nodes
    const stopped = await evalJson(`(() => {
        window.__aikoBabel.stop();
        return {
            active: window.__aikoBabel.isActive(),
            remainingTr: document.querySelectorAll('font.aiko-babel-tr').length,
            remainingReplace: document.querySelectorAll('[data-aiko-babel-replace]').length,
            fabRemaining: document.querySelectorAll('.aiko-babel-fab').length,
            introText: document.getElementById('p-intro').textContent
        };
    })()`);
    assert.equal(stopped.active, false);
    assert.equal(stopped.remainingTr, 0, 'all translation font nodes removed cleanly');
    assert.equal(stopped.remainingReplace, 0, 'replace attributes cleared');
    assert.equal(stopped.fabRemaining, 0, 'FAB removed on stop');
    assert.equal(stopped.introText, 'Immersive bilingual translation preserves page layout cleanly.');

    // 4b. Regression: toggle OFF -> ON re-translates previously queued elements, AND changing targetLang restarts cleanly
    const reToggle = await evalJson(`(async () => {
        window.scrollTo(0, 0);
        window.__aikoBabel.start();
        await new Promise(r => setTimeout(r, 320));
        const afterReEnable = document.querySelector('#p-intro > font.aiko-babel-tr')?.textContent || '';
        const h1ReEnable = document.querySelector('#h1-title > font.aiko-babel-tr')?.textContent || '';

        // Change targetLang live while active -> should clear old nodes and re-translate into Japanese
        window.__aikoBabel.configure({ targetLang: 'ja' });
        await new Promise(r => setTimeout(r, 360));
        const afterLangSwitch = document.querySelector('#p-intro > font.aiko-babel-tr')?.textContent || '';
        const trCountOnIntro = document.querySelectorAll('#p-intro > font.aiko-babel-tr').length;

        // Restore to zh-CN and stop for selection test
        window.__aikoBabel.configure({ targetLang: 'zh-CN', enabled: false });
        return { afterReEnable, h1ReEnable, afterLangSwitch, trCountOnIntro, activeAfter: window.__aikoBabel.isActive() };
    })()`);
    assert.ok(reToggle.afterReEnable.startsWith('【译】Immersive'), 'toggle OFF->ON re-translates #p-intro: ' + reToggle.afterReEnable);
    assert.ok(reToggle.h1ReEnable.startsWith('【译】Welcome'), 'toggle OFF->ON re-translates #h1-title: ' + reToggle.h1ReEnable);
    assert.ok(reToggle.afterLangSwitch.startsWith('【JA】Immersive'), 'live targetLang change restarts & re-translates: ' + reToggle.afterLangSwitch);
    assert.equal(reToggle.trCountOnIntro, 1, 'no duplicate <font> nodes after live language switch');
    assert.equal(reToggle.activeAfter, false, 'stopped cleanly after re-toggle check');

    // 5. Selection Popup Translator (works even when page translation is OFF!)
    const selResult = await evalJson(`(async () => {
        window.scrollTo(0, 0);
        const p = document.getElementById('p-intro');
        const range = document.createRange();
        range.selectNodeContents(p);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
        document.dispatchEvent(new Event('selectionchange'));
        await new Promise(r => setTimeout(r, 240));
        const pill = document.querySelector('[data-aiko-babel-role="selection-pill"]');
        const pillText = pill ? pill.textContent : '';
        if (pill) pill.click();
        await new Promise(r => setTimeout(r, 120));
        const cardOut = document.querySelector('[data-aiko-babel-role="selection-output"]')?.textContent || '';
        document.querySelector('[data-aiko-babel-action="copy"]')?.click();
        document.querySelector('[data-aiko-babel-action="speak"]')?.click();
        const copyMsgs = window.__bridgeLog.filter(m => m.type === 'copy');
        const speakMsgs = window.__bridgeLog.filter(m => m.type === 'speak');
        return {
            pillText,
            cardOut,
            copyCount: copyMsgs.length,
            speakCount: speakMsgs.length,
            speakText: speakMsgs[0]?.text || ''
        };
    })()`);
    assert.equal(selResult.pillText, '译', 'selection pill appears with 译');
    assert.ok(selResult.cardOut.startsWith('【划词译】Immersive'), 'selection card displays translated text: ' + selResult.cardOut);
    assert.equal(selResult.copyCount, 1, 'copy button posts copy bridge message');
    assert.equal(selResult.speakCount, 1, 'speak button posts speak bridge message');
    assert.ok(selResult.speakText.startsWith('【划词译】'), 'speak payload contains translation');

    // 6. Triple-Space Input Back-Translation (<input>, <textarea>, [contenteditable], and password skipped!)
    const inputResult = await evalJson(`(async () => {
        let inputEvents = 0;
        let textareaEvents = 0;
        let ceEvents = 0;

        const inp = document.getElementById('txt-input');
        inp.addEventListener('input', () => inputEvents++);
        inp.value = '你好世界';
        for (let i = 0; i < 3; i++) {
            inp.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', code: 'Space', bubbles: true }));
            inp.value += ' ';
        }

        const ta = document.getElementById('txt-area');
        ta.addEventListener('input', () => textareaEvents++);
        ta.value = '浏览器翻译';
        for (let i = 0; i < 3; i++) {
            ta.value += ' ';
            ta.dispatchEvent(new InputEvent('input', { data: ' ', bubbles: true }));
        }

        const ce = document.getElementById('ce-box');
        ce.addEventListener('input', () => ceEvents++);
        ce.textContent = '富文本内容';
        for (let i = 0; i < 3; i++) {
            ce.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', code: 'Space', bubbles: true }));
            ce.textContent += ' ';
        }

        // Password input must NEVER trigger translation
        const pwd = document.getElementById('pwd-input');
        pwd.value = '秘密密码';
        for (let i = 0; i < 3; i++) {
            pwd.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', code: 'Space', bubbles: true }));
            pwd.value += ' ';
        }

        await new Promise(r => setTimeout(r, 200));
        return {
            inpVal: inp.value,
            inpEvents: inputEvents,
            taVal: ta.value,
            taEvents: textareaEvents,
            ceVal: ce.textContent,
            ceEvents: ceEvents,
            pwdVal: pwd.value,
            stillLoading: inp.classList.contains('aiko-babel-input-loading')
        };
    })()`);

    assert.equal(inputResult.inpVal, 'EN:你好世界', 'input triple-space back-translated');
    assert.ok(inputResult.inpEvents >= 1, 'React/Vue input event dispatched on <input>');
    assert.equal(inputResult.taVal, 'EN:浏览器翻译', 'textarea IME-style triple-space back-translated');
    assert.ok(inputResult.taEvents >= 1, 'input event dispatched on <textarea>');
    assert.equal(inputResult.ceVal, 'EN:富文本内容', 'contenteditable triple-space back-translated');
    assert.ok(inputResult.ceEvents >= 1, 'input event dispatched on contenteditable');
    assert.equal(inputResult.pwdVal, '秘密密码   ', 'password input strictly skipped');
    assert.equal(inputResult.stillLoading, false, 'loading shimmer removed after completion');

    console.log('PASS babel_dom_check.mjs (Chromium strict CSP, lazy discovery, skip guards, 5 DisplayStyles, per-block streaming, stale replies, stop cleanup, selection popup & triple-space input back-translation)');
} finally {
    clearTimeout(watchdog);
    try { socket?.close(); } catch (_) {}
    try { child.kill(); } catch (_) {}
    server.close();
    try { await rm(userDataDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 150 }); } catch (_) {}
}

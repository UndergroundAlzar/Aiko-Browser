import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {createServer} from 'node:http';
import {readFile, mkdtemp, realpath, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {resolve, dirname, basename} from 'node:path';
import assert from 'node:assert/strict';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const chromePath = process.env.AIKO_CHROMIUM || resolve(repoRoot, '../work/chrome-test/chrome-headless-shell-win64/chrome-headless-shell.exe');
const server = createServer((request, response) => {
    response.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Content-Security-Policy': "script-src 'none'; style-src 'unsafe-inline'"
    }).end('<!doctype html><html><head><title>Local fixture</title></head><body><p id="example">Fixture</p></body></html>');
}).listen(0, '127.0.0.1');
await once(server, 'listening');
const port = server.address().port;
const tempRoot = await realpath(tmpdir());
const isolate = await mkdtemp(resolve(tempRoot, 'aiko-chromium-'));
const checkedProfile = await realpath(isolate);
assert.equal(dirname(checkedProfile), tempRoot);
assert.ok(basename(checkedProfile).startsWith('aiko-chromium-'));
const child = spawn(chromePath, [
    '--disable-gpu', '--disable-background-networking', '--no-first-run', '--disable-extensions',
    '--remote-debugging-port=0', '--user-data-dir=' + isolate, 'about:blank'
], {windowsHide: true, stdio: ['ignore', 'ignore', 'pipe']});
let socket;
const timeout = setTimeout(() => child.kill(), 26000);
try {
    const endpoint = await new Promise((done, fail) => {
        child.once('error', fail);
        child.once('exit', code => fail(new Error('Browser exited: ' + code)));
        let log = '';
        child.stderr.on('data', bytes => {
            log += bytes.toString();
            const match = log.match(/DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)\//);
            if (match) done(Number(match[1]));
        });
    });
    const list = await (await fetch(`http://127.0.0.1:${endpoint}/json/list`)).json();
    const page = list.find(row => row.type === 'page');
    socket = new WebSocket(page.webSocketDebuggerUrl);
    await once(socket, 'open');
    let counter = 0;
    const commands = new Map(), events = new Map();
    socket.addEventListener('message', ({data}) => {
        const reply = JSON.parse(data);
        if (reply.id && commands.has(reply.id)) commands.get(reply.id)(reply);
        if (reply.method && events.has(reply.method)) {
            events.get(reply.method)(reply.params);
            events.delete(reply.method);
        }
    });
    async function command(method, params = {}) {
        const id = ++counter;
        const next = new Promise(resolve => commands.set(id, resolve));
        socket.send(JSON.stringify({id, method, params}));
        const result = await next;
        commands.delete(id);
        if (result.error) throw new Error(JSON.stringify(result.error));
        return result.result;
    }
    async function navigate(pagePath) {
        const next = new Promise(resolve => events.set('Page.loadEventFired', resolve));
        await command('Page.navigate', {url: `http://127.0.0.1:${port}${pagePath}`});
        await next;
        const output = await command('Runtime.evaluate', {
            expression: "JSON.stringify([document.querySelector('#example').dataset.ran,getComputedStyle(document.querySelector('#example')).display,typeof __aikoScriptValues, typeof internalState])",
            returnByValue: true, allowUnsafeEvalBlockedByCSP: false
        });
        assert.ok(!output.exceptionDetails);
        return JSON.parse(output.result.value);
    }
    await command('Page.enable');
    const runtime = await readFile(resolve(repoRoot,'app/src/main/assets/userscripts/runtime.js'), 'utf8');
    const config = {
        id: 'isolated-smoke', release: '0.1.8', runAt: 'document-end',
        includes: [['http','127.0.0.1',false,port,'/allow*']], excludes: [],
        grants: ['GM_addStyle','GM_getValue'], storage: true, values: {counter: 7},
        script: {name: 'Local fixture'}
    };
    const body = "window.internalState=true;document.querySelector('#example').dataset.ran=String(GM_getValue('counter'));GM_addStyle('#example{display:none}');";
    await command('Page.addScriptToEvaluateOnNewDocument', {
        source: `globalThis.__aikoScriptValues={postMessage(){}};(${runtime})(${JSON.stringify(config)},${JSON.stringify(body)});`,
        worldName: 'aiko-smoke-isolated'
    });
    assert.deepEqual(await navigate('/allow'), ['7','none','undefined','undefined']);
    assert.deepEqual(await navigate('/deny'), [null,'block','undefined','undefined']);
    console.log('PASS Chromium isolated-world runtime under strict script CSP, DOM effect, world separation and scoped URL');
} finally {
    clearTimeout(timeout);
    socket?.close();
    child.kill();
    server.close();
    await rm(checkedProfile, {recursive:true, force:true, maxRetries:10, retryDelay:150});
}

function (config, body) {
    "use strict";
    if (window.top !== window) return;

    function glob(pattern, value) {
        let at = 0, token = 0, star = -1, resume = 0;
        while (at < value.length) {
            if (pattern[token] === "*") {
                star = token++;
                resume = at;
            } else if (pattern[token] === value[at]) {
                token++;
                at++;
            } else if (star >= 0) {
                token = star + 1;
                at = ++resume;
            } else {
                return false;
            }
        }
        while (pattern[token] === "*") token++;
        return token === pattern.length;
    }

    function permitted() {
        const protocol = location.protocol.slice(0, -1);
        if (protocol !== "https" && protocol !== "http" || location.href.length > 8192) return false;
        const host = location.hostname.replace(/\.$/, "");
        const port = Number(location.port || (protocol === "https" ? 443 : 80));
        const path = location.pathname + location.search;
        function matches(rule) {
            const [scheme, domain, subdomains, expectedPort, pathPattern] = rule;
            return (scheme === "*" || scheme === protocol)
                && (expectedPort === -1 || expectedPort === port)
                && (domain === "*" || domain === host || (subdomains && host.endsWith("." + domain)))
                && glob(pathPattern, path);
        }
        return config.includes.some(matches) && !config.excludes.some(matches);
    }

    if (!permitted()) return;
    const marker = Symbol.for("aiko.userscript." + config.id);
    if (document[marker]) return;
    Object.defineProperty(document, marker, {value: true});

    // Capture bridge immediately at document-start so ephemeral page-world bridges cannot be observed by page scripts.
    const bridgeName = config.bridge || "__aikoScriptValues";
    const needsBridge = Boolean(config.storage || config.bridgeEnabled);
    const capturedBridge = needsBridge ? globalThis[bridgeName] : null;
    if (config.ephemeralBridge && capturedBridge) {
        try { delete globalThis[bridgeName]; } catch { /* Non-configurable host property */ }
    }

    const B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    function bytesToB64(bytes) {
        if (typeof btoa === "function") {
            let bin = "";
            const chunk = 0x8000;
            for (let i = 0; i < bytes.length; i += chunk) {
                bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
            }
            return btoa(bin);
        }
        let out = "";
        for (let i = 0; i < bytes.length; i += 3) {
            const b0 = bytes[i];
            const has1 = i + 1 < bytes.length;
            const has2 = i + 2 < bytes.length;
            const b1 = has1 ? bytes[i + 1] : 0;
            const b2 = has2 ? bytes[i + 2] : 0;
            const trip = (b0 << 16) | (b1 << 8) | b2;
            out += B64_CHARS[(trip >> 18) & 63];
            out += B64_CHARS[(trip >> 12) & 63];
            out += has1 ? B64_CHARS[(trip >> 6) & 63] : "=";
            out += has2 ? B64_CHARS[trip & 63] : "=";
        }
        return out;
    }

    function b64ToBytes(b64) {
        const clean = String(b64 || "").replace(/\s+/g, "");
        if (!clean) return new Uint8Array(0);
        if (typeof atob === "function") {
            const bin = atob(clean);
            const out = new Uint8Array(bin.length);
            for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
            return out;
        }
        const pad = clean.endsWith("==") ? 2 : (clean.endsWith("=") ? 1 : 0);
        const out = new Uint8Array(Math.max(0, ((clean.length * 3) >> 2) - pad));
        let p = 0;
        for (let i = 0; i < clean.length; i += 4) {
            const c0 = B64_CHARS.indexOf(clean[i]);
            const c1 = B64_CHARS.indexOf(clean[i + 1]);
            const c2 = clean[i + 2] === "=" ? 0 : B64_CHARS.indexOf(clean[i + 2]);
            const c3 = clean[i + 3] === "=" ? 0 : B64_CHARS.indexOf(clean[i + 3]);
            const trip = (c0 << 18) | (c1 << 12) | (c2 << 6) | c3;
            if (p < out.length) out[p++] = (trip >> 16) & 255;
            if (p < out.length) out[p++] = (trip >> 8) & 255;
            if (p < out.length) out[p++] = trip & 255;
        }
        return out;
    }

    function utf8Encode(str) {
        if (typeof TextEncoder === "function") return new TextEncoder().encode(str);
        const enc = unescape(encodeURIComponent(str));
        const out = new Uint8Array(enc.length);
        for (let i = 0; i < enc.length; i++) out[i] = enc.charCodeAt(i);
        return out;
    }

    function utf8Decode(bytes) {
        if (typeof TextDecoder === "function") return new TextDecoder("utf-8").decode(bytes);
        let bin = "";
        for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
        try { return decodeURIComponent(escape(bin)); } catch { return bin; }
    }

    function run() {
        if (!permitted()) return;
        const bridge = capturedBridge;
        const token = config.token || "";
        const values = Object.assign(Object.create(null), config.values);
        const resources = (config.resources && typeof config.resources === "object") ? config.resources : Object.create(null);
        const owns = (key) => Object.prototype.hasOwnProperty.call(values, key);
        const valuesLimit = 65536;
        const keyLimit = 128;
        const listeners = new Map();
        let nextListenerId = 1;
        const menuCommands = new Map();
        let nextMenuId = 1;
        const pendingXhrs = new Map();
        let nextXhrId = 1;

        const postBridge = (args) => {
            if (!bridge) throw new Error("GM bridge is unavailable");
            const payload = token ? [...args, token] : args;
            bridge.postMessage(JSON.stringify(payload));
        };

        const keyText = (key) => {
            const text = String(key);
            if (text.length > keyLimit) throw new RangeError("GM value key is too long");
            return text;
        };
        const cloneValue = (val) => {
            if (val === undefined || val === null || typeof val !== "object") return val;
            try { return JSON.parse(JSON.stringify(val)); } catch { return val; }
        };
        const notifyListeners = (key, oldVal, newVal, remote) => {
            if (listeners.size === 0) return;
            for (const entry of Array.from(listeners.values())) {
                if (entry.key !== key) continue;
                try {
                    entry.cb(key, cloneValue(oldVal), cloneValue(newVal), remote);
                } catch (err) {
                    console.warn("[Aiko userscript] value listener error", err);
                }
            }
        };
        const valueGet = (key, fallback) => {
            key = keyText(key);
            return owns(key) ? cloneValue(values[key]) : fallback;
        };
        const valueSet = (key, value) => {
            key = keyText(key);
            if (!bridge) throw new Error("GM storage is unavailable");
            const encoded = JSON.stringify(value);
            if (encoded === undefined || encoded.length > valuesLimit) throw new TypeError("Unsupported GM value");
            const payload = token ? [location.href, "set", key, encoded, token] : [location.href, "set", key, encoded];
            const envelope = JSON.stringify(payload);
            if (envelope.length > 140000) throw new RangeError("GM value is too large");
            const previous = owns(key) ? values[key] : undefined;
            const parsed = JSON.parse(encoded);
            values[key] = parsed;
            if (Object.keys(values).length > 128 || JSON.stringify(values).length > valuesLimit) {
                if (previous === undefined) delete values[key];
                else values[key] = previous;
                throw new RangeError("GM storage limit reached");
            }
            bridge.postMessage(envelope);
            notifyListeners(key, previous, parsed, false);
        };
        const valueDelete = (key) => {
            key = keyText(key);
            if (!bridge) throw new Error("GM storage is unavailable");
            const previous = owns(key) ? values[key] : undefined;
            delete values[key];
            const payload = token ? [location.href, "delete", key, token] : [location.href, "delete", key];
            bridge.postMessage(JSON.stringify(payload));
            if (previous !== undefined) notifyListeners(key, previous, undefined, false);
        };
        const valueList = () => Object.keys(values);
        const valuesGet = (keysOrDefaults) => {
            const out = {};
            if (Array.isArray(keysOrDefaults)) {
                for (const k of keysOrDefaults) {
                    const key = keyText(k);
                    if (owns(key)) out[key] = cloneValue(values[key]);
                }
            } else if (keysOrDefaults && typeof keysOrDefaults === "object") {
                for (const k of Object.keys(keysOrDefaults)) {
                    const key = keyText(k);
                    out[key] = owns(key) ? cloneValue(values[key]) : keysOrDefaults[k];
                }
            }
            return out;
        };
        const valuesSet = (entries) => {
            if (!entries || typeof entries !== "object") return;
            for (const k of Object.keys(entries)) valueSet(k, entries[k]);
        };
        const valuesDelete = (keys) => {
            if (!Array.isArray(keys)) return;
            for (const k of keys) valueDelete(k);
        };
        const addValueChangeListener = (key, callback) => {
            key = keyText(key);
            if (typeof callback !== "function") throw new TypeError("GM_addValueChangeListener requires a function");
            const id = nextListenerId++;
            listeners.set(id, {key, cb: callback});
            return id;
        };
        const removeValueChangeListener = (id) => {
            listeners.delete(id);
        };
        const addStyle = (css) => {
            const style = document.createElement("style");
            style.textContent = String(css);
            const parent = document.head || document.documentElement;
            if (parent) parent.appendChild(style);
            else document.addEventListener("DOMContentLoaded", () => {
                (document.head || document.documentElement).appendChild(style);
            }, {once: true});
            return style;
        };
        const addElement = (first, second, third) => {
            let parent = null, tag = first, attrs = second;
            if (first && typeof first === "object" && typeof first.appendChild === "function") {
                parent = first;
                tag = second;
                attrs = third;
            }
            const el = document.createElement(String(tag));
            if (attrs && typeof attrs === "object") {
                for (const [k, v] of Object.entries(attrs)) {
                    if (k === "textContent") el.textContent = String(v);
                    else if (v !== undefined && v !== null) el.setAttribute(k, String(v));
                }
            }
            const target = parent || document.head || document.body || document.documentElement;
            if (target) target.appendChild(el);
            return el;
        };
        const setClipboard = (data) => {
            const text = String(data ?? "");
            if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
                return navigator.clipboard.writeText(text).catch(() => {});
            }
            try {
                const ta = document.createElement("textarea");
                ta.value = text;
                ta.style.cssText = "position:fixed;opacity:0;pointer-events:none;left:-9999px;top:0;";
                (document.body || document.documentElement).appendChild(ta);
                ta.focus();
                ta.select();
                document.execCommand("copy");
                ta.remove();
            } catch { /* Ignore clipboard errors in non-gesture context */ }
        };
        const registerMenuCommand = (caption, onClick, options) => {
            const id = "cmd-" + (nextMenuId++);
            const label = String(caption ?? "");
            menuCommands.set(id, {caption: label, onClick, options});
            if (bridge) {
                try { postBridge([location.href, "menu_reg", id, label]); } catch {}
            }
            return id;
        };
        const unregisterMenuCommand = (id) => {
            const key = String(id ?? "");
            if (menuCommands.delete(key) && bridge) {
                try { postBridge([location.href, "menu_unreg", key]); } catch {}
            }
        };
        const openInTab = (url) => {
            const target = String(url ?? "");
            if (!/^https?:\/\//i.test(target)) return {closed: true, close() {}, onclose: null};
            const opened = window.open(target, "_blank", "noopener");
            return {
                get closed() { return !opened || opened.closed; },
                close() { try { opened && opened.close(); } catch {} },
                onclose: null
            };
        };
        const notification = (first, second, _third, fourth) => {
            const opts = (first && typeof first === "object")
                ? first
                : {text: String(first ?? ""), title: second ? String(second) : config.script.name, onclick: fourth};
            try {
                const host = document.createElement("div");
                host.style.cssText = "position:fixed;top:14px;right:14px;z-index:2147483647;max-width:300px;pointer-events:auto;";
                const root = host.attachShadow ? host.attachShadow({mode: "open"}) : host;
                const box = document.createElement("div");
                box.style.cssText = "background:#171B26;color:#F3F5FA;border:1px solid #3A4666;border-radius:14px;"
                    + "padding:10px 14px;font:13px/1.4 system-ui,sans-serif;box-shadow:0 8px 24px rgba(0,0,0,.45);cursor:pointer;";
                if (opts.title) {
                    const t = document.createElement("div");
                    t.style.cssText = "font-weight:700;margin-bottom:2px;";
                    t.textContent = String(opts.title);
                    box.appendChild(t);
                }
                const m = document.createElement("div");
                m.style.cssText = "color:#B8C2D8;";
                m.textContent = String(opts.text ?? "");
                box.appendChild(m);
                root.appendChild(box);
                (document.body || document.documentElement)?.appendChild(host);
                let closed = false;
                const finish = (clicked) => {
                    if (closed) return;
                    closed = true;
                    host.remove();
                    if (clicked && typeof opts.onclick === "function") try { opts.onclick(); } catch {}
                    if (typeof opts.ondone === "function") try { opts.ondone(clicked); } catch {}
                };
                box.addEventListener("click", () => finish(true), {once: true});
                setTimeout(() => finish(false), Math.max(1200, Math.min(15000, Number(opts.timeout) || 4000)));
            } catch { /* Ignore DOMless contexts */ }
        };

        const getResourceText = (name) => {
            const key = String(name ?? "");
            if (!Object.prototype.hasOwnProperty.call(resources, key)) return null;
            const entry = resources[key];
            if (typeof entry === "string") return entry;
            if (!entry || typeof entry !== "object") return null;
            if (typeof entry.text === "string") return entry.text;
            if (typeof entry.b64 === "string") return utf8Decode(b64ToBytes(entry.b64));
            return null;
        };

        const getResourceURL = (name) => {
            const key = String(name ?? "");
            if (!Object.prototype.hasOwnProperty.call(resources, key)) return null;
            const entry = resources[key];
            if (typeof entry === "string") {
                return "data:text/plain;base64," + bytesToB64(utf8Encode(entry));
            }
            if (!entry || typeof entry !== "object") return null;
            const mime = (typeof entry.mime === "string" && entry.mime) ? entry.mime : "application/octet-stream";
            if (typeof entry.b64 === "string") return "data:" + mime + ";base64," + entry.b64;
            if (typeof entry.text === "string") return "data:" + mime + ";base64," + bytesToB64(utf8Encode(entry.text));
            return null;
        };

        const download = (first, second) => {
            const opts = (first && typeof first === "object")
                ? first
                : {url: String(first ?? ""), name: second != null ? String(second) : ""};
            const target = String(opts.url ?? "");
            const name = String(opts.name ?? "");
            if (!/^https?:\/\//i.test(target)) {
                if (typeof opts.onerror === "function") try { opts.onerror({error: "invalid_url"}); } catch {}
                return {abort() {}};
            }
            if (!bridge) {
                if (typeof opts.onerror === "function") try { opts.onerror({error: "bridge_unavailable"}); } catch {}
                return {abort() {}};
            }
            try {
                postBridge([location.href, "download", target, name]);
                if (typeof opts.onload === "function") try { opts.onload({url: target, name}); } catch {}
            } catch (err) {
                if (typeof opts.onerror === "function") try { opts.onerror({error: String(err)}); } catch {}
            }
            return {abort() {}};
        };

        const buildXmlDoc = (text, contentType) => {
            if (!text || typeof DOMParser !== "function") return null;
            const lower = String(contentType || "").toLowerCase();
            if (!lower.includes("xml") && !lower.includes("html")) return null;
            try {
                const mime = lower.includes("html") && !lower.includes("xhtml") ? "text/html" : "application/xml";
                return new DOMParser().parseFromString(text, mime);
            } catch {
                return null;
            }
        };

        const invokeXhrCallback = (fn, res) => {
            if (typeof fn !== "function") return;
            try { fn(res); } catch (err) { console.warn("[Aiko userscript] GM_xmlhttpRequest callback error", err); }
        };

        const dispatchXhr = (details, resolve, reject) => {
            if (!details || typeof details !== "object" || !details.url) {
                const err = {readyState: 4, status: 0, statusText: "", finalUrl: "", responseHeaders: "", responseText: "", response: null, responseXML: null, error: "missing_url", context: details?.context};
                invokeXhrCallback(details?.onerror, err);
                invokeXhrCallback(details?.onloadend, err);
                if (reject) reject(err);
                return {abort() {}};
            }
            let targetUrl;
            try {
                targetUrl = new URL(String(details.url), location.href).href;
            } catch {
                const err = {readyState: 4, status: 0, statusText: "", finalUrl: String(details.url), responseHeaders: "", responseText: "", response: null, responseXML: null, error: "invalid_url", context: details.context};
                invokeXhrCallback(details.onerror, err);
                invokeXhrCallback(details.onloadend, err);
                if (reject) reject(err);
                return {abort() {}};
            }
            if (!bridge) {
                const err = {readyState: 4, status: 0, statusText: "", finalUrl: targetUrl, responseHeaders: "", responseText: "", response: null, responseXML: null, error: "bridge_unavailable", context: details.context};
                invokeXhrCallback(details.onerror, err);
                invokeXhrCallback(details.onloadend, err);
                if (reject) reject(err);
                return {abort() {}};
            }

            const reqId = "xhr-" + (nextXhrId++);
            const method = String(details.method || "GET").toUpperCase();
            const headers = {};
            if (details.headers && typeof details.headers === "object") {
                for (const [hk, hv] of Object.entries(details.headers)) {
                    if (hv !== undefined && hv !== null) headers[String(hk)] = String(hv);
                }
            }
            let bodyData = null;
            let dataIsBase64 = false;
            if (details.data !== undefined && details.data !== null) {
                if (typeof details.data === "string") {
                    bodyData = details.data;
                } else if (typeof URLSearchParams === "function" && details.data instanceof URLSearchParams) {
                    bodyData = details.data.toString();
                } else if (details.data instanceof ArrayBuffer) {
                    bodyData = bytesToB64(new Uint8Array(details.data));
                    dataIsBase64 = true;
                } else if (ArrayBuffer.isView(details.data)) {
                    bodyData = bytesToB64(new Uint8Array(details.data.buffer, details.data.byteOffset, details.data.byteLength));
                    dataIsBase64 = true;
                } else {
                    bodyData = String(details.data);
                }
            }

            const reqPayload = {
                id: reqId,
                method,
                url: targetUrl,
                headers,
                data: bodyData,
                dataIsBase64,
                timeout: Number(details.timeout) || 0,
                responseType: String(details.responseType || ""),
                overrideMimeType: String(details.overrideMimeType || ""),
                anonymous: Boolean(details.anonymous)
            };
            pendingXhrs.set(reqId, {details, url: targetUrl, aborted: false, resolve, reject});

            try {
                postBridge([location.href, "xhr", JSON.stringify(reqPayload)]);
            } catch (err) {
                pendingXhrs.delete(reqId);
                const failRes = {readyState: 4, status: 0, statusText: "", finalUrl: targetUrl, responseHeaders: "", responseText: "", response: null, responseXML: null, error: String(err), context: details.context};
                invokeXhrCallback(details.onerror, failRes);
                invokeXhrCallback(details.onloadend, failRes);
                if (reject) reject(failRes);
                return {abort() {}};
            }

            return {
                abort() {
                    const item = pendingXhrs.get(reqId);
                    if (!item || item.aborted) return;
                    item.aborted = true;
                    pendingXhrs.delete(reqId);
                    try { postBridge([location.href, "xhr_abort", reqId]); } catch {}
                    const abortRes = {readyState: 4, status: 0, statusText: "", finalUrl: targetUrl, responseHeaders: "", responseText: "", response: null, responseXML: null, context: details.context};
                    invokeXhrCallback(details.onabort, abortRes);
                    invokeXhrCallback(details.onloadend, abortRes);
                    if (item.reject) item.reject(abortRes);
                }
            };
        };

        const xmlhttpRequest = (details) => dispatchXhr(details, null, null);
        const xmlHttpRequestAsync = (details) => {
            let handle = {abort() {}};
            const promise = new Promise((resolve, reject) => {
                handle = dispatchXhr(details, resolve, reject);
            });
            promise.abort = () => handle.abort();
            return promise;
        };

        const enabled = new Set(config.grants);
        const needsReplyChannel = enabled.has("GM_addValueChangeListener") || enabled.has("GM.addValueChangeListener")
            || enabled.has("GM_xmlhttpRequest") || enabled.has("GM.xmlHttpRequest")
            || enabled.has("GM_registerMenuCommand") || enabled.has("GM.registerMenuCommand");

        // Subscribe to bidirectional native messages (cross-tab storage sync, XHR responses, menu invocations).
        if (bridge && needsReplyChannel) {
            const onRemoteMessage = (event) => {
                try {
                    const msg = JSON.parse(typeof event === "string" ? event : event.data);
                    if (Array.isArray(msg)) {
                        if (msg.length < 5) return;
                        const [key, hadOld, oldVal, hasNew, newVal] = msg;
                        if (typeof key !== "string") return;
                        const prev = hadOld ? oldVal : (owns(key) ? values[key] : undefined);
                        if (hasNew) values[key] = newVal;
                        else delete values[key];
                        notifyListeners(key, prev, hasNew ? newVal : undefined, true);
                        return;
                    }
                    if (!msg || typeof msg !== "object") return;
                    if (msg.type === "menu_invoke") {
                        const cmd = menuCommands.get(String(msg.cmdId || ""));
                        if (cmd && typeof cmd.onClick === "function") {
                            try { cmd.onClick(); } catch (err) { console.warn("[Aiko userscript] menu command error", err); }
                        }
                        return;
                    }
                    if (msg.type === "xhr_done" || msg.type === "xhr_error" || msg.type === "xhr_timeout") {
                        const item = pendingXhrs.get(String(msg.id || ""));
                        if (!item || item.aborted) return;
                        pendingXhrs.delete(String(msg.id || ""));
                        const details = item.details;
                        const respType = String(details.responseType || "").toLowerCase();
                        const ct = String(details.overrideMimeType || msg.contentType || "");
                        let responseText = "";
                        let response = null;
                        if (msg.type === "xhr_done") {
                            if (msg.binary) {
                                const bytes = b64ToBytes(msg.body || "");
                                if (respType === "arraybuffer") {
                                    response = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
                                } else if (respType === "blob") {
                                    const mime = ct.split(";")[0].trim() || "application/octet-stream";
                                    response = typeof Blob === "function" ? new Blob([bytes], {type: mime}) : bytes;
                                } else {
                                    response = bytes.buffer;
                                }
                            } else {
                                responseText = typeof msg.body === "string" ? msg.body : "";
                                if (respType === "json") {
                                    try { response = JSON.parse(responseText); } catch { response = null; }
                                } else if (respType === "document") {
                                    response = buildXmlDoc(responseText, ct || "text/html") || responseText;
                                } else {
                                    response = responseText;
                                }
                            }
                        }
                        let xmlCached = undefined;
                        const res = {
                            readyState: 4,
                            status: Number(msg.status || 0),
                            statusText: String(msg.statusText || ""),
                            finalUrl: String(msg.finalUrl || item.url || ""),
                            responseHeaders: String(msg.responseHeaders || ""),
                            responseText,
                            response,
                            context: details.context
                        };
                        if (msg.error) res.error = String(msg.error);
                        Object.defineProperty(res, "responseXML", {
                            enumerable: true,
                            get() {
                                if (xmlCached === undefined) xmlCached = msg.binary ? null : buildXmlDoc(responseText, ct);
                                return xmlCached;
                            }
                        });
                        invokeXhrCallback(details.onreadystatechange, res);
                        if (msg.type === "xhr_done") {
                            invokeXhrCallback(details.onload, res);
                            invokeXhrCallback(details.onloadend, res);
                            if (item.resolve) item.resolve(res);
                        } else if (msg.type === "xhr_timeout") {
                            invokeXhrCallback(details.ontimeout, res);
                            invokeXhrCallback(details.onloadend, res);
                            if (item.reject) item.reject(res);
                        } else {
                            invokeXhrCallback(details.onerror, res);
                            invokeXhrCallback(details.onloadend, res);
                            if (item.reject) item.reject(res);
                        }
                    }
                } catch { /* Ignore malformed bridge message */ }
            };
            if (typeof bridge.addEventListener === "function") bridge.addEventListener("message", onRemoteMessage);
            else if ("onmessage" in bridge) bridge.onmessage = onRemoteMessage;
            try {
                const hello = token ? [location.href, "hello", token] : [location.href, "hello"];
                bridge.postMessage(JSON.stringify(hello));
            } catch { /* Best-effort reply proxy registration */ }
        }

        // Optional SPA URL change watcher when @grant window.onurlchange is requested.
        if (enabled.has("window.onurlchange") && typeof history === "object") {
            let lastHref = location.href;
            const emitUrlChange = () => {
                if (location.href === lastHref) return;
                lastHref = location.href;
                const info = {url: lastHref};
                try { window.dispatchEvent(new CustomEvent("urlchange", {detail: info})); } catch {}
                if (typeof window.onurlchange === "function") {
                    try { window.onurlchange(info); } catch {}
                }
            };
            for (const fn of ["pushState", "replaceState"]) {
                const orig = history[fn];
                if (typeof orig === "function") {
                    history[fn] = function (...args) {
                        const res = orig.apply(this, args);
                        emitUrlChange();
                        return res;
                    };
                }
            }
            window.addEventListener("popstate", emitUrlChange);
            window.addEventListener("hashchange", emitUrlChange);
        }

        const info = {scriptHandler: "Aiko Browser", version: config.release, script: config.script};
        const log = (...items) => console.log(...items);
        const functions = {
            addStyle, addElement, log,
            getValue: valueGet, setValue: valueSet, deleteValue: valueDelete, listValues: valueList,
            getValues: valuesGet, setValues: valuesSet, deleteValues: valuesDelete,
            addValueChangeListener, removeValueChangeListener,
            setClipboard, registerMenuCommand, unregisterMenuCommand,
            openInTab, notification,
            getResourceText, getResourceURL, download
        };
        const modern = Object.create(null);
        if (enabled.has("GM.info")) modern.info = info;
        const names = ["GM", "GM_info"];
        const args = [modern, info];
        if (enabled.has("unsafeWindow")) {
            names.push("unsafeWindow");
            args.push(window);
        }
        for (const method of Object.keys(functions)) {
            const legacy = "GM_" + method;
            if (enabled.has(legacy)) {
                names.push(legacy);
                args.push(functions[method]);
            }
            if (enabled.has("GM." + method)) modern[method] = async (...params) => functions[method](...params);
        }
        if (enabled.has("GM_xmlhttpRequest")) {
            names.push("GM_xmlhttpRequest");
            args.push(xmlhttpRequest);
        }
        if (enabled.has("GM.xmlHttpRequest")) {
            modern.xmlHttpRequest = xmlHttpRequestAsync;
        }
        try {
            // Compile only after the URL check. Source text cannot escape the scope check.
            new Function(...names, body).apply(window, args);
        } catch (error) {
            console.warn("[Aiko userscript] " + config.script.name, error);
        }
    }

    function whenReady(callback) {
        if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", callback, {once: true});
        else callback();
    }

    if (config.runAt === "document-start") {
        run();
    } else if (config.runAt === "document-body" && !document.body && document.readyState === "loading") {
        let done = false;
        let observed = document.documentElement || document;
        const finish = (invoke) => {
            if (done) return;
            done = true;
            observer.disconnect();
            document.removeEventListener("DOMContentLoaded", onReady);
            if (invoke) run();
        };
        const onReady = () => finish(!!document.body);
        const observer = new MutationObserver(() => {
            if (document.body) {
                finish(true);
            } else if (document.documentElement && observed !== document.documentElement) {
                observer.disconnect();
                observed = document.documentElement;
                observer.observe(observed, {childList: true});
            }
        });
        observer.observe(observed, {childList: true});
        document.addEventListener("DOMContentLoaded", onReady, {once: true});
    } else if (config.runAt === "document-idle") {
        whenReady(() => {
            if (typeof requestIdleCallback === "function") requestIdleCallback(run, {timeout: 700});
            else setTimeout(run, 0);
        });
    } else {
        whenReady(run);
    }
}

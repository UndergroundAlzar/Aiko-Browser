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

    function run() {
        if (!permitted()) return;
        const bridge = config.storage ? globalThis.__aikoScriptValues : null;
        const values = Object.assign(Object.create(null), config.values);
        const owns = (key) => Object.prototype.hasOwnProperty.call(values, key);
        const valuesLimit = 32768;
        const keyLimit = 128;
        const keyText = (key) => {
            const text = String(key);
            if (text.length > keyLimit) throw new RangeError("GM value key is too long");
            return text;
        };
        const valueGet = (key, fallback) => {
            key = keyText(key);
            return owns(key) ? values[key] : fallback;
        };
        const valueSet = (key, value) => {
            key = keyText(key);
            if (!bridge) throw new Error("GM storage is unavailable");
            const encoded = JSON.stringify(value);
            if (encoded === undefined || encoded.length > valuesLimit) throw new TypeError("Unsupported GM value");
            const envelope = JSON.stringify([location.href, "set", key, encoded]);
            if (envelope.length > 76000) throw new RangeError("GM value is too large");
            const previous = owns(key) ? values[key] : undefined;
            values[key] = JSON.parse(encoded);
            if (Object.keys(values).length > 128 || JSON.stringify(values).length > valuesLimit) {
                if (previous === undefined) delete values[key];
                else values[key] = previous;
                throw new RangeError("GM storage limit reached");
            }
            bridge.postMessage(envelope);
        };
        const valueDelete = (key) => {
            key = keyText(key);
            if (!bridge) throw new Error("GM storage is unavailable");
            delete values[key];
            bridge.postMessage(JSON.stringify([location.href, "delete", key]));
        };
        const valueList = () => Object.keys(values);
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
        const info = {scriptHandler: "Aiko Browser", version: config.release, script: config.script};
        const log = (...items) => console.log(...items);
        const functions = {
            addStyle, log, getValue: valueGet, setValue: valueSet, deleteValue: valueDelete, listValues: valueList
        };
        const modern = Object.create(null);
        const enabled = new Set(config.grants);
        if (enabled.has("GM.info")) modern.info = info;
        const names = ["GM", "GM_info"];
        const args = [modern, info];
        for (const method of Object.keys(functions)) {
            const legacy = "GM_" + method;
            if (enabled.has(legacy)) {
                names.push(legacy);
                args.push(functions[method]);
            }
            if (enabled.has("GM." + method)) modern[method] = async (...params) => functions[method](...params);
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
        const observer = new MutationObserver(() => {
            if (!document.body) return;
            observer.disconnect();
            run();
        });
        observer.observe(document, {childList: true, subtree: true});
    } else if (config.runAt === "document-idle") {
        whenReady(() => {
            if (typeof requestIdleCallback === "function") requestIdleCallback(run, {timeout: 700});
            else setTimeout(run, 0);
        });
    } else {
        whenReady(run);
    }
}

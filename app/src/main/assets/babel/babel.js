(() => {
    'use strict';

    if (window.__aikoBabel && window.__aikoBabel.__version === '0.5.0') {
        return;
    }

    const STYLE_ID = 'aiko-babel-style';
    const TR_CLASS = 'aiko-babel-tr';
    const UI_ATTR = 'data-aiko-babel-ui';
    const SKIP_TAGS = new Set([
        'CODE', 'PRE', 'KBD', 'SAMP', 'VAR', 'SVG', 'MATH',
        'SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE',
        'TEXTAREA', 'INPUT', 'SELECT', 'OPTION', 'BUTTON',
        'CANVAS', 'VIDEO', 'AUDIO', 'IFRAME', 'OBJECT', 'EMBED', 'MAP', 'AREA'
    ]);
    const BLOCK_TAGS = new Set([
        'P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
        'LI', 'TD', 'TH', 'BLOCKQUOTE', 'FIGCAPTION', 'DT', 'DD', 'SUMMARY', 'CAPTION'
    ]);
    const CONTAINER_CANDIDATE_TAGS = new Set(['DIV', 'ARTICLE', 'SECTION', 'SPAN', 'MAIN']);
    const NAV_TAGS = new Set(['NAV', 'HEADER', 'FOOTER']);
    const NAV_ROLES = new Set(['navigation', 'menubar', 'toolbar', 'tablist', 'menu']);
    const SKIP_SELECTOR = [
        'code', 'pre', 'kbd', 'samp', 'var', 'svg', 'math',
        'script', 'style', 'noscript', 'template',
        'textarea', 'input', 'select', 'button',
        '[contenteditable]', '.notranslate', '[translate="no"]',
        '.' + TR_CLASS, '[' + UI_ATTR + ']'
    ].join(',');
    const INPUT_ALLOWED_TYPES = new Set(['', 'text', 'search']);

    const config = {
        enabled: false,
        style: 'soft',
        targetLang: 'zh-CN',
        inputTargetLang: 'en',
        selectionTranslate: true,
        tripleSpaceInputTranslate: true,
        skipCodeAndNav: true,
        showFloatingFab: true,
        batchMaxItems: 8,
        batchMaxChars: 2400,
        theme: {
            accent: '#7C9BFF',
            accentCyan: '#5CE1E6',
            text: '#F3F6FC',
            muted: '#9AA5BD',
            surface: '#111520',
            card: '#171D2B',
            stroke: 'rgba(255,255,255,0.16)',
            onAccent: '#0B0D12'
        }
    };

    let io = null;
    let mo = null;
    let moTimer = 0;
    let flushTimer = 0;
    let selTimer = 0;
    let seq = 0;
    let nodeCounter = 0;
    let activeGeneration = 0;

    const observedNodes = new Set();
    const translatedNodes = new Set();
    const pendingQueue = [];
    let queuedSet = new WeakSet();
    const pendingBatches = new Map();     // reqId -> { gen, entries: [{ id, el }] }
    const pendingSelections = new Map();  // reqId -> { text }
    const pendingInputs = new Map();      // reqId -> { el, origText }
    const spaceTracker = new WeakMap();   // el -> { times: number[], lastKeyTs: number, lastVal: string, timer: number }

    let styleEl = null;
    let adoptedSheet = null;
    let selPill = null;
    let selCard = null;
    let fabEl = null;
    let currentSelectionText = '';
    let currentSelectionRect = null;
    let lastTranslatedSelection = '';

    // ---- Native Bridge ----

    function postToNative(payload) {
        try {
            const serialized = JSON.stringify(payload);
            const customBridge = window.__aikoBabelBridge;
            if (customBridge && typeof customBridge.post === 'function') {
                customBridge.post(serialized);
                return true;
            }
            if (customBridge && typeof customBridge.postMessage === 'function') {
                customBridge.postMessage(serialized);
                return true;
            }
            if (window.__aikoBabelNative && typeof window.__aikoBabelNative.postMessage === 'function') {
                window.__aikoBabelNative.postMessage(serialized);
                return true;
            }
            if (window.__aikoBabelJs && typeof window.__aikoBabelJs.post === 'function') {
                window.__aikoBabelJs.post(serialized);
                return true;
            }
        } catch (_) {
            // Ignore bridge errors when detached
        }
        return false;
    }

    function nextId(prefix) {
        seq += 1;
        return prefix + '_' + seq;
    }

    // ---- CSP-Safe Styling ----

    function buildCss() {
        const t = config.theme;
        return [
            ':root {',
            '  --aiko-babel-accent: ' + t.accent + ';',
            '  --aiko-babel-cyan: ' + t.accentCyan + ';',
            '  --aiko-babel-text: ' + t.text + ';',
            '  --aiko-babel-muted: ' + t.muted + ';',
            '  --aiko-babel-surface: ' + t.surface + ';',
            '  --aiko-babel-card: ' + t.card + ';',
            '  --aiko-babel-stroke: ' + t.stroke + ';',
            '  --aiko-babel-on-accent: ' + t.onAccent + ';',
            '}',
            'font.' + TR_CLASS + ' {',
            '  display: block;',
            '  margin-top: 0.34em;',
            '  font-family: inherit;',
            '  font-size: 0.96em;',
            '  font-weight: 400;',
            '  line-height: 1.56;',
            '  letter-spacing: normal;',
            '  word-break: break-word;',
            '  box-sizing: border-box;',
            '}',
            'font.' + TR_CLASS + '[data-style="soft"] {',
            '  opacity: 0.86;',
            '}',
            'font.' + TR_CLASS + '[data-style="quote"] {',
            '  padding: 0.16em 0 0.16em 0.68em;',
            '  border-left: 3px solid var(--aiko-babel-accent);',
            '  opacity: 0.93;',
            '}',
            'font.' + TR_CLASS + '[data-style="dashed"] {',
            '  text-decoration: underline dashed var(--aiko-babel-accent);',
            '  text-underline-offset: 4px;',
            '  text-decoration-thickness: 1.25px;',
            '  opacity: 0.93;',
            '}',
            'font.' + TR_CLASS + '[data-style="blur"] {',
            '  filter: blur(5px);',
            '  -webkit-filter: blur(5px);',
            '  transition: filter 0.18s ease;',
            '  cursor: pointer;',
            '  user-select: text;',
            '}',
            'font.' + TR_CLASS + '[data-style="blur"]:hover,',
            'font.' + TR_CLASS + '[data-style="blur"].aiko-babel-revealed {',
            '  filter: none;',
            '  -webkit-filter: none;',
            '}',
            '[data-aiko-babel-replace="1"] {',
            '  font-size: 0 !important;',
            '  line-height: 0 !important;',
            '  color: transparent !important;',
            '}',
            '[data-aiko-babel-replace="1"] > font.' + TR_CLASS + '[data-style="replace"] {',
            '  display: inline-block;',
            '  margin-top: 0;',
            '  font-size: var(--aiko-babel-orig-fs, 16px) !important;',
            '  line-height: var(--aiko-babel-orig-lh, 1.55) !important;',
            '  color: var(--aiko-babel-orig-color, inherit) !important;',
            '  opacity: 1;',
            '}',
            'font.' + TR_CLASS + '.aiko-babel-loading {',
            '  opacity: 0.45;',
            '  font-style: italic;',
            '}',
            '@keyframes aikoBabelPulse {',
            '  0%, 100% { box-shadow: 0 0 0 2px var(--aiko-babel-accent); }',
            '  50% { box-shadow: 0 0 0 4px var(--aiko-babel-cyan); }',
            '}',
            '.aiko-babel-input-loading {',
            '  animation: aikoBabelPulse 0.85s ease-in-out infinite !important;',
            '  opacity: 0.78 !important;',
            '}',
            '.aiko-babel-pill {',
            '  position: fixed; z-index: 2147483646;',
            '  display: flex; align-items: center; justify-content: center;',
            '  min-width: 34px; height: 34px; padding: 0 11px; border-radius: 17px;',
            '  background: var(--aiko-babel-surface); color: var(--aiko-babel-accent);',
            '  border: 1px solid var(--aiko-babel-stroke);',
            '  box-shadow: 0 8px 24px rgba(0,0,0,0.42);',
            '  font: 600 13px/1 system-ui, -apple-system, sans-serif;',
            '  cursor: pointer; user-select: none; -webkit-user-select: none;',
            '  touch-action: manipulation;',
            '}',
            '.aiko-babel-card {',
            '  position: fixed; z-index: 2147483647; max-width: min(340px, calc(100vw - 24px)); min-width: 220px;',
            '  border-radius: 16px; padding: 12px 14px;',
            '  background: var(--aiko-babel-surface); color: var(--aiko-babel-text);',
            '  border: 1px solid var(--aiko-babel-stroke);',
            '  box-shadow: 0 14px 36px rgba(0,0,0,0.55);',
            '  font: 400 13px/1.5 system-ui, -apple-system, sans-serif;',
            '  box-sizing: border-box;',
            '}',
            '.aiko-babel-card-head {',
            '  display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 6px;',
            '}',
            '.aiko-babel-card-badge {',
            '  font-size: 11px; font-weight: 600; color: var(--aiko-babel-accent);',
            '}',
            '.aiko-babel-card-src {',
            '  font-size: 12px; color: var(--aiko-babel-muted); margin-bottom: 8px;',
            '  max-height: 54px; overflow: hidden; text-overflow: ellipsis;',
            '}',
            '.aiko-babel-card-tr {',
            '  font-size: 14px; color: var(--aiko-babel-text); white-space: pre-wrap; word-break: break-word;',
            '  max-height: 200px; overflow-y: auto; margin-bottom: 10px;',
            '}',
            '.aiko-babel-card-actions {',
            '  display: flex; align-items: center; gap: 8px; justify-content: flex-end;',
            '}',
            '.aiko-babel-btn {',
            '  border: 1px solid var(--aiko-babel-stroke); background: var(--aiko-babel-card);',
            '  color: var(--aiko-babel-text); border-radius: 10px; padding: 5px 10px;',
            '  font: 500 12px/1.2 system-ui, -apple-system, sans-serif; cursor: pointer;',
            '}',
            '.aiko-babel-btn-accent {',
            '  background: var(--aiko-babel-accent); color: var(--aiko-babel-on-accent); border-color: transparent; font-weight: 600;',
            '}',
            '.aiko-babel-fab {',
            '  position: fixed; right: 12px; bottom: 88px; z-index: 2147483645;',
            '  display: flex; align-items: center; gap: 5px; height: 36px; padding: 0 12px;',
            '  border-radius: 18px; background: var(--aiko-babel-surface); color: var(--aiko-babel-accent);',
            '  border: 1px solid var(--aiko-babel-accent); box-shadow: 0 8px 24px rgba(0,0,0,0.42);',
            '  font: 600 12px/1 system-ui, -apple-system, sans-serif; cursor: pointer;',
            '  user-select: none; -webkit-user-select: none; opacity: 0.92;',
            '}'
        ].join('\n');
    }

    function ensureStyle() {
        const css = buildCss();
        // Try standard <style> tag first
        try {
            if (!styleEl || !styleEl.isConnected) {
                styleEl = document.getElementById(STYLE_ID);
                if (!styleEl) {
                    styleEl = document.createElement('style');
                    styleEl.id = STYLE_ID;
                    styleEl.setAttribute(UI_ATTR, '1');
                    (document.head || document.documentElement).appendChild(styleEl);
                }
            }
            styleEl.textContent = css;
        } catch (_) {
            // Fall through to adoptedStyleSheets
        }
        // Constructable Stylesheet fallback for strict style-src CSP pages
        try {
            if (typeof CSSStyleSheet === 'function' && 'adoptedStyleSheets' in document) {
                if (!adoptedSheet) {
                    adoptedSheet = new CSSStyleSheet();
                }
                adoptedSheet.replaceSync(css);
                if (!document.adoptedStyleSheets.includes(adoptedSheet)) {
                    document.adoptedStyleSheets = [...document.adoptedStyleSheets, adoptedSheet];
                }
            }
        } catch (_) {
            // Best-effort under restrictive browsers
        }
    }

    // ---- Script Detection & Element Eligibility ----

    function needsTranslation(text, targetLang) {
        if (!text) return false;
        const trimmed = text.trim();
        if (trimmed.length < 2) return false;
        // Skip pure URLs / emails / numbers / symbols
        if (/^(?:https?:\/\/\S+|www\.\S+|[\w.+-]+@[\w.-]+\.[a-z]{2,}|[\d\s.,:%$€¥£+\-*/=()[\]{}<>!?@#&^~|\\/"'`]+)$/i.test(trimmed)) {
            return false;
        }

        let han = 0, kana = 0, hangul = 0, latin = 0, cyrillic = 0, letters = 0;
        for (let i = 0; i < trimmed.length; i++) {
            const cp = trimmed.charCodeAt(i);
            if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)) {
                han++; letters++;
            } else if ((cp >= 0x3040 && cp <= 0x30FF)) {
                kana++; letters++;
            } else if (cp >= 0xAC00 && cp <= 0xD7AF) {
                hangul++; letters++;
            } else if ((cp >= 0x41 && cp <= 0x5A) || (cp >= 0x61 && cp <= 0x7A) || (cp >= 0xC0 && cp <= 0x024F)) {
                latin++; letters++;
            } else if (cp >= 0x0400 && cp <= 0x04FF) {
                cyrillic++; letters++;
            }
        }
        if (letters === 0) return false;

        const lang = (targetLang || 'zh-CN').toLowerCase();
        if (lang.startsWith('zh')) {
            // Already predominantly Chinese (with no Japanese kana)
            if (kana === 0 && han / letters >= 0.65 && latin < 4) return false;
            return true;
        }
        if (lang === 'en') {
            if (han > 0 || kana > 0 || hangul > 0 || cyrillic > 0) return true;
            // Pure ASCII English when target is English -> skip
            return false;
        }
        if (lang === 'ja') {
            if (kana > 0 && (kana + han) / letters >= 0.75) return false;
            return true;
        }
        if (lang === 'ko') {
            if (hangul / letters >= 0.65) return false;
            return true;
        }
        if (lang === 'ru') {
            if (cyrillic / letters >= 0.75) return false;
            return true;
        }
        return true;
    }

    function isSkippedElement(el) {
        if (!el || el.nodeType !== 1) return true;
        if (SKIP_TAGS.has(el.tagName)) return true;
        if (el.hasAttribute(UI_ATTR) || el.classList.contains(TR_CLASS)) return true;
        if (el.isContentEditable) return true;
        try {
            if (el.closest(SKIP_SELECTOR)) return true;
        } catch (_) {
            // Fallback walk
            let cur = el;
            while (cur && cur.nodeType === 1) {
                if (SKIP_TAGS.has(cur.tagName)) return true;
                if (cur.classList && (cur.classList.contains('notranslate') || cur.classList.contains(TR_CLASS))) return true;
                if (cur.getAttribute && (cur.getAttribute('translate') === 'no' || cur.hasAttribute('contenteditable'))) return true;
                cur = cur.parentElement;
            }
        }
        if (config.skipCodeAndNav) {
            let cur = el;
            while (cur && cur.nodeType === 1) {
                if (NAV_TAGS.has(cur.tagName)) {
                    // Skip nav completely; skip short header/footer chrome links
                    if (cur.tagName === 'NAV') return true;
                    const txt = extractCleanText(el);
                    if (txt.length < 48) return true;
                }
                const role = cur.getAttribute ? (cur.getAttribute('role') || '').toLowerCase() : '';
                if (NAV_ROLES.has(role)) return true;
                cur = cur.parentElement;
            }
        }
        return false;
    }

    function hasVisibleDimensions(el) {
        try {
            const style = window.getComputedStyle(el);
            if (!style || style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
                return false;
            }
        } catch (_) { }
        return true;
    }

    function extractCleanText(el) {
        const parts = [];
        const walk = (node) => {
            if (!node) return;
            if (node.nodeType === 3) {
                parts.push(node.nodeValue || '');
                return;
            }
            if (node.nodeType !== 1) return;
            if (node.classList && node.classList.contains(TR_CLASS)) return;
            if (node.hasAttribute && node.hasAttribute(UI_ATTR)) return;
            if (node.tagName === 'SCRIPT' || node.tagName === 'STYLE' || node.tagName === 'NOSCRIPT' || node.tagName === 'SVG' || node.tagName === 'MATH') {
                return;
            }
            for (let c = node.firstChild; c; c = c.nextSibling) {
                walk(c);
            }
        };
        walk(el);
        return parts.join('').replace(/\s+/g, ' ').trim();
    }

    function containsTranslatableChildBlock(el) {
        if (!el || !el.children || el.children.length === 0) return false;
        const queue = Array.from(el.children);
        while (queue.length > 0) {
            const child = queue.shift();
            if (!child || child.nodeType !== 1) continue;
            if (SKIP_TAGS.has(child.tagName) || child.classList.contains(TR_CLASS)) continue;
            if (BLOCK_TAGS.has(child.tagName)) {
                if (extractCleanText(child).length >= 2) return true;
            }
            if (child.tagName === 'DIV' || child.tagName === 'ARTICLE' || child.tagName === 'SECTION') {
                if (hasDirectMeaningfulText(child)) return true;
                for (let i = 0; i < child.children.length; i++) {
                    queue.push(child.children[i]);
                }
            }
        }
        return false;
    }

    function hasDirectMeaningfulText(el) {
        let len = 0;
        for (let c = el.firstChild; c; c = c.nextSibling) {
            if (c.nodeType === 3) {
                len += (c.nodeValue || '').trim().length;
            } else if (c.nodeType === 1 && !BLOCK_TAGS.has(c.tagName) && !CONTAINER_CANDIDATE_TAGS.has(c.tagName) && !SKIP_TAGS.has(c.tagName)) {
                len += extractCleanText(c).length;
            }
        }
        return len >= 12;
    }

    function isOnlyCodeOrSkippedChildren(el) {
        const nonSkipped = [];
        const walk = (node) => {
            if (!node) return;
            if (node.nodeType === 3) {
                nonSkipped.push(node.nodeValue || '');
                return;
            }
            if (node.nodeType !== 1) return;
            if (SKIP_TAGS.has(node.tagName) || node.classList.contains(TR_CLASS)
                    || node.classList.contains('notranslate')
                    || node.getAttribute('translate') === 'no'
                    || node.hasAttribute(UI_ATTR)) {
                return;
            }
            for (let c = node.firstChild; c; c = c.nextSibling) {
                walk(c);
            }
        };
        for (let c = el.firstChild; c; c = c.nextSibling) {
            walk(c);
        }
        const prose = nonSkipped.join(' ').replace(/\s+/g, ' ').trim();
        if (!prose) return true;
        return !/[A-Za-z\u00C0-\u024F\u0400-\u04FF\u3040-\u30FF\u4E00-\u9FFF\uAC00-\uD7AF]/.test(prose);
    }

    function isEligibleBlock(el) {
        if (!el || el.nodeType !== 1) return false;
        if (el.dataset.aikoBabelState === 'done' || el.dataset.aikoBabelState === 'pending') return false;
        if (isSkippedElement(el)) return false;
        if (!hasVisibleDimensions(el)) return false;

        const tag = el.tagName;
        const isPrimary = BLOCK_TAGS.has(tag)
            || el.getAttribute('data-testid') === 'tweetText'
            || el.classList.contains('comment-body');
        if (!isPrimary) {
            if (tag === 'DIV' || tag === 'ARTICLE' || tag === 'SECTION') {
                if (!hasDirectMeaningfulText(el)) return false;
            } else {
                return false;
            }
        }
        if (isOnlyCodeOrSkippedChildren(el)) return false;
        if (containsTranslatableChildBlock(el)) return false;

        const text = extractCleanText(el);
        if (text.length < 2 || text.length > 6000) return false;
        return needsTranslation(text, config.targetLang);
    }

    function discoverAndObserve(root) {
        if (!config.enabled || !io) return;
        const scope = root && root.querySelectorAll ? root : document.body;
        if (!scope) return;

        const candidates = [];
        if (scope !== document.body && isEligibleBlock(scope)) {
            candidates.push(scope);
        }
        const selector = 'p,h1,h2,h3,h4,h5,h6,li,td,th,blockquote,figcaption,dt,dd,summary,caption,article,div,[data-testid="tweetText"]';
        let list;
        try {
            list = scope.querySelectorAll(selector);
        } catch (_) {
            return;
        }
        for (let i = 0; i < list.length; i++) {
            const el = list[i];
            if (observedNodes.has(el)) continue;
            if (isEligibleBlock(el)) {
                candidates.push(el);
            }
        }
        for (let i = 0; i < candidates.length; i++) {
            const el = candidates[i];
            if (!observedNodes.has(el)) {
                observedNodes.add(el);
                io.observe(el);
            }
        }
    }

    function enqueueElement(el) {
        if (!config.enabled || !el || !el.isConnected) return;
        if (queuedSet.has(el)) return;
        if (el.dataset.aikoBabelState === 'done' || el.dataset.aikoBabelState === 'pending') return;
        if (isSkippedElement(el)) return;
        const text = extractCleanText(el);
        if (!needsTranslation(text, config.targetLang)) return;

        queuedSet.add(el);
        el.dataset.aikoBabelState = 'pending';
        insertOrUpdateTranslationNode(el, '…', true);
        pendingQueue.push({ id: 'n_' + (++nodeCounter), el, text });

        if (!flushTimer) {
            flushTimer = setTimeout(flushPendingBatches, 90);
        }
    }

    function flushPendingBatches() {
        flushTimer = 0;
        if (!config.enabled || pendingQueue.length === 0) {
            pendingQueue.length = 0;
            return;
        }
        const maxItems = Math.max(1, Math.min(30, config.batchMaxItems || 8));
        const maxChars = Math.max(400, Math.min(12000, config.batchMaxChars || 2400));

        while (pendingQueue.length > 0) {
            const batch = [];
            let charSum = 0;
            while (pendingQueue.length > 0) {
                const next = pendingQueue[0];
                if (batch.length > 0 && (batch.length >= maxItems || charSum + next.text.length > maxChars)) {
                    break;
                }
                pendingQueue.shift();
                if (!next.el.isConnected) continue;
                batch.push(next);
                charSum += next.text.length;
            }
            if (batch.length === 0) continue;

            const reqId = nextId('batch');
            pendingBatches.set(reqId, { gen: activeGeneration, entries: batch });
            postToNative({
                type: 'translate_batch',
                reqId,
                targetLang: config.targetLang,
                items: batch.map(item => ({ id: item.id, text: item.text }))
            });
        }
    }

    // ---- Safe <font class="aiko-babel-tr"> Injection & Style Sync ----

    function applyReplaceMetrics(el, enableReplace) {
        if (!el || !el.style) return;
        if (enableReplace) {
            if (el.getAttribute('data-aiko-babel-replace') !== '1') {
                try {
                    const cs = window.getComputedStyle(el);
                    if (cs) {
                        if (cs.fontSize) el.style.setProperty('--aiko-babel-orig-fs', cs.fontSize);
                        if (cs.lineHeight && cs.lineHeight !== 'normal') {
                            el.style.setProperty('--aiko-babel-orig-lh', cs.lineHeight);
                        }
                        if (cs.color) el.style.setProperty('--aiko-babel-orig-color', cs.color);
                    }
                } catch (_) { }
                el.setAttribute('data-aiko-babel-replace', '1');
            }
        } else if (el.hasAttribute('data-aiko-babel-replace')) {
            el.removeAttribute('data-aiko-babel-replace');
            el.style.removeProperty('--aiko-babel-orig-fs');
            el.style.removeProperty('--aiko-babel-orig-lh');
            el.style.removeProperty('--aiko-babel-orig-color');
        }
    }

    function insertOrUpdateTranslationNode(el, translatedText, isLoading) {
        if (!el || !el.isConnected) return null;
        let tr = null;
        for (let i = el.children.length - 1; i >= 0; i--) {
            const child = el.children[i];
            if (child.tagName === 'FONT' && child.classList.contains(TR_CLASS)) {
                tr = child;
                break;
            }
        }
        if (!tr) {
            tr = document.createElement('font');
            tr.className = TR_CLASS;
            tr.setAttribute('translate', 'no');
            tr.setAttribute('data-aiko-babel-node', '1');
            tr.addEventListener('click', (ev) => {
                if (tr.getAttribute('data-style') === 'blur') {
                    ev.stopPropagation();
                    tr.classList.toggle('aiko-babel-revealed');
                }
            });
            el.appendChild(tr);
        }
        tr.setAttribute('data-style', config.style);
        tr.classList.toggle('aiko-babel-loading', !!isLoading);
        tr.textContent = translatedText;
        applyReplaceMetrics(el, !isLoading && config.style === 'replace');
        return tr;
    }

    function removeTranslationNode(el) {
        if (!el) return;
        queuedSet.delete(el);
        applyReplaceMetrics(el, false);
        for (let i = el.children.length - 1; i >= 0; i--) {
            const child = el.children[i];
            if (child.tagName === 'FONT' && child.classList.contains(TR_CLASS)) {
                child.remove();
            }
        }
        delete el.dataset.aikoBabelState;
    }

    function syncStyleAcrossTranslatedNodes() {
        ensureStyle();
        const nodes = document.querySelectorAll('font.' + TR_CLASS);
        for (let i = 0; i < nodes.length; i++) {
            const tr = nodes[i];
            tr.setAttribute('data-style', config.style);
            const parent = tr.parentElement;
            if (parent) {
                const loading = tr.classList.contains('aiko-babel-loading');
                applyReplaceMetrics(parent, !loading && config.style === 'replace');
            }
        }
    }

    // ---- Page Translation Lifecycle ----

    function startPageTranslation() {
        ensureStyle();
        if (config.enabled && io) {
            syncStyleAcrossTranslatedNodes();
            discoverAndObserve(document.body);
            syncFab();
            return;
        }
        config.enabled = true;
        activeGeneration += 1;

        if (typeof IntersectionObserver === 'function') {
            io = new IntersectionObserver((entries) => {
                for (let i = 0; i < entries.length; i++) {
                    const entry = entries[i];
                    if (entry.isIntersecting) {
                        io.unobserve(entry.target);
                        enqueueElement(entry.target);
                    }
                }
            }, { root: null, rootMargin: '900px 0px', threshold: 0.01 });
        } else {
            io = {
                observe(el) { enqueueElement(el); },
                unobserve() { },
                disconnect() { }
            };
        }

        if (typeof MutationObserver === 'function' && document.body) {
            mo = new MutationObserver((mutations) => {
                if (!config.enabled) return;
                let hasAdded = false;
                for (let i = 0; i < mutations.length; i++) {
                    const m = mutations[i];
                    for (let j = 0; j < m.addedNodes.length; j++) {
                        const n = m.addedNodes[j];
                        if (n.nodeType === 1 && !n.classList.contains(TR_CLASS) && !n.hasAttribute(UI_ATTR)) {
                            hasAdded = true;
                            break;
                        }
                    }
                    if (hasAdded) break;
                }
                if (hasAdded) {
                    if (moTimer) clearTimeout(moTimer);
                    moTimer = setTimeout(() => {
                        moTimer = 0;
                        discoverAndObserve(document.body);
                    }, 240);
                }
            });
            mo.observe(document.body, { childList: true, subtree: true });
        }

        discoverAndObserve(document.body);
        syncFab();
        postToNative({ type: 'state', active: true });
    }

    function stopPageTranslation() {
        config.enabled = false;
        activeGeneration += 1;
        if (flushTimer) { clearTimeout(flushTimer); flushTimer = 0; }
        if (moTimer) { clearTimeout(moTimer); moTimer = 0; }
        if (io) { try { io.disconnect(); } catch (_) { } io = null; }
        if (mo) { try { mo.disconnect(); } catch (_) { } mo = null; }

        pendingQueue.length = 0;
        pendingBatches.clear();

        const trNodes = document.querySelectorAll('font.' + TR_CLASS);
        for (let i = 0; i < trNodes.length; i++) {
            const tr = trNodes[i];
            const parent = tr.parentElement;
            if (parent) applyReplaceMetrics(parent, false);
            tr.remove();
        }
        const marked = document.querySelectorAll('[data-aiko-babel-state], [data-aiko-babel-replace]');
        for (let i = 0; i < marked.length; i++) {
            const el = marked[i];
            applyReplaceMetrics(el, false);
            delete el.dataset.aikoBabelState;
        }
        observedNodes.clear();
        translatedNodes.clear();
        queuedSet = new WeakSet();
        syncFab();
        postToNative({ type: 'state', active: false });
    }

    // ---- Floating Mini FAB ----

    function syncFab() {
        if (config.enabled && config.showFloatingFab && document.body) {
            ensureStyle();
            if (!fabEl || !fabEl.isConnected) {
                fabEl = document.createElement('button');
                fabEl.type = 'button';
                fabEl.className = 'aiko-babel-fab';
                fabEl.setAttribute(UI_ATTR, '1');
                fabEl.setAttribute('aria-label', '巴别塔翻译设置');
                fabEl.textContent = '译 · 双语';
                fabEl.addEventListener('click', (ev) => {
                    ev.stopPropagation();
                    postToNative({ type: 'open_sheet' });
                });
                document.body.appendChild(fabEl);
            }
        } else if (fabEl) {
            fabEl.remove();
            fabEl = null;
        }
    }

    // ---- Selection Popup Translator ----

    function removeSelectionPill() {
        if (selPill) {
            selPill.remove();
            selPill = null;
        }
    }

    function removeSelectionCard() {
        if (selCard) {
            selCard.remove();
            selCard = null;
        }
    }

    function isInsideBabelUi(node) {
        if (!node) return false;
        const el = node.nodeType === 1 ? node : node.parentElement;
        return !!(el && el.closest && el.closest('[' + UI_ATTR + ']'));
    }

    function scheduleSelectionCheck() {
        if (!config.selectionTranslate) {
            removeSelectionPill();
            return;
        }
        if (selTimer) clearTimeout(selTimer);
        selTimer = setTimeout(inspectSelection, 140);
    }

    function inspectSelection() {
        selTimer = 0;
        if (!config.selectionTranslate) return;
        const sel = window.getSelection ? window.getSelection() : null;
        if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
            removeSelectionPill();
            return;
        }
        if (isInsideBabelUi(sel.anchorNode) || isInsideBabelUi(sel.focusNode)) {
            return;
        }
        const text = String(sel.toString() || '').trim();
        if (text.length < 1 || text.length > 1800) {
            removeSelectionPill();
            return;
        }
        let rect = null;
        try {
            const range = sel.getRangeAt(0);
            rect = range.getBoundingClientRect();
        } catch (_) { }
        currentSelectionText = text;
        currentSelectionRect = rect;
        showSelectionPill(rect);
    }

    function clampPosition(x, y, width, height) {
        const vw = window.innerWidth || 390;
        const vh = window.innerHeight || 844;
        const left = Math.max(10, Math.min(vw - width - 10, x));
        const top = Math.max(10, Math.min(vh - height - 10, y));
        return { left, top };
    }

    function showSelectionPill(rect) {
        if (!document.body) return;
        ensureStyle();
        removeSelectionPill();
        selPill = document.createElement('button');
        selPill.type = 'button';
        selPill.className = 'aiko-babel-pill';
        selPill.setAttribute(UI_ATTR, '1');
        selPill.setAttribute('data-aiko-babel-role', 'selection-pill');
        selPill.textContent = '译';

        const baseX = rect && (rect.width > 0 || rect.height > 0) ? (rect.left + rect.width / 2 - 20) : 24;
        const baseY = rect && (rect.width > 0 || rect.height > 0)
            ? (rect.top > 52 ? rect.top - 42 : rect.bottom + 8)
            : 24;
        const pos = clampPosition(baseX, baseY, 44, 36);
        selPill.style.left = Math.round(pos.left) + 'px';
        selPill.style.top = Math.round(pos.top) + 'px';

        selPill.addEventListener('mousedown', (ev) => ev.preventDefault());
        selPill.addEventListener('click', (ev) => {
            ev.stopPropagation();
            const textToTranslate = currentSelectionText;
            const anchorRect = currentSelectionRect;
            removeSelectionPill();
            if (!textToTranslate) return;
            const reqId = nextId('sel');
            pendingSelections.set(reqId, { text: textToTranslate });
            showSelectionCard(textToTranslate, '正在翻译…', true, anchorRect);
            postToNative({
                type: 'translate_selection',
                reqId,
                text: textToTranslate,
                targetLang: config.targetLang
            });
        });
        document.body.appendChild(selPill);
    }

    function showSelectionCard(sourceText, translatedText, isLoading, rect) {
        if (!document.body) return;
        ensureStyle();
        removeSelectionCard();
        lastTranslatedSelection = isLoading ? '' : translatedText;

        selCard = document.createElement('div');
        selCard.className = 'aiko-babel-card';
        selCard.setAttribute(UI_ATTR, '1');
        selCard.setAttribute('data-aiko-babel-role', 'selection-card');

        const head = document.createElement('div');
        head.className = 'aiko-babel-card-head';
        const badge = document.createElement('span');
        badge.className = 'aiko-babel-card-badge';
        badge.textContent = '巴别塔划词译 · ' + config.targetLang;
        const closeX = document.createElement('button');
        closeX.type = 'button';
        closeX.className = 'aiko-babel-btn';
        closeX.textContent = '✕';
        closeX.style.padding = '2px 8px';
        closeX.addEventListener('click', () => removeSelectionCard());
        head.appendChild(badge);
        head.appendChild(closeX);
        selCard.appendChild(head);

        const src = document.createElement('div');
        src.className = 'aiko-babel-card-src';
        src.textContent = sourceText.length > 120 ? (sourceText.slice(0, 120) + '…') : sourceText;
        selCard.appendChild(src);

        const tr = document.createElement('div');
        tr.className = 'aiko-babel-card-tr';
        tr.setAttribute('data-aiko-babel-role', 'selection-output');
        tr.textContent = translatedText;
        if (isLoading) tr.style.opacity = '0.65';
        selCard.appendChild(tr);

        const actions = document.createElement('div');
        actions.className = 'aiko-babel-card-actions';

        const copyBtn = document.createElement('button');
        copyBtn.type = 'button';
        copyBtn.className = 'aiko-babel-btn';
        copyBtn.setAttribute('data-aiko-babel-action', 'copy');
        copyBtn.textContent = '复制';
        copyBtn.addEventListener('click', () => {
            const val = lastTranslatedSelection || tr.textContent || '';
            if (!val) return;
            try {
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(val).catch(() => {});
                }
            } catch (_) { }
            postToNative({ type: 'copy', text: val });
            copyBtn.textContent = '已复制';
        });

        const ttsBtn = document.createElement('button');
        ttsBtn.type = 'button';
        ttsBtn.className = 'aiko-babel-btn aiko-babel-btn-accent';
        ttsBtn.setAttribute('data-aiko-babel-action', 'speak');
        ttsBtn.textContent = '朗读';
        ttsBtn.addEventListener('click', () => {
            const val = lastTranslatedSelection || tr.textContent || '';
            if (val) postToNative({ type: 'speak', text: val, lang: config.targetLang });
        });

        actions.appendChild(copyBtn);
        actions.appendChild(ttsBtn);
        selCard.appendChild(actions);

        const baseX = rect && (rect.width > 0 || rect.height > 0) ? rect.left : 16;
        const baseY = rect && (rect.width > 0 || rect.height > 0) ? (rect.bottom + 10) : 64;
        const pos = clampPosition(baseX, baseY, 310, 175);
        selCard.style.left = Math.round(pos.left) + 'px';
        selCard.style.top = Math.round(pos.top) + 'px';

        document.body.appendChild(selCard);
    }

    // ---- Triple-Space Input Back-Translation ----

    function isEligibleInputElement(el) {
        if (!el || el.nodeType !== 1) return false;
        if (el.hasAttribute(UI_ATTR) || (el.closest && el.closest('[' + UI_ATTR + ']'))) return false;
        if (el.tagName === 'TEXTAREA') return !el.readOnly && !el.disabled;
        if (el.tagName === 'INPUT') {
            if (el.readOnly || el.disabled) return false;
            const type = (el.getAttribute('type') || '').toLowerCase();
            return INPUT_ALLOWED_TYPES.has(type);
        }
        return !!el.isContentEditable;
    }

    function readEditableText(el) {
        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
            return String(el.value || '');
        }
        return String(el.innerText != null ? el.innerText : (el.textContent || ''));
    }

    function writeEditableText(el, newValue) {
        if (!el || !el.isConnected) return;
        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
            const proto = el.tagName === 'TEXTAREA'
                ? window.HTMLTextAreaElement && window.HTMLTextAreaElement.prototype
                : window.HTMLInputElement && window.HTMLInputElement.prototype;
            const desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
            if (desc && typeof desc.set === 'function') {
                desc.set.call(el, newValue);
            } else {
                el.value = newValue;
            }
            try {
                el.dispatchEvent(new InputEvent('input', {
                    bubbles: true, cancelable: false, data: newValue, inputType: 'insertReplacementText'
                }));
            } catch (_) {
                el.dispatchEvent(new Event('input', { bubbles: true }));
            }
            el.dispatchEvent(new Event('change', { bubbles: true }));
            try {
                if (typeof el.setSelectionRange === 'function') {
                    el.setSelectionRange(newValue.length, newValue.length);
                }
            } catch (_) { }
            return;
        }
        // contenteditable
        el.textContent = newValue;
        try {
            const sel = window.getSelection();
            if (sel) {
                const range = document.createRange();
                range.selectNodeContents(el);
                range.collapse(false);
                sel.removeAllRanges();
                sel.addRange(range);
            }
        } catch (_) { }
        try {
            el.dispatchEvent(new InputEvent('input', {
                bubbles: true, cancelable: false, data: newValue, inputType: 'insertReplacementText'
            }));
        } catch (_) {
            el.dispatchEvent(new Event('input', { bubbles: true }));
        }
    }

    function getSpaceState(el) {
        let st = spaceTracker.get(el);
        if (!st) {
            st = { times: [], lastKeyTs: 0, lastVal: readEditableText(el), timer: 0 };
            spaceTracker.set(el, st);
        }
        return st;
    }

    function recordSpacePress(el, now, fromKeydown) {
        const st = getSpaceState(el);
        st.times = st.times.filter(ts => (now - ts) <= 750);
        st.times.push(now);
        if (fromKeydown) st.lastKeyTs = now;

        if (st.times.length >= 3 && (now - st.times[st.times.length - 3]) <= 750) {
            st.times = [];
            if (st.timer) clearTimeout(st.timer);
            st.timer = setTimeout(() => {
                st.timer = 0;
                triggerInputBackTranslation(el);
            }, 0);
        }
    }

    function triggerInputBackTranslation(el) {
        if (!config.tripleSpaceInputTranslate || !isEligibleInputElement(el)) return;
        const raw = readEditableText(el);
        const trimmed = raw.replace(/[ \u00A0]+$/g, '').trim();
        if (!trimmed || trimmed.length > 4000) return;
        if (!needsTranslation(trimmed, config.inputTargetLang)) return;

        ensureStyle();
        el.classList.add('aiko-babel-input-loading');
        const reqId = nextId('inp');
        pendingInputs.set(reqId, { el, origText: trimmed });
        postToNative({
            type: 'translate_input',
            reqId,
            text: trimmed,
            targetLang: config.inputTargetLang
        });
    }

    function onDocumentKeydown(ev) {
        if (!config.tripleSpaceInputTranslate) return;
        const el = ev.target;
        if (!isEligibleInputElement(el)) return;
        const isSpace = ev.key === ' ' || ev.code === 'Space' || ev.keyCode === 32;
        if (isSpace) {
            recordSpacePress(el, Date.now(), true);
        } else if (ev.key && ev.key.length === 1 && ev.key !== ' ') {
            const st = getSpaceState(el);
            st.times = [];
        }
    }

    function onDocumentInput(ev) {
        if (!config.tripleSpaceInputTranslate) return;
        const el = ev.target;
        if (!isEligibleInputElement(el)) return;
        const st = getSpaceState(el);
        const val = readEditableText(el);
        const prev = st.lastVal;
        st.lastVal = val;

        const now = Date.now();
        // Avoid double-counting when keydown already recorded this space press
        if (now - st.lastKeyTs < 60) return;

        const addedSpace = (ev.data === ' ' || ev.data === '\u00A0')
            || (val.length === prev.length + 1 && /[ \u00A0]$/.test(val));
        if (addedSpace) {
            recordSpacePress(el, now, false);
        } else if (val.length < prev.length || !/[ \u00A0]$/.test(val)) {
            st.times = [];
        }
    }

    function onDocumentPointerDown(ev) {
        if (isInsideBabelUi(ev.target)) return;
        removeSelectionCard();
        if (selPill && ev.target !== selPill) {
            removeSelectionPill();
        }
    }

    // Attach global passive listeners once
    document.addEventListener('selectionchange', scheduleSelectionCheck, true);
    document.addEventListener('mouseup', scheduleSelectionCheck, true);
    document.addEventListener('touchend', scheduleSelectionCheck, true);
    document.addEventListener('keydown', onDocumentKeydown, true);
    document.addEventListener('input', onDocumentInput, true);
    document.addEventListener('mousedown', onDocumentPointerDown, true);
    document.addEventListener('touchstart', onDocumentPointerDown, { capture: true, passive: true });

    // ---- Public Native-Callable Controller ----

    function applyConfig(opts) {
        if (!opts || typeof opts !== 'object') return;
        if (typeof opts.style === 'string' && opts.style) config.style = opts.style;
        if (typeof opts.targetLang === 'string' && opts.targetLang) config.targetLang = opts.targetLang;
        if (typeof opts.inputTargetLang === 'string' && opts.inputTargetLang) config.inputTargetLang = opts.inputTargetLang;
        if (typeof opts.selectionTranslate === 'boolean') config.selectionTranslate = opts.selectionTranslate;
        if (typeof opts.tripleSpaceInputTranslate === 'boolean') config.tripleSpaceInputTranslate = opts.tripleSpaceInputTranslate;
        if (typeof opts.skipCodeAndNav === 'boolean') config.skipCodeAndNav = opts.skipCodeAndNav;
        if (typeof opts.showFloatingFab === 'boolean') config.showFloatingFab = opts.showFloatingFab;
        if (typeof opts.batchMaxItems === 'number') config.batchMaxItems = opts.batchMaxItems;
        if (typeof opts.batchMaxChars === 'number') config.batchMaxChars = opts.batchMaxChars;
        if (opts.theme && typeof opts.theme === 'object') {
            Object.assign(config.theme, opts.theme);
        }
        ensureStyle();
        if (!config.selectionTranslate) {
            removeSelectionPill();
            removeSelectionCard();
        }
        if (config.enabled) {
            syncStyleAcrossTranslatedNodes();
        }
        syncFab();
    }

    let spaHooked = false;
    let spaTimer = 0;
    function hookSpaRoutes() {
        if (spaHooked) return;
        spaHooked = true;
        const scheduleRescan = () => {
            if (!config.enabled) return;
            if (spaTimer) clearTimeout(spaTimer);
            spaTimer = setTimeout(() => {
                spaTimer = 0;
                if (config.enabled && document.body) {
                    discoverAndObserve(document.body);
                }
            }, 350);
        };
        try {
            const origPush = history.pushState;
            if (typeof origPush === 'function') {
                history.pushState = function () {
                    const ret = origPush.apply(this, arguments);
                    scheduleRescan();
                    return ret;
                };
            }
            const origReplace = history.replaceState;
            if (typeof origReplace === 'function') {
                history.replaceState = function () {
                    const ret = origReplace.apply(this, arguments);
                    scheduleRescan();
                    return ret;
                };
            }
            window.addEventListener('popstate', scheduleRescan, true);
        } catch (_) { }
    }

    window.__aikoBabel = {
        __version: '0.5.0',

        configure(opts) {
            hookSpaRoutes();
            const wasEnabled = config.enabled;
            const prevTargetLang = config.targetLang;
            applyConfig(opts);
            const wantEnabled = (opts && typeof opts.enabled === 'boolean') ? opts.enabled : wasEnabled;
            if (wantEnabled) {
                if (wasEnabled && prevTargetLang !== config.targetLang) {
                    stopPageTranslation();
                }
                startPageTranslation();
            } else if (config.enabled) {
                stopPageTranslation();
            }
        },

        start(opts) {
            if (opts) applyConfig(opts);
            startPageTranslation();
        },

        stop() {
            stopPageTranslation();
        },

        toggle(opts) {
            if (config.enabled) {
                stopPageTranslation();
                return false;
            }
            if (opts) applyConfig(opts);
            startPageTranslation();
            return true;
        },

        setStyle(styleKey) {
            if (typeof styleKey === 'string' && styleKey) {
                config.style = styleKey;
                syncStyleAcrossTranslatedNodes();
            }
        },

        setTheme(theme) {
            if (theme && typeof theme === 'object') {
                Object.assign(config.theme, theme);
                ensureStyle();
            }
        },

        applyBatchResult(reqId, translations) {
            const job = pendingBatches.get(reqId);
            if (!job) return;
            pendingBatches.delete(reqId);
            if (!config.enabled || job.gen !== activeGeneration) return;
            const list = Array.isArray(translations) ? translations : [];
            for (let i = 0; i < job.entries.length; i++) {
                const entry = job.entries[i];
                const trText = typeof list[i] === 'string' ? list[i].trim() : '';
                if (!entry.el.isConnected) continue;
                if (trText) {
                    entry.el.dataset.aikoBabelState = 'done';
                    insertOrUpdateTranslationNode(entry.el, trText, false);
                    translatedNodes.add(entry.el);
                } else {
                    removeTranslationNode(entry.el);
                }
            }
            if (fabEl && fabEl.isConnected) fabEl.textContent = '译 · 双语';
        },

        applyBatchError(reqId, message) {
            const job = pendingBatches.get(reqId);
            if (!job) return;
            pendingBatches.delete(reqId);
            for (let i = 0; i < job.entries.length; i++) {
                removeTranslationNode(job.entries[i].el);
            }
            if (fabEl && fabEl.isConnected) {
                fabEl.textContent = '译 · 点按重试';
                fabEl.title = message || '翻译失败';
            }
        },

        applySelectionResult(reqId, translatedText) {
            const job = pendingSelections.get(reqId);
            pendingSelections.delete(reqId);
            const clean = typeof translatedText === 'string' ? translatedText.trim() : '';
            lastTranslatedSelection = clean;
            if (selCard && selCard.isConnected) {
                const out = selCard.querySelector('[data-aiko-babel-role="selection-output"]');
                if (out) {
                    out.textContent = clean || '（无译文）';
                    out.style.opacity = '1';
                }
            } else if (job) {
                showSelectionCard(job.text, clean || '（无译文）', false, currentSelectionRect);
            }
        },

        applySelectionError(reqId, message) {
            pendingSelections.delete(reqId);
            if (selCard && selCard.isConnected) {
                const out = selCard.querySelector('[data-aiko-babel-role="selection-output"]');
                if (out) {
                    out.textContent = '翻译失败：' + (message || '请检查网络或配置');
                    out.style.opacity = '1';
                }
            }
        },

        showQuickSelection(sourceText, translatedText) {
            showSelectionCard(sourceText || '', translatedText || '', false, currentSelectionRect);
        },

        applyInputResult(reqId, translatedText) {
            const job = pendingInputs.get(reqId);
            if (!job) return;
            pendingInputs.delete(reqId);
            job.el.classList.remove('aiko-babel-input-loading');
            const clean = typeof translatedText === 'string' ? translatedText.trim() : '';
            if (clean && job.el.isConnected) {
                writeEditableText(job.el, clean);
                const st = getSpaceState(job.el);
                st.lastVal = clean;
                st.times = [];
            }
        },

        applyInputError(reqId) {
            const job = pendingInputs.get(reqId);
            if (!job) return;
            pendingInputs.delete(reqId);
            job.el.classList.remove('aiko-babel-input-loading');
        },

        isActive() {
            return !!config.enabled;
        },

        getStats() {
            return {
                active: !!config.enabled,
                style: config.style,
                targetLang: config.targetLang,
                observedCount: observedNodes.size,
                translatedCount: translatedNodes.size,
                pendingBatches: pendingBatches.size
            };
        }
    };
})();

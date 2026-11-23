# 站点控制、过滤与油猴脚本

## 站点控制

打开网页后点地址栏左侧的盾牌。桌面模式、字号和图片开关按精确主机名保存。切换桌面模式会刷新当前页，并用与系统 WebView Chromium 主版本一致的桌面标识发起下一次请求。某些站点在表单提交或重定向后可手动刷新以应用目标站点的桌面设置。字号和网络图片开关会立即应用到当前 WebView，已经显示的图片可通过刷新重新加载。

「拦截记录」列出当前文档的拦截总数，以及最多 12 条最近命中。详情包含请求域名、列表来源与匹配文本，只在该文档的内存中保存。CSS 元素隐藏未计入网络请求数。「恢复默认」会还原该站点的浏览和过滤设置，保留普通标签的登录数据和历史。

## 自定义过滤规则

打开「⋯ → 过滤规则 → 自定义规则」，每行填一条 ABP 规则。保存时会逐行检查，遇到不识别的语法会标出行号。规则会在后台编译，刷新网页即可应用新的加载结果。清空并保存可移除全部自定义规则。

```text
! 拦截广告域
||ads.example.com^
! 只在 forum.example.com 放行特定脚本
@@||metrics.example.com/widget.js$script,domain=forum.example.com
! 隐藏当前站点的赞助栏
forum.example.com##.sponsored-banner
```

自定义规则和内置列表合并，支持 `@@` 例外、`$document`、`$badfilter`、按站点的元素隐藏 `##` 与例外 `#@#`。上限为 2,000 条和 128,000 字符。支持 WebView 可见的常见 ABP 语法，脚本片段、URL 重定向和扩展过滤语法仍有缺口。

## 油猴脚本

从「⋯ → 油猴脚本 → 导入脚本」粘贴源码、打开本地 UTF-8 `.user.js` 文件，或输入 HTTPS 的脚本源码地址。导入时会显示命名空间、匹配和排除网址、运行时机以及 GM 授权。导入或替换后默认关闭，进入脚本详情启用，刷新网页即可运行。在详情的「管理」菜单也可编辑或删除脚本。

- 匹配支持 HTTP(S) 的 `@match`、通配符 `@include`、`@exclude` 和 `@exclude-match`。仅在普通标签的顶层文档执行。时间支持 `document-start`、`document-body`、`document-end` 和 `document-idle`。
- 支持 `GM_info`、`GM_log`、`GM_addStyle`、`GM_getValue`、`GM_setValue`、`GM_deleteValue`、`GM_listValues` 及对应的 `GM.*` 形式。存储按脚本隔离。同一文档内立即读到自己的修改，新文档会收到已保存的值。每个脚本可以保存最多 128 个键，合计 32,768 字符。
- 新版系统 WebView 支持独立的脚本环境。无授权脚本可用 `@sandbox raw` 选择与网页共享 JavaScript 环境，这时站点的脚本策略可能影响运行。老 WebView 可运行部分无授权脚本，隔离和早期注入请以脚本详情的提示为准。
- 依赖 `GM_xmlhttpRequest`、`GM_cookie`、远程 `@require` 或 `@resource` 的脚本暂不能安装。导入时会指出不支持的声明。

可以用下面的脚本快速验证。导入并启用后打开 example.com，标题会变成玫红色。

```javascript
// ==UserScript==
// @name         Aiko 样式测试
// @match        https://example.com/*
// @grant        GM_addStyle
// @run-at       document-end
// ==/UserScript==
GM_addStyle('h1 { color: #e84f79; }');
```

[Tampermonkey 元数据和 GM 接口](https://www.tampermonkey.net/documentation.php) · [AndroidX WebView 注入接口](https://developer.android.com/reference/androidx/webkit/WebViewCompat)

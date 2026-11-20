# AI 助手接入实施计划（right-sidebar Tab + X_Assistant SDK + 宿主 postMessage 通信层）

> 需求来源：`docs/UI/agent-intergration.md`（智能助理接入需求）
> 状态：**已完成需求澄清与方案设计（2026-11-09），尚未实施**。后续会话按本计划执行。
> 决策均已经用户确认，实施时勿再重新讨论。

## Context（背景）

本 fork 的 Penpot 会被宿主平台通过 iframe 嵌入（已有 `app.util.embed` 启动握手层与 `PENPOT_EMBED_*` 配置链）。本次需求：在 workspace 右侧栏 tab 切换器新增「AI助手」Tab（Design | AI助手 | Inspect），激活时动态加载宿主提供的 JS SDK（挂 `window.X_Assistant`），SDK 初始化参数通过 postMessage 向父页面获取，SDK 回调事件转发回父页面。真实 SDK 由宿主提供，开发/审查期用自建 mock SDK。

## 用户已确认的决策

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 宿主协议现状 | 协议由我方定义，宿主后实现 |
| 2 | SDK 全局对象名 | `window.X_Assistant`（文档中 `AI_Assistant` 为笔误；init/render/destroy 都在 `X_Assistant` 上） |
| 3 | 环境变量名 | `PENPOT_X_FRONT_SDK`；值=SDK script URI；非空且 `embed/embedded?` 才显示 tab |
| 4 | 消息 type 拼写 | 修正为 `AI_ASSISTANT_EVENT`（弃用文档拼写 AI_ASSITANT_EVENT） |
| 5 | 必填参数校验失败 UX | 错误占位 + 重试按钮（复用现有 `labels.retry` 文案） |
| 6 | 销毁语义 | 切走 tab 即销毁（SDK destroy + 卸载组件 + 摘 script + 清全局）；切回重走完整流程，每次新会话 |
| 7 | PAGE_ID/FILE_ID 来源 | Penpot 当前 state（每条消息发送时经 ref 取最新值） |
| 8 | mock SDK 形态 | 极简占位 UI + 按钮触发全部回调事件链 |
| 9 | Tab 位置 | 右侧栏 Design/Inspect 切换器，顺序 **Design \| AI助手 \| Inspect** |

## 通信协议规格（我方定义，宿主按此实现）

- 通道：`window.parent.postMessage` ↔ `window.addEventListener("message")`
- 消息附加判别字段 `scope: "penpot/assistant"`（子端接收时容忍缺失）
- 子→父：`{scope, type: "AI_ASSISTANT_EVENT", eventName, payload, reqId?}`
- 父→子：`{scope, type: "AI_ASSISTANT_RESPONSE", eventName, payload}`（须应答到 iframe contentWindow）
- 安全：入站校验 `event.source === js/parent` + `embed/origin-accepted? cf/embed-parent-origin event.origin` 精确匹配（未配置则通配放行并一次性 warn）；出站 targetOrigin = `normalize-origin(cf/embed-parent-origin)`，缺省 `"*"` 并一次性 warn（生产必须配 `PENPOT_EMBED_PARENT_ORIGIN`，与凭据握手共用，无需新变量）

| eventName | 方向 | reqId | payload |
|---|---|---|---|
| `ready` | 子→父 | 无 | `{timestamp}`；挂载即发，每 2s 重发直至父应答 |
| `ready` | 父→子 | — | `{ok: true, timestamp}` 握手 ack |
| `getInitParams` | 子→父 | 有 | `{timestamp}`；握手完成后发起，每 2s 重发同一 reqId，10s 超时 |
| `getInitParams` | 父→子 | 回显 | 成功 `{ok:true, reqId, params:{idToken, appName, agentId, skillName, logoUrl?}}`；失败 `{ok:false, reqId, error}`。四必填字段非空字符串；`logoUrl` 可选（有则传 init 的 `appImage.src`） |
| `initialized` | 子→父 | 无 | `{timestamp}`（SDK init+render 成功后） |
| `afterSendMessage` / `onMessageFinish` | 子→父 | 无 | `{timestamp, ...ISessionData}`（SDK 回调透传） |
| `onSatisfactionRatingComplete` | 子→父 | 无 | `{timestamp, ...result}`（修正需求文档 :213 的 eventName 误写） |

超时参数（可注入）：`resend-interval-ms 2000`、`handshake-timeout-ms 15000`、`request-timeout-ms 10000`。

## 实施方案

### A. 通信层（新 ns `app.util.embed.assistant`）

**新文件 `frontend/src/app/util/embed/assistant.cljs`**（`embed.cljs` 文件与 `embed/` 目录共存合法，仓库有 `workspace.cljs`+`workspace/` 先例）。复用 `app.util.embed` 的公开纯函数 `normalize-origin`(embed.cljs:61)、`origin-accepted?`(:73) 与 `cf/embed-parent-origin`。**零顶层副作用**（不 addEventListener、不读 window；默认 IO 参数在 `create-conn` 闭包内经 `ug/window` 惰性取，node 测试环境可注入）。

```clojure
;; 纯函数（单测直测）
(defn valid-init-params? [params])  ;; 四必填字段（idToken/appName/agentId/skillName）均非空字符串
(defn parse-incoming [data])        ;; event.data -> {:type :event-name :req-id :payload} | nil

;; 连接（每次 tab 激活创建，卸载即销毁；无 defonce）
(defn create-conn [opts])
;; => {:handshake! (fn [] -> Promise :ready|:timeout)
;;     :request!   (fn [event-name payload] -> Promise {:ok true :payload}|{:ok false :error})
;;     :post-event! (fn [event-name payload])   ;; 单向上行通知
;;     :close!     (fn [])}                     ;; 摘监听+清定时器，幂等
```

可注入项：`post-message! add-listener! remove-listener! set-timeout! clear-timeout! set-interval! clear-interval! origin-allowed? target-origin parent on-event`。reqId 匹配才 resolve request；未消费的下行消息走 `on-event` 占位回调。

### B. 状态机 + UI 组件

**`layout.cljs`（唯一必改状态机触点）**：`frontend/src/app/main/data/workspace/layout.cljs:59-60` 的 `valid-options-mode` 加 `:assistant`——`set-options-mode`(:143-147) 有 `dm/assert!` 生产也抛，**必须先于 UI 接线改**。其余触点（set-options-mode 事件体、初始值 :76-77、切文件重置、持久化 mapping、URL、viewport/sidebar/context_menu 的 `=` 比较、render_wasm context 保存恢复）已逐一审计无需改；`notifications.cljs` 角色变更会强制切走 mode（销毁助手会话，预期行为）。

**`options.cljs` 接线**（`frontend/src/app/main/ui/workspace/sidebar/options.cljs`）：
1. ns require 加 `[app.config :as cf]` `[app.util.embed :as embed]` `[app.main.ui.ds.foundations.assets.icon :as i]` `[app.main.ui.workspace.sidebar.assistant :refer [assistant-tab*]]`
2. 新增 `(defn- assistant-tab-visible? [] (and (some? cf/x-front-sdk) (embed/embedded?)))`
3. `options-tabs`(:216-225)：**assistant 的条件 conj 必须放在 `:always (conj inspect)` 之前**，保证 Design | AI助手 | Inspect 顺序：
   ```clojure
   (cond-> [{:label (tr "workspace.options.design") :id "design"}]
     assistant-tab-visible?
     (conj {:label (tr "workspace.options.assistant") :id "assistant" :icon i/assistant})
     :always
     (conj {:label (tr "workspace.options.inspect") :id "inspect"}))
   ```
4. `on-option-tab-change`(:227-233) 不改（`:assistant` 走 else 分支 read-only=false，正确）
5. case(:263-283) 加分支：`:assistant [:> assistant-tab* {:page-id page-id :file-id file-id}]`（props 已解构 page-id/file-id，:237）
6. `visible-options-tab-ids`(:204-208) 同步追加条件项（当前无调用方，保持语义一致）

**范围说明**：只读分支（`:can-edit` false，options.cljs:285-292）不渲染 switcher，本期助手 tab 仅可编辑分支可见（与 design 同策略）；viewer 放开需重构只读分支，另立任务。

**新组件 `frontend/src/app/main/ui/workspace/sidebar/assistant.cljs` + `assistant.scss`**：

状态机：`:handshaking → :requesting → :loading-sdk → :initializing → :ready`；任一步失败/超时 → `[:error reason]`，reason ∈ `#{:handshake-timeout :params-timeout :invalid-params :sdk-load :sdk-global :sdk-init}`。loading 态显示 spinner+文案，error 态显示对应文案+重试按钮（点击 attempt+1 重走全流程）。

生命周期（`mf/use-effect [attempt]`）：
1. `create-conn` → `handshake!`（15s 超时）
2. `request! "getInitParams"`（10s 超时）；应答 `ok:false` 或 `valid-init-params?` 失败 → `:invalid-params`
3. 动态加载 SDK：先 `querySelector "[data-penpot-assistant-sdk]"` 移除旧节点，`dom/create-element "script"`（参照 `app/util/dom.cljs` create+appendChild 模式），src=`cf/x-front-sdk`、标记 `data-penpot-assistant-sdk`、appendChild 到 `document.head`；onload→下一步，onerror→`:sdk-load`
4. `(unchecked-get ug/global "X_Assistant")`，非 object 或 init/render 非 fn → `:sdk-global`
5. init config（try/catch → `:sdk-init`）：
   ```clojure
   #js {:appName appName :agentId agentId
        :getIdToken (fn [] (p/resolved idToken))
        :defaultSkillName skillName :prompts nil
        :layoutType "domMount"
        ;; 需求文档键名自相矛盾（:48 beforeSendMesssage 三连 s / :112 beforeSendMessage），
        ;; 真实 SDK 拼写未知 —— 两个键都设同一个函数兜底
        :beforeSendMesssage enrich-fn :beforeSendMessage enrich-fn
        ;; enrich-fn: 从 mf/use-ref 取最新 page-id/file-id，obj/set! "envs" {"PAGE_ID" "FILE_ID"}
        :afterSendMessage #(post-event! "afterSendMessage" ...)
        :onMessageFinish #(post-event! "onMessageFinish" ...)
        :satisfactionRating #js {:enabled true :onComplete #(post-event! "onSatisfactionRatingComplete" ...)}}
   ```
   （page-id/file-id 用 `mf/use-ref` 每次 render 更新，回调读 ref 避免陈旧值；`logoUrl` 有则加 `:appImage #js {:src logoUrl :className "ai-penpot-icon" :hideClose true}`）
6. 渲染挂载 div（固定 id `"penpot-assistant-mount"`），`(sdk/render "penpot-assistant-mount")`
7. `post-event! "initialized"` → `:ready`

卸载 cleanup（切走/重试时执行，幂等）：`X_Assistant.destroy()`（fn? 判空+try/catch）→ 移除 script 节点 → `(unchecked-set ug/global "X_Assistant" nil)` → `(conn :close!)`。

`assistant.scss`：`.assistant-tab {height:100%; display:flex; flex-direction:column}`、`.mount {flex:1; min-height:0; width:100%; overflow:hidden}`（tab-panel 在 `.tabs` grid `1fr` 行内自动撑满，无需 hack 高度）。

### C. 配置链（照 `penpotEmbed*` 三 knob 现有路径）

| 文件 | 改动 |
|---|---|
| `frontend/src/app/config.cljs` | `embed-boot-deadline-ms`(:247-253) 后新增 `(def x-front-sdk ...)`：读 `global "penpotXFrontSdk"`，非空字符串 trim 后返回否则 nil |
| `frontend/resources/config.js` | `penpotEmbedBootDeadlineMs` 块后新增 IIFE 默认 `globalThis.penpotXFrontSdk = ""` + 注释（空=隐藏 tab，docker 由 `PENPOT_X_FRONT_SDK` 覆盖，值为 SDK script URI） |
| `docker/images/files/config.js` | 同步新增同一块（两文件内容本有差异，逐块手工同步勿整拷） |
| `docker/images/files/nginx-entrypoint.sh` | 新增 `update_x_front_sdk()`（仿 `update_embed_parent_origin` :215-244）：空值 return；`none` 清空；正则 `^(https?://|/)[A-Za-z0-9._~:/?#\[\]@!$&*+,;=%-]+$` 校验（允许绝对 URL 或根相对路径，排除引号/反引号/空白/`<>` 防注入），不匹配 stderr 告警保留默认；调用列表(:341-352)末尾追加 |
| `docker/images/docker-compose.yaml` | frontend env 注释区(~:168)加 `# PENPOT_X_FRONT_SDK: "https://portal.example.com/ai/sdk.js"` 示例（注明本地调试可 `/assistant/mock-assistant-sdk.js`，须同时 iframe 嵌入，须配 `PENPOT_EMBED_PARENT_ORIGIN`） |

e2e 不改 `BasePage.js`：现有 `mockConfigFlags(page, [], {penpotXFrontSdk: "..."})` extras 即可注入。

### D. i18n + 图标

i18n（`.po` 机制，缺失 key 自动回退 en，**只改 en.po + zh_CN.po 两个文件**，其余 40 语言回退英文）：

| key | en | zh_CN |
|---|---|---|
| `workspace.options.assistant` | AI Assistant | AI助手 |
| `workspace.options.assistant.loading` | Connecting the AI assistant… | 正在连接 AI 助手… |
| `workspace.options.assistant.error.handshake` | Could not reach the host page. | 无法连接宿主页面。 |
| `workspace.options.assistant.error.params` | The host page did not supply valid assistant parameters. | 宿主页面未返回有效的助手初始化参数。 |
| `workspace.options.assistant.error.sdk` | Failed to load or initialize the assistant SDK. | 助手 SDK 加载或初始化失败。 |

（插入在 `workspace.options.design` 附近：en.po ~:8108、zh_CN.po ~:6520，**编辑前先 grep 核实行号**，带 `#: src/...` 引用注释。重试按钮复用 `labels.retry`（en.po:3747、zh_CN.po:2880）。）

图标：新文件 `frontend/resources/images/icons/assistant.svg`（viewBox `0 0 16 16`、stroke 风格单 path 无 fill/stroke 属性，照抄 `help.svg` 格式，图形用四角星/火花 sparkle）；`frontend/src/app/main/ui/ds/foundations/assets/icon.cljs` 按字母序加 `(def ^:icon-id assistant "assistant")`。**新增 svg watch 不重编 sprite，必须手动 `pnpm build:app:assets`**。

### E. mock SDK + 开发父页面（新目录 `frontend/resources/assistant/`）

- `mock-assistant-sdk.js`：IIFE 纯 JS。挂 `window.X_Assistant = {init, render, destroy, on, off, getTools}`；init 把 config 存 `window.__mockAssistantConfig`；render 在挂载 div 画极简占位 UI + 按钮：触发 `afterSendMessage`/`onMessageFinish`/`satisfactionRating.onComplete` 回调、显示收到的 before 改写结果；destroy 清 DOM 并置 `window.__mockAssistantDestroyed = true`（联调断言用）
- `dev-host.html`：开发用模拟父页面——iframe 嵌 Penpot、实现协议父侧（回 ready ack / getInitParams 应答）、页面打印全部子→父事件。与 mock 同目录被伺服到 `/assistant/dev-host.html`，iframe src 指向同源 Penpot（origin 未配置时通配放行，可直接工作）
- 构建拷贝 2 处：`frontend/scripts/_helpers.js` `copyAssets()`（~:625-633）加 `syncDirs("resources/assistant/", "resources/public/assistant/")`；`frontend/scripts/watch.js`（~:105）watch 数组加 `"resources/assistant"`。dev 由 shadow `:dev-http {8888 "classpath:public"}` 伺服，生产由 nginx root 伺服 `/assistant/...`

### F. 文件清单汇总

**新增**：`app/util/embed/assistant.cljs`、`workspace/sidebar/assistant.cljs`、`assistant.scss`、`resources/assistant/mock-assistant-sdk.js`、`resources/assistant/dev-host.html`、`resources/images/icons/assistant.svg`、`frontend/test/frontend_tests/util_embed_assistant_test.cljs`、（可选）`frontend/playwright/ui/specs/assistant-tab.spec.js`

**修改**：`data/workspace/layout.cljs`（valid-options-mode）、`ui/workspace/sidebar/options.cljs`、`app/config.cljs`、两份 config.js、`nginx-entrypoint.sh`、`docker-compose.yaml`（注释）、`icon.cljs`、`en.po` + `zh_CN.po`、`scripts/_helpers.js`、`scripts/watch.js`、`test/frontend_tests/runner.cljs`（:require + test-namespaces 双注册，:155 有 assert 校验一致性）

## 实施顺序（每阶段可独立验证）

1. **通信层 + 单测**：assistant.cljs + 测试 + runner 注册 → `pnpm run test:quiet -- --focus frontend-tests.util-embed-assistant-test`
2. **配置链**：config.cljs / 两份 config.js / entrypoint / compose 注释 → `bash -n nginx-entrypoint.sh`；本地跑 entrypoint 片段用毒值（`"; alert(1)` 等）验证正则拒绝
3. **UI**：icon+sprite 重编 → layout.cljs（先改否则点 tab 即抛 assert）→ options.cljs 接线 → assistant 组件 → po 文件 → 非嵌入环境确认 tab 不显示、无编译告警
4. **mock SDK + 构建拷贝** → `pnpm build:app:assets` 后 curl `/assistant/mock-assistant-sdk.js` 200
5. **联调 + 回归**：单测全量、lint/fmt（`./scripts/lint`、`./scripts/check-fmt`）、真机 iframe 走查

## 验证

**单测**（`util_embed_assistant_test.cljs`，注入式，`t/async` + 小毫秒参数，照抄 `util_embed_test.cljs` 风格）：
- `valid-init-params?`：齐全通过；逐字段缺失/空串/非字符串拒绝
- `parse-incoming`：合法解析；缺 scope 容忍；错 type/eventName/reqId → nil；非 object 拒绝
- `handshake!`：ack resolve `:ready`；超时 `:timeout`；origin 拒绝时消息被忽略（假 post 计数证明持续重发）
- `request!`：reqId 匹配 resolve；`ok:false` 错误返回；超时前按间隔重发（断言 post 次数 ≥2）
- `post-event!`：消息形状（type/eventName/payload/targetOrigin，未配置 origin 为 `"*"`）
- `close!`：监听移除、幂等

**手动联调**（devenv 或本地 watch）：
1. `frontend/resources/config.js` 置 `penpotXFrontSdk = "/assistant/mock-assistant-sdk.js"`（watch 自动重编 config，**需硬刷新**——gz/浏览器缓存）
2. 打开 `http://localhost:8888/assistant/dev-host.html`（或 devenv 对应路径），iframe 内进 workspace
3. 验收点：tab 出现（图标+AI助手，Design|AI助手|Inspect 顺序）；点击 → mock 占位 UI 渲染、父页面收到 `initialized`；mock 按钮触发后父页面收到三个回调事件；切到 Design → `window.X_Assistant` 被清、script 节点移除；切回 → 全流程重走；父页面不回 getInitParams → 10s 后错误占位+重试生效；缺 skillName 毒参数 → `:invalid-params` 占位
4. docker 联调：`PENPOT_X_FRONT_SDK=/assistant/mock-assistant-sdk.js` + `PENPOT_EMBED_PARENT_ORIGIN=<宿主origin>` 起 compose 验证（frame-ancestors 由 entrypoint 自动切换）

**e2e**（可选，默认不做——CLAUDE.md 约定仅显式要求时跑）：仿 `embed-credentials.spec.js` 父页面模式 + `mockConfigFlags` extras。

## 风险与注意

1. `dm/assert!` 生产也抛：layout.cljs 必须先于 options.cljs 改（阶段 3 内有顺序依赖）
2. 新增 svg 不触发 sprite 重编（watch 只监听既有文件 change）；新增 scss 首次同理 → 兜底 `pnpm build:app:assets`
3. config.js/翻译/图标均有缓存：改动后硬刷新（Cmd+Shift+R），「配置不生效」先查缓存
4. Rumext `mf/spread-props` 必须 let 绑定后进 hiccup，不得内联展开（仓库有整页崩溃先例）
5. `app.util.embed.assistant` 零顶层副作用；仅被 main UI 与测试 require，不进 `:shared` chunk
6. targetOrigin 缺省通配的上行泄露面（timestamp/会话数据）——生产必须配 `PENPOT_EMBED_PARENT_ORIGIN`（一次性 warn 提示，行为与 embed.cljs 一致）
7. SDK 键名：`beforeSendMesssage`/`beforeSendMessage` 双键同函数兜底；我方协议 type 用修正拼写 `AI_ASSISTANT_EVENT`；评分回调 eventName 用 `onSatisfactionRatingComplete`
8. script onload 后 `X_Assistant` 可能仍非 object（CSP 拦截/错误脚本）→ fn? 逐方法判空走 `:sdk-global`；destroy 包 try/catch 不阻断卸载清理
9. 角色变更/退出只读强制切 mode 会顺带销毁助手会话（预期行为，知会测试即可）
10. 两份 config.js 手工逐块同步，勿整文件覆盖
11. `reqId` 必填回显需在交付给宿主的协议文档中明确（本文档「通信协议规格」节即可作为交付物）

## 宿主对接要点（交付给宿主团队的最小摘要）

父页面需实现：① iframe 加载 Penpot；② 监听 message，过滤 `type==="AI_ASSISTANT_EVENT"`（建议同时校验 `scope==="penpot/assistant"`）；③ 收到 `eventName==="ready"` → 向 iframe.contentWindow 回 `{scope:"penpot/assistant", type:"AI_ASSISTANT_RESPONSE", eventName:"ready", payload:{ok:true, timestamp:...}}`；④ 收到 `getInitParams` → 回显 `reqId` 并返回 `params:{idToken, appName, agentId, skillName}`（可选 `logoUrl`），或 `{ok:false, reqId, error}`；⑤ Penpot 侧会在就绪后上行 `initialized/afterSendMessage/onMessageFinish/onSatisfactionRatingComplete` 事件（含 timestamp）。生产环境 Penpot 侧需配 `PENPOT_EMBED_PARENT_ORIGIN`（父页面 origin）以启用精确 origin 校验。

# Assistant init params 全量透传 — 设计

- 日期：2026-11-16
- 状态：已批准（方案 A）
- 关联：`docs/superpowers/plans/2026-11-09-ai-assistant-tab-integration.md`（初始实施记录，历史文档不改）、`docs/UI/agent-intergration.md`（宿主对接文档）

## 背景与问题

AI 助手 tab 的 iframe postMessage 通信链路：`ready` 握手 → `getInitParams` 请求/应答 →
动态加载宿主 SDK → `X_Assistant.init(config)` → `render`。

现状 `frontend/src/app/main/ui/workspace/sidebar/assistant.cljs` 的 `init-and-render!`
用**白名单摘键**构造 init config：只从应答 `params` 里取
`appName`、`agentId`、`idToken→getIdToken`、`skillName→defaultSkillName`、`logoUrl→appImage`
五个键，父页面返回的其他键全部被静默丢弃。宿主侧后续为 SDK 增加新配置项时，Penpot
侧必须逐键跟进才能生效。

需求：`getInitParams` 返回的参数**全部透传**给下游的 `X_Assistant.init`。

## 设计

### 合并语义（已批准方案 A）

```
config = obj/merge params 本地-wiring
```

`obj/merge` 即 `Object.assign #js {} a b`：产出新对象，后者覆盖前者。

- **params 为 base 全量透传**：任意未知键（`theme`、`locale`、未来的新配置项）原样到达 init。
- **本地 wiring 覆盖在上**：安全性论据 —— postMessage 结构化克隆**无法传递函数**，父页面
  永远不可能提供回调钩子，因此本地覆盖不破坏任何透传能力；反向合并（params 覆盖本地）
  则会让宿主数据砸掉 `layoutType`/事件接线，助手功能直接失效。

### 本地 wiring（固定覆盖键）

| 键 | 值 | 说明 |
|---|---|---|
| `getIdToken` | `(fn [] (p/resolved (obj/get params "idToken")))` | 闭包包装，函数无法经 postMessage 传入 |
| `defaultSkillName` | `params.skillName` | 参数名 → SDK 配置名派生 |
| `layoutType` | `"domMount"` | 渲染进 Penpot 自己的挂载 div，宿主不可改 |
| `beforeSendMesssage` / `beforeSendMessage` | 同一个 `enrich-message` | SDK 文档两种拼写都设（沿用现状） |
| `afterSendMessage` / `onMessageFinish` | `(forward! …)` | 事件上行转发 |
| `satisfactionRating` | `{enabled true, onComplete (forward! …)}` | 沿用现状 |

- **删除硬编码 `"prompts" nil`**：父页面的 `prompts` 得以透传；不传时键缺失，对 SDK 等效。
- **`logoUrl → appImage` 派生保留**：有 `logoUrl` 则设置
  `appImage {src, className "ai-penpot-icon", hideClose true}`。父页面直传 `appImage`
  且无 `logoUrl` 时原样透传；两者同传时派生键赢（edge case，接受）。
- `idToken` / `skillName` / `logoUrl` 原键随 base 保留在 config 中（透传语义），SDK 忽略不认识的键。

### 校验不变

`valid-init-params?` 仍是进入 SDK 加载前的门槛：`idToken`/`appName`/`agentId`/`skillName`
四个必填键非空字符串，`logoUrl` 可选。透传不放松校验。

### 代码落点

| 文件 | 改动 |
|---|---|
| `frontend/src/app/util/embed/assistant.cljs` | 新增公开纯函数 `build-init-config [params {:keys [enrich-message forward!]}]` → `^js config`，与 `valid-init-params?` 同层；命名空间仍无顶层副作用 |
| `frontend/src/app/main/ui/workspace/sidebar/assistant.cljs` | `init-and-render!` 改为调用 `assistant/build-init-config`，传入会话闭包，不再自行构造 config |

conn 层协议（`parse-incoming` / `request!` / 应答 `params` 提取）**零改动**。

## 测试

`frontend/test/frontend_tests/util_embed_assistant_test.cljs` 新增 `build-init-config` 测试
（RED→GREEN）：

1. 未知额外键（如 `theme`）存活透传
2. `appName`/`agentId` 同名键透传
3. `getIdToken` 闭包调用返回 resolved 的 `idToken` 值；`defaultSkillName ← skillName`
4. 两个 before-send 拼写指向同一个函数；回调键均为函数
5. 有 `logoUrl` → `appImage.src` 派生；无 `logoUrl` → 不设 `appImage` 键
6. 传入的 `params` 对象不被 mutate

手动验证（用户自行执行）：dev-host + mock SDK 走查，`mockParams()` 加示例额外键后确认
`window.__mockAssistantConfig` 里可见。

## 周边改动

- `frontend/resources/assistant/dev-host.html`：`mockParams()` 加 `theme: "dark"` 示例额外键。
- `docs/UI/agent-intergration.md`：getInitParams 契约段补充「`params` 中的额外键将全量透传给
  `X_Assistant.init`」。
- `mock-assistant-sdk.js` 不改（config 整体存 `window.__mockAssistantConfig`，可直接查看）。

## 风险与边界

- 父页面数据中若含与本地 wiring 同名的数据键（如字符串 `afterSendMessage`）会被本地函数
  覆盖 —— 预期行为，函数配置本就不该经 postMessage 传输。
- `params` 是 postMessage 反序列化出的新对象，`obj/merge` 再拷贝一次，收到与构造两侧均无
  mutate。

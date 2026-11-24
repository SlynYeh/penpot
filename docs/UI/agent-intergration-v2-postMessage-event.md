# assistant AI助手事件通信类型扩展

## 切换专家事件

```typescript
window.X_Assistant.on('ai:connectX', (data) => {
  const { success } = data;
  if (success) {
    console.log('链接成功')
  } else {
    console.log('链接失败')
  }
})
```

## 助手鉴权错误事件

```typescript
window.X_Assistant.on('ai:tokenError', () => {
  console.log('token校验失效')
})
```

## 会话切换事件

```typescript
window.X_Assistant.on('ai:changeSession', (data) => {
  const { oldSessionId, sessionId } = data;
  console.log('旧sessionId', oldSessionId);
  console.log('新sessionId', sessionId);
})
```

## 新建对话事件

```typescript
window.X_Assistant.on("ai:createNewSession", (data) => {
  console.log(data.sessionId);
});
```

> Penpot 侧在 assistant tab 每次激活后订阅 `ai:createNewSession`，把 `sessionId`
> 存入 localStorage（key: `penpot-assistant-session:<profileId>:<fileId>`），用于下次
> 进入 tab 时通过 `changeSessionId` 恢复会话；tab 卸载时 `off` 退订。

---

## iframe ↔ 宿主页面 postMessage 通信

assistant tab 与嵌入宿主页面之间有一条专用 postMessage 通道（与 embed 凭据握手
`scope: "penpot/embed"` 相互独立），实现在
`frontend/src/app/util/embed/assistant.cljs`（协议层）+
`frontend/src/app/main/ui/workspace/sidebar/assistant.cljs`（触发层）。
通道为**纯上行单向事件流**：没有握手、没有请求/应答、没有入站监听。

### 消息信封

```typescript
// 子 → 父（iframe → 宿主）。通道为纯上行：无应答、无入站监听、无 reqId
{
  scope: "penpot/assistant",           // 固定值
  type: "AI_ASSISTANT_EVENT",          // 固定值
  eventName: string,                   // 见下方事件表
  payload: object | null
}
```

安全规则：

- 出站 `targetOrigin` = `PENPOT_EMBED_PARENT_ORIGIN` 配置值（与凭据握手共用）；
  未配置时降级 `"*"` 并一次性 warn，生产环境必须配置。
- 通道无入站监听，不存在入站校验。
- 宿主收到事件建议同时校验 `scope === "penpot/assistant"` 与 `type`，且
  `event.source` 是自己持有的 iframe `contentWindow`。

### 初始化参数来源（`getInitParams` 已移除）

SDK `init` 所需的参数不再经 postMessage 获取：

| 参数 | 来源 |
|---|---|
| `appName` | docker env `PENPOT_X_FRONT_APP_NAME` → js/config.js `penpotXFrontAppName` |
| `agentId` | `PENPOT_X_FRONT_AGENT_ID` → `penpotXFrontAgentId` |
| `skillName` | `PENPOT_X_FRONT_SKILL_NAME` → `penpotXFrontSkillName`（同时作为 `defaultSkillName`） |
| `xPlatformEnv`（可选） | `PENPOT_X_FRONT_PLATFORM_ENV` → `penpotXFrontPlatformEnv`；未配置或为空白时**不传该键**，SDK 侧读到 `undefined`，不影响助手 tab 启动 |
| `idToken` | 宿主在与 Penpot iframe **同源**的 sessionStorage 写入 `EBEE_USER_KEY`，值为 JSON `{ idToken: "xxx" }` |

约束：

- sessionStorage 按（scheme, host, port, 标签页）分区，与路径无关。宿主页面与 Penpot
  iframe 必须同源同标签页（本 fork 的路径前缀同源部署满足）；跨端口即跨源，无法共享，
  且**没有** postMessage / URL 参数之类的 fallback，助手 tab 会稳定进入错误态。
- 三个 env 任一缺失：助手 tab **立即**进入错误态（`error.params` 文案），iframe 日志以
  `:missing-config` 列出缺失项。
- `EBEE_USER_KEY` 缺失或其中没有非空 `idToken`：每 500ms 检查一次、共 10 次
  （首次立即，9 个间隔，约 4.5s），耗尽进入错误态（同一文案），日志为
  `:token-missing`；可点重试。
- SDK 的 `getIdToken()` 每次调用都重新读取 `EBEE_USER_KEY`：宿主刷新 token 后**必须
  回写**该 key，否则 SDK 永远拿到旧值（Penpot 不会主动向宿主索取）。
- 宿主无法再透传任何其它 SDK 配置键（如 `prompts`、`theme`、`appImage`）：日后新增
  配置项走「Penpot 侧新增 env 变量并发版」，不存在通用 JSON 通道。
- 发布次序：① 宿主先按新契约写 `EBEE_USER_KEY`（旧 `ready` / `getInitParams` handler
  可先留着，无害）→ ② 升级 Penpot → ③ 观察期后宿主再删旧 handler。Penpot 先上而宿主
  未写 key 时助手 tab 必然进入错误态。

### 子 → 父事件表

| eventName | reqId | payload | 触发时机 |
|---|---|---|---|
| `initialized` | 无 | `{timestamp}` | 本地启动链全部完成才发：config 校验 + `EBEE_USER_KEY` 轮询取到 idToken → 注入 SDK script → `sdk.init` → `sdk.render` →（localStorage 有存档时）恢复会话。会话恢复失败走兜底仍会发 |
| `afterSendMessage` | 无 | `{timestamp, ...ISessionData}` | SDK 聊天**发送消息完成**回调透传 |
| `onMessageFinish` | 无 | `{timestamp, ...}` | SDK **单条消息生成结束**回调透传 |
| `onSatisfactionRatingComplete` | 无 | `{timestamp, ...result}` | 用户**完成满意度评分**回调透传（评分组件常开） |
| `ai:connectX` | 无 | `{timestamp, ...data}` | SDK 连接结果回调透传（`data.success` 原样透传） |
| `ai:tokenError` | 无 | `{timestamp}` | SDK token 校验失效回调透传 |
| `ai:changeSession` | 无 | `{timestamp, oldSessionId, sessionId}` | SDK 会话切换回调透传 |
| `ai:createNewSession` | 无 | `{timestamp, ...data}` | SDK 新建会话回调透传（iframe 内部同时持久化该会话） |

所有事件均为一次性转发（无重发无超时）。

四个 `ai:*` 事件是 SDK 事件（`window.X_Assistant.on` 订阅，见文档开头），在
assistant tab 就绪后订阅转发——订阅是上行 `initialized` 之前的最后一步
（SDK init → render → 会话恢复全部完成后）：一次性转发（无重发无超时），
payload 统一 `{timestamp, ...sdkData}`。在此之前触发的 SDK 事件都会漏发，
包括会话恢复调用本身引发的 `ai:changeSession`；宿主收到 `initialized` 后，
后续所有 SDK 事件都会转发，可把它当作监听同步点。init 阶段没有鉴权失败的
专用上行信号（`getInitParams` 已移除）：`ai:tokenError` 只有在订阅之后触发
才会转发，取不到 token 的情况只体现在 iframe 日志的 `:token-missing`。

### 时序

```
tab 挂载 ─→ config 校验(appName/agentId/skillName, 缺失立即错误态)
  └→ EBEE_USER_KEY 轮询(首次立即, 至多 10 次检查/9 个间隔/约 4.5s)
       └→ 注入SDK script → init → render → [恢复上次会话]
            └→ initialized 上行        ← 此刻 assistant 才算"就绪"
会话期间: afterSendMessage / onMessageFinish / onSatisfactionRatingComplete /
          ai:* 事件按需上行
tab 卸载/重试: 不发送任何告别消息，连接静默关闭
```

参数轮询：500ms 间隔、10 次检查（首次立即，共 9 个间隔，约 4.5s）；所有事件
一次性转发，无重发无超时。

### 宿主实现要点

1. 监听 `message`，按 `type === "AI_ASSISTANT_EVENT"`（建议加 `scope` 与
   `event.source` 校验）识别 assistant 事件——**必须在用户打开助手 tab 之前装好
   监听**（事件一次性转发、无缓冲、无握手探测）。
2. 在与 Penpot iframe 同源的 sessionStorage 写入
   `EBEE_USER_KEY = JSON.stringify({ idToken: "…" })`；刷新 token 时必须回写，
   否则 SDK 的 `getIdToken()` 永远读到旧值。
3. 之后按需消费 `initialized` / `afterSendMessage` / `onMessageFinish` /
   `onSatisfactionRatingComplete` / `ai:*`。

### 边界注意

- **无关闭通知**：tab 卸载、用户重试、SDK 初始化失败均不向父发事件，父只能靠静默
  感知。
- **错误不上行**：`missing-config / token-missing / sdk-load / sdk-global / sdk-init`
  五种失败只进 iframe 内部 UI 错误态，通道上不会出现错误事件（前两种共用
  `error.params` 文案，靠 iframe 日志区分）。
- **不再探测宿主可达性**：`PENPOT_EMBED_PARENT_ORIGIN` 配错、宿主未装监听时，界面
  照常就绪，宿主全程收不到任何事件（原先由 15s 握手超时暴露，现在静默）。
- **`initialized` 一次性、无缓冲**：postMessage 不排队（原 `ready` 的 2s 重发正是
  为此）。宿主必须在用户打开助手 tab 之前完成监听。

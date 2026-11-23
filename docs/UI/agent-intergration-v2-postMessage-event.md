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

### 消息信封

```typescript
// 子 → 父（iframe → 宿主）
{
  scope: "penpot/assistant",           // 固定值
  type: "AI_ASSISTANT_EVENT",          // 出站固定
  eventName: string,                   // 见下方事件表
  payload: object | null,
  reqId?: string                       // 仅 getInitParams 携带，
                                       // 形如 "penpot-assistant-<n>-<ts>-<rand>"
}

// 父 → 子（宿主 → iframe，只允许 RESPONSE 类型）
{
  scope: "penpot/assistant",
  type: "AI_ASSISTANT_RESPONSE",
  eventName: string,
  payload: object                      // 一般含 ok: boolean
}
```

安全规则：

- 出站 `targetOrigin` = `PENPOT_EMBED_PARENT_ORIGIN` 配置值（与凭据握手共用）；
  未配置时降级 `"*"` 并一次性 warn，生产环境必须配置。
- 入站校验 `event.source === iframe` 的 `window.parent`，且
  `event.origin` 匹配配置的父 origin；未配置时通配放行并一次性 warn。
- 宿主收到事件建议同时校验 `scope === "penpot/assistant"` 与 `type`。

### 子 → 父事件表

| eventName | reqId | payload | 触发时机 |
|---|---|---|---|
| `ready` | 无 | `{timestamp}` | assistant tab **挂载即发**；父不 ack 则**每 2s 重发**直至 ack；15s 未 ack 放弃（UI 报握手超时，可重试） |
| `getInitParams` | **有** | `{timestamp}` | 收到父的 `ready` ack 后**立即发起**；同一 reqId 每 2s 重发（父只需应答一次）；10s 超时 |
| `initialized` | 无 | `{timestamp}` | 本地启动链全部完成才发：握手 → 拿到合法 init params → 注入 SDK script → `sdk.init` → `sdk.render` →（localStorage 有存档时）恢复会话。会话恢复失败走兜底仍会发 |
| `afterSendMessage` | 无 | `{timestamp, ...ISessionData}` | SDK 聊天**发送消息完成**回调透传 |
| `onMessageFinish` | 无 | `{timestamp, ...}` | SDK **单条消息生成结束**回调透传 |
| `onSatisfactionRatingComplete` | 无 | `{timestamp, ...result}` | 用户**完成满意度评分**回调透传（评分组件常开） |
| `ai:connectX` | 无 | `{timestamp, ...data}` | SDK 连接结果回调透传（`data.success` 原样透传） |
| `ai:tokenError` | 无 | `{timestamp}` | SDK token 校验失效回调透传 |
| `ai:changeSession` | 无 | `{timestamp, oldSessionId, sessionId}` | SDK 会话切换回调透传 |
| `ai:createNewSession` | 无 | `{timestamp, ...data}` | SDK 新建会话回调透传（iframe 内部同时持久化该会话） |

除 `ready`/`getInitParams` 外，其余事件均为一次性转发（无重发无超时）。

四个 `ai:*` 事件是 SDK 事件（`window.X_Assistant.on` 订阅，见文档开头），在
assistant tab 就绪后订阅转发——订阅是上行 `initialized` 之前的最后一步
（SDK init → render → 会话恢复全部完成后）：一次性转发（无重发无超时），
payload 统一 `{timestamp, ...sdkData}`。在此之前触发的 SDK 事件都会漏发，
包括会话恢复调用本身引发的 `ai:changeSession`；宿主收到 `initialized` 后，
后续所有 SDK 事件都会转发，可把它当作监听同步点。`getInitParams` 失败
路径可作为 init 阶段鉴权问题（token 失效）的兜底信号。

### 父 → 子应答表

| eventName | payload | 说明 |
|---|---|---|
| `ready` | `{ok: true, timestamp}` | 握手 ack；必须回发到 iframe 的 `contentWindow` |
| `getInitParams` | 成功：`{ok: true, reqId, params: {idToken, appName, agentId, skillName, logoUrl?}}`；失败：`{ok: false, reqId, error}` | 必须回显 `reqId`。四必填字段均为非空字符串；`logoUrl` 可选（有则作为 SDK init 的 `appImage.src`） |

### 时序

```
tab 挂载 ─→ ready ──(2s重发,≤15s)──→ 父 ack
  └→ getInitParams ──(2s重发同reqId,≤10s)──→ 父回 params
       └→ 注入SDK script → init → render → [恢复上次会话]
            └→ initialized 上行        ← 此刻 assistant 才算"就绪"
会话期间: afterSendMessage / onMessageFinish / onSatisfactionRatingComplete 按需上行
tab 卸载/重试: 不发送任何告别消息，连接静默关闭
```

超时参数：重发间隔 2s、握手超时 15s、请求超时 10s。

### 宿主实现要点

1. 监听 `message`，按 `type === "AI_ASSISTANT_EVENT"`（建议加 `scope` 校验）识别 assistant 事件。
2. 收到 `ready` → 向 `iframe.contentWindow` 回 `{scope, type: "AI_ASSISTANT_RESPONSE", eventName: "ready", payload: {ok: true}}`。
3. 收到 `getInitParams` → 回显 `reqId` 并返回四必填 init params。
4. 之后按需消费 `initialized` / `afterSendMessage` / `onMessageFinish` / `onSatisfactionRatingComplete`。

### 两个边界注意

- **无关闭通知**：tab 卸载、用户重试、SDK 初始化失败均不向父发事件，父只能靠静默/超时感知。
- **错误不上行**：`handshake-timeout / params-timeout / invalid-params / sdk-load / sdk-global / sdk-init` 六种失败只进 iframe 内部 UI 错误态，通道上不会出现错误事件。

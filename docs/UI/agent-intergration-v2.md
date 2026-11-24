# 智能助理接入需求 v2 —— 新对话 / 历史对话

> 状态：已完成需求澄清（2026-11-19），所有决策均经用户确认，实施时勿再重新讨论。
> 基线：v1 已实现（右侧栏 AI助手 tab + X_Assistant SDK 动态加载，方案见
> `docs/superpowers/plans/2026-11-09-ai-assistant-tab-integration.md`）。
> 本期在其上增加「新对话」「历史对话」能力。**v1 决策 6「切走 tab 即销毁、切回=全新会话」中的
> 「切回=全新会话」被本期部分推翻**（见「会话记忆」节）。

## 需求概述

基于当前「AI助手」功能增加「新对话」「历史对话」：

- 会话视图顶部新增「新对话」「历史对话」两个入口按钮；
- 「历史对话」打开历史会话列表视图，可切换、可删除历史会话；
- tab 切走再切回（含刷新页面后）恢复用户上一次所在的会话。

## 参考图（引用已修正）

| 图片 | 内容 | 用途 |
|---|---|---|
| `docs/UI/IMG_4245.JPG` | 会话视图：tab 栏下方左对齐「新对话（气泡图标）」「历史对话（时钟图标）」按钮行 + 聊天内容区 | **按钮样式/位置参考** |
| `docs/UI/IMG_4244.JPG` | 历史视图：「← 历史对话」返回行 + 会话卡片列表 | **历史列表布局参考** |

原始文档中两张图的引用写反，已确认修正。图片中与 `SessionListRes` 数据模型无法对应的内容
（如卡片下方的两行摘要文字）**不实现**。

## 已确认决策（2026-11-19）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 参考图引用 | 写反，修正为 4245=按钮参考、4244=列表参考 |
| 2 | 列表摘要文字 | 不显示（API 无对应字段） |
| 3 | 删除当前会话 | `changeSessionId('')` 切到新会话（与官方示例一致） |
| 4 | 切回 tab 语义 | **恢复上次会话**（推翻 v1「每次新会话」） |
| 5 | 删除确认 | 不弹确认框，直接删除 |
| 6 | 会话记忆存储 | localStorage（跨 tab 切换、跨页面刷新） |
| 7 | init 参数 | 维持 v1 现状不动 |
| 8 | 时间格式 | `YYYY-MM-DD HH:mm`，固定格式不随界面语言变化 |
| 9 | 其余默认 | 空标题占位「新对话」；历史视图整行替换按钮行；每次进入历史视图重新拉取；列表三态 UI；status/pinned/projectName 不展示；恢复失效兜底新会话 |

## 功能交互

### 视图结构

tab-content 内两个互斥视图，组件本地 state 切换：

**会话视图（默认）**

- 顶部按钮行：位于 tab 栏之下、SDK 挂载区之上，左对齐横排两个「图标+文字」扁平按钮
  （无背景、无边框）：「新对话」（对话气泡图标）、「历史对话」（时钟历史图标）。
- 其下为 SDK 挂载区（现状不变）。

**历史视图**

- 顶部返回行：**整行替换**按钮行（非叠加），「← 历史对话」；返回图标与标题点击行为一致，
  均返回会话视图。
- 其下为历史会话列表。

### 新对话

点击「新对话」：

1. `getTools().changeSessionId('')` 创建新会话；会话内容区由 SDK 自动清空并渲染新会话。
2. 清除会话记忆（savedSessionId）。用户在新会话中发出首条消息后，SDK 派发
   `ai:createNewSession` 事件，届时再写入新 sessionId。

### 历史对话（列表视图）

- 每次进入历史视图都重新调用 `getTools().jsSdk.getSessionList()` 拉取列表，不使用缓存。
- 列表三种状态均有对应 UI：
  - 加载中：spinner 占位；
  - 加载失败：错误文案「历史对话加载失败」+ 重试按钮（复用现有 `labels.retry` 文案）；
  - 空列表：空态文案「暂无历史对话」。
- 列表项布局：左右分布。左侧 = 会话标题（`title` 为空时显示占位「新对话」）；
  右侧 = `updatedAt`，格式 `YYYY-MM-DD HH:mm`。
- 不展示字段：`status`、`pinned`、`projectName` 及其余字段。
- 列表顺序：按 `getSessionList` 返回顺序展示，前端不重排。
- hover 列表项：右侧时间位置让位显示删除图标按钮（见「删除会话」）。

### 切换会话

点击历史列表项：

1. 返回会话视图；
2. `changeSessionId(session.uid)` 切换至对应会话，会话内容区由 SDK 自动渲染；
3. 写入会话记忆（savedSessionId = session.uid）。

### 删除会话

hover 列表项右侧显示删除图标按钮，点击：

1. **直接删除**（无确认弹窗）：`await jsSdk.deleteSession(sessionId)`；
2. 重新 `getSessionList()` 更新列表；
3. 若删除的正是当前会话（sessionId === savedSessionId）：额外 `changeSessionId('')`
   切到新会话，并清除会话记忆。

### 会话记忆（tab 生命周期变更）

**存储**：`localStorage`，key = `penpot-assistant-session:{profile-id}:{file-id}`
（profile 维度防跨账号串会话，file 维度防跨文件串会话）。

| 时机 | 动作 |
|---|---|
| 收到 `ai:createNewSession` 事件（`data.sessionId`） | 写入 |
| 点击历史列表项切换会话 | 写入 |
| 点击「新对话」 | 清除 |
| 删除的正是当前会话 | 清除 |

**恢复流程**：tab 激活走初始化全流程（config 校验 + `EBEE_USER_KEY` 轮询取 idToken → 加载 SDK → init → render，无握手，见
[agent-intergration-v2-postMessage-event.md](./agent-intergration-v2-postMessage-event.md)）；
**render 成功后**若存在 savedSessionId，调用 `changeSessionId(savedSessionId)` 恢复；
不存在则维持 SDK 默认新会话。恢复切换瞬间 SDK 可能先短暂渲染默认会话再切换，属已知可接受行为。

**失效兜底**：恢复调用抛异常或返回拒绝 → 清除存储 + `changeSessionId('')` 回新会话。
若真实 SDK 对无效 id 静默不报错则无法自动检测，联调时需与宿主确认 `changeSessionId`
对无效 id 的行为。

**不变部分**：切走 tab 仍完整销毁 SDK（destroy + 摘 script + 清全局 + 关连接）；
本变更只增加「切回时恢复上次会话」。

## js-sdk 接口文档（宿主提供）

```typescript
// 会话列表响应体
type SessionListRes = {
  total: string; // '5'（字符串数字，仅内部使用，不展示）
  sessions: Array<{
    createdAt: string; // 时间戳
    domain: "design";
    pinned: boolean;
    projectName: string;
    projectUid: string;
    sapUserId: string;
    status: "SESSION_STATUS_STOPPED" | "SESSION_STATUS_RUNNING"; // 会话状态（本期不展示）
    title: string; // 会话标题
    uid: string; // 会话ID
    updatedAt: string; // 时间戳（单位秒/毫秒以宿主实际返回为准，格式化前先验证；展示为 YYYY-MM-DD HH:mm）
    userId: string;
  }>
}

// 初始化助手，调用 getTools 之前必须 init（init 参数维持 v1 现状，此处仅为 SDK 文档示意）
window.X_Assistant?.init?.({
  appName: "你的应用ID",
  agentId: "你的专家ID",
  getIdToken: () => { return "用户的idToken" }, // 提供一个获取当前用户一事通token的方法 必需
  layoutType: "domMount", // dom
});

// 手动执行挂载
window.X_Assistant?.render("mountId"); // 保证有挂载点 <div id="mountId" />

// 获取 sdk 实例
const { jsSdk } = window.X_Assistant?.getTools();

// 获取session任务列表
const res: SessionListRes = await jsSdk?.getSessionList?.();
const list = res?.sessions || [];

// 删除session任务，删除任务后，建议重新获取列表
await jsSdk?.deleteSession?.(sessionId);

// 获取 切换/新建会话 方法
const { changeSessionId } = window.X_Assistant?.getTools();
// 切换会话
changeSessionId(sessionId);
// 新建对话
changeSessionId('');

// 监听新建对话事件，获得新对话产生的sessionId
window.X_Assistant.on("ai:createNewSession", (data: any) => {
  console.log(data.sessionId);
});
```

## 重要规则

1. 图片内容区域中与本需求及数据模型无关的内容不要照抄；不确定图片中哪些内容需要实现时，必须先询问。
2. 会话内容区由 SDK 自动渲染，Penpot 侧不介入渲染。
3. 所有会话操作（获取/新建/切换/删除）一律通过 `window.X_Assistant` SDK API 完成，
   Penpot 不直接请求宿主 HTTP 接口。

## 明确不做（防过度实现）

- 列表摘要文字（`SessionListRes` 无对应字段；未来要加需宿主扩展接口）
- 删除二次确认弹窗
- 列表分页 / 虚拟滚动
- 相对时间（「3 小时前」类）
- 多浏览器窗口间的会话记忆同步
- init 参数变更（`xPlatformEnv` 等 v2 示例新参数不引入）

## 实施注意（对既有代码的影响）

1. **mock SDK 需升级**（`frontend/resources/assistant/mock-assistant-sdk.js`）：现状无任何
   会话管理方法，且 `getTools()` 返回形状与 v2 SDK 不同。需对齐：`getTools() → {jsSdk, changeSessionId}`；
   `jsSdk.getSessionList/deleteSession`；会话存储建议放 sessionStorage（tab 切走会销毁 mock
   全局，sessionStorage 可使重进后仍能模拟「恢复会话/历史列表」）；新建会话时派发
   `ai:createNewSession`。
2. **assistant.cljs**（`frontend/src/app/main/ui/workspace/sidebar/assistant.cljs`）：状态机在
   render 成功后增加恢复分支（savedSessionId 存在则 `changeSessionId` 恢复，失败兜底新会话）；
   新增视图切换 state、`ai:createNewSession` 监听（`X_Assistant.on`）、localStorage 读写、
   删除当前会话判断。
3. **profile-id 来源**：组件现只收 `file-id/page-id` props，需另取当前 profile id（refs）拼
   localStorage key。
4. **i18n**：新增按钮/返回行标题/空态/加载失败文案 key（仅改 en.po + zh_CN.po）；
   时间格式固定 `YYYY-MM-DD HH:mm`，不随语言本地化。
5. **图标**：「新对话/历史对话/返回/删除」优先复用现有 sprite 图标；若新增 svg，注意 watch
   不重编 sprite 的盲区（需手动 `pnpm build:app:assets`）。
6. **v1 计划文档**：其决策 6 的「切回=全新会话」被本需求部分推翻，以本文档为准。

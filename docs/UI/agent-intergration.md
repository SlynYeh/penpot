# 智能助理接入需求

## 背景

right-sidebar 的 main_ui_ds_layout_tab_switcher__tab-nav 菜单增加 AI助手 Tab项及相关功能

## 细节描述

### UI

- tab-nav-item
  名称：AI助手
  组成：图标 + 文字(AI助手)
- AI助手 功能通过docker-compose文件 + config配置参数控制显隐、开关，用一个 X_FRONT_SDK 环境变量控制
- Ai助手 默认撑满 tab-content 的可用区域

### 功能交互

- 点击 AI助手 tab切换到对应tab-content
- AI助手 tab-content 组件初始化时，初始化 js-sdk，初始化参数通过postMessage向父页面获取


## js-sdk 接口文档

### 初始化方式

```typescript
interface Window {
  X_Assistant?: {
    init?: (config: AIAssistantOption) => void;
    getTools?: () => any;
    on?: (eventName: string, callback: (event: any) => void) => void;
    off?: (eventName: string) => void;
    destroy?: () => void;
  }
}

useEffect(() => {
  if (idToken) {
    window.X_Assistant?.init?.({
      appName: appName,
      agentId: agentId,
      getIdToken; () => idToken,
      defaultSkillName: skillName,
      prompts: null,
      layoutType: 'domMount',
      appImage: { src: aiLogo, className: 'ai-penpot-icon', hideClose: true },
      beforeSendMesssage: (data: SafAny) => {
        const query = getQueryFromUrl();
        return {
          ...data,
          envs: {
            'PAGE_ID': query['page-id'] ?? '',
            'FILE_ID': query['file-id'] ?? '',
            // Penpot 侧本地接线时额外注入（嵌入凭据握手收到的可选字段，
            // 未提供时对应键不存在）：
            'DOC_CONTENT': '宿主回传的 docContent',
            'DOC_TITLE': '宿主回传的 docTitle',
          }
        }
      },
      afterSendMessage: afterSendMessage,
      onMessageFinish: onMessageFinish,
      satisfactionRating: { enabled: true, onComplete: onSatisfactionRatingComplete }
    });

    window.AI_Assistant?.render('aiAssitantDomId');
  }

  return () => {
    window.AI_Assistant?.destroy();
  }
}, [idToken, appName, idToken, skillName, agentId])
```

> 注：以上是宿主侧最初的接线示意。Penpot 实际传给 `X_Assistant.init` 的 config 以
> [agent-intergration-v2-postMessage-event.md](./agent-intergration-v2-postMessage-event.md)
> 的「初始化参数来源」为准：`appName` / `agentId` / `skillName` / `idToken` 不再由宿主经
> postMessage 提供；`prompts` 改由 Penpot 侧环境变量 `PENPOT_X_FRONT_PROMPTS`（`|` 分隔的字符串）
> 配置，`appImage` 等其余宿主侧示例项 Penpot 不会注入。

### 配置参数

```typescript
// 配置项参数
interface AIAssistantOption {
  appName: string; // 应用唯一标识
  agentId: string; // 专家ID
  getIdToken: () => Promise<string>; // 认证信息
  setHeaders?: (headers: {[key: string: string]}) => {[key: string: string]}; // 设置自定义请求头
  welcomeRender?: string | (() => string); // 欢迎语渲染函数，新对话顶部的欢迎部分
  customMessageHeadRender?: (sessionId: string) => string; // 自定义某条消息顶部固定显示的内容
  customMessageRender?: () => string;
  customMessageTag?: (data: any) => string;
  showWelcomeName?: boolean; //是否显示欢迎语标题，default=true
  name?: string;
  title?: string;
  prompts?: Array<{
    key: string;
    triggerChat?: boolean;
    description: string;
  }>;
  quickOperations?: QuickOperationOption[]; // 输入框顶部的快捷操作
  quickOperationRender?: () => Promise<string>;
  customUpload?: {
    label: string;
    onClick: () => void;
  };
  hideThinking?: boolean; // 是否隐藏深度思考 default=false
  headerHeight?: number;
  defaultWidth?: number;
  layoutType?: "float" | "drawer";
  disableChangeLayout?: boolean;
  userSelection?: boolean | { textHandler?: (text: string) => string };
  appImage?: {
    src: string;
    className?: string;
    hideClose?: boolean;
  };
  defaultSkillName?: string;
  onMessageFinish?: ({ sessionId: string; message: any }) => void;
  beforeSendMessage?: (data: ISessionData, sessionId?: string) => Promise<ISessionData>;
  afterSendMessage?: (data: ISessionData) => void;
  artifactConfig?: {
    view?: boolean;
    download?: boolean;
    allowEdit?: boolean;
    showPreviewInMarkDown?: boolean;
    autoSaveBeforeSendMsg?: boolean;
    openConfig?: {
      autoOpenTargetMsg?: "none" | "lastEnd" | "lastArtifact" | ((messages: DisplayMessage[]) => DisplayMessage | null);
      artifactFindFunc?: (message: DisplayMessage) => Artifact | null;
    }
  };
  pageContext?: {
    enabled?: boolean;
    rootSelector?: string;
    maxLength?: number;
    maxNodes?: number;
  };
  isShowFloatAppInNoAuth?: boolean;
  dialogHeaderRender?: () => string;
}

interface QuickOperationOption {
  type: "Radio" | "Popover" | "Button" | "Switch" | "Select";
  key: string;
  Props: Antd.RadioProps | Antd.PopoverProps | Antd.ButtonProps | Antd.SwitchProps | Antd.SelectProps;
}

type ISessionData = { contents: Array<{ type: 'text', value: string }>, sessionId: string }
| { questionId: string, answers: { [key in string]: string }, sessionId: string }

interface Artifact {
  fileName: string;
  contentType: string;
  path: string;
}

interface BaseDisplayMessage {
  id: string;
  eventId?: string;
  role: 'user' | 'assistant';
  timestamp: number | undefined;
  planContext?: { messageId?: string; planStep?: string };
}

interface UserInputDisplayMessage extends BaseDisplayMessage {
  type: 'useInput';
  content: string;
  skillNames?: string[];
  attachments?: Artifact[];
  feUserInputMessageId?: string;
  sessionParams?: { knowledgeBaseNames?: string[] };
}

interface ChatDisplayMessage extends BaseDisplayMessage {
  type: 'chat';
  content: string;
  fileType?; 'common' | 'webProject';
  attachments?: Artifact[];
}

type DisplayMessage = UserInputDisplayMessage | ChatDisplayMessage;
```

### 事件处理

1. afterSendMessage - 发送消息后
```typescript
const afterSendMessage = (data: ISessionData) => {
  const content = JSON.stringify(data?.contents || '');

  window.parent.postMessage({
    type: 'AI_ASSITANT_EVENT',
    eventName: 'afterSendMessage',
    payload: {
      timestamp: new Date().toString(),
      ...data
    }
  }, PENPOT_EMBED_PARENT_ORIGIN)
}
```

2. onMessageFinish - 消息结束
```typescript
const onMessageFinish = (data: ISessionData) => {
  window.parent.postMessage({
    type: 'AI_ASSITANT_EVENT',
    eventName: 'onMessageFinish',
    payload: {
      timestamp: new Date().toString(),
      ...data
    }
  }, PENPOT_EMBED_PARENT_ORIGIN)
}
```

3. onSatisfactionRatingComplete - 对话评分
```typescript
const onSatisfactionRatingComplete = (result: any) => {
  window.parent.postMessage({
    type: 'AI_ASSITANT_EVENT',
    eventName: 'onMessageFinish',
    payload: {
      timestamp: new Date().toString(),
      ...result
    }
  }, PENPOT_EMBED_PARENT_ORIGIN)
}
```

4. initialized - 初始化完成
```typescript
const initialized = () => {
  window.parent.postMessage({
    type: 'AI_ASSITANT_EVENT',
    eventName: 'initialized',
    payload: {
      timestamp: new Date().toString()
    }
  }, PENPOT_EMBED_PARENT_ORIGIN)
}
```

## 重要规则

- 必须抽象出独立的iframe postMessage通信层
  - 通道为纯上行单向事件流（`initialized` 与 SDK 回调透传），无握手、无请求/应答、无入站监听；宿主必须在用户打开助手 tab 之前装好 message 监听（事件一次性转发、无缓冲）
  - SDK 初始化参数不再经 postMessage 获取：appName/agentId/skillName 由 Penpot 侧环境变量配置（PENPOT_X_FRONT_APP_NAME / PENPOT_X_FRONT_AGENT_ID / PENPOT_X_FRONT_SKILL_NAME）；idToken 由宿主在与 iframe 同源的 sessionStorage 写入 `EBEE_USER_KEY`（JSON：`{ idToken: "xxx" }`），刷新 token 时必须回写；可选的 `xPlatformEnv` 由 PENPOT_X_FRONT_PLATFORM_ENV 配置（未配置则不传该键，SDK 侧读到 undefined）
  - 回调类配置（函数）无法经 postMessage 传输，由 Penpot 侧本地接线；宿主不可透传任何其它 SDK 配置键（如需新增走 Penpot 侧新增环境变量并发版）
  - 主动发起的事件至少包括：initialized、afterSendMessage、onMessageFinish、onSatisfactionRatingComplete、ai:connectX、ai:tokenError、ai:changeSession、ai:createNewSession
  - 详见 [agent-intergration-v2-postMessage-event.md](./agent-intergration-v2-postMessage-event.md)
- aiAssitantDomId 是 sdk 挂载的DOM ID，通常为 div，也就是 AI助手的 tab-content内容区
- AI助手 tab只有在 iframe 嵌入场景才显示
- 切换到 AI助手 tab时，触发初始化，切换到其它tab销毁
- X_Assistant 是 SDK加载后挂载全局的js对象，通过 script 标签动态加载，uri通过 X_FRONT_SDK 变量配置
  - 开发、审查时，你构造一个 sdk 用于调试，真实环境我来替换环境变量


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

4. getInitParams - 获取初始化参数
```typescript
const getInitParams = () => {
  window.parent.postMessage({
    type: 'AI_ASSITANT_EVENT',
    eventName: 'getInitParams',
    payload: {
      timestamp: new Date().toString()
    }
  }, PENPOT_EMBED_PARENT_ORIGIN)
}
```

## 重要规则

- 必须抽象出独立的iframe postMessage通信层
  - 建立简单的通信握手协议，确保父子页面在各自确认就绪，才执行正常的事件通讯
  - 通信建立并就绪后，切换到 AI助手 Tab 时发起 getInitParams 事件，接收父页面响应的sdk初始化必要的参数：idToken、appName、idToken、skillName，需校验参数必填性。params 中的其他键（如 prompts、theme 等）将全量透传给 `X_Assistant.init` 的 config；回调类配置（函数）无法经 postMessage 传输，由 Penpot 侧本地接线
  - 主动发起的事件至少包括：getInitParams、initialized、afterSendMessage、onMessageFinish、onSatisfactionRatingComplete
- aiAssitantDomId 是 sdk 挂载的DOM ID，通常为 div，也就是 AI助手的 tab-content内容区
- AI助手 tab只有在 iframe 嵌入场景才显示
- 切换到 AI助手 tab时，触发初始化，切换到其它tab销毁
- X_Assistant 是 SDK加载后挂载全局的js对象，通过 script 标签动态加载，uri通过 X_FRONT_SDK 变量配置
  - 开发、审查时，你构造一个 sdk 用于调试，真实环境我来替换环境变量


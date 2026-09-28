# Penpot iframe 嵌入对接文档

面向把 Penpot 前端以 iframe 嵌入自有系统的第三方团队，说明**消息通讯协议**（谁在什么时候发什么）和**验证方法**（怎么确认真的生效）。

本文所有代码路径都相对于**仓库根目录**。代码位置：`frontend/src/app/util/embed.cljs`（握手）、`frontend/src/app/main.cljs`（启动时序）、`frontend/src/app/util/http.cljs`（请求头注入）。

---

## 1. 前置条件（必须由 Penpot 部署方完成）

### 1.1 配置宿主的 origin

在 Penpot 前端容器上设置环境变量：

```yaml
PENPOT_EMBED_PARENT_ORIGIN: "https://your-portal.example.com"
```

这一个变量同时决定两件事：

| 作用 | 说明 |
|---|---|
| 消息层 | guest 发 `ready` 时的 `targetOrigin`，以及校验宿主回传消息的合法来源 |
| 渲染层 | nginx 把 `X-Frame-Options: SAMEORIGIN` 换成 `Content-Security-Policy: frame-ancestors 'self' https://your-portal.example.com` |

**不设这个变量，浏览器会直接拒绝渲染，前端代码再正确也没用**，控制台报：

```
Refused to display 'https://penpot.example.com/penpot/' in a frame because it set 'X-Frame-Options' to 'sameorigin'.
```

要求：**裸 origin**（`scheme://host[:port]`，可带一个结尾 `/`）。写错格式不会报错，只会静默退回"通配 + 保持拒框"，所以请用第 4 节的命令自检。

### 1.2 guest 地址必须带 base path

前端构建时把 base path 烧进了 `index.html`（默认 `/penpot/`）：

```html
<script type="module">
  globalThis.penpotBasePath = "/penpot/";
  globalThis.penpotPublicURI = location.origin + "/penpot/";
</script>
```

所以 iframe 的 `src` 必须是 `<域名>/<base-path>/`，且所有后端请求、WebSocket 都从这个值推导。自检：

```bash
curl -s https://penpot.example.com/ | grep penpotBasePath
```

### 1.3 宿主页面自身的前置要求

| 要求 | 说明 |
|---|---|
| CSP 允许框住 Penpot | 若宿主页有 `Content-Security-Policy`，`frame-src`（或 `child-src` / `default-src`）必须包含 Penpot 的 origin |
| 协议一致 | 宿主是 HTTPS 时 Penpot 也必须是 HTTPS，否则被浏览器按混合内容拦截 |

---

## 2. 消息协议

只涉及两条消息，都走 `window.postMessage`。

### 2.1 guest → 宿主：`ready`

guest 在自己**被 iframe 嵌入**时，向 `window.parent` 发一条 `ready`：

```json
{ "scope": "penpot/embed", "type": "ready" }
```

| 项目 | 说明 |
|---|---|
| 发送时机 | guest 页面加载后立即（在发起任何后端请求之前） |
| 发送频率 | 每个页面加载**只发一次**；iframe 重新加载 = 一轮新握手 |
| targetOrigin | 1.1 配置的宿主 origin（未配置时为 `*`，会打告警日志） |
| 顶层打开 | 直接访问 Penpot 网址（非 iframe）时**不发**，也不安装监听器 |

### 2.2 宿主 → guest：凭据

宿主收到 `ready` 后，用 **guest 的 origin** 作为 `targetOrigin` 回传：

```js
frame.contentWindow.postMessage(
  { "X-token": "<token>", "X-ClientId": "<clientId>" },
  penpotOrigin,   // 例如 https://penpot.example.com
);
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `X-token` | string | **键名大小写敏感**，必须逐字一致 |
| `X-ClientId` | string | 同上 |

校验规则（任一条不满足，整条消息被丢弃并忽略）：

- 两个值都必须存在、且是**非空白字符串**（`""`、`"  "` 无效）
- 消息**必须来自 `window.parent`**（兄弟 iframe、其他窗口发的都无效）
- 发送方的 `event.origin` 必须与 1.1 配置的 origin **精确相等**（scheme + host + port 全一致；不做前缀匹配，`https://your-portal.example.com.attacker.io` 会被拒）
- 多余的字段被忽略

生效后的请求头名是 `x-token` / `x-clientid`（HTTP 头本身不区分大小写）。

### 2.3 时序

```
宿主页（第三方系统）                          guest（iframe 内的 Penpot）
      |   <iframe src="https://.../penpot/">          |
      |---------------------------------------------->|  加载 → 安装 message 监听
      |<----------------------------------------------|  {scope:"penpot/embed", type:"ready"}
      |  postMessage(凭据, penpotOrigin)              |
      |---------------------------------------------->|  校验来源 + 字段 → 存下凭据
      |                                               |  开始启动，发首个后端请求
      |                                               |  （已带 x-token / x-clientid）
```

### 2.4 宿主最小实现

```html
<iframe
  id="penpot"
  src="https://penpot.example.com/penpot/"
  allow="clipboard-read; clipboard-write; fullscreen"
  style="width: 100%; height: 100%; border: 0"
></iframe>

<script>
  const frame = document.getElementById("penpot");
  const PENPOT_ORIGIN = new URL(frame.src).origin; // https://penpot.example.com

  window.addEventListener("message", (event) => {
    // 必须校验来源，否则任何被嵌入的页面都能冒充 Penpot 让你下发凭据
    if (event.origin !== PENPOT_ORIGIN) return;
    if (event.source !== frame.contentWindow) return;

    const data = event.data;
    if (!data || data.scope !== "penpot/embed" || data.type !== "ready") return;

    frame.contentWindow.postMessage(
      { "X-token": TOKEN, "X-ClientId": CLIENT_ID },
      PENPOT_ORIGIN,
    );
  });
</script>
```

要点：

- `allow` 里的剪贴板权限建议加上，否则 Penpot 内的复制/粘贴不可用（代码见 `frontend/src/app/util/clipboard.js`）
- **不要**给 iframe 加 `sandbox` 属性；若必须加，至少要给 `allow-scripts allow-same-origin allow-forms allow-popups allow-downloads`——少了 `allow-same-origin` 会得到不透明 origin，本地存储、Worker、WebSocket、下载导出等能力都会失效
- guest 主动发 `ready`，宿主**不要**靠"设完 `src` 就发凭据"来抢时序；也**不要**为了重试而反复重设 `src`（每次都是一轮完整加载）

---

## 3. guest 侧行为

### 3.1 凭据生效范围

**会带**：所有 Penpot 后端调用（`…/api/main/methods/*` 等）。

**不会带**（有意为之，避免把凭据泄漏给外部主机）：

- 拉取外部资源的请求（图片/字体转 data-uri、`fetch-text`）
- 插件 API 的资源请求
- rasterizer 子 iframe 自身发出的请求

**WebSocket**（`…/ws/notifications?session-id=…`）：浏览器不允许给 WebSocket 握手设置自定义请求头，因此它**不带**这两个 header，靠 `session-id` 查询参数 + Cookie 鉴权。**如果宿主环境屏蔽第三方 Cookie，实时协作/通知通道可能连不上，请实测。**

**另外**：本 fork 的 `js/config.js` 会给所有 `fetch`/XHR 请求追加一个 `x-iframe-src: <guest 当前 URL>`（实测：仅出现在这类请求上，静态资源/图片等不经 fetch 的请求没有）。这是 Penpot 自身的行为，与凭据无关，但第三方做安全评审时会在 Network 里看到它——也就是说 guest 会把自身的当前 URL 一起发给后端。

### 3.2 启动时序与超时

guest 会**先等握手，再启动**，这样第一个请求就带凭据。等待上限由 `penpotEmbedTimeoutMs` 决定（`js/config.js`，默认 **5000ms**，没有对应的环境变量）。

超时后的行为：

1. guest 无凭据启动，此阶段所有请求**不带** header
2. 凭据稍后到达 → 存下凭据，并**补一次 `get-profile`**（日志：`embed: credentials arrived late, refreshing profile`），此后请求开始带 header

结论：不要依赖"迟到也能救回来"，宿主应在收到 `ready` 后**立即**回传。

### 3.3 凭据更新

再发一条消息即可覆盖，**后续**请求使用新值；已经加载的 profile 不会自动重拉（要立即生效可让宿主控制 iframe 重新加载）。不要发空值或残缺字段——会被直接丢弃，等于没更新。

---

## 4. 验证方法

### 4.1 第 0 层：能否被嵌入（部署层）

```bash
curl -sI https://penpot.example.com/penpot/ | grep -iE "x-frame-options|content-security-policy"
```

| 期望 | 含义 |
|---|---|
| 无 `X-Frame-Options` | 已由 nginx 替换 |
| `Content-Security-Policy: frame-ancestors 'self' https://your-portal.example.com` | origin 与宿主一致 |

未配置 `PENPOT_EMBED_PARENT_ORIGIN` 时保留 `X-Frame-Options: SAMEORIGIN`（即**不允许**被跨域嵌入）。**值写成非法格式时也是保留 SAMEORIGIN**，同时 guest 侧退回通配——所以务必用这条命令确认，不要只看环境变量"已设置"。

### 4.2 第 1 层：握手是否发生（消息层）

在宿主页控制台打开日志（或在 2.4 的监听里加 `console.log`），确认：

1. 收到 `{scope: "penpot/embed", type: "ready"}`，且 `event.origin` 等于 Penpot 的 origin
2. 已向 `frame.contentWindow` 发出凭据

### 4.3 第 2 层：凭据是否真的上到请求（关键）

宿主页 DevTools → **Network** → 过滤 `api/main/methods` → 逐个点开 **Request Headers**，每个 Penpot 后端请求都应含：

```
x-token: <你的 token>
x-clientid: <你的 clientId>
```

参考实测结果（假 token、启动阶段、经真实后端）：

```
带凭据  /penpot/api/main/methods/get-enabled-flags
带凭据  /penpot/api/main/methods/get-profile
带凭据  /penpot/api/main/methods/get-profile
带凭据  /penpot/api/main/methods/get-teams
带凭据  /penpot/api/main/methods/logout

合计 5 个后端请求：5 带凭据，0 不带凭据
```

请求集合会随 token 有效性和业务状态变化（有效 token 不会出现 `logout`，会多出工作台相关请求），所以不要按请求个数断言，**唯一要检查的是：列表里不应出现任何"无凭据"的后端请求**。

### 4.4 负向验证（确认测试本身有效）

| 做法 | 期望现象 |
|---|---|
| 把 `PENPOT_EMBED_PARENT_ORIGIN` 配成另一个 origin | 宿主**收不到** `ready`（guest 的 targetOrigin 不匹配，静默丢弃） |
| 收到 `ready` 后故意不回复 | guest 等 5 秒后启动，所有请求**不带** header |
| 把字段名写成 `x-token` / `x-clientid` | 无效，等同于没发（消息键名大小写敏感） |
| 传 `{"X-token": "", "X-ClientId": "x"}` | 无效，走超时路径 |

### 4.5 故障排查表

| 现象 | 排查方向 |
|---|---|
| iframe 白屏，控制台 `Refused to display ... X-Frame-Options` | 部署未设 `PENPOT_EMBED_PARENT_ORIGIN`，或值与宿主实际 origin 不一致（注意端口、scheme、有无结尾 `/`） |
| 宿主收不到 `ready` | 同上——origin 不一致会导致 `ready` 发给一个不存在的窗口，**没有任何报错** |
| 收到 `ready`，但 guest 内显示「此页面已被禁用」 | guest 认为未登录、跳到了登录页（本 fork 屏蔽了登录/注册页）。说明凭据没生效：键名大小写、空值、或回复晚于 5 秒 |
| 所有请求都没有 `x-token` | 凭据在超时之后才到，或 payload 不合法 |
| 只有部分请求有 header | 那些是外部资源/插件资源/WebSocket 请求，按设计不带 |
| 复制/粘贴不可用 | iframe 缺 `allow="clipboard-read; clipboard-write"`，且需 HTTPS |
| 实时协作/通知不工作 | WebSocket 不带自定义 header，检查第三方 Cookie 是否被浏览器策略拦截 |

---

## 5. 安全注意事项

- **务必配置宿主 origin，不要用通配**：`PENPOT_EMBED_PARENT_ORIGIN` 留空或写成非法值时，guest 会退化为"接受任意窗口的凭据"（日志会告警 `no parent origin configured, accepting credentials from any window`）
- guest 只接受来自 `window.parent` 的消息，且 origin 精确匹配
- guest 记录日志时只记 token **长度**，不记值（`embed: credentials received :token-length … :client-id-length …`），宿主可以据此确认"收到了但没泄漏"
- Penpot 只把凭据发往自己的后端，不会随外部资源请求外发
- 凭据存放在 guest 的内存里，宿主页的 DevTools 能直接看到请求头；宿主自身应使用短 TTL 的 token 并全程 HTTPS
- 顶层直接打开 Penpot 网址时不会发 `ready`、不安装监听器，不存在"被页面直接读取凭据"的路径

---

## 6. FAQ

**Q：能用 cookie 或 URL 参数代替 postMessage 吗？**
不能。Penpot 读不到父窗口注入的 DOM/JS，跨域 iframe 也无法共享 cookie 语义（且第三方 Cookie 可能被浏览器拦截）。设计上只支持 postMessage。

**Q：能嵌入多个 iframe 吗？**
可以，每个 iframe 各自独立握手；同一个 Penpot 域下用不同 token 即可（注意配额与并发）。

**Q：换 token 需要重新加载 iframe 吗？**
不需要，再发一条消息即可覆盖（见 3.3）。只有希望"已加载的 profile 立刻刷新"时才需要重载。

**Q：`ready` 重复发送吗？**
一个页面加载只发一次。宿主如果想确认 guest 还活着，请自行在业务层做心跳，不要依赖 guest 重复发 `ready`。

**Q：Penpot 侧怎么调试？**
同一个 Penpot 前端也可以用仓库里的 `frontend/scripts/proxy-server.js` 起本地环境，配合一个最小宿主页验证（`frontend/playwright/ui/specs/embed-credentials.spec.js` 是自动化版本，覆盖正常握手、迟到凭据、origin 不匹配拒绝、通配回退四个场景）。

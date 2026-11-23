# PENPOT_EMBED_PARENT_ORIGIN 多 origin 与 none 跳过校验 — 设计

日期：2026-11-23
状态：已确认（用户批准）
范围：frontend（embed/assistant 校验）、docker（entrypoint/CSP/config 注释）、文档、测试

## 背景与现状

`PENPOT_EMBED_PARENT_ORIGIN` / `globalThis.penpotEmbedParentOrigin` 当前只支持**单个 origin**：

- **配置链**：env → `docker/images/files/nginx-entrypoint.sh` 的 `update_embed_parent_origin`（正则
  `^https?://[A-Za-z0-9._:-]+/?$`，`none` 清空，非法告警后保留默认）→ 追加写入 js/config.js →
  `app.config/embed-parent-origin` 原始字符串透传。
- **前端校验**：`app.util.embed` 的 `origin-accepted?` 将归一化后的 `scheme://host[:port]` **精确比对**；
  未配置/非法 → 通配（接受任意窗口）+ wrn。`post-ready!` 以配置 origin 为 postMessage targetOrigin
  （否则 `*`）。`app.util.embed/assistant.cljs` 重复了一遍同样的 origin-allowed?/resolve-target-origin
  默认逻辑。
- **nginx 嵌入控制**：同一 env 经 `update_frame_ancestors` 写入
  `frame-ancestors 'self' <origin>`（替代 X-Frame-Options）；空/none/非法 → 保持 SAMEORIGIN。

## 目标

1. 支持配置**多个域名**，含 `*.example.com` 形式的**通配子域**。
2. 支持 `none` **显式、无条件跳过** postMessage origin 校验。

## 已确认的关键决策

| 决策点 | 结论 |
|---|---|
| 匹配语义 | 精确枚举 + 通配子域（通配条目放行裸域+任意深度子域；nginx 侧对通配条目同时输出裸域 source 以对齐，见 §1/§4） |
| `none` 边界 | **仅**跳过前端 postMessage origin 校验；nginx X-Frame-Options/frame-ancestors 完全不动 |
| 配置形态 | **方案 A**：保留单字符串，扩展为逗号分隔 origin 列表（向后兼容，方案 B 数组 / 方案 C 新键并存被否） |

## 设计

### 1. 匹配语义（前端）

Pattern 形式 `scheme://[*.]host[:port]`，仅 host 开头的 `*.` 有特殊含义；pattern 其余位置出现 `*`
视为非法条目（拒绝并告警）。

| Pattern | 命中 | 不命中 |
|---|---|---|
| `https://portal.example.com` | 仅该 origin（scheme/端口精确） | 任何 lookalike |
| `https://*.example.com` | `https://example.com`（裸域）、`https://a.example.com`、`https://a.b.example.com`（任意深度子域） | `https://evil-example.com`、`http://a.example.com`、非默认端口者 |
| 带端口 pattern（`:8443`） | 同 scheme 同端口的 origin | 无端口或异端口 origin |

- 纯 `*`、`*://` 不支持——无条件跳过校验用 `none`，两者语义不混。
- **与 nginx frame-ancestors 的对齐**（Chromium 实测）：CSP 的 `*.host` host-source **只匹配子域**，
  不放行裸域，而本设计的通配语义包含裸域。因此 entrypoint 的 `update_frame_ancestors` 对每个通配
  条目**同时输出裸域 source**（`https://*.example.com` → `https://*.example.com https://example.com`），
  保证 HTTP 层与前端匹配器行为一致；前端匹配语义本身不变。
- **`none`**（trim 后大小写不敏感）= 前端显式跳过 postMessage origin 校验：接受任意窗口凭据、
  postMessage 目标退为 `*`、打一条 info（区别于未配置的 wrn）。
- **未配置/全非法回退不变**：通配 + wrn（dev-host.html 流程不受影响）。

### 2. 前端校验（`app.util.embed`）

- `app.config/embed-parent-origin` 保持原始字符串透传，读取逻辑不动。
- 新增 `normalize-origin-pattern`：`normalize-origin` 的通配版（`*.` 前缀保留），仅用于配置条目；
  `event.origin` 永远不是通配，仍走 `normalize-origin`。
- 新增 `parse-origin-config`：字符串 → `:none` | pattern 向量 | `nil`。
  - trim；`none`（ci）→ `:none`。
  - 逗号 + 空白均为分隔符；逐条 `normalize-origin-pattern`，非法条目丢弃并 wrn（列出被丢条目）；
    **全部非法 → `nil`（通配回退，与今天单值行为一致）**。
  - 解析结果 memo（config 是编译期常量，不必逐消息重解析）。
- `origin-accepted?` **签名不变**，语义扩展：`:none`/`nil` → true；pattern 向量 → 任一命中即 true
  （全部精确比对，无前缀匹配）。现有单值测试全部继续成立。
- `target-origins`（替代单数 `target-origin`）：
  - 全部精确 pattern → 向量逐个 postMessage（targetOrigin 仅做过滤，N 次投递只有真实父 origin
    命中那条送达）。
  - **含任一通配 pattern、或 `:none`/未配置 → `["*"]`**（postMessage targetOrigin 无法表达通配主机名，
    `https://*.b.com` 会被浏览器当字面量；ready 消息不含凭据，`*` 无敏感面扩大）。
- `post-ready!` 循环 targets；`*` 告警细化三分支：未配置 → 保持 wrn；`:none` → info 文案
  「validation disabled by config」；**配置含通配 pattern → info**（`*` 目标是 postMessage 的固有限制
  而非配置错误，不能让正确配置的运维者每 2s 重发都吃误导性 wrn；assistant 的 target 分支同规则）。

### 3. `app.util.embed/assistant.cljs`（消重 + 多目标）

- 默认 `origin-allowed?`：删除本地重复的 warned?/normalize 逻辑，改调 embed 公共解析
  （`embed/origin-config` + `embed/origins-accept?`，策略与数据单一来源）；日志文案保留各命名空间
  自有的措辞（`embed:`/`assistant:` 前缀），按 `:none` → info、未配置 → wrn 分级。
- 默认 `resolve-target-origin`：返回 `embed/target-origins` 向量；注入的 string/function 覆盖路径保留
  （string → 常量、function → 调用，返回值统一包装为向量再循环）。
- `post!` 处改为循环 targets 调 `post-message!`；注入签名 `(message origin)` 不变。
- 测试注入路径不受影响；走默认路径的 `post-event-falls-back-to-wildcard-target-origin` 用例适配。

### 4. nginx-entrypoint.sh

- `update_embed_parent_origin`：trim → `none`（ci）→ 写空串（现状不变）；否则剥空白后按逗号 split，
  逐条正则 `^https?://(\*\.)?[A-Za-z0-9._:-]+/?$`，非法条目 stderr 告警丢弃，合法条目去尾斜杠后
  逗号 join 写入；**全部非法 → 不追加**（js/config.js 默认 = 通配，与今天一致）。
- `update_frame_ancestors`：同样分割逐条校验，合法条目空格 join 进 `frame-ancestors 'self' o1 o2 …`；
  **每个 `*.` 通配条目额外输出其裸域 source**（CSP 通配只匹配子域，需双输出才能与前端语义一致）；
  helper 正则收紧为非空 dot 分隔标签 + 纯数字端口（拒绝 `*..host`、`https://...`、`:abc` 等无效形态）；
  `none`/空 → 保持 SAMEORIGIN（已确认不动）。
- 无 shell 测试基建：本地 bash 冒烟脚本验证（source 后调函数，断言生成的 config.js /
  security-headers 内容）。

### 5. 文档与配置注释

四处同步：`frontend/resources/config.js`、`docker/images/files/config.js`（逗号分隔、`*.` 通配、
none 语义=仅 postMessage 校验）、`docker/images/docker-compose.yaml`（env 注释 + 多 origin 示例）、
`frontend/IFRAME-EMBED.md`（配置章节 + 排查表：多域名字段、通配不命中时查 scheme/端口）。

### 6. 测试

- `util_embed_test.cljs` 新增/扩展：`parse-origin-config`（none 大小写、逗号+空白分隔、非法条目丢弃
  wrn、全非法→nil）；通配命中表（裸域/一级/多级/异 scheme/异端口/lookalike 拒绝）；`origin-accepted?`
  多 pattern；`target-origins` 策略（纯精确→逐个；含通配/none→`["*"]`）。
- `util_embed_assistant_test.cljs`：适配默认路径用例 + 补多目标 post 断言。
- 运行：容器内 `pnpm run test:quiet -- --focus frontend-tests.util-embed-test`（遵守 mem:testing，
  不用 tail/head 管道过滤输出）。

## 不做的事（YAGNI）

- 不支持 `*://` / 纯 `*` pattern；不做 CIDR/后缀匹配；不做运行时热更新；不动 `app.config` schema；
  不改 frame-ancestors 的 none 语义；不引入数组/新配置键。

## 兼容性

- 现有单 origin 配置值 = 单元素列表，行为不变。
- 未配置（空）值行为不变（通配 + wrn）。
- entrypoint 对 `none` 的处理不变（清空 → 前端通配）；新增前端对字面 `none` 的显式识别，两者最终
  行为一致，前端不再依赖 entrypoint 转写。

# iframe 嵌入冷启动 WS 不连接 — 修复实施计划（方向1：penpot 侧自愈）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** iframe 嵌入的 Penpot 在冷启动（cookie+storage 全空）时,即使外层系统的凭据/cookie 迟到或 `ready` 消息丢失,也能在**不刷新页面**的情况下自动恢复登录态并建立 WebSocket,而不是永久停在「此页面已被禁用」的死状态。

**Architecture:** 把 `main.cljs` 的「5s 超时即未认证启动」改成分阶段启动门（boot gate）：先只等 postMessage 凭据（0–5s,行为不变）；超时后不立刻启动,而是静默探测 auth cookie（get-profile）并周期重发 `ready`（5–30s）；探测到已认证则以认证态干净启动;到 30s 硬上限才降级为今天的未认证启动。非嵌入（顶层打开）路径完全不变。

**Tech Stack:** ClojureScript（frontend 模块,potok/promesa/beicon）、shadow-cljs watch、cljs.test 单测、Playwright e2e（frontend/playwright）。

---

## 0. 背景与根因（实施者必读）

### 0.1 故障链（已全部代码定位并在本地复现）

用户场景：**360 企业浏览器(内核108)、同域同源 iframe 嵌入、清除 cookie+storage 后重启浏览器,WS 不连接;刷新页面后一切正常。**

```
清空 cookie+storage → iframe 冷启动(jar 无 auth-token),外层 portal 自身也在冷启动(SSO 慢)
 ① penpot boot → embed/start! 向 parent 发 postMessage "ready" ── 只发一次,丢了就没了 (embed.cljs:144)
 ② 5s 内凭据未到(cf/embed-timeout-ms,config.cljs:243-246 读 penpotEmbedTimeoutMs 默认 5000)
    → p/timeout → 未认证 boot! (main.cljs:171-177)
 ③ 未认证 profile → 路由进入 dashboard 侧栏 → fetch-teams 401
    → app/main/errors.cljs:252 (st/emit! (da/logout)) → logged-out:
      - ws/finalize (auth.cljs:253, 无 ws 可关,无操作)
      - 状态 wipe + 跳转 :auth-login (auth.cljs:251)
 ④ pushState 到 /auth/login → resources/config.js 的 blockAuthPages 脚本执行
    document.body.innerHTML = '<div>此页面已被禁用</div>'   (config.js:145-158)
    → #app 渲染根节点被销毁 → 后续渲染反复抛错(console "app is not defined")
    → potok 事件管线死亡
 ⑤ 外层在 8s(或更晚)种下有效 cookie / 补发凭据:
    - 凭据路径: main.cljs:185-189 (st/emit! (dp/refresh-profile)) 事件进入已死的管线 → 无效
    - cookie 路径: 没有任何机制会再拉一次 profile
 ⑥ WebSocket 永不初始化(ws/initialize 只在 authenticated profile-fetched 触发,main.cljs:112-117)
 ⑦ 刷新: portal 已热,凭据 5s 内到达 → 干净认证 boot → ws 正常 ←「刷新即好」
```

本地复现矩阵（台架见 §0.2,服务端 nREPL 连接表为准）:

| 实验 | 场景 | 现状结果 |
|---|---|---|
| A | cookie+凭据即时 | ✅ ws 连接 |
| B-true | cookie+凭据 8s 迟到(冷 jar) | ❌ 停在登录页,ws 无连接（恢复链被④⑤杀死） |
| E | cookie 8s 就位,postMessage 永不到达 | ❌ 同上（唯一恢复钩子挂在 postMessage 上） |

### 0.2 复现台架（Task 6 会用到,已就位）

- 父页面 `frontend/resources/public/embed-test.html`(untracked,参数 `token/delay/creds/cookie/clear/lose`)
- 反代 `tools/analysis/embed-repro-proxy.mjs`(127.0.0.1:9877 → https://localhost:3449,含 ws upgrade;`node tools/analysis/embed-repro-proxy.mjs` 启动)
- 测试账号: devenv `test1@email.com` / `penpot-test-1234`(密码已被本调查重置)
- 服务端 ground truth:
  ```bash
  docker exec penpot-devenv-ws0-main sh -c 'cd /home/penpot/penpot && \
    node tools/nrepl-eval.mjs --backend "(set (map str (keys @app.http.websocket/state)))"'
  ```
- 铸 token:
  ```bash
  curl -sk -D - -o /dev/null -X POST "https://localhost:3449/api/rpc/command/login-with-password" \
    -H 'Content-Type: application/json' \
    -d '{"email":"test1@email.com","password":"penpot-test-1234"}' | grep -i set-cookie
  ```

### 0.3 已核实的关键既有事实（不要重新推导）

- `p/timeout` 是 `race`,**不会** resolve 原始 deferred（编译产物 `cljs-runtime/promesa.core.js:1334`,arity-4 用 `promesa.core.race`）。
- WS URL = `cf/public-uri` + `ws/notifications`(websocket.cljs:23-33);握手纯 cookie 鉴权(backend websocket.clj:282-285);goog.net.WebSocket 无限重连无上限(编译产物 goog.net.websocket.js:102-121)——所以「从未连接」只能是 initialize 从未发生。
- `X-token`/`XClientId` 头后端**零消费**(grep 全后端无引用);真正让 iframe 认证的是同源父页种的 `auth-token` cookie。嵌入契约见 `frontend/IFRAME-EMBED.md`(修复后须同步更新,见 Task 7)。
- config.js 的生成关系: **源文件是 `frontend/resources/config.js`**,`scripts/_helpers.js:590-607` 编译到 `resources/public/js/config.js`,`scripts/watch.js:119` watch 它(改源文件即热生效)。两份当前完全相同,**只改源文件**。
- e2e: `frontend/playwright/ui/specs/embed-credentials.spec.js` 已有 4 用例,用 `BasePage.mockConfigFlags` 注入 `penpotEmbedTimeoutMs` 加速超时;`BasePage.mockRPC` mock `get-profile` 恒返回已登录 fixture。
- 嵌入时 `get-profile` 无会话返回 200+匿名 profile(`:id` = uuid/zero),不会抛错;`da/is-authenticated?`(auth.cljs:30-32)判 `(uuid? id)` 且非 zero。

### 0.4 设计决策（为什么是三阶段门）

1. **阶段划分**（新语义,`deadline` 指 `penpotEmbedBootDeadlineMs`）:
   - **Phase 1 (0 → timeout,默认 5s)**: 只等 postMessage 凭据 + 周期重发 `ready`。**不发任何探测请求**——保住 IFRAME-EMBED.md §4.3 的契约「后端请求不应无凭据」(正常路径零污染)。
   - **Phase 2 (timeout → deadline,默认 30s)**: 凭据没来,不启动;每 3s 静默 `get-profile` 探测 cookie(此时发无凭据请求是安全的:外层已先违约);`ready` 继续每 2s 重发。探测到已认证 → 以认证态干净启动（profile fetch 首次即认证 → 路由/ws 全部正常,不会触发 ③④ 的登出级联与登录页抹除）。
   - **Phase 3 (deadline)**: 降级为今天的行为(未认证启动)。保守兜底,保证"永远会有启动"。
2. **`ready` 重发**: 修复「parent 监听器未就绪导致 ready 丢失」(postMessage 无缓冲)。重发直到门打开;IFRAME-EMBED.md §2.1 契约同步修改。
3. **probe 依赖注入**: `wait-boot!` 接收 `probe` 函数参数而不是自己 require `app.main.repo`——embed.cljs 处于 `app.util.*` 层,被 `app.util.http` 依赖,直接引 `app.main.repo` 会造成分层环。
4. **非嵌入路径零改动**: `embedded?` 为假时 `start!` 立即 resolve nil,main.cljs 直接 `boot!`。
5. **旧全局保留**: `penpotEmbedTimeoutMs` 语义不变(Phase 1 时长);新增 `penpotEmbedBootDeadlineMs`(默认 30000)。旧 e2e 用例对 timeout 的注入方式继续有效。

### 0.5 明确不在本计划范围（记录为后续工作）

- **config.js blockAuthPages 销毁 #app 根节点导致 store 死亡**(④):本修复让嵌入流程不再到达登录页,从而绕开它;但顶层/降级路径仍可触发。后续建议:改为在 #app 内渲染遮罩而非替换 body。
- **errors.cljs:252 对匿名会话 401 直接 logout 的级联**(③):同上被绕开;后续可细化为「未登录态下 401 只导航不 logout」。
- 协同丢帧类问题(丢消息补偿/`:lagged` 应用/重连 revn 追赶)是另一条工作线,与本计划互补。

---

## 文件结构总览

| 文件 | 动作 | 职责 |
|---|---|---|
| `frontend/src/app/config.cljs` | 修改 | 新增 `embed-boot-deadline-ms` |
| `frontend/resources/config.js` | 修改 | 新增 `penpotEmbedBootDeadlineMs` 默认值块(只改源文件) |
| `frontend/src/app/util/embed.cljs` | 修改 | `embedded?` 转公开;新增 `wait-boot!`(三阶段门+ready 重发);更新 ns docstring |
| `frontend/src/app/main.cljs` | 修改 | init 的 embed 块改为走门;新增 `probe-authenticated?`(rp 注入) |
| `frontend/test/frontend_tests/util_embed_test.cljs` | 修改 | `wait-boot!` 三结局 + 非 embedded? 的单测 |
| `frontend/playwright/ui/specs/embed-credentials.spec.js` | 修改 | 重写迟到凭据用例(新语义 RED);新增 cookie-迟到-无回复用例 |
| `frontend/IFRAME-EMBED.md` | 修改 | 契约文档同步(§2.1/§3.2/§4.4/§4.5/FAQ) |
| `~/.claude/projects/-Users-slynyeh-Downloads-penpot-2-17-0/memory/collab-realtime-chain-analysis.md` | 修改 | 记录修复落地与验证结果 |

单测新 ns 不新增(扩展 util-embed-test,无需改 runner);e2e 不新增文件。

---

## Task 1: 配置项 `penpotEmbedBootDeadlineMs`

**Files:**
- Modify: `frontend/src/app/config.cljs:243-246`
- Modify: `frontend/resources/config.js`(embed timeout 块之后)

- [x] **Step 1.1: config.cljs 新增 def**

在现有 `embed-timeout-ms` def 之后(`frontend/src/app/config.cljs:246` 之后)追加:

```clojure
(def embed-boot-deadline-ms
  "How long the embedded app keeps waiting for either the postMessage
   credentials or a working auth cookie before giving up and booting
   logged-out (degraded boot, same behavior as the old fixed timeout).
   Must be >= embed-timeout-ms to make sense. Configured via
   `penpotEmbedBootDeadlineMs` in resources/config.js."
  (d/parse-integer (obj/get global "penpotEmbedBootDeadlineMs") 30000))
```

- [x] **Step 1.2: config.js 源文件新增默认值块**

在 `frontend/resources/config.js` 的 `penpotEmbedTimeoutMs` 块之后追加(注意文件是 IIFE 块风格,保持一致):

```js
(function () {
  // 嵌入启动门:等待凭据或可用 auth cookie 的总上限(毫秒)。超过 penpotEmbedTimeoutMs
  // 后进入探测期:不再立即以未登录态启动,而是周期静默 get-profile 探测 cookie、并重发
  // ready,直到凭据/cookie 任一就绪(干净启动)或到达本上限(降级为旧的未登录启动)。
  globalThis.penpotEmbedBootDeadlineMs = 30000;
})();
```

- [x] **Step 1.3: 验证 watch 热编译**

等 shadow watch 重编译完成(tmux 0.0 pane 或 `curl -s http://localhost:8000/penpot/js/config.js | grep BootDeadline` 应输出该行)。若 8000 未起,用 `curl -sk https://localhost:3449/js/config.js | grep BootDeadline`。

---

## Task 2: embed.cljs — `embedded?` 公开 + `wait-boot!` 实现

**Files:**
- Modify: `frontend/src/app/util/embed.cljs`

- [x] **Step 2.1: 先写失败的单测(见 Task 3 的测试将引用 wait-boot!;此处先落实现框架会让 watch 报未定义,故顺序为:本步先写实现,Task 3 补测试跑 RED→GREEN 不适用——wait-boot! 是新增 API,单测与实现同 PR 内落地,Task 3 跑通)**

> 注:新增 API(非行为修改)时 TDD 的 RED 是「测试引用未定义符号编译失败」,在 watch 环境下会阻塞其他测试,故此处实现与测试同任务批次交付;**行为级 RED→GREEN 由 Task 5 的 e2e 承担**(改写迟到凭据用例为期望新行为,先在旧代码上跑红)。

- [x] **Step 2.2: `embedded?` 转公开**

把 `(defn- embedded? ...)`(embed.cljs:44-47)改为:

```clojure
(defn embedded?
  "True when this document is rendered inside another window. Public so the
   boot sequence in `app.main` can skip the embed gate entirely for
   top-level loads."
  []
  (not (identical? js/window js/parent)))
```

同 ns 内部调用点无需改(`embedded?` 名字不变)。

- [x] **Step 2.3: 新增 `wait-boot!`**

在 `start!`(embed.cljs:146-163)之后追加。完整实现:

```clojure
(defn wait-boot!
  "Boot gate for embedded loads. Returns a promise resolved with one of:

   :credentials  — the parent delivered the postMessage credentials
   :authenticated — a probe says the cookie jar already authenticates
   :deadline     — gave up; caller should boot degraded (logged-out)

   Phases: until `timeout-ms` only the handshake is watched (no requests,
   so the happy path never sends uncredentialed traffic); from `timeout-ms`
   to `deadline-ms` the app probes `probe` (a promise of boolean, injected
   to avoid a layering cycle with app.main.repo) and re-posts `ready` every
   `resend-interval-ms` so a parent that missed the first message can still
   answer. Non-embedded callers never reach this function."
  [{:keys [handshake probe timeout-ms deadline-ms
           probe-interval-ms resend-interval-ms]
    :or {timeout-ms 5000
         probe-interval-ms 3000
         resend-interval-ms 2000}}]
  (let [result (p/deferred)
        timers (atom #{})
        open!  (fn [outcome]
                 (when (p/pending? result)
                   (run! js/clearInterval @timers)
                   (p/resolve! result outcome)))
        every! (fn [ms f] (swap! timers conj (js/setInterval f ms)))
        probe! (fn []
                 (-> (probe)
                     (p/catch (constantly false))
                     (p/then (fn [auth?] (when auth? (open! :authenticated))))))]

    ;; Phase 1: the handshake resolves as soon as credentials arrive.
    (p/then handshake (fn [creds] (when (some? creds) (open! :credentials))))

    ;; Phase 2: once the handshake-only window is over, probe the cookie
    ;; jar (immediately, then on an interval).
    (js/setTimeout
     (fn []
       (when (p/pending? result)
         (probe!)
         (every! probe-interval-ms #(when (p/pending? result) (probe!)))))
     timeout-ms)

    ;; Re-post ready while unresolved (postMessage has no buffering; the
    ;; first one can be lost if the parent listener is not installed yet).
    (every! resend-interval-ms
            (fn []
              (when (p/pending? result)
                (post-ready!))))

    ;; Phase 3: degraded boot at the deadline.
    (swap! timers conj (js/setTimeout #(open! :deadline) deadline-ms))

    result))
```

注意:
- `js/setTimeout` 返回的 id 与 `js/setInterval` 一样收进 `timers`,统一用 `js/clearInterval` 清除(浏览器两 API 共用定时器池,互清合法)。
- `probe` 异常一律吞为 false(探测失败绝不能崩掉启动门)。
- deadline 触发的 `open! :deadline` 在门已开时是 no-op(`p/pending?` 守卫)。
- 本 ns 不得引入新的顶层副作用(见 ns docstring 约束);`wait-boot!` 仅由 `app.main/init` 显式调用。

- [x] **Step 2.4: 更新 ns docstring**

把 ns docstring 中 "The handshake is started explicitly from `app.main/init` only." 之后补一句:
`The boot gate (wait-boot!) is likewise driven only from app.main/init; this namespace keeps no top-level side effects.`

- [x] **Step 2.5: watch 编译通过**

tmux penpot 会话 0.0 pane(容器内)确认无新编译错误;有断括号先跑 `tools/paren-repair.bb`(见 CLAUDE.md)。

---

## Task 3: 单测 — `wait-boot!` 与 `embedded?`

**Files:**
- Modify: `frontend/test/frontend_tests/util_embed_test.cljs`

- [x] **Step 3.1: 追加测试(文件末尾)**

```clojure
(t/deftest wait-boot-opens-on-credentials
  (t/async done
    (let [handshake (p/deferred)
          probes    (atom 0)]
      (p/then
       (embed/wait-boot! {:handshake handshake
                          :probe #(do (swap! probes inc)
                                      (p/rejected (js/Error. "probe must not run")))
                          :timeout-ms 50
                          :deadline-ms 500})
       (fn [outcome]
         (t/is (= :credentials outcome))
         (t/is (zero? @probes) "phase 1 must not probe")
         (done)))
      ;; credentials arrive before the timeout: the probe must never fire.
      (js/setTimeout #(p/resolve! handshake {:token "t" :client-id "c"}) 10))))

(t/deftest wait-boot-opens-on-authenticated-probe
  (t/async done
    ;; Handshake never resolves (parent lost the ready message); the cookie
    ;; jar starts authenticating after the timeout-only window.
    (let [handshake (p/deferred)
          jar       (atom false)]
      (p/then
       (embed/wait-boot! {:handshake handshake
                          :probe #(p/resolved @jar)
                          :timeout-ms 30
                          :deadline-ms 2000
                          :probe-interval-ms 20})
       (fn [outcome]
         (t/is (= :authenticated outcome))
         (done)))
      (js/setTimeout #(reset! jar true) 60))))

(t/deftest wait-boot-degrades-at-deadline
  (t/async done
    (let [handshake (p/deferred)]
      (p/then
       (embed/wait-boot! {:handshake handshake
                          :probe #(p/resolved false)
                          :timeout-ms 20
                          :deadline-ms 120
                          :probe-interval-ms 20})
       (fn [outcome]
         (t/is (= :deadline outcome))
         (done))))))
```

文件头部 require 增加 `[promesa.core :as p]`。

> 实施期修订(代码评审): 第一个用例的哨兵 probe 会被 `wait-boot!` 的 `p/catch` 吞掉,原写法守护不了「Phase 1 零探测」契约,故改为计数断言。同批评审还向 `wait-boot!` 的 `:or` 补了 `deadline-ms 30000` 与 `probe #(p/resolved false)` 默认值(防止缺参时 0ms 立即降级/定时器回调抛 TypeError)。

- [x] **Step 3.2: 运行**

```bash
docker exec penpot-devenv-ws0-main sh -c \
  'cd /home/penpot/penpot/frontend && pnpm run test:quiet -- --focus frontend-tests.util-embed-test --log-level warn'
```

预期: 全部通过(含既有用例;实际既有为 8 个,共 11 个)。失败时先核对 `wait-boot!` 的参数默认值与 `p/pending?` 守卫。

---

## Task 4: main.cljs — 启动门接线

**Files:**
- Modify: `frontend/src/app/main.cljs:160-192`(init 内 embed 块)及 ns require

- [x] **Step 4.1: ns require 增补**

`app.main.cljs` ns 块加 `[app.main.repo :as rp]`(promesa `p`、rx、embed、dp、log 均已在)。

- [x] **Step 4.2: 新增 probe 辅助函数**

在 `(defn- initialize-rasterizer ...)`(约 :77)之后加:

```clojure
(defn- first-value
  "Resolve with the first value of `obs` (nil if it completes empty)."
  [obs]
  (p/create (fn [resolve _]
              (rx/subscribe! (rx/take 1 obs) resolve))))

(defn- probe-authenticated?
  "Promise of boolean: does the current cookie jar authenticate? Used by the
   embed boot gate to notice the parent planting the auth cookie without (or
   before) the postMessage credentials. Anonymous profiles return 200 with
   :id = uuid/zero, so this never throws for a logged-out jar."
  []
  (->> (rp/cmd! :get-profile)
       (rx/map da/is-authenticated?)
       (rx/catch (constantly (rx/of false)))
       (first-value)))
```

- [x] **Step 4.3: 替换 init 内的 embed 块**

用下面代码替换 `main.cljs` init 中 `(let [handshake (embed/start!) ...)` 到最后一个 `(p/catch ...))` 的整块(现 :165-192)。保留其上方注释思想,重写为:

```clojure
      ;; When embedded in a third party system, booting is gated: wait for
      ;; the postMessage credentials first (happy path, no uncredentialed
      ;; requests); past `embed-timeout-ms` start probing the cookie jar and
      ;; re-posting `ready` (cold start recovery); at `embed-boot-deadline-ms`
      ;; boot degraded exactly like the old fixed timeout did. Top-level
      ;; loads boot immediately.
      (let [handshake (embed/start!)
            degraded  (volatile! false)]
        (if (embed/embedded?)
          (-> (embed/wait-boot! {:handshake handshake
                                 :probe probe-authenticated?
                                 :timeout-ms cf/embed-timeout-ms
                                 :deadline-ms (max cf/embed-timeout-ms
                                                   cf/embed-boot-deadline-ms)})
              (p/then (fn [outcome]
                        (when (= outcome :deadline)
                          (vreset! degraded true)
                          (log/wrn :hint "embed: timed out waiting for credentials"))
                        (log/inf :hint "embed: boot gate opened" :outcome outcome)
                        (boot!)))
              (p/catch (fn [cause]
                         (log/err :hint "embed: boot gate failed, booting anyway"
                                  :cause cause)
                         (boot!))))

          ;; Not embedded: the handshake is already resolved with nil.
          (-> handshake
              (p/then (fn [_] (boot!)))
              (p/catch (fn [cause]
                         (log/err :hint "embed: handshake failed, booting anyway"
                                  :cause cause)
                         (boot!)))))

        ;; Backstop: credentials that only arrive after a degraded boot still
        ;; refresh the session once (unchanged semantics from the old flow).
        (-> handshake
            (p/then (fn [creds]
                      (when (and (some? creds) @degraded)
                        (log/wrn :hint "embed: credentials arrived late, refreshing profile")
                        (st/emit! (dp/refresh-profile)))))
            (p/catch (fn [cause]
                       (log/err :hint "embed: late credentials handling failed"
                                :cause cause)))))
```

语义要点:
- `log/wrn "embed: timed out waiting for credentials"` 与 `"embed: credentials arrived late, refreshing profile"` **字面保留**(e2e 断言依赖,见 Task 5)。
- deadline 取 `(max timeout deadline)`:部署把 deadline 配得比 timeout 小时不产生负等待。
- Phase 1 内 probe 从不执行(由 `wait-boot!` 保证),正常嵌入路径不会出现无凭据请求。
- `(log/inf :hint "embed: boot gate opened" :outcome outcome)` 用 inf 不用 dbg,e2e console 可见。

- [x] **Step 4.4: watch 编译通过 + 冒烟**

编译无错后,浏览器顶层打开 `https://localhost:3449/penpot/`(或 8000):行为必须与现在完全一致(立即启动)。console 不应出现 boot gate 日志(非嵌入)。

---

## Task 5: e2e — 改写旧用例为新语义(RED) + 新增恢复用例

> **实施期修订(控制器预审+代码评审):** ① Step 5.2 的 fixture 实际位于 `frontend/playwright/data/`(非 `playwright/ui/mocks`),匿名 fixture 复用根目录既有 `get-profile-anonymous.json`(勿新建 `not-logged-in/`);无 `fulfillRPC` 助手,自定义 route 用 `route.fulfill({status,contentType,path:"playwright/data/<fixture>"})`,与 mockRPC 约定一致,无需 `fs`。② Step 5.3 计划断言自相矛盾:timeout=300ms、回复 1200ms 时 Phase 2 探测必然在 300ms 发出无凭据 get-profile,故「零无凭据请求」断言须豁免 `/get-profile`(排除后仍能区分旧代码——旧代码会发无凭据的非探测启动流量);`path.endsWith` 为笔误应为 `r.path.endsWith`。③ Step 5.4 加固(评审): `deadlineMs: 8000`、`>1` 轮询显式 `{timeout: 15000}`、`waitForTimeout(600)` 改为事件驱动(先轮询到首个探测再种 cookie)。④ 基础设施偏差(已验证接受): 父页服务绑定 `127.0.0.1`→`localhost`(SameSite=Lax cookie 需同 site 才能随 iframe 请求发送;端口不同仍保「不同 origin」语义,/unsolicited 用例不受影响,且与生产同域嵌入拓扑一致)。⑤ Step 5.6 命令修正见下。

**Files:**
- Modify: `frontend/playwright/ui/specs/embed-credentials.spec.js`

- [x] **Step 5.1: 先在旧代码上跑改写后的用例(RED 确认)**

本步不跑也行(旧代码上必红:新用例期望「迟到时不出现未认证请求」),执行会话若要严格 RED,可在 git stash 实现后运行——**实际执行时可跳过,直接实现后跑绿;RED 的意义已由 §0.1 复现矩阵承担。**

- [x] **Step 5.2: `openEmbeddedApp` 改造**

1. `mockConfigFlags` 注入增加新全局(保留旧的全局注入不动):

```js
  await BasePage.mockConfigFlags(page, [], {
    ...(configured ? { penpotEmbedParentOrigin: configured } : {}),
    penpotEmbedTimeoutMs: timeoutMs,
    penpotEmbedBootDeadlineMs: deadlineMs,
  });
```

函数签名加 `deadlineMs = 30000`。

2. **get-profile 改为 cookie 感知的自定义 route**(替换原 `BasePage.mockRPC(page, "get-profile", ...)` 一行)。mock 语义:请求带 `auth-token` cookie → 已登录 fixture;否则匿名 fixture。匿名 fixture 若 mocks 目录没有,用已登录 fixture 复制改 `"id": "00000000-0000-0000-0000-000000000000"` 存为 `not-logged-in/get-profile-anonymous.json`(先 `ls frontend/playwright/ui/mocks` 确认命名惯例):

```js
  await page.route(`**/api/main/methods/get-profile`, async (route) => {
    const cookie = (await route.request().headerValue("cookie")) || "";
    const fixture = cookie.includes("auth-token=")
      ? "logged-in-user/get-profile-logged-in.json"
      : "not-logged-in/get-profile-anonymous.json";
    await BasePage.fulfillRPC(route, fixture); // 若 BasePage 无此助手,直接读 fixture 文件 fulfill,与 mockRPC 内部实现保持一致
  });
```

(若 `BasePage.mockRPC` 的内部履行逻辑不可复用,就 `fs.readFile` mocks 目录 fixture 后 `route.fulfill({ contentType: "application/transit+json", body })`;以 BasePage 现有实现为准,保持一致。)

- [x] **Step 5.3: 重写「迟到凭据」用例(新语义)**

替换 `test("boots without credentials when the parent replies late, ...")` 整段为:

```js
test("waits for late credentials instead of booting logged out", async ({
  page,
}) => {
  const { backend, logs } = await openEmbeddedApp(page, {
    path: "/ready-late",
    parentOrigin,
  });

  // The gate holds the boot until the credentials land, so every backend
  // request (the probe included — it must not run before the timeout) goes
  // out with the headers attached.
  await expect
    .poll(() => backend.filter((r) => r.token === TOKEN).length)
    .toBeGreaterThan(0);
  expect(backend.filter((r) => !r.token && !r.clientId)).toEqual([]);

  // The parent saw the ready message exactly once is no longer asserted:
  // the gate re-posts ready until it opens, so `received` may grow. What
  // must hold is that the reply was used.
  await expect.poll(() => page.evaluate("window.parentLog.replies")).toBe(1);
});
```

- [x] **Step 5.4: 新增 cookie-迟到-无回复用例(核心恢复场景)**

在文件追加(需 `import { test, expect } ...` 已有;再 `import fs from "node:fs"` 若 Step 5.2 用到):

```js
test("recovers when the parent plants the cookie but never replies", async ({
  page,
  context,
}) => {
  const { backend, logs } = await openEmbeddedApp(page, {
    path: "/never-reply",           // 新增父页: replyAfterMs: null
    parentOrigin,
    timeoutMs: 300,
    deadlineMs: 5000,
  });

  // No credentials ever arrive; the parent (simulated via playwright's
  // cookie jar, same effect as document.cookie on a same-origin host)
  // plants a working auth cookie after the timeout-only window.
  await page.waitForTimeout(600);
  await context.addCookies([
    { name: "auth-token", value: "planted-by-parent", url: GUEST_URL },
  ]);

  // The probe picks the cookie up and the app boots authenticated: no
  // logged-out boot, no uncredentialed request after the gate opens.
  await expect
    .poll(() => backend.filter((r) => r.path.endsWith("/get-profile")).length)
    .toBeGreaterThan(1);
  expect(logs()).toContain("embed: session cookie detected");
  // The degraded path was never taken.
  expect(logs()).not.toContain("embed: timed out waiting for credentials");
});
```

配套:`PARENT_PAGES` 增加 `"/never-reply": parentPage({ replyAfterMs: null })`;probe 认证后门打开的日志(见 Step 5.5)。

- [x] **Step 5.5: probe 打开门的日志**(已并入 Task 4 实施)

在 `embed.cljs` 的 `wait-boot!` 中 `open! :authenticated` 处,把 outcome 日志留给 main.cljs 之外,再在 main.cljs `(log/inf :hint "embed: boot gate opened" :outcome outcome)` 之外补一条人类可读 wrn/inf(新用例断言):

```clojure
(when (= outcome :authenticated)
  (log/inf :hint "embed: session cookie detected"))
```

(放在 main.cljs gate 的 `p/then` 内,与 deadline 的 wrn 并列。)

- [x] **Step 5.6: 运行 e2e**

```bash
docker exec -u penpot penpot-devenv-ws0-main bash -c \
  'cd /home/penpot/penpot/frontend && pnpm run test:e2e --grep "embed-credentials" 2>&1 | tee /tmp/embed-e2e-output.txt; exit ${PIPESTATUS[0]}'
```

(实测修正: docker exec 需 `-u penpot`(root 无浏览器缓存);`--grep` 前只一个 `-`(双 `-` 会让 playwright 跑全量 326 个用例);用 bash -c 以支持 PIPESTATUS。)

预期: 5 个用例(原 4 改 1 + 新 1)全绿。origin-reject / unsolicited / wildcard 三个既有用例若因 probe 请求出现而断言失败,按「probe 是无凭据 get-profile,允许出现在 timeout 之后」放宽对应断言(只放宽 `backend.length` 类轮询,不放宽 token 断言)。

> 注:CLAUDE.md 说 e2e "only when explicitly asked" —— 本计划获准执行即视为对 embed-credentials 单 spec 的显式授权范围;不要跑全量 e2e。

---

## Task 6: 手工验证(复现台架,新旧对照)

- [x] **Step 6.1: 起台架**

```bash
node tools/analysis/embed-repro-proxy.mjs &   # 若 9877 未监听
TOKEN=$(curl -sk -D - -o /dev/null -X POST "https://localhost:3449/api/rpc/command/login-with-password" \
  -H 'Content-Type: application/json' \
  -d '{"email":"test1@email.com","password":"penpot-test-1234"}' | grep -i '^set-cookie' | sed 's/.*auth-token=\([^;]*\).*/\1/')
```

- [x] **Step 6.2: Test E(GREEN 判定)——cookie 迟到、postMessage 丢失**

浏览器(或 Playwright MCP)打开:

```
http://127.0.0.1:9877/embed-test.html?token=$TOKEN&delay=8000&creds=0&clear=1
```

等待 ~20s 后(判据按可信度排序,console 仅作辅助——dev watch 构建里 app 日志不一定进 console,e2e 构建才保证):
- iframe URL 应为 `/penpot/dashboard/...`(不再是 `/penpot/auth/login`)——主判据;
- nREPL 连接集合出现新 id(见 §0.2 命令),且 `repl-get-connection-info` 的 profile-id = `4d03d20b-5e02-8073-8008-75fdfa9b914b`——主判据;
- console(若可见)含 `embed: session cookie detected`。

- [x] **Step 6.3: Test B-true——凭据+cookie 双迟到**

```
http://127.0.0.1:9877/embed-test.html?token=$TOKEN&delay=8000&clear=1
```

预期同 6.2(门以 :credentials 打开,干净认证启动,ws 连接)。

- [x] **Step 6.4: 回归——A(即时)与降级路径**

- `?token=$TOKEN&delay=0&clear=1`: 立即正常,ws 连接,console 无 timeout/探测日志。
- `?token=$TOKEN&creds=0&clear=1&delay=999999`(永不到来): ~30s 后降级启动(登录页禁用态,与现状一致;deadline 后 console 有 `embed: timed out waiting for credentials`)。此路径即 §0.5 的后续工作,行为不变是预期。

- [x] **Step 6.5: 回归——顶层(非嵌入)**

直接顶层打开 `https://localhost:3449/penpot/`(带 cookie):立即启动,console 无 gate 日志。

---

## Task 7: 文档与记忆同步

- [x] **Step 7.1: IFRAME-EMBED.md 更新**(面向外层系统团队,保持中文风格)
  - §2.1 发送频率: 改为「启动门打开前每 2 秒重发(凭据/可用 cookie/上限任一到达即停);宿主监听器请幂等」。
  - §3.2 启动时序: 改为三阶段描述(5s 只等凭据 → 5–30s 探测 cookie+重发 ready,任一就绪即干净启动 → 30s 降级);新全局 `penpotEmbedBootDeadlineMs`;删去「不要依赖迟到也能救回来」结论,改为「5–30s 内凭据或 cookie 任一就绪都会自动恢复;30s 仍是硬上限」。
  - §4.4 负向验证「收到 ready 后故意不回复」: 期望改为「5s 后开始探测(每 3s 一个无凭据 get-profile)+重发 ready;30s 前种好 cookie 即恢复,否则 30s 降级」。
  - §4.5 排查表「此页面已被禁用」行: 补「现已支持 30s 内 cookie/凭据迟到自愈;若仍出现,说明超过上限或 cookie 无效」。
  - FAQ「ready 重复发送吗?」: 按新契约改写。
- [x] **Step 7.2: 记忆更新**

更新 `~/.claude/projects/-Users-slynyeh-Downloads-penpot-2-17-0/memory/collab-realtime-chain-analysis.md`:在「修复方向」段落标注方向1已实施,指向本计划路径与验证结果;如实验结论有出入,如实记录。

---

## Task 8: lint / fmt / 收尾提交

- [x] **Step 8.1:**

```bash
./scripts/lint && ./scripts/check-fmt
```

修复所有新引入问题(不动预存告警;`render-wasm?`/`process-wasm-object` 预存 undeclared-var 勿追)。

- [x] **Step 8.2: 全量前端单测**

> 实测注记: 全量 474 测试 2340 断言,2 failures + 1 error,均与本计划无关——①keymap_test 2 个 FAIL 是 fork 已提交的自相矛盾(keymap.cljs 把 :escape 映射为 "Esc" 而其测试仍期望 "⎋",HEAD 即红);②wait-boot-opens-on-authenticated-probe 的 ERROR 是另一条 WIP(workspace.cljs:472-478 watch)的 wasm 异步 store 异常(`render_wasm.api.initialized_QMARK_ is not a function`)漂到 embed 测试的异步窗口被误报,聚焦跑 embed 11/11 全绿。本计划 7 文件 lint/fmt 零发现(check-fmt 的 24 个不合格均为无关 WIP/预存文件)。

```bash
docker exec penpot-devenv-ws0-main sh -c \
  'cd /home/penpot/penpot/frontend && pnpm run test:quiet --log-level warn'
```

- [ ] **Step 8.3: 提交(按 CLAUDE.md:仅在用户确认后提交;绝不 push)**

```bash
git add frontend/src/app/config.cljs frontend/resources/config.js \
        frontend/src/app/util/embed.cljs frontend/src/app/main.cljs \
        frontend/test/frontend_tests/util_embed_test.cljs \
        frontend/playwright/ui/specs/embed-credentials.spec.js \
        frontend/IFRAME-EMBED.md docs/superpowers/plans/2026-11-04-embed-coldstart-ws-recovery.md
git commit -s -m ":bug: Recover embedded cold-start when embed credentials or cookie arrive late

Embedded boots no longer give up at the fixed 5s timeout: the new boot
gate waits for the postMessage credentials, probes the cookie jar and
re-posts ready until either lands (or penpotEmbedBootDeadlineMs), so a
cold iframe whose parent SSO is slow self-heals instead of ending on
the disabled login page with no websocket.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

提交信息里的 AI 尾注以会话当时的 attribution 指引为准。

---

## 验收清单(全部满足才算完成)

1. Task 3 单测绿(既有用例无回归)。
2. Task 5 e2e 5 用例绿(含新恢复用例)。
3. Task 6 四个手工场景结果与预期一致(E/B-true 自愈,A 不变,降级路径与现状一致,顶层不变)。
4. Task 7 两份文档更新完毕。
5. lint/check-fmt 干净、全量单测绿。

## 回滚

全部改动集中在前端 4 个源文件 + 2 个测试/文档;`git revert` 单提交即回滚。config 新全局缺省(未设置)时 deadline=30s——回滚后旧全局 `penpotEmbedTimeoutMs` 语义原样保留,部署侧无残留依赖。

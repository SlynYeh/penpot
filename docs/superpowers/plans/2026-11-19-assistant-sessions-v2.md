# AI 助手 新对话/历史对话/会话记忆 v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已实现的 AI助手 tab 上增加「新对话」「历史对话」按钮、历史会话列表视图（查看/切换/删除），以及跨 tab 切换与刷新的会话记忆恢复。

**Architecture:** 全部会话操作走 `window.X_Assistant` SDK（`getTools() → {jsSdk, changeSessionId}`）；会话记忆存 localStorage（key 含 profile+file 维度）；tab-content 内 `:chat | :history` 两互斥视图本地 state 切换；SDK 挂载 div 永不条件渲染（仅 CSS 隐藏），防止 React 重挂载销毁 SDK DOM。纯逻辑（存储 key、时间戳解析/格式化）下沉到 `app.util.embed.assistant` 以便 node 单测；mock SDK 升级到 v2 契约（sessionStorage 持久化会话）支撑手动走查。

**Tech Stack:** ClojureScript (Rumext v2 + Potok)，SCSS module，`.po` i18n（仅 en/zh_CN），plain JS mock SDK。

**需求来源（决策勿再讨论）：** `docs/UI/agent-intergration-v2.md`（2026-11-19 已确认稿，含 9 项决策表）。

**基线：** v1 已实现并走查通过（`docs/superpowers/plans/2026-11-09-ai-assistant-tab-integration.md`）。v1 决策 6 的「切回=全新会话」被本期部分推翻：切走仍完整销毁 SDK，但切回时恢复 localStorage 记忆的会话。

**关键既有代码（本计划 Modify 目标）：**

| 文件 | 现状 |
|---|---|
| `frontend/src/app/util/embed/assistant.cljs` | 通信层纯函数 + `create-conn`；零顶层副作用约束 |
| `frontend/src/app/main/ui/workspace/sidebar/assistant.cljs` | 255 行；状态机 `:handshaking→…→:ready/[error]`；effect `[attempt]` 全流程 + cleanup 销毁 |
| `frontend/src/app/main/ui/workspace/sidebar/assistant.scss` | `.assistant-tab/.mount/.status/.spinner` 已有 |
| `frontend/resources/assistant/mock-assistant-sdk.js` | 426 行 IIFE；`getTools()` 返回工具描述符数组（**v1 形状，无会话方法**） |
| `frontend/test/frontend_tests/util_embed_assistant_test.cljs` | 已注册于 runner（同 ns 加 var 无需改 runner） |

**图标：** 全部复用现有 sprite：`i/comments`（新对话）、`i/history`（历史对话）、`i/arrow-left`（返回）、`i/remove`（删除）。**无需新增 svg**（规避 watch 不重编 sprite 盲区）。

**验证命令约定（frontend 目录执行）：** 单测 `pnpm run test:quiet -- --focus frontend-tests.util-embed-assistant-test`；lint/fmt 从仓库根 `./scripts/lint`、`./scripts/check-fmt`。

---

### Task 1: embed 层会话记忆 + 时间戳 helpers（TDD）

**Files:**
- Modify: `frontend/src/app/util/embed/assistant.cljs`
- Test: `frontend/test/frontend_tests/util_embed_assistant_test.cljs`（追加，ns 已注册 runner）

- [ ] **Step 1: 写失败测试**

在 `frontend/test/frontend_tests/util_embed_assistant_test.cljs` 文件末尾追加：

```clojure
;; ---------------------------------------------------------------------------
;; v2: session memory (localStorage) and history list formatting
;; ---------------------------------------------------------------------------

(defn- fake-storage
  "The localStorage surface the session memory needs, backed by an atom;
  also able to throw on every method for the containment tests."
  [& {:keys [throwing]}]
  (let [items (atom {})]
    #js {:setItem    (fn [k v]
                       (when throwing (throw (js/Error. "boom")))
                       (swap! items assoc k v))
         :getItem    (fn [k]
                       (when throwing (throw (js/Error. "boom")))
                       (get @items k))
         :removeItem (fn [k]
                       (when throwing (throw (js/Error. "boom")))
                       (swap! items dissoc k))}))

(t/deftest session-storage-key-format
  (t/is (= "penpot-assistant-session:p1:f1"
           (assistant/session-storage-key "p1" "f1"))))

(t/deftest saved-session-id-roundtrip
  (let [storage (fake-storage)]
    (t/is (nil? (assistant/load-saved-session-id "p1" "f1" storage)))
    (assistant/save-session-id! "p1" "f1" "s1" storage)
    (t/is (= "s1" (assistant/load-saved-session-id "p1" "f1" storage)))
    ;; a different (profile, file) pair never sees the same slot
    (t/is (nil? (assistant/load-saved-session-id "p2" "f1" storage)))
    (assistant/clear-saved-session-id! "p1" "f1" storage)
    (t/is (nil? (assistant/load-saved-session-id "p1" "f1" storage)))))

(t/deftest saved-session-id-nil-storage-is-a-no-op
  (t/is (nil? (assistant/load-saved-session-id "p" "f" nil)))
  (t/is (nil? (assistant/save-session-id! "p" "f" "s" nil)))
  (t/is (nil? (assistant/clear-saved-session-id! "p" "f" nil))))

(t/deftest saved-session-id-throwing-storage-is-contained
  (let [storage (fake-storage :throwing true)]
    (t/is (nil? (assistant/load-saved-session-id "p" "f" storage)))
    (t/is (nil? (assistant/save-session-id! "p" "f" "s" storage)))
    (t/is (nil? (assistant/clear-saved-session-id! "p" "f" storage)))))

(t/deftest parse-sdk-timestamp-units
  ;; 13 digit numeric string -> epoch millis
  (let [date (assistant/parse-sdk-timestamp "1732012800000")]
    (t/is (some? date))
    (t/is (= 1732012800000 (.getTime date))))
  ;; 10 digit numeric string -> epoch seconds (same instant)
  (let [date (assistant/parse-sdk-timestamp "1732012800")]
    (t/is (some? date))
    (t/is (= 1732012800000 (.getTime date)))))

(t/deftest parse-sdk-timestamp-garbage
  (t/is (nil? (assistant/parse-sdk-timestamp nil)))
  (t/is (nil? (assistant/parse-sdk-timestamp "")))
  (t/is (nil? (assistant/parse-sdk-timestamp "not-a-date"))))

(t/deftest format-session-time-fixed-shape
  ;; timezone independent assertions: the two unit spellings of the same
  ;; instant format identically, and the shape is always yyyy-MM-dd HH:mm
  (let [via-ms (assistant/format-session-time "1732012800000")
        via-s  (assistant/format-session-time "1732012800")]
    (t/is (some? via-ms))
    (t/is (= via-ms via-s))
    (t/is (re-find #"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$" via-ms))))

(t/deftest format-session-time-invalid
  (t/is (nil? (assistant/format-session-time "garbage")))
  (t/is (nil? (assistant/format-session-time nil))))
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && pnpm run test:quiet -- --focus frontend-tests.util-embed-assistant-test --log-level warn
```

Expected: FAIL — `session-storage-key`/`load-saved-session-id`/`parse-sdk-timestamp`/`format-session-time` 未定义（编译错误或 no such var）。

- [ ] **Step 3: 实现**

`frontend/src/app/util/embed/assistant.cljs` 两处改动。

3a. ns require 块加 `app.common.time`（插在 `app.common.logging` 之后，保持字母序）：

```clojure
  (:require
   [app.common.logging :as log]
   [app.common.time :as ct]
   [app.config :as cf]
   ...)
```

3b. 在 `build-init-config` 函数之后、`parse-incoming` 之前插入：

```clojure
;; -------------------------------------------------------------------------
;; v2: session memory (localStorage) and history list formatting.
;; Pure key building / parsing lives here so the exact contracts stay
;; unit-testable; the localStorage IO is nil-safe and injectable.
;; -------------------------------------------------------------------------

(def ^:private session-storage-prefix "penpot-assistant-session")

(defn session-storage-key
  "Builds the localStorage key under which the assistant tab remembers
  the last session of a (profile, file) pair: the file dimension keeps
  the conversations of different designs apart, the profile dimension
  keeps different Penpot accounts apart on a shared browser. Public so
  the exact key format can be unit-tested."
  [profile-id file-id]
  (str session-storage-prefix ":" (str profile-id) ":" (str file-id)))

(defn- resolve-session-storage
  "The localStorage of the current window, or nil when unavailable
  (node tests, privacy modes, storage that throws on access)."
  []
  (try (unchecked-get ug/window "localStorage")
       (catch :default _ nil)))

(defn load-saved-session-id
  "Returns the remembered session id for the (profile, file) pair, or
  nil when nothing (or nothing usable) is stored. `storage` is
  injectable for tests and defaults to the window localStorage."
  ([profile-id file-id]
   (load-saved-session-id profile-id file-id (resolve-session-storage)))
  ([profile-id file-id storage]
   (when (some? storage)
     (try (let [value (.getItem storage (session-storage-key profile-id file-id))]
            (when (and (string? value) (pos? (count value))) value))
          (catch :default _ nil)))))

(defn save-session-id!
  "Remembers `session-id` as the current session of the (profile, file)
  pair. Never throws: a quota or privacy failure only loses the
  memory, the live session is unaffected."
  ([profile-id file-id session-id]
   (save-session-id! profile-id file-id session-id (resolve-session-storage)))
  ([profile-id file-id session-id storage]
   (when (some? storage)
     (try (.setItem storage (session-storage-key profile-id file-id) (str session-id))
          (catch :default _ nil)))))

(defn clear-saved-session-id!
  "Forgets the remembered session of the (profile, file) pair."
  ([profile-id file-id]
   (clear-saved-session-id! profile-id file-id (resolve-session-storage)))
  ([profile-id file-id storage]
   (when (some? storage)
     (try (.removeItem storage (session-storage-key profile-id file-id))
          (catch :default _ nil)))))

(defn- valid-date?
  [date]
  (and (some? date) (not (js/isNaN (.getTime ^js date)))))

(defn parse-sdk-timestamp
  "Parses an SDK `updatedAt`/`createdAt` string into a js/Date, or nil.
  The host docs call the field a timestamp without naming the unit, so
  numeric strings are read as epoch seconds when their magnitude fits
  the 10 digit era and as epoch millis otherwise; non numeric strings
  go through the Date constructor. Public so the heuristic can be
  unit-tested."
  [value]
  (when (and (string? value) (pos? (count value)))
    (let [number (js/Number value)]
      (if (js/isFinite number)
        (let [millis (if (and (pos? number) (< number 100000000000))
                       (* number 1000)
                       number)
              date  (js/Date. millis)]
          (when (valid-date? date) date))
        (let [date (try (js/Date. value) (catch :default _ nil))]
          (when (valid-date? date) date))))))

(defn format-session-time
  "Formats an SDK timestamp string for the history list with the fixed,
  never localized `yyyy-MM-dd HH:mm` shape agreed in the v2
  requirements. Public so the shape can be unit-tested."
  [value]
  (when-let [date (parse-sdk-timestamp value)]
    (try (ct/format-inst date "yyyy-MM-dd HH:mm")
         (catch :default _ nil))))
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend && pnpm run test:quiet -- --focus frontend-tests.util-embed-assistant-test --log-level warn
```

Expected: PASS（既有测试 + 新增 8 个 deftest 全绿）。

- [ ] **Step 5: lint + fmt**

```bash
./scripts/lint && ./scripts/check-fmt
```

Expected: 无新增告警/格式问题（有则 `./scripts/fmt` 修复后复跑）。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/util/embed/assistant.cljs frontend/test/frontend_tests/util_embed_assistant_test.cljs
git commit -s -m ":sparkles: Add session memory and time helpers to assistant embed layer" -m "localStorage-backed (profile, file) session memory with injectable storage, plus the fixed yyyy-MM-dd HH:mm formatter for SDK timestamps, as pure functions of app.util.embed.assistant." -m "AI-assisted-by: Claude Code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: mock SDK 升级到 v2 会话 API

**Files:**
- Modify: `frontend/resources/assistant/mock-assistant-sdk.js`

**注意：** 纯 JS 开发基础设施，无单测框架；语法用 `node --check` 验证，行为在 Task 5 手动走查。watch 已监听 `resources/assistant`（v1 接入），改动自动拷贝到 `resources/public/assistant`。

- [ ] **Step 1: 更新头部注释**

替换文件头部注释（第 2-26 行）中的 `Shape agreed` 块——将：

```js
 *   X_Assistant.on/off         minimal event registry for future use
 *   X_Assistant.getTools()     one or two mock tool descriptors
```

改为：

```js
 *   X_Assistant.on/off         minimal event registry; the SDK emits
 *                              "ai:createNewSession" when a new chat
 *                              gets its first user message
 *   X_Assistant.getTools()     v2 session contract:
 *                              {jsSdk: {getSessionList, deleteSession},
 *                               changeSessionId(id)} — sessions are
 *                              persisted in sessionStorage so they
 *                              survive the tab switch destroy/re-init
 *                              cycle; changeSessionId("") starts a new
 *                              chat, an unknown id throws so Penpot's
 *                              restore-failure fallback is exercisable
```

- [ ] **Step 2: 加会话存储基础函数**

在 `var listeners = {};`（约第 39 行）之后插入：

```js
  // ---- v2 session store ------------------------------------------------
  //
  // Sessions live in sessionStorage so they survive the tab switch /
  // SDK destroy / re-init cycle (Penpot destroys window.X_Assistant
  // when the tab is left) — exactly what the restore flow needs to be
  // developable against the mock. The *current* session is runtime
  // state only: the SDK starts without one and Penpot drives it
  // through changeSessionId.
  var SESSIONS_KEY = "mockAssistantSessions";
  var currentSession = null;

  function loadSessions() {
    try {
      var raw = sessionStorage.getItem(SESSIONS_KEY);
      var parsed = raw ? JSON.parse(raw) : [];
      return Array.isArray(parsed) ? parsed : [];
    } catch (cause) {
      return [];
    }
  }

  function storeSessions(sessions) {
    try {
      sessionStorage.setItem(SESSIONS_KEY, JSON.stringify(sessions));
    } catch (cause) {
      // quota or privacy mode: keep working with the in-memory copy
    }
  }

  function replaceSession(session) {
    storeSessions(
      loadSessions().map(function (candidate) {
        return candidate.uid === session.uid ? session : candidate;
      }),
    );
  }

  function newSessionId() {
    return (
      "mock-session-" +
      Date.now().toString(36) +
      "-" +
      Math.random().toString(36).slice(2, 6)
    );
  }

  function emit(name, data) {
    (listeners[name] || []).forEach(function (fn) {
      try {
        fn(data);
      } catch (cause) {
        // one broken consumer must not break the others
      }
    });
  }

  function sortSessions(sessions) {
    return sessions.slice().sort(function (a, b) {
      return String(b.updatedAt).localeCompare(String(a.updatedAt));
    });
  }
```

- [ ] **Step 3: 新增 Session lifecycle UI section + 当前会话渲染**

在 `makeLogSection` 函数定义之前插入两个新函数：

```js
  function renderCurrentSession() {
    if (!mountElement) {
      return;
    }
    var box = mountElement.querySelector(".xmock-current");
    if (!box) {
      return;
    }
    box.textContent = currentSession
      ? "current session\n  uid   : " +
        currentSession.uid +
        "\n  title : " +
        currentSession.title
      : "current session\n  (none — a new chat waits for its first message)";
  }

  function makeSessionSection() {
    var section = makeSection("Session lifecycle (v2)");

    var row = el("div", "xmock-row");
    var input = document.createElement("input");
    input.setAttribute("type", "text");
    input.setAttribute("placeholder", "first message / session title…");

    var sendUserMessage = makeTriggerButton("user message", function () {
      var content = input.value.trim() || "Mock first message";
      if (!currentSession) {
        currentSession = {
          uid: newSessionId(),
          title: content.slice(0, 40),
          createdAt: String(nowTs()),
          updatedAt: String(nowTs()),
          domain: "design",
          pinned: false,
          projectName: "Penpot mock",
          projectUid: "mock-project",
          sapUserId: "mock-user",
          status: "SESSION_STATUS_STOPPED",
          userId: "mock-user",
        };
        var sessions = loadSessions();
        sessions.push(currentSession);
        storeSessions(sessions);
        emit("ai:createNewSession", { sessionId: currentSession.uid });
        logLine("ai:createNewSession <- " + currentSession.uid);
      } else {
        currentSession.updatedAt = String(nowTs());
        replaceSession(currentSession);
      }
      runTrigger(hook("afterSendMessage"), "afterSendMessage", {
        messageId: "mock-msg-" + ++messageCounter,
        role: "user",
        content: content,
        sentAt: nowTs(),
      });
      renderCurrentSession();
    });

    var startNewChat = makeTriggerButton("changeSessionId('')", function () {
      // exactly what the Penpot "new chat" button does
      window.X_Assistant.getTools().changeSessionId("");
      logLine("changeSessionId('') — new chat armed");
    });

    row.appendChild(input);
    row.appendChild(sendUserMessage);
    row.appendChild(startNewChat);
    section.appendChild(row);

    var current = el("pre", "xmock-summary xmock-current");
    section.appendChild(current);
    return section;
  }
```

- [ ] **Step 4: 挂进 render()**

`render` 函数中，在 `root.appendChild(makeSummary());` 之后加一行：

```js
      root.appendChild(makeSessionSection());
```

并在 `mountElement.appendChild(root);` 之后、`logLine("mock SDK rendered ...")` 之前加：

```js
      renderCurrentSession();
```

- [ ] **Step 5: 替换 getTools 为 v2 契约**

将整个 `getTools: function () { ... },`（返回工具描述符数组的旧实现）替换为：

```js
    getTools: function () {
      // v2 host SDK contract: {jsSdk, changeSessionId}
      return {
        jsSdk: {
          getSessionList: function () {
            var sessions = sortSessions(loadSessions());
            return Promise.resolve({
              total: String(sessions.length),
              sessions: sessions,
            });
          },
          deleteSession: function (sessionId) {
            storeSessions(
              loadSessions().filter(function (session) {
                return session.uid !== sessionId;
              }),
            );
            if (currentSession && currentSession.uid === sessionId) {
              currentSession = null;
            }
            renderCurrentSession();
            return Promise.resolve(true);
          },
        },
        changeSessionId: function (sessionId) {
          if (sessionId === "" || sessionId == null) {
            // brand new session: it only gets an identity (and the
            // ai:createNewSession event) with its first user message
            currentSession = null;
            renderCurrentSession();
            return;
          }
          var found = loadSessions().find(function (session) {
            return session.uid === sessionId;
          });
          if (!found) {
            // deliberate: lets Penpot exercise its restore-failure
            // fallback path against the mock
            throw new Error(
              "mock-assistant-sdk: unknown session " + sessionId,
            );
          }
          currentSession = found;
          renderCurrentSession();
        },
      };
    },
```

- [ ] **Step 6: 语法验证**

```bash
node --check frontend/resources/assistant/mock-assistant-sdk.js
```

Expected: 无输出（语法 OK）。

- [ ] **Step 7: Commit**

```bash
git add frontend/resources/assistant/mock-assistant-sdk.js
git commit -s -m ":hammer: Upgrade the mock assistant SDK to the v2 session contract" -m "getTools() now returns {jsSdk: {getSessionList, deleteSession}, changeSessionId}; sessions persist in sessionStorage across the tab destroy/re-init cycle; a first user message creates a session and emits ai:createNewSession; an unknown changeSessionId id throws so the restore-failure fallback is exercisable." -m "AI-assisted-by: Claude Code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: assistant.cljs 会话恢复 + createNewSession 监听 + SDK 工具桥

**Files:**
- Modify: `frontend/src/app/main/ui/workspace/sidebar/assistant.cljs`

**无组件单测框架（仓库现状）：本任务以 lint/编译 + Task 5 手动走查验证；逻辑已尽量下沉 Task 1。**

- [ ] **Step 1: 更新 ns docstring 与 require**

ns docstring（第 8-17 行）末尾一句改为：

```clojure
  Leaving the tab (or hitting retry) destroys the SDK, removes the
  script node, clears `window.X_Assistant` and closes the connection.
  Since v2 the last session of the (profile, file) pair is remembered
  in localStorage: re-entering the tab restores it through
  changeSessionId instead of always starting a new one."
```

require 块加两行（保持字母序：`app.main.refs` 在 `app.main.ui.ds.buttons.button` 之前；`app.main.ui.hooks` 在 ds 之后）：

```clojure
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.hooks :as hooks]
   [app.main.refs :as refs]
```

即完整块变为（只展示新增行位置）：

```clojure
  (:require
   [app.common.logging :as log]
   [app.config :as cf]
   [app.main.refs :as refs]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.hooks :as hooks]
   ...)
```

- [ ] **Step 2: 加 `extract-sdk-tools` 私有函数**

在 `adopt-sdk-result` 函数之后插入：

```clojure
(defn- extract-sdk-tools
  "Extracts the v2 session management surface from the host SDK global
  (`getTools() -> {:jsSdk {:getSessionList :deleteSession}
  :changeSessionId}`), every member nil checked and bound to its owner
  object so `this` survives the extraction. An SDK without the v2
  surface yields a map of nils — the v2 UI degrades to hidden instead
  of throwing."
  [^js sdk]
  (when (object? sdk)
    (let [tools (when (fn? (obj/get sdk "getTools"))
                  (try (.getTools sdk) (catch :default _ nil)))
          bind  (fn [owner name]
                  (let [method (when (object? owner) (obj/get owner name))]
                    (when (fn? method) (.bind method owner))))]
      (when (object? tools)
        (let [js-sdk (obj/get tools "jsSdk")]
          {:change-session-id! (bind tools "changeSessionId")
           :get-session-list!  (bind js-sdk "getSessionList")
           :delete-session!    (bind js-sdk "deleteSession")})))))
```

- [ ] **Step 3: 组件新增 state/refs**

`assistant-tab*` 的 let 绑定中，在 `attempt  (deref attempt*)` 之后加：

```clojure
        ;; v2: id of the session currently shown in the chat area (nil =
        ;; a fresh session whose first message has not produced an id
        ;; yet). Mirrors the localStorage memory of this (profile,
        ;; file) pair.
        session-id* (mf/use-state nil)

        ;; v2: the {:change-session-id! :get-session-list!
        ;; :delete-session!} bridge while the session is alive; nil
        ;; outside the ready state.
        sdk-tools* (mf/use-state nil)

        profile    (mf/deref refs/profile)
        profile-id (some-> profile :id str)

        profile-id-ref (hooks/use-update-ref profile-id)
```

（`page-id-ref`/`file-id-ref` 既有，保持不动。）

- [ ] **Step 4: effect 内新增闭包（restore/ready/监听）**

`mf/use-effect` 内部 let（`forward!` 定义之后、`init-and-render!` 之前）插入：

```clojure
             create-session-handler
             (fn [data]
               (when-not @cancelled?
                 (let [session-id (when (object? data) (obj/get data "sessionId"))]
                   (when (and (string? session-id) (pos? (count session-id)))
                     (assistant/save-session-id!
                      (mf/ref-val profile-id-ref) (mf/ref-val file-id-ref) session-id)
                     (reset! session-id* session-id)))))

             ready!
             (fn [^js sdk]
               (reset! sdk-tools* (extract-sdk-tools sdk))
               (when-let [on (when (object? sdk) (obj/get sdk "on"))]
                 (when (fn? on)
                   (try
                     (on "ai:createNewSession" create-session-handler)
                     (catch :default cause
                       (log/wrn :hint "assistant: could not subscribe to the SDK session events"
                                :cause cause)))))
               ;; Only now, with init, render and (when applicable) the
               ;; session restore settled, is the assistant really up.
               (post-event! "initialized" #js {"timestamp" (now-ts)})
               (advance! :ready))

             restore-and-ready!
             (fn [^js sdk]
               (let [saved-id (assistant/load-saved-session-id
                               (mf/ref-val profile-id-ref)
                               (mf/ref-val file-id-ref))
                     change! (:change-session-id! (extract-sdk-tools sdk))]
                 (reset! session-id* saved-id)
                 (if (or (nil? saved-id) (nil? change!))
                   (ready! sdk)
                   (-> (try (adopt-sdk-result (change! saved-id))
                            (catch :default cause (p/rejected cause)))
                       (p/then (fn [_] (ready! sdk)))
                       (p/catch
                        (fn [cause]
                          (log/wrn :hint "assistant: could not restore the last session, starting a new one"
                                   :cause cause)
                          (assistant/clear-saved-session-id!
                           (mf/ref-val profile-id-ref) (mf/ref-val file-id-ref))
                          (reset! session-id* nil)
                          (try (change! "") (catch :default _ nil))
                          (ready! sdk)))))))
```

- [ ] **Step 5: 接线 init-and-render! 与 effect 起止**

5a. `init-and-render!` 内 promise 链的最后一个 `(p/then (fn [_] ...))`（原为 post-event + advance）整体替换为：

```clojure
                       (p/then (fn [_]
                                 (restore-and-ready! sdk)))
```

（原注释「Only now, with both init and render settled…」删除——语义已移入 `ready!` 的 docstring 语境。）

5b. effect 体开始处 `(advance! :handshaking)` 之前加两行重置（retry 场景防陈旧桥）：

```clojure
         (reset! sdk-tools* nil)
         (reset! session-id* nil)
         (advance! :handshaking)
```

- [ ] **Step 6: cleanup 摘除监听**

effect 返回的 cleanup fn 中，`(let [sdk (unchecked-get ug/global sdk-global-name)]` 的 `(when (object? sdk)` 分支体内、`destroy` 调用**之前**插入：

```clojure
               (let [off (obj/get sdk "off")]
                 (when (fn? off)
                   (try
                     (off "ai:createNewSession" create-session-handler)
                     (catch :default _ nil))))
```

- [ ] **Step 7: 编译 + lint 验证**

```bash
./scripts/lint && ./scripts/check-fmt
```

Expected: 无新增告警。（如误断括号，先跑 `bb tools/paren-repair.bb <file>` 再 lint。）

- [ ] **Step 8: 既有单测回归（确认通信层未被破坏）**

```bash
cd frontend && pnpm run test:quiet -- --focus frontend-tests.util-embed-assistant-test --log-level warn
```

Expected: PASS。

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/main/ui/workspace/sidebar/assistant.cljs
git commit -s -m ":sparkles: Restore the last assistant session and track create-session events" -m "After init+render the tab now restores the localStorage-remembered session of the (profile, file) pair through changeSessionId, falls back to a new chat when the restore fails, and subscribes to ai:createNewSession to keep the memory current. The v2 getTools surface is extracted once per session behind nil-checked bound fns." -m "AI-assisted-by: Claude Code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: 视图 UI —— 操作行 / 历史视图 / i18n / SCSS

**Files:**
- Modify: `frontend/src/app/main/ui/workspace/sidebar/assistant.cljs`
- Modify: `frontend/src/app/main/ui/workspace/sidebar/assistant.scss`
- Modify: `frontend/translations/en.po`
- Modify: `frontend/translations/zh_CN.po`

- [ ] **Step 1: i18n — en.po**

`frontend/translations/en.po`：在 `msgid "workspace.options.assistant.error.sdk"` 条目之后插入（保持字母序：error.sdk < history < history.empty < history.error < loading）：

```
#: src/app/main/ui/workspace/sidebar/assistant.cljs
msgid "workspace.options.assistant.history"
msgstr "History"

#: src/app/main/ui/workspace/sidebar/assistant.cljs
msgid "workspace.options.assistant.history.empty"
msgstr "No conversations yet"

#: src/app/main/ui/workspace/sidebar/assistant.cljs
msgid "workspace.options.assistant.history.error"
msgstr "Failed to load the conversation history."
```

在 `msgid "workspace.options.assistant.loading"` 条目之后插入：

```
#: src/app/main/ui/workspace/sidebar/assistant.cljs
msgid "workspace.options.assistant.new-session"
msgstr "New chat"
```

- [ ] **Step 2: i18n — zh_CN.po**

`frontend/translations/zh_CN.po` 同位置（error.sdk 之后 / loading 之后）插入：

```
#: src/app/main/ui/workspace/sidebar/assistant.cljs
msgid "workspace.options.assistant.history"
msgstr "历史对话"

#: src/app/main/ui/workspace/sidebar/assistant.cljs
msgid "workspace.options.assistant.history.empty"
msgstr "暂无历史对话"

#: src/app/main/ui/workspace/sidebar/assistant.cljs
msgid "workspace.options.assistant.history.error"
msgstr "历史对话加载失败"
```

```
#: src/app/main/ui/workspace/sidebar/assistant.cljs
msgid "workspace.options.assistant.new-session"
msgstr "新对话"
```

- [ ] **Step 3: require 图标 ns**

`assistant.cljs` require 块加（字母序：`app.main.refs` < `app.main.ui.ds.buttons.button` < `app.main.ui.ds.foundations.assets.icon` < `app.main.ui.hooks`）：

```clojure
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
```

- [ ] **Step 4: 组件新增视图 state + handlers**

4a. let 绑定中、`on-retry` 之前加：

```clojure
        ;; v2: which of the two mutually exclusive views the tab content
        ;; shows — :chat (SDK mount + action bar) or :history.
        view* (mf/use-state :chat)
        view  (deref view*)

        ;; v2: history list fetch state — nil (never fetched), :loading,
        ;; :error or [:ok sessions-js-array]. history-attempt bumps on
        ;; retry and after a delete; the fetch effect reruns on it.
        history*        (mf/use-state nil)
        history-attempt* (mf/use-state 0)
```

4b. `on-retry` 之后加 handlers：

```clojure
        on-back-to-chat!
        (mf/use-fn (fn [] (reset! view* :chat)))

        on-open-history!
        (mf/use-fn (fn [] (reset! view* :history)))

        on-history-retry!
        (mf/use-fn (fn [] (swap! history-attempt* inc)))

        on-new-session!
        (mf/use-fn
         (fn []
           (when-let [change! (:change-session-id! (deref sdk-tools*))]
             (try
               (change! "")
               (assistant/clear-saved-session-id!
                (mf/ref-val profile-id-ref) (mf/ref-val file-id-ref))
               (reset! session-id* nil)
               (catch :default cause
                 (log/wrn :hint "assistant: failed to start a new session" :cause cause))))))

        on-select-session!
        (mf/use-fn
         (fn [session-id]
           (when-let [change! (:change-session-id! (deref sdk-tools*))]
             (try
               (change! session-id)
               (assistant/save-session-id!
                (mf/ref-val profile-id-ref) (mf/ref-val file-id-ref) session-id)
               (reset! session-id* session-id)
               (reset! view* :chat)
               (catch :default cause
                 (log/wrn :hint "assistant: failed to switch session" :cause cause))))))

        on-delete-session!
        (mf/use-fn
         (fn [session-id]
           (when-let [delete! (:delete-session! (deref sdk-tools*))]
             (-> (try (adopt-sdk-result (delete! session-id))
                      (catch :default cause (p/rejected cause)))
                 (p/then
                  (fn [_]
                    (when (= session-id (deref session-id*))
                      ;; deleting the session that is on screen falls
                      ;; back to a brand new one
                      (assistant/clear-saved-session-id!
                       (mf/ref-val profile-id-ref) (mf/ref-val file-id-ref))
                      (reset! session-id* nil)
                      (when-let [change! (:change-session-id! (deref sdk-tools*))]
                        (try (change! "") (catch :default _ nil))))
                    ;; always refresh the list after a delete
                    (swap! history-attempt* inc)))
                 (p/catch
                  (fn [cause]
                    (log/wrn :hint "assistant: failed to delete the session" :cause cause)
                    ;; still refresh: the list may be stale
                    (swap! history-attempt* inc))))))
```

- [ ] **Step 5: 历史列表拉取 effect**

在主 `mf/use-effect` 之后追加第二个 effect：

```clojure
    ;; v2: every entry into the history view fetches the list fresh
    ;; (spec: no caching); retry and post-delete re-fetches bump
    ;; history-attempt.
    (mf/use-effect
     (mf/deps view attempt (deref history-attempt*))
     (fn []
       (when (= :history view)
         (let [cancelled? (volatile! false)]
           (reset! history* :loading)
           (let [{:keys [get-session-list!]} (deref sdk-tools*)]
             (if (nil? get-session-list!)
               (reset! history* :error)
               (-> (try (adopt-sdk-result (get-session-list!))
                        (catch :default cause (p/rejected cause)))
                   (p/then (fn [^js result]
                             (when-not @cancelled?
                               (let [sessions (when (object? result)
                                                (obj/get result "sessions"))]
                                 (reset! history* [:ok (when (array? sessions) sessions)])))))
                   (p/catch (fn [cause]
                              (when-not @cancelled?
                                (log/wrn :hint "assistant: failed to load the session history"
                                         :cause cause)
                                (reset! history* :error))))))
           (fn []
             (vreset! cancelled! true))))))
```

- [ ] **Step 6: 重写 render 体**

将 render 部分（`(let [[_ reason :as error] ...` 到文件末尾的 `]])))` 整块）替换为：

```clojure
    (let [[_ reason :as error] (when (vector? state) state)
          history  (deref history*)
          sessions (when (vector? history) (second history))]
      [:div {:class (stl/css :assistant-tab)}
       (if (= :history view)
         ;; history header row replaces the action bar entirely
         [:div {:class (stl/css :history-head)
                :role "button"
                :tab-index "0"
                :on-click on-back-to-chat!}
          [:> icon* {:icon-id i/arrow-left :size "s"}]
          [:span {:class (stl/css :history-head-label)}
           (tr "workspace.options.assistant.history")]]
         (when (and (= :ready state) (some? (deref sdk-tools*)))
           [:div {:class (stl/css :action-bar)}
            [:button {:type "button"
                      :class (stl/css :action-btn)
                      :on-click on-new-session!}
             [:> icon* {:icon-id i/comments :size "s"}]
             (tr "workspace.options.assistant.new-session")]
            [:button {:type "button"
                      :class (stl/css :action-btn)
                      :on-click on-open-history!}
             [:> icon* {:icon-id i/history :size "s"}]
             (tr "workspace.options.assistant.history")]]))

       ;; The SDK mount div is NEVER conditionally rendered: the host
       ;; SDK owns its DOM and a React remount would destroy it. It is
       ;; only hidden while the history view is on.
       [:div {:id mount-dom-id
              :class (cond-> (stl/css :mount)
                       (= :history view)
                       (str " " (stl/css :mount-hidden)))}]

       (when (= :history view)
         (cond
           (= :loading history)
           [:div {:class (stl/css :history-state)}
            [:span {:class (stl/css :spinner)}]]

           (= :error history)
           [:div {:class (stl/css :history-state)}
            [:div {:class (stl/css :status-text)}
             (tr "workspace.options.assistant.history.error")]
            [:> button* {:variant "secondary"
                         :on-click on-history-retry!}
             (tr "labels.retry")]]

           (or (nil? sessions) (zero? (alength sessions)))
           [:div {:class (stl/css :history-state)}
            [:div {:class (stl/css :status-text)}
             (tr "workspace.options.assistant.history.empty")]]

           :else
           [:div {:class (stl/css :history-list)}
            (for [^js session sessions]
              (let [uid   (obj/get session "uid")
                    title (obj/get session "title")]
                [:div {:key uid
                       :class (stl/css :history-item)
                       :on-click #(on-select-session! uid)}
                 [:span {:class (stl/css :history-item-title)}
                  (if (and (string? title) (pos? (count title)))
                    title
                    (tr "workspace.options.assistant.new-session"))]
                 [:span {:class (stl/css :history-item-time)}
                  (assistant/format-session-time (obj/get session "updatedAt"))]
                 [:button {:type "button"
                           :class (stl/css :history-item-delete)
                           :on-click (fn [event]
                                       (dom/stop-propagation event)
                                       (on-delete-session! uid))}
                  [:> icon* {:icon-id i/remove :size "s"}]]]))]))

       (if error
         [:div {:class (stl/css :status)}
          [:div {:class (stl/css :status-text)}
           (tr (get error->i18n-key reason "workspace.options.assistant.error.sdk"))]
          [:> button* {:variant "secondary"
                       :on-click on-retry}
           (tr "labels.retry")]]

         (when (not= :ready state)
           [:div {:class (stl/css :status)}
            [:span {:class (stl/css :spinner)}]
            [:div {:class (stl/css :status-text)}
             (tr "workspace.options.assistant.loading")]]))])))
```

（后两个 overlay 分支与现状相同，只是因插入历史视图而需整体在新结构里。）

- [ ] **Step 7: SCSS 追加**

`frontend/src/app/main/ui/workspace/sidebar/assistant.scss` 末尾（`@keyframes assistant-spin` 之前或之后均可，建议 `.status` 块之前插入视图相关类）追加：

```scss
// v2: chat view action bar (IMG_4245) — flat icon+text buttons, left
// aligned, no background and no border.
.action-bar {
  display: flex;
  align-items: center;
  gap: var(--sp-m);
  padding: 6px 10px 0;
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 0;
  border: none;
  background: none;
  color: var(--color-foreground-secondary);
  font: inherit;
  cursor: pointer;

  &:hover {
    color: var(--color-foreground-primary);
  }
}

// The SDK mount is hidden (never unmounted) while history is on.
.mount-hidden {
  display: none;
}

// v2: history view header (IMG_4244) — back arrow + title, whole row
// clickable.
.history-head {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  font-weight: 600;
  color: var(--color-foreground-primary);
  cursor: pointer;
}

.history-head-label {
  white-space: nowrap;
}

.history-state {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sp-m);
  padding: var(--sp-m);
}

.history-list {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 6px 10px;
  overflow: auto;
}

.history-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 6px;
  border-radius: $br-4;
  cursor: pointer;

  &:hover {
    background: var(--color-background-tertiary);
  }
}

.history-item-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-foreground-primary);
}

.history-item-time {
  flex: none;
  color: var(--color-foreground-secondary);
}

.history-item-delete {
  display: none;
  flex: none;
  padding: 2px;
  border: none;
  background: none;
  color: var(--color-foreground-secondary);
  cursor: pointer;

  &:hover {
    color: var(--color-foreground-primary);
  }
}

// hover swaps the timestamp for the delete action (spec)
.history-item:hover {
  .history-item-time {
    display: none;
  }

  .history-item-delete {
    display: inline-flex;
  }
}
```

- [ ] **Step 8: 编译 + lint + 单测回归**

```bash
./scripts/lint && ./scripts/check-fmt
cd frontend && pnpm run test:quiet -- --focus frontend-tests.util-embed-assistant-test --log-level warn
```

Expected: lint 无新增告警；测试 PASS。（SCSS 为既有文件改动，watch 会正常增量编译，无 sprite 盲区。）

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/main/ui/workspace/sidebar/assistant.cljs frontend/src/app/main/ui/workspace/sidebar/assistant.scss frontend/translations/en.po frontend/translations/zh_CN.po
git commit -s -m ":sparkles: Add new-chat and history views to the assistant tab" -m "A chat-view action bar (new chat / history, IMG_4245 style) and a history view (back header + session list, IMG_4244 style) switch inside the tab content; the SDK mount div is only ever hidden, never remounted. The list shows title and yyyy-MM-dd HH:mm updatedAt, swaps the timestamp for a direct-delete button on hover, refreshes on every entry and after deletes, and falls back to a new chat when the deleted session is the one on screen. i18n: en + zh_CN only." -m "AI-assisted-by: Claude Code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: 回归 + 手动走查（devenv / mock SDK）

**Files:** 无代码改动（仅验证；发现问题回上游 Task 修）。

- [ ] **Step 1: 全量单测 + lint**

```bash
cd frontend && pnpm run test:quiet --log-level warn
cd .. && ./scripts/lint && ./scripts/check-fmt
```

Expected: 全绿（对比基线：预存失败清单见 `mem:watch-build-error-triage`，不得新增）。

- [ ] **Step 2: 确认 mock SDK 可被伺服**

watch 存活时改动已自动拷贝；验证：

```bash
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8000/penpot/assistant/mock-assistant-sdk.js?v=$RANDOM"
```

Expected: `200`。若 404：容器内跑 `pnpm build:app:assets` 或检查 watch 进程（`docker exec penpot-devenv-ws0-main sh -c 'ps -eo pid,args | grep -E "shadow|watch"'`）。

- [ ] **Step 3: 确认 tab 可见配置**

`frontend/resources/config.js` 的 `penpotXFrontSdk` 为 `/penpot/assistant/mock-assistant-sdk.js`（**watch 重启会重置回 ""，每次重启后要重设**）；硬刷新（Cmd+Shift+R，gz 缓存）打开嵌入的 workspace（`http://localhost:8000/penpot/`）。

- [ ] **Step 4: 走查清单（对照需求文档逐条勾）**

1. 进入 AI助手 tab：mock 渲染；顶部左侧「新对话(气泡)」「历史对话(时钟)」两扁平按钮（IMG_4245 样式）。
2. 「user message」发首条消息 → DevTools Application→localStorage 出现 `penpot-assistant-session:<profile-id>:<file-id>`；mock 打印 `ai:createNewSession`。
3. 再发几条建立第 2 个会话（先点「新对话」或 mock 的 `changeSessionId('')`）→ localStorage 更新为新 id。
4. 「历史对话」→ 返回行（← 历史对话，整行可点）+ 列表（标题左/`YYYY-MM-DD HH:mm` 右，updatedAt 降序）；按钮行消失。
5. hover 列表项：时间消失、删除图标出现；点删除 → 列表立即刷新少一条（无确认弹窗）。
6. 删除**非当前**会话 → 会话区不动。
7. 删除**当前**会话（对照 mock 的 current session 行）→ 切回会话区后是全新会话，localStorage 清空。
8. 点列表项 → 返回会话区，mock current session 变为所选，localStorage 写入。
9. 切到 Design tab 再切回 → mock current session 仍为所记会话（恢复成功）；**刷新整页**再进 → 同样恢复。
10. 失效兜底：DevTools 手改 localStorage 值为 `mock-session-bogus` → 切回 AI助手 tab → 控制台出现 restore 警告、current session 为 none（新会话）、localStorage 被清除。
11. 空态：DevTools 删掉 mock 的 `mockAssistantSessions`（sessionStorage）→ 历史视图显示「暂无历史对话」。
12. 错误态（可选）：console 执行 `window.X_Assistant.getTools = () => ({})` 后重进历史 → 错误文案 + 重试按钮。
13. 「新对话」按钮 → current session 为 none、localStorage 清除、会话区为空新会话。

- [ ] **Step 5: 走查通过后收尾**

无代码改动则无需 commit；若走查发现缺陷，修复后按对应 Task 的格式追加 commit，并复跑 Step 1。

---

## 风险与注意（实施者必读）

1. **Rumext `mf/spread-props` 不得内联**（整页崩溃先例）；本计划未用到，但改 UI 时勿引入。
2. **`dm/assert!` 相关**：本计划不改 `layout.cljs`/`options.cljs`（tab 已存在），无断言风险。
3. **CLJS→JS 边界**：SDK 方法一律 `fn?` 判空 + `try/catch`；`changeSessionId` 对无效 id 的行为联调时与宿主确认（mock 用 throw 模拟）。
4. **`.po` 只改 en + zh_CN**，其余 40 语言回退英文；插入位置保持字母序。
5. **commit 一律 `git commit -s`**（DCO），不 push（用户自行 push）。
6. **勿动 v1 语义以外的东西**：init 参数、postMessage 协议、销毁流程全部维持 v1；本计划只加不改（除 init-and-render! 最后一步接入 restore）。
7. 若 lint 报括号错，先 `bb tools/paren-repair.bb <file>` 再复跑（delimiter 错误会伪装成其他告警）。

## 需求覆盖对照（spec → task）

| 需求（agent-intergration-v2.md） | Task |
|---|---|
| 修正参考图引用 | 无代码（文档已修正） |
| 按钮：新对话/历史对话（IMG_4245 样式） | Task 4 |
| 新对话 changeSessionId('') + 清记忆 | Task 3（桥）/ Task 4（按钮+清除） |
| 历史视图：返回行整行替换 + 列表 | Task 4 |
| getSessionList 每次进入拉取 + 三态 | Task 4 |
| 列表项：标题占位/时间格式 | Task 1（格式化）/ Task 4（渲染） |
| 切换会话 + 记忆 | Task 3 / Task 4 |
| 删除：直接删/刷新/删当前回新会话 | Task 4 |
| 会话记忆 localStorage + 恢复 + 兜底 | Task 1（存储）/ Task 3（恢复+监听） |
| mock SDK v2 契约 | Task 2 |
| 手动验收 | Task 5 |

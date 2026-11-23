# Embed Parent Origin 多 origin 与 none 跳过校验 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `penpotEmbedParentOrigin` / `PENPOT_EMBED_PARENT_ORIGIN` 支持逗号分隔多 origin（含 `*.host` 通配子域，CSP 语义），并让字面值 `none`（前端显式识别）无条件跳过 postMessage origin 校验。

**Architecture:** 配置仍是单字符串（方案 A，向后兼容）。`app.util.embed` 新增 pattern 归一化、配置解析（`:none` | pattern 向量 | nil）、多 pattern 匹配与 target-origins 策略，全部纯函数 + 单测；运行时路径走 `delay` memo。`assistant.cljs` 消重改调 embed 公共 API，post 改为循环 targets。entrypoint 提取共用校验 helper，逐条校验后分别写入 js/config.js（逗号 join）与 CSP frame-ancestors（空格 join，`none` 不影响）。设计 spec：`docs/superpowers/specs/2026-11-23-embed-parent-origin-multi-origin-design.md`。

**Tech Stack:** ClojureScript（cuerdas、cljs.test）、bash（nginx-entrypoint.sh）、shadow-cljs test runner。

---

## 执行环境约定（所有任务适用）

- **在主工作树直接执行**，不开 worktree：devenv 容器把本仓库 bind-mount 在 `/home/penpot/penpot`，watch/测试都对着这棵树（与 2026-11-04/2026-11-09 计划一致）。
- **测试命令（宿主机直跑，绕开 pnpm purge 坑）**：

  ```bash
  cd /Users/slynyeh/Downloads/penpot-2.17.0/frontend
  clojure -M:dev:shadow-cljs compile test
  node target/tests/test.js --focus frontend-tests.util-embed-test --log-level warn
  ```

  或**容器内**（工具链与 CI 同版本，clj-kondo/cljfmt 在容器里）：

  ```bash
  docker exec penpot-devenv-ws0-main sh -c 'cd /home/penpot/penpot/frontend && pnpm run test:quiet -- --focus frontend-tests.util-embed-test --log-level warn'
  ```

- **禁管道截断**（mem:testing）：不用 `| tail`/`| head` 过滤测试输出；要过滤先 `tee` 到文件再 grep。
- **预存失败基线**（与本次无关，勿追）：`frontend_tests.data.keymap-test` 2 处断言失败（Esc vs ⎋）；条件性失败 `wait-boot-opens-on-authenticated-probe`（宿主机未建 wasm 时 TypeError，容器里跑过 wasm 构建即消失）。
- **提交**：只在任务步骤明确要求时提交；格式 `:emoji: Subject ≤70 chars` + 正文 + `AI-assisted-by: claude-code` + `Signed-off-by`（`git commit -s`）+ `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

---

### Task 1: `normalize-origin-pattern`（pattern 归一化）

**Files:**
- Modify: `frontend/src/app/util/embed.cljs`（`normalize-origin` 之后，约 103 行处插入）
- Test: `frontend/test/frontend_tests/util_embed_test.cljs`（`normalize-origin-invalid` 之后插入）

**背景事实（已验证）**：`new URL('https://*.example.com').origin` 返回 `"https://*.example.com"`（node 24 与浏览器 WHATWG URL 一致，`*` 不是 forbidden host code point；host 会被小写化、端口保留）。`new URL('https://*').origin` 返回 `"https://*"`（能解析），所以裸 `*` host 必须用 hostname 正则显式拒绝。

- [ ] **Step 1: 写失败测试** — 在 `util_embed_test.cljs` 的 `normalize-origin-invalid` deftest 之后加：

```clojure
(t/deftest normalize-origin-pattern-accepts-origins-and-wildcards
  (t/is (= "https://portal.example.com"
           (embed/normalize-origin-pattern "https://portal.example.com")))
  (t/is (= "https://portal.example.com"
           (embed/normalize-origin-pattern "https://portal.example.com/")))
  (t/is (= "https://*.example.com"
           (embed/normalize-origin-pattern "https://*.example.com")))
  (t/is (= "https://*.example.com"
           (embed/normalize-origin-pattern "https://*.EXAMPLE.com/")))
  (t/is (= "https://*.example.com:8443"
           (embed/normalize-origin-pattern "https://*.example.com:8443")))
  (t/is (= "http://*.localhost:3001"
           (embed/normalize-origin-pattern "http://*.localhost:3001"))))

(t/deftest normalize-origin-pattern-rejects-malformed
  (t/is (nil? (embed/normalize-origin-pattern nil)))
  (t/is (nil? (embed/normalize-origin-pattern "")))
  (t/is (nil? (embed/normalize-origin-pattern "   ")))
  (t/is (nil? (embed/normalize-origin-pattern "portal.example.com")))
  ;; a bare `*` host is not a pattern; unconditional acceptance is the
  ;; "none" sentinel, never a wildcard pattern
  (t/is (nil? (embed/normalize-origin-pattern "https://*")))
  ;; `*` is only valid as the leading `*.` label
  (t/is (nil? (embed/normalize-origin-pattern "https://a*b.example.com"))))
```

- [ ] **Step 2: 跑测试确认 RED**（编译错误：函数不存在也算失败）

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0/frontend
clojure -M:dev:shadow-cljs compile test
node target/tests/test.js --focus frontend-tests.util-embed-test --log-level warn
```

预期：`normalize-origin-pattern-accepts-origins-and-wildcards` FAIL（`undefined is not a function` 或断言失败）。

- [ ] **Step 3: 实现** — 在 `embed.cljs` 的 `normalize-origin`（103 行附近的 defn）之后插入：

```clojure
(defn normalize-origin-pattern
  "Like `normalize-origin`, but additionally accepts a configured origin
  pattern with a leading `*.` wildcard label in the host. Returns a
  comparable `scheme://[*.]host[:port]` form, or nil when the value is
  missing or is not a valid url with a plain hostname — a bare `*` host
  is not a pattern (unconditional acceptance is the `none` sentinel)
  and `*` is only valid as the leading label. Public so the matching
  rule can be unit-tested."
  [pattern]
  (when (and (string? pattern) (not (str/blank? pattern)))
    (try
      (let [url (js/URL. pattern)]
        (when (and (re-find #"^(\*\.)?[A-Za-z0-9._-]+$" (.-hostname url))
                   (not= (.-origin url) "null"))
          (.-origin url)))
      (catch :default _ nil))))
```

- [ ] **Step 4: 跑测试确认 GREEN**（同 Step 2 命令；预期新增两个 deftest 全过，既有测试不受影响）

- [ ] **Step 5: 提交**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0
git add frontend/src/app/util/embed.cljs frontend/test/frontend_tests/util_embed_test.cljs
git commit -s -m ":sparkles: Add wildcard-aware origin pattern normalization

normalize-origin-pattern accepts configured origins with a leading
*. host label and rejects bare * hosts, so the none sentinel stays
the only unconditional acceptance path.

AI-assisted-by: claude-code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: `parse-origin-config`（配置解析：none / 列表 / 非法条目）

**Files:**
- Modify: `frontend/src/app/util/embed.cljs`（`normalize-origin-pattern` 之后）
- Test: `frontend/test/frontend_tests/util_embed_test.cljs`

- [ ] **Step 1: 写失败测试** — 在 Task 1 的测试后加：

```clojure
(t/deftest parse-origin-config-none-sentinel
  (t/is (= :none (embed/parse-origin-config "none")))
  (t/is (= :none (embed/parse-origin-config " NONE ")))
  (t/is (= :none (embed/parse-origin-config "None"))))

(t/deftest parse-origin-config-parses-lists
  (t/is (= ["https://a.example.com" "https://b.example.com:8443"]
           (embed/parse-origin-config
            "https://a.example.com,https://b.example.com:8443")))
  ;; commas and whitespace are both separators, freely mixed
  (t/is (= ["https://a.example.com" "https://b.example.com"]
           (embed/parse-origin-config
            " https://a.example.com ,  https://b.example.com ")))
  (t/is (= ["https://*.example.com"]
           (embed/parse-origin-config "https://*.example.com,"))))

(t/deftest parse-origin-config-drops-invalid-entries
  ;; invalid entries are dropped individually when at least one is usable
  (t/is (= ["https://a.example.com"]
           (embed/parse-origin-config "https://a.example.com,junk")))
  ;; every entry unusable means unconfigured: the wildcard fallback
  (t/is (nil? (embed/parse-origin-config "junk,https://*")))
  (t/is (nil? (embed/parse-origin-config "")))
  (t/is (nil? (embed/parse-origin-config nil)))
  ;; a mixed "none" token is just an invalid entry, not the sentinel
  (t/is (= ["https://a.example.com"]
           (embed/parse-origin-config "https://a.example.com,none"))))
```

- [ ] **Step 2: 跑测试确认 RED**（命令同 Task 1 Step 2）

- [ ] **Step 3: 实现** — 在 `normalize-origin-pattern` 之后插入：

```clojure
(defn parse-origin-config
  "Parses the raw `penpotEmbedParentOrigin` value. Returns `:none` for
  the trimmed, case-insensitive sentinel (validation disabled), a
  vector of normalized origin patterns when at least one entry is
  usable, or nil when unset/blank or when every entry was unusable
  (wildcard fallback, the historical behavior). Entries are separated
  by commas and/or whitespace; invalid entries are dropped with a
  warning. Public so the parsing rule can be unit-tested."
  [configured]
  (cond
    (not (string? configured)) nil
    (= "none" (str/lower (str/trim configured))) :none
    :else
    (let [tokens  (re-seq #"[^,\s]+" configured)
          dropped (volatile! [])
          parsed  (into []
                        (keep (fn [token]
                                (or (normalize-origin-pattern token)
                                    (do (vswap! dropped conj token) nil))))
                        tokens)]
      (when (seq @dropped)
        (log/wrn :hint "embed: dropping invalid penpotEmbedParentOrigin entries"
                 :entries (str/join "," @dropped)))
      (when (seq parsed) parsed))))
```

注意：cuerdas 的 `lower`/`trim`/`join` 均已可用（文件已 require `cuerdas.core :as str`、`app.common.logging :as log`）。测试输出里出现几条 `dropping invalid ...` wrn 属预期（被测行为本身），不影响断言。

- [ ] **Step 4: 跑测试确认 GREEN**（同前命令）

- [ ] **Step 5: 提交**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0
git add frontend/src/app/util/embed.cljs frontend/test/frontend_tests/util_embed_test.cljs
git commit -s -m ":sparkles: Parse the embed parent origin config value

parse-origin-config turns the raw value into :none, a pattern vector,
or nil (wildcard fallback when nothing survives), accepting comma
and/or whitespace separated entries.

AI-assisted-by: claude-code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: 多 pattern 匹配（`origins-accept?`）+ `origin-accepted?` 重接

**Files:**
- Modify: `frontend/src/app/util/embed.cljs`（替换现有 `origin-accepted?`，并在 `parse-origin-config` 与 `origin-accepted?` 之间插入 helpers）
- Test: `frontend/test/frontend_tests/util_embed_test.cljs`

**关键点：`origin-accepted?` 签名不变**（现有 4 个 deftest 必须继续过，这是向后兼容验收）。

- [ ] **Step 1: 写失败测试** — 追加：

```clojure
(t/deftest origin-accepted-matches-any-listed-origin
  (t/is (true? (embed/origin-accepted?
                "https://a.example.com,https://b.example.com:8443"
                "https://b.example.com:8443")))
  (t/is (false? (embed/origin-accepted?
                 "https://a.example.com,https://b.example.com:8443"
                 "https://a.example.com:8443"))))

(t/deftest origin-accepted-wildcard-subdomains
  ;; CSP host-source style: the wildcard covers the bare domain and
  ;; subdomains at any depth, on the same scheme and, when the pattern
  ;; carries one, the same port.
  (t/is (true? (embed/origin-accepted? "https://*.example.com"
                                       "https://example.com")))
  (t/is (true? (embed/origin-accepted? "https://*.example.com"
                                       "https://a.example.com")))
  (t/is (true? (embed/origin-accepted? "https://*.example.com"
                                       "https://a.b.example.com")))
  (t/is (true? (embed/origin-accepted? "https://*.example.com/"
                                       "https://a.example.com"))))

(t/deftest origin-accepted-wildcard-lookalikes-rejected
  (t/is (false? (embed/origin-accepted? "https://*.example.com"
                                        "https://evil-example.com")))
  (t/is (false? (embed/origin-accepted? "https://*.example.com"
                                        "https://attacker.io")))
  (t/is (false? (embed/origin-accepted? "https://*.example.com"
                                        "http://a.example.com")))
  ;; a pattern without a port never matches an origin with one
  (t/is (false? (embed/origin-accepted? "https://*.example.com"
                                        "https://a.example.com:8443")))
  (t/is (false? (embed/origin-accepted? "https://*.example.com:8443"
                                        "https://a.example.com")))
  (t/is (true? (embed/origin-accepted? "https://*.example.com:8443"
                                       "https://example.com:8443"))))

(t/deftest origin-accepted-none-sentinel-accepts-anything
  (t/is (true? (embed/origin-accepted? "none" "https://anything.example")))
  (t/is (true? (embed/origin-accepted? "NONE" "https://anything.example"))))
```

- [ ] **Step 2: 跑测试确认 RED**

- [ ] **Step 3: 实现** — 在 `embed.cljs` 中，把现有：

```clojure
(defn origin-accepted?
  "Whether `origin`, as reported by a received message, is an acceptable
  parent origin for the `configured` value. An absent or invalid
  configured origin means wildcard, so anything is accepted. Public so the
  matching rule can be unit-tested."
  [configured origin]
  (if-let [expected (normalize-origin configured)]
    ;; Exact comparison on normalized origins. A prefix check would accept
    ;; `https://good.example.attacker.io` for `https://good.example`.
    (= expected (normalize-origin origin))
    true))
```

整体替换为：

```clojure
(defn- split-origin
  "Splits a normalized `scheme://host[:port]` string into
  `[scheme host-with-port]`, or nil for an unexpected shape."
  [origin]
  (when-let [idx (and (string? origin) (str/index-of origin "://"))]
    [(subs origin 0 idx)
     (subs origin (+ idx 3))]))

(defn- wildcard-pattern?
  "Whether `pattern` carries the leading `*.` host label."
  [pattern]
  (some? (str/index-of pattern "://*.")))

(defn- origin-matches-pattern?
  "Whether the normalized `origin` matches the normalized `pattern`.
  Scheme and `host[:port]` compare exactly; a `*.` pattern host
  additionally matches the bare domain and subdomains at any depth.
  The port, when present, is part of the compared remainder and must
  match too — so a pattern without a port never matches an origin
  with one, and vice versa."
  [pattern origin]
  (when-let [[p-scheme p-rest] (split-origin pattern)]
    (when-let [[o-scheme o-rest] (split-origin origin)]
      (and (= p-scheme o-scheme)
           (if (wildcard-pattern? pattern)
             (let [suffix (subs p-rest 2)]
               (or (= suffix o-rest)
                   (str/ends-with? o-rest (str "." suffix))))
             (= p-rest o-rest))))))

(defn origins-accept?
  "Whether `origin`, as reported by a received message, is accepted by
  the parsed configuration `parsed` (`:none` | pattern vector | nil,
  the shape returned by `parse-origin-config`). `:none` and absent or
  unusable configurations accept anything; otherwise the origin must
  match at least one pattern. Public so the matching rule can be
  unit-tested."
  [parsed origin]
  (if (or (= :none parsed) (nil? parsed) (empty? parsed))
    true
    (let [normalized (normalize-origin origin)]
      (boolean (and normalized
                    (some #(origin-matches-pattern? % normalized) parsed))))))

(defn origin-accepted?
  "Whether `origin`, as reported by a received message, is an acceptable
  parent origin for the raw `configured` value. The value is parsed
  with `parse-origin-config` (comma/whitespace separated origin
  patterns; `none` and unusable values mean wildcard) and matched with
  `origins-accept?`. Public so the matching rule can be unit-tested."
  [configured origin]
  (origins-accept? (parse-origin-config configured) origin))
```

- [ ] **Step 4: 跑测试确认 GREEN**（既有 `origin-accepted-exact-match` / `rejects-lookalikes` / `without-configuration` 必须原样通过）

- [ ] **Step 5: 提交**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0
git add frontend/src/app/util/embed.cljs frontend/test/frontend_tests/util_embed_test.cljs
git commit -s -m ":sparkles: Match the embed origin against a pattern list

origins-accept? accepts an origin matching any configured pattern;
*. patterns follow CSP host-source semantics (bare domain plus
subdomains at any depth, exact scheme and port). origin-accepted?
keeps its signature and now parses the raw value first.

AI-assisted-by: claude-code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: `target-origins` 策略 + memoized `origin-config`

**Files:**
- Modify: `frontend/src/app/util/embed.cljs`（`origins-accept?` 之后插入；`wildcard-warned?` 定义保持在原处不动）
- Test: `frontend/test/frontend_tests/util_embed_test.cljs`

- [ ] **Step 1: 写失败测试** — 追加：

```clojure
(t/deftest target-origins-strategy
  ;; wildcard fallbacks: nothing configured, nothing usable, none sentinel
  (t/is (= ["*"] (embed/target-origins nil)))
  (t/is (= ["*"] (embed/target-origins [])))
  (t/is (= ["*"] (embed/target-origins :none)))
  ;; every exact pattern posts once per origin: the browser delivers
  ;; only the message whose target origin equals the real parent's
  (t/is (= ["https://a.example.com" "https://b.example.com:8443"]
           (embed/target-origins
            ["https://a.example.com" "https://b.example.com:8443"])))
  ;; a postMessage targetOrigin cannot express a wildcard host, so any
  ;; wildcard pattern degrades the whole target set to "*"
  (t/is (= ["*"] (embed/target-origins
                  ["https://a.example.com" "https://*.example.com"]))))
```

- [ ] **Step 2: 跑测试确认 RED**

- [ ] **Step 3: 实现** — 在 `origins-accept?` 之后插入：

```clojure
(defonce ^:private parsed-origin-config
  ;; Memoized `parse-origin-config` over the compile-time constant
  ;; `cf/embed-parent-origin`: :none | pattern vector | nil.
  (delay (parse-origin-config cf/embed-parent-origin)))

(defn origin-config
  "The memoized parse of the configured `penpotEmbedParentOrigin`:
  `:none` | pattern vector | nil (unconfigured/unusable). Public so
  the assistant namespace shares one parse and one policy."
  []
  (deref parsed-origin-config))

(defn target-origins
  "Vector of postMessage target origins for the parent window. Exact
  patterns return themselves (one postMessage per origin; the browser
  delivers only the one matching the real parent). The `none` sentinel,
  an absent or unusable configuration, or any wildcard pattern returns
  `[\"*\"]` — a targetOrigin cannot express a wildcard host, and the
  ready/handshake messages carry no credentials. The one argument form
  takes an already parsed config; the zero argument form parses the
  live configuration. Public so the strategy can be unit-tested."
  ([]
   (target-origins (parse-origin-config cf/embed-parent-origin)))
  ([parsed]
   (cond
     (or (= :none parsed) (nil? parsed) (empty? parsed)) ["*"]
     (some wildcard-pattern? parsed) ["*"]
     :else (vec parsed))))
```

- [ ] **Step 4: 跑测试确认 GREEN**

- [ ] **Step 5: 提交**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0
git add frontend/src/app/util/embed.cljs frontend/test/frontend_tests/util_embed_test.cljs
git commit -s -m ":sparkles: Resolve postMessage target origins for a pattern list

Exact patterns post once per origin; a wildcard pattern, the none
sentinel, or an unusable config falls back to a single * target.
origin-config memoizes the parse for runtime paths.

AI-assisted-by: claude-code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: embed.cljs 运行时接线（`origin-allowed?` / `post-ready!`）

**Files:**
- Modify: `frontend/src/app/util/embed.cljs`（替换 171–187 行的 `configured-origin` / `target-origin` / `origin-allowed?`，替换 222–227 行的 `post-ready!`）

纯逻辑已在 Task 1–4 全覆盖；本任务是薄接线层，不新增单测（现有 `wait-boot-*` 测试继续作为回归保障）。

- [ ] **Step 1: 替换私有 origin 函数** — 删除这三段（原 171–187 行）：

```clojure
(defn- configured-origin
  []
  (normalize-origin cf/embed-parent-origin))

(defn- target-origin
  []
  (or (configured-origin) "*"))

(defn- origin-allowed?
  [origin]
  (if (configured-origin)
    (origin-accepted? cf/embed-parent-origin origin)
    (do
      (when-not (deref wildcard-warned?)
        (vreset! wildcard-warned? true)
        (log/wrn :hint "embed: no parent origin configured, accepting credentials from any window"))
      true)))
```

替换为（`none-warned?` 是新的一次性 volatile，与文件顶部已有的 `wildcard-warned?` 平行）：

```clojure
(defonce ^:private none-warned?
  (volatile! false))

(defn- origin-allowed?
  [origin]
  (let [parsed   (origin-config)
        allowed? (origins-accept? parsed origin)]
    (when (and allowed? (or (= :none parsed) (nil? parsed)))
      (if (= :none parsed)
        (when-not (deref none-warned?)
          (vreset! none-warned? true)
          (log/inf :hint "embed: parent origin validation disabled by config (none)"))
        (when-not (deref wildcard-warned?)
          (vreset! wildcard-warned? true)
          (log/wrn :hint "embed: no parent origin configured, accepting credentials from any window"))))
    allowed?))
```

- [ ] **Step 2: 替换 `post-ready!`** — 把原 222–227 行：

```clojure
(defn- post-ready!
  []
  (let [origin (target-origin)]
    (when (= origin "*")
      (log/wrn :hint "embed: posting ready with wildcard target origin"))
    (.postMessage js/parent #js {:scope "penpot/embed" :type "ready"} origin)))
```

替换为：

```clojure
(defn- post-ready!
  []
  (let [parsed  (origin-config)
        targets (target-origins parsed)]
    (when (some #(= "*" %) targets)
      (cond
        (= :none parsed)
        (log/inf :hint "embed: posting ready with a wildcard target origin (validation disabled by config)")

        ;; a wildcard pattern degrades the target set to "*" by design
        ;; (a targetOrigin cannot express a wildcard host) — not a
        ;; misconfiguration, so info, not wrn
        (vector? parsed)
        (log/inf :hint "embed: posting ready with a wildcard target origin (wildcard pattern configured)")

        :else
        (log/wrn :hint "embed: posting ready with a wildcard target origin")))
    (doseq [origin targets]
      (.postMessage js/parent #js {:scope "penpot/embed" :type "ready"} origin))))
```

- [ ] **Step 3: 全量 embed 测试回归 GREEN**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0/frontend
clojure -M:dev:shadow-cljs compile test
node target/tests/test.js --focus frontend-tests.util-embed-test --log-level warn
```

预期：全部通过（wait-boot-* 条件性 wasm 失败见环境约定，容器跑可避免）。

- [ ] **Step 4: 提交**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0
git add frontend/src/app/util/embed.cljs
git commit -s -m ":sparkles: Drive the embed handshake from the parsed origin config

origin-allowed?/post-ready! switch to the memoized pattern config:
multi-origin validation, per-origin ready posts, and a dedicated
info-level log for the none sentinel.

AI-assisted-by: claude-code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: assistant.cljs 消重 + 多目标 post

**Files:**
- Modify: `frontend/src/app/util/embed/assistant.cljs`（ns form 30 行删 `[app.config :as cf]`；483–511 行替换；578–593 行 `post!` 改循环）
- Test: `frontend/test/frontend_tests/util_embed_assistant_test.cljs`

`cf/embed-parent-origin` 的全部三处引用都在被替换块里 → require 必须同步删除，否则 lint 报 unused。

- [ ] **Step 1: 写失败测试** — 在 `util_embed_assistant_test.cljs` 的 `post-event-falls-back-to-wildcard-target-origin` 之后追加：

```clojure
(t/deftest post-event-targets-every-configured-origin
  ;; an exact-only target set addresses one envelope per origin
  (let [io     (fake-io)
        parent (js-obj)
        conn   (assistant/create-conn
                (assoc (conn-opts io parent)
                       :target-origin (fn [] ["https://a.example.com"
                                              "https://b.example.com"])))]
    ((:post-event! conn) "initialized" nil)
    (t/is (= ["https://a.example.com" "https://b.example.com"]
             (mapv :origin @(:posted io))))
    ((:close! conn))))

(t/deftest post-event-wraps-scalar-injected-target
  ;; a scalar override keeps working (one envelope, one origin)
  (let [io     (fake-io)
        parent (js-obj)
        conn   (assistant/create-conn
                (assoc (conn-opts io parent)
                       :target-origin (fn [] "https://a.example.com")))]
    ((:post-event! conn) "initialized" nil)
    (t/is (= ["https://a.example.com"] (mapv :origin @(:posted io))))
    ((:close! conn))))
```

- [ ] **Step 2: 跑测试确认 RED**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0/frontend
clojure -M:dev:shadow-cljs compile test
node target/tests/test.js --focus frontend-tests.util-embed-assistant-test --log-level warn
```

预期：第一个新测试 FAIL（注入的 fn 返回向量被当成单个 origin 传出 → postMessage 参数错误或 origin 断言不符）。

- [ ] **Step 3: 删 require** — ns form 里删除一行 `[app.config :as cf]`（原 30 行）。

- [ ] **Step 4: 替换默认 origin 逻辑** — 把 483–511 行（`origin-allowed?` 与 `resolve-target-origin` 两个 opts 块）：

```clojure
        origin-allowed?
        (or origin-allowed?
            (let [warned? (volatile! false)]
              (fn [origin]
                (if (embed/normalize-origin cf/embed-parent-origin)
                  (embed/origin-accepted? cf/embed-parent-origin origin)
                  (do
                    (when-not @warned?
                      (vreset! warned? true)
                      (log/wrn :hint "assistant: no parent origin configured, accepting messages from any window"))
                    true)))))
```

```clojure
        resolve-target-origin
        (cond
          (string? target-origin)
          (constantly target-origin)

          (some? target-origin)
          target-origin

          :else
          (let [warned? (volatile! false)]
            (fn []
              (or (embed/normalize-origin cf/embed-parent-origin)
                  (do
                    (when-not @warned?
                      (vreset! warned? true)
                      (log/wrn :hint "assistant: no parent origin configured, posting with a wildcard target origin"))
                    "*")))))
```

替换为：

```clojure
        origin-allowed?
        (or origin-allowed?
            (let [warned? (volatile! false)]
              (fn [origin]
                (let [parsed   (embed/origin-config)
                      allowed? (embed/origins-accept? parsed origin)]
                  (when (and allowed?
                             (not (deref warned?))
                             (or (= :none parsed) (nil? parsed)))
                    (vreset! warned? true)
                    (if (= :none parsed)
                      (log/inf :hint "assistant: parent origin validation disabled by config (none)")
                      (log/wrn :hint "assistant: no parent origin configured, accepting messages from any window")))
                  allowed?))))
```

```clojure
        resolve-target-origin
        (cond
          (string? target-origin)
          (let [origin target-origin]
            (constantly [origin]))

          (some? target-origin)
          (let [resolve target-origin]
            (fn []
              (let [resolved (resolve)]
                (if (vector? resolved) resolved [resolved]))))

          :else
          (let [warned? (volatile! false)]
            (fn []
              (let [parsed  (embed/origin-config)
                    targets (embed/target-origins parsed)]
                (when (and (some #(= "*" %) targets) (not (deref warned?)))
                  (vreset! warned? true)
                  (cond
                    (= :none parsed)
                    (log/inf :hint "assistant: parent origin validation disabled by config (none), posting with a wildcard target origin")

                    ;; wildcard pattern configured: "*" is by design, not
                    ;; a misconfiguration — info, not wrn
                    (vector? parsed)
                    (log/inf :hint "assistant: posting with a wildcard target origin (wildcard pattern configured)")

                    :else
                    (log/wrn :hint "assistant: no usable parent origin configured, posting with a wildcard target origin")))
                targets))))
```

注意解构 key 不变（`:origin-allowed? :target-origin` 等），`target-origin` 现在是 opts 里的原始值，绑定名不冲突。

- [ ] **Step 5: `post!` 改循环 targets** — 把 578–593 行 `post!` 的 try 体内一行：

```clojure
            (post-message! message (resolve-target-origin))
```

改为：

```clojure
            (doseq [origin (resolve-target-origin)]
              (post-message! message origin))
```

（try/catch 与注释原样保留。）

- [ ] **Step 6: 跑 assistant 全部测试确认 GREEN**（同 Step 2 命令；`post-event-falls-back-to-wildcard-target-origin`、`conn-opts` 字符串注入路径必须原样通过）

- [ ] **Step 7: 提交**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0
git add frontend/src/app/util/embed/assistant.cljs frontend/test/frontend_tests/util_embed_assistant_test.cljs
git commit -s -m ":recycle: Reuse the embed origin policy in the assistant channel

The assistant default origin-allowed?/target resolution now delegate
to app.util.embed instead of duplicating them, and posts once per
resolved target origin. The app.config require is gone with the last
direct cf/embed-parent-origin use.

AI-assisted-by: claude-code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: nginx-entrypoint.sh 多条目校验 + CSP 列表

**Files:**
- Modify: `docker/images/files/nginx-entrypoint.sh`（新增 `valid_embed_parent_origins` helper；重写 `update_embed_parent_origin` 约 211–244 行、`update_frame_ancestors` 约 263–302 行）

- [ ] **Step 1: 新增 helper** — 紧接在 `update_embed_parent_origin` 定义之前插入：

```sh
valid_embed_parent_origins() {
  # Prints the PENPOT_EMBED_PARENT_ORIGIN entries that pass validation,
  # as a comma separated list (whitespace stripped, trailing slashes
  # removed, invalid entries warned and dropped); prints nothing for an
  # unset value, the "none" sentinel, or a list where nothing survived.
  local raw="${PENPOT_EMBED_PARENT_ORIGIN:-}";
  # strip whitespace so that " none " works and list items may be
  # written with spaces around the commas
  raw="${raw//[[:space:]]/}";

  if [ -z "$raw" ] || [ "${raw,,}" == "none" ]; then
    return;
  fi

  local rest="$raw" entry origin out="";
  while [ -n "$rest" ]; do
    if [[ "$rest" == *,* ]]; then
      entry="${rest%%,*}";
      rest="${rest#*,}";
    else
      entry="${rest}";
      rest="";
    fi

    [ -z "$entry" ] && continue;

    # Entries are interpolated into a double quoted JS string and into
    # the nginx config, so a quote, a backslash or a newline would be
    # an injection. Only accept bare origins, optionally with a leading
    # `*.` wildcard label and/or a trailing slash.
    if [[ ! "$entry" =~ ^https?://(\*\.)?[A-Za-z0-9._:-]+/?$ ]]; then
      echo "nginx-entrypoint: dropping invalid PENPOT_EMBED_PARENT_ORIGIN entry '${entry}'" >&2;
      continue;
    fi

    origin="${entry%/}";
    if [ -z "$out" ]; then
      out="${origin}";
    else
      out="${out},${origin}";
    fi
  done

  [ -n "$out" ] && printf '%s' "$out";
}
```

（注意 `[ -n "$out" ] && printf ...` 是函数最后一句，`out` 为空时函数返回非 0 —— 调用方用命令替换取 stdout，不检查退出码，行为正确。）

- [ ] **Step 2: 重写 `update_embed_parent_origin`** — 整个函数替换为：

```sh
update_embed_parent_origin() {
  # Origin(s) of the third party system embedding the app in an iframe,
  # used both as the postMessage target origin(s) and to validate the
  # origin of the credentials the parent sends back. Accepts a comma
  # separated list of origins; each entry may carry a leading `*.`
  # wildcard label (https://*.example.com). Overrides the js/config.js
  # default. The special value "none" (case insensitive) writes the
  # clearing default: the frontend then skips origin validation
  # altogether, accepting credentials from any window. "none" does NOT
  # change the nginx framing policy.
  if [ -z "${PENPOT_EMBED_PARENT_ORIGIN:-}" ]; then
    return;
  fi

  local raw="${PENPOT_EMBED_PARENT_ORIGIN//[[:space:]]/}";
  if [ "${raw,,}" == "none" ]; then
    printf 'globalThis.penpotEmbedParentOrigin = "";\n' >> "$1";
    return;
  fi

  local origins;
  origins="$(valid_embed_parent_origins)";
  if [ -z "$origins" ]; then
    echo "nginx-entrypoint: PENPOT_EMBED_PARENT_ORIGIN had no valid origin entries; keeping the js/config.js default" >&2;
    return;
  fi

  printf 'globalThis.penpotEmbedParentOrigin = "%s";\n' "$origins" >> "$1";
}
```

- [ ] **Step 3: 重写 `update_frame_ancestors`** — 整个函数替换为：

```sh
update_frame_ancestors() {
  # Let the third party systems that embed the app actually frame it. The
  # bundled `add_header X-Frame-Options SAMEORIGIN` makes the browser refuse
  # cross origin framing, which no amount of frontend handshake can work
  # around, so when parent origins are configured the X-Frame-Options line
  # is swapped for the equivalent CSP directive naming them.
  #
  # Only explicit origins/patterns open this up: unset, empty and "none"
  # keep the SAMEORIGIN default, and a bare wildcard is not expressible on
  # purpose (it would let any site frame the app). `'self'` stays in the
  # list because the app frames itself (rasterizer, render, viewer).
  if [ -z "${PENPOT_EMBED_PARENT_ORIGIN:-}" ]; then
    return;
  fi

  local raw="${PENPOT_EMBED_PARENT_ORIGIN//[[:space:]]/}";
  if [ "${raw,,}" == "none" ]; then
    return;
  fi

  local origins;
  origins="$(valid_embed_parent_origins)";
  if [ -z "$origins" ]; then
    echo "nginx-entrypoint: PENPOT_EMBED_PARENT_ORIGIN had no valid origin entries; keeping X-Frame-Options: SAMEORIGIN" >&2;
    return;
  fi

  # Comma separated entries become space separated CSP host-sources;
  # `*.host` is a native CSP source with the same bare-domain-and-
  # subdomains semantics the frontend matcher implements.
  local sources="${origins//,/ }";
  local policy="add_header Content-Security-Policy \"frame-ancestors 'self' ${sources}\" always;";

  # Rewritten with grep + mv rather than `sed -i`: the in-place flag is not
  # portable across the busybox/BSD/GNU seds this script may run under.
  grep -v '^add_header X-Frame-Options' "$1" > "$1.tmp" || true;
  printf '%s\n' "$policy" >> "$1.tmp";
  mv "$1.tmp" "$1";
}
```

- [ ] **Step 4: 冒烟测试** — 写脚本到宿主机 `/tmp/embed-origin-smoke.sh`：

```bash
SCRIPT=/home/penpot/penpot/docker/images/files/nginx-entrypoint.sh
eval "$(sed -n '/^valid_embed_parent_origins()/,/^}/p; /^update_embed_parent_origin()/,/^}/p; /^update_frame_ancestors()/,/^}/p' "$SCRIPT")"
set -u
fail=0
check() { if [ "$2" == "$3" ]; then echo "PASS $1"; else echo "FAIL $1: expected [$2] got [$3]"; fail=1; fi; }

cfg="$(mktemp)"; hdr="$(mktemp)"
printf 'add_header X-Frame-Options "SAMEORIGIN" always;\n' > "$hdr"

PENPOT_EMBED_PARENT_ORIGIN="https://portal.example.com/" update_embed_parent_origin "$cfg"
check "single-origin" 'globalThis.penpotEmbedParentOrigin = "https://portal.example.com";' "$(cat "$cfg")"

: > "$cfg"
PENPOT_EMBED_PARENT_ORIGIN="https://a.example.com, https://*.b.example.com:8443 ,junk" update_embed_parent_origin "$cfg"
check "list-wildcard-drops-invalid" 'globalThis.penpotEmbedParentOrigin = "https://a.example.com,https://*.b.example.com:8443";' "$(cat "$cfg")"

: > "$cfg"
PENPOT_EMBED_PARENT_ORIGIN=" NONE " update_embed_parent_origin "$cfg"
check "none-clears" 'globalThis.penpotEmbedParentOrigin = "";' "$(cat "$cfg")"

: > "$cfg"
PENPOT_EMBED_PARENT_ORIGIN="junk,alsobad" update_embed_parent_origin "$cfg"
check "all-invalid-keeps-default" "" "$(cat "$cfg")"

PENPOT_EMBED_PARENT_ORIGIN="https://a.example.com,https://*.b.example.com" update_frame_ancestors "$hdr"
check "frame-ancestors-list" "add_header Content-Security-Policy \"frame-ancestors 'self' https://a.example.com https://*.b.example.com\" always;" "$(tail -n 1 "$hdr")"
check "xfo-removed" "0" "$(grep -c '^add_header X-Frame-Options' "$hdr")"

printf 'add_header X-Frame-Options "SAMEORIGIN" always;\n' > "$hdr"
PENPOT_EMBED_PARENT_ORIGIN="none" update_frame_ancestors "$hdr"
check "none-keeps-sameorigin" "1" "$(grep -c '^add_header X-Frame-Options' "$hdr")"

exit $fail
```

在容器里跑（`${raw,,}` 需要 bash≥4，macOS 系统 bash 3.2 不行）：

```bash
docker exec -i penpot-devenv-ws0-main bash < /tmp/embed-origin-smoke.sh
```

预期：6 个 PASS、退出码 0。另跑语法检查：

```bash
docker exec penpot-devenv-ws0-main bash -n /home/penpot/penpot/docker/images/files/nginx-entrypoint.sh
```

预期：无输出、退出码 0。

- [ ] **Step 5: 提交**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0
git add docker/images/files/nginx-entrypoint.sh
git commit -s -m ":sparkles: Accept origin lists in the embed entrypoint wiring

valid_embed_parent_origins validates entries one by one (wildcard
*. labels allowed); js/config.js gets the comma separated list while
frame-ancestors gets the space separated CSP sources. none still
clears the js config and keeps SAMEORIGIN.

AI-assisted-by: claude-code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: 配置注释与文档

**Files:**
- Modify: `frontend/resources/config.js`（105–114 行注释块）
- Modify: `docker/images/files/config.js`（130–139 行注释块，同文案）
- Modify: `docker/images/docker-compose.yaml`（146–162 行 env 注释）
- Modify: `frontend/IFRAME-EMBED.md`（1.1 节、4.1、4.4、4.5、第 5 节）

- [ ] **Step 1: 更新两份 config.js 注释** — 把这段注释（两份文件相同，只在 `以 iframe 方式嵌入…` 起始处定位）：

```js
  // 以 iframe 方式嵌入第三方系统时的父窗口源, 形如 https://portal.example.com。
  // 用途: (1) 向父窗口发 postMessage「就绪」消息时的 targetOrigin;
  //      (2) 校验父窗口回传凭据消息的 event.origin(精确匹配)。
  // 留空则回退 "*", 即任何能触达本页面的窗口都可注入凭据, 生产环境务必配置。
  // 注意: 需与父页面同源, 否则页面会因 nginx 的 X-Frame-Options: SAMEORIGIN 无法被嵌入。
  // Docker 部署: 可用环境变量 PENPOT_EMBED_PARENT_ORIGIN 覆盖
  // (由 nginx-entrypoint.sh 追加赋值, 优先级更高; 设为 none 则清空)。
```

替换为：

```js
  // 以 iframe 方式嵌入第三方系统时的父窗口源, 形如 https://portal.example.com。
  // 用途: (1) 向父窗口发 postMessage「就绪」消息时的 targetOrigin;
  //      (2) 校验父窗口回传凭据消息的 event.origin。
  // 支持逗号分隔的多个 origin(逗号/空白皆可作分隔符), 单条为裸 origin
  // (scheme://host[:port], 可带一个结尾 /)或 *.<domain> 通配子域:
  // https://*.example.com 匹配 example.com 本身及其任意层级子域,
  // scheme 与端口须一致。任意一条通配即以 "*" 为 postMessage 目标
  // (targetOrigin 无法表达通配主机名); 凭据校验仍按逐条精确匹配。
  // 特殊值 none(大小写不敏感)= 无条件跳过校验: 接受任意窗口的凭据,
  // 仅作用于 postMessage 层, 不影响 nginx 的嵌入控制(X-Frame-Options/CSP)。
  // 留空或全部条目非法则回退 "*", 即任何能触达本页面的窗口都可注入凭据
  // (日志 warn 提示未配置), 生产环境务必配置。
  // 注意: 跨源嵌入还需 nginx 放开嵌入控制(设 PENPOT_EMBED_PARENT_ORIGIN
  // 生成 frame-ancestors, 或由外部 nginx 自管), 否则页面无法被嵌入。
  // Docker 部署: 可用环境变量 PENPOT_EMBED_PARENT_ORIGIN 覆盖
  // (由 nginx-entrypoint.sh 逐条校验后追加赋值, 优先级更高;
  // 非法条目丢弃并告警, 全部非法则保留此处默认值; 设为 none 则清空)。
```

- [ ] **Step 2: 更新 compose env 注释** — 把 `# Iframe embedding (fork feature): MUST be set to the origin...` 到 `# PENPOT_EMBED_PARENT_ORIGIN: "https://portal.example.com"` 的整段注释替换为：

```yaml
      # Iframe embedding (fork feature): MUST be set to the origin(s) of the
      # third party system(s) that embed the app, otherwise the handshake
      # keeps the js/config.js default (empty -> wildcard) and accepts
      # credentials from any window. Used as the postMessage target origin(s)
      # for the ready message and to validate the origin of the X-token /
      # X-ClientId credentials the parent sends back, which are then added as
      # request headers to every backend call. Accepts a comma separated list
      # of bare origins, optionally with a trailing slash and/or a leading
      # `*.` wildcard label (https://*.example.com matches the bare domain
      # and any depth of subdomains, same scheme and port); invalid entries
      # are dropped with a warning, and a list where nothing survives keeps
      # the default. Set to "none" to skip postMessage origin validation
      # entirely (does NOT change the framing policy). An empty value counts
      # as unset.
      # Setting it (except "none") also switches the framing policy: nginx
      # replaces X-Frame-Options: SAMEORIGIN with
      # Content-Security-Policy: frame-ancestors 'self' <origin...>, which is
      # what makes the browser render the app in a cross origin iframe at
      # all. Leave it unset to keep SAMEORIGIN (no cross origin framing).
      # PENPOT_EMBED_PARENT_ORIGIN: "https://portal.example.com,https://*.other.example.net"
```

- [ ] **Step 3: 更新 IFRAME-EMBED.md** — 逐处修改：
  1. 1.1 节 yaml 示例（16 行）后补一行多 origin 示例，并把「要求：**裸 origin**…」段替换为：

     ```markdown
     要求：每条为**裸 origin**（`scheme://host[:port]`，可带一个结尾 `/`）或 **`*.<domain>` 通配子域**（`https://*.example.com` 匹配 `example.com` 本身及其任意层级子域，scheme 与端口须一致），多条用**逗号分隔**。特殊值 `none`（大小写不敏感）= 跳过消息层 origin 校验（接受任意窗口的凭据；**不影响** nginx 嵌入控制）。单条非法只丢该条并告警，全部非法回退"通配 + 保持拒框"，所以请用第 4 节的命令自检。
     ```

  2. 4.1 节期望表（242 行）下补一行说明 + 244 行段落替换为：

     ```markdown
     多 origin 时 `frame-ancestors` 会列出全部条目（空格分隔）。未配置 `PENPOT_EMBED_PARENT_ORIGIN`、配置为 `none`、或全部条目非法时保留 `X-Frame-Options: SAMEORIGIN`（即**不允许**被跨域嵌入；`none` 只跳过消息层校验，不放开嵌入）。所以务必用这条命令确认，不要只看环境变量"已设置"。
     ```

  3. 4.4 负向验证表（282 行那条）的「做法」列改为：把 `PENPOT_EMBED_PARENT_ORIGIN` 配成另一个 origin（多 origin 列表中配错任意一条，该条宿主收不到 `ready`，其余条目不受影响）。
  4. 4.5 排查表 293 行「或值与宿主实际 origin 不一致」后补「（多 origin 时逐条核对；通配条目注意 scheme/端口必须显式一致）」。
  5. 第 5 节 305 行「**务必配置宿主 origin，不要用通配**」条目末尾补一句：`none` 同样会让 guest 接受任意窗口凭据，仅用于明确知道风险的场景（日志为 info `parent origin validation disabled by config (none)`）；配置合法通配 pattern 时 ready 以 `"*"` 为目标（postMessage 固有限制），日志同为 **info**（`wildcard pattern configured`），不是告警；306 行「origin 精确匹配」改为「origin 与配置列表逐条精确匹配（`*.` 通配条目按裸域+子域匹配）」。

- [ ] **Step 4: 无代码变更，不跑测试；提交**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0
git add frontend/resources/config.js docker/images/files/config.js docker/images/docker-compose.yaml frontend/IFRAME-EMBED.md
git commit -s -m ":books: Document multi-origin and none embed parent origin config

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 9: 全量回归 + lint/fmt 收尾

**Files:**
- 无新改动（若 lint/fmt 报格式问题则修对应文件）

- [ ] **Step 1: 全量前端测试**（宿主机或容器，任选其一）

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0/frontend
clojure -M:dev:shadow-cljs compile test
node target/tests/test.js --log-level warn 2>&1 | tee /tmp/penpot-embed-full-test.txt
grep -E "FAIL|Failure" /tmp/penpot-embed-full-test.txt
```

预期：除预存基线（keymap 2 断言、条件性 wait-boot wasm 失败）外无失败；`util-embed-test`、`util-embed-assistant-test` 全绿。

- [ ] **Step 2: lint + fmt（容器内）**

```bash
docker exec penpot-devenv-ws0-main sh -c 'cd /home/penpot/penpot/frontend && pnpm run lint:clj'
docker exec penpot-devenv-ws0-main sh -c 'cd /home/penpot/penpot && ./scripts/check-fmt'
```

预期：无新告警（`render-wasm?`/`process-wasm-object` 等 undeclared-var 预存告警忽略）；fmt 无 diff。若本任务改动文件被标格式问题：容器内 `pnpm run fmt`（frontend 目录）后重跑 check-fmt；若引入括号错误先跑 `tools/paren-repair.bb <file>`。

- [ ] **Step 3: 若 Step 2 有修复，追加提交；否则到此为止**

```bash
cd /Users/slynyeh/Downloads/penpot-2.17.0
git add -A frontend/src frontend/test
git commit -s -m ":lipstick: Fix formatting from the embed origin change

AI-assisted-by: claude-code

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 验收对照（spec → 任务）

| Spec 要求 | 任务 |
|---|---|
| 通配 pattern 归一化/非法拒绝 | Task 1 |
| `none` 哨兵 / 列表解析 / 非法条目丢弃告警 | Task 2 |
| 多 pattern + CSP 语义通配匹配 / `origin-accepted?` 向后兼容 | Task 3 |
| target-origins 策略（含通配→`*`）+ memo | Task 4 |
| embed 运行时：多目标 ready、none/wildcard 日志分级 | Task 5 |
| assistant 消重、多目标 post、require 清理 | Task 6 |
| entrypoint 逐条校验、js 逗号 join、CSP 空格 join、none→SAMEORIGIN | Task 7 |
| 四处文档同步 | Task 8 |
| 全量回归 + lint/fmt | Task 9 |

/**
 * Mock implementation of the host-provided assistant SDK
 * (`window.X_Assistant`) used to develop and review the Penpot AI
 * assistant tab without the real third party SDK.
 *
 * Shape agreed with `app.main.ui.workspace.sidebar.assistant`:
 *
 *   X_Assistant.init(config)   config stored on window.__mockAssistantConfig
 *   X_Assistant.render(id)     paints a placeholder UI in the mount div
 *   X_Assistant.destroy()      clears the mount div, sets
 *                              window.__mockAssistantDestroyed = true
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
 *
 * The config Penpot passes to init carries (among others) the hooks
 * `beforeSendMesssage` (the host docs' spelling, preferred) and
 * `beforeSendMessage`, `afterSendMessage`, `onMessageFinish` and
 * `satisfactionRating.onComplete`. The placeholder UI exposes one
 * button per hook so the full callback event chain can be triggered by
 * hand, and a small input that pipes a message through the before-send
 * hook and prints the (enriched) result so the `envs: {PAGE_ID,
 * FILE_ID, DOC_CONTENT?, DOC_TITLE?}` injection performed by Penpot
 * is visible.
 *
 * Plain IIFE, zero dependencies, native DOM APIs only, styling through
 * a small injected <style> block (no external css).
 */
(function () {
  "use strict";

  var GLOBAL_DESTROYED = "__mockAssistantDestroyed";
  var GLOBAL_CONFIG = "__mockAssistantConfig";
  var STYLE_ID = "mock-assistant-sdk-style";

  // The child always mounts into this id, but render takes whatever it
  // is given so the SDK stays usable standalone.
  var mountElement = null;
  var config = null;
  var messageCounter = 0;
  var listeners = {};

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
      if (typeof console !== "undefined" && console.warn) {
        console.warn(
          "mock-assistant-sdk: sessionStorage unavailable (load)",
          cause,
        );
      }
      return [];
    }
  }

  function storeSessions(sessions) {
    try {
      sessionStorage.setItem(SESSIONS_KEY, JSON.stringify(sessions));
    } catch (cause) {
      if (typeof console !== "undefined" && console.warn) {
        console.warn(
          "mock-assistant-sdk: sessionStorage unavailable (store)",
          cause,
        );
      }
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
      return (Number(b.updatedAt) || 0) - (Number(a.updatedAt) || 0);
    });
  }

  function nowTs() {
    return Date.now();
  }

  function safeJson(value) {
    try {
      return JSON.stringify(value, null, 2);
    } catch (cause) {
      return String(value);
    }
  }

  function text(value) {
    return document.createTextNode(String(value));
  }

  function el(tag, className) {
    var node = document.createElement(tag);
    if (className) {
      node.setAttribute("class", className);
    }
    return node;
  }

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) {
      return;
    }
    var style = document.createElement("style");
    style.id = STYLE_ID;
    style.textContent = [
      ".xmock{font:12px/1.5 system-ui,sans-serif;color:#111;background:#fff;",
      "border:1px solid #d4d4d8;border-radius:8px;padding:10px;display:flex;",
      "flex-direction:column;gap:8px;box-sizing:border-box;height:100%;",
      "overflow:auto}",
      ".xmock-head{display:flex;align-items:center;gap:8px}",
      ".xmock-head h3{margin:0;font-size:13px;font-weight:600}",
      ".xmock-logo{width:18px;height:18px;border-radius:3px;object-fit:cover}",
      ".xmock-summary{margin:0;padding:6px 8px;background:#f4f4f5;",
      "border-radius:6px;font-family:ui-monospace,monospace;font-size:11px;",
      "white-space:pre-wrap;word-break:break-all}",
      ".xmock-title{font-weight:600;font-size:11px;text-transform:uppercase;",
      "letter-spacing:.04em;color:#71717a}",
      ".xmock-row{display:flex;gap:6px;flex-wrap:wrap}",
      ".xmock button{font:inherit;padding:3px 8px;border:1px solid #d4d4d8;",
      "border-radius:5px;background:#fafafa;cursor:pointer}",
      ".xmock button:hover{background:#f0f0f1}",
      ".xmock input{font:inherit;flex:1;min-width:60px;padding:3px 6px;",
      "border:1px solid #d4d4d8;border-radius:5px}",
      ".xmock-json,.xmock-log{margin:0;padding:6px 8px;background:#18181b;color:#e4e4e7;",
      "border-radius:6px;font-family:ui-monospace,monospace;font-size:11px;",
      "white-space:pre-wrap;word-break:break-all;max-height:140px;overflow:auto}",
      ".xmock-log:empty{display:none}",
    ].join("");
    document.head.appendChild(style);
  }

  function removeStyle() {
    var style = document.getElementById(STYLE_ID);
    if (style && style.parentNode) {
      style.parentNode.removeChild(style);
    }
  }

  // ---- config hook helpers (every call is null checked) -------------

  function hook(name) {
    if (config && typeof config[name] === "function") {
      return config[name];
    }
    return null;
  }

  function resolveBeforeSendHook() {
    // The host SDK documents this hook with both spellings; Penpot sets
    // the two to the same function. Prefer the "Messsage" one, fall
    // back to the regular spelling.
    return hook("beforeSendMesssage") || hook("beforeSendMessage");
  }

  function logLine(line) {
    if (!mountElement) {
      return;
    }
    var log = mountElement.querySelector(".xmock-log");
    if (!log) {
      return;
    }
    var stamp = new Date().toTimeString().slice(0, 8);
    log.appendChild(text("[" + stamp + "] " + line + "\n"));
    log.scrollTop = log.scrollHeight;
  }

  function runTrigger(hookFn, hookLabel, sample) {
    if (hookFn) {
      try {
        hookFn(sample);
        logLine(hookLabel + " <- " + safeJson(sample));
      } catch (cause) {
        logLine(hookLabel + " threw: " + cause);
      }
    } else {
      logLine(hookLabel + " not configured, nothing to call");
    }
  }

  function makeSection(title) {
    var section = el("div", "xmock-section");
    section.appendChild(el("div", "xmock-title")).appendChild(text(title));
    return section;
  }

  function makeSummary() {
    var summary = el("pre", "xmock-summary");
    var get = function (key) {
      return config && config[key] != null ? String(config[key]) : "(not set)";
    };
    summary.appendChild(
      text(
        "appName          : " +
          get("appName") +
          "\n" +
          "agentId          : " +
          get("agentId") +
          "\n" +
          "defaultSkillName : " +
          get("defaultSkillName"),
      ),
    );
    return summary;
  }

  function makeTriggerButton(label, invoke) {
    var button = el("button");
    button.setAttribute("type", "button");
    button.appendChild(text(label));
    button.addEventListener("click", invoke);
    return button;
  }

  function makeTriggerSection() {
    var section = makeSection("Callback triggers");
    var row = el("div", "xmock-row");

    row.appendChild(
      makeTriggerButton("afterSendMessage", function () {
        runTrigger(hook("afterSendMessage"), "afterSendMessage", {
          messageId: "mock-msg-" + ++messageCounter,
          role: "user",
          content: "Mock user message sent to the agent",
          sentAt: nowTs(),
        });
      }),
    );

    row.appendChild(
      makeTriggerButton("onMessageFinish", function () {
        runTrigger(hook("onMessageFinish"), "onMessageFinish", {
          messageId: "mock-msg-" + ++messageCounter,
          role: "assistant",
          content: "Mock assistant reply, finished",
          finishedAt: nowTs(),
        });
      }),
    );

    row.appendChild(
      makeTriggerButton("satisfactionRating.onComplete", function () {
        var rating = config && config.satisfactionRating;
        var onComplete =
          rating && typeof rating.onComplete === "function"
            ? rating.onComplete
            : null;
        runTrigger(onComplete, "satisfactionRating.onComplete", {
          sessionId: (currentSession && currentSession.uid) || "mock-session-1",
          score: 5,
          comment: "Helpful (mock)",
          ratedAt: nowTs(),
        });
      }),
    );

    section.appendChild(row);
    return section;
  }

  function makeBeforeSendSection() {
    var section = makeSection("beforeSend rewrite demo");

    var row = el("div", "xmock-row");
    var input = document.createElement("input");
    input.setAttribute("type", "text");
    input.setAttribute("placeholder", "message content…");
    var send = el("button");
    send.setAttribute("type", "button");
    send.appendChild(text("Send"));
    row.appendChild(input);
    row.appendChild(send);

    var result = el("pre", "xmock-json");
    result.appendChild(
      text(
        "Type a message and press Send: it goes through the\n" +
          "config before-send hook and the (enriched) object\n" +
          "returned by Penpot is printed here.",
      ),
    );

    var runDemo = function () {
      var content = input.value.trim() || "Hello from the mock SDK";
      var message = {
        id: "mock-msg-" + ++messageCounter,
        content: content,
      };

      var beforeHook = resolveBeforeSendHook();
      if (!beforeHook) {
        result.textContent =
          "No before-send hook found on the config\n" +
          "(neither beforeSendMesssage nor beforeSendMessage).";
        logLine("beforeSend: no hook configured for " + safeJson(message));
        return;
      }

      var hookName =
        config && typeof config.beforeSendMesssage === "function"
          ? "beforeSendMesssage"
          : "beforeSendMessage";

      var display = function (returned) {
        result.textContent = hookName + " returned:\n" + safeJson(returned);
      };

      try {
        var returned = beforeHook(message);
        if (returned && typeof returned.then === "function") {
          returned.then(display, function (cause) {
            result.textContent = hookName + " rejected:\n" + String(cause);
          });
        } else {
          display(returned);
        }
        logLine(hookName + " -> " + safeJson(message));
      } catch (cause) {
        result.textContent = hookName + " threw:\n" + String(cause);
        logLine(hookName + " threw: " + cause);
      }
    };

    send.addEventListener("click", runDemo);
    input.addEventListener("keydown", function (event) {
      if (event.key === "Enter") {
        runDemo();
      }
    });

    section.appendChild(row);
    section.appendChild(result);
    return section;
  }

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

  function makeLogSection() {
    var section = makeSection("SDK log");
    section.appendChild(el("pre", "xmock-log"));
    return section;
  }

  // ---- public API ---------------------------------------------------

  window.X_Assistant = {
    init: function (config_) {
      config = config_ || {};
      try {
        window[GLOBAL_CONFIG] = config;
        window[GLOBAL_DESTROYED] = false;
      } catch (cause) {
        // a frozen or exotic window cannot be annotated; the SDK still
        // works, only the external assertions lose the markers
      }
    },

    render: function (mountId) {
      var element = document.getElementById(mountId);
      if (!element) {
        // Do not throw: the child session wraps render in a promise
        // chain, but a loud console trace helps debugging a missing
        // mount div during integration.
        if (typeof console !== "undefined" && console.warn) {
          console.warn(
            "mock-assistant-sdk: mount element not found: " + mountId,
          );
        }
        return;
      }

      ensureStyle();

      element.textContent = "";
      mountElement = element;

      var root = el("div", "xmock");

      var head = el("div", "xmock-head");
      if (
        config &&
        config.appImage &&
        typeof config.appImage.src === "string" &&
        config.appImage.src
      ) {
        var logo = document.createElement("img");
        logo.setAttribute("src", config.appImage.src);
        logo.setAttribute("alt", "assistant logo");
        logo.setAttribute("class", "xmock-logo");
        head.appendChild(logo);
      }
      var title = el("h3");
      title.appendChild(text("Mock AI Assistant"));
      head.appendChild(title);
      root.appendChild(head);

      root.appendChild(makeSummary());
      root.appendChild(makeSessionSection());
      root.appendChild(makeTriggerSection());
      root.appendChild(makeBeforeSendSection());
      root.appendChild(makeLogSection());

      mountElement.appendChild(root);

      renderCurrentSession();

      logLine("mock SDK rendered (mount: " + mountId + ")");
    },

    destroy: function () {
      if (mountElement) {
        mountElement.textContent = "";
        mountElement = null;
      }
      removeStyle();
      try {
        window[GLOBAL_DESTROYED] = true;
      } catch (cause) {
        // see init
      }
    },

    on: function (name, fn) {
      if (typeof name !== "string" || typeof fn !== "function") {
        return;
      }
      (listeners[name] = listeners[name] || []).push(fn);
    },

    off: function (name, fn) {
      var fns = listeners[name];
      if (!fns) {
        return;
      }
      var index = fns.indexOf(fn);
      if (index !== -1) {
        fns.splice(index, 1);
      }
    },

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
            var sessions = loadSessions();
            var before = sessions.length;
            var remaining = sessions.filter(function (session) {
              return session.uid !== sessionId;
            });
            if (remaining.length === before) {
              logLine("deleteSession: unknown id " + sessionId);
            }
            storeSessions(remaining);
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

        // auto-open contract: called by Penpot right after init when
        // the embed handshake delivered a prompt and/or attachments
        openChat: function (prompt, options) {
          logLine(
            "openChat(prompt=" +
              JSON.stringify(prompt) +
              ", options=" +
              JSON.stringify(options || {}) +
              ")",
          );
          console.log("[mock-assistant-sdk] openChat", prompt, options);
        },
      };
    },
  };
})();

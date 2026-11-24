;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.util-embed-test
  (:require
   [app.util.embed :as embed]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

(defn- fake-storage
  "A read-only Storage double over a plain map: read-user-key only
  ever calls getItem."
  [m]
  #js {"getItem" (fn [k] (get m k))})

;; --- read-user-key (shared EBEE_USER_KEY reader) ----------------------

(t/deftest read-user-key-parses-the-user-object
  (let [parsed (embed/read-user-key
                (fake-storage {"EBEE_USER_KEY"
                               "{\"idToken\":\"t\",\"originPathId\":\"/990001/x\"}"}))]
    (t/is (some? parsed))
    (t/is (= "t" (unchecked-get parsed "idToken")))
    (t/is (= "/990001/x" (unchecked-get parsed "originPathId")))))

(t/deftest read-user-key-returns-nil-when-unusable
  ;; key absent
  (t/is (nil? (embed/read-user-key (fake-storage {}))))
  ;; malformed JSON
  (t/is (nil? (embed/read-user-key (fake-storage {"EBEE_USER_KEY" "{bad json"}))))
  ;; JSON scalars and arrays are not user objects
  (t/is (nil? (embed/read-user-key (fake-storage {"EBEE_USER_KEY" "42"}))))
  (t/is (nil? (embed/read-user-key (fake-storage {"EBEE_USER_KEY" "null"}))))
  (t/is (nil? (embed/read-user-key (fake-storage {"EBEE_USER_KEY" "[1,2]"}))))
  ;; nil storage (node tests resolve no sessionStorage)
  (t/is (nil? (embed/read-user-key nil)))
  ;; zero arity: the node runner resolves no sessionStorage either
  (t/is (nil? (embed/read-user-key))))

(t/deftest read-user-key-tolerates-a-throwing-storage
  (t/is (nil? (embed/read-user-key
               #js {"getItem" (fn [_] (throw (js/Error. "boom")))}))))

(t/deftest headers-without-credentials
  ;; Nothing is sent until the parent has actually handed over credentials.
  (t/is (= {} (embed/headers))))

(t/deftest normalize-origin-valid
  (t/is (= "https://portal.example.com"
           (embed/normalize-origin "https://portal.example.com")))
  (t/is (= "https://portal.example.com"
           (embed/normalize-origin "https://portal.example.com/")))
  (t/is (= "https://portal.example.com"
           (embed/normalize-origin "https://portal.example.com/some/path")))
  (t/is (= "http://localhost:3001"
           (embed/normalize-origin "http://localhost:3001"))))

(t/deftest normalize-origin-invalid
  (t/is (nil? (embed/normalize-origin nil)))
  (t/is (nil? (embed/normalize-origin "")))
  (t/is (nil? (embed/normalize-origin "   ")))
  (t/is (nil? (embed/normalize-origin "*")))
  (t/is (nil? (embed/normalize-origin "portal.example.com")))
  (t/is (nil? (embed/normalize-origin "null"))))

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

(t/deftest normalize-origin-pattern-wildcards-under-host-escaping-url-engine
  ;; URL engines disagree on a `*` in the host: Blink (Chrome and every
  ;; Chromium based browser, including the 360-kernel clients this fork
  ;; targets) percent-escapes it — `new URL("https://*.a.b").hostname`
  ;; is "%2A.a.b" — while Node's URL passes it through, which is why the
  ;; plain tests above stay green either way. Emulate the escaping engine
  ;; here so the regression — every wildcard entry dropped as invalid and
  ;; the whole allowlist silently failing open in real browsers — is
  ;; pinned in the Node runner too.
  (let [real (.-URL js/globalThis)
        wrap (fn [spec]
               (let [u (new real spec)]
                 #js {:hostname (.replace (.-hostname u) "*" "%2A")
                      :origin   (.replace (.-origin u) "*" "%2A")}))]
    (set! (.-URL js/globalThis) wrap)
    (let [result
          (try
            [(embed/normalize-origin-pattern "https://*.st.test.cn")
             (embed/normalize-origin-pattern "https://*.uat.test.cn")
             (embed/normalize-origin-pattern "https://*.EXAMPLE.com/")
             (embed/normalize-origin-pattern "https://*.example.com:8443")
             (embed/normalize-origin-pattern "https://a*b.example.com")
             (embed/normalize-origin-pattern "https://example.com")]
            (finally
              (set! (.-URL js/globalThis) real)))]
      ;; wildcard entries must survive an engine that escapes them
      (t/is (= ["https://*.st.test.cn"
                "https://*.uat.test.cn"
                "https://*.example.com"
                "https://*.example.com:8443"
                nil
                "https://example.com"]
               result)))))

(t/deftest normalize-origin-pattern-rejects-malformed
  (t/is (nil? (embed/normalize-origin-pattern nil)))
  (t/is (nil? (embed/normalize-origin-pattern "")))
  (t/is (nil? (embed/normalize-origin-pattern "   ")))
  (t/is (nil? (embed/normalize-origin-pattern "portal.example.com")))
  ;; a bare `*` host is not a pattern; unconditional acceptance is the
  ;; "none" sentinel, never a wildcard pattern
  (t/is (nil? (embed/normalize-origin-pattern "https://*")))
  ;; `*` is only valid as the leading `*.` label
  (t/is (nil? (embed/normalize-origin-pattern "https://a*b.example.com")))
  ;; percent-decoding must not smuggle a wildcard past the guard
  (t/is (nil? (embed/normalize-origin-pattern "https://%2A.example.com"))))

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

(t/deftest origin-accepted-exact-match
  (t/is (true? (embed/origin-accepted? "https://portal.example.com"
                                       "https://portal.example.com")))
  (t/is (true? (embed/origin-accepted? "https://portal.example.com"
                                       "https://portal.example.com/"))))

(t/deftest origin-accepted-rejects-lookalikes
  ;; A prefix check would let these through.
  (t/is (false? (embed/origin-accepted? "https://good.example"
                                        "https://good.example.attacker.io")))
  (t/is (false? (embed/origin-accepted? "https://good.example"
                                        "https://attacker.io")))
  (t/is (false? (embed/origin-accepted? "https://good.example"
                                        "http://good.example")))
  (t/is (false? (embed/origin-accepted? "https://good.example:8443"
                                        "https://good.example")))
  ;; an unparseable event.origin must fail closed under a configured pattern
  (t/is (false? (embed/origin-accepted? "https://good.example" nil))))

(t/deftest origin-accepted-without-configuration
  ;; An unset (or unusable) configuration means wildcard.
  (t/is (true? (embed/origin-accepted? nil "https://anything.example")))
  (t/is (true? (embed/origin-accepted? "" "https://anything.example")))
  (t/is (true? (embed/origin-accepted? "*" "https://anything.example")))
  (t/is (true? (embed/origin-accepted? "not-an-origin" "https://anything.example"))))

(t/deftest valid-payload-accepts-flat-credentials
  (t/is (true? (embed/valid-payload? #js {"X-token" "abc"
                                          "X-ClientId" "xyz"})))
  ;; Whitespace around a value is trimmed, not rejected.
  (t/is (true? (embed/valid-payload? #js {"X-token" " abc "
                                          "X-ClientId" "xyz"})))
  ;; Optional docContent/docTitle are extra data, not credentials; their
  ;; presence must not change whether the handshake payload is accepted.
  (t/is (true? (embed/valid-payload? #js {"X-token" "abc"
                                          "X-ClientId" "xyz"
                                          "docContent" "hello"
                                          "docTitle" "my design"})))
  ;; Optional prompt/attachments are extra data too, not credentials.
  (t/is (true? (embed/valid-payload? #js {"X-token" "abc"
                                          "X-ClientId" "xyz"
                                          "prompt" "hello"
                                          "attachments" #js [#js {"name" "a" "url" "b"}]}))))

(t/deftest valid-payload-rejects-malformed
  (t/is (false? (embed/valid-payload? nil)))
  (t/is (false? (embed/valid-payload? "X-token")))
  (t/is (false? (embed/valid-payload? #js ["X-token" "abc"])))
  ;; A wrapped payload is not the agreed shape.
  (t/is (false? (embed/valid-payload? #js {"data" #js {"X-token" "abc"
                                                       "X-ClientId" "xyz"}})))
  ;; Wrong key casing is not the agreed shape either.
  (t/is (false? (embed/valid-payload? #js {"x-token" "abc"
                                           "X-ClientId" "xyz"})))
  (t/is (false? (embed/valid-payload? #js {"X-token" "abc"})))
  (t/is (false? (embed/valid-payload? #js {"X-ClientId" "xyz"})))
  (t/is (false? (embed/valid-payload? #js {"X-token" ""
                                           "X-ClientId" "xyz"})))
  (t/is (false? (embed/valid-payload? #js {"X-token" "   "
                                           "X-ClientId" "xyz"})))
  (t/is (false? (embed/valid-payload? #js {"X-token" 1
                                           "X-ClientId" "xyz"})))
  (t/is (false? (embed/valid-payload? #js {"X-token" nil
                                           "X-ClientId" "xyz"})))
  ;; docContent alone is not a credentials payload.
  (t/is (false? (embed/valid-payload? #js {"docContent" "hello"})))
  ;; docTitle alone is not a credentials payload either.
  (t/is (false? (embed/valid-payload? #js {"docTitle" "my design"})))
  ;; prompt/attachments alone are not a credentials payload either.
  (t/is (false? (embed/valid-payload? #js {"prompt" "hello"
                                           "attachments" #js []}))))

(t/deftest parse-payload-extracts-credentials
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"})))
  ;; Token and client-id are trimmed; the rest of the handshake still
  ;; requires both to be present.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" " abc "
                                     "X-ClientId" " xyz "}))))

(t/deftest parse-payload-extracts-optional-doc-content
  (t/is (= {:token "abc" :client-id "xyz" :doc-content "hello" :doc-title nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docContent" "hello"})))
  ;; Empty string is a real value, not "missing".
  (t/is (= {:token "abc" :client-id "xyz" :doc-content "" :doc-title nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docContent" ""})))
  ;; Leading/trailing whitespace in document text is preserved.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content "  keep  " :doc-title nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docContent" "  keep  "})))
  ;; Wrong type is ignored rather than rejecting the credentials.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docContent" 1})))
  ;; Wrong key casing is not the agreed shape for this field.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "DocContent" "hello"}))))

(t/deftest parse-payload-extracts-optional-doc-title
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title "my design"
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docTitle" "my design"})))
  ;; Empty string is a real value, not "missing".
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title ""
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docTitle" ""})))
  ;; Leading/trailing whitespace is preserved, like docContent.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title "  keep  "
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docTitle" "  keep  "})))
  ;; Wrong type is ignored rather than rejecting the credentials.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docTitle" 1})))
  ;; Wrong key casing is not the agreed shape for this field.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "DocTitle" "my design"}))))

(t/deftest parse-payload-extracts-optional-prompt
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil
            :prompt "please review" :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "prompt" "please review"})))
  ;; Empty string is a real value, not "missing".
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil
            :prompt "" :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "prompt" ""})))
  ;; Leading/trailing whitespace is preserved, like docContent.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil
            :prompt "  keep  " :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "prompt" "  keep  "})))
  ;; Wrong type is ignored rather than rejecting the credentials.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "prompt" 1})))
  ;; Wrong key casing is not the agreed shape for this field.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "Prompt" "hello"}))))

(t/deftest parse-payload-extracts-optional-attachments
  ;; A well formed array is normalized to a vector of {:name :url}
  ;; maps; name/url are kept as-is, empty strings included.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil :prompt nil
            :attachments [{:name "spec.pdf"
                           :url "https://portal.example.com/files/spec.pdf"}
                          {:name "" :url ""}]}
           (embed/parse-payload
            #js {"X-token" "abc"
                 "X-ClientId" "xyz"
                 "attachments" #js [#js {"name" "spec.pdf"
                                         "url" "https://portal.example.com/files/spec.pdf"}
                                    #js {"name" "" "url" ""}]})))
  ;; An empty array is a real value, not "missing".
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil :prompt nil
            :attachments []}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js []})))
  ;; Extra fields on an entry are ignored; only name/url are kept.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil :prompt nil
            :attachments [{:name "a" :url "b"}]}
           (embed/parse-payload
            #js {"X-token" "abc"
                 "X-ClientId" "xyz"
                 "attachments" #js [#js {"name" "a" "url" "b" "size" 99}]}))))

(t/deftest parse-payload-rejects-malformed-attachments
  ;; not an array at all
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js {"name" "a" "url" "b"}})))
  ;; an entry that is not an object
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js ["just-a-string"]})))
  ;; an entry missing url
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js [#js {"name" "a"}]})))
  ;; a non-string name / url
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js [#js {"name" 1 "url" "b"}]})))
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js [#js {"name" "a" "url" nil}]})))
  ;; one bad entry invalidates the whole field (kept or dropped as a unit)
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js [#js {"name" "a" "url" "b"}
                                                        "junk"]})))
  ;; wrong key casing is not the agreed shape for this field
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :doc-title nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "Attachments" #js [#js {"name" "a" "url" "b"}]}))))

(t/deftest parse-payload-rejects-malformed
  (t/is (nil? (embed/parse-payload nil)))
  (t/is (nil? (embed/parse-payload #js {"X-token" "abc"}))))

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

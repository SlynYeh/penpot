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
                                        "https://good.example"))))

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
  ;; Optional docContent is extra data, not a credential; its presence
  ;; must not change whether the handshake payload is accepted.
  (t/is (true? (embed/valid-payload? #js {"X-token" "abc"
                                          "X-ClientId" "xyz"
                                          "docContent" "hello"})))
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
  ;; prompt/attachments alone are not a credentials payload either.
  (t/is (false? (embed/valid-payload? #js {"prompt" "hello"
                                           "attachments" #js []}))))

(t/deftest parse-payload-extracts-credentials
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"})))
  ;; Token and client-id are trimmed; the rest of the handshake still
  ;; requires both to be present.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" " abc "
                                     "X-ClientId" " xyz "}))))

(t/deftest parse-payload-extracts-optional-doc-content
  (t/is (= {:token "abc" :client-id "xyz" :doc-content "hello"
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docContent" "hello"})))
  ;; Empty string is a real value, not "missing".
  (t/is (= {:token "abc" :client-id "xyz" :doc-content ""
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docContent" ""})))
  ;; Leading/trailing whitespace in document text is preserved.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content "  keep  "
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docContent" "  keep  "})))
  ;; Wrong type is ignored rather than rejecting the credentials.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "docContent" 1})))
  ;; Wrong key casing is not the agreed shape for this field.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "DocContent" "hello"}))))

(t/deftest parse-payload-extracts-optional-prompt
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil
            :prompt "please review" :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "prompt" "please review"})))
  ;; Empty string is a real value, not "missing".
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil
            :prompt "" :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "prompt" ""})))
  ;; Leading/trailing whitespace is preserved, like docContent.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil
            :prompt "  keep  " :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "prompt" "  keep  "})))
  ;; Wrong type is ignored rather than rejecting the credentials.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "prompt" 1})))
  ;; Wrong key casing is not the agreed shape for this field.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil
            :prompt nil :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "Prompt" "hello"}))))

(t/deftest parse-payload-extracts-optional-attachments
  ;; A well formed array is normalized to a vector of {:name :url}
  ;; maps; name/url are kept as-is, empty strings included.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :prompt nil
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
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :prompt nil
            :attachments []}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js []})))
  ;; Extra fields on an entry are ignored; only name/url are kept.
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :prompt nil
            :attachments [{:name "a" :url "b"}]}
           (embed/parse-payload
            #js {"X-token" "abc"
                 "X-ClientId" "xyz"
                 "attachments" #js [#js {"name" "a" "url" "b" "size" 99}]}))))

(t/deftest parse-payload-rejects-malformed-attachments
  ;; not an array at all
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js {"name" "a" "url" "b"}})))
  ;; an entry that is not an object
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js ["just-a-string"]})))
  ;; an entry missing url
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js [#js {"name" "a"}]})))
  ;; a non-string name / url
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js [#js {"name" 1 "url" "b"}]})))
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js [#js {"name" "a" "url" nil}]})))
  ;; one bad entry invalidates the whole field (kept or dropped as a unit)
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :prompt nil
            :attachments nil}
           (embed/parse-payload #js {"X-token" "abc"
                                     "X-ClientId" "xyz"
                                     "attachments" #js [#js {"name" "a" "url" "b"}
                                                        "junk"]})))
  ;; wrong key casing is not the agreed shape for this field
  (t/is (= {:token "abc" :client-id "xyz" :doc-content nil :prompt nil
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

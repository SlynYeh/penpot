;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.util-embed-test
  (:require
   [app.util.embed :as embed]
   [cljs.test :as t :include-macros true]))

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
                                          "X-ClientId" "xyz"}))))

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
                                           "X-ClientId" "xyz"}))))

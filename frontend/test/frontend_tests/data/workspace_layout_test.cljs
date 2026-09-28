;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-layout-test
  (:require
   [app.config :as cf]
   [app.main.data.workspace.layout :as dwlt]
   [clojure.test :as t]))

;; --- Fork knob: hide the tokens (变量) UI

(t/deftest parse-boolean-keeps-real-booleans
  (t/is (true? (cf/parse-boolean true false)))
  (t/is (false? (cf/parse-boolean false true))))

(t/deftest parse-boolean-parses-strings-case-insensitively
  ;; `js/config.js` is hand-edited in dev and rewritten by sed in Docker, so
  ;; a quoted value has to work as well as a bare boolean.
  (t/is (true? (cf/parse-boolean "true" false)))
  (t/is (true? (cf/parse-boolean "TRUE" false)))
  (t/is (false? (cf/parse-boolean "false" true)))
  (t/is (false? (cf/parse-boolean "False" true))))

(t/deftest parse-boolean-falls-back-on-malformed-input
  ;; A typo must keep the default rather than silently switching sides: were
  ;; "yes" treated as truthy for `penpotHideTokens`, the environment variable
  ;; could never turn the tokens UI back on.
  (t/is (true? (cf/parse-boolean nil true)))
  (t/is (false? (cf/parse-boolean nil false)))
  (t/is (true? (cf/parse-boolean "yes" true)))
  (t/is (false? (cf/parse-boolean "yes" false)))
  (t/is (true? (cf/parse-boolean 1 true)))
  (t/is (false? (cf/parse-boolean js/undefined false))))

(t/deftest hide-tokens-knob-is-a-boolean
  (t/is (boolean? cf/hide-tokens)))

;; --- Left sidebar tokens tab

(t/deftest tokens-tab-visible-only-with-feature-on-and-knob-off
  (t/is (true? (dwlt/tokens-tab-visible? true false)))
  (t/is (false? (dwlt/tokens-tab-visible? true true)))
  (t/is (false? (dwlt/tokens-tab-visible? false false)))
  (t/is (false? (dwlt/tokens-tab-visible? false true))))

(t/deftest tokens-tab-visible-coerces-non-boolean-inputs
  ;; nil/undefined must not leak in as truthy for the hide knob, otherwise a
  ;; missing config.js value would hide the tab and `false` would be ignored.
  (t/is (false? (dwlt/tokens-tab-visible? nil false)))
  (t/is (true? (dwlt/tokens-tab-visible? true nil)))
  (t/is (false? (dwlt/tokens-tab-visible? js/undefined true))))

;; --- Keep-mounted helper backing the sidebar tab panels

(t/deftest keep-sidebar-tab-keeps-selected-and-seen-tabs
  (t/is (true? (dwlt/keep-sidebar-tab? :assets #{} :assets)))
  (t/is (true? (dwlt/keep-sidebar-tab? :layers #{:assets} :assets)))
  (t/is (false? (dwlt/keep-sidebar-tab? :layers #{} :assets))))

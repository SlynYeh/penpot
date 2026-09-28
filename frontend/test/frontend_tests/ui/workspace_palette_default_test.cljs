;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.workspace-palette-default-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.ui.workspace.palette :as dwp]
   [cljs.test :as t :include-macros true]))

(t/deftest returns-configured-library-when-present-and-not-current-file
  (let [lib-id  (uuid/next)
        file-id (uuid/next)]
    (with-redefs [cf/default-palette-library-id lib-id]
      (t/is (= lib-id (dwp/default-palette-library {file-id {} lib-id {}} file-id))))))

(t/deftest returns-nil-when-configured-library-absent
  (let [lib-id  (uuid/next)
        file-id (uuid/next)]
    (with-redefs [cf/default-palette-library-id lib-id]
      (t/is (nil? (dwp/default-palette-library {file-id {}} file-id))))))

(t/deftest returns-nil-when-configured-is-current-file
  (let [file-id (uuid/next)]
    (with-redefs [cf/default-palette-library-id file-id]
      (t/is (nil? (dwp/default-palette-library {file-id {}} file-id))))))

(t/deftest returns-nil-when-no-config
  (let [lib-id  (uuid/next)
        file-id (uuid/next)]
    (with-redefs [cf/default-palette-library-id nil]
      (t/is (nil? (dwp/default-palette-library {file-id {} lib-id {}} file-id))))))

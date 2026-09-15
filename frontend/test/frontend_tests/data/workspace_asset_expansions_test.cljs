;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-asset-expansions-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.workspace :as dw]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(defn- run-update
  "Apply an UpdateEvent to a state synchronously."
  [event state]
  (ptk/update event state))

(t/deftest pre-seeds-library-and-all-ancestor-paths
  (let [lib-id (uuid/next)]
    (with-redefs [cf/default-expanded-asset-groups
                  [{:library-id lib-id :groups ["按钮 / 主要"]}]]
      (let [state  {}
            new-st (run-update (dw/apply-default-asset-expansions) state)]
        (t/is (true? (get-in new-st [:workspace-assets :open-status lib-id :library])))
        (t/is (true? (get-in new-st [:workspace-assets :open-status lib-id :components])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-id :groups :components "按钮"])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-id :groups :components "按钮 / 主要"])))))))

(t/deftest opens-deeply-nested-group
  (let [lib-id (uuid/next)]
    (with-redefs [cf/default-expanded-asset-groups
                  [{:library-id lib-id :groups ["a / b / c / d"]}]]
      (let [new-st (run-update (dw/apply-default-asset-expansions) {})]
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-id :groups :components "a"])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-id :groups :components "a / b"])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-id :groups :components "a / b / c"])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-id :groups :components "a / b / c / d"])))))))

(t/deftest skips-pre-seed-when-user-already-interacted
  (let [lib-id (uuid/next)]
    (with-redefs [cf/default-expanded-asset-groups
                  [{:library-id lib-id :groups ["按钮 / 主要"]}]]
      (let [state  (assoc-in {} [:workspace-assets :open-status lib-id :library] false)
            new-st (run-update (dw/apply-default-asset-expansions) state)]
        (t/is (false? (get-in new-st [:workspace-assets :open-status lib-id :library])))
        (t/is (nil? (get-in new-st [:workspace-assets :open-status lib-id :components])))
        (t/is (nil? (get-in new-st
                            [:workspace-assets :open-status lib-id :groups :components "按钮"])))))))

(t/deftest no-op-when-config-empty
  (with-redefs [cf/default-expanded-asset-groups []]
    (let [state  {}
          new-st (run-update (dw/apply-default-asset-expansions) state)]
      (t/is (= state new-st)))))

(t/deftest handles-multiple-libraries-and-groups
  (let [lib-a (uuid/next)
        lib-b (uuid/next)]
    (with-redefs [cf/default-expanded-asset-groups
                  [{:library-id lib-a :groups ["A / 1"]}
                   {:library-id lib-b :groups ["B / 2" "B / 3"]}]]
      (let [new-st (run-update (dw/apply-default-asset-expansions) {})]
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-a :groups :components "A"])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-a :groups :components "A / 1"])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-a :library])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-b :groups :components "B"])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-b :groups :components "B / 2"])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-b :groups :components "B / 3"])))))))

(t/deftest skips-only-the-already-interacted-library
  (let [lib-a (uuid/next)
        lib-b (uuid/next)]
    (with-redefs [cf/default-expanded-asset-groups
                  [{:library-id lib-a :groups ["A / 1"]}
                   {:library-id lib-b :groups ["B / 2"]}]]
      (let [state  (assoc-in {} [:workspace-assets :open-status lib-a :library] false)
            new-st (run-update (dw/apply-default-asset-expansions) state)]
        (t/is (false? (get-in new-st [:workspace-assets :open-status lib-a :library])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-b :groups :components "B"])))
        (t/is (true? (get-in new-st
                             [:workspace-assets :open-status lib-b :groups :components "B / 2"])))))))

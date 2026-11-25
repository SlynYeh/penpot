;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.dropdown-menu-eviction-test
  (:require
   [app.main.ui.components.dropdown-menu :refer [dropdown-evicts?]]
   [cljs.test :as t :include-macros true]))

(t/deftest test-dropdown-evicts
  (t/testing "a different id closes the other dropdown"
    ;; The workspace help center must evict the whole main menu group,
    ;; otherwise its flyout renders on top of an open secondary panel.
    (t/is (true? (dropdown-evicts? "workspace-menu"
                                   "workspace-help-center-menu")))
    (t/is (true? (dropdown-evicts? "workspace-help-center-menu"
                                   "workspace-menu"))))

  (t/testing "the same id keeps one logical menu open"
    ;; Main menu and its secondary panels share an id so hovering into a
    ;; submenu does not close the primary list.
    (t/is (false? (dropdown-evicts? "workspace-menu" "workspace-menu"))))

  (t/testing "a dropdown without an id opts out of the channel"
    (t/is (false? (dropdown-evicts? nil "workspace-menu")))
    (t/is (false? (dropdown-evicts? nil nil)))))

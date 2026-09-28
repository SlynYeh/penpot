;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.workspace-asset-local-library-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.ui.workspace.sidebar.assets :as assets]
   [cljs.test :as t :include-macros true]))

(defn- sample-component
  []
  {:id (uuid/next) :name "sample" :path ""})

(defn- sample-color
  []
  {:id (uuid/next) :name "sample" :color "#ffffff" :opacity 1})

(defn- sample-typography
  []
  {:id (uuid/next) :name "sample" :font-id (uuid/next)})

(t/deftest collapsed-when-library-is-empty
  (t/is (false? (assets/has-assets? {})))
  (t/is (false? (assets/has-assets? {:components {} :colors {} :typographies {}})))
  (t/is (false? (assets/has-assets? {:components nil :colors nil :typographies nil}))))

(t/deftest expanded-when-library-has-components
  (t/is (true? (assets/has-assets? {:components {(uuid/next) (sample-component)}}))))

(t/deftest expanded-when-library-has-only-colors
  (t/is (true? (assets/has-assets? {:colors {(uuid/next) (sample-color)}}))))

(t/deftest expanded-when-library-has-only-typographies
  (t/is (true? (assets/has-assets? {:typographies {(uuid/next) (sample-typography)}}))))

(t/deftest ignores-deleted-components
  (t/is (false? (assets/has-assets? {:components {(uuid/next) (assoc (sample-component)
                                                                     :deleted true)}})))
  (t/is (true? (assets/has-assets?
                {:components (hash-map (uuid/next) (assoc (sample-component) :deleted true)
                                       (uuid/next) (sample-component))}))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-finalize-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(t/deftest finalize-workspace-dissocs-thumbnails-and-comment-threads
  (let [file-id (uuid/next)
        event   (dw/finalize-workspace (uuid/next) file-id)
        state   {:current-file-id file-id
                 :thumbnails {"obj-1" "blob:http://x/1"
                              "obj-2" "data:image/png;base64,AAA"}
                 :thumbnails-meta {"obj-1" {:revn 1}}
                 :comment-threads {"thread-1" {:id "thread-1"}}
                 :workspace-undo []
                 :files {file-id {:id file-id :data {:pages-index {}}}}}
        result  (ptk/update event state)]
    (t/is (nil? (:current-file-id result)))
    (t/is (nil? (:thumbnails result)))
    (t/is (nil? (:thumbnails-meta result)))
    (t/is (nil? (:comment-threads result)))
    ;; the big file data graph is still stripped
    (t/is (nil? (get-in result [:files file-id :data])))))

(t/deftest finalize-workspace-keeps-only-current-file-page-cache
  (let [file-id       (uuid/next)
        other-file-id (uuid/next)
        event         (dw/finalize-workspace (uuid/next) file-id)
        state         {:workspace-cache {[file-id :page-1]      {:zoom 1}
                                         [file-id :page-2]      {:zoom 2}
                                         [other-file-id :page-1] {:zoom 3}}}
        result        (ptk/update event state)]
    ;; entries of other files are dropped, entries of the visited file stay
    (t/is (= 2 (count (:workspace-cache result))))
    (t/is (nil? (get-in result [:workspace-cache [other-file-id :page-1]])))
    (t/is (some? (get-in result [:workspace-cache [file-id :page-1]])))))

(t/deftest finalize-workspace-tolerates-empty-state
  (let [file-id (uuid/next)
        event   (dw/finalize-workspace (uuid/next) file-id)
        result  (ptk/update event {})]
    (t/is (map? result))
    (t/is (= {} (:workspace-cache result)))))

(t/deftest finalize-workspace-watch-closes-plugins-and-does-not-throw
  (t/async done
    (let [file-id       (uuid/next)
          other-file-id (uuid/next)
          event         (dw/finalize-workspace (uuid/next) file-id)
          state         {:current-project-id (uuid/next)
                         ;; non-blob URIs, so no revoke gets scheduled
                         :thumbnails {"obj-1" "data:image/png;base64,AAA"}
                         :workspace-cache {[file-id :page-1]       {:zoom 1 :open-plugins #{(uuid/next)}}
                                           [file-id :page-2]       {:zoom 2 :open-plugins #{(uuid/next)}}
                                           [other-file-id :page-1] {:open-plugins #{(uuid/next)}}}}
          on-events     (fn [events]
                          (let [types (into #{} (map ptk/type) events)]
                            (t/is (contains? types :app.main.data.plugins/close-plugins))
                            (t/is (contains? types :app.main.data.workspace.mcp/finalize)))
                          (done))]
      (ptk/update event state)
      ;; the watch body must not throw synchronously (an uncaught throw
      ;; here would crash the store on every workspace exit)
      (let [stream (try
                     (ptk/watch event state nil)
                     (catch :default e e))]
        (if (instance? js/Error stream)
          (do (t/is (nil? stream) "the finalize watch must not throw")
              (done))
          (let [emitted? (volatile! false)]
            ;; beicon subs! takes the observable as the LAST argument
            (rx/subs! (fn [events]
                        (vreset! emitted? true)
                        (on-events events))
                      (fn [err]
                        (t/is (nil? err) "the finalize watch stream must not error")
                        (done))
                      (fn []
                        (when-not @emitted?
                          (t/is false "the finalize watch emitted no events")
                          (done)))
                      (rx/reduce conj [] stream))))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.onboarding.beginner-guide
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [app.util.storage :as storage]
   [cuerdas.core :as str]
   [goog.events :as events]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

(def storage-key ::beginner-guide-viewed)
(def help-hint-storage-key ::beginner-guide-help-hint-seen)
(def help-hint-state-key :workspace-help-hint)

(defn viewed?
  []
  (true? (get storage/global storage-key)))

(defn mark-viewed!
  []
  (swap! storage/global assoc storage-key true))

(defn help-hint-seen?
  []
  (true? (get storage/global help-hint-storage-key)))

(defn mark-help-hint-seen!
  []
  (swap! storage/global assoc help-hint-storage-key true))

(defn should-reveal-help-hint?
  "True only for the first close of the beginner-guide modal."
  [already-viewed hint-seen]
  (and (not already-viewed)
       (not hint-seen)))

(defn reveal-help-hint
  []
  (ptk/reify ::reveal-help-hint
    ptk/UpdateEvent
    (update [_ state]
      (assoc state help-hint-state-key true))))

(defn dismiss-help-hint
  []
  (ptk/reify ::dismiss-help-hint
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state help-hint-state-key))))

(defn last-item?
  [index total]
  (and (pos? total)
       (= index (dec total))))

(defn next-index
  [index total]
  (when-not (last-item? index total)
    (inc index)))

(defn watch-item
  [watched item-id]
  (conj (or watched #{}) item-id))

(defn item-video-url
  [item]
  (let [url (:video-url item)]
    (when (and (string? url)
               (not (str/blank? url)))
      url)))

(defn should-auto-show?
  "First-visit modal opens only when the config switch is on, the user has
   not already dismissed it, and the modal is not already on screen."
  [enabled? already-viewed modal-type]
  (and enabled?
       (not already-viewed)
       (not= :beginner-guide modal-type)))

(defn guide-items
  ([]
   (guide-items cf/beginner-guide-videos))
  ([videos]
   [{:id          "click-through"
     :title       (tr "workspace.beginner-guide.items.click-through.title")
     :description (tr "workspace.beginner-guide.items.click-through.description")
     :video-url   (get videos "click-through")}
    {:id          "table-shortcuts"
     :title       (tr "workspace.beginner-guide.items.table-shortcuts.title")
     :description (tr "workspace.beginner-guide.items.table-shortcuts.description")
     :video-url   (get videos "table-shortcuts")}
    {:id          "component-library"
     :title       (tr "workspace.beginner-guide.items.component-library.title")
     :description (tr "workspace.beginner-guide.items.component-library.description")
     :video-url   (get videos "component-library")}]))

(defn- dismiss!
  []
  (let [reveal? (should-reveal-help-hint? (viewed?) (help-hint-seen?))]
    (mark-viewed!)
    (st/emit! (modal/hide))
    (when reveal?
      (st/async-emit! (reveal-help-hint)))))

(defn show!
  []
  (st/emit! (modal/show {:type :beginner-guide})
            (modal/update {:allow-click-outside true})))

(defn maybe-show!
  []
  (when (should-auto-show? cf/show-beginner-guide
                           (viewed?)
                           (:type (get @st/state ::modal/modal)))
    (show!)))

(mf/defc guide-card*
  {::mf/private true}
  [{:keys [item index selected watched on-select]}]
  (let [on-click
        (mf/use-fn
         (mf/deps index on-select)
         (fn [event]
           (dom/prevent-default event)
           (on-select index)))]
    [:button {:type "button"
              :data-index (str index)
              :aria-pressed selected
              :class (stl/css-case :card true
                                   :card-selected selected
                                   :card-watched watched)
              :on-click on-click}
     [:div {:class (stl/css :card-body)}
      [:div {:class (stl/css :card-title)}
       (:title item)]
      [:div {:class (stl/css :card-description)}
       (:description item)]]
     [:span {:class (stl/css :card-status)
             :aria-hidden true}
      [:svg {:class (stl/css :card-status-icon)
             :width 20
             :height 20}
       [:use {:href (dm/str "#icon-" (if watched i/check-one i/circle))
              :width 20
              :height 20}]]]]))

(mf/defc guide-video*
  {::mf/private true}
  [{:keys [src title]}]
  (let [video-ref (mf/use-ref nil)]
    (mf/with-effect [src]
      (when-let [node (mf/ref-val video-ref)]
        (let [can-unmute? (boolean (some-> (.-userActivation js/navigator)
                                           (.-isActive)))]
          (set! (.-muted node) (not can-unmute?))
          (let [playing (.play node)]
            (when (some? playing)
              (.catch playing
                      (fn [_]
                        (set! (.-muted node) true)
                        (.play node))))))))
    [:video {:ref video-ref
             :key src
             :class (stl/css :video)
             :src src
             :controls true
             :auto-play true
             :muted true
             :plays-inline true
             :preload "auto"
             :aria-label title}]))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(mf/defc beginner-guide-modal*
  {::mf/register modal/components
   ::mf/register-as :beginner-guide}
  [_]
  (let [items          (guide-items)
        total          (count items)
        selected*      (mf/use-state 0)
        watched*       (mf/use-state #{})
        list-ref       (mf/use-ref nil)
        selected       @selected*
        watched        @watched*
        last-selected? (last-item? selected total)
        current        (nth items selected)
        media-url      (item-video-url current)

        close
        (mf/use-fn
         (fn []
           (dismiss!)))

        select-item
        (mf/use-fn
         (fn [index]
           (reset! selected* index)))

        go-next
        (mf/use-fn
         (mf/deps selected total current)
         (fn []
           (swap! watched* watch-item (:id current))
           (if-let [index (next-index selected total)]
             (reset! selected* index)
             (dismiss!))))]

    (mf/with-effect [selected]
      (when-let [list-node (mf/ref-val list-ref)]
        (when-let [card (dom/query list-node (dm/str "[data-index=\"" selected "\"]"))]
          (dom/scroll-into-view-if-needed! card true))))

    (mf/with-effect []
      (let [on-key
            (fn [event]
              (when (k/esc? event)
                (dom/stop-propagation event)
                (dismiss!)))
            key (events/listen js/document EventType.KEYDOWN on-key)]
        (fn []
          (events/unlistenByKey key))))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)
            :role "dialog"
            :aria-modal "true"
            :aria-labelledby "beginner-guide-title"}
      [:header {:class (stl/css :modal-header)}
       [:> heading* {:id "beginner-guide-title"
                     :level 2
                     :typography t/body-large
                     :class (stl/css :modal-title)}
        (tr "workspace.beginner-guide.title")]
       [:> icon-button* {:class (stl/css :modal-close)
                         :on-click close
                         :icon i/close
                         :variant "action"
                         :aria-label (tr "labels.close")}]]

      [:div {:class (stl/css :modal-body)}
       [:div {:class (stl/css :video-pane)}
        (if media-url
          [:> guide-video* {:src media-url
                            :title (:title current)}]
          [:div {:class (stl/css :video-placeholder)}
           (tr "workspace.beginner-guide.video" (inc selected))])]
       [:div {:ref list-ref
              :class (stl/css :card-list)}
        (for [[index item] (d/enumerate items)]
          [:> guide-card* {:key (:id item)
                           :item item
                           :index index
                           :selected (= index selected)
                           :watched (contains? watched (:id item))
                           :on-select select-item}])]]

      [:footer {:class (stl/css :modal-footer)}
       [:button {:type "button"
                 :class (stl/css :btn-skip)
                 :on-click close}
        (tr "workspace.beginner-guide.skip")]
       [:button {:type "button"
                 :class (stl/css :btn-next)
                 :on-click go-next}
        (if last-selected?
          (tr "workspace.beginner-guide.done")
          (tr "workspace.beginner-guide.next"))]]]]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.help-center-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.config :as cf]
   [app.main.data.event :as ev]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;; --- Help center menu

(mf/defc help-center-menu*
  []
  (let [show-menu* (mf/use-state false)
        show-menu? (deref show-menu*)

        open-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* true)))

        close-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* false)))

        open-shortcuts
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (-> (dw/toggle-layout-flag :shortcuts)
                         (vary-meta assoc ::ev/origin "workspace-help-menu")))
           (reset! show-menu* false)))

        ;; The label -> destination crossover below is intentional per the
        ;; design doc: Tutorials (新手教程) -> PENPOT_HELP_CENTER_URI and FAQ
        ;; (常见问题) -> PENPOT_LEARNING_CENTER_URI; do not "fix" the naming.
        open-tutorials
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-help-center-click"
                                            ::ev/origin "workspace-help-menu"}))
           (dom/open-new-window cf/help-center-uri)
           (reset! show-menu* false)))

        open-faq
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-learning-center-click"
                                            ::ev/origin "workspace-help-menu"}))
           (dom/open-new-window cf/learning-center-uri)
           (reset! show-menu* false)))]

    [:*
     [:button {:class (stl/css :help-trigger)
               :type "button"
               :aria-label (tr "labels.help-center")
               :title (tr "labels.help-center")
               :on-click open-menu}
      deprecated-icon/help
      [:span {:class (stl/css :trigger-label)} (tr "labels.help-center")]]

     [:> dropdown-menu* {:show show-menu?
                         :id "workspace-help-center-menu"
                         :on-close close-menu
                         :class (stl/css :help-menu)}
      [:> dropdown-menu-item* {:class (stl/css :help-menu-item)
                               :on-click    open-shortcuts
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (open-shortcuts event)))
                               :id          "help-menu-shortcuts"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.help.option.shortcuts")]]

      (when cf/help-center-uri
        [:> dropdown-menu-item* {:class (stl/css :help-menu-item)
                                 :on-click    open-tutorials
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (open-tutorials event)))
                                 :id          "help-menu-tutorials"}
         [:span {:class (stl/css :item-name)} (tr "workspace.header.help.option.tutorials")]
         [:span {:class (stl/css :item-icon)} deprecated-icon/external-link]])

      (when cf/learning-center-uri
        [:> dropdown-menu-item* {:class (stl/css :help-menu-item)
                                 :on-click    open-faq
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (open-faq event)))
                                 :id          "help-menu-faq"}
         [:span {:class (stl/css :item-name)} (tr "workspace.header.help.option.faq")]
         [:span {:class (stl/css :item-icon)} deprecated-icon/external-link]])]]))

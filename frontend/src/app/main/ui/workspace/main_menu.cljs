;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.main-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.exports.assets :as de]
   [app.main.data.exports.files :as fexp]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.plugins :as dp]
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as scd]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.versions :as dwv]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.dashboard.subscription :refer [get-subscription-type
                                               main-menu-power-up*]]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.onboarding.beginner-guide :as beginner-guide]
   [app.plugins.register :as preg]
   [app.util.dom :as dom]
   [app.util.embed :as embed]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [goog.events :as events]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

;; --- Header menu and submenus

;; The primary list and every secondary panel must share one :id:
;; `dropdown-evicts?` treats an identical id as a single logical menu, so the
;; two levels stay open together while any other dropdown (help center,
;; tokens, profile) evicts the whole group. Per-panel ids would make a
;; secondary panel close the primary list the moment it opens.
(def ^:private menu-group-id "workspace-menu")

(mf/defc help-info-menu*
  {::mf/props :obj
   ::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [layout on-close]}]
  (let [nav-to-helpc-center
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-help-center-click"
                                            ::ev/origin "workspace-menu:in-app"}))
           (dom/open-new-window "https://help.penpot.app")))

        nav-to-community
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-community-click"
                                            ::ev/origin "workspace-menu:in-app"}))
           (dom/open-new-window "https://community.penpot.app")))

        nav-to-youtube
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-tutorials-click"
                                            ::ev/origin "workspace-menu:in-app"}))
           (dom/open-new-window "https://www.youtube.com/c/Penpot")))

        nav-to-templates
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-libraries-click"
                                            ::ev/origin "workspace"}))
           (dom/open-new-window "https://penpot.app/libraries-templates")))

        nav-to-github
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-github-repository-click"
                                            ::ev/origin "workspace-menu:in-app"}))
           (dom/open-new-window "https://github.com/penpot/penpot")))

        nav-to-terms
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-terms-service-click"
                                            ::ev/origin "workspace-menu:in-app"}))
           (dom/open-new-window "https://penpot.app/terms")))

        nav-to-feedback
        (mf/use-fn #(st/emit! (dcm/go-to-feedback)))

        plugins?
        (features/active-feature? @st/state "plugins/runtime")

        show-shortcuts
        (mf/use-fn
         (mf/deps layout)
         (fn []
           (when (contains? layout :collapse-left-sidebar)
             (st/emit! (dw/toggle-layout-flag :collapse-left-sidebar)))

           (st/emit!
            (-> (dw/toggle-layout-flag :shortcuts)
                (vary-meta assoc ::ev/origin "workspace-header")))))

        show-release-notes
        (mf/use-fn
         (fn [event]
           (let [version (:main cf/version)]
             (st/emit! (ptk/event ::ev/event {::ev/name "show-release-notes" :version version}))
             (println version)
             (if (and (kbd/alt? event) (kbd/mod? event))
               (st/emit! (modal/show {:type :onboarding}))
               (st/emit! (modal/show {:type :release-notes :version version}))))))]

    [:> dropdown-menu* {:show true
                        :id menu-group-id
                        :on-close on-close
                        :class (stl/css-case :sub-menu true
                                             :help-info plugins?
                                             :help-info-old (not plugins?))}
     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-helpc-center
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-helpc-center event)))
                              :id          "file-menu-help-center"}
      [:span {:class (stl/css :item-name)} (tr "labels.help-center")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-community
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-community event)))
                              :id          "file-menu-community"}
      [:span {:class (stl/css :item-name)} (tr "labels.community")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-youtube
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-youtube event)))
                              :id          "file-menu-youtube"}
      [:span {:class (stl/css :item-name)} (tr "labels.tutorials")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    show-release-notes
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (show-release-notes event)))
                              :id          "file-menu-release-notes"}
      [:span {:class (stl/css :item-name)} (tr "labels.release-notes")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-templates
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-templates event)))
                              :id          "file-menu-templates"}
      [:span {:class (stl/css :item-name)} (tr "labels.libraries-and-templates")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-github
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-github event)))
                              :id          "file-menu-github"}
      [:span {:class (stl/css :item-name)} (tr "labels.github-repo")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-terms
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-terms event)))
                              :id          "file-menu-terms"}
      [:span {:class (stl/css :item-name)} (tr "auth.terms-of-service")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    show-shortcuts
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (show-shortcuts event)))
                              :id          "file-menu-shortcuts"}
      [:span {:class (stl/css :item-name)} (tr "label.shortcuts")]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :show-shortcuts))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     (when (contains? cf/flags :user-feedback)
       [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                :on-click    nav-to-feedback
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (nav-to-feedback event)))
                                :id          "file-menu-feedback"}
        [:span {:class (stl/css-case :feedback true
                                     :item-name true)} (tr "labels.give-feedback")]])]))

(mf/defc preferences-menu*
  {::mf/props :obj
   ::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [layout profile toggle-flag on-close]}]
  (let [read-only?           (mf/use-ctx ctx/workspace-read-only?)
        show-nudge-options (mf/use-fn #(modal/show! {:type :nudge-option}))
        webgl-enabled?     (features/use-feature "render-wasm/v1")
        toggle-webgl
        (mf/use-fn
         (mf/deps webgl-enabled?)
         (fn [event]
           (dom/stop-propagation event)
           (let [next-renderer (if webgl-enabled? :svg :wasm)
                 ev-name (if (= next-renderer :wasm)
                           "enable-webgl-rendering"
                           "disable-webgl-rendering")]
             (->> (rx/zip
                   (rp/cmd! :update-profile-props {:props {:renderer next-renderer}})
                   (rx/filter (ptk/type? ::ev/chunk-persisted) st/stream))
                  (rx/timeout 2000 (rx/of :timeout))
                  (rx/subs! (fn [_] (dom/reload-current-window true))
                            (fn [_] (st/emit! (ntf/error (tr "errors.generic"))))))
             (st/emit! (ev/event {::ev/name ev-name ::ev/origin "workspace-menu"})
                       (ptk/data-event ::ev/force-persist {})))))]

    [:> dropdown-menu* {:show true
                        :id menu-group-id
                        :class (stl/css-case :sub-menu true
                                            :preferences (not read-only?)
                                            :preferences-read-only read-only?)
                        :on-close on-close}
     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid   "scale-text"
                              :id          "file-menu-scale-text"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :scale-text)
         (tr "workspace.header.menu.disable-scale-content")
         (tr "workspace.header.menu.enable-scale-content"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :scale))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid   "snap-ruler-guides"
                              :id          "file-menu-snap-ruler-guides"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-ruler-guides)
         (tr "workspace.header.menu.disable-snap-ruler-guides")
         (tr "workspace.header.menu.enable-snap-ruler-guides"))]
      [:span {:class (stl/css :shortcut)}

       (for [sc (scd/split-sc (sc/get-tooltip :toggle-snap-ruler-guide))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid   "snap-guides"
                              :id          "file-menu-snap-guides"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-guides)
         (tr "workspace.header.menu.disable-snap-guides")
         (tr "workspace.header.menu.enable-snap-guides"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :toggle-snap-guides))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid   "dynamic-alignment"
                              :id          "file-menu-dynamic-alignment"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :dynamic-alignment)
         (tr "workspace.header.menu.disable-dynamic-alignment")
         (tr "workspace.header.menu.enable-dynamic-alignment"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :toggle-alignment))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid   "snap-pixel-grid"
                              :id          "file-menu-pixel-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-pixel-grid)
         (tr "workspace.header.menu.disable-snap-pixel-grid")
         (tr "workspace.header.menu.enable-snap-pixel-grid"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :snap-pixel-grid))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:on-click    show-nudge-options
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (show-nudge-options event)))
                              :data-testid   "snap-pixel-grid"
                              :id          "file-menu-nudge"}
      [:span {:class (stl/css :item-name)} (tr "modals.nudge-title")]]

     [:> dropdown-menu-item* {:on-click    toggle-webgl
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-webgl event)))
                              :data-testid "webgl-render"
                              :id          "file-menu-webgl-render"}
      [:span {:class (stl/css :item-name)}
       (if webgl-enabled?
         (tr "workspace.header.menu.disable-webgl")
         (tr "workspace.header.menu.enable-webgl"))]
      [:span {:class (stl/css-case :item-indicator true
                                   :active webgl-enabled?)}]]]))

(mf/defc view-menu*
  {::mf/props :obj
   ::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [layout toggle-flag on-close]}]
  (let [read-only?   (mf/use-ctx ctx/workspace-read-only?)

        toggle-color-palette
        (mf/use-fn
         (fn []
           (r/set-resize-type! :bottom)
           (st/emit! (dw/remove-layout-flag :textpalette)
                     (-> (dw/toggle-layout-flag :colorpalette)
                         (vary-meta assoc ::ev/origin "workspace-menu")))))

        toggle-text-palette
        (mf/use-fn
         (fn []
           (r/set-resize-type! :bottom)
           (st/emit! (dw/remove-layout-flag :colorpalette)
                     (-> (dw/toggle-layout-flag :textpalette)
                         (vary-meta assoc ::ev/origin "workspace-menu")))))]

    [:> dropdown-menu* {:show true
                        :id menu-group-id
                        :class (stl/css-case :sub-menu true
                                            :view (not read-only?)
                                            :view-read-only read-only?)
                        :on-close on-close}

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid   "rulers"
                              :id          "file-menu-rulers"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :rulers)
         (tr "workspace.header.menu.hide-rules")
         (tr "workspace.header.menu.show-rules"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :toggle-rulers))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]


     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid   "display-guides"
                              :id          "file-menu-guides"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :display-guides)
         (tr "workspace.header.menu.hide-guides")
         (tr "workspace.header.menu.show-guides"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :toggle-guides))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]


     (when-not ^boolean read-only?
       [:*
        [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                 :on-click    toggle-color-palette
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (toggle-color-palette event)))
                                 :id          "file-menu-color-palette"}
         [:span {:class (stl/css :item-name)}
          (if (contains? layout :colorpalette)
            (tr "workspace.header.menu.hide-palette")
            (tr "workspace.header.menu.show-palette"))]
         [:span {:class (stl/css :shortcut)}
          (for [sc (scd/split-sc (sc/get-tooltip :toggle-colorpalette))]
            [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

        [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                 :on-click    toggle-text-palette
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (toggle-text-palette event)))
                                 :id          "file-menu-text-palette"}
         [:span {:class (stl/css :item-name)}
          (if (contains? layout :textpalette)
            (tr "workspace.header.menu.hide-textpalette")
            (tr "workspace.header.menu.show-textpalette"))]
         [:span {:class (stl/css :shortcut)}
          (for [sc (scd/split-sc (sc/get-tooltip :toggle-textpalette))]
            [:span {:class (stl/css :shortcut-key) :key sc} sc])]]])

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid   "display-artboard-names"
                              :id          "file-menu-artboards"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :display-artboard-names)
         (tr "workspace.header.menu.hide-artboard-names")
         (tr "workspace.header.menu.show-artboard-names"))]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid   "show-pixel-grid"
                              :id          "file-menu-pixel-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :show-pixel-grid)
         (tr "workspace.header.menu.hide-pixel-grid")
         (tr "workspace.header.menu.show-pixel-grid"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :show-pixel-grid))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid   "hide-ui"
                              :id          "file-menu-hide-ui"}
      [:span {:class (stl/css :item-name)}
       (tr "workspace.shape.menu.hide-ui")]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :hide-ui))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]]))

(mf/defc edit-menu*
  {::mf/props :obj
   ::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [on-close]}]
  (let [select-all (mf/use-fn #(st/emit! (dw/select-all)))
        undo       (mf/use-fn #(st/emit! dwu/undo))
        redo       (mf/use-fn #(st/emit! dwu/redo))
        perms      (mf/use-ctx ctx/permissions)
        can-edit   (:can-edit perms)]

    [:> dropdown-menu* {:show true
                        :id menu-group-id
                        :class (stl/css-case :sub-menu true
                                             :edit true)
                        :on-close on-close}

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    select-all
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (select-all event)))
                              :id          "file-menu-select-all"}
      [:span {:class (stl/css :item-name)}
       (tr "workspace.header.menu.select-all")]
      [:span {:class (stl/css :shortcut)}

       (for [sc (scd/split-sc (sc/get-tooltip :select-all))]
         [:span {:class (stl/css :shortcut-key)
                 :key sc}
          sc])]]

     (when can-edit
       [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                :on-click    undo
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (undo event)))
                                :id          "file-menu-undo"}
        [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.undo")]
        [:span {:class (stl/css :shortcut)}
         (for [sc (scd/split-sc (sc/get-tooltip :undo))]
           [:span {:class (stl/css :shortcut-key)
                   :key sc}
            sc])]])

     (when can-edit
       [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                :on-click    redo
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (redo event)))
                                :id          "file-menu-redo"}
        [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.redo")]
        [:span {:class (stl/css :shortcut)}

         (for [sc (scd/split-sc (sc/get-tooltip :redo))]
           [:span {:class (stl/css :shortcut-key)
                   :key sc}
            sc])]])]))

(mf/defc file-menu*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [on-close file]}]
  (let [file-id      (:id file)
        shared?      (:is-shared file)

        objects      (mf/deref refs/workspace-page-objects)
        selected     (mf/deref refs/selected-shapes)
        all-frames   (->> (cfh/get-immediate-children objects uuid/zero)
                          (filterv cfh/frame-shape?))

        ;; If there are selected frames, use only those. Otherwise, use all frames
        selected-frames (filterv #(contains? selected (:id %)) all-frames)
        frames       (if (seq selected-frames) selected-frames all-frames)

        perms        (mf/use-ctx ctx/permissions)
        can-edit     (:can-edit perms)

        on-remove-shared
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (modal/show!
            {:type :delete-shared-libraries
             :origin :unpublish
             :ids #{file-id}
             :on-accept #(st/emit! (dwl/set-file-shared file-id false))
             :count-libraries 1})))

        on-remove-shared-key-down
        (mf/use-fn
         (mf/deps on-remove-shared)
         (fn [event]
           (when (kbd/enter? event)
             (on-remove-shared event))))

        on-add-shared
        (mf/use-fn
         (mf/deps file-id)
         (fn [_event]
           (let [on-accept #(st/emit! (dwl/set-file-shared file-id true))]
             (st/emit! (dcm/show-shared-dialog file-id on-accept)))))

        on-add-shared-key-down
        (mf/use-fn
         (mf/deps on-add-shared)
         (fn [event]
           (when (kbd/enter? event)
             (on-add-shared event))))

        on-show-version-history
        (mf/use-fn
         (mf/deps file-id)
         (fn [_]
           (st/emit! (dw/toggle-layout-flag :document-history))))

        on-show-version-history-key-down
        (mf/use-fn
         (mf/deps on-show-version-history)
         (fn [event]
           (when (kbd/enter? event)
             (on-show-version-history event))))

        on-pin-version
        (mf/use-fn
         (fn [_]
           (st/emit! (dwv/create-version))))

        on-pin-version-key-down
        (mf/use-fn
         (mf/deps on-pin-version)
         (fn [event]
           (when (kbd/enter? event)
             (on-pin-version event))))]

    [:> dropdown-menu* {:show true
                        :id menu-group-id
                        :class (stl/css-case :sub-menu true
                                             :file true)
                        :on-close on-close}


     (when can-edit
       [:*

        [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                 :on-click    on-pin-version
                                 :on-key-down on-pin-version-key-down
                                 :id          "file-menu-create-version"}
         [:span {:class (stl/css :item-name)}
          (tr "dashboard.create-version-menu")]]

        [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                 :on-click    on-show-version-history
                                 :on-key-down on-show-version-history-key-down
                                 :id          "file-menu-show-version-history"}
         [:span {:class (stl/css :item-name)}
          (tr "dashboard.show-version-history")]
         [:span {:class (stl/css :shortcut)}
          (for [sc (scd/split-sc (sc/get-tooltip :toggle-history))]
            [:span {:class (stl/css :shortcut-key) :key sc} sc])]]])]))

(mf/defc plugins-menu*
  {::mf/props :obj
   ::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [open-plugins on-close]}]
  (when (features/active-feature? @st/state "plugins/runtime")
    (let [plugins                  (preg/plugins-list)
          user-can-edit?           (:can-edit (deref refs/permissions))
          permissions-peek         (deref refs/plugins-permissions-peek)]
      [:> dropdown-menu* {:show true
                          :id menu-group-id
                          :class (stl/css-case :sub-menu true :plugins true)
                          :on-close on-close}
       [:> dropdown-menu-item* {:on-click    open-plugins
                                :class       (stl/css :submenu-item)
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (open-plugins event)))
                                :data-testid   "open-plugins"
                                :id          "file-menu-open-plugins"}
        [:span {:class (stl/css :item-name)}
         (tr "workspace.plugins.menu.plugins-manager")]
        [:span {:class (stl/css :shortcut)}
         (for [sc (scd/split-sc (sc/get-tooltip :plugins))]
           [:span {:class (stl/css :shortcut-key) :key sc} sc])]]


       (when (d/not-empty? plugins)
         [:div {:class (stl/css :separator)}])

       (for [[idx {:keys [plugin-id name host permissions] :as manifest}] (d/enumerate plugins)]
         (let [permissions        (or (get permissions-peek plugin-id) permissions)
               is-edition-plugin? (or (contains? permissions "content:write")
                                      (contains? permissions "library:write"))
               can-open?          (or user-can-edit?
                                      (not is-edition-plugin?))
               on-click
               (mf/use-fn
                (mf/deps can-open? name host manifest user-can-edit?)
                (fn [event]
                  (if can-open?
                    (do
                      (st/emit! (ptk/event ::ev/event {::ev/name "start-plugin"
                                                       ::ev/origin "workspace:menu"
                                                       :name name
                                                       :host host}))
                      (dp/open-plugin! manifest user-can-edit?))
                    (dom/stop-propagation event))))
               on-key-down
               (mf/use-fn
                (mf/deps can-open? name host manifest user-can-edit?)
                (fn [event]
                  (when can-open?
                    (when (kbd/enter? event)
                      (st/emit! (ptk/event ::ev/event {::ev/name "start-plugin"
                                                       ::ev/origin "workspace:menu"
                                                       :name name
                                                       :host host}))
                      (dp/open-plugin! manifest user-can-edit?)))))]
           [:> dropdown-menu-item* {:key         (dm/str "plugins-menu-" idx)
                                    :on-click    on-click
                                    :class       (stl/css-case :submenu-item true :menu-disabled (not can-open?))
                                    :on-key-down on-key-down}
            [:span {:class (stl/css :item-name)} name]
            (when-not can-open?
              [:span {:class (stl/css :item-icon)
                      :title (tr "workspace.plugins.error.need-editor")} deprecated-icon/help])]))])))

(defn- origin-path-allowed?
  "True when sessionStorage's EBEE_USER_KEY parses to an object whose
  originPathId contains \"/990001/\". Parsing is delegated to the
  shared reader (`app.util.embed/read-user-key`, single source of the
  EBEE_USER_KEY contract): a missing key, malformed JSON, a non-object
  value and a non-string originPathId all return false."
  []
  (let [parsed (embed/read-user-key)
        path   (when (some? parsed)
                 (unchecked-get parsed "originPathId"))]
    (boolean
     (and (string? path)
          (str/includes? path "/990001/")))))

(mf/defc menu*
  {::mf/props :obj}
  [{:keys [layout file profile]}]
  (let [read-only?     (mf/use-ctx ctx/workspace-read-only?)
        show-menu*     (mf/use-state false)
        show-menu?     (deref show-menu*)
        sub-menu*      (mf/use-state false)
        sub-menu       (deref sub-menu*)

        open-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* true)))

        close-all-menus
        (mf/use-fn
         (fn []
           (reset! show-menu* false)
           (reset! sub-menu* nil)))

        ;; Closing the group must clear sub-menu* too: the secondary panels
        ;; render outside the show-menu? guard, so leaving it set would orphan
        ;; a panel on screen with no primary list attached to it.
        close-menu
        (mf/use-fn
         (mf/deps close-all-menus)
         (fn [event]
           (dom/stop-propagation event)
           (close-all-menus)))

        close-sub-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! sub-menu* nil)))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [menu (-> (dom/get-current-target event)
                          (dom/get-data "testid")
                          (keyword))]
             (reset! sub-menu* menu))))

        on-power-up-click
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-pricing-click" ::ev/origin "workspace-menu"}))
           (dom/open-new-window "https://penpot.app/pricing")))

        toggle-flag
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [flag (-> (dom/get-current-target event)
                          (dom/get-data "testid")
                          (keyword))]
             (st/emit!
              (-> (dw/toggle-layout-flag flag)
                  (vary-meta assoc ::ev/origin "workspace-menu")))
             (reset! show-menu* false)
             (reset! sub-menu* nil))))

        open-plugins-manager
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* false)
           (reset! sub-menu* nil)
           (st/emit!
            (ptk/event ::ev/event {::ev/name "open-plugins-manager" ::ev/origin "workspace:menu"})
            (modal/show :plugin-management {}))))

        subscription           (:subscription (:props profile))
        subscription-type      (get-subscription-type subscription)

        ;; One-time "原型出码" guide popover (fork feature). It chains
        ;; after the help-center hint popover: it appears once the
        ;; beginner video has been viewed AND the hint's "知道了" has
        ;; been clicked (returning users therefore get it directly),
        ;; and stays until its own "知道了" click. The popover requires
        ;; the session user's originPathId to contain "/990001/" and is
        ;; hidden in view mode (same check as the beginner video modal);
        ;; the menu item is gated only on originPathId.
        prototype-enabled?    (origin-path-allowed?)
        view-mode?            (beginner-guide/current-view-mode?)
        storage-data          (mf/deref storage/global)
        video-viewed?         (true? (get storage-data beginner-guide/storage-key))
        help-hint-seen?       (true? (get storage-data beginner-guide/help-hint-storage-key))
        prototype-dismissed?  (true? (get storage-data ::prototype-code-guide-dismissed))
        show-prototype-guide? (and prototype-enabled?
                                    (not view-mode?)
                                    video-viewed?
                                    help-hint-seen?
                                    (not prototype-dismissed?))

        open-prototype-code
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (ptk/event ::ev/event {::ev/name "prototype-code-click"
                                            ::ev/origin "workspace-menu"}))
           (dom/open-new-window "https://doc.cmbchina.com/f/v?id=zf892V")
           (reset! show-menu* false)
           (reset! sub-menu* nil)))

        dismiss-prototype-guide!
        (mf/use-fn
         (fn []
           (swap! storage/global assoc ::prototype-code-guide-dismissed true)
           (reset! show-menu* false)
           (reset! sub-menu* nil)))]

    ;; The popover points at the menu list, so the list must be open
    ;; while the popover is visible.
    (mf/with-effect [show-prototype-guide?]
      (when show-prototype-guide?
        (reset! show-menu* true)
        (reset! sub-menu* nil)))

    ;; Clicks inside the popover are stopped by its own stop-propagation
    ;; handler, so any document click reaching this listener is an
    ;; outside click: dismiss the popover like the help-center hint.
    (mf/with-effect [show-prototype-guide?]
      (when show-prototype-guide?
        (let [on-doc-click
              (fn [event]
                (when-not (.-data-no-close ^js (dom/get-target event))
                  (dismiss-prototype-guide!)))
              key (events/listen globals/document EventType.CLICK on-doc-click)]
          (fn []
            (events/unlistenByKey key)))))

    (mf/with-effect []
      (let [disposable (->> st/stream
                            (rx/filter #(= :interrupt %))
                            (rx/subs! close-all-menus))]
        (partial rx/dispose! disposable)))


    [:*
     [:div {:class (stl/css :menu-wrapper)}
      [:button {:class (stl/css :menu-trigger)
                :type "button"
                :title (tr "shortcut-subsection.main-menu")
                :aria-label (tr "shortcut-subsection.main-menu")
                :on-click open-menu}
       deprecated-icon/menu-lines]

      (when show-prototype-guide?
        [:div {:class (stl/css-case :prototype-guide-popover true
                                    :prototype-guide-popover-read-only read-only?)
               :role "status"
               :data-no-close true
               :on-click (fn [event]
                           (dom/stop-propagation event))}
         [:span {:class (stl/css :prototype-guide-arrow)}]
         [:div {:class (stl/css :prototype-guide-body)}
          (tr "workspace.header.menu.prototype-code.guide.body")]
         [:div {:class (stl/css :prototype-guide-footer)}
          [:button {:class (stl/css :prototype-guide-got-it)
                    :type "button"
                    :data-no-close true
                    :on-click dismiss-prototype-guide!}
           (tr "workspace.header.help.guide.got-it")]]])]

     [:> dropdown-menu* {:show show-menu?
                         :id menu-group-id
                         :on-close close-menu
                         :class (stl/css :menu)}
      ;; File menu
      (when-not read-only?
        [:> dropdown-menu-item* {:class (stl/css :menu-item)
                                 :on-click    on-menu-click
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (on-menu-click event)))
                                 :on-pointer-enter on-menu-click
                                 :data-testid   "file"
                                 :id          "file-menu-file"}
         [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.file")]
         [:span {:class (stl/css :open-arrow)} deprecated-icon/arrow]])

      ;; Hidden: Edit menu
      #_[:> dropdown-menu-item* {:class (stl/css :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-testid   "edit"
                               :id          "file-menu-edit"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.edit")]
       [:span {:class (stl/css :open-arrow)} deprecated-icon/arrow]]

      [:> dropdown-menu-item* {:class (stl/css :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-testid   "view"
                               :id          "file-menu-view"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.view")]
       [:span {:class (stl/css :open-arrow)} deprecated-icon/arrow]]

      [:> dropdown-menu-item* {:class (stl/css :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-testid   "preferences"
                               :id          "file-menu-preferences"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.preferences")]
       [:span {:class (stl/css :open-arrow)} deprecated-icon/arrow]]

      ;; Fork feature: direct link, opens in a new window (no submenu).
      ;; Shown whenever the session user's originPathId contains
      ;; "/990001/"; view mode only suppresses the popover, not the item.
      (when prototype-enabled?
        [:> dropdown-menu-item* {:class (stl/css :menu-item)
                                 :on-click    open-prototype-code
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (open-prototype-code event)))
                                 :on-pointer-enter close-sub-menu
                                 :data-testid   "prototype-code"
                                 :id          "file-menu-prototype-code"}
         [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.prototype-code")]])

      #_(when (features/active-feature? @st/state "plugins/runtime")
        [:> dropdown-menu-item* {:class (stl/css :menu-item)
                                 :on-click    on-menu-click
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (on-menu-click event)))
                                 :on-pointer-enter on-menu-click
                                 :data-testid   "plugins"
                                 :id          "file-menu-plugins"}
         [:span {:class (stl/css :item-name)} (tr "workspace.plugins.menu.title")]
         [:span {:class (stl/css :open-arrow)} deprecated-icon/arrow]])


      #_[:> dropdown-menu-item* {:class (stl/css-case :menu-item true)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-testid   "help-info"
                               :id          "file-menu-help-info"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.help-info")]
       [:span {:class (stl/css :open-arrow)} deprecated-icon/arrow]]

      (when (and (contains? cf/flags :subscriptions) (not= "enterprise" subscription-type))
        [:> main-menu-power-up* {:close-sub-menu close-sub-menu}])

      ;; TODO remove this block when subscriptions is full implemented
      (when (contains? cf/flags :subscriptions-old)
        [:> dropdown-menu-item* {:class (stl/css-case :menu-item true)
                                 :on-click    on-power-up-click
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (on-power-up-click)))
                                 :on-pointer-enter close-sub-menu
                                 :id          "file-menu-power-up"}
         [:span {:class (stl/css :item-name)} (tr "subscription.workspace.header.menu.option.power-up")]])]

     (case sub-menu
       :file
       (when-not read-only?
         [:> file-menu* {:file file
                         :on-close close-sub-menu}])

       :edit
       [:> edit-menu*
        {:on-close close-sub-menu}]

       :view
       [:> view-menu*
        {:layout layout
         :toggle-flag toggle-flag
         :on-close close-sub-menu}]

       :preferences
       [:> preferences-menu*
        {:layout layout
         :profile profile
         :toggle-flag toggle-flag
         :on-close close-sub-menu}]

       :plugins
       [:> plugins-menu*
        {:open-plugins open-plugins-manager
         :on-close close-sub-menu}]

       :help-info
       [:> help-info-menu*
        {:layout layout
         :on-close close-sub-menu}]

       nil)]))

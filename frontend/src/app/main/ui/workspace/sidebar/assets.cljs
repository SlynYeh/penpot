;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.assets
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.components-list :as ctkl]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.assets :as dwa]
   [app.main.data.workspace.icons :as dwi]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu*]]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.main.ui.workspace.sidebar.assets.file-library :refer [file-library*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(mf/defc assets-libraries*
  {::mf/wrap [mf/memo]
   ::mf/private true}
  [{:keys [filters]}]
  (let [file-id   (mf/use-ctx ctx/current-file-id)
        files     (mf/deref refs/files)
        libraries (mf/with-memo [files file-id]
                    (->> (refs/select-libraries files file-id)
                         (vals)
                         (remove #(= file-id (:id %)))
                         (dwi/assets-libraries)
                         (map (fn [file]
                                (update file :data dissoc :pages-index)))
                         (sort-by #(str/lower (:name %)))))]

    (for [file libraries]
      [:> file-library*
       {:key (dm/str (:id file))
        :file file
        :is-local false
        :is-default-open false
        :filters filters}])))

(def ^:private ref:local-library
  (l/derived (fn [file]
               (update file :data dissoc :pages-index))
             refs/file))

(defn has-assets?
  "True when a file's `:data` map holds at least one non-deleted component,
  color or typography. Drives whether the local library starts expanded: a
  library with nothing to show starts collapsed."
  [file-data]
  (boolean
   (or (seq (ctkl/components-seq file-data))
       (seq (:colors file-data))
       (seq (:typographies file-data)))))

(mf/defc assets-local-library*
  {::mf/private true}
  [{:keys [filters]}]
  (let [file (mf/deref ref:local-library)]
    (when-not (dwi/iconpark-library? file)
      [:> file-library*
       {:file file
        :is-local true
        :is-default-open true
        :filters filters}])))
  (let [file (mf/deref ref:local-library)

        ;; Unlike shared libraries, the local one opens by default — but only
        ;; when it actually has assets. An empty library stays folded so the
        ;; panel doesn't start with a blank section; it opens on its own once
        ;; the first asset is added.
        is-default-open (mf/with-memo [file]
                          (has-assets? (:data file)))]

    [:> file-library*
     {:file file
      :is-local true
      :is-default-open is-default-open
      :filters filters}]))

(defn- toggle-values
  [v a b]
  (if (= v a) b a))

;; Per-file, session-scoped (in-memory only) so the search term and section
;; filter survive switching between the Layers and Assets sidebar tabs without
;; leaking across files or persisting across reloads.
(defonce ^:private session-filters*
  (atom {}))

(mf/defc assets-toolbox*
  {::mf/wrap [mf/memo]}
  [{:keys [size file-id]}]
  (let [read-only?     (mf/use-ctx ctx/workspace-read-only?)
        filters*       (mf/use-state
                        (fn []
                          (-> (or (get @session-filters* file-id)
                                  {:term ""
                                   :section "all"})
                              (assoc :ordering (dwa/get-current-assets-ordering)
                                     :list-style (dwa/get-current-assets-list-style)
                                     :open-menu false))))
        filters        (deref filters*)
        term           (:term filters)
        list-style     (:list-style filters)
        menu-open?     (:open-menu filters)
        section        (:section filters)
        ordering       (:ordering filters)
        reverse-sort?  (= :desc ordering)
        libs           (mf/deref refs/libraries)
        num-libs       (count libs)
        file           (get libs file-id)
        shared?        (:is-shared file)
        components     (mf/with-memo [file] (ctkl/components (:data file)))

        toggle-ordering
        (mf/use-fn
         (mf/deps ordering)
         (fn []
           (let [new-value (toggle-values ordering :asc :desc)]
             (swap! filters* assoc :ordering new-value)
             (dwa/set-current-assets-ordering! new-value))))

        toggle-list-style
        (mf/use-fn
         (mf/deps list-style)
         (fn []
           (let [new-value (toggle-values list-style :thumbs :list)]
             (swap! filters* assoc :list-style new-value)
             (dwa/set-current-assets-list-style! new-value))))

        on-search-term-change
        (mf/use-fn
         (fn [event]
           (st/emit! (dw/clear-assets-section-open))
           (swap! filters* assoc :term event)))

        on-section-filter-change
        (mf/use-fn
         (fn [event]
           (let [value (or (-> (dom/get-target event)
                               (dom/get-value))
                           (as-> (dom/get-current-target event) $
                             (dom/get-attribute $ "data-testid")))]
             (st/emit! (dw/clear-assets-section-open))
             (swap! filters* assoc :section value :open-menu false))))

        show-libraries-dialog
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (modal/show! :libraries-dialog {:file-id file-id})))

        on-open-menu
        (mf/use-fn  #(swap! filters* update :open-menu not))

        on-menu-close
        (mf/use-fn #(swap! filters* assoc :open-menu false))

        ;; Width of the filter dropdown panel, in px. Kept as a binding so the
        ;; right-alignment math below stays in sync with the `:width` prop.
        menu-width 120

        ;; Ref to the actions container so we can measure the filter button's
        ;; right edge and right-align the dropdown panel with it.
        actions-ref (mf/use-ref nil)

        ;; Dropdown panel position, computed when the menu opens.
        menu-pos* (mf/use-state {:left 0 :top 46})

        ;; Recompute panel position whenever the menu opens so it stays
        ;; right-aligned with the filter button even after layout changes.
        _ (mf/use-effect
           (mf/deps menu-open?)
           (fn []
             (when menu-open?
               (let [el (mf/ref-val actions-ref)]
                 (when (some? el)
                   (let [rect (dom/get-bounding-rect el)]
                     ;; The filter button is the last visible child of .actions
                     ;; (the manage-libraries button next to it is display:none
                     ;; and takes no space), so the container's `right` edge is
                     ;; the button's `right` edge. Anchor the panel's right edge
                     ;; to it, and hang the panel just below the header with a
                     ;; small 2px gap.
                     (swap! menu-pos* assoc
                            :left (- (get rect :right) menu-width)
                            :top  (+ (get rect :bottom) 2))))))))

        ;; Memoize options to prevent infinite re-render loops when dev-tools are open.
        ;;
        ;; Problem: When dev-tools are open, they constantly monitor the application state,
        ;; triggering frequent updates to okulary refs. This causes the parent component to
        ;; re-render constantly, recreating the options array on every render.
        ;;
        ;; The context-menu* component has a mf/with-effect that depends on [options].
        ;; When options are recreated (even with identical content), the effect runs,
        ;; updating the internal state, which triggers another re-render, creating
        ;; an infinite loop: render -> new options -> effect -> state update -> render...
        options
        (mf/with-memo [on-section-filter-change]
          [{:name    (tr "workspace.assets.box-filter-all")
            :id      "all"
            :handler on-section-filter-change}
           {:name    (tr "workspace.assets.components")
            :id      "components"
            :handler on-section-filter-change}
           {:name    (tr "workspace.assets.colors")
            :id      "colors"
            :handler on-section-filter-change}
           {:name    (tr "workspace.assets.typography")
            :id      "typographies"
            :handler on-section-filter-change}])]

    (mf/with-effect [file-id term section]
      (swap! session-filters* assoc file-id {:term term :section section}))

    [:article  {:class (stl/css :assets-bar)}
     [:div {:class (stl/css :assets-header)}
      [:div {:class (stl/css :search-wrapper)}
       [:> search-bar* {:on-change on-search-term-change
                        :value term
                        :placeholder (tr "workspace.assets.search")}]]

      (when-not ^boolean read-only?
        [:div {:class (stl/css :actions)
               :ref actions-ref}
         [:> icon-button* {:variant (if menu-open? "secondary" "ghost")
                           :icon i/filter
                           :aria-label (tr "workspace.assets.filter")
                           :tooltip-placement "top"
                           :on-click on-open-menu}]
         ;; Kept in the DOM (and reachable by tests) but hidden via CSS; the
         ;; search box and the filter button are meant to fill the row.
         [:> icon-button* {:class (stl/css :manage-library-btn)
                           :variant "ghost"
                           :icon i/book-open
                           :aria-label (tr "workspace.assets.manage-library")
                           :tooltip-placement "top"
                           :on-click show-libraries-dialog
                           :data-testid "libraries"}]])

      [:> context-menu*
       {:on-close on-menu-close
        :selectable true
        :selected section
        :show menu-open?
        :fixed true
        :min-width false
        :width menu-width
        :top (:top @menu-pos*)
        :left (:left @menu-pos*)
        :options options}]]


     [:& (mf/provider cmm/assets-filters) {:value filters}
      [:& (mf/provider cmm/assets-toggle-ordering) {:value toggle-ordering}
       [:& (mf/provider cmm/assets-toggle-list-style) {:value toggle-list-style}
        [:*
         [:> assets-local-library* {:filters filters}]
         [:> assets-libraries* {:filters filters}]]]]]]))

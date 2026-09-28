;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.ds.product.loader
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def tip-badge-icon-id i/alarm)

(def tip-message-ids
  ["loader.tips.01.message"
   "loader.tips.02.message"
   "loader.tips.03.message"
   "loader.tips.04.message"
   "loader.tips.05.message"
   "loader.tips.06.message"
   "loader.tips.07.message"
   "loader.tips.08.message"])

(defonce ^:private selected-tip-id*
  (atom nil))

(defonce ^:private prepared-tip*
  (atom nil))

(defn- translation-ready
  "Return the translated string only when it is real copy, not the i18n key."
  [code]
  (let [code  (d/name code)
        value (tr code)]
    (when (and (not (i18n/empty-string? value))
               (not= value code))
      value)))

(defn- ensure-tip-message-id!
  []
  (or @selected-tip-id*
      (reset! selected-tip-id* (rand-nth tip-message-ids))))

(defn- prepare-loader-tip
  "Badge label + random message, or nil until both translations are loaded."
  []
  (or @prepared-tip*
      (let [message-id (ensure-tip-message-id!)
            label      (translation-ready "loader.tips.label")
            message    (translation-ready message-id)]
        (when (and label message)
          (reset! prepared-tip* {:label label :message message})))))

(def ^:private
  svg:loader-path-1
  "M128.273 0l-3.9 2.77L0 91.078l128.273 91.076 549.075-.006V.008L128.273 0zm20.852 30l498.223.006V152.15l-498.223.007V30zm-25 9.74v102.678l-49.033-34.813-.578-32.64 49.61-35.225z")

(def ^:private
  svg:loader-path-2
  "M134.482 157.147v25l518.57.008.002-25-518.572-.008z")

(mf/defc loader-icon*
  {::mf/private true}
  [{:keys [width height title] :rest props}]
  (let [class (stl/css :loader)
        props (mf/spread-props props {:viewBox "0 0 677.34762 182.15429"
                                      :role "status"
                                      :width width
                                      :height height
                                      :class class})]
    [:> :svg props
     [:title title]
     [:g
      [:path {:d svg:loader-path-1}]
      [:path {:class (stl/css :loader-line)
              :d svg:loader-path-2}]]]))

(mf/defc loader-tips*
  {::mf/private true}
  []
  (let [tip* (mf/use-state @prepared-tip*)]

    ;; Resolve copy before the first paint when translations are already loaded.
    (mf/with-layout-effect []
      (when (nil? @tip*)
        (when-let [copy (prepare-loader-tip)]
          (reset! tip* copy))))

    ;; Translations load asynchronously; keep waiting until both strings exist.
    (mf/with-effect []
      (when (nil? @prepared-tip*)
        (let [interval-id* (volatile! nil)]
          (vreset! interval-id*
                   (js/setInterval
                    (fn []
                      (when-let [copy (prepare-loader-tip)]
                        (js/clearInterval @interval-id*)
                        (reset! tip* copy)))
                    32))
          (fn []
            (js/clearInterval @interval-id*)))))

    (when-let [{:keys [label message]} @tip*]
      [:div {:class (stl/css :tips-container)}
       [:div {:class (stl/css :tip-badge)}
        [:> icon* {:icon-id tip-badge-icon-id
                   :size "s"}]
        [:span {:class (stl/css :tip-badge-label)}
         label]]
       [:div {:class (stl/css :tip-message)}
        message]])))

(def ^:private schema:loader
  [:map
   [:class {:optional true} :string]
   [:width {:optional true} :int]
   [:height {:optional true} :int]
   [:title {:optional true} :string]
   [:overlay {:optional true} :boolean]
   [:file-loading {:optional true} :boolean]])

(mf/defc loader*
  {::mf/schema schema:loader}
  [{:keys [class width height title overlay children file-loading] :rest props}]
  (let [width  (or width (when (some? height) (mth/ceil (* height (/ 100 27)))) 100)
        height (or height (when (some? width) (mth/ceil (* width (/ 27 100)))) 27)

        class  (dm/str (d/nilv class "") " "
                       (stl/css-case :wrapper true
                                     :wrapper-overlay overlay
                                     :file-loading file-loading))

        title  (or title (tr "labels.loading"))]

    [:> :div {:class class}
     [:div {:class (stl/css :loader-content)}
      [:> loader-icon* {:title title
                        :width width
                        :height height}]
      (when file-loading
        [:> loader-tips*])]

     children]))

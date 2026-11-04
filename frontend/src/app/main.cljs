;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.common.types.objects-map]
   [app.config :as cf]
   [app.main.data.auth :as da]
   [app.main.data.event :as ev]
   [app.main.data.profile :as dp]
   [app.main.data.websocket :as ws]
   [app.main.errors]
   [app.main.features :as feat]
   [app.main.rasterizer :as thr]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui :as ui]
   [app.main.ui.alert]
   [app.main.ui.confirm]
   [app.main.ui.css-cursors :as cur]
   [app.main.ui.delete-shared]
   [app.main.ui.routes :as rt]
   [app.main.worker :as mw]
   [app.plugins :as plugins]
   [app.util.dom :as dom]
   [app.util.embed :as embed]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [debug]
   [features]
   [potok.v2.core :as ptk]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(log/setup! {:app :info})
(log/set-level! :debug)

(when (= :browser cf/target)
  (log/inf :version (:full cf/version)
           :asserts *assert*
           :build-date cf/build-date
           :public-uri (dm/str cf/public-uri)
           :session-id (str cf/session-id))
  (log/inf :hint "enabled flags" :flags (str/join " " (map name cf/flags))))

(declare reinit)

;; Intercept the browser "save page" shortcut (Ctrl+S / Cmd+S) so it
;; does nothing instead of opening the save dialog. Penpot persists
;; changes automatically, so there is nothing to save.
(defonce disable-save-shortcut
  (when (= :browser cf/target)
    (.addEventListener js/window "keydown"
                       (fn [event]
                         (when (and (or (.-ctrlKey ^js event)
                                        (.-metaKey ^js event))
                                    (= "s" (str/lower (.-key ^js event))))
                           (.preventDefault event)
                           (.stopPropagation event)))
                       true)))

(defonce app-root
  (let [el (dom/get-element "app")]
    (mf/create-root el)))

(defn init-ui
  []
  (mf/render! app-root (mf/element ui/app)))

(defn- initialize-rasterizer
  []
  (ptk/reify ::initialize-rasterizer
    ptk/EffectEvent
    (effect [_ _ _]
      ;; The rasterizer is used for the dashboard thumbnails
      (thr/init!))))

(defn- first-value
  "Resolve with the first value of `obs`. The observable is expected to
   emit; if it completes empty the promise stays pending (callers rely on
   their own timeout)."
  [obs]
  (p/create (fn [resolve _]
              (rx/subscribe (rx/take 1 obs) resolve))))

(defn- probe-authenticated?
  "Promise of boolean: does the current cookie jar authenticate? Used by the
   embed boot gate to notice the parent planting the auth cookie without (or
   before) the postMessage credentials. Anonymous profiles return 200 with
   :id = uuid/zero, so this never throws for a logged-out jar."
  []
  (->> (rp/cmd! :get-profile)
       (rx/map da/is-authenticated?)
       (rx/catch (constantly (rx/of false)))
       (first-value)))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :session-id cf/session-id))

    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (rx/of (ev/initialize)
              (dp/refresh-profile))

       ;; Watch for profile deletion events
       (->> stream
            (rx/filter dp/profile-deleted-event?)
            (rx/map da/logged-out))

       ;; Once profile is fetched, initialize all penpot application
       ;; routes
       (->> stream
            (rx/filter dp/profile-fetched?)
            (rx/take 1)
            (rx/map #(rt/init-routes)))

       ;; Once profile fetched and the current user is authenticated,
       ;; proceed to initialize the websockets connection.
       (->> stream
            (rx/filter dp/profile-fetched?)
            (rx/map deref)
            (rx/filter dp/is-authenticated?)
            (rx/take 1)
            (rx/map #(ws/initialize)))

       (->> stream
            (rx/filter (ptk/type? ::feat/initialize))
            (rx/take 1)
            (rx/map #(initialize-rasterizer)))))))

(defn- boot!
  "Emits everything that starts backend traffic. Kept in a single call
  because the iframe credentials must be in place before any of it goes
  out: `initialize` fetches the profile and `ev/initialize` fetches the
  enabled flags."
  []
  (st/emit! (plugins/initialize)
            (initialize)))

(defn ^:export init
  [options]
  ;; WORKAROUND: we set this really not useful property for signal a
  ;; side effect and prevent GCC remove it. We need it because we need
  ;; to populate the Date prototype with transit related properties
  ;; before SES hardening is applied on loading MCP plugin
  (unchecked-set js/globalThis "penpotStartDate"
                 (-> (ct/now)
                     (t/encode-str)
                     (t/decode-str)))

  ;; Before initializing anything, check if the browser has loaded
  ;; stale JS from a previous deployment. If so, do a hard reload so
  ;; the browser fetches fresh assets matching the current index.html.
  (if (cf/stale-build?)
    (cf/throttled-reload
     :reason (dm/str "stale JS: compiled=" cf/compiled-version-tag
                     " expected=" cf/version-tag))
    (do
      (some-> (unchecked-get options "defaultTranslations")
              (i18n/set-default-translations))
      (mw/init!)
      (i18n/init)
      (cur/init-styles)

      ;; Mounting the UI early is safe: nothing is rendered until a route
      ;; is set, which only happens once the profile has been fetched.
      (init-ui)

      ;; When embedded in a third party system, booting is gated: wait for
      ;; the postMessage credentials first (happy path, no uncredentialed
      ;; requests); past `embed-timeout-ms` start probing the cookie jar and
      ;; re-posting `ready` (cold start recovery); at `embed-boot-deadline-ms`
      ;; boot degraded exactly like the old fixed timeout did. Top-level
      ;; loads boot immediately.
      (let [handshake (embed/start!)
            degraded  (volatile! false)]
        (if (embed/embedded?)
          (-> (embed/wait-boot! {:handshake handshake
                                 :probe probe-authenticated?
                                 :timeout-ms cf/embed-timeout-ms
                                 :deadline-ms (max cf/embed-timeout-ms
                                                   cf/embed-boot-deadline-ms)})
              (p/then (fn [outcome]
                        (when (= outcome :deadline)
                          (vreset! degraded true)
                          (log/wrn :hint "embed: timed out waiting for credentials"))
                        (when (= outcome :authenticated)
                          (log/inf :hint "embed: session cookie detected"))
                        (log/inf :hint "embed: boot gate opened" :outcome outcome)
                        (boot!)))
              (p/catch (fn [cause]
                         (log/err :hint "embed: boot gate failed, booting anyway"
                                  :cause cause)
                         (boot!))))

          ;; Not embedded: the handshake is already resolved with nil.
          (-> handshake
              (p/then (fn [_] (boot!)))
              (p/catch (fn [cause]
                         (log/err :hint "embed: handshake failed, booting anyway"
                                  :cause cause)
                         (boot!)))))

        ;; Backstop: credentials that only arrive after a degraded boot still
        ;; refresh the session once (unchanged semantics from the old flow).
        (-> handshake
            (p/then (fn [creds]
                      (when (and (some? creds) @degraded)
                        (log/wrn :hint "embed: credentials arrived late, refreshing profile")
                        (st/emit! (dp/refresh-profile)))))
            (p/catch (fn [cause]
                       (log/err :hint "embed: late credentials handling failed"
                                :cause cause))))))))

(defn ^:export reinit
  ([]
   (reinit false))
  ([hard?]
   ;; The hard flag will force to unmount the whole UI and will redraw every component
   (when hard?
     (mf/unmount! app-root)
     (set! app-root (mf/create-root (dom/get-element "app"))))
   (st/emit! (ev/initialize))
   (init-ui)))

(defn ^:dev/after-load after-load
  []
  (reinit))

(set! (.-stackTraceLimit js/Error) 50)

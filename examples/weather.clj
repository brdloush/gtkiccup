(ns weather
  "A weather app, in babashka, over GTK4 + libadwaita.

   Open-Meteo, so there is no API key and nothing to sign up for. The whole UI
   is a pure function of one map; a worker refreshes it and the reconciler
   patches only what changed.

   Layout is fixed -- 24 hourly cells, 7 day rows, 5 detail rows, always. Rows
   are created once and patched forever, which is what lets this app skip both
   keyed reconciliation and widget-lifetime management.

   English and Czech: the strings are in `weather-i18n`, the language starts
   from the environment, and the header switches it. Nothing here translates
   anything -- `openmeteo` already hands over a view model in the language in
   force, so a switch is just a rebuild from the cached response.

   The hourly/day/detail rows are late-bound ALIASES (`:weather/hour`,
   `:weather/day`, `:weather/detail`) and every header action is a DATA handler
   dispatched by the one `ui/set-dispatch!` fn below. The page resolves the
   localized now-label and reads `@state` before emitting each row, so nothing
   live or language-dependent is baked into an expansion -- a units or language
   switch rebuilds the view model, which moves the attrs and re-expands only
   those rows. See docs/widgets.md (#components-as-aliases, #handlers-as-data).

   Run it with `bb weather`."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [gtkiccup.adw :as adw]
            [gtkiccup.alias :as ga]
            [gtkiccup.core :as ui]
            [gtkiccup.ffi :as g]
            [gtkiccup.ratom :as r]
            [i18n :refer [t]]
            [openmeteo :as om]
            [weather-css :as wcss]
            [weather-i18n]))

(def css
  "The stylesheet, re-exported so tooling does not need to know where it lives."
  wcss/css)

;; ---------------------------------------------------------------------------
;; config and cache
;; ---------------------------------------------------------------------------

(def ^:dynamic *config-path*
  "Where place, units and the cached response live. Dynamic so tests can point
   it at a temp file instead of the real one."
  (str (or (System/getenv "XDG_CONFIG_HOME")
           (str (System/getProperty "user.home") "/.config"))
       "/bb-weather.edn"))

(defn default-config
  "No IP geolocation: that would mean handing our address to a third party.
   The city lives here, and the geocoding call sends only what you type.

   A function, not a constant, because the default place names itself in the
   language in force -- and that is only known once the config has been read."
  []
  {:place {:name (t :place/name) :region (t :place/name) :country (t :place/country)
           :lat 50.0755 :lon 14.4378}
   :units :metric
   ;; the environment decides on a first run; the header decides after that
   :lang (i18n/detect)})

(defn read-config
  "Reads the config and **applies the language in it**, because everything
   after this point -- the default place's own name included -- is built in
   whatever language is in force. A file with no `:lang`, or no file at all,
   takes the language from the environment."
  []
  (let [saved (try (when (fs/exists? *config-path*)
                     (edn/read-string (slurp *config-path*)))
                   (catch Exception _ nil))]
    (i18n/set-lang! (or (:lang saved) (i18n/detect)))
    (merge (default-config) saved)))

(defn write-config! [m]
  (try
    (fs/create-dirs (fs/parent *config-path*))
    (spit *config-path* (pr-str (select-keys m [:place :units :lang :cache :fetched-at])))
    (catch Exception e
      (println "[weather] could not save config:" (ex-message e)))))

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(defn- vm-from
  "The view model for a config, from whatever response is cached in it. nil
   when nothing has been fetched yet. Both the units switch and the language
   switch are this and nothing more."
  [{:keys [cache place units fetched-at]}]
  (when cache
    (om/view-model cache place {:units units :fetched-at fetched-at})))

(defonce state
  (r/atom {:config (read-config)
           :vm nil
           :loading? true
           :error nil}))

(defn reset-state!
  "Reloads config from *config-path*. For tests, and for after a config edit."
  []
  (reset! state {:config (read-config) :vm nil :loading? true :error nil}))

(defonce overlay (atom nil))

(def refresh-ms (* 15 60 1000))

(defn- apply-response!
  "Store a fresh response, cache it, and rebuild the view model."
  [data]
  (let [now (System/currentTimeMillis)]
    (swap! state
           (fn [s]
             (let [cfg (assoc (:config s) :cache data :fetched-at now)]
               (write-config! cfg)
               (assoc s
                      :config cfg
                      :vm (om/view-model data (:place cfg)
                                         {:units (:units cfg) :fetched-at now})
                      :loading? false
                      :error nil))))))

(defn- show-cached!
  "Render whatever was on disk, so the window opens with real content instead
   of a spinner. Marked with its age by the banner."
  []
  (swap! state (fn [s] (if-let [vm (vm-from (:config s))] (assoc s :vm vm) s))))

(defn refresh!
  "Fetch on this thread -- callers put it on a worker. Never throws."
  []
  (swap! state assoc :loading? true)
  (try
    (apply-response! (om/fetch! (:place (:config @state))))
    (catch Exception e
      (swap! state assoc :loading? false :error (ex-message e)))))

(defn start-polling! []
  (let [running? (volatile! true)]
    (future
      (while @running?
        (refresh!)
        (Thread/sleep refresh-ms)))
    #(vreset! running? false)))

(defn set-place! [place]
  (swap! state (fn [s] (-> s
                           (assoc-in [:config :place] place)
                           (assoc :vm nil :loading? true))))
  (write-config! (:config @state))
  (future (refresh!)))

(defn toggle-units! []
  (swap! state (fn [s]
                 (let [cfg (update-in (:config s) [:units]
                                      {:metric :imperial :imperial :metric})]
                   (write-config! cfg)
                   (assoc s :config cfg :vm (vm-from cfg)))))
  (future (refresh!)))

(defn next-language
  "The language after this one, wrapping. Two of them, so it is a toggle.
   Anything unknown lands on the first, which is English."
  [lang]
  (let [ls i18n/languages]
    (nth ls (mod (inc (.indexOf ^java.util.List ls lang)) (count ls)))))

(defn set-language!
  "Switches language everywhere. No fetch: every string the UI shows is built
   from the cached response, so rebuilding the view model is the whole job."
  [lang]
  (let [lang (i18n/set-lang! lang)]
    (swap! state (fn [s]
                   (let [cfg (assoc (:config s) :lang lang)]
                     (write-config! cfg)
                     (assoc s :config cfg :vm (vm-from cfg)))))))

(ui/set-dispatch!
  (fn [{:gtkiccup/keys [args]} [action]]
    (case action
      :weather/search
      (let [q (first args)]
        (future
          (if-let [hit (first (om/search-places! q))]
            (do (set-place! hit)
                (when-let [o @overlay]
                  (adw/toast! o (str (:name hit) ", " (:country hit)))))
            (when-let [o @overlay]
              (adw/toast! o (t :ui/no-place q))))))

      :weather/refresh (future (refresh!))

      :weather/units   (toggle-units!)

      :weather/lang    (set-language! (next-language i18n/*lang*)))))

;; ---------------------------------------------------------------------------
;; view
;; ---------------------------------------------------------------------------

(ga/register-alias! :weather/hour
  {:purity :pure}
  (fn [{:keys [now? label temp-label precip icon]} _children]
    [:vbox {:spacing 3 :halign :center
            :class (if now? ["hour" "now"] ["hour"])}
     [:label {:class "h-time" :halign :center :label label}]
     [:icon {:icon icon :size 20 :halign :center}]
     [:label {:class "h-temp" :halign :center :label temp-label}]
     [:label {:class "h-pop" :halign :center
              :label (if (and precip (pos? precip)) (str precip "%") " ")}]]))

(defn hero [{:keys [place current today hourly sky-class]} {:keys [now feels-like hi lo]}]
  ;; The now-label and the feels/hi/lo captions arrive pre-localized from the
  ;; page; `hero` resolves no language itself. The hourly alias gets the now-string
  ;; as a plain attr, putting it in the cache key -- a language switch (which
  ;; re-runs the page) moves that one attr and re-expands only the now-cell.
  [:bin {:class ["hero" sky-class]}
   [:vbox {:spacing 2 :margin 18}
    [:label {:class "place" :halign :center
             :label (str (:name place)
                         (when (:country place) (str "  ·  " (:country place))))}]
    [:label {:class "temp" :halign :center :label (:temp current)}]
    [:hbox {:spacing 8 :halign :center}
     [:icon {:icon (:icon current) :size 22}]
     [:label {:class "condition" :label (:label current)}]]
    [:label {:class "sub" :halign :center
             :label (str feels-like " " (:feels current)
                         "   ·   " hi " " (:hi today)
                         "   " lo " " (:lo today))}]
    [:scroll {:h :automatic :v :never :hexpand true :margin 8 :class "hourly"}
     (into [:hbox {:spacing 2 :halign :center}]
           (map-indexed (fn [i {:keys [time temp-label precip icon]}]
                          (let [now? (zero? i)]
                            [:weather/hour {:now? now?
                                            :label (if now? now time)
                                            :temp-label temp-label
                                            :precip precip
                                            :icon icon}]))
                        hourly))]]])

(ga/register-alias! :weather/day
  {:purity :pure}
  (fn [{:keys [day icon hi lo precip]} _children]
    [:row {:title day :class "dayrow"}
     [:icon {:slot :prefix :icon icon :size 20}]
     [:hbox {:slot :suffix :spacing 12 :valign :center}
      [:label {:class "pop" :label (if (and precip (pos? precip)) (str precip "%") "")}]
      [:label {:class "d-hi" :label hi}]
      [:label {:class ["d-lo" "dim-label"] :label lo}]]]))

(ga/register-alias! :weather/detail
  {:purity :pure}
  (fn [{:keys [title value icon]} _children]
    [:row {:title title}
     [:icon {:slot :prefix :icon icon :size 18}]
     [:label {:slot :suffix :class "numeric" :valign :center :label value}]]))

(defn dashboard [vm lbl]
  ;; day and detail rows are emitted as ALIAS nodes (plain data); each value is
  ;; handed down as an attr so the expansions stay pure over their args. The two
  ;; group titles are localized strings resolved by the page and passed in via
  ;; `lbl` -- `dashboard` calls no `t`. `vm` and `lbl` are forwarded to `hero`.
  (let [{:keys [daily details place]} vm]
    [:vbox {:spacing 0}
     (hero vm lbl)
     [:clamp {:max 620}
      [:vbox {:spacing 14 :margin 14}
       (into [:group {:title (:week-title lbl)}]
             (map (fn [{:keys [day icon hi lo precip]}]
                    [:weather/day {:day day :icon icon :hi hi :lo lo :precip precip}])
                  daily))
       (into [:group {:title (:details-title lbl)
                      :description (str (:timezone place)
                                        (when (:elevation place)
                                          (format "  ·  %.0f m" (:elevation place))))}]
             (map (fn [{:keys [title value icon]}]
                    [:weather/detail {:title title :value value :icon icon}])
                  details))]]]))

(defn loading [lbl]
  ;; the strings come in from the page's `lbl`; nothing here calls `t`.
  [:status-page {:icon "weather-few-clouds-symbolic"
                 :title (:loading-title lbl)
                 :description (:loading-sub lbl)}])

(defn failed [err lbl]
  ;; the title comes in from the page's `lbl`; only the raw error string is local.
  [:status-page {:icon "network-offline-symbolic"
                 :title (:failed-title lbl)
                 :description (str err)}])

(defn home [s]
  ;; The page is the only function here that reads live state: the atom (deref-ed
  ;; once in `app` and handed to `s`), the clock (for the stale banner) and the
  ;; active language. It resolves EVERY localized string ONCE into `lbl` and
  ;; threads that map down, so `dashboard`, `hero`, `loading`, `failed` and the
  ;; alias bodies never call `t` themselves and stay pure over their arguments.
  ;; A language switch re-runs the page, so the strings refresh even though the
  ;; cached rows do not. This is the page/child contract: state and language are
  ;; read at the edge, everything below is data in / hiccup out.
  (let [{:keys [vm loading? error config]} s
        stale (om/stale-label (:fetched-at config) (System/currentTimeMillis))
        lbl {:now (t :ui/now)
             :feels-like (t :ui/feels-like)
             :hi (t :ui/hi)
             :lo (t :ui/lo)
             :week-title (t :ui/week)
             :details-title (t :ui/details)
             :loading-title (t :ui/loading)
             :loading-sub (t :ui/loading-sub)
             :failed-title (t :ui/failed)}]
    [:toast-overlay {}
     [:toolbar-view {}
      [:header-bar {:slot :top}
       [:window-title {:title (t :ui/title)
                       :subtitle (or (:name (:place config)) "")}]
       [:entry {:slot :start
                :value ""
                :placeholder (t :ui/search)
                :tooltip (t :ui/search-tip)
                ;; the geocode + toast live in the dispatch fn; the query the entry
                ;; produces on activate arrives there as :gtkiccup/args
                :on-activate [:weather/search]}]
       [:icon-button {:slot :end
                      :icon "view-refresh-symbolic"
                      :tooltip (t :ui/refresh-tip)
                      :on-click [:weather/refresh]}]
       [:button {:slot :end
                 :class "flat"
                 :label (if (= :imperial (:units config)) "°F" "°C")
                 :tooltip (t :ui/units-tip)
                 :on-click [:weather/units]}]
       [:button {:slot :end
                 :class "flat"
                 :label (get i18n/language-names i18n/*lang* "EN")
                 :tooltip (t :ui/language-tip)
                 :on-click [:weather/lang]}]]
      [:vbox {:spacing 0}
       (when (or stale error)
         [:banner {:title (or error stale) :revealed true}])
       ;; a GtkBox child takes its natural height unless told to expand, and a
       ;; scrolled window's natural height is tiny -- without this the whole
       ;; dashboard collapses to a 58px sliver
       [:scroll {:vexpand true :hexpand true}
        (cond
          vm       (dashboard vm lbl)
          loading? (loading lbl)
          error    (failed error lbl)
          :else    (loading lbl))]]]]))

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(defn app []
  (show-cached!)
  ;; the single deref, at the page edge: `home` and everything under it get the
  ;; plain map, never the atom.
  (fn [] (home @state)))

(defn -main [& _]
  (let [stop (start-polling!)]
    (try
      (ui/run (app)
              :title (t :ui/title)
              :app-id "cz.brdloush.BbWeather"
              :app-name (t :ui/title)
              :width 560 :height 820
              :window adw/window
              :css wcss/css
              :on-ready (fn [_win tree] (reset! overlay (:widget tree))))
      (finally (stop)))))

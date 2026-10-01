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

   Run it with `bb weather`."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [gtkiccup.adw :as adw]
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

;; ---------------------------------------------------------------------------
;; view
;; ---------------------------------------------------------------------------

(defn hour-cell [now? {:keys [time temp-label precip icon]}]
  [:vbox {:spacing 3 :halign :center
          :class (if now? ["hour" "now"] ["hour"])}
   [:label {:class "h-time" :halign :center :label (if now? (t :ui/now) time)}]
   [:icon {:icon icon :size 20 :halign :center}]
   [:label {:class "h-temp" :halign :center :label temp-label}]
   [:label {:class "h-pop" :halign :center
            :label (if (and precip (pos? precip)) (str precip "%") " ")}]])

(defn hero [{:keys [place current today hourly sky-class]}]
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
             :label (str (t :ui/feels-like) " " (:feels current)
                         "   ·   " (t :ui/hi) " " (:hi today)
                         "   " (t :ui/lo) " " (:lo today))}]
    [:scroll {:h :automatic :v :never :hexpand true :margin 8 :class "hourly"}
     (into [:hbox {:spacing 2 :halign :center}]
           (map-indexed (fn [i h] (hour-cell (zero? i) h)) hourly))]]])

(defn day-row [{:keys [day icon hi lo precip]}]
  ;; deliberately single-line: the icon already carries the condition, and two
  ;; lines x 7 days pushes the dashboard past a screen height
  [:row {:title day :class "dayrow"}
   [:icon {:slot :prefix :icon icon :size 20}]
   [:hbox {:slot :suffix :spacing 12 :valign :center}
    [:label {:class "pop" :label (if (and precip (pos? precip)) (str precip "%") "")}]
    [:label {:class "d-hi" :label hi}]
    [:label {:class ["d-lo" "dim-label"] :label lo}]]])

(defn detail-row [{:keys [title value icon]}]
  [:row {:title title}
   [:icon {:slot :prefix :icon icon :size 18}]
   [:label {:slot :suffix :class "numeric" :valign :center :label value}]])

(defn dashboard [{:keys [daily details place] :as vm}]
  [:vbox {:spacing 0}
   (hero vm)
   [:clamp {:max 620}
    [:vbox {:spacing 14 :margin 14}
     (into [:group {:title (t :ui/week)}] (map day-row daily))
     (into [:group {:title (t :ui/details)
                    :description (str (:timezone place)
                                      (when (:elevation place)
                                        (format "  ·  %.0f m" (:elevation place))))}]
           (map detail-row details))]]])

(defn loading []
  [:status-page {:icon "weather-few-clouds-symbolic"
                 :title (t :ui/loading)
                 :description (t :ui/loading-sub)}])

(defn failed [err]
  [:status-page {:icon "network-offline-symbolic"
                 :title (t :ui/failed)
                 :description (str err)}])

(defn home [state]
  (let [{:keys [vm loading? error config]} @state
        stale (om/stale-label (:fetched-at config) (System/currentTimeMillis))]
    [:toast-overlay {}
     [:toolbar-view {}
      [:header-bar {:slot :top}
       [:window-title {:title (t :ui/title)
                       :subtitle (or (:name (:place config)) "")}]
       [:entry {:slot :start
                :value ""
                :placeholder (t :ui/search)
                :tooltip (t :ui/search-tip)
                :on-activate
                (fn [q]
                  (future
                    (if-let [hit (first (om/search-places! q))]
                      (do (set-place! hit)
                          (when-let [o @overlay]
                            (adw/toast! o (str (:name hit) ", " (:country hit)))))
                      (when-let [o @overlay]
                        (adw/toast! o (t :ui/no-place q))))))}]
       [:icon-button {:slot :end
                      :icon "view-refresh-symbolic"
                      :tooltip (t :ui/refresh-tip)
                      :on-click #(future (refresh!))}]
       [:button {:slot :end
                 :class "flat"
                 :label (if (= :imperial (:units config)) "°F" "°C")
                 :tooltip (t :ui/units-tip)
                 :on-click #(toggle-units!)}]
       [:button {:slot :end
                 :class "flat"
                 :label (get i18n/language-names i18n/*lang* "EN")
                 :tooltip (t :ui/language-tip)
                 :on-click #(set-language! (next-language i18n/*lang*))}]]
      [:vbox {:spacing 0}
       (when (or stale error)
         [:banner {:title (or error stale) :revealed true}])
       ;; a GtkBox child takes its natural height unless told to expand, and a
       ;; scrolled window's natural height is tiny -- without this the whole
       ;; dashboard collapses to a 58px sliver
       [:scroll {:vexpand true :hexpand true}
        (cond
          vm       (dashboard vm)
          loading? (loading)
          error    (failed error)
          :else    (loading))]]]]))

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(defn app []
  (show-cached!)
  (fn [] (home state)))

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

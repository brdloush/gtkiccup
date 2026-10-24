(ns monitor
  "A GNOME-looking system monitor, in babashka, over GTK4 + libadwaita.

   The whole UI is one pure function of one map. A worker thread reads /proc
   once a second and resets a reactive atom; the reconciler patches only the
   properties that actually changed. Nothing here touches GTK off the GTK
   thread -- `reset!` on a ratom just sets a dirty flag.

   The metric and process rows are late-bound ALIASES (`:monitor/metric`,
   `:monitor/process`) and every button is a DATA handler dispatched by the one
   `ui/set-dispatch!` fn below -- so a poll tick that leaves a row's attrs
   `=`-equal re-expands nothing, and a `:on-click` is a stable value rather than
   a fresh closure. Values a signal produces arrive in `:gtkiccup/args`; the
   toast targets `@overlay` and the refresh reads `@state` only at CLICK time,
   so no live value is ever baked into an alias expansion.
   See docs/widgets.md (#components-as-aliases, #handlers-as-data).

   Run it with `bb monitor`."
  (:require [gtkiccup.adw :as adw]
            [gtkiccup.alias :as ga]
            [gtkiccup.core :as ui]
            [gtkiccup.ffi :as g]
            [gtkiccup.ratom :as r]
            [sysinfo :as sys]))

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(defonce state (r/atom nil))
(defonce ^{:doc "The AdwToastOverlay, kept so we can post toasts into it."}
  overlay (atom nil))

(def poll-ms 1000)

(defn- refresh-now!
  "Takes two readings back to back so the CPU figure is immediate rather than
   waiting for the next tick."
  []
  (let [a (sys/sample)]
    (Thread/sleep 120)
    (reset! state (sys/view-model a (sys/sample)))))

(defn start-polling!
  "Reads /proc on a worker thread. Returns a fn that stops it."
  [state]
  (let [running? (volatile! true)]
    (future
      (loop [prev nil]
        (when @running?
          (let [cur (sys/sample)]
            (reset! state (sys/view-model prev cur))
            (Thread/sleep poll-ms)
            (recur cur)))))
    #(vreset! running? false)))

;; ---------------------------------------------------------------------------
;; look
;; ---------------------------------------------------------------------------

(def css
  "Only the few things libadwaita's own style classes do not already give us."
  "
  levelbar.metric > trough            { min-height: 10px; border-radius: 6px; }
  levelbar.metric > trough > block    { border-radius: 6px; }
  levelbar.self > trough > block.filled { background: @success_color; }
  row.self                            { background: alpha(@success_color, 0.10); }
  ")

;; ---------------------------------------------------------------------------
;; view
;; ---------------------------------------------------------------------------

(defn- classes [x]
  (cond (nil? x) [] (string? x) [x] :else (vec x)))

(ui/set-dispatch!
  (fn [_ [action name pid]]
    (case action
      ;; the copy button carries the process name and pid in its data vector
      :monitor/toast-pid (when-let [o @overlay]
                           (adw/toast! o (str name " is pid " pid)))
      ;; the header refresh button takes no arguments
      :monitor/sample    (do (future (refresh-now!))
                             (when-let [o @overlay]
                               (adw/toast! o "Sampled /proc"))))))

(ga/register-alias! :monitor/metric
  {:purity :pure}
  (fn [{:keys [icon title metric class width]} _children]
    [:row {:title title
           :subtitle (:label metric)
           :class (classes class)}
     [:icon {:slot :prefix :icon icon}]
     [:level {:slot :suffix
              :value (or (:value metric) 0)
              :width (or width 170)
              :class (into ["metric"] (classes class))}]]))

(ga/register-alias! :monitor/process
  {:purity :pure}
  (fn [{:keys [i name pid label value]} _children]
    [:row {:title (str (inc i) ". " name)
           :subtitle (str "pid " pid "  ·  " label)}
     [:level {:slot :suffix :value value :width 170 :class "metric"}]
     [:icon-button {:slot :suffix
                    :icon "edit-copy-symbolic"
                    :class "flat"
                    :tooltip "Show this pid in a toast"
                    :on-click [:monitor/toast-pid name pid]}]]))

(defn loading []
  [:status-page {:icon "utilities-system-monitor-symbolic"
                 :title "Reading /proc"
                 :description "One moment."}])

(defn dashboard [{:keys [host uptime cores cpu mem swap load disk procs self]}]
  ;; Rows are emitted as ALIAS nodes (plain data), not function calls. The page
  ;; reads every live value from the state map here and hands it down as an attr,
  ;; so the expansion below stays pure over its args.
  [:page {}
   [:group {:title "System"
            :description (str cores " cores  ·  up " uptime)}
    [:monitor/metric {:icon "utilities-system-monitor-symbolic" :title "Processor" :metric cpu}]
    [:monitor/metric {:icon "drive-harddisk-solidstate-symbolic" :title "Memory" :metric mem}]
    [:monitor/metric {:icon "media-flash-symbolic" :title "Swap" :metric swap}]
    [:monitor/metric {:icon "network-transmit-receive-symbolic" :title "Load average" :metric load}]
    [:monitor/metric {:icon "drive-harddisk-symbolic" :title "Root filesystem" :metric disk}]]

   [:group {:title "This app"
            :description "The window you are looking at, on the same scale as the list below."}
    [:monitor/metric {:icon "text-x-script-symbolic"
                      :title (str "babashka  ·  pid " (:pid self))
                      :metric self
                      :class "self"}]]

   [:group {:title "Top processes by memory"
            :description (str "Largest of everything running on " host ".")}
    (map-indexed (fn [i {:keys [name pid label value]}]
                   [:monitor/process {:i i :name name :pid pid :label label :value value}])
                 procs)]])

(defn home [vm]
  ;; `home` is the page: the host re-runs it on every change, so it is the one
  ;; place allowed to look at live state. `app` (below) deref-s the atom once and
  ;; hands the resulting map here, so `home` and everything under it
  ;; (`dashboard`, the alias rows) are pure functions of their arguments.
  [:toast-overlay {}
   [:toolbar-view {}
    [:header-bar {:slot :top}
     [:window-title {:title "System Monitor"
                     :subtitle (if-let [s vm]
                                 (str (:host s) "  ·  babashka + GTK4")
                                 "starting")}]
     [:icon-button {:slot :end
                    :icon "view-refresh-symbolic"
                    :tooltip "Sample now"
                    :on-click [:monitor/sample]}]]
    [:scroll {}
     (if-let [s vm] (dashboard s) (loading))]]])

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(defn app []
  ;; one synchronous reading so the first frame already has numbers
  (reset! state (sys/view-model nil (sys/sample)))
  ;; the single deref: the atom is read once here, at the page edge, and the
  ;; resulting map is what `home` and its children see. No atom is passed down.
  (fn [] (home @state)))

(defn -main [& _]
  (let [stop (start-polling! state)]
    (try
      (ui/run (app)
              :title "System Monitor"
              :app-id "cz.brdloush.BbMonitor"
              :app-name "System Monitor"
              :width 760 :height 880
              :window adw/window
              :css css
              :on-ready (fn [_win tree] (reset! overlay (:widget tree))))
      (finally (stop)))))

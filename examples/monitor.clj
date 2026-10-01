(ns monitor
  "A GNOME-looking system monitor, in babashka, over GTK4 + libadwaita.

   The whole UI is one pure function of one map. A worker thread reads /proc
   once a second and resets a reactive atom; the reconciler patches only the
   properties that actually changed. Nothing here touches GTK off the GTK
   thread -- `reset!` on a ratom just sets a dirty flag.

   Run it with `bb monitor`."
  (:require [gtkiccup.adw :as adw]
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

(defn metric-row
  "One AdwActionRow: icon on the left, label, level bar on the right."
  [{:keys [icon title metric class width]}]
  [:row {:title title
         :subtitle (:label metric)
         :class (classes class)}
   [:icon {:slot :prefix :icon icon}]
   [:level {:slot :suffix
            :value (or (:value metric) 0)
            :width (or width 170)
            :class (into ["metric"] (classes class))}]])

(defn process-row [i {:keys [name pid label value]}]
  [:row {:title (str (inc i) ". " name)
         :subtitle (str "pid " pid "  ·  " label)}
   [:level {:slot :suffix :value value :width 170 :class "metric"}]
   [:icon-button {:slot :suffix
                  :icon "edit-copy-symbolic"
                  :class "flat"
                  :tooltip "Show this pid in a toast"
                  :on-click #(when-let [o @overlay]
                               (adw/toast! o (str name " is pid " pid)))}]])

(defn loading []
  [:status-page {:icon "utilities-system-monitor-symbolic"
                 :title "Reading /proc"
                 :description "One moment."}])

(defn dashboard [{:keys [host uptime cores cpu mem swap load disk procs self]}]
  [:page {}
   [:group {:title "System"
            :description (str cores " cores  ·  up " uptime)}
    (metric-row {:icon "utilities-system-monitor-symbolic" :title "Processor" :metric cpu})
    (metric-row {:icon "drive-harddisk-solidstate-symbolic" :title "Memory" :metric mem})
    (metric-row {:icon "media-flash-symbolic" :title "Swap" :metric swap})
    (metric-row {:icon "network-transmit-receive-symbolic" :title "Load average" :metric load})
    (metric-row {:icon "drive-harddisk-symbolic" :title "Root filesystem" :metric disk})]

   [:group {:title "This app"
            :description "The window you are looking at, on the same scale as the list below."}
    (metric-row {:icon "text-x-script-symbolic"
                 :title (str "babashka  ·  pid " (:pid self))
                 :metric self
                 :class "self"})]

   [:group {:title "Top processes by memory"
            :description (str "Largest of everything running on " host ".")}
    (map-indexed process-row procs)]])

(defn home [state]
  [:toast-overlay {}
   [:toolbar-view {}
    [:header-bar {:slot :top}
     [:window-title {:title "System Monitor"
                     :subtitle (if-let [s @state]
                                 (str (:host s) "  ·  babashka + GTK4")
                                 "starting")}]
     [:icon-button {:slot :end
                    :icon "view-refresh-symbolic"
                    :tooltip "Sample now"
                    :on-click (fn []
                                (future (refresh-now!))
                                (when-let [o @overlay]
                                  (adw/toast! o "Sampled /proc")))}]]
    [:scroll {}
     (if-let [s @state] (dashboard s) (loading))]]])

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(defn app []
  ;; one synchronous reading so the first frame already has numbers
  (reset! state (sys/view-model nil (sys/sample)))
  (fn [] (home state)))

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

;; libadwaita specs, and the :slot prop they are built on.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.adw :as adw] '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g])
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn level-get-value "gtk_level_bar_get_value" [:pointer] :double)
(defcfn widget-parent "gtk_widget_get_parent" [:pointer] :pointer)
(defcfn row-get-title "adw_preferences_row_get_title" [:pointer] :string)
(defcfn row-get-subtitle "adw_action_row_get_subtitle" [:pointer] :string)
(defcfn title-get-title "adw_window_title_get_title" [:pointer] :string)
(defcfn title-get-subtitle "adw_window_title_get_subtitle" [:pointer] :string)

(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec adw/window))

(println "0) libadwaita" (:version adw/initialized)
         "|" (count (:widgets adw/initialized)) "tags registered")
(assert (every? @ui/widgets (:widgets adw/initialized)))

;; --- 1. every spec builds the GObject type it claims --------------------
(def win ((:ctor adw/window)))
(println "1) window is" (type-name win))
(assert (= "AdwWindow" (type-name win)))

(def state (atom {}))
(defn view []
  [:adw-window {}
   [:toast-overlay {}
    [:toolbar-view {}
     [:header-bar {:slot :top}
      [:window-title {:title "System Monitor" :subtitle (:sub @state "live")}]
      [:icon-button {:slot :end :icon "view-refresh-symbolic"}]]
     [:scroll {}
      [:page {}
       [:group {:title "Load"}
        [:row {:title (:name @state "cpu") :subtitle (:sub @state "live")}
         [:icon {:slot :prefix :icon "utilities-system-monitor-symbolic"}]
         [:level {:slot :suffix :value (:v @state 0.25)}]]]]]]]])

(def t (atom (reconcile root-spec win nil (normalize (view)))))
(defn node [& path] (get-in @t (interleave (repeat :children) path)))
(println "   built the whole Adw tree ok")

(def overlay (:widget (node 0)))
(def toolbar (:widget (node 0 0)))
(def header  (:widget (node 0 0 0)))
(def wtitle  (:widget (node 0 0 0 0)))
(def refresh (:widget (node 0 0 0 1)))
(def row     (:widget (node 0 0 1 0 0 0)))
(def icon    (:widget (node 0 0 1 0 0 0 0)))
(def level   (:widget (node 0 0 1 0 0 0 1)))

(doseq [[nm w expected] [["toast-overlay" overlay "AdwToastOverlay"]
                         ["toolbar-view" toolbar "AdwToolbarView"]
                         ["header-bar" header "AdwHeaderBar"]
                         ["window-title" wtitle "AdwWindowTitle"]
                         ["row" row "AdwActionRow"]
                         ["level" level "GtkLevelBar"]]]
  (println "  " nm "->" (type-name w))
  (assert (= expected (type-name w)) nm))

;; --- 2. slots put children in the right place --------------------------
(defn ancestors-of
  "Adw nests its slots several widgets deep -- a top bar sits under a
   GtkRevealer inside a GtkWindowHandle -- so check the chain, not the parent."
  [w]
  (loop [x (widget-parent w) acc []]
    (if (or (g/null? x) (> (count acc) 12)) acc
        (recur (widget-parent x) (conj acc (type-name x))))))

(println "2) header chain:" (ancestors-of header))
(assert (some #{"AdwToolbarView"} (ancestors-of header)) ":slot :top misplaced")
(println "   :slot :end button chain:" (take 6 (ancestors-of refresh)))
(assert (some #{"AdwHeaderBar"} (ancestors-of refresh)) ":slot :end misplaced")
(println "   prefix icon under the row:" (some #{"AdwActionRow"} (ancestors-of icon)))
(assert (some #{"AdwActionRow"} (ancestors-of icon)) ":slot :prefix misplaced")
(assert (some #{"AdwActionRow"} (ancestors-of level)) ":slot :suffix misplaced")
(println "   level under the row too, and the row under the group:"
         (boolean (some #{"AdwPreferencesGroup"} (ancestors-of level))))
(assert (some #{"AdwPreferencesGroup"} (ancestors-of level)))

;; --- 3. props reach real Adw setters, and stay reactive ----------------
(println "3) row title/subtitle:" (pr-str (row-get-title row)) (pr-str (row-get-subtitle row)))
(assert (= "cpu" (row-get-title row)))
(assert (= "live" (row-get-subtitle row)))
(assert (= "System Monitor" (title-get-title wtitle)))
(assert (< 0.24 (level-get-value level) 0.26))

(reset! state {:name "memory" :sub "3.9 GB" :v 0.75})
(swap! t #(reconcile root-spec win % (normalize (view))))
(println "   after a state change:" (pr-str (row-get-title row))
         (pr-str (row-get-subtitle row)) (level-get-value level))
(assert (= "memory" (row-get-title row)) "title not patched")
(assert (= "3.9 GB" (row-get-subtitle row)) "subtitle not patched")
(assert (< 0.74 (level-get-value level) 0.76) "level not patched")
(assert (= "3.9 GB" (title-get-subtitle wtitle)) "window subtitle not patched")

;; widgets were patched, not rebuilt
(assert (= (ffi/address row) (ffi/address (:widget (node 0 0 1 0 0 0)))) "row rebuilt")
(println "   row pointer unchanged:" true)

;; --- 4. text children work on Adw tags too -----------------------------
(println "4) [:row \"quick\"] ->" (:props (normalize [:row "quick"])))
(assert (= {:title "quick"} (:props (normalize [:row "quick"]))))
(assert (= {:icon "x-symbolic"} (:props (normalize [:icon-button "x-symbolic"]))))

;; --- 4b. :icon from a file, and :picture ---------------------------------
;; A fixed-size logo wants :icon with :size, not :picture: a size request is a
;; minimum, so a picture holding a 512px texture asks for 512px and gets it.
(defcfn image-get-pixel-size "gtk_image_get_pixel_size" [:pointer] :int)
(def img (reconcile root-spec win nil
                    (normalize [:icon {:file "icons/cz.brdloush.BbWeather.svg"
                                       :size 38}])))
(println "4b) :icon from a file ->" (type-name (:widget img))
         "pixel-size" (image-get-pixel-size (:widget img)))
(assert (= "GtkImage" (type-name (:widget img))))
(assert (= 38 (image-get-pixel-size (:widget img))))

;; a missing file must leave it blank rather than raise: a logo is decoration
(println "    a missing file is survivable:"
         (try (some? (reconcile root-spec win nil
                                (normalize [:icon {:file "icons/nope.svg" :size 20}])))
              (catch Exception e (str "RAISED " (ex-message e)))))
(assert (true? (try (some? (reconcile root-spec win nil
                                      (normalize [:icon {:file "icons/nope.svg"}])))
                    (catch Exception _ false))))
(def pic (reconcile root-spec win nil
                    (normalize [:picture {:file "icons/cz.brdloush.BbWeather.svg"}])))
(assert (= "GtkPicture" (type-name (:widget pic))))
(println "    :picture also loads an SVG:" (type-name (:widget pic)))

;; --- 5. toast! does not blow up ----------------------------------------
(adw/toast! overlay "hello from a test")
(println "5) toast shown ok")
(println "ALL OK")
(System/exit 0)

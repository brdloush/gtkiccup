;; AdwOverlaySplitView: the :sidebar / content slots and the width knobs.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.adw :as adw] '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g])
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn sidebar-of "adw_overlay_split_view_get_sidebar" [:pointer] :pointer)
(defcfn content-of "adw_overlay_split_view_get_content" [:pointer] :pointer)
(defcfn show-sidebar "adw_overlay_split_view_get_show_sidebar" [:pointer] :int)
(defcfn get-fraction "adw_overlay_split_view_get_sidebar_width_fraction" [:pointer] :double)
(defcfn get-min "adw_overlay_split_view_get_min_sidebar_width" [:pointer] :double)
(defcfn get-max "adw_overlay_split_view_get_max_sidebar_width" [:pointer] :double)

(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec adw/window))
(def win ((:ctor adw/window)))

(defn node [t & path] (get-in t (interleave (repeat :children) path)))

(println "1) the tag builds an AdwOverlaySplitView with both slots filled")
(def t (reconcile root-spec win nil
                  (normalize '[:overlay-split-view
                               {:sidebar-width-fraction 0.4 :min-sidebar-width 320
                                :max-sidebar-width 420 :show-sidebar true}
                               [:toolbar-view {:slot :sidebar}
                                [:header-bar {:slot :top} [:label "sidebar"]]]
                               [:vbox {} [:label "content"]]])))
(def sv (:widget t))
(assert (= "AdwOverlaySplitView" (type-name sv)))
(println "   ->" (type-name sv) "sidebar" (type-name (sidebar-of sv))
         "content" (type-name (content-of sv)))
(assert (= "AdwToolbarView" (type-name (sidebar-of sv))) ":slot :sidebar misplaced")
(assert (= "GtkBox" (type-name (content-of sv))) "content slot misplaced")

(println "2) the width props land where GTK reads them")
(let [f (get-fraction sv)]
  (assert (< (Math/abs (- f 0.4)) 1e-6) (str "fraction " f)))
(assert (= 320.0 (get-min sv)) (str "min " (get-min sv) " max " (get-max sv)))
(assert (= 420.0 (get-max sv)) (str "max " (get-max sv)))
(assert (= 1 (show-sidebar sv)))
(println "   -> fraction" (format "%.2f" (get-fraction sv))
         "min" (get-min sv) "max" (get-max sv) "shown" (= 1 (show-sidebar sv)))

(println "3) the props are reactive")
(def t2 (reconcile root-spec win t
                   (normalize '[:overlay-split-view
                                {:sidebar-width-fraction 0.25
                                 :min-sidebar-width 240 :max-sidebar-width 600
                                 :show-sidebar false}
                               [:toolbar-view {:slot :sidebar}
                                [:header-bar {:slot :top} [:label "sidebar"]]]
                               [:vbox {} [:label "content"]]])))
(assert (< (Math/abs (- (get-fraction sv) 0.25)) 1e-6))
(assert (= 240.0 (get-min sv)) (str "min " (get-min sv)))
(assert (= 600.0 (get-max sv)) (str "max " (get-max sv)))
(assert (= 0 (show-sidebar sv)))
;; hiding the sidebar does not unparent it
(assert (= "AdwToolbarView" (type-name (sidebar-of sv))))
(println "   -> fraction/min/max follow, sidebar hidden but still in place")

(println "4) swapping the slots keeps one widget per slot")
(def t3 (reconcile root-spec win t2
                   (normalize '[:overlay-split-view {:sidebar-width-fraction 0.3}
                                [:vbox {:slot :sidebar} [:label "now the sidebar"]]
                                [:toolbar-view {}
                                 [:header-bar {:slot :top} [:label "content"]]]])))
(println "   -> sidebar" (type-name (sidebar-of sv))
         "content" (type-name (content-of sv)))
(assert (= "GtkBox" (type-name (sidebar-of sv))) "the swap put the box in the sidebar")
(assert (= "AdwToolbarView" (type-name (content-of sv))) "and the toolbar in the content")

(g/window-destroy win)
(println "ALL OK")
(System/exit 0)

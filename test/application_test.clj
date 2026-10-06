;; AdwApplication + the AdwApplicationWindow window map (goal 01: the app
;; shell). The app object is created lazily, never registered or run: gtkiccup
;; keeps its own loop, the window is just a real application window.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.adw :as adw] '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g])
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn app-id-of "g_application_get_application_id" [:pointer] :string)
(defcfn window-get-title "gtk_window_get_title" [:pointer] :string)
(defcfn first-child "gtk_widget_get_first_child" [:pointer] :pointer)
(defcfn app-win-content "adw_application_window_get_content" [:pointer] :pointer)
(defcfn box-new "gtk_box_new" [:int :int] :pointer)

(g/gtk-init)
(def wmap (adw/application-window "cz.brdloush.AppTestOne"))

(println "1) the ctor builds an application window")
(def win ((:ctor wmap)))
(assert (= "AdwApplicationWindow" (type-name win)))
(println "   ->" (type-name win))

(println "2) the app carries the id")
(def app @(:app wmap))
(assert (= "cz.brdloush.AppTestOne" (app-id-of app)))
(println "   ->" (app-id-of app))

(println "3) set-content puts exactly one content widget in")
((:set-content wmap) win (box-new 0 0))
(assert (= "GtkBox" (type-name (app-win-content win))))
(println "   -> ok")

(println "4) the title behaves like a normal window prop")
(g/window-set-title win "Hello")
(assert (= "Hello" (window-get-title win)))
(println "   ->" (window-get-title win))

(println "5) windows share one app object")
(def win2 ((:ctor wmap)))
(assert (= "AdwApplicationWindow" (type-name win2)))
(assert (identical? app @(:app wmap)))
(println "   -> two windows, one app")

(println "6) run's reconcile path renders into it like into any window")
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec wmap))
(def win3 ((:ctor wmap)))
(def t (reconcile root-spec win3 nil (normalize [:bin {} [:label "hi"]])))
(assert (= "AdwBin" (type-name (app-win-content win3))))
(def t2 (reconcile root-spec win3 t (normalize [:bin {} [:label "bye"]])))
(assert (= "AdwBin" (type-name (app-win-content win3))))
(println "   -> tree renders and re-renders under the application window")

(g/window-destroy win)
(g/window-destroy win2)
(g/window-destroy win3)
(println "ALL OK")
(System/exit 0)

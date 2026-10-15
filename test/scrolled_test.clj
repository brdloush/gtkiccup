;; The core :scrolled tag: it builds the real GtkScrolledWindow, parents its
;; child, and the :wheel-scroll hook installs a controller. What the policy
;; props *do* is a GObject property, not observable from widget state, so that
;; part stays documented-only -- see DOC-QUALITY.md.
(require '[babashka.ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.widgets :as w] '[gtkiccup.ffi :as g])

(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn first-child "gtk_scrolled_window_get_child" [:pointer] :pointer)
(defcfn vadj "gtk_scrolled_window_get_vadjustment" [:pointer] :pointer)

(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec ui/default-window))
(def win ((:ctor ui/default-window)))

(println "1) :scrolled is the real GtkScrolledWindow, with the child inside it")
(let [sw (:widget (reconcile root-spec win nil
                            (normalize '[:scrolled {:hscroll :never :vscroll :always}
                                         [:label "content"]])))]
  (println "   type:" (type-name sw))
  (assert (= "GtkScrolledWindow" (type-name sw)) "not a GtkScrolledWindow")
  (println "   child parented:" (not (g/null? (first-child sw)))
           " adjustment exists:" (not (g/null? (vadj sw))))
  (assert (not (g/null? (first-child sw))) "child not parented")
  (assert (not (g/null? (vadj sw))) "no adjustment"))

(println "2) every documented policy value builds, including the wheel axes")
(doseq [props [{:hscroll :never :vscroll :never}
               {:hscroll :automatic :vscroll :external}
               {:wheel-scroll true}
               {:wheel-scroll :vertical}
               {:wheel-scroll :horizontal}
               {:wheel-scroll :both}]]
  (let [sw (:widget (reconcile root-spec win nil (normalize `[:scrolled ~props [:label "x"]])))]
    (assert (= "GtkScrolledWindow" (type-name sw)) (str "broke on " props))))
(println "   6 shapes built")

(println "3) install-wheel-scroll! hands back a controller, and a second call is fine")
(let [sw (:widget (reconcile root-spec win nil
                            (normalize '[:scrolled {:wheel-scroll true} [:label "x"]])))]
  (def c1 (w/install-wheel-scroll! sw :vertical))
  (def c2 (w/install-wheel-scroll! sw :both))
  (println "   two installs returned non-null:"
           (not (g/null? c1)) (not (g/null? c2)))
  (assert (and (not (g/null? c1)) (not (g/null? c2))) "no controller came back"))

(println "ALL OK")

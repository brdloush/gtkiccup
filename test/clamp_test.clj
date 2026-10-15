;; What :clamp actually does. Plain GTK4 has no maximum-width layout, so core
;; implements the tag as a centred box and turns :max into a *size request* --
;; a request is a minimum, which is the whole reason a page with a clamp cannot
;; be dragged narrower than :max. :min is not read at all there. Loading
;; gtkiccup.adw swaps in the real AdwClamp, where :max is only a ceiling (and
;; defaults to 600 rather than "no ceiling"). Both halves are stated in
;; docs/widgets.md, so both are asserted here -- including the swap itself,
;; which no test named before this file did.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g])

(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn first-child "gtk_widget_get_first_child" [:pointer] :pointer)
(defcfn size-request "gtk_widget_get_size_request" [:pointer :pointer :pointer] :void)
;; adw's bindings resolve only once libadwaita is loaded, which step 3 does --
;; that is why the getter is declared there and not up here.

(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec ui/default-window))
(def win ((:ctor ui/default-window)))

(defn request [w]
  ;; gtk_widget_get_size_request answers through two int out params.
  (with-open [a (ffi/confined-arena)]
    (let [wide (ffi/alloc a :int) tall (ffi/alloc a :int)]
      (size-request w wide tall)
      [(ffi/read wide :int) (ffi/read tall :int)])))

(println "1) core :clamp is a centred box, and :max became its width request")
(def t1 (reconcile root-spec win nil
                   (normalize '[:clamp {:max 400} [:label "narrow"]])))
(def c1 (:widget t1))
(println "   type:" (type-name c1) " size request:" (request c1)
         " child parented:" (not (g/null? (first-child c1))))
(assert (= "GtkBox" (type-name c1)) "core clamp is not the box it is built from")
(assert (= [400 -1] (request c1)) ":max did not become a size request")
(assert (not (g/null? (first-child c1))) "the child is not inside")

(println "2) :min is never read, and :max is only applied at construction")
(def t2 (reconcile root-spec win nil (normalize '[:clamp {:min 100} [:label "narrow"]])))
(def c2 (:widget t2))
(println "   built with only :min 100, size request:" (request c2))
(assert (= [-1 -1] (request c2)) ":min moved the request, contrary to the docs")
;; :apply is a no-op, so a clamp that already exists keeps the request it was
;; built with -- raising :max on a re-render does nothing until it is rebuilt.
(def t2b (reconcile root-spec win t1 (normalize '[:clamp {:max 800} [:label "narrow"]])))
(println "   same widget after :max 400 -> 800:" (identical? (:widget t1) (:widget t2b))
         " request:" (request (:widget t2b)))
(assert (identical? (:widget t1) (:widget t2b)) "the clamp was not reused")
(assert (= [400 -1] (request (:widget t2b))) "changing :max moved the request")

(println "3) loading gtkiccup.adw replaces it with the real AdwClamp")
(require (quote gtkiccup.adw))
(defcfn clamp-max "adw_clamp_get_maximum_size" [:pointer] :int)
(def t3 (reconcile root-spec win nil (normalize '[:clamp {} [:label "narrow"]])))
(def c3 (:widget t3))
(println "   type:" (type-name c3) " default maximum-size:" (clamp-max c3))
(assert (= "AdwClamp" (type-name c3)) "the adw clamp did not take over")
(assert (= 600 (clamp-max c3)) "AdwClamp's documented default is 600")

(println "ALL OK")
(System/exit 0)

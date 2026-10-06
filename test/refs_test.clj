;; Named widget references: the :ref prop, kept current by the renderer.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g] '[gtkiccup.refs :as refs])
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)

(g/gtk-init)
(refs/clear!)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec ui/default-window))
(def win ((:ctor ui/default-window)))

(defn node [t & path] (get-in t (interleave (repeat :children) path)))

(println "1) :ref registers the widget")
(def t (reconcile root-spec win nil
                  (normalize [:vbox {} [:label {:ref "one"} "hi"] [:label "x"]])))
(def one (:widget (node t 0)))
(assert (= "one" (first (refs/names))) (str "refs: " (refs/names)))
(assert (identical? one (refs/resolve "one")) "resolve does not find the widget")
(assert (identical? one (refs/resolve :one)) "keywords resolve to the same thing")
(println "   ->" (type-name (refs/resolve "one")) "(by name and by keyword)")

(println "2) an unknown name resolves to nil, and so does no name")
(assert (nil? (refs/resolve "absent")))
(assert (nil? (refs/resolve nil)))
(println "   -> nil, not a dangling pointer")

(println "3) moving the ref points it at the new widget")
(def t2 (reconcile root-spec win t
                   (normalize [:vbox {} [:label "x"] [:label {:ref "one"} "hi"]])))
(def moved (:widget (node t2 1)))
(assert (identical? moved (refs/resolve "one")) "the ref did not move")
(assert (not (identical? one moved)))
(println "   -> moved to", (type-name moved) ", only one name live:", (refs/names))

(println "4) dropping the :ref prop drops the name")
(reconcile root-spec win t2 (normalize [:vbox {} [:label "x"] [:label "hi"]]))
(assert (empty? (refs/names)) (str "refs: " (refs/names)))
(println "   ->" (refs/names))

(println "5) unmounting the subtree that held it drops the name too")
(def t3 (reconcile root-spec win t2
                   (normalize [:vbox {} [:vbox {} [:label {:ref "deep"} "x"]]])))
(def deep (:widget (node t3 0 0)))
(assert (identical? deep (refs/resolve "deep")))
(def t3b (reconcile root-spec win t3 (normalize [:vbox {}])))
(assert (empty? (refs/names)) "a ref outlived its widget")
(println "   ->", (refs/names))

(println "6) a rebuilt tree re-registers, and clear! forgets everything")
(def t4 (reconcile root-spec win t3b (normalize [:vbox {} [:button {:ref "btn" :label "go"}]])))
(assert (= #{"btn"} (set (refs/names))))
(def t5 (reconcile root-spec win t4 (normalize [:vbox {} [:button {:ref "btn" :label "go again"}]])))
(assert (= #{"btn"} (set (refs/names))))
(reconcile root-spec win t5 (normalize [:vbox {}]))
(refs/clear!)
(assert (empty? (refs/names)))
(println "   -> stable across re-renders")

(g/window-destroy win)
(println "ALL OK")
(System/exit 0)

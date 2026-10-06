;; AdwViewSwitcher: :policy wide/narrow, and the :stack reference that binds
;; it to an AdwViewStack. The stack here is deliberately written *after* the
;; switcher, so resolving the ref is a test of the deferred path.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.adw :as adw] '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g]
         '[gtkiccup.refs :as refs])
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn switcher-stack "adw_view_switcher_get_stack" [:pointer] :pointer)
(defcfn switcher-policy "adw_view_switcher_get_policy" [:pointer] :int)
(defcfn visible-name "adw_view_stack_get_visible_child_name" [:pointer] :string)

(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec adw/window))
(def win ((:ctor adw/window)))

(defn node [t & path] (get-in t (interleave (repeat :children) path)))

(def stack
  [:view-stack {:ref "pages" :visible-child-name "player"}
   [:vbox {:name "player" :title "Player"} [:label "player"]]
   [:vbox {:name "lyrics" :title "Lyrics"} [:label "lyrics"]]
   [:vbox {:name "queue" :title "Queue"} [:label "queue"]]])

(defn view [switcher-attrs]
  [:vbox {}
   ;; the switcher comes first, the stack it refers to second
   [:center-box {} (vector :view-switcher switcher-attrs)]
   stack])

(println "1) the switcher is built and bound to the stack by name")
(def t (reconcile root-spec win nil (normalize (view {:stack "pages" :policy :wide}))))
(def switcher (:widget (node t 0 0)))
(def stack-w (:widget (node t 1)))
(assert (= "AdwViewSwitcher" (type-name switcher)))
(assert (= 1 (switcher-policy switcher)) ":policy :wide")
(println "   ->" (type-name switcher) "policy" (switcher-policy switcher))

(println "2) the ref resolved, even though the stack was built later")
(let [bound (switcher-stack switcher)]
  (assert (not (g/null? bound)) "no stack bound")
  (assert (= "AdwViewStack" (type-name bound)))
  ;; the same object, told by what it shows: the same visible page as the tree's
  (assert (= (visible-name bound) (visible-name stack-w))
          "the bound stack is not the one in the tree")
  (println "   -> bound stack shows" (visible-name bound)))

(println "3) :policy is reactive")
(def t2 (reconcile root-spec win t (normalize (view {:stack "pages" :policy :narrow}))))
(assert (= 0 (switcher-policy switcher)) ":policy :narrow")
(println "   ->" (switcher-policy switcher))

(println "4) dropping :stack unbinds it")
(def t3 (reconcile root-spec win t2 (normalize (view {}))))
(assert (g/null? (switcher-stack switcher)) "the switcher is still bound")
(assert (seq (refs/names)) "the stack still carries its :ref")
(println "   -> unbound, live refs:" (refs/names))

(println "5) dropping the stack itself drops the ref")
(reconcile root-spec win t3
           (normalize [:vbox {} [:center-box {} [:view-switcher {}]]]))
(assert (empty? (refs/names)) "stale ref left behind")
(println "   -> refs" (refs/names))

(g/window-destroy win)
(println "ALL OK")
(System/exit 0)

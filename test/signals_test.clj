(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g] '[gtkiccup.ratom :as r])
(defcfn emit "g_signal_emit_by_name" [:pointer :string :&] :void)
(defn widget-activate [w] (emit w "clicked"))

(g/gtk-init)
(def win (g/window-new))
(def root-spec (@#'ui/root-spec ui/default-window))   ; root-spec takes the :window map now
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)

(def n (r/atom 0))
(def log (atom []))
(defn view []
  [:vbox {}
   [:label {:label (str "Count: " @n)}]
   ;; handler closes over the CURRENT @n -> classic stale-closure trap
   [:button {:label "add-current" :on-click #(swap! log conj @n)}]
   [:button {:label "+1" :on-click #(swap! n inc)}]
   [:check {:label "done" :active false :on-toggle #(swap! log conj [:toggled %])}]])

(def t (atom (reconcile root-spec win nil (normalize (view)))))
(defn rerender! [] (swap! t #(reconcile root-spec win % (normalize (view)))))
(defn w [i] (get-in @t [:children i :widget]))

(widget-activate (w 2))            ; +1
(println "1) click +1 ->" @n)
(rerender!)
(widget-activate (w 1))            ; add-current, must see 1 not 0
(println "2) handler refreshed after re-render (expect [1]):" @log)
(widget-activate (w 2)) (rerender!)
(widget-activate (w 2)) (rerender!)
(widget-activate (w 1))
(println "3) after two more increments (expect [1 3]):" @log "n =" @n)

(g/check-button-set-active (w 3) 1)
(println "4) check toggle fired:" @log)
(println "ALL OK")

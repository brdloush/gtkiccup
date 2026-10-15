(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g] '[gtkiccup.ratom :as r]
         '[gtkiccup.widgets :as w])
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

;; 5) the leak the connect! fix was for: widgets swapped out during navigation
;;    churn must release their C callback, or the arena map grows forever. The
;;    button and the check carry a handler, and swapping their tag tears the
;;    old widget down and builds a new one -- 40 times.
(def arenas @#'gtkiccup.widgets/arena->cb)
(def baseline (count @arenas))
(defn churn-view [i]
  [:vbox {} [:label {:label (str "i " i)}]
   (if (even? i)
     [:button {:label "go" :on-click #(swap! n inc)}]
     [:check {:label "go" :active false :on-toggle #(swap! log conj [:c %])}])])
(doseq [i (range 40)]
  (swap! t #(reconcile root-spec win % (normalize (churn-view i)))))
(System/gc)
(Thread/sleep 300)
(System/gc)
(Thread/sleep 300)
(let [now (count @arenas)]
  (println "5) arenas before/after 40 widget swaps:" baseline "->" now)
  (assert (< now (+ baseline 8)) (str "connections leaked: " baseline " -> " now)))

;; 6) the two signals an :entry carries. Both give their handler the text, read
;;    through GtkEditable -- which is the part a widget could not fake, so it is
;;    worth more than a "the handler ran" check.
(def log2 (atom []))
(defcfn set-text "gtk_editable_set_text" [:pointer :string] :void)
(defn entry-view []
  [:vbox {} [:entry {:on-activate #(swap! log2 conj [:activate %])
                     :on-change  #(swap! log2 conj [:change %])}]])
(def t6 (reconcile root-spec win nil (normalize (entry-view))))
(def e (get-in t6 [:children 0 :widget]))
;; typing is the real path, and it needs no emit: GtkEditable's own setter
;; drives "changed", so the handler sees text a test never invented.
(set-text e "typed")
(println "   typing reached :on-change on its own:" @log2)
(assert (= [[:change "typed"]] @log2) (str "typing did not fire :on-change: " @log2))
(emit e "activate")
(println "6) typing + Enter ->" @log2)
(assert (= [[:change "typed"] [:activate "typed"]] @log2)
        (str "entry signals: " @log2))

(println "ALL OK")

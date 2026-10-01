;; headless-ish test: exercise create/reconcile directly, no main loop
(require '[babashka.ffi :as ffi] '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g] '[gtkiccup.ratom :as r])
(g/gtk-init)
(def win (g/window-new))
(def root-spec (@#'ui/root-spec ui/default-window))   ; root-spec takes the :window map now
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)

(def n (r/atom 0))
(defn view []
  [:vbox {:spacing 12 :margin 16}
   [:label {:label (str "Count: " @n)}]
   [:hbox {:spacing 8}
    [:button {:label "- 1" :on-click #(swap! n dec)}]
    [:button {:label "+ 1" :on-click #(swap! n inc)}]
    [:button {:label "reset" :on-click #(reset! n 0) :sensitive (not= 0 @n)}]]])

(def t1 (reconcile root-spec win nil (normalize (view))))
(def lbl1 (get-in t1 [:children 0 :widget]))
(println "1) built tree ok, label text prop =" (get-in t1 [:children 0 :props :label]))

(reset! n 5)
(def t2 (reconcile root-spec win t1 (normalize (view))))
(def lbl2 (get-in t2 [:children 0 :widget]))
(println "2) label widget reused (patched, not rebuilt):"
         (= (ffi/address lbl1) (ffi/address lbl2)))
(println "   new label prop =" (get-in t2 [:children 0 :props :label]))

;; --- dynamic children ---
(def items (r/atom ["a" "b" "c"]))
(defn list-view []
  (into [:vbox {:spacing 4}]
        (for [i @items] [:label {:label i}])))
(def l1 (reconcile root-spec win nil (normalize (list-view))))
(println "3) list children:" (count (:children l1)))
(reset! items ["a" "b" "c" "d" "e"])
(def l2 (reconcile root-spec win l1 (normalize (list-view))))
(println "4) after grow:" (count (:children l2)))
(reset! items ["z"])
(def l3 (reconcile root-spec win l2 (normalize (list-view))))
(println "5) after shrink:" (count (:children l3))
         "label =" (get-in l3 [:children 0 :props :label]))

;; --- tag change forces replace ---
(def flip (r/atom true))
(defn flip-view [] [:vbox {} (if @flip [:label {:label "L"}] [:button {:label "B"}])])
(def f1 (reconcile root-spec win nil (normalize (flip-view))))
(def w1 (get-in f1 [:children 0 :widget]))
(reset! flip false)
(def f2 (reconcile root-spec win f1 (normalize (flip-view))))
(println "6) tag change replaced widget:"
         (not= (ffi/address w1) (ffi/address (get-in f2 [:children 0 :widget])))
         "->" (get-in f2 [:children 0 :tag]))
(println "ALL OK")

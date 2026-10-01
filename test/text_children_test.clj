;; [:label "foo"] and [:label "Count: " 3] fold text children into the
;; widget's text prop. Containers and leaves reject what they cannot hold.
(require '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g] '[gtkiccup.ratom :as r])
(g/gtk-init)
(def win (g/window-new))
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec ui/default-window))   ; root-spec takes the :window map now

(defn props-of [form] (-> (normalize form) :props))
(defn fails [form]
  (try (normalize form) :NO-ERROR
       (catch Exception e (ex-message e))))

;; --- the ask -------------------------------------------------------------
(println "1) [:label \"foo!\"] ->" (props-of [:label "foo!"]))
(assert (= {:label "foo!"} (props-of [:label "foo!"])))

(println "2) several children join:" (props-of [:label "Count: " 3]))
(assert (= {:label "Count: 3"} (props-of [:label "Count: " 3])))

(println "3) props still work alongside:" (props-of [:label {:margin 4} "hi"]))
(assert (= {:margin 4 :label "hi"} (props-of [:label {:margin 4} "hi"])))

(println "4) button and check too:"
         (props-of [:button "Add"]) (props-of [:check "done"]) (props-of [:entry "typed"]))
(assert (= {:label "Add"}    (props-of [:button "Add"])))
(assert (= {:label "done"}   (props-of [:check "done"])))
(assert (= {:value "typed"}  (props-of [:entry "typed"])))

;; --- the explicit form is untouched --------------------------------------
(println "5) explicit form unchanged:" (props-of [:label {:label "x"}]))
(assert (= {:label "x"} (props-of [:label {:label "x"}])))

;; --- rejections, each with a message that says what to do ----------------
(println "6) container with text:" (fails [:vbox {} "oops"]))
(assert (re-find #"has no text of its own" (fails [:vbox {} "oops"])))

(println "7) both prop and text:" (fails [:label {:label "a"} "b"]))
(assert (re-find #"both a :label prop and text" (fails [:label {:label "a"} "b"])))

(println "8) widgets under a leaf:" (fails [:label "hi" [:button "no"]]))
(assert (re-find #"cannot contain child widgets" (fails [:label "hi" [:button "no"]])))

(println "9) a child that is neither:" (fails [:vbox {} #{:a}]))
(assert (re-find #"expected a vector" (fails [:vbox {} #{:a}])))

(println "10) unknown widget:" (fails [:slider {}]))
(assert (re-find #"known widgets" (fails [:slider {}])))

;; --- it is reactive, like any other prop --------------------------------
(def n (r/atom 0))
(defn view [] [:vbox {} [:label "Count: " @n]])
(def t (atom (reconcile root-spec win nil (normalize (view)))))
(defn live [] (-> @t :children first :widget g/label-get-text))
(println "11) rendered:" (live))
(assert (= "Count: 0" (live)))
(reset! n 7)
(swap! t #(reconcile root-spec win % (normalize (view))))
(println "    after state change:" (live))
(assert (= "Count: 7" (live)))
(println "ALL OK")
(System/exit 0)

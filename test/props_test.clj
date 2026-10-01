;; Props that appear, disappear, or are explicitly nil.
;; Two bugs lived here:
;;   * :sensitive nil -- what (seq "") gives you -- read the same as no
;;     :sensitive at all, so it never reached the widget
;;   * a widget that gained an :on-click after creation was never connected,
;;     leaving a button that renders but does nothing, forever
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g] '[gtkiccup.ratom :as r])
(defcfn emit "g_signal_emit_by_name" [:pointer :string :&] :void)
(defcfn sensitive? "gtk_widget_get_sensitive" [:pointer] :int)
(defcfn hexpand? "gtk_widget_get_hexpand" [:pointer] :int)
(defcfn margin-top "gtk_widget_get_margin_top" [:pointer] :int)
(defcfn has-css-class? "gtk_widget_has_css_class" [:pointer :string] :int)

(g/gtk-init)
(def win (g/window-new))
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec ui/default-window))   ; root-spec takes the :window map now

(def props (atom {}))
(def log (atom []))
(defn view [] [:vbox {} (into [:button] [(merge {:label "Add"} @props)])])
(def t (atom nil))
(defn render! [] (swap! t #(reconcile root-spec win % (normalize (view)))))
(defn btn [] (-> @t :children first :widget))

;; --- 1. an explicit nil is not the same as absent ------------------------
(reset! props {}) (render!)
(println "1) no :sensitive at all -> sensitive?" (sensitive? (btn)))
(assert (= 1 (sensitive? (btn))))

(reset! props {:sensitive (seq "")}) (render!)     ; (seq "") is nil
(println "   :sensitive (seq \"\") -> sensitive?" (sensitive? (btn)))
(assert (= 0 (sensitive? (btn))) "an explicit nil never reached the widget")

(reset! props {:sensitive (seq "abc")}) (render!)
(println "   :sensitive (seq \"abc\") -> sensitive?" (sensitive? (btn)))
(assert (= 1 (sensitive? (btn))))

;; --- 2. removing a prop restores the GTK default, not nil ----------------
(reset! props {:sensitive false}) (render!)
(assert (= 0 (sensitive? (btn))))
(reset! props {}) (render!)
(println "2) :sensitive removed -> back to enabled:" (sensitive? (btn)))
(assert (= 1 (sensitive? (btn))) "removal should re-enable, not disable")

(reset! props {:margin 12 :hexpand true}) (render!)
(assert (and (= 12 (margin-top (btn))) (= 1 (hexpand? (btn)))))
(reset! props {}) (render!)
(println "   :margin and :hexpand removed ->" (margin-top (btn)) (hexpand? (btn)))
(assert (= 0 (margin-top (btn))))
(assert (= 0 (hexpand? (btn))))

;; --- 3. css classes are removed, not just added --------------------------
(reset! props {:class ["a" "b"]}) (render!)
(println "3) classes a,b ->" (has-css-class? (btn) "a") (has-css-class? (btn) "b"))
(assert (= 1 (has-css-class? (btn) "a")))
(reset! props {:class ["b" "c"]}) (render!)
(println "   swapped to b,c -> a:" (has-css-class? (btn) "a")
         "b:" (has-css-class? (btn) "b") "c:" (has-css-class? (btn) "c"))
(assert (= 0 (has-css-class? (btn) "a")) "a should have been removed")
(assert (= 1 (has-css-class? (btn) "b")))
(assert (= 1 (has-css-class? (btn) "c")))

;; --- 4. a handler gained after creation gets connected -------------------
(reset! props {}) (render!)
(emit (btn) "clicked")
(println "4) no handler yet, click ->" @log)
(assert (empty? @log))

(reset! props {:on-click #(swap! log conj :one)}) (render!)
(emit (btn) "clicked")
(println "   after gaining :on-click ->" @log)
(assert (= [:one] @log) "handler added after creation was never connected")

;; --- 5. a replaced handler wins, a removed one goes quiet ---------------
(reset! props {:on-click #(swap! log conj :two)}) (render!)
(emit (btn) "clicked")
(assert (= [:one :two] @log))
(reset! props {}) (render!)
(emit (btn) "clicked")
(println "5) handler removed, click ->" @log)
(assert (= [:one :two] @log) "a removed handler should stop firing")
(println "ALL OK")
(System/exit 0)

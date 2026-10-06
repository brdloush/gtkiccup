;; The small leaf widgets a real UI is packed with: a separator, a ratio
;; frame, a range, a menu button, and the child-on-a-button the app's controls
;; are made of. Each one is checked at build time and after a re-render.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g]
         ;; :picture comes from adw, and the frame's child is a real image
         '[gtkiccup.adw :as adw])
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn orient-of "gtk_orientable_get_orientation" [:pointer] :int)
(defcfn ratio-of "gtk_aspect_frame_get_ratio" [:pointer] :float)
(defcfn obey-of "gtk_aspect_frame_get_obey_child" [:pointer] :int)
(defcfn first-child "gtk_widget_get_first_child" [:pointer] :pointer)
(defcfn value-of "gtk_range_get_value" [:pointer] :double)
(defcfn draw-of "gtk_scale_get_draw_value" [:pointer] :int)
(defcfn inverted-of "gtk_range_get_inverted" [:pointer] :int)
(defcfn emit "g_signal_emit_by_name" [:pointer :string :&] :void)
(defcfn button-child "gtk_button_get_child" [:pointer] :pointer)
(defcfn mb-label "gtk_menu_button_get_label" [:pointer] :string)
(defcfn mb-icon "gtk_menu_button_get_icon_name" [:pointer] :string)
(defcfn mb-frame "gtk_menu_button_get_has_frame" [:pointer] :int)
(defcfn mb-child "gtk_menu_button_get_child" [:pointer] :pointer)

(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec ui/default-window))
(def win ((:ctor ui/default-window)))

(defn node [t & path] (get-in t (interleave (repeat :children) path)))
(defn tree [& kids] (reconcile root-spec win nil (normalize (into [:vbox {}] kids))))

(println "1) :separator is a line, horizontal unless told otherwise")
(def t (tree [:separator {}] [:separator {:orientation :vertical}]))
(def sep (:widget (node t 0)))
(def sep-v (:widget (node t 1)))
(assert (= "GtkSeparator" (type-name sep)))
(assert (= 0 (orient-of sep)) "default orientation horizontal")
(assert (= 1 (orient-of sep-v)) ":orientation :vertical")
(println "   ->" (orient-of sep) "and" (orient-of sep-v))
;; the lone child is now vertical, which is the same setter on a live widget
(def t2 (reconcile root-spec win t (normalize [:vbox {} [:separator {:orientation :vertical}]])))
(assert (= 1 (orient-of sep)) "the orientation is reactive")

(println "2) :aspect-frame keeps a child at a ratio")
(def t3 (reconcile root-spec win t2
                   (normalize [:vbox {} [:aspect-frame {:ratio 1.0}
                                         [:picture {:file "docs/images/weather.png"}]]])))
(def frame (:widget (node t3 0)))
(assert (= "GtkAspectFrame" (type-name frame)))
(assert (< (Math/abs (- (ratio-of frame) 1.0)) 1e-6) "ratio read back")
(assert (= 1 (obey-of frame)) "obey-child defaults to true")
(assert (= "GtkPicture" (type-name (first-child frame))) "the child went in")
(def t4 (reconcile root-spec win t3 (normalize [:vbox {} [:aspect-frame {:ratio 1.7778}
                                                           [:vbox {}]]])))
(assert (< (Math/abs (- (ratio-of frame) 1.7778)) 1e-3) "the ratio is reactive")
(assert (= "GtkBox" (type-name (first-child frame))) "the child swapped")
(println "   -> ratio" (ratio-of frame) "child" (type-name (first-child frame)))

(println "3) :scale is a range with the bounds the app asked for")
(def t5 (reconcile root-spec win t4
                   (normalize [:vbox {}
                               [:scale {:min 10 :max 60 :step 5 :value 47
                                        :draw-value true :inverted true}]])))
(def sc (:widget (node t5 0)))
(assert (= "GtkScale" (type-name sc)))
(assert (< (Math/abs (- (value-of sc) 47.0)) 1e-9) "the value is the state's")
(assert (= 1 (draw-of sc)) ":draw-value true")
(assert (= 1 (inverted-of sc)) ":inverted true")
(let [a (g/range-get-adjustment sc)]
  (assert (< (Math/abs (- (g/adjustment-get-lower a) 10.0)) 1e-9) "lower")
  (assert (< (Math/abs (- (g/adjustment-get-upper a) 60.0)) 1e-9) "upper"))
(println "   -> value" (value-of sc) "draw-value" (draw-of sc)
         "inverted" (inverted-of sc))

(println "4) the value is only written when it really differs")
;; a value pushed while the user drags is what makes a slider fight back, so
;; the write is guarded: here the same value arrives twice and the slider does
;; not notice, then a different one does.
(def seen (atom []))
(def t6 (reconcile root-spec win t5
                   (normalize [:vbox {} [:scale {:min 10 :max 60 :step 5 :value 47
                                                 :on-change-value #(swap! seen conj %)}]])))
(assert (< (Math/abs (- (value-of sc) 47.0)) 1e-9))
(reconcile root-spec win t6 (normalize [:vbox {} [:scale {:min 10 :max 60 :step 5
                                                         :value 12 :on-change-value #(swap! seen conj %)}]]))
(assert (< (Math/abs (- (value-of sc) 12.0)) 1e-9) ":value followed the state")
(println "   -> moved to" (value-of sc))

(println "5) :on-change-value fires with the value the user moved to")
(reset! seen [])
(emit sc "change-value" 1 33.0)
(assert (seq @seen) "no change-value reached the handler")
(assert (< (Math/abs (- (first @seen) 33.0)) 1e-6) (str "handler saw " (first @seen)))
(println "   -> handler saw" (first @seen))

(println "6) :menu-button shows an icon, a label, or a child")
(def t7 (reconcile root-spec win t6
                   (normalize [:vbox {}
                               [:menu-button {:icon "open-menu-symbolic" :has-frame true}]])))
(def mb (:widget (node t7 0)))
(assert (= "GtkMenuButton" (type-name mb)))
(assert (= "open-menu-symbolic" (mb-icon mb)))
(assert (= 1 (mb-frame mb)) ":has-frame true")
(def t8 (reconcile root-spec win t7 (normalize [:vbox {} [:menu-button {:label "Sort"}]])))
(assert (= "Sort" (mb-label mb)) ":label")
(def t9 (reconcile root-spec win t8 (normalize [:vbox {} [:menu-button {} [:label "child"]]])))
(assert (not (g/null? (mb-child mb))) "the child did not go in")
(assert (= "GtkLabel" (type-name (mb-child mb))))
(println "   -> icon, label and child all read back")

(println "7) :button takes a child too")
(def t10 (reconcile root-spec win t9
                    (normalize [:vbox {} [:button {:has-frame false}
                                         [:hbox {} [:label "go"]]]])))
(def b (:widget (node t10 0)))
(assert (= "GtkButton" (type-name b)))
(assert (not (g/null? (button-child b))) "the button has no child")
(assert (= "GtkBox" (type-name (button-child b))))
(assert (= 0 (g/button-get-has-frame b)) ":has-frame false")
(println "   ->" (type-name (button-child b)) "inside the button")

(println "8) a scale with no :step is still a real GtkScale")
;; gtk_scale_new_with_range asserts step != 0 and returns NULL, which used to
;; poison the whole render: the widget, then the box it lands in. So the ctor
;; falls back to a keyboard step of 1, and an explicit :step 0 is coerced.
(def t11 (reconcile root-spec win t10
                    (normalize [:vbox {} [:scale {:min 0 :max 160 :value 47}]])))
(def sc2 (:widget (node t11 0)))
(assert (= "GtkScale" (type-name sc2))
        (str "step-less scale is " (type-name sc2)))
(assert (< (Math/abs (- (value-of sc2) 47.0)) 1e-9) "the value stuck")
(let [a (g/range-get-adjustment sc2)]
  (assert (< (Math/abs (- (g/adjustment-get-upper a) 160.0)) 1e-9) "upper")
  (assert (< (Math/abs (- (g/adjustment-get-step-increment a) 1.0)) 1e-9) "step"))
(def t12 (reconcile root-spec win t11 (normalize [:vbox {} [:scale {:min 0 :max 160 :step 0}]])))
(assert (= "GtkScale" (type-name (:widget (node t12 0)))) ":step 0 coerced")
(println "   -> no step, no crash")

(g/window-destroy win)
(println "ALL OK")
(System/exit 0)

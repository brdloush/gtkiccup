;; GtkCenterBox (the three-slot bar) and the :slot prop it is built on.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g])
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn start-of "gtk_center_box_get_start_widget" [:pointer] :pointer)
(defcfn center-of "gtk_center_box_get_center_widget" [:pointer] :pointer)
(defcfn end-of "gtk_center_box_get_end_widget" [:pointer] :pointer)

(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec ui/default-window))
(def win ((:ctor ui/default-window)))

(defn node [t & path] (get-in t (interleave (repeat :children) path)))

;; `reconcile` returns the component's own root node, so a node lookup starts
;; there: (node t) is the box, (node t 0) is its first child.
(def view1
  '[:center-box {}
    [:label "centre"]                     ; no slot: the centred child
    [:button {:slot :start :label "back"}]
    [:button {:slot :end :label "next"}]])

(def view2
  '[:center-box {}
    [:label "centre"]
    [:button {:slot :start :label "back"}]])

;; Same three children, but keyed, and with start/centre swapped: a keyed
;; reorder remounts them, which is what puts each one in its slot again.
(def view3
  '[:center-box {}
    [:button {:key 1 :slot :start :label "back"}]
    [:label {:key 2} "centre"]])
(def view4
  '[:center-box {}
    [:label {:key 2} "centre"]
    [:button {:key 1 :slot :end :label "back"}]])

(println "1) the tag builds a GtkCenterBox, slots filled from the children")
(def t (reconcile root-spec win nil (normalize view1)))
(def box (:widget t))
(assert (= "GtkCenterBox" (type-name box)))
(println "   ->" (type-name box) "/" (type-name (start-of box))
         (type-name (center-of box)) (type-name (end-of box)))
(assert (= "GtkButton" (type-name (start-of box))))
(assert (= "GtkLabel" (type-name (center-of box))))
(assert (= "GtkButton" (type-name (end-of box))))

(println "2) removing a slotted child clears the slot it was in")
(def t2 (reconcile root-spec win t (normalize view2)))
(assert (g/null? (end-of box)) "the end slot should be empty now")
(assert (= "GtkButton" (type-name (start-of box))))
(assert (= "GtkLabel" (type-name (center-of box))))
(println "   -> start and centre survive, end is empty")

(println "3) re-rendering the same view changes nothing")
(reconcile root-spec win t2 (normalize view2))
(assert (= "GtkButton" (type-name (start-of box))))
(assert (= "GtkLabel" (type-name (center-of box))))
(println "   -> stable")

(println "4) keyed children keep their slots across a reorder")
(def win2 ((:ctor ui/default-window)))
(def s (reconcile root-spec win2 nil (normalize view3)))
(def s2 (reconcile root-spec win2 s (normalize view4)))
(def box2 (:widget s2))
;; view3 had the button at :start and the label centred; view4 moves the same
;; keyed button to :end, so start is empty, centre is still the label and the
;; button is at the end. (The slot a child is cleared from comes from what it
;; was appended to, not from the props of this render -- see gtkiccup.renderer.)
(assert (g/null? (start-of box2)) "start should be empty after the move")
(assert (= "GtkLabel" (type-name (center-of box2))) "centre after the swap")
(assert (= "GtkButton" (type-name (end-of box2))) "end after the swap")
(println "   ->" (type-name (start-of box2)) (type-name (center-of box2))
         (type-name (end-of box2)))

(g/window-destroy win2)
(g/window-destroy win)
(println "ALL OK")
(System/exit 0)

;; The shape of a label: how much of a string fits, and what happens when it
;; does not. Card titles and rows are the reason: truncated is useful, wrapped
;; into four lines is not.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g])
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn text-of "gtk_label_get_text" [:pointer] :string)
(defcfn ell-of "gtk_label_get_ellipsize" [:pointer] :int)
(defcfn lines-of "gtk_label_get_lines" [:pointer] :int)
(defcfn maxc-of "gtk_label_get_max_width_chars" [:pointer] :int)
(defcfn wc-of "gtk_label_get_width_chars" [:pointer] :int)
(defcfn just-of "gtk_label_get_justify" [:pointer] :int)
(defcfn wm-of "gtk_label_get_wrap_mode" [:pointer] :int)
(defcfn sel-of "gtk_label_get_selectable" [:pointer] :int)
(defcfn visible-of "gtk_widget_get_visible" [:pointer] :int)

(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec ui/default-window))
(def win ((:ctor ui/default-window)))
(defn node [t & path] (get-in t (interleave (repeat :children) path)))

(def title "An unreasonably long album title that has to fit a narrow card")

(println "1) the defaults are GTK's own: no ellipsis, every line")
(def t (reconcile root-spec win nil (normalize [:vbox {} [:label title]])))
(def l (:widget (node t 0)))
(assert (= "GtkLabel" (type-name l)))
(assert (= title (text-of l)))
(assert (= 0 (ell-of l)) "ellipsize defaults to none")
(assert (= -1 (lines-of l)) "lines default to however many it needs")
(println "   ->" (type-name l) "ellipsize" (ell-of l) "lines" (lines-of l))

(println "2) every shape prop arrives where GTK reads it")
(def t2 (reconcile root-spec win t
                   (normalize [:vbox {}
                               [:label {:ellipsize :middle :lines 2 :max-width-chars 18
                                        :width-chars 24 :justify :center :wrap-mode :word
                                        :selectable true}
                                title]])))
(assert (= 2 (ell-of l)) ":ellipsize :middle")
(assert (= 2 (lines-of l)) ":lines 2")
(assert (= 18 (maxc-of l)) ":max-width-chars")
(assert (= 24 (wc-of l)) ":width-chars")
(assert (= 2 (just-of l)) ":justify :center")
(assert (= 2 (wm-of l)) ":wrap-mode :word")
(assert (= 1 (sel-of l)) ":selectable true")
(println "   -> ellipsize" (ell-of l) "lines" (lines-of l) "chars" (maxc-of l)
         "/" (wc-of l) "justify" (just-of l) "wrap" (wm-of l) "selectable" (sel-of l))

(println "3) the props are reactive")
(def t3 (reconcile root-spec win t2 (normalize [:vbox {}
                                                [:label {:ellipsize :end :lines 1} title]])))
(assert (= 3 (ell-of l)) ":ellipsize :end")
(assert (= 1 (lines-of l)))
(println "   -> ellipsize" (ell-of l) "lines" (lines-of l))

(println "4) props that go away fall back to the defaults")
(def t4 (reconcile root-spec win t3 (normalize [:vbox {} [:label title]])))
(assert (= 0 (ell-of l)) "ellipsize back to none")
(assert (= -1 (lines-of l)) "lines back to all")
(assert (= -1 (maxc-of l)) "the width cap is gone")
(println "   -> ellipsize" (ell-of l) "lines" (lines-of l) "chars" (maxc-of l))

(println "5) :visible hides the widget, :overflow clips it")
;; overflow has no getter, so what is checked is that the widget still renders:
;; hidden overflow is a paint-time decision, it must not cost the widget.
(def t5 (reconcile root-spec win t4
                   (normalize [:vbox {}
                               [:label {:visible false :overflow :hidden} title]])))
(assert (= 0 (visible-of l)) ":visible false")
(def t6 (reconcile root-spec win t5
                   (normalize [:vbox {} [:label {:visible true :overflow :visible} title]])))
(assert (= 1 (visible-of l)) ":visible true")
(assert (= title (text-of l)))
(println "   -> back visible, text intact after the clip")

(g/window-destroy win)
(println "ALL OK")
(System/exit 0)

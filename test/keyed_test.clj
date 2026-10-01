;; What Replicant brings: keyed children that keep their widgets when they move,
;; widgets that are really freed when they go, tag changes that stay in place,
;; lifecycle hooks that see the widget in its parent, and handlers as data.
;; Every check reads the real GTK tree, not our mirror of it.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g])
(defcfn emit "g_signal_emit_by_name" [:pointer :string :&] :void)
(defcfn first-child "gtk_widget_get_first_child" [:pointer] :pointer)
(defcfn next-sibling "gtk_widget_get_next_sibling" [:pointer] :pointer)
(defcfn parent-of "gtk_widget_get_parent" [:pointer] :pointer)
(defcfn label-text "gtk_label_get_text" [:pointer] :string)
(defcfn overlay-child "gtk_overlay_get_child" [:pointer] :pointer)
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn weak-ref "g_object_weak_ref" [:pointer :pointer :pointer] :void)

(g/gtk-init)
(def win (g/window-new))
(def root-spec (@#'ui/root-spec ui/default-window))
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def tree (atom nil))
(defn render! [hiccup] (swap! tree #(reconcile root-spec win % (normalize hiccup))))

(defn gtk-children
  "The widgets GTK really holds under w, in order."
  [w]
  (loop [c (first-child w) acc []]
    (if (g/null? c) acc (recur (next-sibling c) (conj acc c)))))

(defn addr [p] (ffi/address p))
(defn texts [w] (mapv label-text (gtk-children w)))

;; --- 1. a keyed reorder moves widgets instead of relabelling them ---------
(defn rows [ks] [:vbox {} (for [k ks] [:label {:key k} (str "row " k)])])

(render! (rows [1 2 3 4]))
(def box (:widget @tree))
(def by-key (zipmap [1 2 3 4] (map addr (gtk-children box))))
(render! (rows [4 3 2 1]))
(println "1) reversed:" (texts box))
(assert (= ["row 4" "row 3" "row 2" "row 1"] (texts box)) "GTK order does not follow the keys")
(assert (= (map by-key [4 3 2 1]) (map addr (gtk-children box)))
        "a moved row should keep its widget")
(println "   same four widgets, moved")

;; --- 2. an insert in the middle lands in the middle -----------------------
(render! (rows [4 3 9 2 1]))
(println "2) inserted 9:" (texts box))
(assert (= ["row 4" "row 3" "row 9" "row 2" "row 1"] (texts box)))
(assert (= (map by-key [4 3 2 1]) (map addr (remove #(= "row 9" (label-text %))
                                                   (gtk-children box)))))

;; --- 3. a removed widget is freed, the rest are untouched ------------------
;; g_object_weak_ref fires when the object is finalized: proof the reference
;; we took at construction was dropped, and nothing else holds it.
(def freed (atom []))
(def on-free (ffi/callback (ffi/global-arena)
                           (fn [data _obj] (swap! freed conj (ffi/address data)))
                           [:pointer :pointer] :void))
(def victim (first (filter #(= "row 3" (label-text %)) (gtk-children box))))
(weak-ref victim on-free victim)
(render! (rows [4 9 2 1]))
(println "3) removed 3:" (texts box) " finalized:" (= [(addr victim)] @freed))
(assert (= ["row 4" "row 9" "row 2" "row 1"] (texts box)))
(assert (= [(addr victim)] @freed) "the removed label was not finalized")

;; --- 4. a tag change keeps its position -----------------------------------
;; The old reconciler appended the replacement at the end of the parent.
(render! [:vbox {} [:label "a"] [:label "b"] [:label "c"]])
(def box4 (:widget @tree))
(render! [:vbox {} [:label "a"] [:button "b"] [:label "c"]])
(def kinds (mapv type-name (gtk-children box4)))
(println "4) after a tag change in the middle:" kinds)
(assert (= ["GtkLabel" "GtkButton" "GtkLabel"] kinds) "the replacement moved")

;; --- 5. ... and works in a single-child container -------------------------
;; Replicant inserts the new child before removing the old one; a container
;; holding one child must not end up empty.
(render! [:bin {} [:label "old"]])
(def bin (:widget @tree))
(render! [:bin {} [:button "new"]])
(def content (overlay-child bin))
(println "5) single-child container holds:" (when-not (g/null? content) (type-name content)))
(assert (and (not (g/null? content)) (= "GtkButton" (type-name content))))

;; --- 6. a container with no positional insert still gets the order right --
(ui/register-widget! :append-only-box
  {:ctor   (fn [_] (g/box-new g/VERTICAL 0))
   :apply  (fn [_ _ _] nil)
   :append (fn [parent child _] (g/box-append parent child))
   :remove (fn [parent child _] (g/box-remove parent child))})
(defn rows6 [ks] [:append-only-box {} (for [k ks] [:label {:key k} (str k)])])
(render! (rows6 ["a" "b" "c" "d"]))
(def box6 (:widget @tree))
(def ids6 (zipmap ["a" "b" "c" "d"] (map addr (gtk-children box6))))
(render! (rows6 ["a" "d" "b" "c"]))
(println "6) append-only container:" (texts box6))
(assert (= ["a" "d" "b" "c"] (texts box6)))
(assert (= (map ids6 ["a" "d" "b" "c"]) (map addr (gtk-children box6))))

;; --- 7. lifecycle hooks run once the widget is in its parent ---------------
(def hooks (atom []))
(defn hooked [show?]
  [:vbox {}
   (when show?
     [:label {:replicant/on-mount
              (fn [e]
                (let [w (ui/widget e)]
                  ;; @tree is still the previous render here, so ask GTK
                  (swap! hooks conj [:mount (label-text w)
                                     (let [p (parent-of w)]
                                       (and (not (g/null? p)) (type-name p)))])))
              :replicant/on-unmount (fn [_] (swap! hooks conj [:unmount]))}
      "hooked"])])
(render! (hooked true))
(render! (hooked true))
(render! (hooked false))
(println "7) hooks:" @hooks)
(assert (= [[:mount "hooked" "GtkBox"] [:unmount]] @hooks)
        "mount once, with the widget already parented; unmount on removal")

;; --- 8. handlers can be data ------------------------------------------------
(def dispatched (atom []))
(ui/set-dispatch! (fn [e data] (swap! dispatched conj [(:gtkiccup/event e) data])))
(render! [:vbox {} [:button {:on-click [:counter/inc 1]} "+1"]])
(emit (first (gtk-children (:widget @tree))) "clicked")
(println "8) data handler dispatched:" @dispatched)
(assert (= [[:on-click [:counter/inc 1]]] @dispatched))
(ui/set-dispatch! nil)
(def refused (try (render! [:button {:on-click [:x]} "x"]) :NO-ERROR
                  (catch Exception e (first (clojure.string/split-lines (ex-message e))))))
(println "   without a dispatch fn it is refused:" refused)
(assert (string? refused) "a data handler with nothing to dispatch to must be refused")

;; --- 9. classes in the tag ---------------------------------------------------
(defcfn has-class? "gtk_widget_has_css_class" [:pointer :string] :int)
(render! [:label.title.dim "x"])
(println "9) [:label.title.dim] classes:"
         (mapv #(g/<-gbool (has-class? (:widget @tree) %)) ["title" "dim"]))
(assert (every? #(g/<-gbool (has-class? (:widget @tree) %)) ["title" "dim"]))

;; --- 10. the tree snapshot carries classes under :class ----------------------
;; Replicant diffs classes itself, so they are not props on the way in; an app
;; that finds its widgets by class in (:tree @ui/current) still needs them.
(render! [:vbox {} [:label.title {:class ["passage" nil]} "x"] [:label "y"]])
(def snap-classes (mapv #(get-in % [:props :class]) (:children @tree)))
(println "10) classes in the tree:" snap-classes)
(assert (= [["passage" "title"] nil] snap-classes))

(println "ALL OK")

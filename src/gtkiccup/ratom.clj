(ns gtkiccup.ratom
  "Reactive atoms. A plain Clojure atom plus a watch that marks the UI dirty.
   Deliberately coarse: any change re-runs the render fn, and the renderer
   diffs the resulting hiccup so only real changes touch GTK."
  (:refer-clojure :exclude [atom]))

;; set by gtkiccup.core/run
(defonce ^:private invalidate-fn (clojure.core/atom (fn [])))

(defn set-invalidate! [f] (reset! invalidate-fn f))

(defn invalidate!
  "Marks the UI dirty by hand. Safe to call from any thread: it only flips a
   flag, and the render itself happens on the thread running the GTK loop."
  []
  (@invalidate-fn)
  nil)

(defn atom
  "Like clojure.core/atom, but changes schedule a re-render."
  [init]
  (doto (clojure.core/atom init)
    (add-watch ::reactive
               (fn [_ _ old new]
                 (when (not= old new) (@invalidate-fn))))))

(ns gtkiccup.refs
  "Named widget references.

   Some GTK APIs connect two widgets -- `AdwViewSwitcher.set_stack`, a
   popover's parent, a size group -- and a hiccup tree has no way to point one
   node at another. A `:ref` prop names a widget, so another node can find it
   by name:

     [:view-stack {:ref \"pages\" :visible-child-name \"player\"} ...]
     [:vbox {} [:view-switcher {:stack \"pages\" :policy :wide}]]

   The renderer registers the name when it builds the widget and drops it when
   the element is released, so the table only ever holds live widgets. Names
   are strings; a keyword is accepted and folded to one.

   A spec that resolves a ref while it is being constructed may find its
   sibling is not built yet -- construction order follows the tree. The
   renderer's `:after-children` step runs once the container's children are
   all in place, which is why specs like `:view-switcher` resolve refs in both
   `:apply` and `:after-children`."
  (:require [babashka.ffi :as ffi]))

(defonce ^{:doc "name -> widget pointer, kept current by gtkiccup.renderer."}
  refs (atom {}))

(defn- key-of [x]
  (cond
    (string? x) x
    ;; `name` is not called inside a binding named `name`: a keyword is
    ;; callable, so (name name) would ask the keyword for its own value.
    (keyword? x) (name x)
    :else (str x)))

(defn register!
  "Points `name` at `widget`. Returns the widget, so it composes into a spec."
  [name widget]
  (swap! refs assoc (key-of name) widget)
  widget)

(defn unregister!
  "Drops `name`, but only while it still points at `widget`: if a newer
   registration has taken the name over, that one is the truth and stays."
  [name widget]
  (swap! refs (fn [m] (let [k (key-of name)]
                        (if (identical? (get m k) widget)
                          (dissoc m k)
                          m))))
  nil)

(defn resolve
  "The widget `name` refers to, or nil. Anything that is not a name is taken
   to be a widget already, so `:stack` can also be handed a kept pointer."
  [name]
  (cond
    (nil? name) nil
    (or (string? name) (keyword? name)) (get @refs (key-of name))
    :else name))

(defn clear!
  "Forgets everything. Called when a run ends, and by tests between cases."
  []
  (reset! refs {})
  nil)

(defn names
  "The live ref names, for debugging."
  []
  (sort (keys @refs)))

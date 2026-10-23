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
   `:apply` and `:after-children`.

   Uniqueness is the caller's job: a name points at ONE widget, and
   `register!` is last-write-wins. When two live widgets share a name -- the
   classic case is deriving the name from item data and rendering the same
   item twice (one album in two strips) -- `resolve` only ever finds the
   widget that registered last, and the earlier one silently never receives
   anything done through the name: an image that stays a placeholder, a value
   that never lands. The renderer detects this at the end of the render (see
   `render-done!`) and logs a warning ONCE per name, so the head-scratching
   has a paper trail -- but it is a warning, not a fix. Rebuilding does not
   heal it if the names are deterministic. Include what makes the occurrence
   unique in the name (the section it lives in; an index when duplicates are
   possible within one scope), or pass a kept pointer instead of a name."
  (:require [babashka.ffi :as ffi]))

(defonce ^{:doc "name -> widget pointer, kept current by gtkiccup.renderer."}
  refs (atom {}))

(defonce ^{:doc "name -> {:old w :new w} for registrations that displaced a
  live widget during the CURRENT render. render-done! (the renderer's
  end-of-render hook) warns the ones still standing -- a displaced widget
  released during the same render cancels its entry via unregister!, so what
  survives is two widgets that were live together, not a rebuild handover."}
  collisions (atom {}))

(defonce ^{:doc "names already warned about by render-done!: the collision
  warning fires once per name, never as a per-render flood. clear! forgets."}
  warned (atom #{}))

(defn- key-of [x]
  (cond
    (string? x) x
    ;; `name` is not called inside a binding named `name`: a keyword is
    ;; callable, so (name name) would ask the keyword for its own value.
    (keyword? x) (name x)
    :else (str x)))

(defn register!
  "Points `name` at `widget`. Returns the widget, so it composes into a spec.
   LAST-WRITE-WINS: a name holds one widget at a time, so two live widgets
   sharing a name make the earlier one unreachable by `resolve` -- build names
   unique per live widget (see the ns docstring's uniqueness note). A
   registration that displaces a different live widget is recorded as a
   pending collision; `render-done!` warns once per name about the ones that
   turn out to be genuinely simultaneous."
  [name widget]
  (let [k   (key-of name)
        old (get @refs k)]
    (when (and old (not (identical? old widget)))
      (swap! collisions assoc k {:old old :new widget}))
    (swap! refs assoc k widget))
  widget)

(defn unregister!
  "Drops `name`, but only while it still points at `widget`: if a newer
   registration has taken the name over, that one is the truth and stays.
   Releasing a widget that a pending collision names as its `:old` cancels
   that collision: the name changed hands because a render REPLACED the
   widget, not because two widgets are alive under one name."
  [name widget]
  (let [k (key-of name)]
    (when-let [c (get @collisions k)]
      (when (identical? widget (:old c))
        (swap! collisions dissoc k)))
    (swap! refs (fn [m] (if (identical? (get m k) widget)
                          (dissoc m k)
                          m))))
  nil)

(defn render-done!
  "The renderer calls this once at the end of every render (reconcile's
   finally, after flush!). Pending collisions still standing are genuine --
   both widgets were live under one name, nobody released the loser -- so log
   ONE warning per name to *err*. A rebuild whose displaced widget was
   released in the same render already cancelled via unregister!, so ordinary
   replacement is silent, and an already-warned name never re-fills the log."
  []
  ;; reset! returns the NEW value, so consume via a read-then-clear: the
  ;; renderer runs render! and render-done! on the same (GTK) thread, so no
  ;; concurrent register! can slip between the read and the clear.
  (let [cs @collisions]
    (when (seq cs)
      (reset! collisions {})
      (doseq [[nm {:keys [old new]}] cs
              :when (not (contains? @warned nm))]
        (swap! warned conj nm)
        (binding [*out* *err*]
          (println (str "\n[gtkiccup] ref collision: \"" nm
                        "\" names two live widgets (" old " and " new ")"
                        " -- resolve finds only the one that registered last,"
                        " and the other never sees anything done through the"
                        " name. Make the name unique per widget (include its"
                        " section / an index) or pass a kept pointer."
                        " (warned once per name; refs/clear! resets)"))))))
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
  "Forgets everything -- refs, pending collisions, and the warned-names set
   (so a later run warns again). Called when a run ends, and by tests between
   cases."
  []
  (reset! refs {})
  (reset! collisions {})
  (reset! warned #{})
  nil)

(defn names
  "The live ref names, for debugging."
  []
  (sort (keys @refs)))

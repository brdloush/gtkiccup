(ns gtkiccup.hiccup
  "Our hiccup dialect, and its translation into Replicant's.

   Two passes, both pure:

     normalize   validates the whole tree and folds text children into the
                 widget's text prop. Runs before anything touches GTK, so a
                 malformed view is rejected without half-mutating the window.
     ->replicant turns the result into the hiccup Replicant diffs.

   The translation is where GTK and HTML disagree. Replicant follows the DOM:
   an attribute whose value is false or nil is *removed*, and a false value on
   first render is never sent. GTK props are not like that -- `:sensitive
   false` is a value, and an explicit nil must still reach the widget. So every
   prop value travels boxed in a one-element vector: always truthy, compared by
   value, and unboxed by the renderer. Replicant never looks inside."
  (:require [clojure.string]
            [gtkiccup.widgets :as w]))

(defonce ^{:doc "The fn non-fn handlers are dispatched to, set with
  gtkiccup.core/set-dispatch!. Called as (f event-map handler-data)."}
  dispatch
  (atom nil))

(def ^:private hook-keys
  #{:replicant/on-mount :replicant/on-update :replicant/on-unmount :replicant/on-render})

;; ---------------------------------------------------------------------------
;; Component memoization
;; ---------------------------------------------------------------------------
;;
;; A render re-runs every function component in the tree, even when nothing it
;; depends on has changed. `memoized` opts a view fn out of that: `normalize`
;; caches the expansion of [fn args...] and reuses it while the args are `=`.
;; The cached value carries its own `->replicant` output under
;; :replicant/cached, so a hit skips both the component call and the
;; translation of its subtree.
;;
;; Two rules make this safe:
;;
;;   - A memoized view fn must be PURE in its args: same args -> same tree. It
;;     must not deref application state, read time, or use rand. If it does,
;;     the cache serves a stale tree forever -- either pass everything it
;;     renders through args, or don't memoize it.
;;   - Expansions containing lifecycle hooks (any :replicant/on-* prop anywhere
;;     in the subtree) are never cached: `hook` closes over the render-scoped
;;     *defer-hook*, so a cached wrapper would defer to a dead render.

(declare ->replicant)

(defonce ^:private memo-cache (atom {}))

(defonce ^:private data-handler-cache (atom {}))

(def ^:private memo-cache-max
  "Whole-cache eviction: at the cap the cache is cleared rather than aged.
   A view is a handful of component shapes, so a miss after a clear costs one
   expansion each -- cheap, and it keeps the cache a plain `=`-keyed map."
  4000)

(defn memoized
  "Mark a pure view fn as cacheable by `normalize`.

   (def row (h/memoized row-fn))
   [:vbox {} [row track playing-id]]

   See the note at the top of this namespace: the fn must render only from its
   args. Reuse one marked fn (a def), the cache keys on its identity."
  [f]
  (with-meta f {:gtkiccup/memo true}))

(defn clear-memo!
  "Drop every cached component expansion (and the cached data handlers).
   Useful in tests and after re-defining components in a REPL."
  []
  (reset! memo-cache {})
  (reset! data-handler-cache {}))

(defn- node-has-hooks?
  "Any :replicant/on-* prop anywhere in a normalized subtree."
  [{:keys [props children]}]
  (boolean (or (some hook-keys (keys props))
               (some node-has-hooks? children))))

(defn- memo-lookup [tag more]
  (when (:gtkiccup/memo (meta tag))
    (get @memo-cache [tag (vec more)])))

(defn- memo-store! [tag more node]
  "Return the node to keep for this expansion: the cached one when tag is
   `memoized` and the subtree is hook-free, otherwise `node` unchanged."
  (if (and (:gtkiccup/memo (meta tag))
           (not (node-has-hooks? node)))
    (do (when (>= (count @memo-cache) memo-cache-max)
          (reset! memo-cache {}))
        (let [cached (assoc node :replicant/cached (->replicant node))]
          (swap! memo-cache assoc [tag (vec more)] cached)
          cached))
    node))

(defn- text-like?
  "A child that reads as content rather than as a widget."
  [x]
  (or (string? x) (number? x)))

(defn- child-run?
  "True when x is a run of children rather than one child.

   A widget vector's head is a tag -- a keyword or an fn -- so a vector whose
   head is itself a vector cannot be a widget form; it is (mapv ...) or
   (mapcat ...) output, a run of siblings that must be spliced like a seq.
   Without this, a vector-returning form like (mapv cover-card cards) arrives
   as a single child whose tag is the whole run, and is rejected as an unknown
   widget."
  [x]
  (and (vector? x) (or (empty? x) (vector? (first x)))))

(defn- spliced
  "Flattens child collections: a lazy seq (map, filter, for) splices like
   always, and so does a vector that is not a widget form (mapv, mapcat,
   vec). Nested runs splice too -- (mapv splice-ish things) is the whole point
   of building a view out of helpers."
  [xs]
  (mapcat (fn [x]
            (cond
              (nil? x) []
              (or (seq? x) (child-run? x)) (spliced x)
              :else [x]))
          xs))

(defn- hiccup-error! [msg data]
  (throw (ex-info msg data)))

(defn- bad-child! [form parent]
  (hiccup-error!
   (str "invalid hiccup " (if parent (str "inside " parent) "at the root of a view")
        ": " (pr-str form)
        "\n  expected a vector like [:label \"hi\"], a string, a number, a seq of"
        " those, or nil")
   {:form form :parent parent}))

(defn- base-tag
  "[:label.title.dim ...] is a :label with two classes. Replicant reads the
   classes out of the tag itself; the widget table only needs the part before
   the first dot."
  [tag]
  (if (and (keyword? tag) (nil? (namespace tag)))
    (let [n (name tag) i (.indexOf n ".")]
      (if (pos? i) (keyword (subs n 0 i)) tag))
    tag))

(defn normalize
  "Expands function components, flattens seqs, and folds text children into the
   widget's text prop, so [:label \"Count: \" 3] means [:label {:label \"Count: 3\"}].

   Returns {:tag kw :props map :children [normalized...]} or nil.

   Runs over the whole tree before `reconcile` touches a single widget, so a
   malformed view is rejected without half-mutating the window.

   Expansions of components marked with `memoized` are cached on [fn args];
   a hit returns the cached subtree (carrying its prebuilt :replicant/cached
   form) without calling the fn. See the memoization note above."
  ([form] (normalize form nil))
  ([form parent]
   (cond
     (nil? form) nil
     (not (vector? form)) (bad-child! form parent)
     :else
     (let [[tag & more] form]
       (if (fn? tag)
         (or (memo-lookup tag more)
             (memo-store! tag more (normalize (apply tag more) parent)))
         (let [spec (or (@w/widgets (base-tag tag))
                        (hiccup-error!
                         (str "unknown widget " (pr-str tag)
                              "\n  known widgets: "
                              (clojure.string/join " " (sort (map str (keys @w/widgets)))))
                         {:tag tag :known (set (keys @w/widgets))}))
               props (if (map? (first more)) (first more) {})
               kids  (->> (if (map? (first more)) (rest more) more)
                          spliced
                          (remove nil?))
               _     (run! #(when-not (or (vector? %) (text-like? %))
                              (bad-child! % tag))
                           kids)
               texts (remove vector? kids)
               nodes (filterv vector? kids)
               tprop (:text-prop spec)]
           (when (and (seq texts) (nil? tprop))
             (hiccup-error!
              (str tag " has no text of its own: " (pr-str (vec texts))
                   "\n  put the text in a child, e.g. [" tag " {} [:label "
                   (pr-str (str (first texts))) "]]")
              {:tag tag :texts (vec texts)}))
           (when (and (seq texts) (contains? props tprop))
             (hiccup-error!
              (str tag " was given both a " tprop " prop and text children"
                   "\n  drop one: [" tag " {" tprop " ...}] or [" tag " \"...\"]")
              {:tag tag :prop tprop :texts (vec texts)}))
           (when (and (seq nodes) (nil? (:append spec)))
             (hiccup-error!
              (str tag " cannot contain child widgets"
                   "\n  wrap them in a :vbox or :hbox instead")
              {:tag tag :children (count nodes)}))
           {:tag tag
            :props (cond-> props
                     (seq texts) (assoc tprop (apply str texts)))
            :children (mapv #(normalize % tag) nodes)}))))))

;; ---------------------------------------------------------------------------
;; -> Replicant hiccup
;; ---------------------------------------------------------------------------

(defn- data-handler
  "A handler that is data, not a fn -- [:todo/toggle 3], say. It is sent to
   the dispatch fn along with what the signal produced. Cached by [prop data]
   so repeated renders produce `=` (indeed identical) handler fns, which keeps
   Replicant's attribute diff from seeing churn; the fn resolves @dispatch at
   call time, so a later set-dispatch! still takes effect. (Corollary: a
   cached handler invoked after set-dispatch! nil would call a nil fn -- the
   pre-memoization code captured the fn instead. Only tests reset dispatch.)"
  [prop data]
  (if-let [cached (get @data-handler-cache [prop data])]
    cached
    (do (when-not @dispatch
          (hiccup-error!
           (str prop " is data, not a fn: " (pr-str data)
                "\n  data handlers need (gtkiccup.core/set-dispatch! f) first")
           {:prop prop :handler data}))
        (let [f (fn [& args]
                  (@dispatch {:gtkiccup/event prop :gtkiccup/args (vec args)} data))]
          (when (>= (count @data-handler-cache) memo-cache-max)
            (reset! data-handler-cache {}))
          (swap! data-handler-cache assoc [prop data] f)
          f))))

(def ^:dynamic *defer-hook*
  "Bound by the renderer for the length of a render: takes a thunk and runs it
   once GTK is in sync. nil outside a render, and then a hook runs at once."
  nil)

(defn- hook
  "Replicant calls lifecycle hooks at the end of its diff, which here is
   before GTK has been brought in line -- see gtkiccup.renderer. So each hook
   is wrapped to wait for that. A hook that is data goes to the dispatch fn,
   which is why Replicant's own *dispatch* is never needed."
  [k v]
  (when v
    (when-not (or (fn? v) @dispatch)
      (hiccup-error!
       (str k " is data, not a fn: " (pr-str v)
            "\n  data hooks need (gtkiccup.core/set-dispatch! f) first")
       {:prop k :handler v}))
    (fn [e]
      (let [run #(if (fn? v) (v e) (@dispatch e v))]
        (if *defer-hook* (*defer-hook* run) (run))))))

(defn- ->attrs
  "Our props -> Replicant attributes. Signals go under :on, keyed by their own
   prop name; :key becomes Replicant's key; :class passes through, since
   Replicant diffs classes itself; lifecycle hooks are wrapped to run after
   GTK is in sync; other replicant/* keys pass through; everything else is
   boxed."
  [props signals]
  (reduce-kv
   (fn [m k v]
     (cond
       (contains? signals k)
       (assoc-in m [:on k] (if (or (nil? v) (fn? v) (var? v)) v (data-handler k v)))

       (= :key k)   (assoc m :replicant/key v)
       (= :class k) (assoc m :class v)
       (= :style k)
       (hiccup-error! (str ":style is HTML. Style a GTK widget with CSS: give it a :class"
                           " and install the rules with gtkiccup.core/load-css!")
                      {:style v})
       (hook-keys k) (assoc m k (hook k v))
       (= "replicant" (namespace k)) (assoc m k v)
       :else (assoc m k [v])))
   {}
   props))

(defn ->replicant
  "A normalized node -> Replicant hiccup.

   Nodes from `normalize` whose expansion was cached by `memoized` carry that
   form prebuilt under :replicant/cached, and it is reused as-is -- the
   widget/signal tables are static once loaded, so a cached form stays valid."
  ([node] (or (:replicant/cached node) (->replicant node @w/signals)))
  ([{:keys [tag props children]} signals]
   (into [tag (->attrs props signals)]
         (map #(or (:replicant/cached %) (->replicant % signals)))
         children)))

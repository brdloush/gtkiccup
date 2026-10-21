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

(defonce ^:private memo-cache
  ;; LRU state, not a plain key->node map: {:entries {k {:v node :cost bytes
  ;; :last clock}} :cost total-estimated-bytes :clock monotonic-access-counter}.
  ;; `:last` is bumped on every HIT, so the rows of the page currently on screen
  ;; are the last to be evicted and goal 07's per-render memo wins survive;
  ;; unaccessed entries (rows of pages the user navigated away from) are what
  ;; the byte budget pays. babatidal's goal 09 measured ~40KB of retained JVM
  ;; heap per DISTINCT row, and a plain count cap of 4000 was therefore worth
  ;; ~160MB -- more than the app's whole heap -- so the count cap became a
  ;; secondary bound under `memo-budget-bytes`.
  (atom {:entries {} :cost 0 :clock 0}))

(defonce ^:private data-handler-cache
  ;; Same LRU shape as `memo-cache`. Its `[prop data]` KEYS retain the caller's
  ;; `data` (usually a whole row map), so this cache held per-distinct-row mass
  ;; too; the same byte budget bounds it.
  (atom {:entries {} :cost 0 :clock 0}))

(def memo-cache-max
  "Secondary hard bound per cache (entry COUNT). The real bound is
   `memo-budget-bytes`; this one only stops a pathological flood of
   micro-entries from making the LRU list itself expensive. Eviction past
   either bound is LRU (oldest `:last` first), never a wholesale reset: a
   reset would re-expand every hot row on the next render tick, which is
   exactly the scroll-jank goal 07 removed."
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
  (reset! memo-cache {:entries {} :cost 0 :clock 0})
  (reset! data-handler-cache {:entries {} :cost 0 :clock 0}))

(defn- node-has-hooks?
  "Any :replicant/on-* prop anywhere in a normalized subtree."
  [{:keys [props children]}]
  (boolean (or (some hook-keys (keys props))
               (some node-has-hooks? children))))

(def memo-budget-bytes
  "Estimated-bytes budget PER cache (memo-cache, data-handler-cache each get
   this). Default 16MB: a realistic live page is a few hundred rows at ~40KB
   of retained subtree each, which stays fully resident, while the rows of
   every page the user navigated away from are what the LRU drops. Override
   with the environment (KB) for probes and tests."
  (long
   (or (some-> (System/getenv "GTKICCUP_MEMO_BUDGET_KB") Integer/parseInt)
       (* 16 1024 1024))))

(defn- est
  "Conservative estimate of the JVM heap a cached value retains: a flat
   allowance per container/scalar plus the characters of every string.
   Deliberately dumb and OVER-estimating -- it only needs to be within a small
   factor for the byte budget to do its job (goal 09: the real cost was ~40KB
   per row of node + prebuilt replicant; this sees the same trees). No cycle
   risk: normalized nodes and hiccup data are acyclic."
  [x]
  (cond
    (nil? x) 0
    (string? x) (+ 40 (* 2 (count x)))
    (map? x) (+ 96 (reduce + 0 (map (fn [[k v]] (+ (est k) (est v))) x)))
    (set? x) (+ 96 (reduce + 0 (map est x)))
    (sequential? x) (+ 96 (reduce + 0 (map est x)))
    (instance? clojure.lang.MapEntry x) 64
    :else 32))

(defn- lru-evict-over
  "Drop lowest-`:last` entries until the state is within both bounds. The
   entry just stored has the newest `:last`, so it is dropped last: a single
   value bigger than the whole budget evicts everything else and then itself
   (a big page then simply re-expands; the heap still cannot be overrun)."
  [{:keys [entries clock] :as st} budget]
  (if (and (<= (:cost st) budget) (<= (count entries) memo-cache-max))
    st
    (loop [ks   (map first (sort-by (fn [[_ e]] [(:last e) (:cost e)]) entries))
           cost (:cost st)
           es   entries]
      (if (or (empty? ks)
              (and (<= cost budget) (<= (count es) memo-cache-max)))
        {:entries es :cost cost :clock clock}
        (let [k (first ks)
              e (get es k)]
          (recur (next ks) (- cost (:cost e)) (dissoc es k)))))))

(defn- lru-get
  "The value for `k`, bumping its access clock on a hit (that bump is what
   protects the hot set: the page on screen is re-accessed every render, so
   its rows are the last candidates for eviction). NB `(update st :entries
   ...)` -- the first version threaded `(update entries ...)` inside `->`,
   which passed the map as the KEY and silently bumped only the clock, so the
   hot set aged out like any cold entry."
  [a k]
  (when-let [e (get-in @a [:entries k])]
    (swap! a (fn [{:keys [clock entries] :as st}]
               (let [c (inc clock)]
                 (-> st
                     (assoc :clock c)
                     (assoc-in [:entries k :last] c)))))
    (:v e)))

(defn- lru-put
  "Store `v` under `k` with estimated `cost`, then evict over-budget,
   least-recently-used first. NB: the state's TOTAL and this entry's `cost`
   are different numbers -- the first version destructured both as `cost` and
   the shadowing made every entry cost 0 (the budget never saw anything); the
   `total` binding below is what keeps that honest."
  [a k v cost budget]
  (let [prev (or (get-in @a [:entries k :cost]) 0)]
    (swap! a (fn [{:keys [entries clock] :as st}]
               (let [c     (inc clock)
                     total (:cost st)]
                 (lru-evict-over
                  {:entries (assoc entries k {:v v :cost cost :last c})
                   :cost    (+ (- total prev) cost)
                   :clock   c}
                  budget))))))

(defn memo-stats
  "Diagnostics for probes and tests: entry count and estimated bytes per
   cache, plus the active budget. (The caches are private LRU state; this is
   the read window.)"
  []
  {:memo-entries    (count (:entries @memo-cache))
   :memo-cost       (:cost @memo-cache)
   :handler-entries (count (:entries @data-handler-cache))
   :handler-cost    (:cost @data-handler-cache)
   :budget-bytes    memo-budget-bytes})

(defn- memo-lookup [tag more]
  (when (:gtkiccup/memo (meta tag))
    (lru-get memo-cache [tag (vec more)])))

(defn- memo-store! [tag more node]
  "Return the node to keep for this expansion: the cached one when tag is
   `memoized` and the subtree is hook-free, otherwise `node` unchanged.

   The cached value is the node plus its prebuilt replicant, and the ENTRY --
   like the key -- is keyed by the row's own args, so it is what the byte
   budget bounds: cost = estimate of the cached value + estimate of the args
   (the key vector retains them)."
  (if (and (:gtkiccup/memo (meta tag))
           (not (node-has-hooks? node)))
    (let [cached (assoc node :replicant/cached (->replicant node))]
      (lru-put memo-cache [tag (vec more)] cached
               (+ (est cached) (est (vec more)))
               memo-budget-bytes)
      cached)
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
   pre-memoization code captured the fn instead. Only tests reset dispatch.)

   LRU-bounded like `memo-store!`: the [prop data] KEY retains `data` (often a
   whole row map), so the entry's cost counts both."
  [prop data]
  (if-let [cached (lru-get data-handler-cache [prop data])]
    cached
    (do (when-not @dispatch
          (hiccup-error!
           (str prop " is data, not a fn: " (pr-str data)
                "\n  data handlers need (gtkiccup.core/set-dispatch! f) first")
           {:prop prop :handler data}))
        (let [f (fn [& args]
                  (@dispatch {:gtkiccup/event prop :gtkiccup/args (vec args)} data))]
          (lru-put data-handler-cache [prop data] f
                   (+ (est prop) (est data) 128)
                   memo-budget-bytes)
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

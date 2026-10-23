(ns gtkiccup.alias
  "Late-bound component expansion -- components as DATA.

   A component is a namespaced keyword plus a registered expansion fn. The page
   emits the keyword as a node tag with a plain-data attrs map:

     [:bt/track-row {:album a :track t :playing? p}]

   `gtkiccup.hiccup/normalize` recognises a registered qualified keyword and
   passes the node through untouched (it is a cache key, not a widget yet), and
   `reconcile` expands it LAZILY: Replicant's `unchanged?` compares the stored
   alias node against the incoming one, and only when the attrs differ does it
   call the registered fn. A hit expands nothing and walks nothing -- the whole
   subtree short-circuits on identity. That replaces the `h/memoized` ritual
   (body/prep/marker/node-emitter/shim per component) with the upstream
   mechanism whose cache IS Replicant's own vdom.

   ## The data-flow contract (the thing an app must get right)

   The attrs map IS the cache key and IS the expansion's whole input. Anything
   the expansion reads must arrive as an attr; a value read at expansion time
   from live state can go stale, because a state change that does not move an
   attr never re-expands. So:

   - attrs must be plain data (`=`-comparable): maps, vectors, keywords,
     numbers, strings, and data handlers like `[:bt/play {:id 7}]`. A fresh
     CLOSURE in an attr breaks the key every render (the expansion never hits).
   - live values are read by the PAGE (which runs every render) and passed in
     as attrs -- the page prep is the attrs map itself, no separate prep defs.
   - `:replicant/alias-data` (set with the `:alias-data` run opt) is merged into
     every expansion's attrs by Replicant -- the sanctioned side-chain for
     near-static config (theme, country). It is NOT for live state: changing it
     invalidates EVERY alias expansion.

   The expansion fn is registered here as a raw `(fn [attrs children] -> hiccup)`;
   `gtkiccup.hiccup` owns the wrapper that runs its result through `normalize` +
   `->attrs` + `->replicant` at EXPANSION time (so signal conversion, value
   boxing, data handlers and lifecycle hooks all still apply, under the
   renderer's current `*defer-hook*` binding)."
  )

;; ---------------------------------------------------------------------------
;; Registry: qualified keyword -> raw expansion fn (fn [attrs children] -> hiccup)

(def ^{:doc "The alias registry: {qualified-keyword raw-expansion-fn}. `reconcile` reads
   it each render through `gtkiccup.hiccup/aliases-for-render`, which wraps each
   fn so its result passes through the prop pipeline at expansion time."}
  aliases
  (atom {}))

(def ^{:doc "Registration options per alias: {keyword {:purity :deps|:pure}}. `:purity`
   is advisory documentation today (double-invoke checking is a future knob);
   recorded so `alias-stats` can report it."}
  opts
  (atom {}))

(def ^{:doc "Observability: {keyword {:expansions n}}. Incremented by
   `gtkiccup.hiccup/expand-alias` on every cold expansion, so a cache that
   never fires (or an alias nobody registered right) is no longer silent."}
  stats
  (atom {}))

(defn- valid-tag? [k]
  (and (keyword? k) (some? (namespace k))))

(defn register-alias!
  "Registers `f` as the expansion for qualified keyword `k`.

     (register-alias! :bt/track-row (fn [attrs children] [:hbox {...} ...]))
     (register-alias! :bt/track-row {:purity :deps} (fn [attrs _] ...))

   `f` is called as (f attrs children) where `attrs` is the emitted attrs map as
   PLAIN DATA (plus :replicant/alias-data when set) and `children` is a flattened
   seq of any emitted children. It returns a single hiccup vector (or a string).
   Re-registering the same keyword swaps the fn; the vdom keys on the keyword,
   not the fn identity, so hot reload picks new bodies up without invalidating
   cached nodes. Returns k."
  ([k f]
   (register-alias! k {} f))
  ([k opts-map f]
   (when-not (valid-tag? k)
     (throw (ex-info (str "alias tag must be a namespaced keyword, got: " (pr-str k))
                     {:alias k})))
   (when-not (fn? f)
     (throw (ex-info (str "alias " (pr-str k) " expansion must be a fn, got: " (pr-str f))
                     {:alias k})))
   (swap! opts assoc k opts-map)
   (swap! aliases assoc k f)
   k))

(defn registered?
  "True when `k` is a qualified keyword registered as an alias. `normalize` uses
   this to decide pass-through vs the unknown-widget error."
  [k]
  (contains? @aliases k))

(defn clear-aliases!
  "Empties the registry, options and stats. Tests use it to start clean; the
   dev loop does not need it (register-alias! replaces by keyword)."
  []
  (reset! aliases {})
  (reset! opts {})
  (reset! stats {})
  nil)

(defn reset-stats!
  "Zeroes the per-alias expansion counters without touching the registry -- so a
   test can snapshot expansions between renders."
  []
  (reset! stats {})
  nil)

(defn alias-stats
  "Diagnostics: {keyword {:expansions n :purity p}} for every alias that has
   expanded at least once or is registered. The read window on the mechanism:
   an unchanged attrs map over two renders means +0 expansions."
  []
  (into {}
        (map (fn [k]
               [k (merge {:expansions (get-in @stats [k :expansions] 0)
                          :registered (contains? @aliases k)}
                         (when-let [p (:purity (get @opts k))]
                           {:purity p}))]))
        (into (keys @aliases) (keys @stats))))

(defn on-alias-exception
  "The `:on-alias-exception` handler passed to Replicant. Replicant calls it as
   (f exception [tag-name attrs children]) when an expansion throws; without it
   the render degrades to an opaque error div. We LOG (the [gtkiccup] pattern the
   rest of the library uses) and return a valid empty label so the frame keeps a
   visible, GTK-buildable node instead of an HTML div we cannot build. Render
   continues; the next good attrs map replaces it."
  [e [tag-name attrs _children]]
  (binding [*out* *err*]
    (println (str "\n[gtkiccup] alias " (pr-str tag-name)
                  " expansion threw: " (or (ex-message e) (type e))
                  "  attrs=" (pr-str (into {} (remove (comp #{:children} key) attrs))))))
  [:label {:label "" :class "alias-error"}])

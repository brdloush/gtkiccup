(ns gtkiccup.renderer
  "Replicant's reconciler, rendering into GTK4 widgets.

   Replicant does the diffing -- keyed children, moves, lifecycle hooks -- and
   talks to the host only through `replicant.protocols/IRender`. This namespace
   is that protocol for GTK, extended through metadata so there is no deftype.

   An *element* is an atom over a plain map, one per widget:

     {:tag :label, :spec {...}, :props {...}, :classes #{...},
      :widget ptr-or-nil, :kids [element ...], :handlers {prop holder}}

   Replicant addresses children by index (`get-child`, `insert-before`), so each
   element mirrors its child list.

   Four things GTK needs that the DOM does not:

   - **Widgets are made late.** Replicant creates an element and then sets its
     attributes one at a time, but a GTK constructor wants props up front: a
     box needs its spacing, an image its file. So the widget is constructed on
     first use -- when a child goes into it or it goes into its parent -- by
     which time Replicant has set every attribute.
   - **Widgets are owned.** A container frees a widget it removes. A keyed move
     is a remove and an insert, so every widget is ref-sunk at construction and
     unref'd only when Replicant really removes it.
   - **The work is done at the end.** Replicant's operations only record:
     changed props per element, and each child list in a mirror. Once the diff
     is done, each touched widget gets all its changed props at once, each
     touched container is brought in line, and then the lifecycle hooks run --
     so a hook sees widgets that are already in place. The children section
     below says why doing it live does not work.
   - **Text is a prop.** Text children were folded into the widget's text prop
     by `gtkiccup.hiccup/normalize`, so Replicant never makes a text node."
  (:require [gtkiccup.ffi :as g]
            [gtkiccup.hiccup :as h]
            [gtkiccup.widgets :as w]
            [replicant.core :as rc]
            [replicant.protocols :as rp]))

;; ---------------------------------------------------------------------------
;; elements
;; ---------------------------------------------------------------------------

(defn- element [tag]
  (let [tag (keyword tag)]
    (atom {:tag tag :spec (@w/widgets tag) :props {} :classes #{}
           :widget nil :kids [] :attached [] :handlers {}})))

(defn root-element
  "The element for the window itself. Its widget exists already, and `spec`
   says how a child becomes the window's content."
  [spec window]
  (atom {:tag ::root :spec spec :props {} :classes #{}
         :widget window :kids [] :attached [] :handlers {}}))

(defn- widget!
  "The element's widget, constructed on first call from the props Replicant has
   set by now."
  [el]
  (or (:widget @el)
      (let [{:keys [spec props classes handlers]} @el
            wp ((:ctor spec) props)]
        (g/object-ref-sink wp)
        (swap! el assoc :widget wp :changed #{})
        (w/apply-common! wp props (set (keys props)))
        ((:apply spec) wp props #{})                 ; ctor already set the basics
        (doseq [c classes] (g/widget-add-css-class wp c))
        (doseq [[prop holder] handlers] (w/connect! wp prop holder))
        wp)))

(declare owe!)

(defn- prop-changed!
  "Records a changed prop. It is pushed with the element's other changes once
   the diff is done, not now: Replicant sets props one at a time, and a spec's
   :apply reads its siblings -- a carousel's :page must see this render's
   :animate, not the last one's. Before the widget exists there is nothing to
   do: construction reads them all."
  [el k]
  (when (:widget @el)
    (when (empty? (:changed @el)) (owe! :props el))
    (swap! el update :changed (fnil conj #{}) k)))

(defn- apply-props! [el]
  (let [{:keys [widget spec props changed released?]} @el]
    (swap! el assoc :changed #{})
    (when (and widget (seq changed) (not released?))
      (w/apply-common! widget props changed)
      ((:apply spec) widget props changed))))

;; ---------------------------------------------------------------------------
;; children
;; ---------------------------------------------------------------------------
;;
;; Replicant's child operations only edit each element's mirror (:kids). GTK is
;; brought in line once, at the end of the render, container by container.
;;
;; Doing it live does not work. On a tag change Replicant inserts the new child
;; before the old one and then removes the old one -- fine in a DOM, but a GTK
;; frame or window holds one child, so the insert evicts the old child, and
;; removing the old child then empties the container. Syncing at the end sees
;; only the outcome: one child out, one in.
;;
;; :attached is what GTK holds, in order; :kids is what it should hold.

(defonce ^:private pending
  ;; what this render owes GTK, flushed at its end
  (atom {:props [] :dirty [] :fresh [] :releases [] :hooks []}))

(defn- owe! [k x] (swap! pending update k conj x))

(defn- touch! [parent]
  (when-not (:dirty? @parent)
    (swap! parent assoc :dirty? true)
    (owe! :dirty parent)))

(defn- index-of [kids el]
  (loop [i 0]
    (cond (= i (count kids))            -1
          (identical? (kids i) el)      i
          :else                         (recur (inc i)))))

(defn- without [kids el] (filterv #(not (identical? % el)) kids))

(defn- insert!
  "Puts child before ref in the mirror (nil = at the end). A child that is
   already here is a keyed move."
  [parent child ref]
  (let [kids (without (:kids @parent) child)
        i    (let [i (if ref (index-of kids ref) -1)] (if (neg? i) (count kids) i))]
    (swap! parent assoc :kids (into (conj (subvec kids 0 i) child) (subvec kids i)))
    (touch! parent)))

(defn- release!
  "Forgets an element and everything under it: its handlers go quiet now, and
   its references are dropped at the end of the render. Parent first --
   disposing it unparents the children, which our own references keep alive
   until theirs are dropped too."
  [el]
  (let [{:keys [widget handlers kids]} @el]
    (swap! el assoc :released? true)
    (doseq [[_ holder] handlers] (reset! holder nil))
    (when widget (owe! :releases widget))
    (run! release! kids)))

(defn- remove-child! [parent child]
  (swap! parent update :kids without child)
  (touch! parent)
  (release! child))

(defn- gtk-remove! [parent child]
  ((:remove (:spec @parent)) (:widget @parent) (:widget @child) (:props @child)))

(defn- gtk-append! [parent child]
  ((:append (:spec @parent)) (widget! parent) (widget! child) (:props @child)))

(defn- sync-children!
  "Makes the GTK children of `parent` match its mirror."
  [parent]
  (swap! parent assoc :dirty? false)
  (when-not (:released? @parent)
    (let [{:keys [spec kids attached]} @parent
          wanted? (fn [el] (<= 0 (index-of kids el)))
          gone    (remove wanted? attached)
          _       (run! #(gtk-remove! parent %) gone)
          kept    (filterv wanted? attached)]
      (doseq [k kids :when (neg? (index-of attached k))] (owe! :fresh k))
      (if-let [insert-after (:insert-after spec)]
        ;; positional: walk the wanted order, fixing each slot that is wrong
        (loop [i 0 cur kept]
          (when (< i (count kids))
            (let [k (kids i)]
              (if (and (< i (count cur)) (identical? (cur i) k))
                (recur (inc i) cur)
                (let [cur (if (<= 0 (index-of cur k))
                            (do (gtk-remove! parent k) (without cur k))
                            cur)]
                  (insert-after (widget! parent) (widget! k)
                                (when (pos? i) (widget! (kids (dec i))))
                                (:props @k))
                  (recur (inc i) (into (conj (subvec cur 0 i) k) (subvec cur i))))))))
        ;; append-only: keep the longest prefix already in place, redo the rest
        (let [j (loop [j 0]
                  (if (and (< j (count kept)) (< j (count kids))
                           (identical? (kept j) (kids j)))
                    (recur (inc j))
                    j))]
          (run! #(gtk-remove! parent %) (subvec kept j))
          (run! #(gtk-append! parent %) (subvec kids j))))
      (swap! parent assoc :attached kids))))

(defn- settle!
  "Once, when an element first lands in its parent, after every container is
   in sync -- so its own children are in place, which is what
   `:after-children` (a carousel's initial page) needs."
  [el]
  (when-not (or (:settled? @el) (:released? @el))
    (swap! el assoc :settled? true)
    (when-let [f (:after-children (:spec @el))]
      (f (:widget @el) (:props @el)))))

(defn- flush!
  "Pays what the render owes GTK, in the order it has to happen: changed props,
   then structure, then first-landing callbacks, then the references of removed widgets, then
   the lifecycle hooks -- last, so a hook sees the tree as it now is."
  []
  (loop []
    (let [[{:keys [props dirty fresh releases hooks]} _]
          (reset-vals! pending {:props [] :dirty [] :fresh [] :releases [] :hooks []})]
      (when (or (seq props) (seq dirty) (seq fresh) (seq releases) (seq hooks))
        (run! apply-props! props)
        (run! sync-children! dirty)
        (run! settle! fresh)
        (run! g/object-unref releases)
        (run! #(%) hooks)
        ;; a hook may have changed something; a second pass picks it up
        (recur)))))

;; ---------------------------------------------------------------------------
;; the protocol
;; ---------------------------------------------------------------------------

(defn- unbox
  "Prop values travel boxed, so false and nil survive Replicant's HTML rules.
   See gtkiccup.hiccup."
  [v]
  (if (vector? v) (first v) v))

(def renderer
  (with-meta {}
    {`rp/attached? (fn [_ el] (not (:released? @el)))

     `rp/create-text-node
     (fn [_ text]
       (throw (ex-info (str "a text node reached the renderer: " (pr-str text)
                            "\n  text children should have been folded by normalize")
                       {:text text})))

     `rp/create-element (fn [_ tag _opts] (element tag))

     `rp/set-attribute
     (fn [_ el a v _opts]
       (let [k (keyword a)]
         (swap! el assoc-in [:props k] (unbox v))
         (prop-changed! el k)))

     `rp/remove-attribute
     (fn [_ el a]
       (let [k (keyword a)]
         (swap! el update :props dissoc k)
         (prop-changed! el k)))                ; a removed prop falls back to its default

     `rp/add-class
     (fn [_ el cn]
       (swap! el update :classes conj cn)
       (some-> (:widget @el) (g/widget-add-css-class cn)))

     `rp/remove-class
     (fn [_ el cn]
       (swap! el update :classes disj cn)
       (some-> (:widget @el) (g/widget-remove-css-class cn)))

     ;; normalize rejects :style, so these never run
     `rp/set-style    (fn [_ _ _ _] nil)
     `rp/remove-style (fn [_ _ _] nil)

     ;; One holder per widget and signal, connected once. A later render only
     ;; swaps the fn inside it, so a handler never closes over stale state and
     ;; connections never pile up.
     `rp/set-event-handler
     (fn [_ el event f _opts]
       (if-let [holder (get-in @el [:handlers event])]
         (reset! holder f)
         (let [holder (atom f)]
           (swap! el assoc-in [:handlers event] holder)
           (when-let [wp (:widget @el)] (w/connect! wp event holder)))))

     `rp/remove-event-handler
     (fn [_ el event _opts]
       (some-> (get-in @el [:handlers event]) (reset! nil)))

     `rp/append-child  (fn [this el child] (insert! el child nil) this)
     `rp/insert-before (fn [this el child ref] (insert! el child ref) this)
     `rp/remove-child  (fn [this el child] (remove-child! el child) this)

     `rp/replace-child
     (fn [this el new old]
       (swap! el update :kids (fn [ks] (mapv #(if (identical? % old) new %) ks)))
       (touch! el)
       (release! old)
       this)

     `rp/remove-all-children
     (fn [this el]
       (run! #(remove-child! el %) (:kids @el))
       this)

     `rp/get-child (fn [_ el idx] (get (:kids @el) idx))

     ;; no transitions and no frames to wait for: a GTK render is synchronous
     `rp/on-transition-end (fn [_ _ f] (f) nil)
     `rp/next-frame        (fn [_ f] (f))

     `rp/remember (fn [_ el memory] (swap! el assoc :memory memory) nil)
     `rp/recall   (fn [_ el] (:memory @el))}))

;; ---------------------------------------------------------------------------
;; trees
;; ---------------------------------------------------------------------------

(defn- snapshot
  "An element as plain data: {:tag :props :widget :children}. What
   `(:tree @gtkiccup.core/current)` holds, for a REPL or a test to walk.

   Replicant diffs classes on its own, so they never arrive as a prop. They
   are put back under :class -- as a vector, from the :class prop and the tag
   alike -- so finding a node by its class works the way the view reads."
  [el]
  (let [{:keys [tag props classes widget kids]} @el]
    {:tag tag
     :props (cond-> props (seq classes) (assoc :class (vec (sort classes))))
     :widget widget
     :children (mapv snapshot kids)}))

(defn widget
  "The GTK widget behind whatever you have: a lifecycle hook's event map, an
   element, or a node of the tree snapshot."
  [x]
  (cond
    (and (map? x) (:replicant/node x)) (widget (:replicant/node x))
    (map? x)                           (:widget x)
    :else                              (widget! x)))

(defn reconcile
  "Renders the normalized `vnode` into `window`, diffing against `old-tree` --
   what the previous call returned, or nil the first time. Returns the new tree
   snapshot, which carries Replicant's state in its metadata for the next call."
  [root-spec window old-tree vnode]
  (let [{:keys [root vdom unmounts unmount-hooks]} (meta old-tree)
        root (or root (root-element root-spec window))
        res  (binding [h/*defer-hook* #(owe! :hooks %)]
               (try
                 (rc/reconcile renderer root (some-> vnode h/->replicant) vdom
                               {:unmounts unmounts :unmount-hooks unmount-hooks})
                 (finally (flush!))))]
    (with-meta (or (some-> (first (:kids @root)) snapshot) {})
      {:root root :vdom (:vdom res)
       :unmounts (:unmounts res) :unmount-hooks (:unmount-hooks res)})))

;; Component memoization: `memoized` view fns are expanded once per `=` args
;; across renders (and their ->replicant form is reused), hook-bearing
;; expansions are never cached, and data handlers are identical across renders.
;; Pure hiccup -- no window, no gtk-init.
(require '[gtkiccup.core :as ui]
         '[gtkiccup.hiccup :as h])

(def cases (atom 0))
(defn ok [cond_ msg]
  (swap! cases inc)
  (assert cond_ msg))

;; ---------------------------------------------------------------------------
;; 1) a memoized component is called once per = args, re-called when they change

(def calls (atom 0))
(defn view [x]
  (swap! calls inc)
  [:label {:label (str "v" x)}])
(def mview (h/memoized view))

(h/clear-memo!)
(reset! calls 0)
(def n1 (h/normalize [mview 1]))
(def n2 (h/normalize [mview 1]))
(ok (= 1 @calls) (str "memoized fn called " @calls "x for two = renders, want 1"))
(ok (identical? n1 n2) "a cache hit returns the same node")
(def n3 (h/normalize [mview 2]))
(ok (= 2 @calls) (str "changed args re-call the fn, calls=" @calls))
(ok (= :label (:tag n3)) "the re-call still normalizes")

;; ---------------------------------------------------------------------------
;; 2) an unmarked fn component keeps its per-render call (default behavior)

(def plain-calls (atom 0))
(defn plain [x] (swap! plain-calls inc) [:label {:label (str x)}])
(reset! plain-calls 0)
(h/normalize [plain 1])
(h/normalize [plain 1])
(ok (= 2 @plain-calls) "unmarked fns are not memoized")

;; ---------------------------------------------------------------------------
;; 3) equality on args is deep: maps/vectors as args hit too

(def deep-calls (atom 0))
(def deep (h/memoized (fn [m] (swap! deep-calls inc) [:label {:label (:t m)}])))
(h/clear-memo!)
(reset! deep-calls 0)
(h/normalize [deep {:t "a" :xs [1 2 3]}])
(h/normalize [deep {:t "a" :xs [1 2 3]}])
(ok (= 1 @deep-calls) "structurally = args are cache hits")
(h/normalize [deep {:t "a" :xs [1 2 4]}])
(ok (= 2 @deep-calls) "one changed value re-calls")

;; ---------------------------------------------------------------------------
;; 4) nested memoized components: inner rows cached while the page rebuilds

(def row-calls (atom 0))
(def row (h/memoized (fn [i] (swap! row-calls inc) [:label {:label (str "r" i)}])))
(def page-calls (atom 0))
(def page (fn [title]
            (swap! page-calls inc)
            `[:vbox {} ~@(mapv (fn [i] [row i]) (range 3))]))
(h/clear-memo!)
(reset! row-calls 0)
(reset! page-calls 0)
(h/normalize [page "t"])
(h/normalize [page "t"])
(ok (= 2 @page-calls) "the unmarked page re-runs each render")
(ok (= 3 @row-calls) (str "memoized rows expanded once each, calls=" @row-calls))

;; ---------------------------------------------------------------------------
;; 5) hook-bearing expansions are never cached (hook closes over *defer-hook*)

(def hook-calls (atom 0))
(def hooked (h/memoized (fn [x]
                          (swap! hook-calls inc)
                          [:label {:label (str x)
                                   :replicant/on-mount (fn [_] nil)}])))
(h/clear-memo!)
(reset! hook-calls 0)
(h/normalize [hooked 1])
(h/normalize [hooked 1])
(ok (= 2 @hook-calls) (str "a hook-bearing memoized fn is re-called, calls=" @hook-calls))
(ok (nil? (:replicant/cached (h/normalize [hooked 1])))
    "hook-bearing nodes carry no cached replicant form")

;; a hook DEEP in the subtree must also defeat caching
(def hooked-deep (h/memoized (fn [x]
                               (swap! hook-calls inc)
                               [:vbox {} [:label {:label (str x)}]
                                [:label {:replicant/on-update (fn [_] nil)}]])))
(h/clear-memo!)
(reset! hook-calls 0)
(h/normalize [hooked-deep 1])
(h/normalize [hooked-deep 1])
(ok (= 2 @hook-calls) "hooks anywhere in the subtree defeat the cache")

;; ---------------------------------------------------------------------------
;; 6) ->replicant reuses the prebuilt form (:replicant/cached)

(h/clear-memo!)
(def nn (h/normalize [mview 7]))
(ok (some? (:replicant/cached nn)) "cached node carries its replicant form")
(ok (identical? (:replicant/cached nn) (h/->replicant nn))
    "->replicant returns the cached form as-is")
(ok (= [:label {:label ["v7"]}] (h/->replicant nn)) "the cached form is correct")

;; ---------------------------------------------------------------------------
;; 7) data handlers are identical across renders and still dispatch

(def seen (atom []))
(ui/set-dispatch! (fn [e d] (swap! seen conj [(:gtkiccup/event e) d])))
(h/clear-memo!)
(def b1 (h/->replicant (h/normalize [:button {:on-click [:do 1]}])))
(def b2 (h/->replicant (h/normalize [:button {:on-click [:do 1]}])))
(def f1 (get-in (second b1) [:on :on-click]))
(def f2 (get-in (second b2) [:on :on-click]))
(ok (fn? f1) "a data handler is a fn in the output")
(ok (identical? f1 f2) "the same [prop data] yields the identical fn across renders")
(f1 "evt-arg")
(ok (= [[:on-click [:do 1]]] @seen) (str "it still dispatches: " @seen))
(def b3 (h/->replicant (h/normalize [:button {:on-click [:do 2]}])))
(ok (not (identical? f1 (get-in (second b3) [:on :on-click]))) "different data => different fn")

;; an fn handler still passes through untouched
(def my-handler (fn [_] nil))
(def b4 (h/->replicant (h/normalize [:button {:on-click my-handler}])))
(ok (identical? my-handler (get-in (second b4) [:on :on-click])) "fn handlers pass through raw")
(ui/set-dispatch! nil)

;; ---------------------------------------------------------------------------
;; 8) eviction: past the cap the cache is cleared, results stay correct

(h/clear-memo!)
(reset! calls 0)
(doseq [i (range 4001)] (h/normalize [mview [:k i]])) ; fresh key each time
(def after-evict (h/normalize [mview 1]))
(ok (= :label (:tag after-evict)) "post-eviction normalize still produces a node")
(ok (some? (:replicant/cached after-evict)) "post-eviction entries are cached again")

(println (str "MEMO-OK cases=" @cases))
(println "ALL OK")

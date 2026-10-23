;; Late-bound alias rendering: components emitted as DATA nodes (namespaced
;; keyword + plain-data attrs) that Replicant expands lazily -- the registered
;; fn runs once per distinct attrs, not once per render. Covers the goal-07
;; memoization failure modes: silent no-op is impossible (there is only one way
;; to emit a component), the cache is observable (alias-stats counters), and
;; hooks fire exactly once because a cached-in-vdom alias subtree keeps its
;; mounted widget. Drives reconcile directly against a window, like
;; reconcile_test -- no main loop.
(require '[gtkiccup.ffi :as g]
         '[gtkiccup.core :as ui]
         '[gtkiccup.hiccup :as h]
         '[gtkiccup.alias :as ga])

(g/gtk-init)
(def win (g/window-new))
(def root-spec (@#'ui/root-spec ui/default-window))
(def reconcile @#'ui/reconcile)
(defn norm [x] (h/normalize x))

(def cases (atom 0))
(defn ok [cond_ msg]
  (swap! cases inc)
  (assert cond_ msg))

;; ---------------------------------------------------------------------------
;; 1) an alias expands ONCE across two renders of equal attrs; only the changed
;;    rows re-expand when one attr moves (the goal-07 render_memo_test idiom).

(ga/clear-aliases!)
(def row-calls (atom {}))
(ga/register-alias! :t/row
  (fn [attrs _children]
    (swap! row-calls update (:k attrs) (fnil inc 0))
    [:label {:label (str "row " (:n attrs))}]))

(defn page [] [:vbox {} [:t/row {:k :a :n 1}] [:t/row {:k :b :n 2}]])
(def t1 (reconcile root-spec win nil (norm (page))))
(ok (= {:a 1, :b 1} @row-calls) (str "first render expands each row once, " @row-calls))
(def t2 (reconcile root-spec win t1 (norm (page))))
(ok (= {:a 1, :b 1} @row-calls) (str "equal attrs re-expand nothing, " @row-calls))

(defn page2 [] [:vbox {} [:t/row {:k :a :n 9}] [:t/row {:k :b :n 2}]])
(def t3 (reconcile root-spec win t2 (norm (page2))))
(ok (= {:a 2, :b 1} @row-calls)
    (str "changing :a re-expands only :a, " @row-calls))

;; alias-stats reports the expansions (the mechanism is observable now).
(ok (= 3 (get-in (ga/alias-stats) [:t/row :expansions]))
    (str "alias-stats counts expansions: " (pr-str (ga/alias-stats))))
(ga/reset-stats!)
(ok (= 0 (get-in (ga/alias-stats) [:t/row :expansions])) "reset-stats! zeroes counters")

;; ---------------------------------------------------------------------------
;; 2) nested aliases stay lazy: an outer alias whose body emits an inner alias.

(ga/clear-aliases!)
(def card-calls (atom 0))
(def badge-calls (atom 0))
(ga/register-alias! :t/card
  (fn [attrs _] (swap! card-calls inc) [:vbox {} [:t/badge {:id (:id attrs)}]]))
(ga/register-alias! :t/badge
  (fn [attrs _] (swap! badge-calls inc) [:label {:label (str "b" (:id attrs))}]))

(def t4 (reconcile root-spec win nil (norm [:t/card {:id 7}])))
(ok (= 1 @card-calls) "outer alias expanded once")
(ok (= 1 @badge-calls) "inner alias expanded once on the cold pass")
(def t5 (reconcile root-spec win t4 (norm [:t/card {:id 7}])))
(ok (= 1 @card-calls) (str "equal outer attrs do NOT re-expand the outer, " @card-calls))
(ok (= 1 @badge-calls) (str "nor walk into the inner subtree, " @badge-calls))
(def t6 (reconcile root-spec win t5 (norm [:t/card {:id 8}])))
(ok (= 2 @card-calls) "a changed outer attr re-expands the outer")
(ok (= 2 @badge-calls) "which re-expands the inner with the new id")

;; ---------------------------------------------------------------------------
;; 3) hooks fire exactly once and are NOT re-run on a cache hit (a cached-in-vdom
;;    alias subtree is a mounted widget whose hooks already ran).

(ga/clear-aliases!)
(def hook-runs (atom 0))
(ga/register-alias! :t/hooked
  (fn [attrs _]
    [:label {:label (str "h" (:n attrs))
             :replicant/on-mount (fn [_] (swap! hook-runs inc))}]))
(def th1 (reconcile root-spec win nil (norm [:t/hooked {:n 1}])))
(ok (= 1 @hook-runs) (str "a hook in an alias body ran once, " @hook-runs))
(def th2 (reconcile root-spec win th1 (norm [:t/hooked {:n 1}])))
(ok (= 1 @hook-runs) (str "equal attrs do NOT re-fire the hook, " @hook-runs))

;; ---------------------------------------------------------------------------
;; 4) an unregistered qualified keyword is an emit-time error (typo protection),
;;    not a soft error div.

(ga/clear-aliases!)
(ga/register-alias! :t/known (fn [_ _] [:label {:label "x"}]))
(def unreg (try (norm [:t/typo {:a 1}]) (catch Throwable _ :errored)))
(ok (= :errored unreg) "an unregistered qualified keyword errors at normalize")
(def known (norm [:t/known {:a 1}]))
(ok (= :t/known (:tag known)) "a registered alias passes through as a data node")
(ok (= {:a 1} (:props known)) "the alias node keeps its attrs as plain data")
(ok (= [] (:children known)) "children default to empty")

;; ---------------------------------------------------------------------------
;; 5) :on-alias-exception logs when an expansion throws, and the frame survives.

(ga/clear-aliases!)
(ga/register-alias! :t/boom (fn [_ _] (throw (ex-info "kaboom" {:boom true}))))
(def logged (java.io.StringWriter.))
(def crash (atom nil))
(binding [*err* logged]
  (try (reconcile root-spec win nil (norm [:vbox {} [:t/boom {}]]))
       (catch Throwable e (reset! crash e))))
(def err-str (str logged))
(ok (nil? @crash) (str "a throwing alias does not crash the render: " (some-> @crash ex-message)))
(ok (re-find #"alias .*:t/boom.* threw" err-str)
    (str ":on-alias-exception logged the throw: " (pr-str err-str)))

;; ---------------------------------------------------------------------------
;; 6) :replicant/alias-data reaches the expansion's attrs (near-static side-chain).

(ga/clear-aliases!)
(def seen-data (atom nil))
(ga/register-alias! :t/dataed
  (fn [attrs _] (reset! seen-data (:replicant/alias-data attrs)) [:label {:label "d"}]))
(reconcile root-spec win nil (norm [:t/dataed {}]) {:alias-data {:base-url "https://x"}})
(ok (= {:base-url "https://x"} @seen-data)
    (str "alias-data merged into expansion attrs: " (pr-str @seen-data)))

;; ---------------------------------------------------------------------------
;; 7) a data handler in an attr survives to the expansion as DATA (a vector),
;;    not a closure -- the whole reason attrs can be the cache key.

(ga/clear-aliases!)
(def seen-handler (atom :unset))
(ga/register-alias! :t/hdata
  (fn [attrs _] (reset! seen-handler (:on-click attrs)) [:label {:label "hd"}]))
(reconcile root-spec win nil (norm [:t/hdata {:on-click [:bt/play {:id 7}]}]))
(ok (= [:bt/play {:id 7}] @seen-handler)
    (str "handler arrives as data: " (pr-str @seen-handler)))

(println (str "ALIAS-OK cases=" @cases))
(println "ALL OK")

;; LRU byte budget on the memo caches (goal 09): with a tiny budget the caches
;; stay bounded while DISTINCT rows churn, the HOT set (re-accessed every
;; render -- the page on screen) is never evicted, cold rows are (so the cache
;; really evicts rather than silently growing), and the data-handler cache is
;; bounded the same way. Pure hiccup -- no window, no gtk-init.
(require '[gtkiccup.core :as ui]
         '[gtkiccup.hiccup :as h])

(def cases (atom 0))
(defn ok [cond_ msg]
  (swap! cases inc)
  (assert cond_ msg))

(def budget 200000) ;; bytes; a row costs ~2300 est (node+replicant+args), so
                    ;; the hot set (~12k) always fits, but the 600-row churn
                    ;; (~1.4MB) is ~7x the budget -- old pages are what pays

(defn big-row [i]
  ;; a realistic-ish row: strings long enough that est() sees hundreds of bytes
  [:label {:label (str "row " i " " (apply str (repeat 40 (str "filler" i "-"))))}])

(def row-calls (atom {}))
(def mrow (h/memoized
           (fn [i]
             (swap! row-calls update i (fnil inc 0))
             (big-row i))))

(defn stats [] (h/memo-stats))

(defn -run []
  (alter-var-root #'h/memo-budget-bytes (constantly budget))
  (h/clear-memo!)
  (try
    ;; ------------------------------------------------------------------
    ;; 1) distinct-row churn: the whole point of goal 09. babatidal measured
    ;; ~40KB retained per DISTINCT row and an unbounded climb; here the same
    ;; churn must leave the cache bounded by the budget.
    (let [hot    (range 5)          ; the "page on screen": re-accessed every round
          rounds 30
          fresh  20]                ; fresh distinct rows per round => 600 total
      (doseq [r (range rounds)]
        ;; hot set re-rendered every round (this is the access bump protecting it)
        (doseq [i hot] (h/normalize [mrow i]))
        ;; churn: rows never seen again after this round
        (doseq [i (range (* r fresh) (* (inc r) fresh))]
          (h/normalize [mrow i])))
      (let [s (stats)]
        (ok (pos? (:memo-entries s)) "the cache is not empty after churn")
        (ok (<= (:memo-cost s) budget)
            (str "memo cost " (:memo-cost s) " stayed within budget " budget))
        (ok (< (:memo-entries s) (* rounds fresh))
            (str "entries " (:memo-entries s) " stayed far below the "
                 (* rounds fresh) " distinct rows churned"))
        (ok (= (:budget-bytes s) budget) "memo-stats reports the active budget")

        ;; (b) HOT rows were expanded exactly once across all rounds
        (doseq [i hot]
          (ok (= 1 (get @row-calls i))
              (str "hot row " i " expanded " (get @row-calls i) "x, want 1")))

        ;; (c) COLD rows DID get evicted: a row from round 5, untouched since
        ;; the budget cycled ~24 rounds past it, must re-expand on demand.
        ;; (NOT a row from 0-4 -- those are the hot set and must survive.)
        (let [cold        100
              before      (get @row-calls cold)
              _           (h/normalize [mrow cold])
              after       (get @row-calls cold)]
          (ok (= (inc before) after)
              (str "cold row was NOT evicted (calls " before "->" after ")")))))

    ;; ------------------------------------------------------------------
    ;; 2) correctness survives eviction: nodes and cached replicants still come back
    (h/clear-memo!)
    (let [n1 (h/normalize [mrow 1])
          n2 (h/normalize [mrow 1])]
      (ok (= :label (:tag n1)) "normalize still produces a node")
      (ok (identical? n1 n2) "a cache hit still returns the same node")
      (ok (= [:label {:label [(str (:label (second (big-row 1))))]}]
             (h/->replicant n1))
          "the cached replicant form is still correct"))

    ;; ------------------------------------------------------------------
    ;; 3) data-handler cache is bounded the same way (its [prop data] keys
    ;; retain `data` -- usually a row map -- so it held per-row mass too)
    (h/clear-memo!)
    (ui/set-dispatch! (fn [_ _] nil))
    (let [hot-data   [{:row :hot-a} {:row :hot-b}]
          handler-of (fn [d] (-> (h/->replicant (h/normalize [:button {:on-click d}]))
                                 second (get-in [:on :on-click])))
          handlers-a (mapv handler-of (repeat 2 (first hot-data)))]
      (ok (every? fn? handlers-a) "data handlers are fns")
      (ok (apply identical? handlers-a) "the hot [prop data] handler stays identical")
      (doseq [r (range 40)
              i (range 20)]
        ;; ->replicant, not just normalize: data handlers are created in
        ;; ->attrs, so normalize alone would leave the cache empty and the
        ;; asserts below would pass vacuously
        (-> (h/normalize [:button {:on-click {:row i :round r :filler (apply str (repeat 30 "x"))}}])
            (h/->replicant)))
      (let [s (stats)]
        (ok (<= (:handler-cost s) budget)
            (str "handler cost " (:handler-cost s) " stayed within budget"))
        (ok (< (:handler-entries s) 800)
            (str "handler entries " (:handler-entries s) " stayed bounded")))
      (ui/set-dispatch! nil))

    (println (str "MEMOBUDGET-OK cases=" @cases))
    (println "ALL OK")
    (finally
      (alter-var-root #'h/memo-budget-bytes (constantly (* 16 1024 1024))))))

(-run)

;; LRU byte budget on the data-handler cache (goal 09, repointed at goal 11):
;; with a tiny budget the cache stays bounded while DISTINCT [prop data] rows
;; churn, the HOT set (re-accessed every render -- the page on screen) keeps
;; returning the IDENTICAL handler fn (never evicted), a cold handler is evicted
;; (so re-creating it yields a fresh fn, proving real eviction, not silent
;; growth). Pure hiccup -- no window, no gtk-init.
;;
;; Goal 11 removed the h/memoized component cache; the shared LRU/est/budget
;; machinery now backs exactly one cache (data-handler-cache), which is what the
;; churn below exercises.
(require '[gtkiccup.core :as ui]
         '[gtkiccup.hiccup :as h])

(def cases (atom 0))
(defn ok [cond_ msg]
  (swap! cases inc)
  (assert cond_ msg))

(def budget 200000) ;; bytes; a [prop data] entry costs ~ hundreds of est bytes,
                    ;; so the hot set fits, but the 600-row churn is many times
                    ;; the budget -- old pages are what pays

(defn big-data [i]
  ;; a realistic-ish handler payload: a row map with enough string mass that
  ;; est() sees hundreds of bytes retained by the [prop data] key + value
  {:row i :filler (apply str (repeat 40 (str "filler" i "-")))})

(defn handler-of [d]
  ;; ->replicant (not just normalize): data handlers are created in ->attrs, so
  ;; normalize alone would leave the cache empty and the asserts would be vacuous
  (-> (h/normalize [:button {:on-click d}])
      h/->replicant
      second
      (get-in [:on :on-click])))

(defn stats [] (h/memo-stats))

(defn -run []
  (alter-var-root #'h/memo-budget-bytes (constantly budget))
  (h/clear-memo!)
  (ui/set-dispatch! (fn [_ _] nil))
  (try
    ;; 1) distinct-row churn: the hot [prop data] handlers stay identical, the
    ;;    cache stays bounded, and cold handlers really evict.
    (let [hot    (mapv big-data (range 5))   ; re-accessed every round
          rounds 30
          fresh  20]                         ; fresh distinct rows per round
      ;; capture the hot handler identity BEFORE churn
      (doseq [d hot] (handler-of d))
      (let [hot-before (mapv handler-of hot)]
        (doseq [r (range rounds)]
          (doseq [d hot] (handler-of d))                     ; protect the hot set
          (doseq [i (range (* r fresh) (* (inc r) fresh))]   ; cold churn
            (handler-of (big-data i))))
        (let [s          (stats)
              hot-after  (mapv handler-of hot)]
          (ok (pos? (:handler-entries s)) "the cache is not empty after churn")
          (ok (<= (:handler-cost s) budget)
              (str "handler cost " (:handler-cost s) " stayed within budget " budget))
          (ok (< (:handler-entries s) (* rounds fresh))
              (str "entries " (:handler-entries s) " stayed far below the "
                   (* rounds fresh) " distinct rows churned"))
          (ok (= (:budget-bytes s) budget) "memo-stats reports the active budget")
          ;; (b) hot handlers stayed identical across the churn
          (ok (every? true? (map identical? hot-before hot-after))
              "a hot [prop data] handler was evicted (not identical after churn)"))))

    ;; 2) correctness survives eviction: a handler is still a working fn
    (h/clear-memo!)
    (let [h1 (handler-of {:row 1})
          h2 (handler-of {:row 1})]
      (ok (fn? h1) "a handler is a fn")
      (ok (identical? h1 h2) "the same [prop data] returns the identical fn"))

    (println (str "MEMOBUDGET-OK cases=" @cases))
    (println "ALL OK")
    (finally
      (ui/set-dispatch! nil)
      (alter-var-root #'h/memo-budget-bytes (constantly (* 16 1024 1024))))))

(-run)

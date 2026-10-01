;; The data layer is pure, so it tests without a window.
(require '[sysinfo :as s])

;; --- 1. every reading comes back sane -----------------------------------
(def m (s/meminfo))
(println "1) meminfo:" (s/human-bytes (:total m)) "total,"
         (s/human-bytes (:available m)) "available")
(assert (pos? (:total m)) "no MemTotal -- did slurp-vs-read-all-lines regress?")
(assert (<= 0 (:available m) (:total m)))

(def c (s/cpu-times))
(println "   cpu-times:" c)
(assert (pos? (:total c)))
(assert (<= 0 (:idle c) (:total c)))

(println "   loadavg:" (s/loadavg) "| cores:" (s/core-count)
         "| uptime:" (s/human-duration (s/uptime-seconds)))
(assert (= 3 (count (s/loadavg))))
(assert (every? #(>= % 0) (s/loadavg)))
(assert (pos? (s/core-count)))
(assert (pos? (s/uptime-seconds)))
(assert (string? (s/hostname)))

(def st (s/storage))
(println "   storage:" (s/human-bytes (:total st)) "total")
(assert (pos? (:total st)))
(assert (<= 0 (:free st) (:total st)))

;; --- 2. processes are sorted, bounded, and populated --------------------
(def ps (s/processes 8))
(println "2) top" (count ps) "processes, biggest:" (:name (first ps))
         (s/human-bytes (:rss (first ps))))
(assert (<= 1 (count ps) 8))
(assert (= (map :rss ps) (reverse (sort (map :rss ps)))) "not sorted by rss")
(assert (every? #(and (string? (:name %)) (re-matches #"\d+" (:pid %)) (pos? (:rss %))) ps))

;; --- 3. cpu-fraction needs two samples ---------------------------------
(def a (s/sample 3))
(println "3) cpu-fraction with no previous sample:" (s/cpu-fraction nil a))
(assert (nil? (s/cpu-fraction nil a)) "should be nil without a previous sample")
(Thread/sleep 250)
(def b (s/sample 3))
(def f (s/cpu-fraction a b))
(println "   with two samples:" (s/pct f))
(assert (<= 0.0 f 1.0) "cpu fraction out of range")

;; --- 4. formatting ------------------------------------------------------
(println "4) human-bytes:" (mapv s/human-bytes [0 999 1024 1048576 1073741824 1.5e12]))
(assert (= "0 B" (s/human-bytes 0)))
(assert (= "1 kB" (s/human-bytes 1024)))
(assert (= "1 MB" (s/human-bytes 1048576)))
(assert (= "1.0 GB" (s/human-bytes 1073741824)))
(println "   human-duration:" (mapv s/human-duration [30 90 3700 90000]))
(assert (= "0m" (s/human-duration 30)))
(assert (= "1m" (s/human-duration 90)))
(assert (= "1h 1m" (s/human-duration 3700)))
(assert (= "1d 1h" (s/human-duration 90000)))
(assert (= "50%" (s/pct 0.5)))

;; --- 5. the view model has everything the UI reads ---------------------
(def vm (s/view-model a b))
(println "5) view-model keys:" (sort (keys vm)))
(assert (= [:at :cores :cpu :disk :host :load :mem :procs :self :swap :uptime]
           (sort (keys (assoc vm :at (:at b))))))
(doseq [k [:cpu :mem :swap :load :disk]]
  (let [{:keys [value label]} (get vm k)]
    (println "  " k "->" (format "%.3f" (double value)) (pr-str label))
    (assert (<= 0.0 (double value) 1.0) (str k " value out of 0..1"))
    (assert (string? label) (str k " has no label"))))
(assert (every? #(<= 0.0 (:value %) 1.0) (:procs vm)))
(assert (= 1.0 (:value (first (:procs vm)))) "biggest process should be a full bar")

;; --- 5b. this process's own footprint, the point of the demo -------------
(println "5b) self:" (:self vm))
(assert (re-matches #"\d+" (:pid (:self vm))) "no own pid")
(assert (pos? (s/self-rss)) "no own rss")
(assert (<= 0.0 (:value (:self vm)) 1.0))
(assert (re-find #"of one core" (:label (:self vm))) "no own cpu figure")
(assert (nil? (s/self-cpu-fraction nil b)) "should be nil without a previous sample")
(let [f (s/self-cpu-fraction a b)]
  (println "    own cpu fraction of one core:" (format "%.3f" f))
  (assert (<= 0.0 f 4.0) "own cpu wildly out of range"))
;; we should be far smaller than the biggest desktop app on the machine
(println "    self rss vs biggest process:"
         (s/human-bytes (s/self-rss)) "vs" (:label (first (:procs vm))))
(assert (< (s/self-rss) (:rss (first (:procs vm)))) "we are the biggest process?")

;; a machine with no swap must not divide by zero
(def no-swap (assoc-in b [:mem :swap-total] 0))
(def vm2 (s/view-model a (assoc-in no-swap [:mem :swap-free] 0)))
(println "6) with no swap configured:" (:swap vm2))
(assert (= 0 (:value (:swap vm2))))
(assert (= "none configured" (:label (:swap vm2))))
(println "ALL OK")

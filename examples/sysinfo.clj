(ns sysinfo
  "Reads /proc. Pure data, no UI, no state -- so it can be tested on its own.

   Note: `slurp` fails on /proc files under babashka (\"Invalid argument\"),
   because they report a size of zero. `babashka.fs/read-all-lines` works."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn- lines [path]
  (try (fs/read-all-lines path) (catch Exception _ nil)))

(defn- nums [s]
  (->> (str/split (str/trim s) #"\s+") (keep parse-long)))

;; ---------------------------------------------------------------------------
;; individual readings
;; ---------------------------------------------------------------------------

(defn cpu-times
  "Cumulative jiffies from /proc/stat: {:idle n :total n}. Meaningless alone --
   CPU load is the delta between two of these."
  []
  (when-let [l (first (lines "/proc/stat"))]
    (let [[user nice sys idle iowait irq softirq] (nums (subs l 4))]
      {:idle  (+ (or idle 0) (or iowait 0))
       :total (reduce + 0 (keep identity [user nice sys idle iowait irq softirq]))})))

(defn meminfo
  "Bytes: {:total :available :swap-total :swap-free}."
  []
  (let [kv (into {} (for [l (lines "/proc/meminfo")
                          :let [[k v] (str/split l #":\s+")]
                          :when v]
                      [k (* 1024 (or (first (nums v)) 0))]))]
    {:total      (get kv "MemTotal" 0)
     :available  (get kv "MemAvailable" 0)
     :swap-total (get kv "SwapTotal" 0)
     :swap-free  (get kv "SwapFree" 0)}))

(defn loadavg []
  (when-let [l (first (lines "/proc/loadavg"))]
    (mapv parse-double (take 3 (str/split l #"\s+")))))

(defn uptime-seconds []
  (when-let [l (first (lines "/proc/uptime"))]
    (long (parse-double (first (str/split l #"\s+"))))))

;; Core count and hostname never change while we run, and /proc/cpuinfo is the
;; biggest file we touch, so read each once.
(def core-count
  (memoize (fn [] (count (filter #(str/starts-with? % "processor")
                                 (lines "/proc/cpuinfo"))))))

(def hostname
  (memoize (fn [] (or (first (lines "/proc/sys/kernel/hostname")) "localhost"))))

(defn storage
  "Root filesystem: {:total :free} bytes."
  []
  (let [f (java.io.File. "/")]
    {:total (.getTotalSpace f) :free (.getUsableSpace f)}))

(def ^:private clock-ticks
  "USER_HZ. 100 on every Linux worth worrying about; /proc/*/stat counts CPU
   time in these."
  100.0)

(defn self-pid [] (str (.pid (java.lang.ProcessHandle/current))))

(defn self-cpu-ticks
  "utime + stime for this process. The comm field is parenthesised and can
   contain spaces, so fields are counted from the last ')'."
  []
  (when-let [l (first (lines "/proc/self/stat"))]
    (let [f (str/split (str/trim (subs l (inc (str/last-index-of l ")")))) #"\s+")]
      (+ (parse-long (nth f 11)) (parse-long (nth f 12))))))

(defn self-rss []
  (when-let [st (first (lines "/proc/self/statm"))]
    (* 4096 (second (nums st)))))

(defn- pids []
  (->> (fs/list-dir "/proc")
       (map (comp str fs/file-name))
       (filter #(re-matches #"\d+" %))))

(defn processes
  "The n biggest processes by resident memory.

   statm is read for every pid, comm only for the winners. Names are what makes
   the sweep expensive, and all but n of them get thrown away -- doing it this
   way roughly halves the cost."
  [n]
  (->> (for [pid (pids)
             :let [statm (first (lines (str "/proc/" pid "/statm")))
                   rss   (when statm (second (nums statm)))]
             :when (and rss (pos? rss))]
         {:pid pid :rss (* 4096 rss)})
       (sort-by :rss #(compare %2 %1))
       (take n)
       (mapv (fn [p]
               (assoc p :name (or (first (lines (str "/proc/" (:pid p) "/comm")))
                                  "?"))))))

;; ---------------------------------------------------------------------------
;; one reading of everything
;; ---------------------------------------------------------------------------

(defn sample
  "Everything at one instant. Cheap enough to call once a second on a worker
   thread; nothing here touches GTK."
  ([] (sample 8))
  ([n-procs]
   {:at      (System/nanoTime)
    :self    {:pid (self-pid) :rss (self-rss) :ticks (self-cpu-ticks)}
    :cpu     (cpu-times)
    :mem     (meminfo)
    :load    (loadavg)
    :uptime  (uptime-seconds)
    :cores   (core-count)
    :host    (hostname)
    :storage (storage)
    :procs   (processes n-procs)}))

(defn self-cpu-fraction
  "This process's CPU use between two samples, as a fraction of ONE core.
   nil without a previous sample."
  [prev cur]
  (when (and prev cur)
    (let [dticks   (- (:ticks (:self cur)) (:ticks (:self prev)))
          dseconds (/ (- (:at cur) (:at prev)) 1e9)]
      (when (pos? dseconds)
        (max 0.0 (/ (/ dticks clock-ticks) dseconds))))))

(defn cpu-fraction
  "Busy fraction 0..1 between two samples. nil when there is no previous one."
  [prev cur]
  (when (and prev cur)
    (let [dt (- (:total (:cpu cur)) (:total (:cpu prev)))
          di (- (:idle (:cpu cur)) (:idle (:cpu prev)))]
      (when (pos? dt)
        (max 0.0 (min 1.0 (double (/ (- dt di) dt))))))))

;; ---------------------------------------------------------------------------
;; formatting
;; ---------------------------------------------------------------------------

(defn human-bytes [n]
  (let [n (double (or n 0))]
    (cond
      (< n 1024)       (format "%.0f B" n)
      (< n 1048576)    (format "%.0f kB" (/ n 1024))
      (< n 1073741824) (format "%.0f MB" (/ n 1048576))
      (< n 1.0995116E12) (format "%.1f GB" (/ n 1073741824))
      :else            (format "%.2f TB" (/ n 1.0995116E12)))))

(defn human-duration [secs]
  (let [s (or secs 0)
        d (quot s 86400) h (quot (mod s 86400) 3600) m (quot (mod s 3600) 60)]
    (cond
      (pos? d) (format "%dd %dh" d h)
      (pos? h) (format "%dh %dm" h m)
      :else    (format "%dm" m))))

(defn pct [x] (format "%.0f%%" (* 100.0 (or x 0))))

;; ---------------------------------------------------------------------------
;; the shape the UI actually renders
;; ---------------------------------------------------------------------------

(defn view-model
  "Turns a sample (plus the one before it, for CPU) into exactly what the UI
   shows. Keeping this pure is what makes the UI a plain function of data."
  [prev cur]
  (let [{:keys [mem load uptime cores host storage procs]} cur
        used      (- (:total mem) (:available mem))
        swap-used (- (:swap-total mem) (:swap-free mem))
        disk-used (- (:total storage) (:free storage))]
    {:host    host
     :uptime  (human-duration uptime)
     :cores   cores
     :cpu     {:value (cpu-fraction prev cur)
               :label (if-let [f (cpu-fraction prev cur)]
                        (str (pct f) " of " cores " cores")
                        "sampling...")}
     :mem     {:value (if (pos? (:total mem)) (/ (double used) (:total mem)) 0)
               :label (str (human-bytes used) " of " (human-bytes (:total mem)))}
     :swap    {:value (if (pos? (:swap-total mem))
                        (/ (double swap-used) (:swap-total mem)) 0)
               :label (if (pos? (:swap-total mem))
                        (str (human-bytes swap-used) " of "
                             (human-bytes (:swap-total mem)))
                        "none configured")}
     :load    {:value (if (pos? cores) (min 1.0 (/ (first load) cores)) 0)
               :label (str/join "  " (map #(format "%.2f" %) load))}
     :disk    {:value (if (pos? (:total storage))
                        (/ (double disk-used) (:total storage)) 0)
               :label (str (human-bytes disk-used) " of "
                           (human-bytes (:total storage)) " used")}
     :procs   (let [biggest (double (max 1 (:rss (first procs) 1)))]
                (mapv (fn [p] (assoc p
                                     :label (human-bytes (:rss p))
                                     :value (/ (:rss p) biggest)))
                      procs))
     ;; This app, on the same bar scale as the process list above, so the
     ;; comparison is honest: a whole reactive GUI next to the big desktop apps.
     :self    (let [biggest (double (max 1 (:rss (first procs) 1)))
                    rss     (:rss (:self cur) 0)
                    cpu     (self-cpu-fraction prev cur)]
                {:pid   (:pid (:self cur))
                 :value (min 1.0 (/ rss biggest))
                 :label (str (human-bytes rss)
                             "  ·  "
                             (if cpu (format "%.1f%% of one core" (* 100 cpu))
                                 "sampling..."))})}))

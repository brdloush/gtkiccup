#!/usr/bin/env bb
;; examples-docs-check.clj -- goal check for "examples + docs use the
;; deref-once-at-the-page / pass-sub-states-down approach, and every verbatim
;; doc citation matches the real .clj file it cites."
;;
;; Exit 0 only when EVERYTHING below passes; otherwise prints one
;; "FAIL: <what>" line per failure plus progress counts. Runs headless (no
;; display): loading the examples resolves the FFI tables but never gtk_init,
;; and clj-kondo is static. The windowed suite (`bb test`) is deliberately NOT run
;; here (flaky + out of scope); it runs once at claim time as a proof command.
;;
;; Citation convention (the machine-checked part):
;;   ```clojure file=examples/deck.clj      <-- the fence body, trimmed, must
;;   ...                                   appear verbatim as a substring of
;;   ```                                     examples/deck.clj
;; An untagged ```clojure fence is "illustrative" and exempt from the substring
;; rule, but is still scanned for the forbidden old style.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

(def repo (System/getProperty "user.dir"))
(def examples ["examples/counter.clj" "examples/todo.clj" "examples/monitor.clj"
               "examples/weather.clj" "examples/deck.clj"])
(def failures (atom []))
(defn fail! [msg] (swap! failures conj msg) (println "FAIL:" msg))
(defn ok!   [msg] (println "ok  :" msg))

(defn read-file [path]
  (try (slurp (fs/file repo path))
       (catch Exception e (fail! (str "cannot read " path ": " (ex-message e))) "")))

;; ---- fence parser ---------------------------------------------------------
;; Returns [{:info <info-string> :body <trimmed body string>}] for one text.
(defn fences [text]
  (let [lines (str/split-lines text)]
    (loop [i 0 in? false info nil acc [] body []]
      (if (>= i (count lines))
        acc
        (let [ln (nth lines i)
              open (when-not in? (re-find #"^\s*```(.*)$" ln))
              close (when in? (re-find #"^\s*```\s*$" ln))]
          (cond
            (and open (not in?)) (recur (inc i) true (nth open 1) acc [])
            close (recur (inc i) false nil
                         (if (some? info)
                           (conj acc {:info (or info "") :body (str/trimr (str/join "\n" body))})
                           acc)
                         [])
            in?   (recur (inc i) true info acc (conj body ln))
            :else (recur (inc i) false info acc body)))))))

(defn doc-files []
  (cons "README.md"
        (->> (fs/glob (fs/file repo "docs") "*.md")
             (map #(fs/relativize (fs/file repo ".") %))
             (map str)
             sort)))

;; ---- balanced top-level form extraction (for the weather i18n scan) -------
;; From `start` (index of the opening paren) return the balanced form text,
;; skipping string literals and ; comments so parens inside them don't count.
(defn form-text [text start]
  (let [n (count text)]
    (loop [i start depth 0 in-str false esc false]
      (cond
        (>= i n) (subs text start n)
        esc      (recur (inc i) depth in-str false)
        in-str   (if (= \" (nth text i))
                   (recur (inc i) depth false false)
                   (recur (inc i) depth true (= \\ (nth text i))))
        (= \\ (nth text i)) (recur (inc i) depth in-str false)
        (= \" (nth text i)) (recur (inc i) depth true false)
        (= \; (nth text i)) (recur (+ i (or (str/index-of (subs text i) "\n") (- n i))) depth false false)
        (= \( (nth text i)) (recur (inc i) (inc depth) false false)
        (= \) (nth text i)) (let [d (dec depth)]
                              (if (zero? d) (subs text start (inc i)) (recur (inc i) d false false)))
        :else (recur (inc i) depth false false)))))

(defn index-of-form [text pattern]
  (when-let [m (re-find pattern text)]
    (str/index-of text m)))

;; ---- 1. verbatim citation sync (D5) --------------------------------------
(def citation-count (atom 0))
(defn check-citations []
  (doseq [f (doc-files)]
    (doseq [{:keys [info body]} (fences (read-file f))]
      (when-let [m (re-find #"file=([^\s]+)" info)]
        (swap! citation-count inc)
        (let [target (second m)]
          (if-not (fs/exists? (fs/file repo target))
            (fail! (str f " cites file=" target " which does not exist"))
            (let [src (slurp (fs/file repo target))]
              (when-not (or (str/blank? body) (str/includes? src body))
                (fail! (str f " fenced block tagged file=" target
                            " is NOT verbatim in " target " -- first line: "
                            (pr-str (first (str/split-lines body))))))))))))
  (if (>= @citation-count 12)
    (ok! (str "verbatim citations: " @citation-count " (>=12)"))
    (fail! (str "only " @citation-count " file= citations; need >= 12"))))

;; ---- 2. forbidden old style in docs fences (D6) --------------------------
(defn forbidden-doc-fence? [body]
  (or (re-find #"\(fn\s*\[\s*\]\s*\(home\s+state\)\)" body)
      (re-find #"\(defn\s+home\s*\[\s*state\s*\]" body)
      (and (re-find #"\[\s*state\s*\]" body) (re-find #"@state" body))))

(defn check-doc-oldstyle []
  (let [hits (for [f (doc-files)
                   {:keys [info body]} (fences (read-file f))
                   :when (str/starts-with? (str/triml info) "clojure")
                   :when (forbidden-doc-fence? body)]
               f)]
    (if (seq hits)
      (fail! (str "docs fences show the forbidden atom-passing style in: "
                  (str/join ", " (distinct hits))))
      (ok! "no docs fence demonstrates the old atom-into-render style"))))

;; ---- 3. examples: headless load + forbidden signatures (D1) --------------
(defn check-examples-load []
  (let [syms (map #(symbol (str/replace (fs/file-name %) #"\.clj$" "")) examples)]
    (try
      (apply require syms)
      (ok! (str "examples load headless: " (str/join " " (map name syms))))
      (catch Throwable t
        (fail! (str "examples failed to load: " (ex-message t)))))))

(defn check-examples-signatures []
  (let [bad (for [e examples
                  :let [txt (read-file e)]
                  :when (or (re-find #"\(fn\s*\[\s*\]\s*\(home\s+state\)\)" txt)
                            (re-find #"\(defn\s+home\s*\[\s*state\s*\]" txt))]
              e)]
    (if (seq bad)
      (fail! (str "examples still pass the atom into render in: " (str/join ", " bad)))
      (ok! "no example hands the atom to a render helper (page derefs at the edge)"))))

;; ---- 4. every example alias registration carries :purity :pure (D2) ------
(defn check-alias-purity []
  (let [nreg (apply + (for [e examples :let [txt (read-file e)]]
                        (count (re-seq #"\(ga/register-alias!" txt))))
        npure (apply + (for [e examples :let [txt (read-file e)]]
                         (count (re-seq #"\{:purity\s+:pure\}" txt))))]
    (cond
      (< nreg 8) (fail! (str "expected >= 8 alias registrations across examples, found " nreg))
      (not= nreg npure) (fail! (str "alias registrations " nreg " but only " npure
                                    " carry {:purity :pure}; every example alias must be pure"))
      :else (ok! (str "alias registrations " nreg ", all {:purity :pure}")))))

;; ---- 5. weather: no localized (t ...) inside child helpers / alias bodies (D3) --
(def weather-child-names ["hero" "dashboard" "loading" "failed"])
(def weather-alias-kws [":weather/hour" ":weather/day" ":weather/detail"])
(defn check-weather-i18n []
  (let [txt (read-file "examples/weather.clj")
        t-re #"\(\s*t\s+:ui/"]
    (let [offenders
          (concat
           (for [nm weather-child-names
                 :let [st (index-of-form txt (re-pattern (str "(?m)^\\(defn\\s+" nm "\\s")))]
                 :when (some? st)
                 :let [ft (form-text txt st)]
                 :when (re-find t-re ft)]
             (str "defn " nm))
           (for [kw weather-alias-kws
                 :let [st (index-of-form txt (re-pattern (str "\\(ga/register-alias!\\s+"
                                                               (str/replace kw #":" "\\\\:") "\\s")))]
                 :when (some? st)
                 :let [ft (form-text txt st)]
                 :when (re-find t-re ft)]
             (str "alias " kw)))]
      (if (seq offenders)
        (fail! (str "weather child/alias forms resolve localized strings themselves (must be "
                    "passed in from the page): " (str/join ", " (distinct offenders))))
        (ok! "weather resolves all i18n strings at the page edge (children/aliases pure)")))))

;; ---- 6. docs teach the rule + link Replicant + name the i18n exception (D4) --
(defn check-docs-content []
  (let [all (apply str (interpose "\n" (map read-file (doc-files))))
        need {"replicant.fun link" #"replicant\.fun/"
              ":replicant/alias-data exception" #":replicant/alias-data"
              "the deref-at-page rule prose" #"(?i)deref"}]
    (doseq [[label re] need]
      (if (re-find re all)
        (ok! (str "docs contain " label))
        (fail! (str "docs are missing " label))))))

;; ---- 7. guards: clj-kondo errors:0 on examples; internals table; src clean (D7) --
(defn check-kondo []
  (let [args (into ["clj-kondo" "--lint"] examples)
        res (deref (p/process args {:out :string :err :string}))
        out (:out res)]
    (if (re-find #"errors: 0," out)
      (ok! "clj-kondo: examples lint with errors: 0")
      (fail! (str "clj-kondo reported errors in examples:\n"
                  (str/join "\n" (take 15 (str/split-lines out))))))))

(defn check-internals-table []
  (let [txt (read-file "docs/internals.md")
        rows (re-seq #"\|\s*`(src/gtkiccup/[A-Za-z0-9_\-]+\.clj)`\s*\|\s*(\d+)\s*\|" txt)
        mismatches (for [[_ path n] rows
                         :let [actual (count (str/split-lines (read-file path)))]
                         :when (not= (parse-long n) actual)]
                     (str path " table=" n " actual=" actual))]
    (cond
      (< (count rows) 5) (fail! (str "internals namespace table has only " (count rows) " rows"))
      (seq mismatches) (fail! (str "internals line-count table stale: " (str/join "; " mismatches)))
      :else (ok! (str "internals line-count table matches wc for " (count rows) " namespaces")))))

(defn check-src-untouched []
  (let [res (deref (p/process ["git" "-C" repo "diff" "--stat" "--" "src/"]
                              {:out :string :err :string}))
        out (str/trim (:out res))]
    (if (str/blank? out)
      (ok! "library src/ untouched by this goal")
      (fail! (str "this goal must not modify src/, but git diff shows:\n" out)))))

;; ---- run ------------------------------------------------------------------
(defn run-all []
  (check-citations)
  (check-doc-oldstyle)
  (check-examples-load)
  (check-examples-signatures)
  (check-alias-purity)
  (check-weather-i18n)
  (check-docs-content)
  (check-kondo)
  (check-internals-table)
  (check-src-untouched)
  (println)
  (let [n (count @failures)]
    (if (zero? n)
      (do (println (str "EXAMPLES-DOCS-OK citations=" @citation-count))
          (System/exit 0))
      (do (println (str "EXAMPLES-DOCS-FAILED " n " item(s) outstanding"))
          (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (run-all))

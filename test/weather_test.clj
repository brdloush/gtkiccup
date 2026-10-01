
;; The weather app's config, cache and offline path. No network: the "response"
;; is the captured fixture, so this is deterministic.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[babashka.fs :as fs] '[cheshire.core :as json]
         '[gtkiccup.adw :as adw] '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g]
         '[i18n] '[openmeteo :as om] '[weather])

;; English, whatever locale this machine runs in: the strings below are the
;; English ones. test/i18n_test.clj is where both languages are checked.
(i18n/set-lang! :en)
(defcfn label-get-text "gtk_label_get_text" [:pointer] :string)
(defcfn row-get-title "adw_preferences_row_get_title" [:pointer] :string)

(def fixture (json/parse-string (slurp "test/fixtures/openmeteo-prague.json") true))
(def tmp (str (fs/create-temp-dir) "/cfg.edn"))

;; point the app at a temp config for the whole run
(alter-var-root #'weather/*config-path* (constantly tmp))

;; --- 1. defaults when there is no config file --------------------------
(weather/reset-state!)
(def cfg (:config @weather/state))
(println "1) defaults:" (:name (:place cfg)) (:units cfg))
(assert (= "Prague" (:name (:place cfg))) "no default place")
(assert (= :metric (:units cfg)))
(assert (nil? (:cache cfg)) "should start with no cache")
(assert (not (fs/exists? tmp)) "reading should not create the file")

;; --- 2. write then read back ------------------------------------------
(weather/write-config! (assoc cfg :cache fixture :fetched-at 12345
                              :place {:name "Brno" :country "Czechia" :lat 49.2 :lon 16.6}))
(assert (fs/exists? tmp) "config was not written")
(weather/reset-state!)
(def cfg2 (:config @weather/state))
(println "2) round-tripped place:" (:name (:place cfg2)) "| cache?" (some? (:cache cfg2)))
(assert (= "Brno" (:name (:place cfg2))))
(assert (= 12345 (:fetched-at cfg2)))
(assert (some? (:cache cfg2)) "cache did not survive")

;; a corrupt file must not stop the app starting
(spit tmp "{{{ not edn")
(weather/reset-state!)
(println "3) corrupt config falls back to defaults:"
         (:name (:place (:config @weather/state))))
(assert (= "Prague" (:name (:place (:config @weather/state)))))

;; --- 4. the offline path: render from cache, with no network ----------
(weather/write-config! {:place {:name "Prague" :country "Czechia" :lat 50.07 :lon 14.43}
                        :units :metric
                        :lang :en
                        :cache fixture
                        :fetched-at (- (System/currentTimeMillis) (* 60000 200))})
(weather/reset-state!)
(weather/show-cached!)
(def vm (:vm @weather/state))
(println "4) rendered from cache, no fetch:" (:temp (:current vm)) (:label (:current vm)))
(assert (some? vm) "cache did not produce a view model")
(assert (= 7 (count (:daily vm))))

;; and the banner tells the truth about its age
(def stale (om/stale-label (:fetched-at (:config @weather/state)) (System/currentTimeMillis)))
(println "   banner:" (pr-str stale))
(assert (re-find #"3 hours" stale) "200 minutes old should read as hours")
;; and just under the threshold it stays in minutes, so it never says "1 hours"
(assert (re-find #"minutes" (om/stale-label 0 (* 60000 119))))
(assert (re-find #"2 hours"  (om/stale-label 0 (* 60000 120))))

;; --- 5. the whole tree builds from cached data ------------------------
(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def win ((:ctor adw/window)))
(def tree (reconcile (@#'ui/root-spec adw/window) win nil
                     (normalize ((weather/app)))))
(println "5) built the offline tree ok")

;; the hero shows the cached temperature
(defn find-label
  "First label in the tree whose text matches."
  [node re]
  (when node
    (or (when (and (= :label (:tag node))
                   (re-find re (or (label-get-text (:widget node)) "")))
          (label-get-text (:widget node)))
        (some #(find-label % re) (:children node)))))
(println "   hero temperature on screen:" (pr-str (find-label tree #"^-?\d+°$")))
(assert (find-label tree #"^-?\d+°$") "no temperature rendered")
(println "   place line:" (pr-str (find-label tree #"Prague")))
(assert (find-label tree #"Prague"))

;; --- 6. units toggle reaches the view model --------------------------
(def before (:temp (:current (:vm @weather/state))))
(swap! weather/state
       (fn [s] (let [cfg (assoc (:config s) :units :imperial)]
                 (assoc s :config cfg
                        :vm (om/view-model (:cache cfg) (:place cfg)
                                           {:units :imperial
                                            :fetched-at (:fetched-at cfg)})))))
(def after (:temp (:current (:vm @weather/state))))
(println "6) units:" before "->" after)
(assert (not= before after) "toggling units changed nothing")
(println "ALL OK")
(System/exit 0)

;; The weather data layer. Runs from a captured response, so it needs no
;; network and gives the same answer every time.
(require '[cheshire.core :as json] '[clojure.string :as str] '[openmeteo :as om])

(def data (json/parse-string (slurp "test/fixtures/openmeteo-prague.json") true))
(def place {:name "Prague" :region "Prague" :country "Czechia"
            :lat 50.0755 :lon 14.4378})

;; --- 1. every WMO code maps to something usable -------------------------
;; the codes Open-Meteo actually emits. WMO skips the even numbers inside the
;; drizzle/rain/snow ranges, so this is an explicit list, not a range.
(def codes [0 1 2 3 45 48 51 53 55 56 57 61 63 65 66 67 71 73 75 77
            80 81 82 85 86 95 96 99])
(println "1) mapping" (count codes) "documented codes")
(doseq [c codes, day? [true false]]
  (let [{:keys [label icon sky]} (om/condition c day?)]
    (assert (string? label))
    (assert (not= "Unknown" label) (str "code " c " unmapped"))
    (assert (str/ends-with? icon "-symbolic") (str "bad icon for " c))
    (assert (str/ends-with? (name sky) (if day? "-day" "-night")) (str "bad sky for " c))))
(println "   all mapped, day and night")

;; an unknown code degrades instead of blowing up
(println "2) unknown code ->" (om/condition 1234 true))
(assert (= "Unknown" (:label (om/condition 1234 true))))
(assert (some? (:icon (om/condition 1234 true))))

;; night icons only where the theme actually has one
(assert (= "weather-clear-night-symbolic" (:icon (om/condition 0 false))))
(assert (= "weather-clear-symbolic" (:icon (om/condition 0 true))))
(assert (= "weather-showers-symbolic" (:icon (om/condition 63 false)))
        "rain has no night variant in the theme")
(println "   night variants only where the theme has them")

;; --- 3. sky classes are all distinct and prefixed ----------------------
(def skies (set (for [c codes, d? [true false]] (om/sky-class (:sky (om/condition c d?))))))
(println "3) distinct sky classes:" (count skies) (sort skies))
(assert (every? #(str/starts-with? % "sky-") skies))

;; --- 4. formatting ----------------------------------------------------
(println "4) deg:" (om/deg 17.6) (om/deg 17.4) (om/deg -0.4) (om/deg nil)
         "| imperial:" (om/deg 0 :imperial) (om/deg 100 :imperial))
(assert (= "18°" (om/deg 17.6)))
(assert (= "17°" (om/deg 17.4)))
(assert (= "0°" (om/deg -0.4)))
(assert (= "--" (om/deg nil)))
(assert (= "32°" (om/deg 0 :imperial)))
(assert (= "212°" (om/deg 100 :imperial)))
(assert (= "10 km/h" (om/speed 10 :metric)))
(assert (= "6 mph" (om/speed 10 :imperial)))
(assert (= "--" (om/speed nil :metric)))

;; --- 5. the view model ------------------------------------------------
(def vm (om/view-model data place))
(println "5) view-model keys:" (sort (keys vm)))
(assert (= [:current :daily :details :fetched-at :hourly :place :sky :sky-class :today :units]
           (sort (keys vm))))
(println "   place:" (:place vm))
(assert (= "Prague" (:name (:place vm))))
(assert (= "Europe/Prague" (:timezone (:place vm))))

(println "   current:" (:current vm))
(assert (re-matches #"-?\d+°" (:temp (:current vm))))
(assert (string? (:label (:current vm))))
(assert (= false (:day? (:current vm))) "fixture was captured at 23:15, so night")
(assert (= "sky-clear-night" (:sky-class vm)) "night fixture should pick a night sky")

;; exactly 24 hourly entries, starting at or after the current time
(println "   hourly:" (count (:hourly vm)) "entries, first at" (:time (first (:hourly vm))))
(assert (= 24 (count (:hourly vm))) "should be 24 hours")
(assert (every? #(re-matches #"\d\d:\d\d" (:time %)) (:hourly vm)))
(assert (every? #(re-matches #"-?\d+°" (:temp-label %)) (:hourly vm)))
(assert (every? #(<= 0 (:precip %) 100) (:hourly vm)))

;; 7 days, first two named rather than numbered
(println "   daily:" (mapv :day (:daily vm)))
(assert (= 7 (count (:daily vm))))
(assert (= "Today" (:day (first (:daily vm)))))
(assert (= "Tomorrow" (:day (second (:daily vm)))))
(assert (every? #(re-matches #"[A-Z][a-z]{2}" (:day %)) (drop 2 (:daily vm))))
(assert (every? #(and (:hi %) (:lo %) (:icon %)) (:daily vm)))

(println "   details:" (mapv :title (:details vm)))
(assert (= ["Humidity" "Wind" "Daylight"] (mapv :title (:details vm)))
        "feels-like belongs in the hero; sunrise and sunset share a row")
(assert (re-find #"\d\d:\d\d.+\d\d:\d\d" (:value (last (:details vm))))
        "daylight row should show both times")
(assert (every? #(and (seq (:value %)) (seq (:icon %))) (:details vm)))

;; --- 6. imperial flows all the way through ----------------------------
(def vmi (om/view-model data place {:units :imperial}))
(println "6) metric" (:temp (:current vm)) "-> imperial" (:temp (:current vmi)))
(assert (not= (:temp (:current vm)) (:temp (:current vmi))))
(assert (str/includes? (:value (nth (:details vmi) 1)) "mph"))

;; --- 7. the staleness banner ------------------------------------------
(println "7) stale-label:"
         (pr-str (om/stale-label nil 0))
         (pr-str (om/stale-label 0 (* 60000 10)))
         (pr-str (om/stale-label 0 (* 60000 45)))
         (pr-str (om/stale-label 0 (* 60000 200)))
         (pr-str (om/stale-label 0 (* 60000 5000))))
(assert (nil? (om/stale-label nil 0)) "no timestamp, no banner")
(assert (nil? (om/stale-label 0 (* 60000 10))) "fresh enough, no banner")
(assert (re-find #"45 minutes" (om/stale-label 0 (* 60000 45))))
(assert (re-find #"3 hours" (om/stale-label 0 (* 60000 200))))
(assert (re-find #"3 days" (om/stale-label 0 (* 60000 5000))))

;; --- 8. the URL builder sends what we think ---------------------------
(def u (om/forecast-url {:lat 1.5 :lon -2.5}))
(println "8) url:" (subs u 0 78) "...")
(assert (str/includes? u "latitude=1.5"))
(assert (str/includes? u "longitude=-2.5"))
(assert (str/includes? u "timezone=auto"))
(assert (str/includes? u "forecast_days=7"))
(assert (not (str/includes? u "key")) "this API takes no key; nothing secret in the URL")
(println "ALL OK")

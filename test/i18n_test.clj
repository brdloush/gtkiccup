;; The weather app in two languages. No network: the "response" is the
;; captured fixture, so every string below is deterministic.
(require '[babashka.fs :as fs] '[cheshire.core :as json] '[clojure.string :as str]
         '[i18n] '[openmeteo :as om] '[weather] '[weather-i18n :as wi])

(def data (json/parse-string (slurp "test/fixtures/openmeteo-prague.json") true))
(def place {:name "Prague" :region "Prague" :country "Czechia"
            :lat 50.0755 :lon 14.4378})

;; --- 1. the locale comes from the environment -------------------------
(println "1) detect:" (mapv i18n/detect ["cs_CZ.UTF-8" "cs" "en_US.UTF-8" "de_DE" "" nil]))
(assert (= :cs (i18n/detect "cs_CZ.UTF-8")))
(assert (= :cs (i18n/detect "CS")))
(assert (= :en (i18n/detect "en_US.UTF-8")))
(assert (= :en (i18n/detect "de_DE")) "an unsupported language falls back to English")
(assert (= :en (i18n/detect nil)) "no locale set at all")
(assert (= :en (i18n/detect "c")) "a one-letter locale must not blow up")

;; --- 2. the Czech dictionary is complete -------------------------------
(def missing (remove (set (keys wi/cs)) (keys wi/en)))
(println "2)" (count wi/en) "keys, untranslated:" (vec missing))
(assert (empty? missing) (str "no Czech for " (vec missing)))
(assert (= (set (keys (:cond/labels wi/en))) (set (keys (:cond/labels wi/cs))))
        "every WMO code needs a label in both languages")
(assert (= 7 (count (:day/short wi/cs))) "seven weekdays")
(assert (= 8 (count (:wind/points wi/cs))) "eight compass points")

;; --- 3. a missing key falls back rather than blowing up ---------------
(i18n/register! {:en {:test/only-english "English only"}})
(binding [i18n/*lang* :cs]
  (println "3) no Czech for :test/only-english ->" (pr-str (i18n/t :test/only-english)))
  (assert (= "English only" (i18n/t :test/only-english))
          "a half translated dictionary shows English, not nothing"))
(assert (= "nothing/here" (i18n/t :nothing/here)) "an unknown key shows itself")
(assert (= :en (i18n/set-lang! :klingon)) "an unsupported language becomes English")

;; --- 4. the same response, in both languages --------------------------
(defn vm-in [lang] (binding [i18n/*lang* lang] (om/view-model data place)))
(def en (vm-in :en))
(def cs (vm-in :cs))
(println "4) condition:" (pr-str (:label (:current en))) "->" (pr-str (:label (:current cs))))
(assert (= "Mainly clear" (:label (:current en))))
(assert (= "Skoro jasno" (:label (:current cs))))
(println "   days:" (mapv :day (:daily en)) (mapv :day (:daily cs)))
(assert (= "Today" (:day (first (:daily en)))))
(assert (= "Dnes" (:day (first (:daily cs)))))
(assert (= "Zítra" (:day (second (:daily cs)))))
(assert (every? #(re-matches #"[A-ZÁČĎÉĚÍŇÓŘŠŤÚŮÝŽ][a-záčďéěíňóřšťúůýž]" (:day %))
                (drop 2 (:daily cs)))
        "Czech weekday abbreviations are two letters")
(println "   details:" (mapv :title (:details cs)))
(assert (= ["Vlhkost" "Vítr" "Denní světlo"] (mapv :title (:details cs))))
;; the numbers are language-independent: only the words move
(assert (= (:temp (:current en)) (:temp (:current cs))))
(assert (= (mapv :hi (:daily en)) (mapv :hi (:daily cs))))
;; and the icons are theme names, which are never translated
(assert (= (mapv :icon (:daily en)) (mapv :icon (:daily cs))))

;; --- 5. Czech counts in three shapes ----------------------------------
(defn stale-in [lang mins] (binding [i18n/*lang* lang] (om/stale-label 0 (* 60000 mins))))
(println "5) stale:" (mapv #(stale-in :cs %) [45 200 500 5000 13000]))
(assert (nil? (stale-in :cs 10)) "fresh enough, no banner in any language")
(assert (str/includes? (stale-in :cs 45) "45 minut"))
(assert (str/includes? (stale-in :cs 200) "3 hodiny") "two to four takes the plural")
(assert (str/includes? (stale-in :cs 500) "8 hodin") "five and up takes the genitive")
(assert (str/includes? (stale-in :cs 5000) "3 dny"))
(assert (str/includes? (stale-in :cs 13000) "9 dní"))

;; --- 6. the switch in the app -----------------------------------------
(def tmp (str (fs/create-temp-dir) "/cfg.edn"))
(alter-var-root #'weather/*config-path* (constantly tmp))
(weather/write-config! {:place place :units :metric :lang :en
                        :cache data :fetched-at (System/currentTimeMillis)})
(weather/reset-state!)
(weather/show-cached!)
(assert (= :en i18n/*lang*) "the language is read back out of the config")
(def before (:label (:current (:vm @weather/state))))

(weather/set-language! (weather/next-language i18n/*lang*))
(def after (:label (:current (:vm @weather/state))))
(println "6) switched:" (pr-str before) "->" (pr-str after))
(assert (= :cs i18n/*lang*) "two languages, so next wraps to Czech")
(assert (= "Skoro jasno" after) "switching rebuilt the view model from the cache")
(assert (= :cs (:lang (weather/read-config))) "the choice was written to disk")

;; and back again, which is what the header button does twice
(weather/set-language! (weather/next-language i18n/*lang*))
(println "   and back:" (pr-str (:label (:current (:vm @weather/state)))))
(assert (= :en i18n/*lang*))
(assert (= before (:label (:current (:vm @weather/state)))))

;; a config written before the app knew about languages still opens, and
;; falls back to whatever the environment says
(spit tmp (pr-str {:place place :units :metric}))
(weather/reset-state!)
(println "7) config with no :lang ->" i18n/*lang*)
(assert (= (i18n/detect) i18n/*lang*)
        "a config from before this feature should take the environment's language")
(println "ALL OK")

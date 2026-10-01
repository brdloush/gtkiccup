(ns weather-i18n
  "Every string the weather app shows, in English and Czech.

   Kept in one file, away from the layout and away from the data layer, for the
   same reason `weather-css` is: a translator should be able to read it without
   reading the app. Requiring this namespace registers the strings, so both
   `openmeteo` and `weather` just pull it in and call `i18n/t`.

   English is the fallback, so an untranslated key shows English rather than
   nothing. Keys are namespaced by what they belong to: `:cond/N` is the label
   for WMO code N, `:day/*` names days, `:ui/*` is chrome."
  (:require [i18n]))

(def en
  {;; -- WMO weather codes ------------------------------------------------
   ;; WMO code -> label. A keyword cannot start with a digit, so
   ;; these live in one map rather than one key each.
   :cond/labels
   {0   "Clear"
    1   "Mainly clear"
    2   "Partly cloudy"
    3   "Overcast"
    45  "Fog"
    48  "Freezing fog"
    51  "Light drizzle"
    53  "Drizzle"
    55  "Heavy drizzle"
    56  "Freezing drizzle"
    57  "Freezing drizzle"
    61  "Light rain"
    63  "Rain"
    65  "Heavy rain"
    66  "Freezing rain"
    67  "Freezing rain"
    71  "Light snow"
    73  "Snow"
    75  "Heavy snow"
    77  "Snow grains"
    80  "Light showers"
    81  "Showers"
    82  "Violent showers"
    85  "Snow showers"
    86  "Heavy snow showers"
    95  "Thunderstorm"
    96  "Thunderstorm, hail"
    99  "Thunderstorm, hail"}
   :cond/unknown "Unknown"

   ;; -- days and directions ----------------------------------------------
   :day/today    "Today"
   :day/tomorrow "Tomorrow"
   ;; Monday first, as java.time.DayOfWeek numbers them
   :day/short    ["Mon" "Tue" "Wed" "Thu" "Fri" "Sat" "Sun"]
   ;; compass points clockwise from north, the direction the wind comes from
   :wind/points  ["N" "NE" "E" "SE" "S" "SW" "W" "NW"]

   ;; -- the staleness banner ---------------------------------------------
   ;; %d is always 30 or more, 2 or more, 2 or more: no singular can occur
   :stale/minutes "Showing data from %d minutes ago"
   :stale/hours   "Showing data from %d hours ago"
   :stale/days    "Showing data from %d days ago"

   ;; -- detail rows --------------------------------------------------------
   :detail/humidity "Humidity"
   :detail/wind     "Wind"
   :detail/daylight "Daylight"

   ;; -- chrome -------------------------------------------------------------
   ;; the place the app opens on before anyone searches for another
   :place/name    "Prague"
   :place/country "Czechia"

   :ui/title        "Weather"
   :ui/search       "Search a city"
   :ui/search-tip   "Search for a city, then press Enter"
   :ui/refresh-tip  "Refresh now"
   :ui/units-tip    "Switch units"
   :ui/language-tip "Switch language"
   :ui/no-place     "No place called \"%s\""
   :ui/now          "now"
   :ui/feels-like   "Feels like"
   :ui/hi           "H"
   :ui/lo           "L"
   :ui/week         "7 days"
   :ui/details      "Details"
   :ui/loading      "Fetching the forecast"
   :ui/loading-sub  "Open-Meteo, no account needed."
   :ui/failed       "Could not reach Open-Meteo"})

(def cs
  {:cond/labels
   {0   "Jasno"
    1   "Skoro jasno"
    2   "Polojasno"
    3   "Zataženo"
    45  "Mlha"
    48  "Mrznoucí mlha"
    51  "Slabé mrholení"
    53  "Mrholení"
    55  "Silné mrholení"
    56  "Mrznoucí mrholení"
    57  "Mrznoucí mrholení"
    61  "Slabý déšť"
    63  "Déšť"
    65  "Silný déšť"
    66  "Mrznoucí déšť"
    67  "Mrznoucí déšť"
    71  "Slabé sněžení"
    73  "Sněžení"
    75  "Silné sněžení"
    77  "Sněhová zrna"
    80  "Slabé přeháňky"
    81  "Přeháňky"
    82  "Silné přeháňky"
    85  "Sněhové přeháňky"
    86  "Silné sněhové přeháňky"
    95  "Bouřka"
    96  "Bouřka s krupobitím"
    99  "Bouřka s krupobitím"}
   :cond/unknown "Neznámo"

   :day/today    "Dnes"
   :day/tomorrow "Zítra"
   :day/short    ["Po" "Út" "St" "Čt" "Pá" "So" "Ne"]
   ;; sever, severovýchod, východ, jihovýchod, jih, jihozápad, západ, severozápad
   :wind/points  ["S" "SV" "V" "JV" "J" "JZ" "Z" "SZ"]

   ;; Czech counts in three shapes -- 1 hodina, 2 hodiny, 5 hodin -- so these
   ;; are functions rather than format strings. The thresholds in stale-label
   ;; mean the singular can never come up; two to four still can.
   :stale/minutes (fn [n] (str "Data jsou " n " minut stará"))
   :stale/hours   (fn [n] (str "Data jsou " n (if (<= n 4) " hodiny" " hodin") " stará"))
   :stale/days    (fn [n] (str "Data jsou " n (if (<= n 4) " dny" " dní") " stará"))

   :detail/humidity "Vlhkost"
   :detail/wind     "Vítr"
   :detail/daylight "Denní světlo"

   ;; the place the app opens on before anyone searches for another
   :place/name    "Praha"
   :place/country "Česko"

   :ui/title        "Počasí"
   :ui/search       "Hledat město"
   :ui/search-tip   "Zadejte město a stiskněte Enter"
   :ui/refresh-tip  "Obnovit"
   :ui/units-tip    "Přepnout jednotky"
   :ui/language-tip "Přepnout jazyk"
   :ui/no-place     "Žádné místo s názvem „%s“"
   :ui/now          "teď"
   :ui/feels-like   "Pocitově"
   :ui/hi           "max"
   :ui/lo           "min"
   :ui/week         "7 dní"
   :ui/details      "Podrobnosti"
   :ui/loading      "Načítám předpověď"
   :ui/loading-sub  "Open-Meteo, bez registrace."
   :ui/failed       "Open-Meteo je nedostupné"})

(defonce ^{:doc "Registered when this namespace loads, once."} registered
  (do (i18n/register! {:en en :cs cs})
      {:keys (count en)
       :untranslated (sort (remove (set (keys cs)) (keys en)))}))

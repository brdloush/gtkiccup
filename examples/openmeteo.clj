(ns openmeteo
  "Open-Meteo: fetch, parse, and shape into exactly what the UI renders.

   No API key and no signup -- that is why this API was chosen. Fetching is one
   function; everything else is pure, so the whole layer is testable from a
   captured response with no network. See test/openmeteo_test.clj.

   Privacy: no IP geolocation. The place comes from config, and a geocoding
   request sends only the name that was typed.

   Every string it produces goes through `i18n/t`, so a view model built while
   Czech is in force is already Czech -- the UI never translates anything."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [i18n :refer [t]]
            [weather-i18n]))

;; ---------------------------------------------------------------------------
;; WMO weather codes
;; ---------------------------------------------------------------------------

(def ^:private conditions
  "WMO code -> icon stem and the sky palette to paint behind it. The label is
   not here: it lives in the dictionary, under :cond/<code>.
   Icon stems get -symbolic, and a -night- variant where the theme has one."
  {0  ["weather-clear"             :clear]
   1  ["weather-few-clouds"        :clear]
   2  ["weather-few-clouds"        :few]
   3  ["weather-overcast"          :overcast]
   45 ["weather-fog"               :fog]
   48 ["weather-fog"               :fog]
   51 ["weather-showers-scattered" :rain]
   53 ["weather-showers-scattered" :rain]
   55 ["weather-showers-scattered" :rain]
   56 ["weather-showers-scattered" :rain]
   57 ["weather-showers-scattered" :rain]
   61 ["weather-showers"           :rain]
   63 ["weather-showers"           :rain]
   65 ["weather-showers"           :rain]
   66 ["weather-showers"           :rain]
   67 ["weather-showers"           :rain]
   71 ["weather-snow"              :snow]
   73 ["weather-snow"              :snow]
   75 ["weather-snow"              :snow]
   77 ["weather-snow"              :snow]
   80 ["weather-showers-scattered" :rain]
   81 ["weather-showers"           :rain]
   82 ["weather-showers"           :rain]
   85 ["weather-snow"              :snow]
   86 ["weather-snow"              :snow]
   95 ["weather-storm"             :storm]
   96 ["weather-storm"             :storm]
   99 ["weather-storm"             :storm]})

(def ^:private has-night-variant
  "Only these icon stems have a -night- form in the Adwaita theme."
  #{"weather-clear" "weather-few-clouds"})

(defn condition
  "Everything the UI needs about a weather code: {:label :icon :sky}.
   `day?` picks the night icon and the night palette where they exist.
   The label is in whatever language `i18n/*lang*` currently names."
  [code day?]
  (let [[stem sky] (get conditions code ["weather-severe-alert" :overcast])
        label (get (t :cond/labels) code (t :cond/unknown))]
    {:label label
     :icon  (if (and (not day?) (has-night-variant stem))
              (str stem "-night-symbolic")
              (str stem "-symbolic"))
     :sky   (keyword (str (name sky) (if day? "-day" "-night")))}))

(defn sky-class
  "The CSS class for a sky palette. Every one of these is defined up front in
   the stylesheet, so changing conditions only swaps a class -- it never has to
   rebuild a CSS provider."
  [sky]
  (str "sky-" (name sky)))

;; ---------------------------------------------------------------------------
;; fetch
;; ---------------------------------------------------------------------------

(def ^:private forecast-fields
  {:current "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m,wind_direction_10m"
   :hourly  "temperature_2m,precipitation_probability,weather_code"
   :daily   "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"})

(defn forecast-url [{:keys [lat lon]}]
  (str "https://api.open-meteo.com/v1/forecast"
       "?latitude=" lat "&longitude=" lon
       "&current=" (:current forecast-fields)
       "&hourly=" (:hourly forecast-fields)
       "&daily=" (:daily forecast-fields)
       "&timezone=auto&forecast_days=7"))

(defn fetch!
  "One HTTP call. The only impure function here. Throws on a non-200."
  [place]
  (let [{:keys [status body]} (http/get (forecast-url place) {:timeout 10000})]
    (when (not= 200 status)
      (throw (ex-info "open-meteo returned an error" {:status status})))
    (json/parse-string body true)))

(defn search-places!
  "Geocoding, also keyless. Sends only the typed name -- and the language, so
   the answer comes back as `Praha, Česko` rather than `Prague, Czechia`."
  [q]
  (when-not (str/blank? q)
    (let [url (str "https://geocoding-api.open-meteo.com/v1/search?count=8"
                   "&language=" (name i18n/*lang*)
                   "&name=" (java.net.URLEncoder/encode (str/trim q) "UTF-8"))
          {:keys [status body]} (http/get url {:timeout 8000})]
      (when (= 200 status)
        (->> (:results (json/parse-string body true))
             (mapv (fn [r] {:name (:name r)
                            :region (:admin1 r)
                            :country (:country r)
                            :lat (:latitude r)
                            :lon (:longitude r)})))))))

;; ---------------------------------------------------------------------------
;; formatting
;; ---------------------------------------------------------------------------

(defn deg
  "Temperature as a whole number with a degree sign. Imperial converts."
  ([c] (deg c :metric))
  ([c units]
   (if (nil? c)
     "--"
     (let [v (if (= :imperial units) (+ 32 (* 1.8 c)) c)]
       (str (Math/round (double v)) "°")))))

(defn speed [kmh units]
  (if (nil? kmh)
    "--"
    (if (= :imperial units)
      (format "%.0f mph" (* 0.621371 kmh))
      (format "%.0f km/h" (double kmh)))))

(defn- hh:mm [iso]
  (when iso (last (str/split iso #"T"))))

(defn- day-name
  "Today, Tomorrow, then the weekday. Keeps the 7-day list readable."
  [date-str today-str]
  (let [d     (java.time.LocalDate/parse date-str)
        today (java.time.LocalDate/parse today-str)
        delta (- (.toEpochDay d) (.toEpochDay today))]
    (case delta
      0 (t :day/today)
      1 (t :day/tomorrow)
      ;; DayOfWeek numbers Monday 1 .. Sunday 7, and so does :day/short
      (nth (t :day/short) (dec (.getValue (.getDayOfWeek d)))))))

(defn- wind-arrow
  "Meteorological direction is where the wind comes *from*."
  [deg-from]
  (when deg-from
    (nth (t :wind/points)
         (mod (Math/round (/ (double deg-from) 45.0)) 8))))

;; ---------------------------------------------------------------------------
;; the shape the UI renders
;; ---------------------------------------------------------------------------

(defn- hourly-from-now
  "The next n hours, starting at the current hour. The API returns entries from
   local midnight, so find where now sits rather than assuming index 0."
  [data n day?]
  (let [times (-> data :hourly :time)
        now   (-> data :current :time)
        start (or (->> times (keep-indexed (fn [i t] (when (>= (compare t now) 0) i))) first)
                  0)
        idx   (range start (min (count times) (+ start n)))]
    (mapv (fn [i]
            (let [t (nth times i)
                  code (nth (-> data :hourly :weather_code) i)
                  ;; crude but right most of the year: daylight roughly 6am-9pm
                  hour (parse-long (subs t 11 13))
                  d?   (if (nil? hour) day? (<= 6 hour 20))]
              {:time (hh:mm t)
               :temp (nth (-> data :hourly :temperature_2m) i)
               :precip (nth (-> data :hourly :precipitation_probability) i)
               :icon (:icon (condition code d?))}))
          idx)))

(defn view-model
  "Turns one API response into exactly what the UI shows. Pure."
  ([data place] (view-model data place {}))
  ([data place {:keys [units fetched-at] :or {units :metric}}]
   (let [cur    (:current data)
         daily  (:daily data)
         day?   (= 1 (:is_day cur))
         cond*  (condition (:weather_code cur) day?)
         today  (first (:time daily))]
     {:place    (merge (select-keys place [:name :region :country])
                       {:timezone (:timezone data)
                        :elevation (:elevation data)})
      :units    units
      :sky      (:sky cond*)
      :sky-class (sky-class (:sky cond*))
      :current  {:temp   (deg (:temperature_2m cur) units)
                 :feels  (deg (:apparent_temperature cur) units)
                 :label  (:label cond*)
                 :icon   (:icon cond*)
                 :day?   day?
                 :at     (hh:mm (:time cur))}
      :today    {:hi (deg (first (:temperature_2m_max daily)) units)
                 :lo (deg (first (:temperature_2m_min daily)) units)
                 :precip (first (:precipitation_probability_max daily))
                 :sunrise (hh:mm (first (:sunrise daily)))
                 :sunset  (hh:mm (first (:sunset daily)))}
      :hourly   (mapv (fn [h] (assoc h :temp-label (deg (:temp h) units)))
                      (hourly-from-now data 24 day?))
      :daily    (mapv (fn [i]
                        (let [code (nth (:weather_code daily) i)
                              c (condition code true)]
                          {:day    (day-name (nth (:time daily) i) today)
                           :date   (nth (:time daily) i)
                           :label  (:label c)
                           :icon   (:icon c)
                           :hi     (deg (nth (:temperature_2m_max daily) i) units)
                           :lo     (deg (nth (:temperature_2m_min daily) i) units)
                           :precip (nth (:precipitation_probability_max daily) i)}))
                      (range (count (:time daily))))
      ;; "Feels like" deliberately absent: the hero already says it, and a
      ;; duplicated row is both noise and a screen-height it does not earn.
      :details  [{:title (t :detail/humidity) :value (str (:relative_humidity_2m cur) "%")
                  :icon "weather-showers-scattered-symbolic"}
                 {:title (t :detail/wind) :value (str (speed (:wind_speed_10m cur) units)
                                                      " " (wind-arrow (:wind_direction_10m cur)))
                  :icon "weather-windy-symbolic"}
                 ;; one row, not two: they are always read together
                 {:title (t :detail/daylight)
                  :value (str (hh:mm (first (:sunrise daily))) "  \u2192  "
                              (hh:mm (first (:sunset daily))))
                  :icon "daytime-sunrise-symbolic"}]
      :fetched-at fetched-at})))

(defn stale-label
  "How old the shown data is, for the offline banner. nil when it is fresh."
  [fetched-at now-ms]
  (when fetched-at
    (let [mins (quot (- now-ms fetched-at) 60000)]
      (when (>= mins 30)
        (cond
          (< mins 120)  (t :stale/minutes mins)
          (< mins 2880) (t :stale/hours (quot mins 60))
          :else         (t :stale/days (quot mins 1440)))))))

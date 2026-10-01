(ns weather-css
  "The stylesheet. Kept apart from the layout because it is the whole reason
   this app looks designed rather than assembled.

   Every sky palette is defined here up front. Changing conditions only swaps a
   class on the hero -- it never rebuilds a CSS provider, which matters because
   `load-css!` stacks providers rather than replacing them.")

(def ^:private skies
  "Palette per condition. Dark enough at the top that white text always reads,
   lighter towards the bottom so the hero has some depth."
  {"sky-clear-day"     ["#1c5a9e" "#4f95d4"]
   "sky-clear-night"   ["#0d1430" "#2a1c46"]
   "sky-few-day"       ["#2a6ba8" "#7f9fbc"]
   "sky-few-night"     ["#121a33" "#2c2745"]
   "sky-overcast-day"  ["#4a5a6b" "#7d8b99"]
   "sky-overcast-night"["#171e2a" "#2f3644"]
   "sky-fog-day"       ["#5d6874" "#8d97a1"]
   "sky-fog-night"     ["#1d222a" "#383d46"]
   "sky-rain-day"      ["#31485c" "#5f7689"]
   "sky-rain-night"    ["#101720" "#26303c"]
   "sky-snow-day"      ["#4e6c88" "#8aa5bd"]
   "sky-snow-night"    ["#232d3c" "#434f5f"]
   "sky-storm-day"     ["#232b36" "#443653"]
   "sky-storm-night"   ["#080b10" "#1e1729"]})

(defn- sky-rules []
  (apply str
         (for [[cls [from to]] (sort skies)]
           (str "." cls " { background-image: linear-gradient(160deg, "
                from " 0%, " to " 100%); }\n"))))

(def css
  (str "
/* --- the hero ------------------------------------------------------------ */
.hero            { color: #ffffff; }
.hero .temp      { font-size: 84px; font-weight: 200; color: #ffffff;
                   margin: 0px; padding: 0px; }
.hero .condition { font-size: 19px; font-weight: 500; color: #ffffff; }
.hero .sub       { font-size: 14px; font-weight: 500;
                   color: alpha(#ffffff, 0.88);
                   /* slack above and below the glyphs: if anything ever
                      squeezes the hero, GTK4 clips the child rather than
                      overflowing, and this padding is what gets eaten
                      instead of the tops of the letters */
                   padding: 2px 0px; }
.hero .place     { font-size: 13px; font-weight: 700; color: alpha(#ffffff, 0.85);
                   letter-spacing: 1px; }

/* --- the hourly strip ---------------------------------------------------- */
.hourly          { color: #ffffff; }
.hour            { padding: 10px 14px; border-radius: 12px; }
.hour.now        { background: alpha(#ffffff, 0.16); }
.hour .h-time    { font-size: 11px; color: alpha(#ffffff, 0.7); }
.hour .h-temp    { font-size: 15px; font-weight: 600; color: #ffffff; }
.hour .h-pop     { font-size: 10px; color: alpha(#7fc4ff, 0.95); }

/* --- the day list ------------------------------------------------------- */
.dayrow .d-hi    { font-size: 15px; font-weight: 600; }
.dayrow .d-lo    { font-size: 15px; }
.pop             { font-size: 12px; color: #4a9eff; }

/* --- the sky palettes --------------------------------------------------- */
"
       (sky-rules)))

;; The .desktop installer. Writes into a temp XDG_DATA_HOME, so this never
;; touches the real ~/.local/share.
(require '[babashka.fs :as fs] '[clojure.string :as str] '[desktop])

(def tmp (str (fs/create-temp-dir)))
(alter-var-root #'desktop/*data-home* (constantly tmp))
(def project (str (fs/absolutize ".")))

;; --- 1. the entry text says what a compositor needs -------------------
(def app (first desktop/apps))
(def entry (desktop/desktop-entry app "/some/project"))
(println "1) entry for" (:id app))
(doseq [k ["[Desktop Entry]" "Type=Application" "Name=Weather"
           "Icon=cz.brdloush.BbWeather" "Exec=bb weather"
           "Path=/some/project" "StartupWMClass=cz.brdloush.BbWeather"]]
  (assert (str/includes? entry k) (str "missing: " k)))
(println "   all required keys present")
;; the icon name must equal the app_id, or the .desktop cannot find our SVG
(assert (str/includes? entry (str "Icon=" (:id app))))
;; and StartupWMClass must equal it too, for the X11 path
(assert (str/includes? entry (str "StartupWMClass=" (:id app))))
;; Path is what lets a dock launch it from anywhere
(assert (str/includes? entry "Path=/some/project") "no Path -- a dock launch would fail")

;; --- 2. every app is distinct and complete ---------------------------
(def n-apps (count desktop/apps))
(println "2) apps:" (mapv :id desktop/apps))
(assert (>= n-apps 3) "expected at least the three showcase apps")
(assert (= n-apps (count (set (map :id desktop/apps)))) "duplicate app ids")
(assert (= n-apps (count (set (map :task desktop/apps)))) "duplicate tasks")
(assert (every? #(every? (fn [k] (seq (get % k))) [:id :name :task :comment :categories])
                desktop/apps))
;; every app_id needs an icon committed in the repo -- an SVG or, where the
;; mark is drawn rather than hand-written, a PNG
(doseq [app desktop/apps]
  (assert (desktop/icon-source app project) (str "no icon for " (:id app))))
(println "   every app has an icon in icons/:"
         (mapv #(fs/file-name (desktop/icon-source % project)) desktop/apps))

;; --- 3. install writes exactly two files per app ----------------------
(def written (desktop/install! project))
(println "3) installed" (count written) "apps into the temp data home")
(assert (= n-apps (count written)))
(doseq [{:keys [desktop icon]} written]
  (assert (fs/exists? desktop) (str "no desktop file: " desktop))
  (assert (fs/exists? icon) (str "no icon: " icon))
  (assert (str/starts-with? desktop tmp) "wrote outside the temp data home!")
  (assert (str/starts-with? icon tmp) "wrote outside the temp data home!"))
(println "   " (mapv #(fs/file-name (:desktop %)) written))

;; the installed icons are the real artwork, not empty files, and each one is
;; in the hicolor directory its format belongs in: scalable for an SVG, a
;; named pixel size for a PNG. Put an SVG under 512x512 and the shell scales
;; it as if it were 512 wide.
(doseq [{:keys [icon]} written]
  (assert (> (fs/size icon) 300) (str "icon suspiciously small: " icon))
  (case (fs/extension icon)
    "svg" (do (assert (str/includes? (slurp icon) "<svg") "not an SVG")
              (assert (str/includes? icon "/scalable/apps/") icon))
    ;; the signature as bytes: slurp mangles the leading 0x89
    "png" (do (assert (= [-119 80 78 71] (take 4 (fs/read-all-bytes icon)))
                      "not a PNG")
              (assert (str/includes? icon "/512x512/apps/") icon))
    (assert false (str "unexpected icon format: " icon))))
(println "   installed icons:" (mapv #(str (fs/file-name (fs/parent (fs/parent (:icon %))))
                                           "/" (fs/file-name (:icon %)))
                                     written))

;; the installed entry points at the project we passed
(assert (str/includes? (slurp (:desktop (first written))) (str "Path=" project)))
(println "   Path points at the project root")

;; --- 4. installing twice is safe -------------------------------------
(def again (desktop/install! project))
(println "4) re-install ok:" (= (map :desktop written) (map :desktop again)))
(assert (= (map :desktop written) (map :desktop again)))

;; --- 4b. replacing the artwork's format removes the old file ----------
;; The theme lookup matches on the name only, so an icon left behind in the
;; other format would keep being found. Leave a stale PNG beside an app whose
;; artwork is an SVG, and the install must clear it.
(def svg-app (first desktop/apps))
(def stale (desktop/icon-path svg-app "png"))
(fs/create-dirs (fs/parent stale))
(spit stale "not really a png")
(desktop/install! project)
(println "4b) stale" (fs/file-name stale) "still there:" (fs/exists? stale))
(assert (not (fs/exists? stale)) "an icon in the superseded format must go")
(assert (fs/exists? (desktop/icon-path svg-app "svg")) "the new icon must be there")

;; --- 5. uninstall removes exactly those, and is safe to repeat -------
(def removed (desktop/uninstall!))
(println "5) removed" (count removed) "files")
(assert (= (* 2 n-apps) (count removed))
        "should remove one desktop file and one icon per app")
(doseq [{:keys [desktop icon]} written]
  (assert (not (fs/exists? desktop)))
  (assert (not (fs/exists? icon))))
(assert (empty? (desktop/uninstall!)) "a second uninstall should be a no-op")
(println "   second uninstall is a no-op")

;; --- 6. a missing icon fails loudly rather than writing half of it ----
(println "6) install from a directory with no icons/ ->"
         (try (desktop/install! (str (fs/create-temp-dir))) :NO-ERROR
              (catch Exception e (ex-message e))))
(assert (= :threw (try (desktop/install! (str (fs/create-temp-dir))) :no-error
                       (catch Exception _ :threw))))
(println "ALL OK")

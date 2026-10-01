(ns desktop
  "Installs .desktop files and icons so the examples look like real
   applications in the switcher, the dock and the app grid.

   Why this is needed at all: `:app-id` on `ui/run` sets the Wayland app_id at
   runtime, which fixes the *name*. The **icon** cannot be set at runtime on
   GNOME -- its switcher shows the icon of the application it matched the window
   to, and ignores what the window asks for, protocol support or not. Tested
   both ways: install the file and the icon appears, remove it and the generic
   one comes straight back.

   Everything here is opt-in and reversible:

     bb install-desktop      writes to ~/.local/share
     bb uninstall-desktop    removes exactly what it wrote"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def ^:dynamic *data-home*
  "XDG data root. Dynamic so tests write to a temp directory."
  (or (System/getenv "XDG_DATA_HOME")
      (str (System/getProperty "user.home") "/.local/share")))

(def apps
  "One entry per example. :id is both the app_id and the icon name, so the
   .desktop file, the SVG and the running window all agree."
  [{:id "cz.brdloush.BbWeather" :name "Weather" :task "weather"
    :comment "Forecast from Open-Meteo, in babashka"
    :categories "Utility;Science;"}
   {:id "cz.brdloush.BbMonitor" :name "System Monitor" :task "monitor"
    :comment "Live system load from /proc, in babashka"
    :categories "Utility;System;Monitor;"}
   {:id "cz.brdloush.BbDeck" :name "Deck" :task "deck"
    :comment "Present a markdown file, in babashka"
    :categories "Office;Presentation;"}])

(defn- applications-dir [] (str *data-home* "/applications"))

(defn- icons-dir
  "Which hicolor directory an icon belongs in. An SVG is scalable; a raster
   icon has to name its own size, and ours are drawn at 512."
  [ext]
  (str *data-home* "/icons/hicolor/"
       (if (= "svg" ext) "scalable" "512x512")
       "/apps"))

(def ^:private icon-exts
  "Preferred first. An SVG scales to any size the shell asks for, so it wins
   when an app has both."
  ["svg" "png"])

(defn icon-source
  "The icon committed in the repo for `app`, or nil. `dir` is the project root.

   Looking the file up rather than naming it in `apps` means dropping a
   replacement PNG next to the SVG is the whole change."
  [{:keys [id]} dir]
  (first (for [ext icon-exts
               :let [f (str dir "/icons/" id "." ext)]
               :when (fs/exists? f)]
           f)))

(defn desktop-path [{:keys [id]}] (str (applications-dir) "/" id ".desktop"))

(defn icon-path
  "Where an icon lands. The extension follows the source file, because the
   theme lookup matches on the name only -- \"Icon=cz.brdloush.BbWeather\"
   finds either -- but the directory has to match the format."
  ([app] (icon-path app "svg"))
  ([{:keys [id]} ext] (str (icons-dir ext) "/" id "." ext)))

(defn desktop-entry
  "The file contents. `dir` is the project root: it becomes Path=, so launching
   from a dock works regardless of the caller's working directory.

   StartupWMClass repeats the app_id, which is what the X11 side matches on."
  [{:keys [id name task comment categories]} dir]
  (str "[Desktop Entry]\n"
       "Type=Application\n"
       "Name=" name "\n"
       "Comment=" comment "\n"
       "Icon=" id "\n"
       "Exec=bb " task "\n"
       "Path=" dir "\n"
       "Terminal=false\n"
       "Categories=" categories "\n"
       "StartupWMClass=" id "\n"))

(defn- refresh-caches!
  "Best effort. Neither tool is required for the icon to resolve, and both are
   missing on plenty of systems, so a failure here is not worth reporting."
  []
  (doseq [cmd [["update-desktop-database" (applications-dir)]
               ["gtk-update-icon-cache" "-qtf" (str *data-home* "/icons/hicolor")]]]
    (try (p/sh cmd) (catch Exception _ nil))))

(defn install!
  "Writes one .desktop file and one icon per app. `dir` is the project root,
   which must contain icons/. Returns the paths written."
  ;; normalize, or "." leaves a trailing "/." in Path= -- harmless but it looks
  ;; like a mistake in a file people read
  ([] (install! (str (fs/normalize (fs/absolutize ".")))))
  ([dir]
   (fs/create-dirs (applications-dir))
   (let [written
         (vec (for [app apps
                    :let [src (icon-source app dir)]]
                (do
                  (when-not src
                    (throw (ex-info "icon missing -- run this from the project root"
                                    {:expected (str dir "/icons/" (:id app)
                                                    ".{" (str/join "," icon-exts) "}")})))
                  (let [dst (icon-path app (fs/extension src))]
                    ;; an icon of the same name in another format, left by an
                    ;; earlier install, would still be found -- and a scalable
                    ;; SVG beats a 512px PNG in the theme lookup, so replacing
                    ;; the artwork has to delete the one it replaces
                    (doseq [stale (map #(icon-path app %) icon-exts)
                            :when (and (not= stale dst) (fs/exists? stale))]
                      (fs/delete stale))
                    (fs/create-dirs (fs/parent dst))
                    (fs/copy src dst {:replace-existing true})
                    (spit (desktop-path app) (desktop-entry app dir))
                    {:desktop (desktop-path app) :icon dst}))))]
     (refresh-caches!)
     written)))

(defn uninstall!
  "Removes exactly what install! wrote, and nothing else."
  []
  (let [removed (vec (for [app apps
                           f (cons (desktop-path app)
                                   (map #(icon-path app %) icon-exts))
                           :when (fs/exists? f)]
                       (do (fs/delete f) f)))]
    (refresh-caches!)
    removed))

(defn -main [& [cmd]]
  (case (or cmd "install")
    "install"
    (let [written (install!)]
      (println "Installed" (count written) "applications:")
      (doseq [{:keys [desktop]} written] (println "  " desktop))
      (println)
      (println "Look for them in the app grid, or start one and check the switcher.")
      (println "If an icon is missing, GNOME has not rescanned yet -- it picks new")
      (println "entries up on its own, but not always instantly.")
      (println "Undo with: bb uninstall-desktop"))

    "uninstall"
    (let [removed (uninstall!)]
      (if (seq removed)
        (do (println "Removed" (count removed) "files:")
            (doseq [f removed] (println "  " f)))
        (println "Nothing installed.")))

    (do (println "usage: bb install-desktop | bb uninstall-desktop")
        (System/exit 1))))

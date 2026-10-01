(ns gtkiccup.dev
  "Development helpers. None of this is needed to run an app -- it exists to
   shorten the edit/see loop.

   Three ways to get a redefined view onto the screen. Pick one; they compose.

     (dev/auto-refresh!)      ; re-render on a timer. no rules, catches everything
     (dev/watch-ns! 'todo)    ; re-render when any fn in the ns is redefined
     (dev/defview home [st]   ; same, but armed at definition time
       [:vbox {} ...])

   And one that needs no REPL at all:

     (dev/watch-files! \"src\" \"examples\")   ; save in your editor, window updates

   And, for documentation, the app can shoot its own picture:

     (dev/screenshot! \"docs/app.png\")

   (dev/status) shows what is active. (dev/stop!) shuts it all down."
  (:require [babashka.ffi :as ffi]
            [babashka.fs :as fs]
            [gtkiccup.core :as ui]
            [gtkiccup.ffi :as g]))

;; ---------------------------------------------------------------------------
;; var watching
;; ---------------------------------------------------------------------------

(defonce ^:private watched-vars (atom #{}))

(defn watch-var!
  "Re-renders whenever v is redefined. Idempotent: re-registering under the
   same key replaces the previous watch instead of stacking up."
  [v]
  (add-watch v ::dev (fn [_ _ _ _] (ui/refresh!)))
  (swap! watched-vars conj v)
  v)

(defn unwatch-var! [v]
  (remove-watch v ::dev)
  (swap! watched-vars disj v)
  v)

(defn- fn-vars
  "The vars in ns whose value is a fn. bb's ns-interns yields values rather
   than vars, so each var is looked up by name."
  [ns]
  (->> (keys (ns-interns ns))
       (keep #(ns-resolve ns %))
       (filter #(fn? @%))))

(defn watch-ns!
  "Watches every fn currently interned in ns. Returns how many.

   A var defined *later* is not covered, because there was nothing to watch
   when this ran. Re-run it after adding a fn, or use `defview`, or use
   `auto-refresh!` which needs no registration at all."
  [ns]
  (let [vs (fn-vars ns)]
    (run! watch-var! vs)
    (count vs)))

(defn unwatch-all! []
  (run! unwatch-var! @watched-vars)
  nil)

(defmacro defview
  "Like defn, but the var re-renders the UI when redefined.

   A redef fires the watch armed by the *previous* definition, then this one
   re-arms under the same key. So the first eval arms it, and every later eval
   both refreshes and re-arms."
  [name & body]
  `(do (defn ~name ~@body)
       (watch-var! (var ~name))
       (var ~name)))

;; ---------------------------------------------------------------------------
;; auto refresh
;; ---------------------------------------------------------------------------

(defonce ^:private auto (atom nil))

(defn auto-refresh!
  "Re-renders on a timer, whether anything changed or not. No registration and
   no rules: it catches a redefined view, a redefined nested component, a whole
   namespace reload, a changed top-level value.

   A no-op re-render of ~90 widgets costs about 0.3 ms, so at the default 100 ms
   this is well under 1% of a core.

   The catch: your view fns now run 10 times a second. Keep them pure -- a
   `println` or a `swap!` inside a view will fire continuously."
  ([] (auto-refresh! 100))
  ([ms]
   (swap! auto (fn [old]
                 (when old (vreset! (:running? old) false))
                 (let [running? (volatile! true)]
                   {:ms ms
                    :running? running?
                    :thread (future
                              (while @running?
                                (ui/refresh!)
                                (Thread/sleep ms)))})))
   ms))

(defn stop-auto-refresh! []
  (swap! auto (fn [old] (when old (vreset! (:running? old) false)) nil))
  nil)

;; ---------------------------------------------------------------------------
;; file watching
;; ---------------------------------------------------------------------------

(defonce ^:private files (atom nil))

(defn- clj-files [paths]
  (sort (mapcat #(map str (fs/glob % "**.clj")) paths)))

(defn- mtimes [paths]
  (into {} (for [f (clj-files paths)]
             [f (str (fs/last-modified-time f))])))

(defn- reload! [path]
  (try
    (load-file path)
    (println "reloaded" path)
    true
    (catch Exception e
      ;; a half-saved file is normal. report and keep watching.
      (println "reload failed" path "-" (ex-message e))
      false)))

(defn watch-files!
  "Polls paths for changed .clj files, reloads them, then re-renders. Lets you
   edit and save with no REPL attached.

   Two things to know. `load-file` re-runs the file's top-level forms, so a
   top-level `(def state (r/atom ...))` is rebuilt on every save -- use `defonce`
   for anything you want to survive, or keep state inside the fn that `run`
   closes over, which is what the todo example does. And keep `ui/run` itself
   out of the top level, or a save will open a second window."
  [& paths]
  (let [paths (or (seq paths) ["src" "examples"])
        ms    300]
    (swap! files
           (fn [old]
             (when old (vreset! (:running? old) false))
             (let [running? (volatile! true)
                   seen     (atom (mtimes paths))]
               {:paths paths
                :running? running?
                :thread
                (future
                  (while @running?
                    (Thread/sleep ms)
                    (let [now     (mtimes paths)
                          ;; forced before `seen` is replaced -- a lazy seq here
                          ;; would compare against the new snapshot and never
                          ;; see a change
                          changed (into [] (for [[f t] now
                                                 :when (not= t (get @seen f))]
                                             f))]
                      (reset! seen now)
                      (when (seq changed)
                        (run! reload! changed)
                        (ui/refresh!)))))})))
    (println "watching" (count (clj-files paths)) "files in" (vec paths))
    nil))

(defn stop-watching-files! []
  (swap! files (fn [old] (when old (vreset! (:running? old) false)) nil))
  nil)

;; ---------------------------------------------------------------------------
;; getting onto the GTK thread
;; ---------------------------------------------------------------------------

;; These moved into gtkiccup.core: an app needs them at runtime, and nothing in
;; a dev namespace should be. Kept here so existing calls keep working.
(def on-gtk-thread? ui/on-gtk-thread?)
(def later! ui/later!)
(def on-gtk-thread! ui/on-gtk-thread!)

;; ---------------------------------------------------------------------------
;; screenshots
;; ---------------------------------------------------------------------------

(defn- shoot!
  "The actual GSK render. Must run on the GTK thread; screenshot! ensures that."
  [path widget scale]
  (let [w (g/widget-get-width widget)
        h (g/widget-get-height widget)]
    (when (or (zero? w) (zero? h))
      (throw (ex-info "widget has no size yet -- is it realized?"
                      {:width w :height h})))
    (let [paintable (g/widget-paintable-new widget)
          snapshot  (g/snapshot-new)]
      ;; Widget sizes are in logical pixels. On a HiDPI screen GTK paints at
      ;; scale x that, so snapshotting at the logical size gives a file at half
      ;; resolution which then looks soft wherever it is viewed. Scaling the
      ;; snapshot first makes the node -- and so the texture -- device sized.
      (when (not= 1 scale)
        (g/snapshot-scale snapshot (float scale) (float scale)))
      (g/paintable-snapshot paintable snapshot (double w) (double h))
      (let [node     (g/snapshot-free-to-node snapshot)
            ;; the renderer belongs to the toplevel, not to the widget, so ask
            ;; for the root -- this is what lets a single widget be shot too
            renderer (g/native-get-renderer (g/widget-get-root widget))
            texture  (g/renderer-render-texture renderer node nil)]
        (when-not (g/<-gbool (g/texture-save-to-png texture path))
          (throw (ex-info "gdk_texture_save_to_png failed" {:path path})))
        {:path path
         :scale scale
         :width (g/texture-get-width texture)
         :height (g/texture-get-height texture)}))))

(defn screenshot!
  "Renders a widget to a PNG. The app draws itself through GSK, so this needs
   no compositor cooperation -- which matters, because GNOME refuses D-Bus
   screenshots from unsandboxed callers.

   With no widget, shoots the running window. Widget calls must happen on the
   GTK thread, so a call from anywhere else is marshalled there and waited on --
   calling this from a worker or the REPL is fine. The window must be realized,
   so give it a moment after `run` starts.

   Renders at the widget's scale factor by default, so a 2x screen gives a file
   with twice the pixels rather than a soft, upscaled one. Pass `scale` to
   override -- 1 for a logical-size file.

   Returns {:path :scale :width :height}, the size being real pixels."
  ([path] (screenshot! path (:window @ui/current)))
  ([path widget] (screenshot! path widget nil))
  ([path widget scale]
   (when-not widget
     (throw (ex-info "nothing to screenshot: no window" {:path path})))
   (on-gtk-thread!
    #(shoot! path widget (or scale (g/widget-get-scale-factor widget))))))

;; ---------------------------------------------------------------------------
;; status / teardown
;; ---------------------------------------------------------------------------

(defn status
  "What dev tooling is currently running, and whether a window is up."
  []
  {:window-open?  (some? @ui/current)
   :watched-vars  (count @watched-vars)
   :auto-refresh  (when-let [a @auto] (str (:ms a) "ms"))
   :watching-files (when-let [f @files] (vec (:paths f)))})

(defn stop!
  "Stops every dev helper. Leaves the window alone -- use ui/close! for that."
  []
  (stop-auto-refresh!)
  (stop-watching-files!)
  (unwatch-all!)
  (status))

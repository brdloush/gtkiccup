(ns gtkiccup.core
  "Hiccup -> GTK4 widgets, reconciled by Replicant.

   A component is a fn of no args returning hiccup:

     [:vbox {:spacing 12}
      [:label \"Count: \" @count]
      [:button {:label \"+1\" :on-click #(swap! count inc)}]]

   State changes mark the tree dirty. On the next main-loop turn the render fn
   runs again, and Replicant diffs the new hiccup against the previous render:
   only the props that changed are pushed into GTK, and a child with a :key
   keeps its widget when it moves.

   The parts live in their own namespaces:

     gtkiccup.widgets   the tag and signal tables, the common props
     gtkiccup.hiccup    validation, and the translation to Replicant's hiccup
     gtkiccup.renderer  Replicant's IRender protocol, for GTK
     gtkiccup.core      this: the window, the main loop, the public API"
  (:require [babashka.ffi :as ffi]
            [gtkiccup.ffi :as g]
            [gtkiccup.hiccup :as h]
            [gtkiccup.ratom :as ra]
            [gtkiccup.renderer :as r]
            [gtkiccup.widgets :as w]))

;; ---------------------------------------------------------------------------
;; the tables, and the app state, re-exported
;; ---------------------------------------------------------------------------

(def ^{:doc "The running app, for REPL poking:
  {:window ptr :tree node :thread id :stop! f :error e}. nil when no window is
  up."} current w/current)

(def ^{:doc "Last thing that blew up, so a REPL can look at it."} last-error
  w/last-error)

(def ^{:doc "tag -> widget spec. See gtkiccup.widgets/widgets."} widgets w/widgets)
(def ^{:doc "prop -> signal spec. See gtkiccup.widgets/signals."} signals w/signals)
(def ^{:doc "Controllers created for :on-key and friends, newest last."}
  controllers w/controllers)

(def register-widget! w/register-widget!)
(def register-signal! w/register-signal!)

(def ^:private normalize h/normalize)
(def ^:private reconcile r/reconcile)

(def ^{:arglists '([x])
       :doc "The GTK widget behind a lifecycle hook's event map, a renderer
  element, or a node of (:tree @current). This is how a :replicant/on-render
  hook gets at the widget it fired for:

    [:label {:replicant/on-render (fn [e] (measure! (ui/widget e)))} ...]"}
  widget r/widget)

(defn set-dispatch!
  "Makes data usable as a handler. With a dispatch fn set,

     [:button {:on-click [:counter/inc 1]} \"+1\"]

   calls (f {:gtkiccup/event :on-click :gtkiccup/args [...]} [:counter/inc 1])
   when clicked. Lifecycle hooks that are data -- :replicant/on-mount and
   friends -- go to the same fn, with Replicant's event map. nil turns it off."
  [f]
  (reset! h/dispatch f)
  nil)

;; ---------------------------------------------------------------------------
;; the GTK thread
;; ---------------------------------------------------------------------------

(defonce ^:private gtk-queue (atom []))

(defn- take-all!
  "Empties the queue atomically and returns what was in it."
  []
  (loop []
    (let [q @gtk-queue]
      (if (empty? q)
        []
        (if (compare-and-set! gtk-queue q [])
          q
          (recur))))))

(defonce ^:private drain-cb
  ;; one callback, reused for every later! call, so nothing accumulates in the
  ;; global arena. Returns 0 = G_SOURCE_REMOVE, so each schedule runs once.
  (delay
    (ffi/callback (ffi/global-arena)
                  (fn [_data]
                    (doseq [f (take-all!)]
                      (try (f)
                           (catch Throwable t
                             (w/report! "later! failed" t))))
                    0)
                  [:pointer] :int)))

(defn on-gtk-thread?
  "True when the calling thread is the one running the main loop -- the only
   thread allowed to touch widgets."
  []
  (= (:thread @current) (.getId (Thread/currentThread))))

(defn later!
  "Runs f on the GTK main-loop thread, soon. Safe to call from any thread.

   This is the piece that lets a worker thread touch GTK at all: do the slow
   work wherever you like, then hand the widget calls back here."
  [f]
  (swap! gtk-queue conj f)
  (g/idle-add @drain-cb nil)
  nil)

(defn on-gtk-thread!
  "Runs f on the GTK thread and returns its value, waiting up to ms.
   Runs inline when already there, so it cannot deadlock against itself."
  ;; 20s, not 5: the wait competes with app startup and with other windows on
  ;; a busy machine, and a timeout here fails a test that is otherwise fine.
  ([f] (on-gtk-thread! f 20000))
  ([f ms]
   (if (on-gtk-thread?)
     (f)
     (let [p (promise)]
       (later! #(deliver p (try {:ok (f)} (catch Throwable t {:err t}))))
       (let [r (deref p ms ::timeout)]
         (cond
           (= ::timeout r) (throw (ex-info "timed out waiting for the GTK thread"
                                           {:ms ms}))
           (:err r)        (throw (:err r))
           :else           (:ok r)))))))

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(def default-window
  "How to make the top-level window and put content in it. `gtk.adw` supplies
   an AdwWindow instead, which has no titlebar of its own -- which is what makes
   an Adw header bar look right."
  {:ctor        g/window-new
   :set-content g/window-set-child})

(def chromeless-window
  "A GtkWindow with no titlebar, for an app that draws its own top edge. The
   plain-GTK4 stand-in for `gtk.adw/window`, so a view that wants an edge-to-edge
   surface does not have to pull libadwaita in."
  {:ctor        (fn [] (doto (g/window-new) (g/window-set-decorated 0)))
   :set-content g/window-set-child})

(defn root-spec [{:keys [set-content]}]
  {:append (fn [win child _props] (set-content win child))
   :remove (fn [win _child _props] (set-content win nil))})

(defn- wake!
  "Interrupts a blocking g_main_context_iteration so the loop can notice a
   dirty flag set from another thread. Safe from any thread -- it is one of the
   few GLib calls that is."
  []
  (g/main-context-wakeup nil))

(defn load-css!
  "Installs CSS for the whole display, so :class has something to attach to.
   Call after the window exists. Later calls stack; a higher priority wins."
  ([css] (load-css! css 800))
  ([css priority]
   (let [p (g/css-provider-new)]
     (g/css-provider-load-from-string p css)
     (g/style-context-add-provider-for-display (g/display-get-default) p priority)
     p)))

(defn refresh!
  "Forces a re-render on the next main-loop turn.

   State changes do this on their own. Redefining a function does not: the
   reactive atoms never saw it. So after re-evaluating a view fn at the REPL,
   call this to make the running window pick it up.

   For that to work the view must be reached through a var, not captured:

     (fn [] (#'home state))   ; re-evaluating home is seen
     (fn [] (home state))     ; the old fn stays captured"
  []
  (ra/invalidate!))

(defn close!
  "Closes the running window and lets its main loop return. Safe from any
   thread: it only flips a flag, and the window is destroyed by the loop
   itself, on the thread GTK expects."
  []
  (when-let [f (:stop! @current)] (f))
  nil)

(defn run
  "Opens a window, renders `component` (a fn returning hiccup) into it and
   drives the GTK main loop until the window is closed.

   `:css` is a stylesheet string, installed with `load-css!` right after
   gtk_init and before the first render. Prefer it to loading CSS from
   `:on-ready`: by then the first render has run, and anything that looked at a
   widget there -- an `:on-render` asking a label for its Pango layout, say --
   saw it unstyled. GtkLabel keeps a layout built that way, default font and
   all, until its text changes.

   `:on-ready` is called once as (f window root-node), after gtk_init and the
   first render but before the window is presented. `:on-render` is called after
   *every* render, for work that needs the widgets to already carry this frame's
   properties -- measuring laid-out text, for instance. It runs on the GTK
   thread inside the render, so it must not be slow and must not dirty the tree.

   `:on-layout` is called as (f window root-node) after every frame GTK paints,
   once that frame's layout is done -- the first point where a widget's size and
   position are real. On the first frame too: `:on-render` and lifecycle hooks
   run before GTK has laid anything out, so anything measured there reads zero.
   It fires only when something is painted, so an idle window stays idle. Keep
   it cheap and idempotent: hovering a button repaints too.

   `:app-id` sets the Wayland app_id, in reverse-DNS form by convention. It is
   what makes the desktop treat this as its own application rather than as
   plain `bb`, and it is the key a compositor uses to find a .desktop file --
   which is the only way to get an icon in GNOME's switcher. `:app-name` is the
   human-readable name shown beside it.

   Blocks. At the REPL, start it on its own thread so the prompt stays free:

     (def app-thread (future (ui/run (app) :title \"todo\")))

   Every GTK call then happens on that thread, which is what GTK requires.
   `refresh!` and `swap!` from the REPL only set a flag, so they are safe."
  [component & {:keys [title width height window css on-ready on-render on-layout
                       app-id app-name]
                :or   {title "babashka + gtk4" width 360 height 200
                       window default-window}}]
  ;; Identity first: GTK derives the Wayland app_id from prgname when it creates
  ;; the surface, and a compositor matches that against a .desktop file. Setting
  ;; it later has no effect.
  (when app-id (g/set-prgname app-id))
  (when app-name (g/set-application-name app-name))
  (g/gtk-init)
  ;; before anything is built, so no widget is ever seen without its style
  (when css (load-css! css))
  (let [root      (root-spec window)
        win       ((:ctor window))
        running   (volatile! true)
        destroyed (volatile! false)
        dirty     (volatile! true)
        tree      (volatile! nil)
        render! (fn []
                  (vreset! dirty false)
                  (try
                    ;; normalize first: it validates the whole tree before
                    ;; reconcile mutates any widget
                    (let [vtree (h/normalize (component))]
                      (vreset! tree (r/reconcile root win @tree vtree))
                      (reset! w/last-error nil)
                      (swap! current assoc :tree @tree :error nil)
                      ;; after the widgets exist and carry this frame's props,
                      ;; so a caller can measure them -- text geometry, say
                      (when on-render (on-render win @tree)))
                    (catch Throwable t
                      ;; keep the old tree and keep pumping GTK, so the window
                      ;; stays alive and the next good render recovers it
                      (w/report! "render failed" t)
                      (swap! current assoc :error @w/last-error))))]
    (reset! current {:window win :tree nil
                     ;; the thread running this loop is the GTK thread: every
                     ;; widget call must happen here. Recorded so helpers can
                     ;; check, or marshal onto it.
                     :thread (.getId (Thread/currentThread))
                     :stop! #(do (vreset! running false) (wake!))})
    (g/window-set-title win title)
    (g/window-set-default-size win width height)
    (let [cb (ffi/callback (ffi/global-arena)
                           (fn [_ _]
                             (vreset! destroyed true)
                             (vreset! running false))
                           [:pointer :pointer] :void)]
      (g/signal-connect-data win "destroy" cb nil nil 0))

    ;; the loop blocks in g_main_context_iteration, so anything that makes the
    ;; UI stale has to wake it as well as set the flag
    (ra/set-invalidate! #(do (vreset! dirty true) (wake!)))
    (render!)
    ;; after gtk_init and the first render, so a caller can keep a pointer to a
    ;; widget it just built
    (when on-ready (on-ready win @tree))
    (g/window-present win)
    ;; Frames, not renders: a render only changes props, and GTK lays the
    ;; result out later, in its own frame. "after-paint" is the first signal
    ;; after layout -- a tick callback runs before it, and still sees zero.
    (when on-layout
      (let [cb (ffi/callback (ffi/global-arena)
                             (fn [_clock _data]
                               (try (on-layout win @tree)
                                    (catch Throwable t (w/report! "on-layout failed" t))))
                             [:pointer :pointer] :void)
            hook! #(g/signal-connect-data (g/widget-get-frame-clock win) "after-paint"
                                          cb nil nil 0)]
        ;; the clock exists once the window is realized, which presenting
        ;; usually does at once; if not yet, the next loop turn will have it
        (if (g/null? (g/widget-get-frame-clock win)) (later! hook!) (hook!))))

    (try
      (while @running
        ;; Block until GTK has something to do. An idle app costs nothing: no
        ;; timer, no polling. A state change on any thread calls wake! and the
        ;; iteration returns immediately.
        (g/main-iteration nil 1)
        ;; then drain anything else already queued, bounded so a busy source
        ;; cannot starve the re-render below
        (loop [i 0]
          (when (and (< i 64) (g/<-gbool (g/main-iteration nil 0)))
            (recur (inc i))))
        (when @dirty (render!)))
      (finally
        ;; whatever happened, do not leave an unpumped window on screen
        (when-not @destroyed
          (g/window-destroy win)
          (loop [i 0] (when (and (< i 64) (g/<-gbool (g/main-iteration nil 0)))
                        (recur (inc i)))))
        (reset! current nil)))
    nil))

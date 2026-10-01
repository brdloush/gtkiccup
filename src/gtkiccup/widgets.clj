(ns gtkiccup.widgets
  "What a tag means in GTK terms: the widget table, the signal table, and the
   props every widget takes. Nothing here knows how a tree is diffed -- that is
   Replicant's job, driven through gtkiccup.renderer.

   Both tables are atoms, so an optional namespace (gtkiccup.adw, say) can add
   tags and events without this one knowing it exists."
  (:require [babashka.ffi :as ffi]
            [gtkiccup.ffi :as g]))

(defonce ^{:doc "The running app, for REPL poking:
  {:window ptr :tree node :thread id :stop! f :error e}. nil when no window is
  up. Single window, so a single atom is enough."}
  current
  (atom nil))

;; ---------------------------------------------------------------------------
;; signals
;; ---------------------------------------------------------------------------

(def ^{:doc "Last thing that blew up, so a REPL can look at it."} last-error
  (atom nil))

(defn report!
  "Prints what went wrong without killing the caller. Deduplicated, because
   with dev/auto-refresh! a broken view would otherwise print 10x a second."
  [what ^Throwable t]
  (let [msg (or (ex-message t) (str t))]
    (when (not= msg (:message @last-error))
      (reset! last-error {:what what :message msg :data (ex-data t)})
      (binding [*out* *err*]
        (println (str "\n[gtk] " what ": " msg))
        (when-let [d (ex-data t)] (println "      " (pr-str d)))))
    nil))

(def signals
  "hiccup prop -> how to wire it up. An atom so an optional namespace can add
   its own.

     :signal    the GTK signal name
     :invoke    (fn [user-fn widget raw-args] ...) -- raw-args is what C passed,
                including the instance and the user-data pointer
     :argtypes  the callback's signature. Default [:pointer :pointer]
     :rettype   default :void. With :int, a truthy handler return becomes 1
     :controller (fn [] ptr) for input that arrives through a
                GtkEventController rather than a signal on the widget
     :attach    :window to add the controller to the toplevel instead of the
                widget, which is what key handling wants"
  (atom
  {:on-click  {:signal "clicked"
               :invoke (fn [f _w _args] (f))}
   :on-change {:signal "changed"
               :invoke (fn [f w _args] (f (g/editable-get-text w)))}
   :on-toggle {:signal "toggled"
               :invoke (fn [f w _args] (f (g/<-gbool (g/check-button-get-active w))))}
   :on-activate {:signal "activate"
                 :invoke (fn [f w _args] (f (g/editable-get-text w)))}

   ;; Fires when a widget is given its width -- including the first time, which
   ;; is the only moment anything measured from a laid-out widget becomes
   ;; available. A render alone is too early: the tree exists but nothing has
   ;; been allocated yet.
   :on-resize {:signal "notify::width"
               :argtypes [:pointer :pointer :pointer]
               :rettype :void
               :invoke (fn [f _w _args] (f))}

   ;; Keys are not a signal on a widget: you make a controller, connect to it,
   ;; and add it to a widget. Added to the toplevel, because a controller on a
   ;; widget that never takes focus would never see anything.
   :on-key    {:signal "key-pressed"
               :controller g/event-controller-key-new
               :attach :window
               :argtypes [:pointer :int :int :int :pointer]
               :rettype :int
               :invoke (fn [f _w [_ctrl keyval _keycode state _data]]
                         (when-let [name (g/keyval-name keyval)]
                           (f (assoc (g/modifiers state)
                                     :key name
                                     ;; nil unless the key actually types
                                     ;; something printable
                                     :char (g/keyval-char keyval)))))}}))

(defonce ^{:doc "Controllers created for :on-key and friends, newest last.
  Kept so tests and a REPL can reach them; nothing in rendering reads this."}
  controllers
  (atom []))

(defn connect!
  "Connects one signal to a stable C callback that reads the current handler out
   of `holder`. The closure captured at creation time therefore never goes stale
   when a later render supplies a new handler fn."
  [widget prop holder]
  (let [{:keys [signal invoke argtypes rettype controller attach]
         :or   {argtypes [:pointer :pointer] rettype :void}} (@signals prop)
        void?   (= :void rettype)
        target  (if controller (controller) widget)
        ;; a handler that returns nothing means "not handled", which for a
        ;; :rettype :int signal like key-pressed has to be 0, not nil
        ->ret   (fn [v] (cond void?      nil
                              (number? v) v
                              v           1
                              :else       0))
        cb (ffi/callback (ffi/global-arena)
                         (fn [& args]
                           ;; never let an exception cross back into C
                           (try
                             (->ret (when-let [f @holder]
                                      (invoke f widget (vec args))))
                             (catch Throwable t
                               (report! (str prop " handler failed") t)
                               (->ret nil))))
                         argtypes rettype)]
    (g/signal-connect-data target signal cb nil nil 0)
    (when controller
      ;; :window because key events go to the toplevel; a controller on a
      ;; widget that never takes focus would never fire
      (let [host (if (= :window attach) (:window @current) widget)]
        ;; Capture, not the default bubble. In the bubble phase the focused
        ;; widget sees the key first, so a focused button would swallow space
        ;; and activate itself -- which in a typing test restarts the whole run.
        (g/event-controller-set-phase target (:capture g/phase))
        (g/widget-add-controller host target)
        (swap! controllers conj {:prop prop :controller target :host host})))
    target))

;; ---------------------------------------------------------------------------
;; common props
;; ---------------------------------------------------------------------------

(defn apply-common!
  "Pushes the common props. A prop that has been *removed* falls back to its
   GTK default rather than to nil, so dropping :sensitive re-enables the widget
   instead of disabling it. CSS classes are not here: Replicant diffs them and
   the renderer adds and removes them one at a time."
  [w props changed]
  (when (contains? changed :sensitive)
    (g/widget-set-sensitive w (g/->gbool (get props :sensitive true))))
  (when (contains? changed :tooltip)
    (g/widget-set-tooltip-text w (:tooltip props)))
  (when (contains? changed :hexpand)
    (g/widget-set-hexpand w (g/->gbool (get props :hexpand false))))
  (when (contains? changed :vexpand)
    (g/widget-set-vexpand w (g/->gbool (get props :vexpand false))))
  (when (contains? changed :margin)
    (let [m (get props :margin 0)]
      (g/widget-set-margin-top w m)
      (g/widget-set-margin-bottom w m)
      (g/widget-set-margin-start w m)
      (g/widget-set-margin-end w m)))
  ;; a size *request* is a minimum, not a fixed size: -1 means "whatever you
  ;; need". Enough to build a bar chart out of plain boxes.
  (when (or (contains? changed :width) (contains? changed :height))
    (g/widget-set-size-request w (int (get props :width -1)) (int (get props :height -1))))
  ;; individual margins win over the uniform one, so :margin can set a base and
  ;; :margin-start override it
  (when (contains? changed :margin-start)
    (g/widget-set-margin-start w (int (get props :margin-start 0))))
  (when (contains? changed :margin-end)
    (g/widget-set-margin-end w (int (get props :margin-end 0))))
  (when (contains? changed :margin-top)
    (g/widget-set-margin-top w (int (get props :margin-top 0))))
  (when (contains? changed :margin-bottom)
    (g/widget-set-margin-bottom w (int (get props :margin-bottom 0))))
  ;; :focusable false keeps a widget out of the Tab chain, so it can never
  ;; steal a keystroke meant for the app
  (when (contains? changed :focusable)
    (g/widget-set-focusable w (g/->gbool (get props :focusable true))))
  (when (contains? changed :halign)
    (g/widget-set-halign w (get g/align (get props :halign :fill) 0)))
  (when (contains? changed :valign)
    (g/widget-set-valign w (get g/align (get props :valign :fill) 0))))

;; ---------------------------------------------------------------------------
;; widget specs
;; ---------------------------------------------------------------------------

(defn- box-spec [orientation]
  {:ctor      (fn [p] (g/box-new orientation (or (:spacing p) 0)))
   :apply     (fn [w p changed]
                (when (contains? changed :spacing)
                  (g/box-set-spacing w (or (:spacing p) 0))))
   :append    (fn [parent child _props] (g/box-append parent child))
   ;; positional insert, so a keyed move is one call instead of re-appending
   ;; every sibling after it. sibling nil = first.
   :insert-after (fn [parent child sibling _props]
                   (g/box-insert-child-after parent child sibling))
   :remove    (fn [parent child _props] (g/box-remove parent child))
   :container true})

(def widgets
  "tag -> widget spec. An atom so an optional namespace -- gtk.adw, say -- can
   register its own tags without core knowing they exist.

   A spec is:
     :ctor       (fn [props] ptr)
     :apply      (fn [widget props changed-key-set] ...)
     :text-prop  which prop string/number children fold into  (optional)
     :append     (fn [parent child-ptr child-props] ...)       (containers only)
     :remove     (fn [parent child-ptr child-props] ...)       (containers only)
     :insert-after (fn [parent child-ptr sibling-ptr-or-nil child-props] ...)
                 (optional) put a child right after a sibling. Without it, an
                 insert in the middle re-appends the children that follow
     :after-children (fn [widget props] ...)  (optional) run once at creation,
                 after the children are in place -- for a container whose props
                 refer to its children, like a carousel's initial page

   `child-props` is there so a container can honour a :slot prop, which is how
   libadwaita's prefix/suffix/top-bar slots are addressed."
  (atom
  {:vbox   (box-spec g/VERTICAL)
   :hbox   (box-spec g/HORIZONTAL)

   ;; A single-child holder that hands the child its whole allocation, so a
   ;; child with :valign :center really does sit in the middle. A one-child
   ;; GtkBox would not: a box gives its child the natural height and no more.
   ;; gtk.adw replaces this with AdwBin, which is the same idea.
   :bin    {:ctor   (fn [_] (g/overlay-new))
            :apply  (fn [_ _ _] nil)
            :append (fn [parent child _props] (g/overlay-set-child parent child))
            :remove (fn [parent _child _props] (g/overlay-set-child parent nil))}

   ;; Keeps content a readable width, centred. Plain GTK4 has no maximum-width
   ;; layout, so :max becomes a width request instead: the child is exactly
   ;; :max wide and centred, which is what AdwClamp does whenever there is room
   ;; for it. The difference is at the bottom end -- a size request is a
   ;; minimum, so the window cannot be resized narrower than :max, where a real
   ;; AdwClamp would let the content shrink. gtk.adw replaces it with the real
   ;; AdwClamp, which is what runs on a GNOME desktop.
   :clamp  (merge (box-spec g/VERTICAL)
                  {:ctor (fn [p]
                           (doto (g/box-new g/VERTICAL 0)
                             (g/widget-set-halign (get g/align :center 0))
                             (g/widget-set-size-request (int (:max p -1)) -1)))
                   :apply (fn [_ _ _] nil)})

   ;; A scrollable region for a single child -- a box, usually. The scrollbar
   ;; policy props are :hscroll/:vscroll with the GtkPolicyType names, default
   ;; never/automatic: content scrolls vertically when taller than the cap and
   ;; never sideways, which is what a list of rows wants. The cap itself is
   ;; CSS (min-height/max-height), as GtkScrolledWindow honors it.
   :scrolled {:ctor   (fn [p]
                        (doto (g/scrolled-window-new)
                          (g/scrolled-window-set-policy
                            (get g/policy (or (:hscroll p) :never) 2)
                            (get g/policy (or (:vscroll p) :automatic) 1))))
              :apply  (fn [_ _ _] nil)
              :append (fn [parent child _props] (g/scrolled-window-set-child parent child))
              :remove (fn [parent _child _props] (g/scrolled-window-set-child parent nil))}

   ;; One child underneath, any number floating on top. A child with
   ;; :slot :over floats; anything else is the content. Overlaid children are
   ;; positioned with :halign/:valign plus margins, which is how you put a
   ;; caret at a measured pixel offset.
   :overlay {:ctor   (fn [_] (g/overlay-new))
             :apply  (fn [_ _ _] nil)
             :append (fn [parent child props]
                       (if (= :over (:slot props :content))
                         (g/overlay-add-overlay parent child)
                         (g/overlay-set-child parent child)))
             :remove (fn [parent child props]
                       (if (= :over (:slot props :content))
                         (g/overlay-remove-overlay parent child)
                         (g/overlay-set-child parent nil)))}

   ;; :icon takes a theme name, :file takes a path -- an SVG works either way.
   ;; GtkImage's pixel-size is an actual size, not a minimum, so it scales a
   ;; high-resolution source down cleanly.
   :icon   {:text-prop :icon
            :ctor  (fn [p]
                     (doto (if (:file p)
                             (g/image-new-from-file (str (:file p)))
                             (g/image-new-from-icon (:icon p)))
                       (g/image-set-pixel-size (int (:size p -1)))))
            :apply (fn [w p changed]
                     (when (contains? changed :file)
                       (g/image-set-from-file w (str (:file p))))
                     (when (and (contains? changed :icon) (not (:file p)))
                       (g/image-set-from-icon w (:icon p)))
                     (when (contains? changed :size)
                       (g/image-set-pixel-size w (int (:size p -1)))))}

   ;; :markup is Pango markup and wins over :label when both are given. Whatever
   ;; builds it must escape first -- see the deck example's `escape`.
   :label  {:text-prop :label
            :ctor  (fn [p]
                     (let [w (g/label-new nil)]
                       (if (:markup p)
                         (g/label-set-markup w (:markup p))
                         (g/label-set-text w (str (:label p ""))))
                       (g/label-set-wrap w (g/->gbool (:wrap p)))
                       (when-let [x (:xalign p)] (g/label-set-xalign w (float x)))
                       w))
            :apply (fn [w p changed]
                     (when (or (contains? changed :markup) (contains? changed :label))
                       (if (:markup p)
                         (g/label-set-markup w (:markup p))
                         (g/label-set-text w (str (:label p "")))))
                     (when (contains? changed :wrap)
                       (g/label-set-wrap w (g/->gbool (:wrap p))))
                     (when (contains? changed :xalign)
                       (g/label-set-xalign w (float (:xalign p 0.5)))))}

   :button {:text-prop :label
            :ctor  (fn [p] (g/button-new-with-label (str (:label p ""))))
            :apply (fn [w p changed]
                     (when (contains? changed :label)
                       (g/button-set-label w (str (:label p "")))))}

   :entry  {:text-prop :value
            :ctor  (fn [p]
                     (doto (g/entry-new)
                       (g/entry-set-placeholder (:placeholder p))
                       (g/editable-set-text (str (:value p "")))))
            :apply (fn [w p changed]
                     (when (contains? changed :placeholder)
                       (g/entry-set-placeholder w (:placeholder p)))
                     ;; only write back when it really differs, so we do not
                     ;; fight the caret while the user types
                     (when (contains? changed :value)
                       (let [v (str (:value p ""))]
                         (when (not= v (g/editable-get-text w))
                           (g/editable-set-text w v)))))}

   :check  {:text-prop :label
            :ctor  (fn [p]
                     (doto (g/check-button-new-with-label (str (:label p "")))
                       (g/check-button-set-active (g/->gbool (:active p)))))
            :apply (fn [w p changed]
                     (when (contains? changed :label)
                       (g/check-button-set-label w (str (:label p ""))))
                     (when (contains? changed :active)
                       (let [v (g/->gbool (:active p))]
                         (when (not= v (g/check-button-get-active w))
                           (g/check-button-set-active w v)))))}}))

(defn register-widget!
  "Adds or replaces a widget spec. This is how an optional namespace extends the
   set of tags without core needing to know about it."
  [tag spec]
  (swap! widgets assoc tag spec)
  tag)

(defn register-signal!
  "Adds an :on-* prop. `signal` is the GTK signal name; `invoke` is
   (fn [user-fn widget] ...) and decides what the handler receives."
  [prop signal invoke]
  (swap! signals assoc prop {:signal signal :invoke invoke})
  prop)

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
    (g/widget-set-valign w (get g/align (get props :valign :fill) 0)))
  ;; :overflow hidden is what lets border-radius clip a picture -- CSS cannot
  ;; do it on its own, so a rounded cover needs this.
  (when (contains? changed :overflow)
    (g/widget-set-overflow w (get g/overflow (get props :overflow :visible) 0)))
  (when (contains? changed :visible)
    (g/widget-set-visible w (g/->gbool (get props :visible true))))
  (when (contains? changed :direction)
    (g/widget-set-direction w (get g/direction (get props :direction :ltr) 0))))

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

(defn- image-texture!
  "Puts the pixels from `path` into `image` as a GdkTexture, and returns the
   image so it composes into a constructor's doto. The image takes its own
   reference, so the one from the loader goes straight away. A file that is not
   there leaves the image empty rather than raising: a missing picture is
   decoration, not a reason to fail to start."
  [image path]
  (when-let [tex (g/texture-file path)]
    (g/image-set-from-paintable image tex)
    (g/object-unref tex))
  image)

(defn- menu-button-props!
  "The look of a menu button in one pass: icon or label (the same slot), the
   frame, the primary look. Called when the widget is built and again whenever
   one of those changes; re-setting all of them is cheaper than tracking which
   moved, and keeps the prop list in one place."
  [w p]
  (g/menu-button-set-has-frame w (g/->gbool (:has-frame p false)))
  (g/menu-button-set-primary w (g/->gbool (:primary p false)))
  (when-let [i (:icon p)]
    (g/menu-button-set-icon-name w i))
  (when-let [l (:label p)]
    (g/menu-button-set-label w (str l)))
  w)

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

   ;; A bar with something at each end and something centred. :slot :start and
   ;; :slot :end pin the sides, anything else (no :slot) is the centred child.
   ;; GtkCenterBox allows one widget per slot, so a second child in a slot
   ;; replaces the first -- a box inside is how you pack more.
   :center-box {:ctor   (fn [_] (g/center-box-new))
                :apply  (fn [_ _ _] nil)
                :append (fn [parent child props]
                          (case (:slot props :center)
                            :start (g/center-box-set-start-widget parent child)
                            :end   (g/center-box-set-end-widget parent child)
                            (g/center-box-set-center-widget parent child)))
                :remove (fn [parent child props]
                          (case (:slot props :center)
                            :start (g/center-box-set-start-widget parent nil)
                            :end   (g/center-box-set-end-widget parent nil)
                            (g/center-box-set-center-widget parent nil)))}

   ;; :icon takes a theme name, :file takes a path -- an SVG works either way.
   ;; GtkImage's pixel-size is an actual size, not a minimum, so it scales a
   ;; high-resolution source down cleanly.
   ;; :icon is a theme name, :file a path -- both go through the same loaders.
   ;; :texture is a bitmap: it is loaded into a GdkTexture and handed to the
   ;; image as a paintable, which is how a cropped screenshot cover shows up
   ;; at any size the layout asks for. The image takes its own reference, so
   ;; the pixels live as long as the widget that shows them.
   :icon   {:text-prop :icon
            :ctor  (fn [p]
                     (doto (cond (:texture p) (image-texture! (g/image-new) (:texture p))
                                 (:file p) (g/image-new-from-file (str (:file p)))
                                 :else   (g/image-new-from-icon (:icon p)))
                       (g/image-set-pixel-size (int (:size p -1)))))
            :apply (fn [w p changed]
                     (when (or (contains? changed :file) (contains? changed :texture))
                       (if-let [t (:texture p)]
                         (image-texture! w t)
                         (g/image-set-from-file w (str (:file p)))))
                     (when (and (contains? changed :icon) (not (:file p)) (not (:texture p)))
                       (g/image-set-from-icon w (:icon p)))
                     (when (contains? changed :size)
                       (g/image-set-pixel-size w (int (:size p -1)))))}

   ;; :markup is Pango markup and wins over :label when both are given. Whatever
   ;; builds it must escape first -- see the deck example's `escape`.
   ;; A label's shape is set by the props that decide how much of a string fits
   ;; and what happens when it does not: :ellipsize (a title is more useful
   ;; truncated than wrapped twice), :lines (a two-line card title),
   ;; :max-width-chars (:width-chars asks for a width, :max-width-chars caps
   ;; one), plus alignment, justification and wrapping.
   :label  {:text-prop :label
            :ctor  (fn [p]
                     (let [w (g/label-new nil)]
                       (if (:markup p)
                         (g/label-set-markup w (:markup p))
                         (g/label-set-text w (str (:label p ""))))
                       (g/label-set-wrap w (g/->gbool (:wrap p)))
                       (when-let [x (:xalign p)] (g/label-set-xalign w (float x)))
                       (when-let [y (:yalign p)] (g/label-set-yalign w (float y)))
                       (g/label-set-ellipsize w (get g/ellipsize (:ellipsize p :none) 0))
                       (g/label-set-lines w (int (:lines p -1)))
                       (g/label-set-max-width-chars w (int (:max-width-chars p -1)))
                       (g/label-set-width-chars w (int (:width-chars p -1)))
                       (g/label-set-justify w (get g/justify (:justify p :left) 0))
                       (g/label-set-wrap-mode w (get g/wrap-mode (:wrap-mode p :none) 0))
                       (g/label-set-selectable w (g/->gbool (:selectable p)))
                       w))
            :apply (fn [w p changed]
                     (when (or (contains? changed :markup) (contains? changed :label))
                       (if (:markup p)
                         (g/label-set-markup w (:markup p))
                         (g/label-set-text w (str (:label p "")))))
                     (when (contains? changed :wrap)
                       (g/label-set-wrap w (g/->gbool (:wrap p))))
                     (when (contains? changed :xalign)
                       (g/label-set-xalign w (float (:xalign p 0.5))))
                     (when (contains? changed :yalign)
                       (g/label-set-yalign w (float (:yalign p 0.5))))
                     (when (contains? changed :ellipsize)
                       (g/label-set-ellipsize w (get g/ellipsize (:ellipsize p :none) 0)))
                     (when (contains? changed :lines)
                       (g/label-set-lines w (int (:lines p -1))))
                     (when (contains? changed :max-width-chars)
                       (g/label-set-max-width-chars w (int (:max-width-chars p -1))))
                     (when (contains? changed :width-chars)
                       (g/label-set-width-chars w (int (:width-chars p -1))))
                     (when (contains? changed :justify)
                       (g/label-set-justify w (get g/justify (:justify p :left) 0)))
                     (when (contains? changed :wrap-mode)
                       (g/label-set-wrap-mode w (get g/wrap-mode (:wrap-mode p :none) 0)))
                     (when (contains? changed :selectable)
                       (g/label-set-selectable w (g/->gbool (:selectable p)))))}

   ;; A button is either a label or one child: an icon with a caption under
   ;; it, a cover with a play badge over it, a row of controls. When it has a
   ;; child the :label prop is not shown -- that is GTK's rule, not ours.
   :button {:text-prop :label
            :ctor  (fn [p]
                     (let [w (g/button-new-with-label (str (:label p "")))]
                       (g/button-set-has-frame w (g/->gbool (:has-frame p true)))
                       ;; an icon is the other way to say what a button is --
                       ;; and must be set on the way in, because :apply is not
                       ;; told about props the widget was built with
                       (when-let [i (:icon p)] (g/button-set-icon-name w i))
                       w))
            :apply (fn [w p changed]
                     (when (contains? changed :label)
                       (g/button-set-label w (str (:label p ""))))
                     (when (contains? changed :icon)
                       (g/button-set-icon-name w (:icon p)))
                     (when (contains? changed :has-frame)
                       (g/button-set-has-frame w (g/->gbool (:has-frame p true)))))
            :append (fn [parent child _props] (g/button-set-child parent child))
            :remove (fn [parent _child _props] (g/button-set-child parent nil))}

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

   ;; A line. Horizontal by default; :orientation :vertical puts it between two
   ;; cells of a row, where the height is what it has to follow.
   :separator {:ctor  (fn [p] (g/separator-new (if (= :vertical (:orientation p)) 1 0)))
               :apply (fn [w p changed]
                        (when (contains? changed :orientation)
                          (g/orientable-set-orientation w
                                                        (if (= :vertical (:orientation p)) 1 0))))}

   ;; A range: the seek bar, the volume slider. Created with min/max/step
   ;; because that is what sets the adjustment's increments. The value is
   ;; written only when it really differs -- the same reason :entry guards it:
   ;; a value that is pushed every frame fights the user's drag.
   ;;
   ;; :on-change-value (the change-value signal) is registered by this
   ;; namespace; returning nothing from it means "not handled", so GTK moves
   ;; the knob itself and the state catches up on the next render.
   ;; GtkScale has no step-less constructor that takes a range:
   ;; gtk_scale_new_with_range asserts step != 0 and hands back NULL, which
   ;; then poisons every later call on the widget. A step of 1 is what the
   ;; keyboard increments would be anyway, and an explicit :step 0 is coerced
   ;; to it rather than crashing the whole render.
   :scale {:ctor  (fn [p]
                    (doto (g/scale-new-with-range (double (:min p 0))
                                                  (double (:max p 1))
                                                  (let [s (double (:step p 1.0))]
                                                    (if (zero? s) 1.0 s)))
                      (g/range-set-value (double (:value p (:min p 0))))
                      (g/scale-set-draw-value (g/->gbool (:draw-value p false)))
                      (g/range-set-inverted (g/->gbool (:inverted p false)))
                      (g/scale-set-orientation (if (= :vertical (:orientation p)) 1 0))))
           :apply (fn [w p changed]
                    (when (or (contains? changed :min) (contains? changed :max))
                      ;; the bounds live on the adjustment, so moving them
                      ;; leaves a drag in progress alone
                      (when-let [a (g/range-get-adjustment w)]
                        (g/adjustment-set-lower a (double (:min p 0)))
                        (g/adjustment-set-upper a (double (:max p 1)))))
                    (when (contains? changed :step)
                      (g/range-set-increments w (double (:step p 0)) 0))
                    (when (contains? changed :draw-value)
                      (g/scale-set-draw-value w (g/->gbool (:draw-value p false))))
                    (when (contains? changed :inverted)
                      (g/range-set-inverted w (g/->gbool (:inverted p false))))
                    (when (contains? changed :digits)
                      (g/scale-set-digits w (int (:digits p 1))))
                    (when (contains? changed :orientation)
                      (g/scale-set-orientation w (if (= :vertical (:orientation p)) 1 0)))
                    ;; last: setting the value is what a drag changes, and it
                    ;; is written only when it really differs -- the same guard
                    ;; :entry uses, so the app and the slider do not fight.
                    ;; Dropping :value falls back to the lower bound.
                    (when (contains? changed :value)
                      (let [v (double (get p :value (:min p 0)))]
                        (when (not= v (g/range-get-value w))
                          (g/range-set-value w v)))))}

   ;; Keeps a child at a fixed ratio, which is what a square cover strip is
   ;; made of. :ratio 1 is a square; :obey-child false lets the frame decide
   ;; the size on its own (the default lets the child's aspect rule).
   :aspect-frame {:ctor   (fn [p]
                            (doto (g/aspect-frame-new (float (:xalign p 0.5))
                                                      (float (:yalign p 0.5))
                                                      (float (:ratio p 1.0))
                                                      (g/->gbool (:obey-child p true)))
                              ))
                  :apply  (fn [w p changed]
                            (when (contains? changed :ratio)
                              (g/aspect-frame-set-ratio w (float (:ratio p 1.0))))
                            (when (contains? changed :obey-child)
                              (g/aspect-frame-set-obey-child w (g/->gbool (:obey-child p true)))))
                  :append (fn [parent child _props] (g/aspect-frame-set-child parent child))
                  :remove (fn [parent _child _props] (g/aspect-frame-set-child parent nil))}

   ;; A button that opens something. Without :menu or :popover it is inert,
   ;; which is what a visual mock wants; the props that show it -- icon, label,
   ;; frame, the primary look -- are the part that has to be right.
   :menu-button {:text-prop :label
                 :ctor   (fn [p] (menu-button-props! (g/menu-button-new) p))
                 :apply  (fn [w p changed]
                           (when (or (changed :icon) (changed :label)
                                     (changed :has-frame) (changed :primary))
                             (menu-button-props! w p)))
                 :append (fn [parent child _props] (g/menu-button-set-child parent child))
                 :remove (fn [parent _child _props] (g/menu-button-set-child parent nil))}

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
   (fn [user-fn widget raw-args] ...) and decides what the handler receives.

   `opts` is optional and carries what the callback's C signature has to be:
   :argtypes (default the two pointers every signal starts with) and :rettype
   (default :void). Signals that pass more than a widget and an event -- the
   change-value of a range, say -- need the right argtypes, because a closure
   reads its arguments by position and cannot guess them."
  ([prop signal invoke] (register-signal! prop signal invoke nil))
  ([prop signal invoke opts]
   (swap! signals assoc prop (merge {:signal signal :invoke invoke} opts))
   prop))

;; The signal that tells the app the user moved a slider. Its arguments are
;; (range, scroll-type, value, data), and returning nothing (which becomes 0,
;; i.e. "not handled") lets GTK move the knob itself -- the state catches up on
;; the next render, so a drag is never fought by the app.
(register-signal!
  :on-change-value "change-value"
  (fn [f _w args] (f (double (nth args 2))))
  {:argtypes [:pointer :int :double :pointer] :rettype :int})

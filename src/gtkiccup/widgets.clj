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
     :target    (fn [widget] ptr) to connect on an object the widget owns
                instead of the widget itself: a text view's buffer emits
                changed, the view does not
     :attach    :window to add the controller to the toplevel instead of the
                widget, which is what key handling wants"
  (atom
  {:on-click  {:signal "clicked"
               :invoke (fn [f _w _args] (f))}
   :on-change {:signal "changed"
               :invoke (fn [f w _args] (f (g/editable-get-text w)))}
   :on-toggle {:signal "toggled"
               :invoke (fn [f w _args] (f (g/<-gbool (g/check-button-get-active w))))}
   :on-selected {:signal "notify::selected"
                 :argtypes [:pointer :pointer :pointer]
                 :invoke (fn [f w _]
                           (let [i (g/drop-down-get-selected w)]
                             (f (when (<= 0 i) i))))}
   :on-active {:signal "notify::active"
               :argtypes [:pointer :pointer :pointer]
               :invoke (fn [f w _] (f (g/<-gbool (g/switch-get-active w))))}
   :on-search-changed {:signal "search-changed"
                       :invoke (fn [f w _] (f (g/editable-get-text w)))}
   :on-editing {:signal "notify::editing"
                :argtypes [:pointer :pointer :pointer]
                :invoke (fn [f w _] (f (g/<-gbool (g/editable-label-get-editing w))))}
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
  ([widget prop holder] (connect! widget prop holder nil))
  ([widget prop holder spec]
  (let [{:keys [signal invoke argtypes rettype controller attach target]
         :or   {argtypes [:pointer :pointer] rettype :void}} (or (get-in spec [:signals prop]) (@signals prop))
        void?   (= :void rettype)
        target  (cond controller (controller) target (target widget) :else widget)
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
                         argtypes rettype)
        handler-id (g/signal-connect-data target signal cb nil nil 0)]
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
    {:target target :handler-id handler-id})))

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
  (when (or (contains? changed :margin) (contains? changed :margin-start))
    (g/widget-set-margin-start w (int (get props :margin-start (get props :margin 0)))))
  (when (or (contains? changed :margin) (contains? changed :margin-end))
    (g/widget-set-margin-end w (int (get props :margin-end (get props :margin 0)))))
  (when (or (contains? changed :margin) (contains? changed :margin-top))
    (g/widget-set-margin-top w (int (get props :margin-top (get props :margin 0)))))
  (when (or (contains? changed :margin) (contains? changed :margin-bottom))
    (g/widget-set-margin-bottom w (int (get props :margin-bottom (get props :margin 0)))))
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

(defn- widget-spec [ctor apply-props]
  {:ctor (fn [p]
           (let [w (ctor)]
             (apply-props w p (set (keys p)))
             w))
   :apply apply-props})

(defn- apply-box! [w p changed]
  (when (contains? changed :spacing)
    (g/box-set-spacing w (or (:spacing p) 0)))
  (when (contains? changed :homogeneous)
    (g/box-set-homogeneous w (g/->gbool (:homogeneous p)))))

(defn- box-spec [orientation apply-props]
  (assoc (widget-spec #(g/box-new orientation 0) apply-props)
         :append (fn [parent child _props] (g/box-append parent child))
         ;; positional insert, so a keyed move is one call instead of re-appending
         ;; every sibling after it. sibling nil = first.
         :insert-after (fn [parent child sibling _props]
                         (g/box-insert-child-after parent child sibling))
         :remove (fn [parent child _props] (g/box-remove parent child))))

(defn- apply-button! [w p changed]
  (when (or (contains? changed :label) (contains? changed :icon))
    (if-let [icon (:icon p)]
      (g/button-set-icon-name w icon)
      (g/button-set-label w (str (:label p "")))))
  (when (contains? changed :has-frame)
    (g/button-set-has-frame w (g/->gbool (get p :has-frame true)))))

(defn- apply-label! [w p changed]
  (when (or (contains? changed :markup) (contains? changed :label))
    (if (:markup p)
      (g/label-set-markup w (:markup p))
      (g/label-set-text w (str (:label p "")))))
  (when (contains? changed :wrap)
    (g/label-set-wrap w (g/->gbool (:wrap p))))
  (when (contains? changed :selectable)
    (g/label-set-selectable w (g/->gbool (:selectable p))))
  (when (contains? changed :ellipsize)
    (g/label-set-ellipsize w (get g/ellipsize-mode (get p :ellipsize :none) 0)))
  (when (contains? changed :max-width-chars)
    (g/label-set-max-width-chars w (int (or (:max-width-chars p) -1))))
  (when (contains? changed :xalign)
    (g/label-set-xalign w (float (:xalign p 0.5)))))

(defn- apply-inscription! [w p changed]
  (when (contains? changed :text)
    (g/inscription-set-text w (str (:text p ""))))
  (when (contains? changed :min-chars)
    (g/inscription-set-min-chars w (int (or (:min-chars p) 3))))
  (when (contains? changed :nat-chars)
    (g/inscription-set-nat-chars w (int (or (:nat-chars p) 0))))
  (when (contains? changed :nat-lines)
    (g/inscription-set-nat-lines w (int (or (:nat-lines p) 0))))
  (when (contains? changed :text-overflow)
    (g/inscription-set-text-overflow w (get g/inscription-overflow (get p :text-overflow :clip) 0)))
  (when (contains? changed :wrap-mode)
    (g/inscription-set-wrap-mode w (get g/pango-wrap-mode (get p :wrap-mode :word-char) 2)))
  (when (contains? changed :xalign)
    (g/inscription-set-xalign w (float (:xalign p 0.0)))))

(defn- apply-image! [w p changed]
  (when (or (contains? changed :file) (contains? changed :icon))
    (if (:file p)
      (g/image-set-from-file w (str (:file p)))
      (g/image-set-from-icon w (:icon p))))
  (when (contains? changed :size)
    (g/image-set-pixel-size w (int (:size p -1)))))

(defn- apply-frame! [w p changed]
  (when (contains? changed :label)
    (g/frame-set-label w (:label p))))

(defn- calendar-date [w]
  (let [date (g/calendar-get-date w)]
    (try
      [(g/date-time-get-year date) (g/date-time-get-month date) (g/date-time-get-day date)]
      (finally (g/date-time-unref date)))))

(defn- apply-calendar! [w p changed]
  (when (contains? changed :date)
    (let [[year month day] (or (:date p)
                               (let [today (java.time.LocalDate/now)]
                                 [(.getYear today) (.getMonthValue today) (.getDayOfMonth today)]))
          date (g/date-time-new-local year month day 12 0 0.0)]
      (when (g/null? date)
        (throw (ex-info "Invalid calendar date" {:date [year month day]})))
      (try
        (g/calendar-select-day w date)
        (finally (g/date-time-unref date))))))

(defn- color-value [w]
  (ffi/read (ffi/reinterpret (g/color-dialog-button-get-rgba w) 16) [:array :float 4]))

(defn- apply-color-button! [w p changed]
  (when (contains? changed :dialog)
    (let [dialog (g/color-dialog-button-get-dialog w)
          options (:dialog p)]
      (g/color-dialog-set-title dialog (or (:title options) "Select a Color"))
      (g/color-dialog-set-with-alpha dialog (g/->gbool (get options :with-alpha true)))))
  (when (contains? changed :rgba)
    (with-open [arena (ffi/confined-arena)]
      (let [rgba (ffi/alloc arena [:array :float 4])]
        (ffi/write rgba [:array :float 4] (mapv float (or (:rgba p) [0.0 0.0 0.0 1.0])))
        (g/color-dialog-button-set-rgba w rgba)))))

(defn- font-value [w]
  (let [description (g/font-dialog-button-get-font-desc w)
        text (g/font-description-to-string description)]
    (try
      (ffi/ptr->string text)
      (finally (g/g-free text)))))

(defn- apply-font-button! [w p changed]
  (when (contains? changed :dialog)
    (g/font-dialog-set-title (g/font-dialog-button-get-dialog w)
                             (or (get-in p [:dialog :title]) "Select a Font")))
  (when (contains? changed :font-desc)
    (let [description (g/font-description-from-string (or (:font-desc p) "Sans 12"))]
      (try
        (g/font-dialog-button-set-font-desc w description)
        (finally (g/font-description-free description))))))

(defn- about-window [w]
  (g/object-get-data w "gtkiccup-about-window"))

(defonce ^:private release-about-window
  (ffi/callback (ffi/global-arena)
                (fn [dialog]
                  (g/window-destroy dialog)
                  (g/object-unref dialog))
                [:pointer] :void))

(defn- apply-about! [w p changed]
  (let [dialog (about-window w)]
    (doseq [[prop setter] [[:program-name g/about-dialog-set-program-name]
                          [:version g/about-dialog-set-version]
                          [:comments g/about-dialog-set-comments]
                          [:website g/about-dialog-set-website]]
            :when (contains? changed prop)]
      (setter dialog (get p prop)))
    (when (contains? changed :authors)
      (let [builder (g/strv-builder-new)]
        (doseq [author (:authors p)] (g/strv-builder-add builder author))
        (let [authors (g/strv-builder-end builder)]
          (g/strv-builder-unref builder)
          (try
            (g/about-dialog-set-authors dialog authors)
            (finally (g/strfreev authors))))))
    (when (contains? changed :license-type)
      (g/about-dialog-set-license-type dialog
                                       (get {:unknown 0 :custom 1 :mit-x11 8}
                                            (get p :license-type :unknown) 0)))
    (when (contains? changed :open)
      (if (:open p)
        (do
          (g/window-set-transient-for dialog (:window @current))
          (g/window-present dialog))
        (g/widget-set-visible dialog 0)))))

(defn- apply-editable! [w p changed]
  ;; only write back when it really differs, so we do not fight the caret
  ;; while the user types
  (when (contains? changed :value)
    (let [value (str (:value p))]
      (when (not= value (g/editable-get-text w))
        (g/editable-set-text w value))))
  (when (contains? changed :editable)
    (g/editable-set-editable w (g/->gbool (get p :editable true)))))

(defn- apply-adjustment! [adjustment p changed]
  ;; bounds and value are independent: configure keeps the current value
  ;; (GTK clamps it into the new range), set-value moves it
  (when (contains? changed :adjustment)
    (let [{:keys [lower upper step-increment page-increment page-size]
           :or {lower 0.0 upper 100.0 step-increment 1.0 page-increment 10.0 page-size 0.0}}
          (:adjustment p)]
      (g/adjustment-configure adjustment (g/adjustment-get-value adjustment)
                              (double lower) (double upper) (double step-increment)
                              (double page-increment) (double page-size))))
  (when (contains? changed :value)
    (g/adjustment-set-value adjustment (double (or (:value p) 0.0)))))

(defn- apply-scrollbar! [w p changed]
  (when (contains? changed :orientation)
    (g/orientable-set-orientation w (get g/orientation (:orientation p) g/HORIZONTAL)))
  (when (contains? changed :value)
    (g/adjustment-set-value (g/scrollbar-get-adjustment w) (double (or (:value p) 0.0)))))

(defn- set-scroll-policy! [w p]
  (g/scrolled-window-set-policy w
                                (g/policy (or (:hscroll p) :never))
                                (g/policy (or (:vscroll p) :automatic))))

(defn- apply-scrolled! [w p changed]
  (when (or (contains? changed :hscroll) (contains? changed :vscroll))
    (set-scroll-policy! w p)))

(defn- apply-spin-button! [w p changed]
  (when (contains? changed :digits)
    (g/spin-button-set-digits w (int (or (:digits p) 0))))
  (when (contains? changed :numeric)
    (g/spin-button-set-numeric w (g/->gbool (:numeric p))))
  (apply-adjustment! (g/spin-button-get-adjustment w) p changed))

(defn- apply-progress-bar! [w p changed]
  (when (contains? changed :fraction)
    (g/progress-bar-set-fraction w (double (:fraction p 0.0))))
  (when (contains? changed :show-text)
    (g/progress-bar-set-show-text w (g/->gbool (:show-text p))))
  (when (contains? changed :text)
    (g/progress-bar-set-text w (:text p))))

(defn- apply-orientation! [w p changed]
  (when (contains? changed :orientation)
    (g/orientable-set-orientation w (get g/orientation (get p :orientation :horizontal) 0))))

(defn- apply-oriented-box! [w p changed]
  (apply-box! w p changed)
  (apply-orientation! w p changed))

(defn- grid-cell
  "[column row column-span row-span] of a grid child, GTK's defaults filled in."
  [p]
  [(int (get p :column 0)) (int (get p :row 0))
   (int (get p :column-span 1)) (int (get p :row-span 1))])

(defn- fixed-point
  "[x y] of a GtkFixed child."
  [p]
  [(double (get p :x 0)) (double (get p :y 0))])

(defn- single-child
  "Append/remove for a container that holds one child through a set-child
   function; removing sets it to nil."
  [set-child!]
  {:append (fn [w child _] (set-child! w child))
   :remove (fn [w _ _] (set-child! w nil))})

(defn- slotted
  "Append/remove for a container whose children pick one of `setters` by their
   :slot prop; a child without one goes to `default`."
  [default setters]
  {:append (fn [w child p] ((setters (:slot p default)) w child))
   :remove (fn [w _ p] ((setters (:slot p default)) w nil))})

(defn packed
  "Append/remove for a bar that packs any number of :start and :end children
   around one :center widget, picked by the child's :slot prop. A child without
   one packs at the start, as GTK's builder does, so a second slotless child
   cannot silently replace the title. `remove!` takes a child out of whichever
   of the three it is in. Public so gtkiccup.adw can give AdwHeaderBar the same
   slots."
  [packers remove!]
  {:append (fn [w child p] ((packers (:slot p :start)) w child))
   :remove (fn [w child _] (remove! w child))})

(defn- apply-scale! [w p changed]
  (apply-orientation! w p changed)
  (when (contains? changed :digits)
    (g/scale-set-digits w (int (or (:digits p) 1))))
  (when (contains? changed :draw-value)
    (g/scale-set-draw-value w (g/->gbool (get p :draw-value true))))
  (when (contains? changed :value-pos)
    (g/scale-set-value-pos w (get g/position (get p :value-pos :top) 2)))
  (when (contains? changed :inverted)
    (g/range-set-inverted w (g/->gbool (:inverted p))))
  (apply-adjustment! (g/range-get-adjustment w) p changed))

(defn- apply-scale-button! [w p changed]
  (when (contains? changed :icons)
    (let [builder (g/strv-builder-new)]
      (doseq [icon (:icons p)] (g/strv-builder-add builder icon))
      (let [icons (g/strv-builder-end builder)]
        (g/strv-builder-unref builder)
        ;; set_icons copies the array, so the strv is ours to free
        (g/scale-button-set-icons w icons)
        (g/strfreev icons))))
  (apply-adjustment! (g/scale-button-get-adjustment w) p changed))

(defn- apply-text-entry! [w p changed]
  (apply-editable! w p changed)
  (when (contains? changed :placeholder)
    (g/object-set-string w "placeholder-text" (:placeholder p) nil)))

(defn- apply-editing! [w p]
  (let [editing? (boolean (:editing p))]
    (when (not= editing? (g/<-gbool (g/editable-label-get-editing w)))
      (if editing?
        (g/editable-label-start-editing w)
        (g/editable-label-stop-editing w 1)))))

(defn- text-view-value [w]
  (g/object-string (g/text-view-get-buffer w) "text"))

(defn- apply-text-view! [w p changed]
  (when (contains? changed :value)
    (let [value (str (:value p))]
      (when (not= value (text-view-value w))
        (g/text-buffer-set-text (g/text-view-get-buffer w) value -1))))
  (when (contains? changed :wrap-mode)
    (g/text-view-set-wrap-mode w (get {:none 0 :char 1 :word 2 :word-char 3} (:wrap-mode p) 0)))
  (when (contains? changed :editable)
    (g/text-view-set-editable w (g/->gbool (get p :editable true))))
  (when (contains? changed :monospace)
    (g/text-view-set-monospace w (g/->gbool (:monospace p))))
  (doseq [[prop setter] [[:left-margin g/text-view-set-left-margin]
                        [:right-margin g/text-view-set-right-margin]
                        [:top-margin g/text-view-set-top-margin]
                        [:bottom-margin g/text-view-set-bottom-margin]]]
    (when (contains? changed prop)
      (setter w (int (get p prop 0))))))

(defn selected-index
  "Selection shared by grouped checks and dropdowns; nil means no selection.
   Missing selection defaults to the first item; out-of-range values clear it."
  [p]
  (let [i (get p :selected 0)]
    (when (and (integer? i) (<= 0 i) (< i (count (:items p))))
      i)))

(defn- apply-dropdown! [w p changed]
  (when (contains? changed :items)
    (let [model (g/string-list-new nil)]
      (doseq [item (:items p)] (g/string-list-append model item))
      (g/drop-down-set-model w model)
      ;; set_model retains its own reference; StringList is not floating.
      (g/object-unref model)))
  ;; GtkDropDown autoselects: with items it always shows one, so "no
  ;; selection" would leave the app believing something GTK does not show.
  (when (or (contains? changed :items) (contains? changed :selected))
    (g/drop-down-set-selected w (or (selected-index p) 0))))

(defn- select-list-row! [w p]
  ;; GTK answers an out-of-range index with NULL, so only nil needs catching
  (let [index (:selected p)
        row (when (integer? index) (g/list-box-get-row-at-index w index))]
    (if (and row (not (g/null? row)))
      (g/list-box-select-row w row)
      (g/list-box-unselect-all w))))

(defn- apply-list-box! [w p changed]
  (when (contains? changed :selection-mode)
    (g/list-box-set-selection-mode w
      (get g/selection-mode (get p :selection-mode :single) 1)))
  (when (contains? changed :show-separators)
    (g/list-box-set-show-separators w (g/->gbool (:show-separators p)))))

(defn- apply-list-row! [w p changed]
  (when (contains? changed :selectable)
    (g/list-box-row-set-selectable w (g/->gbool (get p :selectable true))))
  (when (contains? changed :activatable)
    (g/list-box-row-set-activatable w (g/->gbool (get p :activatable true)))))

(defn- select-flow-children! [w p]
  ;; :selected is one index in :single and :browse, a collection in :multiple.
  ;; GTK answers an out-of-range index with NULL, so only nil needs catching.
  (let [selected (:selected p)
        indices (cond (integer? selected) [selected]
                      (coll? selected) selected
                      :else [])]
    (g/flow-box-unselect-all w)
    (doseq [index indices
            :let [child (g/flow-box-get-child-at-index w (int index))]
            :when (not (g/null? child))]
      (g/flow-box-select-child w child))))

(defn- selected-flow-indices [w]
  (loop [index 0 selected []]
    (let [child (g/flow-box-get-child-at-index w index)]
      (if (g/null? child)
        selected
        (recur (inc index)
               (if (g/<-gbool (g/flow-box-child-is-selected child))
                 (conj selected index)
                 selected))))))

(defn- report-flow-selection [w]
  ;; the handler gets what :selected takes: every index in :multiple, else one
  (let [indices (selected-flow-indices w)]
    (if (= (g/selection-mode :multiple) (g/flow-box-get-selection-mode w))
      indices
      (first indices))))

(defn- apply-flow-box! [w p changed]
  (when (contains? changed :selection-mode)
    (g/flow-box-set-selection-mode w (get g/selection-mode (get p :selection-mode :single) 1)))
  (when (contains? changed :min-children-per-line)
    (g/flow-box-set-min-children-per-line w (int (get p :min-children-per-line 0))))
  (when (contains? changed :max-children-per-line)
    (g/flow-box-set-max-children-per-line w (int (get p :max-children-per-line 7))))
  (when (contains? changed :row-spacing)
    (g/flow-box-set-row-spacing w (int (get p :row-spacing 0))))
  (when (contains? changed :column-spacing)
    (g/flow-box-set-column-spacing w (int (get p :column-spacing 0))))
  (when (contains? changed :homogeneous)
    (g/flow-box-set-homogeneous w (g/->gbool (:homogeneous p)))))

(defn- stack-navigation-spec
  "A spec for :stack-switcher and :stack-sidebar. GTK wires either to its stack
   through a pointer, and hiccup has no way to point at a sibling, so the tag is
   a box that holds the switcher (or sidebar) first and takes the stack as its
   only child. `:append` and `:remove` find the switcher as the box's first
   child, which is why the ctor must add it before anything else."
  [orientation ctor set-stack!]
  {:ctor (fn [p]
           (let [w (g/box-new orientation 0)]
             (apply-box! w p (set (keys p)))
             (g/box-append w (ctor))
             w))
   :apply apply-box!
   :append (fn [w child _]
             (g/box-append w child)
             (set-stack! (g/widget-get-first-child w) child))
   :remove (fn [w child _]
             (set-stack! (g/widget-get-first-child w) nil)
             (g/box-remove w child))})

(defn- apply-stack! [w p changed]
  (when (contains? changed :transition-type)
    (g/stack-set-transition-type w (g/stack-transition (get p :transition-type :none))))
  (when (contains? changed :transition-duration)
    (g/stack-set-transition-duration w (int (get p :transition-duration 200))))
  (when (contains? changed :hhomogeneous)
    (g/stack-set-hhomogeneous w (g/->gbool (get p :hhomogeneous true))))
  (when (contains? changed :vhomogeneous)
    (g/stack-set-vhomogeneous w (g/->gbool (get p :vhomogeneous true)))))

(def widgets
  "tag -> widget spec. An atom so an optional namespace -- gtk.adw, say -- can
   register its own tags without core knowing they exist.

   A spec is:
     :ctor       (fn [props] ptr)
     :apply      (fn [widget props changed-key-set] ...)
     :text-prop  which prop string/number children fold into  (optional)
     :append     (fn [parent child-ptr child-props] ...)       (containers only)
     :remove     (fn [parent child-ptr child-props] ...)       (containers only)
     :child-apply (fn [parent child-ptr child-props changed-key-set] ...)
                 (optional) a child's props changed; for props the parent owns,
                 like a grid cell or an overlay alignment
     :insert-after (fn [parent child-ptr sibling-ptr-or-nil child-props] ...)
                 (optional) put a child right after a sibling. Without it, an
                 insert in the middle re-appends the children that follow
     :on-mount (fn [widget props] ...)  (optional) run once at creation,
                 after the children are in place -- for a container whose props
                 refer to its children, like a carousel's initial page
     :on-render (fn [widget props child-elements] ...)  (optional) run after
                 every render that changed the props or the children -- for a
                 prop the children carry, like a list box's selected row or a
                 radio group's active check. Handlers are silenced meanwhile

   `child-props` is there so a container can honour a :slot prop, which is how
   libadwaita's prefix/suffix/top-bar slots are addressed."
  (atom
  {:vbox   (box-spec g/VERTICAL apply-box!)
   :hbox   (box-spec g/HORIZONTAL apply-box!)
   :box    (box-spec g/HORIZONTAL apply-oriented-box!)

   :header-bar
   (merge (widget-spec g/header-bar-new
            (fn [w p changed]
              (when (contains? changed :show-title-buttons)
                (g/header-bar-set-show-title-buttons w (g/->gbool (get p :show-title-buttons true))))
              (when (contains? changed :decoration-layout)
                (g/header-bar-set-decoration-layout w (:decoration-layout p)))))
          (packed {:start  g/header-bar-pack-start
                   :center g/header-bar-set-title-widget
                   :end    g/header-bar-pack-end}
                  g/header-bar-remove))

   :action-bar
   (merge (widget-spec g/action-bar-new
            (fn [w p changed]
              (when (contains? changed :revealed)
                (g/action-bar-set-revealed w (g/->gbool (get p :revealed true))))))
          (packed {:start  g/action-bar-pack-start
                   :center g/action-bar-set-center-widget
                   :end    g/action-bar-pack-end}
                  g/action-bar-remove))

   :window-controls
   (widget-spec #(g/window-controls-new (g/pack-type :start))
     (fn [w p changed]
       (when (contains? changed :side)
         (g/window-controls-set-side w (g/pack-type (get p :side :start))))
       (when (contains? changed :decoration-layout)
         (g/window-controls-set-decoration-layout w (:decoration-layout p)))))

   :window-handle
   (merge (widget-spec g/window-handle-new (fn [_ _ _]))
          (single-child g/window-handle-set-child))

   :search-bar
   (merge (widget-spec g/search-bar-new
            (fn [w p changed]
              (when (contains? changed :search-mode-enabled)
                (g/search-bar-set-search-mode w (g/->gbool (:search-mode-enabled p))))
              (when (contains? changed :show-close-button)
                (g/search-bar-set-show-close-button w (g/->gbool (:show-close-button p))))))
          (single-child g/search-bar-set-child)
          {:signals {:on-search-mode-enabled
                    {:signal "notify::search-mode-enabled"
                     :argtypes [:pointer :pointer :pointer]
                     :invoke (fn [f w _] (f (g/<-gbool (g/search-bar-get-search-mode w))))}}})

   :notebook
   (assoc (widget-spec g/notebook-new
            (fn [w p changed]
              (when (contains? changed :tab-pos)
                (g/notebook-set-tab-pos w (g/position (get p :tab-pos :top))))))
          :append (fn [w child p]
                    (g/notebook-append-page w child (g/label-new (str (:tab p "")))))
          :remove (fn [w child _]
                    (g/notebook-remove-page w (g/notebook-page-num w child)))
          :child-apply (fn [w child p changed]
                         (when (contains? changed :tab)
                           (g/notebook-set-tab-label-text w child (str (:tab p "")))))
          :on-render (fn [w p kids]
                                (when (seq kids)
                                  (g/notebook-set-current-page w (int (get p :page 0)))))
          :signals {:on-page
                    {:signal "switch-page"
                     :argtypes [:pointer :pointer :int :pointer]
                     :invoke (fn [f _ [_ _ page _]] (f page))}})

   :notebook-page (box-spec g/VERTICAL apply-box!)
   :stack-page (box-spec g/VERTICAL apply-box!)

   :stack
   (assoc (widget-spec g/stack-new apply-stack!)
          :append (fn [w child p]
                    (g/stack-add-titled w child (:name p) (str (:title p ""))))
          :remove (fn [w child _] (g/stack-remove w child))
          :child-apply (fn [w child p changed]
                         (when (some changed [:name :title])
                           (let [page (g/stack-get-page w child)]
                             (when (contains? changed :name)
                               (g/stack-page-set-name page (:name p)))
                             (when (contains? changed :title)
                               (g/stack-page-set-title page (str (:title p "")))))))
          :on-render (fn [w p _]
                                (when-let [name (:visible-child-name p)]
                                  (g/stack-set-visible-child-name w name)))
          :signals {:on-visible-child-name
                    {:signal "notify::visible-child-name"
                     :argtypes [:pointer :pointer :pointer]
                     :invoke (fn [f w _] (f (g/stack-get-visible-child-name w)))}})

   :stack-sidebar
   (stack-navigation-spec g/HORIZONTAL g/stack-sidebar-new g/stack-sidebar-set-stack)

   :stack-switcher
   (stack-navigation-spec g/VERTICAL g/stack-switcher-new g/stack-switcher-set-stack)

   :revealer
   (merge (widget-spec g/revealer-new
            (fn [w p changed]
              (when (contains? changed :transition-type)
                (g/revealer-set-transition-type w (g/revealer-transition (get p :transition-type :slide-down))))
              (when (contains? changed :transition-duration)
                (g/revealer-set-transition-duration w (int (get p :transition-duration 250))))
              (when (contains? changed :reveal-child)
                (g/revealer-set-reveal-child w (g/->gbool (:reveal-child p))))))
          (single-child g/revealer-set-child))

   :expander
   (merge (widget-spec #(g/expander-new nil)
            (fn [w p changed]
              (when (contains? changed :label)
                (g/expander-set-label w (str (:label p ""))))
              (when (contains? changed :expanded)
                (g/expander-set-expanded w (g/->gbool (:expanded p))))))
          (single-child g/expander-set-child)
          {:signals {:on-expanded
                    {:signal "notify::expanded"
                     :argtypes [:pointer :pointer :pointer]
                     :invoke (fn [f w _] (f (g/<-gbool (g/expander-get-expanded w))))}}})

   :grid
   (assoc (widget-spec g/grid-new
            (fn [w p changed]
              (when (contains? changed :row-spacing)
                (g/grid-set-row-spacing w (int (get p :row-spacing 0))))
              (when (contains? changed :column-spacing)
                (g/grid-set-column-spacing w (int (get p :column-spacing 0))))
              (when (contains? changed :row-homogeneous)
                (g/grid-set-row-homogeneous w (g/->gbool (:row-homogeneous p))))
              (when (contains? changed :column-homogeneous)
                (g/grid-set-column-homogeneous w (g/->gbool (:column-homogeneous p))))))
          :append (fn [w child p] (apply g/grid-attach w child (grid-cell p)))
          :remove (fn [w child _] (g/grid-remove w child))
          :child-apply
          (fn [w child p changed]
            (when (some changed [:column :row :column-span :row-span])
              (let [cell (g/layout-manager-get-layout-child (g/widget-get-layout-manager w) child)
                    [column row column-span row-span] (grid-cell p)]
                (g/grid-layout-child-set-column cell column)
                (g/grid-layout-child-set-row cell row)
                (g/grid-layout-child-set-column-span cell column-span)
                (g/grid-layout-child-set-row-span cell row-span)))))

   :center-box
   (merge (widget-spec g/center-box-new apply-orientation!)
          (slotted :center {:start  g/center-box-set-start-widget
                            :center g/center-box-set-center-widget
                            :end    g/center-box-set-end-widget}))

   :aspect-frame
   (merge (widget-spec #(g/aspect-frame-new 0.5 0.5 1.0 1)
            (fn [w p changed]
              (when (contains? changed :ratio)
                (g/aspect-frame-set-ratio w (float (get p :ratio 1.0))))
              (when (contains? changed :xalign)
                (g/aspect-frame-set-xalign w (float (get p :xalign 0.5))))
              (when (contains? changed :yalign)
                (g/aspect-frame-set-yalign w (float (get p :yalign 0.5))))
              (when (contains? changed :obey-child)
                (g/aspect-frame-set-obey-child w (g/->gbool (get p :obey-child true))))))
          (single-child g/aspect-frame-set-child))

   :fixed
   (assoc (widget-spec g/fixed-new (fn [_ _ _]))
          :append (fn [w child p] (apply g/fixed-put w child (fixed-point p)))
          :remove (fn [w child _] (g/fixed-remove w child))
          :child-apply (fn [w child p changed]
                         (when (some changed [:x :y])
                           (apply g/fixed-move w child (fixed-point p)))))

   :paned
   (merge (widget-spec #(g/paned-new g/HORIZONTAL)
            (fn [w p changed]
              (apply-orientation! w p changed)
              (when (contains? changed :position)
                (g/paned-set-position w (int (get p :position -1))))
              (when (contains? changed :wide-handle)
                (g/paned-set-wide-handle w (g/->gbool (:wide-handle p))))))
          (slotted :start {:start g/paned-set-start-child
                           :end   g/paned-set-end-child})
          {:signals {:on-position
                     {:signal "notify::position"
                      :argtypes [:pointer :pointer :pointer]
                      :invoke (fn [f w _] (f (g/paned-get-position w)))}}})

   :separator (widget-spec #(g/separator-new g/HORIZONTAL) apply-orientation!)

   :list-box
   (assoc (widget-spec g/list-box-new apply-list-box!)
          :append (fn [parent child _] (g/list-box-append parent child))
          :remove (fn [parent child _] (g/list-box-remove parent child))
          ;; selection lives in :on-render, not :apply: it refers to the
          ;; children, which the renderer attaches after the props
          :on-render (fn [w p _] (select-list-row! w p))
          :signals {:on-selected
                    {:signal "row-selected"
                     :argtypes [:pointer :pointer :pointer]
                     :invoke (fn [f _ [_ row _]]
                               (f (when-not (g/null? row)
                                    (g/list-box-row-get-index row))))}})

   :flow-box
   (assoc (widget-spec g/flow-box-new apply-flow-box!)
          :append (fn [parent child _] (g/flow-box-insert parent child -1))
          :remove (fn [parent child _] (g/flow-box-remove parent child))
          :on-render (fn [w p _] (select-flow-children! w p))
          :signals {:on-selected
                    {:signal "selected-children-changed"
                     :invoke (fn [f w _] (f (report-flow-selection w)))}})

   :flow-box-child
   (merge (widget-spec g/flow-box-child-new (fn [_ _ _]))
          (single-child g/flow-box-child-set-child))

   :list-box-row
   (merge (widget-spec g/list-box-row-new apply-list-row!)
          (single-child g/list-box-row-set-child))

   ;; A single-child holder that hands the child its whole allocation, so a
   ;; child with :valign :center really does sit in the middle. A one-child
   ;; GtkBox would not: a box gives its child the natural height and no more.
   ;; gtk.adw replaces this with AdwBin, which is the same idea.
   :bin    (merge {:ctor  (fn [_] (g/overlay-new))
                   :apply (fn [_ _ _] nil)}
                  (single-child g/overlay-set-child))

   ;; Keeps content a readable width, centred. Plain GTK4 has no maximum-width
   ;; layout, so :max becomes a width request instead: the child is exactly
   ;; :max wide and centred, which is what AdwClamp does whenever there is room
   ;; for it. The difference is at the bottom end -- a size request is a
   ;; minimum, so the window cannot be resized narrower than :max, where a real
   ;; AdwClamp would let the content shrink. gtk.adw replaces it with the real
   ;; AdwClamp, which is what runs on a GNOME desktop.
   :clamp  (merge (box-spec g/VERTICAL apply-box!)
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
   :scrolled
   (merge (widget-spec #(doto (g/scrolled-window-new) (set-scroll-policy! {}))
                        apply-scrolled!)
          (single-child g/scrolled-window-set-child))

   :viewport
   (merge (widget-spec #(g/viewport-new nil nil)
                        (fn [w p changed]
                          (when (contains? changed :scroll-to-focus)
                            (g/viewport-set-scroll-to-focus
                             w (g/->gbool (get p :scroll-to-focus true))))))
          (single-child g/viewport-set-child))

   :scrollbar
   (assoc (widget-spec #(g/scrollbar-new g/HORIZONTAL nil) apply-scrollbar!)
          :signals {:on-value-changed
                    {:signal "value-changed"
                     :target g/scrollbar-get-adjustment
                     :invoke (fn [f w _]
                               (f (g/adjustment-get-value (g/scrollbar-get-adjustment w))))}})

   ;; One child underneath, any number floating on top. A child with
   ;; :slot :over floats; anything else is the content. Overlaid children are
   ;; positioned with :halign/:valign plus margins, which is how you put a
   ;; caret at a measured pixel offset.
   :overlay {:ctor   (fn [_] (g/overlay-new))
             :apply  (fn [_ _ _] nil)
             ;; Alignment only queues an allocate, and GtkOverlay places its
             ;; overlaid children in its own layout pass -- so a child whose
             ;; :halign/:valign changed stays put until the overlay is resized.
             ;; Margins and size requests queue a resize by themselves.
             :child-apply (fn [parent _ _ changed]
                            (when (some changed [:halign :valign])
                              (g/widget-queue-resize parent)))
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
   :icon   (assoc (widget-spec #(g/image-new-from-icon nil) apply-image!)
                  :text-prop :icon)

   :picture
   (assoc (widget-spec g/picture-new
            (fn [w p changed]
              (when (contains? changed :file)
                (g/picture-set-filename w (some-> (:file p) str)))
              (when (contains? changed :content-fit)
                (g/picture-set-content-fit
                 w (get g/content-fit (:content-fit p :contain) 1)))
              (when (contains? changed :can-shrink)
                (g/picture-set-can-shrink w (g/->gbool (get p :can-shrink true))))
              (when (contains? changed :alternative-text)
                (g/picture-set-alternative-text w (:alternative-text p)))))
          :text-prop :file)

   ;; :markup is Pango markup and wins over :label when both are given. Whatever
   ;; builds it must escape first -- see the deck example's `escape`.
   :label  (assoc (widget-spec #(g/label-new nil) apply-label!)
                  :text-prop :label)

   :inscription (assoc (widget-spec #(g/inscription-new nil) apply-inscription!)
                        :text-prop :text)

   :button (assoc (widget-spec #(g/button-new-with-label "") apply-button!)
                  :text-prop :label)

   :toggle (assoc
            (widget-spec g/toggle-button-new
              (fn [w p changed]
                (apply-button! w p changed)
                (when (contains? changed :active)
                  (g/toggle-button-set-active w (g/->gbool (:active p))))))
            :text-prop :label
            :signals {:on-toggle
                      {:signal "toggled"
                       :invoke (fn [f w _]
                                 (f (g/<-gbool (g/toggle-button-get-active w))))}})

   :link (assoc
          (widget-spec #(g/link-button-new "")
            (fn [w p changed]
              (when (contains? changed :uri)
                (g/link-button-set-uri w (or (:uri p) "")))
              (when (contains? changed :label)
                (g/button-set-label w (str (:label p ""))))))
          :text-prop :label)

   :switch (widget-spec g/switch-new
             (fn [w p changed]
               (when (contains? changed :active)
                 (g/switch-set-active w (g/->gbool (:active p))))))

   :dropdown (widget-spec #(g/drop-down-new nil nil) apply-dropdown!)

   :progress-bar (widget-spec g/progress-bar-new apply-progress-bar!)

   ;; gtkiccup.adw replaces this with AdwSpinner, which has no :spinning prop:
   ;; it always spins, so under adw the prop is accepted and ignored.
   :spinner
   (widget-spec g/spinner-new
     (fn [w p changed]
       (when (contains? changed :spinning)
         (g/spinner-set-spinning w (g/->gbool (:spinning p))))))

   :level
   (widget-spec g/level-bar-new
     (fn [w p changed]
       (when (contains? changed :min-value)
         (g/level-bar-set-min-value w (double (:min-value p 0.0))))
       (when (contains? changed :max-value)
         (g/level-bar-set-max-value w (double (:max-value p 1.0))))
       (when (contains? changed :value)
         (g/level-bar-set-value w (double (:value p 0.0))))))

   :spin-button
   (assoc (widget-spec #(g/spin-button-new-with-range 0.0 100.0 1.0) apply-spin-button!)
          ;; a spin button with no decimals is an integer control: hand the
          ;; handler an int so the value can go straight back into an int prop
          :signals {:on-value-changed {:signal "value-changed"
                                      :invoke (fn [f w _]
                                                (f (if (zero? (g/spin-button-get-digits w))
                                                     (g/spin-button-get-value-as-int w)
                                                     (g/spin-button-get-value w))))}})

   :scale
   (assoc (widget-spec #(g/scale-new-with-range g/HORIZONTAL 0.0 100.0 1.0) apply-scale!)
          :signals {:on-value-changed {:signal "value-changed"
                                      :invoke (fn [f w _] (f (g/range-get-value w)))}})

   :scale-button
   (assoc (widget-spec #(g/scale-button-new 0.0 100.0 1.0 nil) apply-scale-button!)
          :signals {:on-value-changed {:signal "value-changed"
                                      :argtypes [:pointer :double :pointer]
                                      :invoke (fn [f w _] (f (g/scale-button-get-value w)))}})

   :calendar
   (assoc (widget-spec g/calendar-new apply-calendar!)
          :signals {:on-date {:signal "day-selected"
                              :invoke (fn [f w _] (f (calendar-date w)))}})

   :color-dialog-button
   (assoc (widget-spec #(g/color-dialog-button-new (g/color-dialog-new)) apply-color-button!)
          :signals {:on-rgba {:signal "notify::rgba"
                              :argtypes [:pointer :pointer :pointer]
                              :invoke (fn [f w _] (f (color-value w)))}})

   :font-dialog-button
   (assoc (widget-spec #(g/font-dialog-button-new (g/font-dialog-new)) apply-font-button!)
          :signals {:on-font-desc {:signal "notify::font-desc"
                                   :argtypes [:pointer :pointer :pointer]
                                   :invoke (fn [f w _] (f (font-value w)))}})

   :menu-button
   (assoc (widget-spec g/menu-button-new
            (fn [w p changed]
              (when (some changed [:icon :label])
                (if-let [icon (:icon p)]
                  (g/menu-button-set-icon-name w icon)
                  (g/menu-button-set-label w (or (:label p) ""))))))
          :text-prop :label
          :append (fn [parent child _] (g/menu-button-set-popover parent child))
          :remove (fn [parent _ _] (g/menu-button-set-popover parent nil)))

   :emoji-chooser
   (assoc (widget-spec g/emoji-chooser-new (fn [_ _ _]))
          :signals {:on-emoji-picked {:signal "emoji-picked"
                                      :argtypes [:pointer :pointer :pointer]
                                      :invoke (fn [f _ [_ text _]] (f (ffi/ptr->string text)))}})

   :about-dialog
   {:ctor (fn [p]
            (let [w (g/box-new g/VERTICAL 0)
                  dialog (g/about-dialog-new)]
              (g/object-ref-sink dialog)
              (g/window-set-modal dialog 1)
              (g/window-set-transient-for dialog (:window @current))
              (g/window-set-destroy-with-parent dialog 1)
              (g/object-set-data-full w "gtkiccup-about-window" dialog release-about-window)
              (apply-about! w p (set (keys p)))
              w))
    :apply apply-about!
    :signals {:on-close {:signal "close-request" :target about-window :rettype :int
                         :invoke (fn [f w _]
                                   (g/widget-set-visible (about-window w) 0)
                                   (f)
                                   true)}}}

   :entry (assoc (widget-spec g/entry-new apply-text-entry!)
                 :text-prop :value)

   :password-entry
   (assoc (widget-spec g/password-entry-new
            (fn [w p changed]
              (apply-text-entry! w p changed)
              (when (contains? changed :show-peek-icon)
                (g/password-entry-set-show-peek-icon w (g/->gbool (:show-peek-icon p))))))
          :text-prop :value)

   :search-entry (assoc (widget-spec g/search-entry-new apply-text-entry!)
                        :text-prop :value)

   :editable-label
   (assoc (widget-spec #(g/editable-label-new "")
            (fn [w p changed]
              (apply-editable! w p changed)
              (when (contains? changed :editing)
                (apply-editing! w p))))
          :text-prop :value)

   :text-view
   (assoc (widget-spec g/text-view-new apply-text-view!)
          :text-prop :value
          :signals {:on-change {:signal "changed"
                                :target g/text-view-get-buffer
                                :invoke (fn [f w _] (f (text-view-value w)))}})

   :frame
   (merge {:ctor  (fn [p] (doto (g/frame-new nil) (apply-frame! p (set (keys p)))))
           :apply apply-frame!}
          (single-child g/frame-set-child))

   :check  {:text-prop :label
            :ctor  (fn [p]
                     (doto (g/check-button-new-with-label (str (:label p "")))
                       (g/check-button-set-active (g/->gbool (:active p)))
                       (g/check-button-set-inconsistent (g/->gbool (:inconsistent p)))))
            :apply (fn [w p changed]
                     (when (contains? changed :label)
                       (g/check-button-set-label w (str (:label p ""))))
                     (when (contains? changed :inconsistent)
                       (g/check-button-set-inconsistent w (g/->gbool (:inconsistent p))))
                     (when (contains? changed :active)
                       (g/check-button-set-active w (g/->gbool (:active p)))))}}))

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

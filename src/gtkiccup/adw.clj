(ns gtkiccup.adw
  "libadwaita widgets, registered into gtkiccup.core. Optional: core does not know
   this namespace exists, and nothing here changes how rendering works.

   Requiring this namespace calls adw_init and adds these tags:

     :adw-window     AdwWindow, the shell. Use with (ui/run ... :window window)
     :toolbar-view   AdwToolbarView -- :slot :top / :bottom for bars
     :header-bar     AdwHeaderBar   -- :slot :start / :end for buttons
     :window-title   AdwWindowTitle, the title/subtitle in a header bar
     :page           AdwPreferencesPage, a scrolling page of groups
     :group          AdwPreferencesGroup, a titled boxed list
     :row            AdwActionRow   -- :slot :prefix / :suffix for widgets
     :toast-overlay  AdwToastOverlay, shows toasts over its child
     :status-page    AdwStatusPage, for empty states
     :bin            AdwBin, a plain single-child holder
     :carousel       AdwCarousel -- :page is an index, and changing it animates
     :revealer       GtkRevealer -- :revealed toggles with a transition
     :clamp          AdwClamp, keeps content a readable width
     :banner         AdwBanner, an inline notice strip
     :spinner       AdwSpinner
     :picture        GtkPicture from a file, SVG included -- :content-fit and
                     :can-shrink say how it sits in the space it is given
     :scroll         GtkScrolledWindow
     :level          GtkLevelBar
     :icon-button    GtkButton from an icon name
     :overlay-split-view  AdwOverlaySplitView -- :slot :sidebar + the main pane
     :view-stack     AdwViewStack, pages keyed by a :name child prop
     :view-switcher  AdwViewSwitcher, bound to a stack by its :ref name

   Slots are a plain `:slot` prop on the child, honoured by the parent's
   :append. A child with no :slot goes to the parent's main content."
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [gtkiccup.core :as ui]
            [gtkiccup.ffi :as g]
            [gtkiccup.refs :as refs]))

(g/load-gtk-library "adwaita-1" 0)

;; ---------------------------------------------------------------------------
;; bindings
;; ---------------------------------------------------------------------------

(defcfn adw-init "adw_init" [] :void)
(defcfn get-major "adw_get_major_version" [] :int)
(defcfn get-minor "adw_get_minor_version" [] :int)

(defcfn window-new "adw_window_new" [] :pointer)
(defcfn window-set-content "adw_window_set_content" [:pointer :pointer] :void)

;; -- application -------------------------------------------------------------
;; The app object itself is GIO, so load it too. There is no g_application_run:
;; gtkiccup keeps pumping its own loop (the recommended integration shape), and
;; the app is created through its GType, because calling adw_application_new
;; through libffi crashes in this build (the crash is inside its variadic
;; g_application_new fallback).
(g/load-gtk-library "gio-2.0" 0)

(defcfn application-new "adw_application_new" [] :pointer)
(defcfn application-get-type "adw_application_get_type" [] :long)
(defcfn application-object-new "g_object_new" [:long :pointer] :pointer)
(defcfn application-set-id "g_application_set_application_id" [:pointer :string] :void)
(defcfn application-get-id "g_application_get_application_id" [:pointer] :string)
(defcfn application-register "g_application_register" [:pointer :pointer :pointer] :int)
(defcfn application-activate "g_application_activate" [:pointer] :void)
(defcfn application-window-new "adw_application_window_new" [:pointer] :pointer)
(defcfn application-window-set-content "adw_application_window_set_content" [:pointer :pointer] :void)

;; -- the sidebar pair, the stack and its switcher ----------------------------
(defcfn split-view-new "adw_overlay_split_view_new" [] :pointer)
(defcfn split-set-sidebar "adw_overlay_split_view_set_sidebar" [:pointer :pointer] :void)
(defcfn split-set-content "adw_overlay_split_view_set_content" [:pointer :pointer] :void)
(defcfn split-get-sidebar "adw_overlay_split_view_get_sidebar" [:pointer] :pointer)
(defcfn split-get-content "adw_overlay_split_view_get_content" [:pointer] :pointer)
(defcfn split-set-show-sidebar "adw_overlay_split_view_set_show_sidebar" [:pointer :int] :void)
(defcfn split-get-show-sidebar "adw_overlay_split_view_get_show_sidebar" [:pointer] :int)
(defcfn split-set-fraction "adw_overlay_split_view_set_sidebar_width_fraction" [:pointer :double] :void)
(defcfn split-get-fraction "adw_overlay_split_view_get_sidebar_width_fraction" [:pointer] :double)
(defcfn split-set-min-width "adw_overlay_split_view_set_min_sidebar_width" [:pointer :double] :void)
(defcfn split-get-min-width "adw_overlay_split_view_get_min_sidebar_width" [:pointer] :double)
(defcfn split-set-max-width "adw_overlay_split_view_set_max_sidebar_width" [:pointer :double] :void)
(defcfn split-get-max-width "adw_overlay_split_view_get_max_sidebar_width" [:pointer] :double)

(defcfn view-stack-new "adw_view_stack_new" [] :pointer)
(defcfn view-stack-add "adw_view_stack_add" [:pointer :pointer] :void)
(defcfn view-stack-add-titled-with-icon "adw_view_stack_add_titled_with_icon"
  [:pointer :pointer :string :string :string] :pointer)
(defcfn view-stack-remove "adw_view_stack_remove" [:pointer :pointer] :void)
(defcfn view-stack-set-visible-name "adw_view_stack_set_visible_child_name" [:pointer :string] :void)
(defcfn view-stack-get-visible-name "adw_view_stack_get_visible_child_name" [:pointer] :string)
(defcfn view-stack-child-by-name "adw_view_stack_get_child_by_name" [:pointer :string] :pointer)
(defcfn view-stack-set-hhomogeneous "adw_view_stack_set_hhomogeneous" [:pointer :int] :void)
(defcfn view-stack-set-vhomogeneous "adw_view_stack_set_vhomogeneous" [:pointer :int] :void)
(defcfn view-stack-get-page "adw_view_stack_get_page" [:pointer :pointer] :pointer)
(defcfn page-get-title "adw_view_stack_page_get_title" [:pointer] :string)
(defcfn page-get-name "adw_view_stack_page_get_name" [:pointer] :string)

(defcfn view-switcher-new "adw_view_switcher_new" [] :pointer)
(defcfn view-switcher-set-stack "adw_view_switcher_set_stack" [:pointer :pointer] :void)
(defcfn view-switcher-get-stack "adw_view_switcher_get_stack" [:pointer] :pointer)
(defcfn view-switcher-set-policy "adw_view_switcher_set_policy" [:pointer :int] :void)
(defcfn view-switcher-get-policy "adw_view_switcher_get_policy" [:pointer] :int)

(def VIEW-SWITCHER-POLICY
  "AdwViewSwitcherPolicy, read out of the typelib (g_enum_info_*). In this
   libadwaita the policy is wide or narrow -- the responsive pair, not the old
   always/never."
  {:narrow 0 :wide 1})

(defcfn bin-new "adw_bin_new" [] :pointer)
(defcfn bin-set-child "adw_bin_set_child" [:pointer :pointer] :void)

(defcfn toolbar-view-new "adw_toolbar_view_new" [] :pointer)
(defcfn toolbar-add-top "adw_toolbar_view_add_top_bar" [:pointer :pointer] :void)
(defcfn toolbar-add-bottom "adw_toolbar_view_add_bottom_bar" [:pointer :pointer] :void)
(defcfn toolbar-remove "adw_toolbar_view_remove" [:pointer :pointer] :void)
(defcfn toolbar-set-content "adw_toolbar_view_set_content" [:pointer :pointer] :void)

(defcfn header-bar-new "adw_header_bar_new" [] :pointer)
(defcfn header-set-title-widget "adw_header_bar_set_title_widget" [:pointer :pointer] :void)
(defcfn header-pack-start "adw_header_bar_pack_start" [:pointer :pointer] :void)
(defcfn header-pack-end "adw_header_bar_pack_end" [:pointer :pointer] :void)
(defcfn header-remove "adw_header_bar_remove" [:pointer :pointer] :void)

(defcfn window-title-new "adw_window_title_new" [:string :string] :pointer)
(defcfn window-title-set-title "adw_window_title_set_title" [:pointer :string] :void)
(defcfn window-title-set-subtitle "adw_window_title_set_subtitle" [:pointer :string] :void)

(defcfn prefs-page-new "adw_preferences_page_new" [] :pointer)
(defcfn prefs-page-add "adw_preferences_page_add" [:pointer :pointer] :void)
(defcfn prefs-page-remove "adw_preferences_page_remove" [:pointer :pointer] :void)

(defcfn prefs-group-new "adw_preferences_group_new" [] :pointer)
(defcfn prefs-group-add "adw_preferences_group_add" [:pointer :pointer] :void)
(defcfn prefs-group-remove "adw_preferences_group_remove" [:pointer :pointer] :void)
(defcfn prefs-group-set-title "adw_preferences_group_set_title" [:pointer :string] :void)
(defcfn prefs-group-set-description "adw_preferences_group_set_description" [:pointer :string] :void)

(defcfn action-row-new "adw_action_row_new" [] :pointer)
(defcfn pref-row-set-title "adw_preferences_row_set_title" [:pointer :string] :void)
(defcfn action-row-set-subtitle "adw_action_row_set_subtitle" [:pointer :string] :void)
(defcfn action-row-add-prefix "adw_action_row_add_prefix" [:pointer :pointer] :void)
(defcfn action-row-add-suffix "adw_action_row_add_suffix" [:pointer :pointer] :void)
(defcfn action-row-remove "adw_action_row_remove" [:pointer :pointer] :void)

(defcfn toast-overlay-new "adw_toast_overlay_new" [] :pointer)
(defcfn toast-overlay-set-child "adw_toast_overlay_set_child" [:pointer :pointer] :void)
(defcfn toast-overlay-add-toast "adw_toast_overlay_add_toast" [:pointer :pointer] :void)
(defcfn toast-new "adw_toast_new" [:string] :pointer)
(defcfn toast-set-timeout "adw_toast_set_timeout" [:pointer :int] :void)

(defcfn status-page-new "adw_status_page_new" [] :pointer)
(defcfn status-set-title "adw_status_page_set_title" [:pointer :string] :void)
(defcfn status-set-description "adw_status_page_set_description" [:pointer :string] :void)
(defcfn status-set-icon-name "adw_status_page_set_icon_name" [:pointer :string] :void)

;; plain GTK4 pieces the Adw layouts need
(defcfn carousel-new "adw_carousel_new" [] :pointer)
(defcfn carousel-append "adw_carousel_append" [:pointer :pointer] :void)
(defcfn carousel-remove "adw_carousel_remove" [:pointer :pointer] :void)
(defcfn carousel-scroll-to "adw_carousel_scroll_to" [:pointer :pointer :int] :void)
(defcfn carousel-get-position "adw_carousel_get_position" [:pointer] :double)
(defcfn carousel-get-n-pages "adw_carousel_get_n_pages" [:pointer] :int)
(defcfn carousel-set-interactive "adw_carousel_set_allow_scroll_wheel" [:pointer :int] :void)
(defcfn carousel-set-mouse-drag "adw_carousel_set_allow_mouse_drag" [:pointer :int] :void)
(defcfn carousel-set-indicator "adw_carousel_set_reveal_duration" [:pointer :int] :void)
(defcfn picture-new "gtk_picture_new" [] :pointer)
(defcfn picture-set-paintable "gtk_picture_set_paintable" [:pointer :pointer] :void)
(defcfn picture-set-content-fit "gtk_picture_set_content_fit" [:pointer :int] :void)
(defcfn revealer-new "gtk_revealer_new" [] :pointer)
(defcfn revealer-set-child "gtk_revealer_set_child" [:pointer :pointer] :void)
(defcfn revealer-set-reveal "gtk_revealer_set_reveal_child" [:pointer :int] :void)
(defcfn revealer-set-transition-duration "gtk_revealer_set_transition_duration" [:pointer :int] :void)
(defcfn widget-get-first-child "gtk_widget_get_first_child" [:pointer] :pointer)
(defcfn widget-get-next-sibling "gtk_widget_get_next_sibling" [:pointer] :pointer)
(defcfn clamp-new "adw_clamp_new" [] :pointer)
(defcfn clamp-set-child "adw_clamp_set_child" [:pointer :pointer] :void)
(defcfn clamp-set-maximum-size "adw_clamp_set_maximum_size" [:pointer :int] :void)
(defcfn banner-new "adw_banner_new" [:string] :pointer)
(defcfn banner-set-title "adw_banner_set_title" [:pointer :string] :void)
(defcfn banner-set-revealed "adw_banner_set_revealed" [:pointer :int] :void)
(defcfn spinner-new "adw_spinner_new" [] :pointer)
(defcfn scroll-new "gtk_scrolled_window_new" [] :pointer)
(defcfn scroll-set-policy "gtk_scrolled_window_set_policy" [:pointer :int :int] :void)
(defcfn scroll-set-child "gtk_scrolled_window_set_child" [:pointer :pointer] :void)
(defcfn level-new "gtk_level_bar_new" [] :pointer)
(defcfn level-set-value "gtk_level_bar_set_value" [:pointer :double] :void)
(defcfn level-set-min "gtk_level_bar_set_min_value" [:pointer :double] :void)
(defcfn level-set-max "gtk_level_bar_set_max_value" [:pointer :double] :void)
(defcfn level-set-mode "gtk_level_bar_set_mode" [:pointer :int] :void)
(defcfn button-new-from-icon "gtk_button_new_from_icon_name" [:string] :pointer)
(defcfn button-set-icon-name "gtk_button_set_icon_name" [:pointer :string] :void)
(defcfn widget-set-size-request "gtk_widget_set_size_request" [:pointer :int :int] :void)
(defcfn widget-set-valign "gtk_widget_set_valign" [:pointer :int] :void)

(def ALIGN-CENTER 3)

;; ---------------------------------------------------------------------------
;; the window, for (ui/run ... :window adw/window)
;; ---------------------------------------------------------------------------

(def window
  "Pass as (ui/run view :window adw/window). AdwWindow has no titlebar of its
   own, which is what lets an :header-bar sit flush at the top."
  {:ctor        window-new
   :set-content window-set-content})

(defn application-window
  "Like `window`, but the toplevel is an AdwApplicationWindow under an
   AdwApplication: (ui/run view :window (adw/application-window \"cz.brdloush.MyApp\")).

   The app is registered and activated -- startup is emitted, which is what the
   windows need before they can be added -- but never `g_application_run`:
   gtkiccup keeps pumping its own g_main_context_iteration loop, so later!,
   wake! and the REPL story are unchanged. Having the app is what makes the
   window a real application window; actions, accelerators and single-instance
   handling can hook into it later. `app-id` may be nil, for a local app that
   only parents windows.

   The activate/startup handlers are inert callbacks: the window is built by
   run's loop, not by an activate handler, but connecting something stops
   GLib's \"application implements no activate\" warning."
  ([app-id]
   (let [noop   (delay
                  (ffi/callback (ffi/global-arena)
                                (fn [_ _] 0)
                                [:pointer :pointer] :int))
         app    (delay
                  (let [a (application-object-new (application-get-type) ffi/null)]
                    (when app-id
                      (application-set-id a (str app-id)))
                    (application-register a nil nil)
                    (g/signal-connect-data a "activate" @noop nil nil 0)
                    (g/signal-connect-data a "startup" @noop nil nil 0)
                    (g/object-ref-sink a)
                    ;; startup is emitted from here if still pending, so the
                    ;; windows created next land in the app
                    (application-activate a)
                    a))]
     {:ctor        #(application-window-new @app)
      :set-content application-window-set-content
      ;; the app itself, dereffed -- for tests and callers wanting the pointer
      :app         app}))
  ([] (application-window nil)))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- slot-of [props] (:slot props :content))

(defn- policy-of [k]
  "AdwViewSwitcherPolicy: :wide is the pill bar, :narrow the icon row."
  (get VIEW-SWITCHER-POLICY (when k (keyword (name k))) 1))

(defn- stack-of-ref
  "What a :stack prop points at -- a ref name, or a pointer kept from earlier.
   nil when the ref is not resolved yet: a late sibling is normal, and
   :after-children tries again."
  [x]
  (when-let [w (refs/resolve x)]
    (when-not (g/null? w) w)))

(defn- set-picture-file!
  "Loads `path` into a GtkPicture as a GdkTexture, which is what lets the
   picture scale it however the layout asks. Silently goes blank when the file
   is not there, so a missing cover cannot stop an app starting. The picture
   takes its own reference, so ours goes immediately."
  [pic path]
  (if-let [tex (g/texture-file path)]
    (do (picture-set-paintable pic tex)
        (g/object-unref tex))
    (picture-set-paintable pic ffi/null)))

(defn- nth-child
  "The nth child widget of a container, by walking the sibling chain. Lets a
   spec reach a child it was not handed."
  [parent n]
  (loop [w (widget-get-first-child parent) i 0]
    (cond (g/null? w) nil                ; not (nil? w): see gtkiccup.ffi/null?
          (= i n)     w
          :else       (recur (widget-get-next-sibling w) (inc i)))))

(defn- single-child
  "A container that holds exactly one child, ignoring slots."
  [set-child]
  {:append (fn [parent child _props] (set-child parent child))
   :remove (fn [parent _child _props] (set-child parent nil))})

(defn- title-subtitle-apply
  "AdwPreferencesRow title + AdwActionRow subtitle."
  [w p changed]
  (when (contains? changed :title)
    (pref-row-set-title w (str (:title p ""))))
  (when (contains? changed :subtitle)
    (action-row-set-subtitle w (str (:subtitle p "")))))

;; ---------------------------------------------------------------------------
;; specs
;; ---------------------------------------------------------------------------

(def specs
  {;; -- shell ---------------------------------------------------------------
   :adw-window   (merge {:ctor  (fn [_] (window-new))
                         :apply (fn [_ _ _] nil)}
                        (single-child window-set-content))

   :bin          (merge {:ctor  (fn [_] (bin-new))
                         :apply (fn [_ _ _] nil)}
                        (single-child bin-set-child))

   :toolbar-view {:ctor   (fn [_] (toolbar-view-new))
                  :apply  (fn [_ _ _] nil)
                  :append (fn [parent child props]
                            (case (slot-of props)
                              :top    (toolbar-add-top parent child)
                              :bottom (toolbar-add-bottom parent child)
                              (toolbar-set-content parent child)))
                  :remove (fn [parent child props]
                            (case (slot-of props)
                              (:top :bottom) (toolbar-remove parent child)
                              (toolbar-set-content parent nil)))}

   :header-bar   {:ctor   (fn [_] (header-bar-new))
                  :apply  (fn [_ _ _] nil)
                  :append (fn [parent child props]
                            (case (slot-of props)
                              :start (header-pack-start parent child)
                              :end   (header-pack-end parent child)
                              (header-set-title-widget parent child)))
                  :remove (fn [parent child props]
                            (case (slot-of props)
                              (:start :end) (header-remove parent child)
                              (header-set-title-widget parent nil)))}

   ;; The two-pane layout: one pane always there, the other floating over it
   ;; when the window is too narrow to hold both. :slot :sidebar picks the
   ;; floating pane; everything else is the content. The width props are what
   ;; make a sidebar read as a panel rather than a strip: a fraction, and the
   ;; pixel range that fraction is clamped to.
   ;;
   ;; The min/max are doubles, not ints -- since the width-unit landed,
   ;; libadwaita measures them as fractions of the content, so an :int argtype
   ;; reads back garbage. The Clojure-side names are unchanged.
   :overlay-split-view {:ctor  (fn [p]
                                 (doto (split-view-new)
                                   (split-set-show-sidebar (g/->gbool (:show-sidebar p true)))
                                   (split-set-fraction
                                    (double (:sidebar-width-fraction p 0.4)))
                                   (split-set-min-width
                                    (double (:min-sidebar-width p 320)))
                                   (split-set-max-width
                                    (double (:max-sidebar-width p 420)))))
                        :apply (fn [w p changed]
                                 (when (contains? changed :show-sidebar)
                                   (split-set-show-sidebar w (g/->gbool (:show-sidebar p true))))
                                 (when (contains? changed :sidebar-width-fraction)
                                   (split-set-fraction w (double (:sidebar-width-fraction p 0.4))))
                                 (when (contains? changed :min-sidebar-width)
                                   (split-set-min-width w (double (:min-sidebar-width p 320))))
                                 (when (contains? changed :max-sidebar-width)
                                   (split-set-max-width w (double (:max-sidebar-width p 420)))))
                        :append (fn [parent child props]
                                  (if (= :sidebar (slot-of props))
                                    (split-set-sidebar parent child)
                                    (split-set-content parent child)))
                        ;; no remove call on the type: clearing the slot the
                        ;; child was in is the removal
                        :remove (fn [parent child props]
                                  (if (= :sidebar (slot-of props))
                                    (split-set-sidebar parent nil)
                                    (split-set-content parent nil)))}

   ;; Pages that switch as a unit -- the sidebar's player / lyrics / queue.
   ;; A child is a page by its :name; :title and :icon are what a switcher
   ;; shows. The visible page is by name too, and since a page only exists
   ;; once its child has landed, the starting page is set in :after-children,
   ;; the way a carousel's is.
   :view-stack {:ctor   (fn [p]
                          (doto (view-stack-new)
                            (view-stack-set-hhomogeneous (g/->gbool (:h-homogeneous p true)))
                            (view-stack-set-vhomogeneous (g/->gbool (:v-homogeneous p false)))))
                 :apply (fn [w p changed]
                          (when (contains? changed :visible-child-name)
                            ;; unknown names are skipped, not warned about:
                            ;; the page may be this render's, not yet added
                            (when-let [n (:visible-child-name p)]
                              (when-not (g/null? (view-stack-child-by-name w n))
                                (view-stack-set-visible-name w n)))))
                 :after-children (fn [w p]
                                   (when-let [n (:visible-child-name p)]
                                     (when-not (g/null? (view-stack-child-by-name w n))
                                       (view-stack-set-visible-name w n))))
                 :append (fn [parent child props]
                           (if-let [n (:name props)]
                             (view-stack-add-titled-with-icon
                              parent child (str n)
                              (str (:title props ""))
                              (when-let [i (:icon props)] (str i)))
                             (view-stack-add parent child)))
                 :remove (fn [parent child _props] (view-stack-remove parent child))}

   ;; What a stack looks like from the outside: a row of buttons. :stack is a
   ;; reference -- the name the stack carries, or a pointer kept from earlier
   ;; (see gtkiccup.refs) -- and :policy is :wide (the pill bar) or :narrow.
   ;;
   ;; The stack may be built after the switcher, so :apply is not enough:
   ;; :after-children runs once the container's children are all in, and that
   ;; is as late as the ref can be resolved without another render.
   :view-switcher {:ctor  (fn [p]
                            (doto (view-switcher-new)
                              (view-switcher-set-policy (policy-of (:policy p :wide)))
                              (view-switcher-set-stack (stack-of-ref (:stack p)))))
                   :apply (fn [w p changed]
                            (when (contains? changed :policy)
                              (view-switcher-set-policy w (policy-of (:policy p :wide))))
                            ;; dropping :stack means unbound, not "keep the
                            ;; old one"; setting it binds whatever the ref
                            ;; resolves to now
                            (when (contains? changed :stack)
                              (if (contains? p :stack)
                                (when-let [s (stack-of-ref (:stack p))]
                                  (view-switcher-set-stack w s))
                                (view-switcher-set-stack w nil))))
                   :after-children (fn [w p]
                                     ;; still unbound? the stack was built late
                                     (when (g/null? (view-switcher-get-stack w))
                                       (when-let [s (stack-of-ref (:stack p))]
                                         (view-switcher-set-stack w s))))}

   :window-title {:text-prop :title
                  :ctor  (fn [p] (window-title-new (str (:title p ""))
                                                   (str (:subtitle p ""))))
                  :apply (fn [w p changed]
                           (when (contains? changed :title)
                             (window-title-set-title w (str (:title p ""))))
                           (when (contains? changed :subtitle)
                             (window-title-set-subtitle w (str (:subtitle p "")))))}

   ;; -- content -----------------------------------------------------------
   :page         {:ctor   (fn [_] (prefs-page-new))
                  :apply  (fn [_ _ _] nil)
                  :append (fn [parent child _props] (prefs-page-add parent child))
                  :remove (fn [parent child _props] (prefs-page-remove parent child))}

   :group        {:ctor   (fn [p] (doto (prefs-group-new)
                                    (prefs-group-set-title (str (:title p "")))))
                  :apply  (fn [w p changed]
                            (when (contains? changed :title)
                              (prefs-group-set-title w (str (:title p ""))))
                            (when (contains? changed :description)
                              (prefs-group-set-description w (str (:description p "")))))
                  :append (fn [parent child _props] (prefs-group-add parent child))
                  :remove (fn [parent child _props] (prefs-group-remove parent child))}

   :row          {:text-prop :title
                  :ctor   (fn [p] (doto (action-row-new)
                                    (pref-row-set-title (str (:title p "")))
                                    (action-row-set-subtitle (str (:subtitle p "")))))
                  :apply  title-subtitle-apply
                  :append (fn [parent child props]
                            (if (= :prefix (slot-of props))
                              (action-row-add-prefix parent child)
                              (action-row-add-suffix parent child)))
                  :remove (fn [parent child _props] (action-row-remove parent child))}

   :toast-overlay (merge {:ctor  (fn [_] (toast-overlay-new))
                          :apply (fn [_ _ _] nil)}
                         (single-child toast-overlay-set-child))

   :status-page  {:text-prop :title
                  :ctor  (fn [p] (doto (status-page-new)
                                   (status-set-title (str (:title p "")))))
                  :apply (fn [w p changed]
                           (when (contains? changed :title)
                             (status-set-title w (str (:title p ""))))
                           (when (contains? changed :description)
                             (status-set-description w (str (:description p ""))))
                           (when (contains? changed :icon)
                             (status-set-icon-name w (:icon p))))}

   ;; :h and :v take :automatic (default), :never, :always or :external. A
   ;; horizontal strip wants {:h :automatic :v :never}.
   :scroll       (merge {:ctor  (fn [p]
                                  (doto (scroll-new)
                                    (scroll-set-policy
                                     (get g/policy (:h p :automatic) 1)
                                     (get g/policy (:v p :automatic) 1))))
                         :apply (fn [w p changed]
                                  (when (or (contains? changed :h) (contains? changed :v))
                                    (scroll-set-policy
                                     w
                                     (get g/policy (:h p :automatic) 1)
                                     (get g/policy (:v p :automatic) 1))))}
                        (single-child scroll-set-child))

   :clamp        (merge {:ctor  (fn [p] (doto (clamp-new)
                                          (clamp-set-maximum-size (int (:max p 600)))))
                         :apply (fn [w p changed]
                                  (when (contains? changed :max)
                                    (clamp-set-maximum-size w (int (:max p 600)))))}
                        (single-child clamp-set-child))

   :banner       {:text-prop :title
                  :ctor  (fn [p] (doto (banner-new (str (:title p "")))
                                   (banner-set-revealed (g/->gbool (:revealed p true)))))
                  :apply (fn [w p changed]
                           (when (contains? changed :title)
                             (banner-set-title w (str (:title p ""))))
                           (when (contains? changed :revealed)
                             (banner-set-revealed w (g/->gbool (:revealed p true)))))}

   :spinner      {:ctor  (fn [_] (spinner-new))
                  :apply (fn [_ _ _] nil)}

   ;; An image from a file -- SVG included, because GdkTexture goes through the
   ;; pixbuf loaders. :content-fit is what to do with the space it is given:
   ;; :cover fills a card without letterboxing, :contain keeps the whole image.
   ;; :can-shrink lets it go below the texture's own size, which a responsive
   ;; grid needs. A missing or unreadable file leaves the picture empty rather
   ;; than raising: a logo is decoration, not a reason to fail to start.
   ;; :texture / :paintable are synonyms for :file here -- all three name a
   ;; file, and a paintable kept from earlier is the other way to say it.
   :picture      {:text-prop :file
                  :ctor  (fn [p]
                           (doto (picture-new)
                             (picture-set-content-fit
                              (get g/content-fit (:content-fit p :contain) 1))
                             (g/picture-set-can-shrink (g/->gbool (:can-shrink p true)))
                             (set-picture-file! (or (:file p) (:texture p) (:paintable p)))))
                  :apply (fn [w p changed]
                           (when (or (contains? changed :file)
                                     (contains? changed :texture)
                                     (contains? changed :paintable))
                             (set-picture-file! w (or (:file p) (:texture p) (:paintable p))))
                           (when (contains? changed :content-fit)
                             (picture-set-content-fit
                              w (get g/content-fit (:content-fit p :contain) 1)))
                           (when (contains? changed :can-shrink)
                             (g/picture-set-can-shrink w (g/->gbool (:can-shrink p true)))))}

   ;; Motion, declaratively. :page is an index; when it changes, :apply
   ;; animates there. So an animation is a side effect of a prop change and the
   ;; app stays a pure function of state.
   ;;
   ;; scroll_to needs the child widget and :apply only gets the parent, so walk
   ;; the sibling chain rather than threading our tree into the spec.
   :carousel     {:ctor   (fn [p]
                            (doto (carousel-new)
                              (carousel-set-mouse-drag (g/->gbool (:drag p false)))
                              (carousel-set-interactive (g/->gbool (:wheel p false)))))
                  :apply  (fn [w p changed]
                            (when (contains? changed :page)
                              (when-let [child (nth-child w (:page p 0))]
                                (carousel-scroll-to w child
                                                    (g/->gbool (:animate p true))))))
                  ;; the pages do not exist when :ctor and :apply run, so the
                  ;; starting page has to be set once they do -- and without
                  ;; animation, since there is nothing to animate from
                  :after-children (fn [w p]
                                    (when-let [child (nth-child w (:page p 0))]
                                      (carousel-scroll-to w child 0)))
                  :append (fn [parent child _props] (carousel-append parent child))
                  :remove (fn [parent child _props] (carousel-remove parent child))}

   :revealer     (merge {:ctor  (fn [p]
                                 (doto (revealer-new)
                                   (revealer-set-transition-duration (int (:duration p 250)))
                                   (revealer-set-reveal (g/->gbool (:revealed p true)))))
                         :apply (fn [w p changed]
                                  (when (contains? changed :duration)
                                    (revealer-set-transition-duration w (int (:duration p 250))))
                                  (when (contains? changed :revealed)
                                    (revealer-set-reveal w (g/->gbool (:revealed p true)))))}
                        (single-child revealer-set-child))

   ;; -- leaves ------------------------------------------------------------
   :level        {:ctor  (fn [p]
                           (doto (level-new)
                             (level-set-min (double (:min p 0)))
                             (level-set-max (double (:max p 1)))
                             (level-set-value (double (:value p 0)))
                             (widget-set-valign ALIGN-CENTER)))
                  :apply (fn [w p changed]
                           (when (contains? changed :min)
                             (level-set-min w (double (:min p 0))))
                           (when (contains? changed :max)
                             (level-set-max w (double (:max p 1))))
                           (when (contains? changed :value)
                             (level-set-value w (double (:value p 0)))))}

   :icon-button  {:text-prop :icon
                  :ctor  (fn [p] (button-new-from-icon (:icon p)))
                  :apply (fn [w p changed]
                           (when (contains? changed :icon)
                             (button-set-icon-name w (:icon p))))}})

;; ---------------------------------------------------------------------------
;; install
;; ---------------------------------------------------------------------------

(ui/register-signal!
  :on-switch
  "notify::visible-child-name"
  (fn [f w _args]
    ;; the page that is showing now, by name
    (f (view-stack-get-visible-name w))))

(defn toast!
  "Shows a toast over a :toast-overlay widget. Find the overlay in the live
   tree, or keep its pointer when you build it."
  ([overlay message] (toast! overlay message 3))
  ([overlay message seconds]
   (let [t (toast-new (str message))]
     (toast-set-timeout t (int seconds))
     (toast-overlay-add-toast overlay t))))

(defonce ^{:doc "adw_init runs once, when this namespace loads."} initialized
  (do (adw-init)
      (doseq [[tag spec] specs] (ui/register-widget! tag spec))
      {:version (str (get-major) "." (get-minor))
       :widgets (vec (sort (keys specs)))}))

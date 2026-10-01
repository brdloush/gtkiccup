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
     :spinner        AdwSpinner
     :picture        GtkPicture from a file, SVG included
     :scroll         GtkScrolledWindow
     :level          GtkLevelBar
     :icon-button    GtkButton from an icon name

   Slots are a plain `:slot` prop on the child, honoured by the parent's
   :append. A child with no :slot goes to the parent's main content."
  (:require [babashka.ffi :as ffi :refer [defcfn]]
            [gtkiccup.core :as ui]
            [gtkiccup.ffi :as g]))

(ffi/load-system-library "adwaita-1")

;; ---------------------------------------------------------------------------
;; bindings
;; ---------------------------------------------------------------------------

(defcfn adw-init "adw_init" [] :void)
(defcfn get-major "adw_get_major_version" [] :int)
(defcfn get-minor "adw_get_minor_version" [] :int)

(defcfn window-new "adw_window_new" [] :pointer)
(defcfn window-set-content "adw_window_set_content" [:pointer :pointer] :void)

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
(defcfn texture-from-filename "gdk_texture_new_from_filename" [:string :pointer] :pointer)
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

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- slot-of [props] (:slot props :content))

(defn- set-picture-file!
  "Loads `path` into a GtkPicture. Silently leaves it blank if the file is not
   there, so a missing logo cannot stop an app starting."
  [pic path]
  (let [tex (when path
              (try (let [t (texture-from-filename (str path) nil)]
                     (when-not (g/null? t) t))
                   (catch Throwable _ nil)))]
    (picture-set-paintable pic (or tex ffi/null))))

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
   ;; pixbuf loaders. A missing or unreadable file leaves the picture empty
   ;; rather than raising: a logo is decoration, not a reason to fail to start.
   :picture      {:text-prop :file
                  :ctor  (fn [p]
                           (doto (picture-new)
                             (picture-set-content-fit 0)   ; CONTAIN
                             (set-picture-file! (:file p))))
                  :apply (fn [w p changed]
                           (when (contains? changed :file)
                             (set-picture-file! w (:file p))))}

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

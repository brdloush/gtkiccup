(ns gtkiccup.ffi
  "Raw GTK4 / GObject bindings via babashka.ffi.
   Only the handful of symbols the POC needs."
  (:require [babashka.ffi :as ffi :refer [defcfn]]))

(defn load-gtk-library
  "Loads a GNOME library by its short name, e.g. \"gtk-4\". Windows DLLs carry
   the ABI version in the name, which load-system-library does not try:
   gtk-4-1.dll from gvsbuild, libgtk-4-1.dll from MSYS2. abi is that suffix."
  [short abi]
  (if (.startsWith (System/getProperty "os.name") "Windows")
    (ffi/load-library [(str short "-" abi ".dll") (str "lib" short "-" abi ".dll")])
    (ffi/load-system-library short)))

;; Linux finds a symbol in any library GTK pulled in; Windows looks only in the
;; DLLs loaded here, so each one a binding below comes from is named.
(load-gtk-library "gtk-4" 1)
(load-gtk-library "gobject-2.0" 0)
(load-gtk-library "glib-2.0" 0)
(load-gtk-library "pango-1.0" 0)

;; -- lifecycle / main loop ---------------------------------------------------
(defcfn gtk-init "gtk_init" [] :void)
(defcfn main-iteration "g_main_context_iteration" [:pointer :int] :int)
(defcfn idle-add "g_idle_add" [:pointer :pointer] :int)
(defcfn timeout-add "g_timeout_add" [:uint :pointer :pointer] :int)

;; -- object lifetime ---------------------------------------------------------
;; A widget is floating until a container sinks it, and a container drops it
;; on removal -- which frees it. The renderer moves widgets (keyed children),
;; so it owns one reference per widget for as long as the widget is in the tree.
(defcfn object-ref-sink "g_object_ref_sink" [:pointer] :pointer)
(defcfn object-unref "g_object_unref" [:pointer] :void)

;; -- frames ------------------------------------------------------------------
;; The window's frame clock. NULL until the window is realized. Its
;; "after-paint" signal is the first moment a frame's layout and allocation are
;; known -- what :on-layout hangs off.
(defcfn widget-get-frame-clock "gtk_widget_get_frame_clock" [:pointer] :pointer)
(defcfn main-context-wakeup "g_main_context_wakeup" [:pointer] :void)

;; -- process identity ------------------------------------------------------
;; prgname becomes the Wayland app_id, which is what a compositor matches
;; against a .desktop file. Must be set before the first window is created.
(defcfn set-prgname "g_set_prgname" [:string] :void)
(defcfn get-prgname "g_get_prgname" [] :string)
(defcfn set-application-name "g_set_application_name" [:string] :void)
(defcfn get-application-name "g_get_application_name" [] :string)

;; -- input (event controllers) ----------------------------------------------
(defcfn event-controller-key-new "gtk_event_controller_key_new" [] :pointer)
(defcfn event-controller-scroll-new "gtk_event_controller_scroll_new" [:int] :pointer)
(defcfn widget-add-controller "gtk_widget_add_controller" [:pointer :pointer] :void)
(defcfn event-controller-set-phase "gtk_event_controller_set_propagation_phase" [:pointer :int] :void)

(def scroll-type
  "GtkScrollType bits -- which axes an event-controller-scroll reports on."
  {:none 0 :vertical 1 :horizontal 2 :both 3})

(def phase
  "GtkPropagationPhase. Controllers default to :bubble, which means the focused
   widget sees the event first -- so a focused button would swallow space."
  {:none 0 :capture 1 :bubble 2 :target 3})
(defcfn keyval-name "gdk_keyval_name" [:int] :string)
(defcfn keyval-to-unicode "gdk_keyval_to_unicode" [:int] :int)
(defcfn unicode-to-keyval "gdk_unicode_to_keyval" [:int] :int)

(defn keyval-char
  "The character a keyval types, or nil when it types nothing.

   Note the lower bound: Escape, Tab and BackSpace map to unicode 27, 9 and 8 --
   real control characters, not zero. Testing `(pos? uni)` would type an Escape
   into your document."
  [keyval]
  (let [uni (keyval-to-unicode keyval)]
    (when (and (>= uni 32) (not= uni 127))
      (char uni))))

(def modifier
  "GdkModifierType bits we care about."
  {:shift 1 :lock 2 :ctrl 4 :alt 8 :super (bit-shift-left 1 26)})

(defn modifiers
  "Turns a GdkModifierType bitfield into {:shift? .. :ctrl? .. :alt? ..}."
  [state]
  (let [s (or state 0)]
    {:shift? (pos? (bit-and s (:shift modifier)))
     :ctrl?  (pos? (bit-and s (:ctrl modifier)))
     :alt?   (pos? (bit-and s (:alt modifier)))
     :super? (pos? (bit-and s (:super modifier)))}))

;; -- window ---------------------------------------------------------------
(defcfn window-fullscreen "gtk_window_fullscreen" [:pointer] :void)
(defcfn window-unfullscreen "gtk_window_unfullscreen" [:pointer] :void)
(defcfn window-is-fullscreen "gtk_window_is_fullscreen" [:pointer] :int)
(defcfn window-maximize "gtk_window_maximize" [:pointer] :void)
(defcfn window-unmaximize "gtk_window_unmaximize" [:pointer] :void)
(defcfn window-is-maximized "gtk_window_is_maximized" [:pointer] :int)

;; -- signals ----------------------------------------------------------------
(defcfn signal-connect-data "g_signal_connect_data"
  [:pointer :string :pointer :pointer :pointer :int] :long)

;; -- window -----------------------------------------------------------------
(defcfn window-new "gtk_window_new" [] :pointer)
(defcfn window-set-title "gtk_window_set_title" [:pointer :string] :void)
(defcfn window-set-default-size "gtk_window_set_default_size" [:pointer :int :int] :void)
(defcfn window-set-child "gtk_window_set_child" [:pointer :pointer] :void)
(defcfn window-present "gtk_window_present" [:pointer] :void)
(defcfn window-destroy "gtk_window_destroy" [:pointer] :void)
(defcfn window-set-decorated "gtk_window_set_decorated" [:pointer :int] :void)

;; -- overlay ----------------------------------------------------------------
(defcfn overlay-new "gtk_overlay_new" [] :pointer)

;; -- scrolled window ---------------------------------------------------------

(defcfn scrolled-window-new "gtk_scrolled_window_new" [] :pointer)
(defcfn scrolled-window-set-child "gtk_scrolled_window_set_child" [:pointer :pointer] :void)
(defcfn scrolled-window-set-policy "gtk_scrolled_window_set_policy" [:pointer :int :int] :void)
(defcfn scrolled-window-get-vadjustment "gtk_scrolled_window_get_vadjustment" [:pointer] :pointer)
(defcfn scrolled-window-get-hadjustment "gtk_scrolled_window_get_hadjustment" [:pointer] :pointer)
(defcfn overlay-set-child "gtk_overlay_set_child" [:pointer :pointer] :void)
(defcfn overlay-add-overlay "gtk_overlay_add_overlay" [:pointer :pointer] :void)
(defcfn overlay-remove-overlay "gtk_overlay_remove_overlay" [:pointer :pointer] :void)

;; A box that keeps its middle child centred. The three slots are how a bar
;; with buttons at both ends and a centred title gets laid out, so a header
;; needs no alignment hacks.
(defcfn center-box-new "gtk_center_box_new" [] :pointer)
(defcfn center-box-set-start-widget "gtk_center_box_set_start_widget" [:pointer :pointer] :void)
(defcfn center-box-set-center-widget "gtk_center_box_set_center_widget" [:pointer :pointer] :void)
(defcfn center-box-set-end-widget "gtk_center_box_set_end_widget" [:pointer :pointer] :void)
(defcfn center-box-get-start-widget "gtk_center_box_get_start_widget" [:pointer] :pointer)
(defcfn center-box-get-center-widget "gtk_center_box_get_center_widget" [:pointer] :pointer)
(defcfn center-box-get-end-widget "gtk_center_box_get_end_widget" [:pointer] :pointer)

;; -- leaf props and small widgets -------------------------------------------
;; Everything a card, a header or a player row needs, with a getter next to
;; every setter so a test can read the value back instead of trusting the call.
(defcfn label-set-ellipsize "gtk_label_set_ellipsize" [:pointer :int] :void)
(defcfn label-set-lines "gtk_label_set_lines" [:pointer :int] :void)
(defcfn label-set-max-width-chars "gtk_label_set_max_width_chars" [:pointer :int] :void)
(defcfn label-set-width-chars "gtk_label_set_width_chars" [:pointer :int] :void)
(defcfn label-set-justify "gtk_label_set_justify" [:pointer :int] :void)
(defcfn label-set-wrap-mode "gtk_label_set_wrap_mode" [:pointer :int] :void)
(defcfn label-set-selectable "gtk_label_set_selectable" [:pointer :int] :void)
(defcfn label-set-yalign "gtk_label_set_yalign" [:pointer :float] :void)
(defcfn label-get-ellipsize "gtk_label_get_ellipsize" [:pointer] :int)
(defcfn label-get-lines "gtk_label_get_lines" [:pointer] :int)
(defcfn label-get-max-width-chars "gtk_label_get_max_width_chars" [:pointer] :int)
(defcfn label-get-width-chars "gtk_label_get_width_chars" [:pointer] :int)
(defcfn label-get-justify "gtk_label_get_justify" [:pointer] :int)
(defcfn label-get-wrap-mode "gtk_label_get_wrap_mode" [:pointer] :int)
(defcfn label-get-selectable "gtk_label_get_selectable" [:pointer] :int)

(defcfn widget-set-overflow "gtk_widget_set_overflow" [:pointer :int] :void)
(defcfn widget-set-visible "gtk_widget_set_visible" [:pointer :int] :void)
(defcfn widget-set-direction "gtk_widget_set_direction" [:pointer :int] :void)
(defcfn widget-is-visible "gtk_widget_get_visible" [:pointer] :int)
(defcfn widget-is-mapped "gtk_widget_get_mapped" [:pointer] :int)
(defcfn widget-is-realized "gtk_widget_get_realized" [:pointer] :int)

(defcfn aspect-frame-new "gtk_aspect_frame_new" [:float :float :float :int] :pointer)
(defcfn aspect-frame-set-child "gtk_aspect_frame_set_child" [:pointer :pointer] :void)
(defcfn aspect-frame-set-ratio "gtk_aspect_frame_set_ratio" [:pointer :float] :void)
(defcfn aspect-frame-get-ratio "gtk_aspect_frame_get_ratio" [:pointer] :float)
(defcfn aspect-frame-set-obey-child "gtk_aspect_frame_set_obey_child" [:pointer :int] :void)
(defcfn aspect-frame-get-obey-child "gtk_aspect_frame_get_obey_child" [:pointer] :int)

(defcfn separator-new "gtk_separator_new" [:int] :pointer)
(defcfn orientable-set-orientation "gtk_orientable_set_orientation" [:pointer :int] :void)
(defcfn orientable-get-orientation "gtk_orientable_get_orientation" [:pointer] :int)

(defcfn scale-new-with-range "gtk_scale_new_with_range" [:double :double :double] :pointer)
(defcfn scale-set-draw-value "gtk_scale_set_draw_value" [:pointer :int] :void)
(defcfn scale-get-draw-value "gtk_scale_get_draw_value" [:pointer] :int)
(defcfn scale-set-digits "gtk_scale_set_digits" [:pointer :int] :void)
(defcfn scale-get-digits "gtk_scale_get_digits" [:pointer] :int)
(defcfn scale-set-orientation "gtk_orientable_set_orientation" [:pointer :int] :void)
(defcfn range-set-value "gtk_range_set_value" [:pointer :double] :void)
(defcfn range-get-value "gtk_range_get_value" [:pointer] :double)
(defcfn range-set-inverted "gtk_range_set_inverted" [:pointer :int] :void)
(defcfn range-get-inverted "gtk_range_get_inverted" [:pointer] :int)
(defcfn range-set-increments "gtk_range_set_increments" [:pointer :double :double] :void)
(defcfn range-get-adjustment "gtk_range_get_adjustment" [:pointer] :pointer)
(defcfn adjustment-set-lower "gtk_adjustment_set_lower" [:pointer :double] :void)
(defcfn adjustment-set-upper "gtk_adjustment_set_upper" [:pointer :double] :void)
(defcfn adjustment-get-lower "gtk_adjustment_get_lower" [:pointer] :double)
(defcfn adjustment-set-value "gtk_adjustment_set_value" [:pointer :double] :void)
(defcfn adjustment-get-page-size "gtk_adjustment_get_page_size" [:pointer] :double)
(defcfn adjustment-get-step-increment "gtk_adjustment_get_step_increment" [:pointer] :double)
(defcfn adjustment-get-upper "gtk_adjustment_get_upper" [:pointer] :double)
(defcfn adjustment-get-value "gtk_adjustment_get_value" [:pointer] :double)

(defcfn menu-button-new "gtk_menu_button_new" [] :pointer)
(defcfn menu-button-set-icon-name "gtk_menu_button_set_icon_name" [:pointer :string] :void)
(defcfn menu-button-get-icon-name "gtk_menu_button_get_icon_name" [:pointer] :string)
(defcfn menu-button-set-label "gtk_menu_button_set_label" [:pointer :string] :void)
(defcfn menu-button-get-label "gtk_menu_button_get_label" [:pointer] :string)
(defcfn menu-button-set-child "gtk_menu_button_set_child" [:pointer :pointer] :void)
(defcfn menu-button-set-has-frame "gtk_menu_button_set_has_frame" [:pointer :int] :void)
(defcfn menu-button-get-has-frame "gtk_menu_button_get_has_frame" [:pointer] :int)
(defcfn menu-button-set-primary "gtk_menu_button_set_primary" [:pointer :int] :void)
(defcfn menu-button-get-primary "gtk_menu_button_get_primary" [:pointer] :int)
(defcfn menu-button-set-direction "gtk_menu_button_set_direction" [:pointer :int] :void)
(defcfn menu-button-set-active "gtk_menu_button_set_active" [:pointer :int] :void)
(defcfn menu-button-get-active "gtk_menu_button_get_active" [:pointer] :int)

(defcfn button-set-child "gtk_button_set_child" [:pointer :pointer] :void)
(defcfn button-set-has-frame "gtk_button_set_has_frame" [:pointer :int] :void)
(defcfn button-get-has-frame "gtk_button_get_has_frame" [:pointer] :int)
(defcfn button-set-icon-name "gtk_button_set_icon_name" [:pointer :string] :void)

(defcfn image-set-from-paintable "gtk_image_set_from_paintable" [:pointer :pointer] :void)
(defcfn picture-set-can-shrink "gtk_picture_set_can_shrink" [:pointer :int] :void)
(defcfn texture-from-filename "gdk_texture_new_from_filename" [:string :pointer] :pointer)

;; -- image ------------------------------------------------------------------
(defcfn image-new "gtk_image_new" [] :pointer)
(defcfn image-new-from-icon "gtk_image_new_from_icon_name" [:string] :pointer)
(defcfn image-new-from-file "gtk_image_new_from_file" [:string] :pointer)
(defcfn image-set-from-file "gtk_image_set_from_file" [:pointer :string] :void)
(defcfn image-set-from-icon "gtk_image_set_from_icon_name" [:pointer :string] :void)
(defcfn image-set-pixel-size "gtk_image_set_pixel_size" [:pointer :int] :void)

;; -- widget (common) --------------------------------------------------------
(defcfn widget-set-sensitive "gtk_widget_set_sensitive" [:pointer :int] :void)
(defcfn widget-set-tooltip-text "gtk_widget_set_tooltip_text" [:pointer :string] :void)
(defcfn widget-set-hexpand "gtk_widget_set_hexpand" [:pointer :int] :void)
(defcfn widget-set-vexpand "gtk_widget_set_vexpand" [:pointer :int] :void)
(defcfn widget-set-margin-top "gtk_widget_set_margin_top" [:pointer :int] :void)
(defcfn widget-set-margin-bottom "gtk_widget_set_margin_bottom" [:pointer :int] :void)
(defcfn widget-set-margin-start "gtk_widget_set_margin_start" [:pointer :int] :void)
(defcfn widget-set-margin-end "gtk_widget_set_margin_end" [:pointer :int] :void)
(defcfn widget-set-size-request "gtk_widget_set_size_request" [:pointer :int :int] :void)
(defcfn widget-set-focusable "gtk_widget_set_focusable" [:pointer :int] :void)
(defcfn widget-set-halign "gtk_widget_set_halign" [:pointer :int] :void)
(defcfn widget-set-valign "gtk_widget_set_valign" [:pointer :int] :void)
(defcfn widget-add-css-class "gtk_widget_add_css_class" [:pointer :string] :void)
(defcfn widget-remove-css-class "gtk_widget_remove_css_class" [:pointer :string] :void)

;; -- box --------------------------------------------------------------------
(defcfn box-new "gtk_box_new" [:int :int] :pointer)
(defcfn box-append "gtk_box_append" [:pointer :pointer] :void)
(defcfn box-remove "gtk_box_remove" [:pointer :pointer] :void)
;; NULL sibling = first position. How a keyed move lands a child in place.
(defcfn box-insert-child-after "gtk_box_insert_child_after" [:pointer :pointer :pointer] :void)
(defcfn box-set-spacing "gtk_box_set_spacing" [:pointer :int] :void)
(defcfn box-set-homogeneous "gtk_box_set_homogeneous" [:pointer :int] :void)

;; -- label ------------------------------------------------------------------
(defcfn label-new "gtk_label_new" [:string] :pointer)
(defcfn label-set-text "gtk_label_set_text" [:pointer :string] :void)
(defcfn label-get-text "gtk_label_get_text" [:pointer] :string)
(defcfn label-set-markup "gtk_label_set_markup" [:pointer :string] :void)
(defcfn label-set-wrap "gtk_label_set_wrap" [:pointer :int] :void)
(defcfn label-set-xalign "gtk_label_set_xalign" [:pointer :float] :void)
(defcfn label-get-layout "gtk_label_get_layout" [:pointer] :pointer)
(defcfn label-get-layout-offsets "gtk_label_get_layout_offsets" [:pointer :pointer :pointer] :void)
(defcfn pango-index-to-pos "pango_layout_index_to_pos" [:pointer :int :pointer] :void)

(def PANGO-SCALE
  "Pango works in 1024ths of a pixel."
  1024)

;; -- button -----------------------------------------------------------------
(defcfn button-new-with-label "gtk_button_new_with_label" [:string] :pointer)
(defcfn button-set-label "gtk_button_set_label" [:pointer :string] :void)

;; -- entry / editable -------------------------------------------------------
(defcfn entry-new "gtk_entry_new" [] :pointer)
(defcfn entry-set-placeholder "gtk_entry_set_placeholder_text" [:pointer :string] :void)
(defcfn editable-get-text "gtk_editable_get_text" [:pointer] :string)
(defcfn editable-set-text "gtk_editable_set_text" [:pointer :string] :void)

;; -- check button -----------------------------------------------------------
(defcfn check-button-new-with-label "gtk_check_button_new_with_label" [:string] :pointer)
(defcfn check-button-set-label "gtk_check_button_set_label" [:pointer :string] :void)
(defcfn check-button-get-active "gtk_check_button_get_active" [:pointer] :int)
(defcfn check-button-set-active "gtk_check_button_set_active" [:pointer :int] :void)

;; -- snapshot to png (gtkiccup.dev/screenshot!) -----------------------------------
(defcfn widget-paintable-new "gtk_widget_paintable_new" [:pointer] :pointer)
(defcfn widget-get-width "gtk_widget_get_width" [:pointer] :int)
(defcfn widget-get-height "gtk_widget_get_height" [:pointer] :int)
(defcfn snapshot-new "gtk_snapshot_new" [] :pointer)
(defcfn snapshot-scale "gtk_snapshot_scale" [:pointer :float :float] :void)
(defcfn widget-get-scale-factor "gtk_widget_get_scale_factor" [:pointer] :int)
(defcfn texture-get-width "gdk_texture_get_width" [:pointer] :int)
(defcfn texture-get-height "gdk_texture_get_height" [:pointer] :int)
(defcfn paintable-snapshot "gdk_paintable_snapshot" [:pointer :pointer :double :double] :void)
(defcfn snapshot-free-to-node "gtk_snapshot_free_to_node" [:pointer] :pointer)
(defcfn widget-get-root "gtk_widget_get_root" [:pointer] :pointer)
(defcfn native-get-renderer "gtk_native_get_renderer" [:pointer] :pointer)
(defcfn renderer-render-texture "gsk_renderer_render_texture" [:pointer :pointer :pointer] :pointer)
(defcfn texture-save-to-png "gdk_texture_save_to_png" [:pointer :string] :int)

;; -- css --------------------------------------------------------------------
(defcfn css-provider-new "gtk_css_provider_new" [] :pointer)
(defcfn css-provider-load-from-string "gtk_css_provider_load_from_string" [:pointer :string] :void)
(defcfn display-get-default "gdk_display_get_default" [] :pointer)
(defcfn style-context-add-provider-for-display "gtk_style_context_add_provider_for_display"
  [:pointer :pointer :int] :void)

;; -- the gtk inspector -------------------------------------------------------
;; The same window GTK_DEBUG=interactive gives you, opened on demand. No env
;; var and no keybinding setting is needed: this call shows the inspector
;; itself, and a 0 hides it again. The inspector window is kept alive between
;; toggles, so its "Frames" page keeps its history.
(defcfn window-set-interactive-debugging "gtk_window_set_interactive_debugging" [:int] :void)

;; -- environment (must be set before GTK reads it, i.e. before the first
;; -- window is realized) ----------------------------------------------------
(defcfn setenv "g_setenv" [:string :string :int] :int)
(defcfn getenv "g_getenv" [:string] :string)

;; -- helpers ----------------------------------------------------------------

(defn null?
  "Whether a pointer C handed back is NULL.

   Necessary because a NULL return is *not* nil: it arrives as a live
   MemorySegment at address 0, so `nil?` and `some?` both lie about it. Walking
   a sibling chain with `nil?` runs off the end and GTK starts printing
   CRITICALs."
  [p]
  (ffi/null? p))

(defn texture-file
  "The GdkTexture for an image file, or nil when it is not there. A missing
   picture is decoration, not a reason to fail to start -- so this reports it
   as nil instead of letting GdkLog a critical through."
  [path]
  (when path
    (try
      (let [t (texture-from-filename (str path) ffi/null)]
        (when-not (null? t) t))
      (catch Throwable _ nil))))
(def HORIZONTAL 0)
(def VERTICAL 1)

(def align
  "GtkAlign. :fill is the GTK default."
  {:fill 0 :start 1 :end 2 :center 3 :baseline 4})

(def policy
  "GtkPolicyType, for a scrolled window's scrollbars. :external is what a
   horizontal strip wants: the scrollbar is not the user's, it is the app's."
  {:always 0 :automatic 1 :never 2 :external 3})

(def ellipsize
  "PangoEllipsizeMode. :middle is what card titles and rows use -- the middle
   of a title is the part you can afford to lose."
  {:none 0 :start 1 :middle 2 :end 3})

(def justify
  "GtkJustification."
  {:left 0 :right 1 :center 2 :fill 3})

(def wrap-mode
  "PangoWrapMode."
  {:none 0 :char 1 :word 2 :word-char 3})

(def overflow
  "GtkOverflow. :hidden is what makes border-radius clip a picture: without it
   a rounded cover shows square corners."
  {:visible 0 :hidden 1 :mask 2})

(def direction
  "GtkTextDirection."
  {:ltr 0 :rtl 1})

(def content-fit
  "GtkContentFit -- what a picture does with the space it is given. :cover is
   what fills a rounded card without letterboxing; :contain keeps the whole
   image visible; :scale-down only shrinks."
  {:fill 0 :contain 1 :cover 2 :scale-down 3})

(defn ->gbool [x] (if x 1 0))
(defn <-gbool [x] (not (zero? x)))

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
(defcfn widget-add-controller "gtk_widget_add_controller" [:pointer :pointer] :void)
(defcfn event-controller-set-phase "gtk_event_controller_set_propagation_phase" [:pointer :int] :void)

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
(defcfn signal-handler-block "g_signal_handler_block" [:pointer :long] :void)
(defcfn signal-handler-unblock "g_signal_handler_unblock" [:pointer :long] :void)

;; -- window -----------------------------------------------------------------
(defcfn window-new "gtk_window_new" [] :pointer)
(defcfn window-set-title "gtk_window_set_title" [:pointer :string] :void)
(defcfn window-set-default-size "gtk_window_set_default_size" [:pointer :int :int] :void)
(defcfn window-set-child "gtk_window_set_child" [:pointer :pointer] :void)
(defcfn window-present "gtk_window_present" [:pointer] :void)
(defcfn window-destroy "gtk_window_destroy" [:pointer] :void)
(defcfn window-set-decorated "gtk_window_set_decorated" [:pointer :int] :void)

;; -- layout containers ------------------------------------------------------
(defcfn grid-new "gtk_grid_new" [] :pointer)
(defcfn grid-attach "gtk_grid_attach" [:pointer :pointer :int :int :int :int] :void)
(defcfn grid-remove "gtk_grid_remove" [:pointer :pointer] :void)
(defcfn grid-set-row-spacing "gtk_grid_set_row_spacing" [:pointer :int] :void)
(defcfn grid-set-column-spacing "gtk_grid_set_column_spacing" [:pointer :int] :void)
(defcfn grid-set-row-homogeneous "gtk_grid_set_row_homogeneous" [:pointer :int] :void)
(defcfn grid-set-column-homogeneous "gtk_grid_set_column_homogeneous" [:pointer :int] :void)
(defcfn widget-get-layout-manager "gtk_widget_get_layout_manager" [:pointer] :pointer)
(defcfn layout-manager-get-layout-child "gtk_layout_manager_get_layout_child" [:pointer :pointer] :pointer)
(defcfn grid-layout-child-set-column "gtk_grid_layout_child_set_column" [:pointer :int] :void)
(defcfn grid-layout-child-set-row "gtk_grid_layout_child_set_row" [:pointer :int] :void)
(defcfn grid-layout-child-set-column-span "gtk_grid_layout_child_set_column_span" [:pointer :int] :void)
(defcfn grid-layout-child-set-row-span "gtk_grid_layout_child_set_row_span" [:pointer :int] :void)
(defcfn center-box-new "gtk_center_box_new" [] :pointer)
(defcfn center-box-set-start-widget "gtk_center_box_set_start_widget" [:pointer :pointer] :void)
(defcfn center-box-set-center-widget "gtk_center_box_set_center_widget" [:pointer :pointer] :void)
(defcfn center-box-set-end-widget "gtk_center_box_set_end_widget" [:pointer :pointer] :void)
(defcfn aspect-frame-new "gtk_aspect_frame_new" [:float :float :float :int] :pointer)
(defcfn aspect-frame-set-child "gtk_aspect_frame_set_child" [:pointer :pointer] :void)
(defcfn aspect-frame-set-ratio "gtk_aspect_frame_set_ratio" [:pointer :float] :void)
(defcfn aspect-frame-set-xalign "gtk_aspect_frame_set_xalign" [:pointer :float] :void)
(defcfn aspect-frame-set-yalign "gtk_aspect_frame_set_yalign" [:pointer :float] :void)
(defcfn aspect-frame-set-obey-child "gtk_aspect_frame_set_obey_child" [:pointer :int] :void)
(defcfn fixed-new "gtk_fixed_new" [] :pointer)
(defcfn fixed-put "gtk_fixed_put" [:pointer :pointer :double :double] :void)
(defcfn fixed-move "gtk_fixed_move" [:pointer :pointer :double :double] :void)
(defcfn fixed-remove "gtk_fixed_remove" [:pointer :pointer] :void)
(defcfn paned-new "gtk_paned_new" [:int] :pointer)
(defcfn paned-set-start-child "gtk_paned_set_start_child" [:pointer :pointer] :void)
(defcfn paned-set-end-child "gtk_paned_set_end_child" [:pointer :pointer] :void)
(defcfn paned-set-position "gtk_paned_set_position" [:pointer :int] :void)
(defcfn paned-get-position "gtk_paned_get_position" [:pointer] :int)
(defcfn paned-set-wide-handle "gtk_paned_set_wide_handle" [:pointer :int] :void)

;; -- pages and disclosure (notebook, stack, revealer, expander) --------------
(defcfn notebook-new "gtk_notebook_new" [] :pointer)
(defcfn notebook-append-page "gtk_notebook_append_page" [:pointer :pointer :pointer] :int)
(defcfn notebook-page-num "gtk_notebook_page_num" [:pointer :pointer] :int)
(defcfn notebook-remove-page "gtk_notebook_remove_page" [:pointer :int] :void)
(defcfn notebook-set-current-page "gtk_notebook_set_current_page" [:pointer :int] :void)
(defcfn notebook-set-tab-pos "gtk_notebook_set_tab_pos" [:pointer :int] :void)
(defcfn notebook-set-tab-label-text "gtk_notebook_set_tab_label_text" [:pointer :pointer :string] :void)
(defcfn stack-new "gtk_stack_new" [] :pointer)
(defcfn stack-add-titled "gtk_stack_add_titled" [:pointer :pointer :string :string] :pointer)
(defcfn stack-remove "gtk_stack_remove" [:pointer :pointer] :void)
(defcfn stack-get-page "gtk_stack_get_page" [:pointer :pointer] :pointer)
(defcfn stack-page-set-name "gtk_stack_page_set_name" [:pointer :string] :void)
(defcfn stack-page-set-title "gtk_stack_page_set_title" [:pointer :string] :void)
(defcfn stack-set-visible-child-name "gtk_stack_set_visible_child_name" [:pointer :string] :void)
(defcfn stack-get-visible-child-name "gtk_stack_get_visible_child_name" [:pointer] :string)
(defcfn stack-set-transition-type "gtk_stack_set_transition_type" [:pointer :int] :void)
(defcfn stack-set-transition-duration "gtk_stack_set_transition_duration" [:pointer :int] :void)
(defcfn stack-set-hhomogeneous "gtk_stack_set_hhomogeneous" [:pointer :int] :void)
(defcfn stack-set-vhomogeneous "gtk_stack_set_vhomogeneous" [:pointer :int] :void)
(defcfn stack-sidebar-new "gtk_stack_sidebar_new" [] :pointer)
(defcfn stack-sidebar-set-stack "gtk_stack_sidebar_set_stack" [:pointer :pointer] :void)
(defcfn stack-switcher-new "gtk_stack_switcher_new" [] :pointer)
(defcfn stack-switcher-set-stack "gtk_stack_switcher_set_stack" [:pointer :pointer] :void)
(defcfn revealer-new "gtk_revealer_new" [] :pointer)
(defcfn revealer-set-child "gtk_revealer_set_child" [:pointer :pointer] :void)
(defcfn revealer-set-reveal-child "gtk_revealer_set_reveal_child" [:pointer :int] :void)
(defcfn revealer-set-transition-type "gtk_revealer_set_transition_type" [:pointer :int] :void)
(defcfn revealer-set-transition-duration "gtk_revealer_set_transition_duration" [:pointer :int] :void)
(defcfn expander-new "gtk_expander_new" [:string] :pointer)
(defcfn expander-set-child "gtk_expander_set_child" [:pointer :pointer] :void)
(defcfn expander-set-label "gtk_expander_set_label" [:pointer :string] :void)
(defcfn expander-set-expanded "gtk_expander_set_expanded" [:pointer :int] :void)
(defcfn expander-get-expanded "gtk_expander_get_expanded" [:pointer] :int)

;; -- window bars and controls -----------------------------------------------
(defcfn header-bar-new "gtk_header_bar_new" [] :pointer)
(defcfn header-bar-pack-start "gtk_header_bar_pack_start" [:pointer :pointer] :void)
(defcfn header-bar-pack-end "gtk_header_bar_pack_end" [:pointer :pointer] :void)
(defcfn header-bar-set-title-widget "gtk_header_bar_set_title_widget" [:pointer :pointer] :void)
(defcfn header-bar-remove "gtk_header_bar_remove" [:pointer :pointer] :void)
(defcfn header-bar-set-show-title-buttons "gtk_header_bar_set_show_title_buttons" [:pointer :int] :void)
(defcfn header-bar-set-decoration-layout "gtk_header_bar_set_decoration_layout" [:pointer :string] :void)
(defcfn action-bar-new "gtk_action_bar_new" [] :pointer)
(defcfn action-bar-pack-start "gtk_action_bar_pack_start" [:pointer :pointer] :void)
(defcfn action-bar-pack-end "gtk_action_bar_pack_end" [:pointer :pointer] :void)
(defcfn action-bar-set-center-widget "gtk_action_bar_set_center_widget" [:pointer :pointer] :void)
(defcfn action-bar-remove "gtk_action_bar_remove" [:pointer :pointer] :void)
(defcfn action-bar-set-revealed "gtk_action_bar_set_revealed" [:pointer :int] :void)
(defcfn window-controls-new "gtk_window_controls_new" [:int] :pointer)
(defcfn window-controls-set-side "gtk_window_controls_set_side" [:pointer :int] :void)
(defcfn window-controls-set-decoration-layout "gtk_window_controls_set_decoration_layout" [:pointer :string] :void)
(defcfn window-handle-new "gtk_window_handle_new" [] :pointer)
(defcfn window-handle-set-child "gtk_window_handle_set_child" [:pointer :pointer] :void)
(defcfn search-bar-new "gtk_search_bar_new" [] :pointer)
(defcfn search-bar-set-child "gtk_search_bar_set_child" [:pointer :pointer] :void)
(defcfn search-bar-set-search-mode "gtk_search_bar_set_search_mode" [:pointer :int] :void)
(defcfn search-bar-get-search-mode "gtk_search_bar_get_search_mode" [:pointer] :int)
(defcfn search-bar-set-show-close-button "gtk_search_bar_set_show_close_button" [:pointer :int] :void)

;; -- overlay ----------------------------------------------------------------
(defcfn overlay-new "gtk_overlay_new" [] :pointer)

;; -- scrolled window ---------------------------------------------------------

(defcfn viewport-new "gtk_viewport_new" [:pointer :pointer] :pointer)
(defcfn viewport-set-child "gtk_viewport_set_child" [:pointer :pointer] :void)
(defcfn viewport-set-scroll-to-focus "gtk_viewport_set_scroll_to_focus" [:pointer :int] :void)
(defcfn scrollbar-new "gtk_scrollbar_new" [:int :pointer] :pointer)
(defcfn scrollbar-get-adjustment "gtk_scrollbar_get_adjustment" [:pointer] :pointer)
(defcfn scrolled-window-new "gtk_scrolled_window_new" [] :pointer)
(defcfn scrolled-window-set-child "gtk_scrolled_window_set_child" [:pointer :pointer] :void)
(defcfn scrolled-window-set-policy "gtk_scrolled_window_set_policy" [:pointer :int :int] :void)
(defcfn overlay-set-child "gtk_overlay_set_child" [:pointer :pointer] :void)
(defcfn overlay-add-overlay "gtk_overlay_add_overlay" [:pointer :pointer] :void)
(defcfn overlay-remove-overlay "gtk_overlay_remove_overlay" [:pointer :pointer] :void)

;; -- image ------------------------------------------------------------------
(defcfn image-new-from-icon "gtk_image_new_from_icon_name" [:string] :pointer)
(defcfn image-new-from-file "gtk_image_new_from_file" [:string] :pointer)
(defcfn image-set-from-file "gtk_image_set_from_file" [:pointer :string] :void)
(defcfn image-set-from-icon "gtk_image_set_from_icon_name" [:pointer :string] :void)
(defcfn image-set-pixel-size "gtk_image_set_pixel_size" [:pointer :int] :void)

(defcfn picture-new "gtk_picture_new" [] :pointer)
(defcfn picture-set-filename "gtk_picture_set_filename" [:pointer :string] :void)
(defcfn picture-set-content-fit "gtk_picture_set_content_fit" [:pointer :int] :void)
(defcfn picture-set-can-shrink "gtk_picture_set_can_shrink" [:pointer :int] :void)
(defcfn picture-set-alternative-text "gtk_picture_set_alternative_text" [:pointer :string] :void)

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
(defcfn widget-queue-resize "gtk_widget_queue_resize" [:pointer] :void)
(defcfn widget-add-css-class "gtk_widget_add_css_class" [:pointer :string] :void)
(defcfn widget-remove-css-class "gtk_widget_remove_css_class" [:pointer :string] :void)
(defcfn widget-get-first-child "gtk_widget_get_first_child" [:pointer] :pointer)

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
(defcfn label-set-selectable "gtk_label_set_selectable" [:pointer :int] :void)
(defcfn label-set-ellipsize "gtk_label_set_ellipsize" [:pointer :int] :void)
(defcfn inscription-new "gtk_inscription_new" [:string] :pointer)
(defcfn inscription-set-text "gtk_inscription_set_text" [:pointer :string] :void)
(defcfn inscription-set-min-chars "gtk_inscription_set_min_chars" [:pointer :int] :void)
(defcfn inscription-set-nat-chars "gtk_inscription_set_nat_chars" [:pointer :int] :void)
(defcfn inscription-set-nat-lines "gtk_inscription_set_nat_lines" [:pointer :int] :void)
(defcfn inscription-set-text-overflow "gtk_inscription_set_text_overflow" [:pointer :int] :void)
(defcfn inscription-set-wrap-mode "gtk_inscription_set_wrap_mode" [:pointer :int] :void)
(defcfn inscription-set-xalign "gtk_inscription_set_xalign" [:pointer :float] :void)
(defcfn label-get-layout "gtk_label_get_layout" [:pointer] :pointer)
(defcfn label-get-layout-offsets "gtk_label_get_layout_offsets" [:pointer :pointer :pointer] :void)
(defcfn pango-index-to-pos "pango_layout_index_to_pos" [:pointer :int :pointer] :void)

(def PANGO-SCALE
  "Pango works in 1024ths of a pixel."
  1024)

;; -- button -----------------------------------------------------------------
(defcfn button-new-with-label "gtk_button_new_with_label" [:string] :pointer)
(defcfn button-set-label "gtk_button_set_label" [:pointer :string] :void)

(defcfn button-set-has-frame "gtk_button_set_has_frame" [:pointer :int] :void)
(defcfn button-get-has-frame "gtk_button_get_has_frame" [:pointer] :int)
(defcfn button-set-icon-name "gtk_button_set_icon_name" [:pointer :string] :void)
(defcfn button-get-icon-name "gtk_button_get_icon_name" [:pointer] :string)
(defcfn button-get-label "gtk_button_get_label" [:pointer] :string)
(defcfn toggle-button-new "gtk_toggle_button_new" [] :pointer)
(defcfn toggle-button-set-active "gtk_toggle_button_set_active" [:pointer :int] :void)
(defcfn toggle-button-get-active "gtk_toggle_button_get_active" [:pointer] :int)
(defcfn link-button-new "gtk_link_button_new" [:string] :pointer)
(defcfn link-button-set-uri "gtk_link_button_set_uri" [:pointer :string] :void)
(defcfn link-button-get-uri "gtk_link_button_get_uri" [:pointer] :string)
(defcfn switch-new "gtk_switch_new" [] :pointer)
(defcfn switch-set-active "gtk_switch_set_active" [:pointer :int] :void)
(defcfn switch-get-active "gtk_switch_get_active" [:pointer] :int)
(defcfn check-button-set-inconsistent "gtk_check_button_set_inconsistent" [:pointer :int] :void)
(defcfn check-button-get-inconsistent "gtk_check_button_get_inconsistent" [:pointer] :int)
(defcfn check-button-set-group "gtk_check_button_set_group" [:pointer :pointer] :void)
(defcfn string-list-new "gtk_string_list_new" [:pointer] :pointer)
(defcfn string-list-append "gtk_string_list_append" [:pointer :string] :void)
(defcfn string-list-get-string "gtk_string_list_get_string" [:pointer :int] :string)
(defcfn drop-down-new "gtk_drop_down_new" [:pointer :pointer] :pointer)
(defcfn drop-down-set-model "gtk_drop_down_set_model" [:pointer :pointer] :void)
(defcfn drop-down-get-model "gtk_drop_down_get_model" [:pointer] :pointer)
(defcfn drop-down-set-selected "gtk_drop_down_set_selected" [:pointer :int] :void)
(defcfn drop-down-get-selected "gtk_drop_down_get_selected" [:pointer] :int)

;; -- adjustment / range / scale ---------------------------------------------
(defcfn adjustment-configure "gtk_adjustment_configure" [:pointer :double :double :double :double :double :double] :void)
(defcfn adjustment-get-value "gtk_adjustment_get_value" [:pointer] :double)
(defcfn adjustment-set-value "gtk_adjustment_set_value" [:pointer :double] :void)
(defcfn spin-button-new-with-range "gtk_spin_button_new_with_range" [:double :double :double] :pointer)
(defcfn spin-button-get-adjustment "gtk_spin_button_get_adjustment" [:pointer] :pointer)
(defcfn spin-button-get-value "gtk_spin_button_get_value" [:pointer] :double)
(defcfn spin-button-get-value-as-int "gtk_spin_button_get_value_as_int" [:pointer] :int)
(defcfn spin-button-get-digits "gtk_spin_button_get_digits" [:pointer] :int)
(defcfn spin-button-set-digits "gtk_spin_button_set_digits" [:pointer :int] :void)
(defcfn spin-button-set-numeric "gtk_spin_button_set_numeric" [:pointer :int] :void)
(defcfn scale-new-with-range "gtk_scale_new_with_range" [:int :double :double :double] :pointer)
(defcfn range-get-adjustment "gtk_range_get_adjustment" [:pointer] :pointer)
(defcfn range-get-value "gtk_range_get_value" [:pointer] :double)
(defcfn range-set-inverted "gtk_range_set_inverted" [:pointer :int] :void)
(defcfn orientable-set-orientation "gtk_orientable_set_orientation" [:pointer :int] :void)

(defcfn separator-new "gtk_separator_new" [:int] :pointer)
(defcfn list-box-new "gtk_list_box_new" [] :pointer)
(defcfn list-box-append "gtk_list_box_append" [:pointer :pointer] :void)
(defcfn list-box-remove "gtk_list_box_remove" [:pointer :pointer] :void)
(defcfn list-box-set-selection-mode "gtk_list_box_set_selection_mode" [:pointer :int] :void)
(defcfn list-box-set-show-separators "gtk_list_box_set_show_separators" [:pointer :int] :void)
(defcfn list-box-get-row-at-index "gtk_list_box_get_row_at_index" [:pointer :int] :pointer)
(defcfn list-box-select-row "gtk_list_box_select_row" [:pointer :pointer] :void)
(defcfn list-box-unselect-all "gtk_list_box_unselect_all" [:pointer] :void)
(defcfn list-box-row-new "gtk_list_box_row_new" [] :pointer)
(defcfn list-box-row-set-child "gtk_list_box_row_set_child" [:pointer :pointer] :void)
(defcfn list-box-row-set-selectable "gtk_list_box_row_set_selectable" [:pointer :int] :void)
(defcfn list-box-row-set-activatable "gtk_list_box_row_set_activatable" [:pointer :int] :void)
(defcfn list-box-row-get-index "gtk_list_box_row_get_index" [:pointer] :int)

(defcfn flow-box-new "gtk_flow_box_new" [] :pointer)
(defcfn flow-box-insert "gtk_flow_box_insert" [:pointer :pointer :int] :void)
(defcfn flow-box-remove "gtk_flow_box_remove" [:pointer :pointer] :void)
(defcfn flow-box-set-selection-mode "gtk_flow_box_set_selection_mode" [:pointer :int] :void)
(defcfn flow-box-get-selection-mode "gtk_flow_box_get_selection_mode" [:pointer] :int)
(defcfn flow-box-set-min-children-per-line "gtk_flow_box_set_min_children_per_line" [:pointer :int] :void)
(defcfn flow-box-set-max-children-per-line "gtk_flow_box_set_max_children_per_line" [:pointer :int] :void)
(defcfn flow-box-set-row-spacing "gtk_flow_box_set_row_spacing" [:pointer :int] :void)
(defcfn flow-box-set-column-spacing "gtk_flow_box_set_column_spacing" [:pointer :int] :void)
(defcfn flow-box-set-homogeneous "gtk_flow_box_set_homogeneous" [:pointer :int] :void)
(defcfn flow-box-get-child-at-index "gtk_flow_box_get_child_at_index" [:pointer :int] :pointer)
(defcfn flow-box-select-child "gtk_flow_box_select_child" [:pointer :pointer] :void)
(defcfn flow-box-unselect-all "gtk_flow_box_unselect_all" [:pointer] :void)
(defcfn flow-box-child-new "gtk_flow_box_child_new" [] :pointer)
(defcfn flow-box-child-set-child "gtk_flow_box_child_set_child" [:pointer :pointer] :void)
(defcfn flow-box-child-is-selected "gtk_flow_box_child_is_selected" [:pointer] :int)

(defcfn scale-set-digits "gtk_scale_set_digits" [:pointer :int] :void)
(defcfn scale-set-draw-value "gtk_scale_set_draw_value" [:pointer :int] :void)
(defcfn scale-set-value-pos "gtk_scale_set_value_pos" [:pointer :int] :void)
(defcfn scale-button-new "gtk_scale_button_new" [:double :double :double :pointer] :pointer)
(defcfn scale-button-get-adjustment "gtk_scale_button_get_adjustment" [:pointer] :pointer)
(defcfn scale-button-get-value "gtk_scale_button_get_value" [:pointer] :double)
(defcfn scale-button-set-icons "gtk_scale_button_set_icons" [:pointer :pointer] :void)

;; -- progress / status ------------------------------------------------------
(defcfn progress-bar-new "gtk_progress_bar_new" [] :pointer)
(defcfn progress-bar-set-fraction "gtk_progress_bar_set_fraction" [:pointer :double] :void)
(defcfn progress-bar-set-show-text "gtk_progress_bar_set_show_text" [:pointer :int] :void)
(defcfn progress-bar-set-text "gtk_progress_bar_set_text" [:pointer :string] :void)
(defcfn spinner-new "gtk_spinner_new" [] :pointer)
(defcfn spinner-set-spinning "gtk_spinner_set_spinning" [:pointer :int] :void)
(defcfn level-bar-new "gtk_level_bar_new" [] :pointer)
(defcfn level-bar-set-value "gtk_level_bar_set_value" [:pointer :double] :void)
(defcfn level-bar-set-min-value "gtk_level_bar_set_min_value" [:pointer :double] :void)
(defcfn level-bar-set-max-value "gtk_level_bar_set_max_value" [:pointer :double] :void)

;; -- entry / editable -------------------------------------------------------
(defcfn entry-new "gtk_entry_new" [] :pointer)
(defcfn editable-get-text "gtk_editable_get_text" [:pointer] :string)
(defcfn editable-set-text "gtk_editable_set_text" [:pointer :string] :void)
(defcfn editable-set-editable "gtk_editable_set_editable" [:pointer :int] :void)
(defcfn object-set-string "g_object_set" [:pointer :string :& :string :pointer] :void)
(defcfn object-get-string "g_object_get" [:pointer :string :& :pointer :pointer] :void)
(defcfn g-free "g_free" [:pointer] :void)

;; a GtkTextBuffer hands its text out through two GtkTextIter stack structs
;; this layer has no layout for; the "text" property gives the same string for
;; one g_free
(defn object-string [object property]
  (with-open [arena (ffi/confined-arena)]
    (let [out (ffi/alloc arena :pointer)]
      (object-get-string object property out nil)
      (let [text (ffi/read out :pointer)]
        (try
          (ffi/ptr->string text)
          (finally (g-free text)))))))

(defcfn password-entry-new "gtk_password_entry_new" [] :pointer)
(defcfn password-entry-set-show-peek-icon "gtk_password_entry_set_show_peek_icon" [:pointer :int] :void)
(defcfn search-entry-new "gtk_search_entry_new" [] :pointer)
(defcfn editable-label-new "gtk_editable_label_new" [:string] :pointer)
(defcfn editable-label-get-editing "gtk_editable_label_get_editing" [:pointer] :int)
(defcfn editable-label-start-editing "gtk_editable_label_start_editing" [:pointer] :void)
(defcfn editable-label-stop-editing "gtk_editable_label_stop_editing" [:pointer :int] :void)
(defcfn text-view-new "gtk_text_view_new" [] :pointer)
(defcfn text-view-get-buffer "gtk_text_view_get_buffer" [:pointer] :pointer)
(defcfn text-buffer-set-text "gtk_text_buffer_set_text" [:pointer :string :int] :void)
(defcfn text-view-set-wrap-mode "gtk_text_view_set_wrap_mode" [:pointer :int] :void)
(defcfn text-view-set-editable "gtk_text_view_set_editable" [:pointer :int] :void)
(defcfn text-view-set-monospace "gtk_text_view_set_monospace" [:pointer :int] :void)
(defcfn text-view-set-left-margin "gtk_text_view_set_left_margin" [:pointer :int] :void)
(defcfn text-view-set-right-margin "gtk_text_view_set_right_margin" [:pointer :int] :void)
(defcfn text-view-set-top-margin "gtk_text_view_set_top_margin" [:pointer :int] :void)
(defcfn text-view-set-bottom-margin "gtk_text_view_set_bottom_margin" [:pointer :int] :void)
(defcfn frame-new "gtk_frame_new" [:string] :pointer)
(defcfn frame-set-label "gtk_frame_set_label" [:pointer :string] :void)
(defcfn frame-set-child "gtk_frame_set_child" [:pointer :pointer] :void)
(defcfn label-set-max-width-chars "gtk_label_set_max_width_chars" [:pointer :int] :void)


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

;; -- glib string arrays (char**) --------------------------------------------
(defcfn strv-builder-new "g_strv_builder_new" [] :pointer)
(defcfn strv-builder-add "g_strv_builder_add" [:pointer :string] :void)
(defcfn strv-builder-end "g_strv_builder_end" [:pointer] :pointer)
(defcfn strv-builder-unref "g_strv_builder_unref" [:pointer] :void)
(defcfn strfreev "g_strfreev" [:pointer] :void)

;; -- helpers ----------------------------------------------------------------

(defn null?
  "Whether a pointer C handed back is NULL.

   Necessary because a NULL return is *not* nil: it arrives as a live
   MemorySegment at address 0, so `nil?` and `some?` both lie about it. Walking
   a sibling chain with `nil?` runs off the end and GTK starts printing
   CRITICALs."
  [p]
  (ffi/null? p))
(def HORIZONTAL 0)
(def VERTICAL 1)

(def align
  "GtkAlign. :fill is the GTK default."
  {:fill 0 :start 1 :end 2 :center 3 :baseline 4})

(def policy
  "GtkPolicyType, for a scrolled window's scrollbars."
  {:always 0 :automatic 1 :never 2 :external 3})

(def orientation
  "GtkOrientation."
  {:horizontal HORIZONTAL :vertical VERTICAL})

(def position
  "GtkPositionType, where a scale draws its value. :top is the GTK default."
  {:left 0 :right 1 :top 2 :bottom 3})

(def pack-type
  "GtkPackType, the end of a header bar window controls stand at. :start is the
   GTK default."
  {:start 0 :end 1})

(def selection-mode
  "GtkSelectionMode, for a list box or a flow box. :single is the GTK default."
  {:none 0 :single 1 :browse 2 :multiple 3})

(def stack-transition
  "GtkStackTransitionType. :none is the GTK default."
  {:none 0 :crossfade 1 :slide-right 2 :slide-left 3 :slide-up 4 :slide-down 5
   :slide-left-right 6 :slide-up-down 7 :over-up 8 :over-down 9 :over-left 10
   :over-right 11 :under-up 12 :under-down 13 :under-left 14 :under-right 15
   :over-up-down 16 :over-down-up 17 :over-left-right 18 :over-right-left 19
   :rotate-left 20 :rotate-right 21 :rotate-left-right 22})

(def revealer-transition
  "GtkRevealerTransitionType. :slide-down is the GTK default."
  {:none 0 :crossfade 1 :slide-right 2 :slide-left 3 :slide-up 4 :slide-down 5})

(def ellipsize-mode
  "PangoEllipsizeMode, for a label. :none is the GTK default."
  {:none 0 :start 1 :middle 2 :end 3})

(def inscription-overflow
  "GtkInscriptionOverflow. :clip is the GTK default."
  {:clip 0 :ellipsize-start 1 :ellipsize-middle 2 :ellipsize-end 3})

(def content-fit
  "GtkContentFit, for a picture. :contain is the GTK default."
  {:fill 0 :contain 1 :cover 2 :scale-down 3})

(def pango-wrap-mode
  "PangoWrapMode, for an inscription. Not GtkWrapMode, which a text view uses:
   the same keywords map to different numbers there (:none is 0 in Gtk, 3 in
   Pango). :word-char is the inscription default."
  {:word 0 :char 1 :word-char 2 :none 3})

(defn ->gbool [x] (if x 1 0))
(defn <-gbool [x] (not (zero? x)))

(defcfn calendar-new "gtk_calendar_new" [] :pointer)
(defcfn calendar-get-date "gtk_calendar_get_date" [:pointer] :pointer)
(defcfn calendar-select-day "gtk_calendar_select_day" [:pointer :pointer] :void)
(defcfn date-time-new-local "g_date_time_new_local" [:int :int :int :int :int :double] :pointer)
(defcfn date-time-get-year "g_date_time_get_year" [:pointer] :int)
(defcfn date-time-get-month "g_date_time_get_month" [:pointer] :int)
(defcfn date-time-get-day "g_date_time_get_day_of_month" [:pointer] :int)
(defcfn date-time-unref "g_date_time_unref" [:pointer] :void)
(defcfn color-dialog-new "gtk_color_dialog_new" [] :pointer)
(defcfn color-dialog-set-title "gtk_color_dialog_set_title" [:pointer :string] :void)
(defcfn color-dialog-set-with-alpha "gtk_color_dialog_set_with_alpha" [:pointer :int] :void)
(defcfn color-dialog-button-new "gtk_color_dialog_button_new" [:pointer] :pointer)
(defcfn color-dialog-button-get-dialog "gtk_color_dialog_button_get_dialog" [:pointer] :pointer)
(defcfn color-dialog-button-set-rgba "gtk_color_dialog_button_set_rgba" [:pointer :pointer] :void)
(defcfn color-dialog-button-get-rgba "gtk_color_dialog_button_get_rgba" [:pointer] :pointer)
(defcfn font-dialog-new "gtk_font_dialog_new" [] :pointer)
(defcfn font-dialog-set-title "gtk_font_dialog_set_title" [:pointer :string] :void)
(defcfn font-dialog-button-new "gtk_font_dialog_button_new" [:pointer] :pointer)
(defcfn font-dialog-button-get-dialog "gtk_font_dialog_button_get_dialog" [:pointer] :pointer)
(defcfn font-dialog-button-set-font-desc "gtk_font_dialog_button_set_font_desc" [:pointer :pointer] :void)
(defcfn font-dialog-button-get-font-desc "gtk_font_dialog_button_get_font_desc" [:pointer] :pointer)
(defcfn font-description-from-string "pango_font_description_from_string" [:string] :pointer)
(defcfn font-description-to-string "pango_font_description_to_string" [:pointer] :pointer)
(defcfn font-description-free "pango_font_description_free" [:pointer] :void)
(defcfn menu-button-new "gtk_menu_button_new" [] :pointer)
(defcfn menu-button-set-icon-name "gtk_menu_button_set_icon_name" [:pointer :string] :void)
(defcfn menu-button-set-popover "gtk_menu_button_set_popover" [:pointer :pointer] :void)
(defcfn menu-button-set-label "gtk_menu_button_set_label" [:pointer :string] :void)
(defcfn emoji-chooser-new "gtk_emoji_chooser_new" [] :pointer)
(defcfn about-dialog-new "gtk_about_dialog_new" [] :pointer)
(defcfn about-dialog-set-program-name "gtk_about_dialog_set_program_name" [:pointer :string] :void)
(defcfn about-dialog-set-version "gtk_about_dialog_set_version" [:pointer :string] :void)
(defcfn about-dialog-set-comments "gtk_about_dialog_set_comments" [:pointer :string] :void)
(defcfn about-dialog-set-website "gtk_about_dialog_set_website" [:pointer :string] :void)
(defcfn about-dialog-set-authors "gtk_about_dialog_set_authors" [:pointer :pointer] :void)
(defcfn about-dialog-set-license-type "gtk_about_dialog_set_license_type" [:pointer :int] :void)
(defcfn window-set-transient-for "gtk_window_set_transient_for" [:pointer :pointer] :void)
(defcfn window-set-destroy-with-parent "gtk_window_set_destroy_with_parent" [:pointer :int] :void)
(defcfn window-set-modal "gtk_window_set_modal" [:pointer :int] :void)
(defcfn widget-set-visible "gtk_widget_set_visible" [:pointer :int] :void)
(defcfn object-set-data-full "g_object_set_data_full" [:pointer :string :pointer :pointer] :void)
(defcfn object-get-data "g_object_get_data" [:pointer :string] :pointer)

(ns gallery.sections.window-bars
  (:require [clojure.string :as str]
            [gallery.common :refer [example section showcase support]]))

(def initial-state
  {:bars {:last-action "Nothing clicked yet."
          :selection-mode false
          :own-title-buttons false
          :decoration-layout "close:minimize,maximize"}
   :search {:searching false :query ""}})

(defn- bars-example [state path]
  (let [{:keys [last-action selection-mode own-title-buttons decoration-layout]} (get-in state path)
        report (fn [title] [[:assoc-in (conj path :last-action) (str title " clicked.")]])
        ;; Either the bar draws its own title buttons from the layout, or explicit
        ;; window controls do; both at once would show every button twice.
        controls (fn [side]
                   (when-not own-title-buttons
                     [:window-controls {:slot side :side side :decoration-layout decoration-layout}]))]
    [example "HeaderBar / ActionBar / WindowControls / WindowHandle"
     [showcase
      [:frame
       [:vbox
        [:header-bar {:show-title-buttons own-title-buttons :decoration-layout decoration-layout}
         (controls :start)
         [:button {:icon "go-previous-symbolic" :tooltip "Back"
                   :on-click (report "Back")}]
         [:label {:slot :center :markup "<b>Document</b>"}]
         [:button {:slot :end :icon "open-menu-symbolic" :tooltip "Menu"
                   :on-click (report "Menu")}]
         (controls :end)]
        [:window-handle
         [:label {:margin 6} "Drag this strip to move the window"]]
        [:separator {:orientation :horizontal}]
        [:action-bar {:revealed selection-mode}
         [:label {:slot :start} "2 selected"]
         [:button {:slot :end :on-click (report "Delete")} "Delete"]
         [:button {:slot :end :on-click [[:assoc-in (conj path :selection-mode) false]]} "Cancel"]]]]]
     [support
      [:label {:wrap true :xalign 0.0 :max-width-chars 28}
       "The window controls act on the gallery window; drag the strip below the bar to move it."]
      [:label {:xalign 0.0 :wrap true :max-width-chars 28} "Decoration layout"]
      [:entry {:value decoration-layout :hexpand true
               :placeholder "icon:minimize,maximize,close"
               :tooltip "Window controls layout"
               :on-change [[:assoc-in (conj path :decoration-layout)]]}]
      [:check {:active own-title-buttons :halign :start
               :on-toggle [[:assoc-in (conj path :own-title-buttons)]]} "Bar's own title buttons"]
      [:check {:active selection-mode :halign :start
               :on-toggle [[:assoc-in (conj path :selection-mode)]]} "Selection mode"]
      [:label {:xalign 0.0 :wrap true :max-width-chars 28} last-action]]]))

(def ^:private fruit ["Apple" "Apricot" "Banana" "Blueberry" "Cherry" "Grape"])

(defn- search-example [state path]
  (let [{:keys [searching query]} (get-in state path)
        needle (str/lower-case query)
        matches (filter #(str/includes? (str/lower-case %) needle) fruit)]
    [example "SearchBar"
     [showcase
      [:search-bar {:search-mode-enabled searching :show-close-button true
                    :on-search-mode-enabled [[:assoc-in (conj path :searching)]]}
       [:search-entry {:value query :placeholder "Filter fruit" :tooltip "Fruit filter"
                       :on-change [[:assoc-in (conj path :query)]]}]]
      (into [:vbox {:spacing 4}]
            (for [name matches]
              [:label {:key name :xalign 0.0} name]))
      (when (empty? matches)
        [:label {:xalign 0.0} "No fruit matches."])]
     [support
      [:toggle {:active searching :halign :start
                :on-toggle [[:assoc-in (conj path :searching)]]} "Search"]]]))

(defn page [state]
  [section
   [bars-example state [:bars]]
   [search-example state [:search]]])

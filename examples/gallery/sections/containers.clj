(ns gallery.sections.containers
  (:require [gallery.common :refer [example section showcase support]]))

(def initial-state
  {:grid {:row-spacing 6 :column-spacing 6 :row-homogeneous false :column-homogeneous false}
   :center-box "Nothing clicked yet."
   :aspect-frame {:ratio 0 :obey-child false}
   :fixed {:x 24 :y 24}
   :paned {:orientation 0 :position 220 :wide-handle false}
   :overlay {:badge true :corner 0}})

(def ^:private grid-cells
  [{:text "Title" :column 0 :row 0 :column-span 2} {:text "Menu" :column 2 :row 0}
   {:text "Sidebar" :column 0 :row 1 :row-span 2} {:text "Content" :column 1 :row 1}
   {:text "Details" :column 2 :row 1} {:text "Status" :column 1 :row 2}
   {:text "Help" :column 2 :row 2}])

(defn- grid-example [state path]
  (let [{:keys [row-spacing column-spacing row-homogeneous column-homogeneous]} (get-in state path)]
    [example "Grid"
     [showcase
      (into [:grid {:row-spacing row-spacing
                    :column-spacing column-spacing
                    :row-homogeneous row-homogeneous
                    :column-homogeneous column-homogeneous}]
            (for [{:keys [text] :as cell} grid-cells]
              [:frame
               (assoc (select-keys cell [:column :row :column-span :row-span])
                      :key text
                      :hexpand true)
               [:label {:label text :margin 8}]]))]
     [support
      [:label {:label "Row spacing" :xalign 0.0 :wrap true :max-width-chars 28}]
      [:spin-button {:value row-spacing
                     :digits 0
                     :numeric true
                     :adjustment {:lower 0 :upper 32 :step-increment 1}
                     :tooltip "Space between rows"
                     :on-value-changed [[:assoc-in (conj path :row-spacing)]]
                     :halign :start}]
      [:label {:label "Column spacing" :xalign 0.0 :wrap true :max-width-chars 28}]
      [:spin-button {:value column-spacing
                     :digits 0
                     :numeric true
                     :adjustment {:lower 0 :upper 32 :step-increment 1}
                     :tooltip "Space between columns"
                     :on-value-changed [[:assoc-in (conj path :column-spacing)]]
                     :halign :start}]
      [:check {:label "Equal rows"
               :active row-homogeneous
               :on-toggle [[:assoc-in (conj path :row-homogeneous)]]
               :halign :start}]
      [:check {:label "Equal columns"
               :active column-homogeneous
               :on-toggle [[:assoc-in (conj path :column-homogeneous)]]
               :halign :start}]]]))
               
               (defn- center-box-example [state path]
               [example "CenterBox"
               [showcase
               [:frame
               [:center-box {:margin 6}
               [:button {:slot :start :label "Previous"
               :on-click [[:assoc-in path "Previous clicked."]]}]
      [:button {:slot :center :label "Play"
                :on-click [[:assoc-in path "Play clicked."]]}]
      [:button {:slot :end :label "Next"
                :on-click [[:assoc-in path "Next clicked."]]}]]]]
   [support
    [:label {:label (get-in state path) :xalign 0.0 :wrap true :max-width-chars 28}]]])

(def ^:private ratios
  [{:title "16:9" :ratio (/ 16.0 9.0)} {:title "4:3" :ratio (/ 4.0 3.0)} {:title "1:1" :ratio 1.0}])

(defn- aspect-frame-example [state path]
  (let [{:keys [ratio obey-child]} (get-in state path)
        {:keys [title] ratio-value :ratio} (nth ratios ratio)]
    [example "AspectFrame"
     [showcase
      [:frame
       [:aspect-frame {:ratio ratio-value
                       :obey-child obey-child
                       :xalign 0.5
                       :yalign 0.5
                       :height 150
                       :hexpand true}
        [:frame
         [:label {:label title}]]]]]
     [support
      [:dropdown {:items (mapv :title ratios)
                  :selected ratio
                  :tooltip "Aspect ratio"
                  :on-selected [[:assoc-in (conj path :ratio)]]
                  :halign :start}]
      [:check {:label "Obey child"
               :active obey-child
               :tooltip "Use the child's own natural ratio instead"
               :on-toggle [[:assoc-in (conj path :obey-child)]]
               :halign :start}]]]))
               
               (defn- fixed-example [state path]
               (let [{:keys [x y]} (get-in state path)]
               [example "Fixed"
               [showcase
               [:frame
               [:fixed {:height 160 :hexpand true}
               [:button {:label "Pinned button"
               :x x
               :y y
               :tooltip "Click to return to 24, 24"
               :on-click [[:assoc-in path {:x 24 :y 24}]]}]]]]
               [support
               [:label {:label "X" :xalign 0.0 :wrap true :max-width-chars 28}]
      [:spin-button {:value x
                     :digits 0
                     :numeric true
                     :adjustment {:lower 0 :upper 400 :step-increment 8 :page-increment 40}
                     :tooltip "Horizontal position in pixels"
                     :on-value-changed [[:assoc-in (conj path :x)]]
                     :halign :start}]
      [:label {:label "Y" :xalign 0.0 :wrap true :max-width-chars 28}]
      [:spin-button {:value y
                     :digits 0
                     :numeric true
                     :adjustment {:lower 0 :upper 120 :step-increment 8 :page-increment 40}
                     :tooltip "Vertical position in pixels"
                     :on-value-changed [[:assoc-in (conj path :y)]]
                     :halign :start}]
      [:label {:label (str "Button at " x ", " y)
               :xalign 0.0
               :wrap true
               :max-width-chars 28}]]]))

(defn- paned-example [state path]
  (let [{:keys [orientation position wide-handle]} (get-in state path)]
    [example "Paned"
     [showcase
      [:frame
       [:paned {:orientation (nth [:horizontal :vertical] orientation)
                :position position
                :wide-handle wide-handle
                :height 160
                :on-position [[:assoc-in (conj path :position)]]}
        [:frame {:slot :start :margin 6}
         [:label {:label "Sidebar" :margin 8}]]
        [:frame {:slot :end :margin 6}
         [:label {:label "Content" :margin 8}]]]]]
     [support
      [:dropdown {:items ["Side by side" "Stacked"]
                  :selected orientation
                  :tooltip "Pane direction"
                  :on-selected [[:assoc-in (conj path :orientation)]]
                  :halign :start}]
      [:label {:label "Divider" :xalign 0.0 :wrap true :max-width-chars 28}]
      [:spin-button {:value position
                     :digits 0
                     :numeric true
                     :adjustment {:lower 0 :upper 600 :step-increment 10 :page-increment 50}
                     :tooltip "Divider position in pixels"
                     :on-value-changed [[:assoc-in (conj path :position)]]
                     :halign :start}]
      [:check {:label "Wide handle"
               :active wide-handle
               :on-toggle [[:assoc-in (conj path :wide-handle)]]
               :halign :start}]
      [:label {:label (str "Divider at " position " px")
               :xalign 0.0
               :wrap true
               :max-width-chars 28}]]]))

(def ^:private corners
  [{:title "Top right" :halign :end :valign :start}
   {:title "Top left" :halign :start :valign :start}
   {:title "Bottom right" :halign :end :valign :end}
   {:title "Bottom left" :halign :start :valign :end}])

(defn- overlay-example [state path]
  (let [{:keys [badge corner]} (get-in state path)
        {:keys [halign valign]} (nth corners corner)]
    [example "Overlay"
     [showcase
      [:overlay
       [:frame
        [:label {:label "Content underneath" :height 120 :hexpand true}]]
       (when badge
         [:frame {:key :badge :slot :over :halign halign :valign valign :margin 8}
          [:label {:label "3 new"
                   :margin-start 8
                   :margin-end 8
                   :margin-top 2
                   :margin-bottom 2}]])]]
     [support
      [:check {:label "Show badge"
               :active badge
               :on-toggle [[:assoc-in (conj path :badge)]]
               :halign :start}]
      [:dropdown {:items (mapv :title corners)
                  :selected corner
                  :tooltip "Badge corner"
                  :on-selected [[:assoc-in (conj path :corner)]]
                  :halign :start}]]]))

(defn page [state]
  [section
   [grid-example state [:grid]]
   [center-box-example state [:center-box]]
   [aspect-frame-example state [:aspect-frame]]
   [fixed-example state [:fixed]]
   [paned-example state [:paned]]
   [overlay-example state [:overlay]]])

(ns gallery.sections.boxes
  (:require [gallery.common :refer [example section showcase support]]))

(def initial-state
  {:box {:orientation 0 :spacing 8 :homogeneous false}
   :alignment {:direction 0 :alignment 0 :expand false :margin 8}})

(defn- box-example [state path]
  (let [{:keys [orientation spacing homogeneous]} (get-in state path)]
    [example "Box"
     [showcase
      [:box {:orientation (nth [:horizontal :vertical] orientation)
             :spacing spacing
             :homogeneous homogeneous}
       [:frame
        [:label {:label "Short" :margin 8}]]
       [:frame
        [:label {:label "A longer label" :margin 8}]]
       [:frame
        [:label {:label "Two\nlines" :margin 8}]]]]
     [support
      [:dropdown {:items ["Horizontal" "Vertical"]
                  :selected orientation
                  :tooltip "Box direction"
                  :on-selected [[:assoc-in (conj path :orientation)]]
                  :halign :start}]
      [:label {:label "Spacing" :xalign 0.0 :wrap true :max-width-chars 28}]
      [:spin-button {:value spacing
                     :digits 0
                     :numeric true
                     :adjustment {:lower 0 :upper 32 :step-increment 1}
                     :tooltip "Space between children"
                     :on-value-changed [[:assoc-in (conj path :spacing)]]
                     :halign :start}]
      [:check {:label "Equal sizes"
               :active homogeneous
               :on-toggle [[:assoc-in (conj path :homogeneous)]]
               :halign :start}]]]))

(defn- alignment-example [state path]
  (let [{:keys [direction alignment expand margin]} (get-in state path)
        horizontal? (zero? direction)
        align (nth [:fill :start :center :end] alignment)
        target-props (if horizontal?
                       {:halign align :valign :fill :hexpand expand :vexpand false}
                       {:halign :fill :valign align :hexpand false :vexpand expand})]
    [example "Box (alignment and expansion)"
     [showcase
      [:frame
       [:box {:orientation (if horizontal? :horizontal :vertical) :height 160}
        [:frame (assoc target-props :margin margin)
         [:label {:label "Target" :margin 8}]]
        [:label {:label "Spare space" :hexpand horizontal? :vexpand (not horizontal?) :margin 8}]]]]
     [support
      [:dropdown {:items ["Horizontal" "Vertical"]
                  :selected direction
                  :tooltip "Preview direction"
                  :on-selected [[:assoc-in (conj path :direction)]]
                  :halign :start}]
      [:dropdown {:items ["Fill" "Start" "Center" "End"]
                  :selected alignment
                  :tooltip "Alignment within the allocated space"
                  :on-selected [[:assoc-in (conj path :alignment)]]
                  :halign :start}]
      [:check {:label "Expand"
               :active expand
               :tooltip "Request a share of the spare space"
               :on-toggle [[:assoc-in (conj path :expand)]]
               :halign :start}]
      [:label {:label "Margin" :xalign 0.0 :wrap true :max-width-chars 28}]
      [:spin-button {:value margin
                     :digits 0
                     :numeric true
                     :adjustment {:lower 0 :upper 24 :step-increment 1}
                     :tooltip "Space around the target"
                     :on-value-changed [[:assoc-in (conj path :margin)]]
                     :halign :start}]]]))

(defn- groups-example []
  [example "Frame / Separator"
   [showcase
    [:frame {:label "Document"}
     [:vbox {:spacing 12 :margin 12}
      [:label {:label "Project notes" :xalign 0.0}]
      [:separator {:orientation :horizontal}]
      [:hbox {:spacing 12}
       [:vbox {:spacing 4 :hexpand true}
        [:label {:markup "<b>Draft</b>" :xalign 0.0}]
        [:label {:label "Local copy" :xalign 0.0}]]
       [:separator {:orientation :vertical}]
       [:vbox {:spacing 4 :hexpand true}
        [:label {:markup "<b>Published</b>" :xalign 0.0}]
        [:label {:label "Shared copy" :xalign 0.0}]]]]]]
   nil])

(defn page [state]
  [section
   [box-example state [:box]]
   [alignment-example state [:alignment]]
   [groups-example]])

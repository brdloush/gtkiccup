(ns gallery.sections.pages
  (:require [clojure.string :as str]
            [gallery.common :refer [example section showcase support]]))

(def initial-state
  {:tabs {:tab 0 :position 0}
   :stack {:page "notes" :transition 0 :visits 0
           :note "A note that stays here." :notifications true}
   :switcher "inbox"
   :revealer {:revealed false :transition 0 :duration 250}
   :expander {:expanded false :autosave true}})

(def ^:private tabs
  [{:key :today :title "Today" :text "Two meetings and one deadline."}
   {:key :week :title "Week" :text "Review on Thursday."}
   {:key :month :title "Month" :text "Release at the end of the month."}])

(defn- next-tab [index]
  (mod (inc index) (count tabs)))

(defn- tabs-example [state path]
  (let [{:keys [tab position]} (get-in state path)]
    [example "Notebook"
     [showcase
      (into [:notebook {:page tab
                        :tab-pos (nth [:top :bottom :left :right] position)
                        :on-page [[:assoc-in (conj path :tab)]]}]
            (for [{:keys [key title text]} tabs]
              [:notebook-page {:key key :tab title :margin 12}
               [:label {:label text :xalign 0.0}]]))]
     [support
      [:label {:label "Tab position" :xalign 0.0 :wrap true :max-width-chars 28}]
      [:dropdown {:items ["Top" "Bottom" "Left" "Right"] :selected position
                  :tooltip "Tab position" :halign :start
                  :on-selected [[:assoc-in (conj path :position)]]}]
      [:button {:label "Next tab" :halign :start
                :on-click [[:update-in (conj path :tab) next-tab]]}]
      [:label {:label (str "Current tab: " (:title (nth tabs tab)))
               :xalign 0.0 :wrap true :max-width-chars 28}]]]))

(defn- switcher-example [state path]
  (let [page (get-in state path)]
    [example "StackSwitcher"
     [showcase
      [:stack-switcher
       [:stack {:visible-child-name page :transition-type :crossfade
                :transition-duration 150 :hhomogeneous true :vhomogeneous true
                :on-visible-child-name [[:assoc-in path]]}
        [:stack-page {:key :inbox :name "inbox" :title "Inbox" :margin 12}
         [:label {:label "Three unread messages." :xalign 0.0}]]
        [:stack-page {:key :archive :name "archive" :title "Archive" :margin 12}
         [:label {:label "Nothing archived yet." :xalign 0.0}]]
        [:stack-page {:key :trash :name "trash" :title "Trash" :margin 12}
         [:label {:label "Trash is empty." :xalign 0.0}]]]]]
     [support
      [:label {:label (str "Showing: " (str/capitalize page))
               :xalign 0.0 :wrap true :max-width-chars 28}]]]))

(defn- stack-example [state path]
  (let [{:keys [page transition visits note notifications]} (get-in state path)]
    [example "Stack / StackSidebar"
     [showcase
      [:frame
       [:stack-sidebar {:height 160}
        [:stack {:visible-child-name page :transition-duration 250
                 :transition-type (nth [:slide-left-right :crossfade :none] transition)
                 :hhomogeneous true :vhomogeneous true :hexpand true
                 :on-visible-child-name [[:assoc-in (conj path :page)]]}
         [:stack-page {:key :overview :name "overview" :title "Overview" :spacing 8 :margin 12}
          [:label {:label (str "Visits recorded: " visits) :xalign 0.0}]
          [:button {:label "Record a visit" :halign :start
                    :on-click [[:update-in (conj path :visits) inc]]}]]
         [:stack-page {:key :notes :name "notes" :title "Notes" :spacing 8 :margin 12}
          [:label {:label "Saved in this example while you switch pages" :xalign 0.0}]
          [:entry {:value note :placeholder "Write a note" :tooltip "Page note"
                   :on-change [[:assoc-in (conj path :note)]]}]]
         [:stack-page {:key :settings :name "settings" :title "Settings" :spacing 8 :margin 12}
          [:check {:label "Enable notifications" :active notifications
                   :on-toggle [[:assoc-in (conj path :notifications)]]}]]]]]]
     [support
      [:label {:label "Switch pages with the buttons or the sidebar, then return to find your note and counter unchanged."
               :wrap true :xalign 0.0 :max-width-chars 28}]
      [:button {:label "Overview" :halign :start
                :on-click [[:assoc-in (conj path :page) "overview"]]}]
      [:button {:label "Notes" :halign :start
                :on-click [[:assoc-in (conj path :page) "notes"]]}]
      [:button {:label "Settings" :halign :start
                :on-click [[:assoc-in (conj path :page) "settings"]]}]
      [:label {:label "Page transition" :xalign 0.0 :wrap true :max-width-chars 28}]
      [:dropdown {:items ["Slide" "Crossfade" "None"] :selected transition
                  :tooltip "Page transition" :halign :start
                  :on-selected [[:assoc-in (conj path :transition)]]}]
      [:label {:label (str "Current page: " page)
               :xalign 0.0 :wrap true :max-width-chars 28}]]]))

(defn- revealer-example [state path]
  (let [{:keys [revealed transition duration]} (get-in state path)]
    [example "Revealer"
     [showcase
      [:revealer {:reveal-child revealed :transition-duration duration
                  :transition-type (nth [:slide-down :crossfade :none] transition)}
       [:frame
        [:vbox {:spacing 8 :margin 12}
         [:label {:markup "<b>Document details</b>" :xalign 0.0}]
         [:label {:label "Draft · Local copy · Ready to edit" :xalign 0.0 :wrap true}]]]]]
     [support
      [:check {:label "Show details" :active revealed :halign :start
               :on-toggle [[:assoc-in (conj path :revealed)]]}]
      [:dropdown {:items ["Slide down" "Crossfade" "None"] :selected transition
                  :tooltip "Details transition" :halign :start
                  :on-selected [[:assoc-in (conj path :transition)]]}]
      [:label {:label "Duration (ms)" :xalign 0.0 :wrap true :max-width-chars 28}]
      [:spin-button {:value duration :digits 0 :numeric true
                     :adjustment {:lower 0 :upper 1000 :step-increment 50 :page-increment 250}
                     :tooltip "Details transition duration in milliseconds" :halign :start
                     :on-value-changed [[:update-in (conj path :duration)
                                         (fn [_ value] (int value))]]}]]]))

(defn- expander-example [state path]
  (let [{:keys [expanded autosave]} (get-in state path)]
    [example "Expander"
     [showcase
      [:expander {:label "Advanced options" :expanded expanded
                  :on-expanded [[:assoc-in (conj path :expanded)]]}
       [:vbox {:spacing 8 :margin 12}
        [:check {:label "Save automatically" :active autosave
                 :on-toggle [[:assoc-in (conj path :autosave)]]}]
        [:label {:label "Closing these options keeps their values." :wrap true :xalign 0.0}]]]]
     [support
      [:label {:label (if expanded "Advanced options are open." "Advanced options are closed.")
               :xalign 0.0 :wrap true :max-width-chars 28}]]]))

(defn page [state]
  [section
   [tabs-example state [:tabs]]
   [stack-example state [:stack]]
   [switcher-example state [:switcher]]
   [revealer-example state [:revealer]]
   [expander-example state [:expander]]])

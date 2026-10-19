(ns gallery.sections.buttons
  (:require [gallery.common :refer [example section showcase support]]))

(def initial-state {:clicks 0 :toggle false :checked false :switched false
                    :selected 0})
(def choices ["Small" "Medium" "Large"])

(defn- button-example [state path]
  [example "Button"
   [showcase
    [:hbox {:spacing 8 :halign :start}
     [:button {:on-click [[:update-in path inc]]} "Click me"]
     [:button {:has-frame false :on-click [[:update-in path inc]]} "Flat"]
     [:button {:icon "list-add-symbolic" :tooltip "Add one click"
               :on-click [[:update-in path inc]]}]
     [:button {:sensitive false} "Disabled"]]]
   [support [:label {:xalign 0.0} "Clicks: " (get-in state path)]]])

(defn- toggle-example [state path]
  (let [active? (get-in state path)]
    [example "ToggleButton"
     [showcase
      [:toggle {:active active? :halign :start
                :on-toggle [[:assoc-in path]]} "Pinned"]]
     [support [:label (if active? "Pinned" "Unpinned")]]]))

(defn- link-example []
  [example "LinkButton"
   [showcase [:link {:uri "https://docs.gtk.org/gtk4/" :halign :start}
              "GTK documentation"]]
   nil])

(defn- check-example [state path]
  (let [checked? (get-in state path)]
    [example "CheckButton"
     [showcase
      [:check {:active checked? :on-toggle [[:assoc-in path]]
               :halign :start} "Receive updates"]
      [:check {:inconsistent true :sensitive false :halign :start} "Mixed selection"]]
     [support [:label (if checked? "Subscribed" "Unsubscribed")]]]))

(defn- switch-example [state path]
  (let [active? (get-in state path)]
    [example "Switch"
     [showcase [:switch {:active active? :halign :start
                        :tooltip "Enable notifications"
                        :on-active [[:assoc-in path]]}]]
     [support [:label (if active? "Notifications on" "Notifications off")]]]))

(defn- dropdown-example [state path]
  (let [selected (get-in state path)]
    [example "DropDown"
     [showcase [:dropdown {:items choices :selected selected :halign :start
                          :tooltip "Size" :on-selected [[:assoc-in path]]}]]
     [support [:label "Selected: " (get choices selected "None")]]]))

(defn page [state]
  [section
   [button-example state [:clicks]]
   [toggle-example state [:toggle]]
   [link-example]
   [check-example state [:checked]]
   [switch-example state [:switched]]
   [dropdown-example state [:selected]]])

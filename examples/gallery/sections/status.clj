(ns gallery.sections.status
  (:require [gallery.common :refer [example section showcase support]]))

(def initial-state {:progress 0 :spinning false :level 0.0})

(defn- progress-bar-example [state path]
  (let [progress (get-in state path)]
    [example "ProgressBar"
     [showcase
      [:progress-bar {:fraction (/ progress 100.0)
                      :show-text true
                      :text (str progress "%")
                      :tooltip "Task progress"
                      :hexpand true}]]
     [support
      [:button {:sensitive (< progress 100)
                :on-click [[:update-in path + 10]]
                :halign :start} "Advance 10%"]
      [:button {:on-click [[:assoc-in path 0]] :halign :start} "Reset"]]]))

(defn- spinner-example [state path]
  (let [spinning? (get-in state path)]
    [example "Spinner"
     [showcase
      [:spinner {:spinning spinning?
                 :width 24 :height 24
                 :tooltip "Activity"
                 :halign :start}]]
     [support
      [:label {:label (if spinning? "Running" "Stopped")
               :xalign 0.0 :wrap true :max-width-chars 28}]
      [:button {:on-click [[:update-in path not]] :halign :start}
       (if spinning? "Stop" "Start")]]]))

(defn- level-bar-example [state path]
  (let [level (get-in state path)]
    [example "LevelBar"
     [showcase
      [:level {:value level :tooltip "Level" :hexpand true}]]
     [support
      [:label {:label (str "Level: " (int (* 100 level)) "%")
               :xalign 0.0 :wrap true :max-width-chars 28}]
      [:button {:on-click [[:assoc-in path 0.0]] :halign :start} "Empty"]
      [:button {:on-click [[:assoc-in path 0.25]] :halign :start} "Low"]
      [:button {:on-click [[:assoc-in path 0.75]] :halign :start} "High"]
      [:button {:on-click [[:assoc-in path 1.0]] :halign :start} "Full"]]]))

(defn page [state]
  [section
   [progress-bar-example state [:progress]]
   [spinner-example state [:spinning]]
   [level-bar-example state [:level]]])

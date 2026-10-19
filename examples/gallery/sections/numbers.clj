(ns gallery.sections.numbers
  (:require [gallery.common :refer [example section showcase support]]))

;; GTK orders the lowest and highest icons first, then the intermediate levels.
(def ^:private volume-icons
  ["audio-volume-muted-symbolic" "audio-volume-high-symbolic" "audio-volume-low-symbolic"
   "audio-volume-medium-symbolic"])

(def initial-state
  {:spin-button {:quantity 3 :amount 1.5} :horizontal 25.0
   :vertical {:value 50.0 :inverted false} :decimal {:value 1.5 :precision 0} :volume 50.0})

(defn- spin-button-example [state path]
  (let [{:keys [quantity amount]} (get-in state path)]
    [example "SpinButton"
     [showcase
      [:spin-button {:adjustment {:lower 0.0 :upper 10.0 :step-increment 1.0 :page-increment 5.0}
                     :digits 0 :numeric true :value quantity
                     :tooltip "0–10, step 1" :halign :start
                     :on-value-changed [[:assoc-in (conj path :quantity)]]}]
      [:spin-button {:adjustment {:lower 0.0 :upper 5.0 :step-increment 0.25 :page-increment 1.0}
                     :digits 2 :numeric true :value amount
                     :tooltip "0–5, step 0.25" :halign :start
                     :on-value-changed [[:assoc-in (conj path :amount)]]}]]
     [support
      [:label {:label (str "Integer: " quantity) :xalign 0.0 :wrap true :max-width-chars 28}]
      [:label {:label (str "Decimal: " amount) :xalign 0.0 :wrap true :max-width-chars 28}]]]))

(defn- horizontal-scale-example [state path]
  (let [value (get-in state path)]
    [example "Scale (horizontal)"
     [showcase
      [:scale {:orientation :horizontal :value value :digits 0 :draw-value false
               :hexpand true :tooltip "0–100, step 1"
               :on-value-changed [[:assoc-in path]]}]]
     [support [:label {:label (str "Value: " (int value)) :xalign 0.0}]]]))

(defn- vertical-scale-example [state path]
  (let [{:keys [value inverted]} (get-in state path)]
    [example "Scale (vertical)"
     [showcase
      [:scale {:orientation :vertical :value value :digits 0 :draw-value true
               :value-pos :right :inverted inverted :height 160 :halign :start
               :tooltip "Vertical value, 0–100"
               :on-value-changed [[:assoc-in (conj path :value)]]}]]
     [support
      [:check {:active inverted :halign :start
               :on-toggle [[:assoc-in (conj path :inverted)]]} "Invert direction"]
      [:label {:label (str "Value: " (int value)) :xalign 0.0}]]]))

(defn- decimal-scale-example [state path]
  (let [{:keys [value precision]} (get-in state path)]
    [example "Scale (decimal)"
     [showcase
      [:scale {:orientation :horizontal
               :adjustment {:lower 0.0 :upper 5.0 :step-increment 0.25 :page-increment 1.0}
               :value value :digits (+ 2 precision) :draw-value true :hexpand true
               :tooltip "0–5, keyboard step 0.25"
               :on-value-changed [[:assoc-in (conj path :value)]]}]]
     [support
      [:hbox {:spacing 8 :halign :start}
       [:label "Decimal places"]
       [:dropdown {:items ["2" "3"] :selected precision :tooltip "Displayed precision"
                   :on-selected [[:assoc-in (conj path :precision)]]}]]
      [:label {:label "Use arrow keys for quarter steps; drag for finer changes."
               :wrap true :max-width-chars 28 :xalign 0.0}]]]))

(defn- scale-button-example [state path]
  (let [volume (get-in state path)]
    [example "ScaleButton"
     [showcase
      [:scale-button {:icons volume-icons :value volume
                      :adjustment {:step-increment 5.0 :page-increment 20.0}
                      :tooltip "Volume" :halign :start
                      :on-value-changed [[:assoc-in path]]}]]
     [support
      [:label {:label (str "Volume: " (int volume)) :xalign 0.0 :wrap true :max-width-chars 28}]
      [:button {:on-click [[:assoc-in path 0.0]] :halign :start} "Mute"]]]))

(defn page [state]
  [section
   [spin-button-example state [:spin-button]]
   [horizontal-scale-example state [:horizontal]]
   [vertical-scale-example state [:vertical]]
   [decimal-scale-example state [:decimal]]
   [scale-button-example state [:volume]]])

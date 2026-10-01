(ns counter
  "The glimmer-ui counter example, in babashka + GTK4."
  (:require [gtkiccup.core :as ui]
            [gtkiccup.ratom :as r]))

(defn counter []
  (let [n (r/atom 0)]
    (fn []
      [:vbox {:spacing 12 :margin 16}
       [:label "Count: " @n]
       [:hbox {:spacing 8}
        [:button {:label "- 1" :on-click #(swap! n dec)}]
        [:button {:label "+ 1" :on-click #(swap! n inc)}]
        [:button {:label "reset" :on-click #(reset! n 0)
                  :sensitive (not= 0 @n)}]]])))

(defn -main [& _]
  (ui/run (counter) :title "counter" :width 320 :height 160))

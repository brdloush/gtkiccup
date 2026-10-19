(ns gallery.sections.scrolling
  (:require [gallery.common :refer [example section showcase support]]))

(def initial-state
  {:scroll-to-focus true})

(defn- scrolled-window-example []
  [example "ScrolledWindow"
   [showcase
    [:frame
     [:scrolled {:height 180 :vexpand false}
      (into [:vbox {:spacing 12 :margin 12}]
            (for [number (range 1 31)]
              [:label {:key number :label (str "Row " number) :xalign 0.0}]))]]]
   nil])

(defn- viewport-example [state path]
  [example "Viewport"
   [showcase
    [:frame
     [:scrolled {:height 160 :vexpand false}
      [:viewport {:scroll-to-focus (get-in state path)}
       (into [:vbox {:spacing 8 :margin 12}]
             (for [number (range 1 21)]
               [:button {:key number :label (str "Focusable row " number) :halign :start}]))]]]]
   [support
    [:check {:label "Scroll to focus"
             :active (get-in state path)
             :tooltip "Scroll a focused row into view when tabbing through them"
             :on-toggle [[:assoc-in path]]
             :halign :start}]]])

(defn page [state]
  [section
   [scrolled-window-example]
   [viewport-example state [:scroll-to-focus]]])

(ns counter
  "The glimmer-ui counter example, in babashka + GTK4.

   The handlers are DATA -- `[:counter/inc]` vectors -- sent to a single dispatch
   fn installed with `ui/set-dispatch!`. A data handler is a plain value, so each
   button's `:on-click` is `=`-stable across renders rather than a fresh closure
   born unequal every tick -- which is exactly what lets a component's cached
   expansion hit. See docs/widgets.md (#handlers-as-data) and docs/internals.md."
  (:require [gtkiccup.core :as ui]
            [gtkiccup.ratom :as r]))

;; State lives at the top level so the one dispatch fn below can reach it. The
;; `defonce` keeps the count across hot reloads in the REPL.
(defonce n (r/atom 0))

;; Every data handler arrives here as (f event-map [action & params]).
(ui/set-dispatch!
  (fn [_ [action]]
    (case action
      :counter/dec   (swap! n dec)
      :counter/inc   (swap! n inc)
      :counter/reset (reset! n 0))))

(defn counter []
  [:vbox {:spacing 12 :margin 16}
   [:label "Count: " @n]
   [:hbox {:spacing 8}
    [:button {:label "- 1" :on-click [:counter/dec]}]
    [:button {:label "+ 1" :on-click [:counter/inc]}]
    [:button {:label "reset" :on-click [:counter/reset]
              :sensitive (not= 0 @n)}]]])

(defn -main [& _]
  ;; `counter` is passed as the component fn; `run` calls it on every render.
  (ui/run counter :title "counter" :width 320 :height 160))

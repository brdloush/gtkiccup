(ns todo
  "Slightly bigger demo: dynamic child list, entry, check buttons.
   Shows the reconciler adding and removing widgets.

   The list rows are a late-bound ALIAS (`:todo/row`, cached on `{:i :text
   :done?}`) and every action is a DATA handler dispatched by the one
   `ui/set-dispatch!` fn below -- so typing in the entry, or toggling one row,
   re-renders `home` but re-expands only the rows whose attrs actually changed.
   Handlers follow the `[action & args]` idiom; the value a signal produces
   (the entry text, the toggled bool) arrives in `:gtkiccup/args`, since GTK has
   no JS event object to interpolate placeholders from.
   See docs/widgets.md (#components-as-aliases, #handlers-as-data)."
  (:require [clojure.string :as str]
            [gtkiccup.core :as ui]
            [gtkiccup.alias :as ga]
            [gtkiccup.ratom :as r]))

;; State is top level so the one dispatch fn can reach it; `defonce` keeps the
;; list across hot reloads while developing.
(defonce state (r/atom {:draft "" :items []}))

(defn- add-item! []
  (when (seq (str/trim (:draft @state)))
    (swap! state #(-> %
                      (update :items conj {:text (:draft %) :done? false})
                      (assoc :draft "")))))

;; One dispatch fn receives every data handler as (f event-map [action & args]).
;; The entry/check signals hand their produced value through :gtkiccup/args.
(ui/set-dispatch!
  (fn [{:gtkiccup/keys [args]} [action i]]
    (case action
      :todo/draft  (swap! state assoc :draft (first args))
      :todo/add    (add-item!)
      :todo/toggle (swap! state assoc-in [:items i :done?] (first args))
      :todo/delete (swap! state update :items
                          #(vec (concat (subvec % 0 i) (subvec % (inc i))))))))

;; A row is a late-bound alias: the body runs only when its {:i :text :done?}
;; attrs change, so the whole hbox subtree is skipped on an unrelated re-render.
(ga/register-alias! :todo/row
  {:purity :pure}
  (fn [{:keys [i text done?]} _children]
    [:hbox {:spacing 8}
     [:check {:label text :active done? :hexpand true
              :on-toggle [:todo/toggle i]}]
     [:button {:label "x" :on-click [:todo/delete i]}]]))

(defn home []
  (let [{:keys [draft items]} @state]
    [:vbox {:spacing 10 :margin 16}
     [:hbox {:spacing 8}
      [:entry {:value draft
               :hexpand true
               :on-change [:todo/draft]
               :on-activate [:todo/add]}]
      [:button {:label "Add" :on-click [:todo/add]
                :sensitive (seq (str/trim draft))}]]
     [:vbox {:spacing 4}
      (map-indexed (fn [i {:keys [text done?]}]
                     [:todo/row {:i i :text text :done? done?}])
                   items)]
     [:label {:label (str (count (filter :done? items)) " / " (count items) " done")}]]))

(defn -main [& _]
  (ui/run home :title "todo" :width 420 :height 320))

(comment
  ;; start it on its own thread so the REPL prompt stays free
  (def app-thread (future (ui/run home :title "todo" :width 420 :height 320)))

  ;; Pick one and you can edit `home` above, hit eval, and see it immediately.
  (require '[gtkiccup.dev :as dev])

  (dev/auto-refresh!)        ; re-render on a timer. no registration at all, catches all
  (dev/watch-ns! 'todo)      ; re-render when a fn already in this ns is redefined
  (dev/watch-files! "src" "examples")   ; or edit and save, with no REPL at all

  (dev/status)

  ;; Without any of those, a redef sits pending until something re-renders --
  ;; either an interaction that changes state, or:
  (ui/refresh!)

  ;; poke at the live tree
  (:window @ui/current)
  (-> @ui/current :tree :children)

  ;; shut it all down
  (dev/stop!)
  (ui/close!))

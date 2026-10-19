(ns gallery.sections.labels
  (:require [gallery.common :refer [example section showcase support]]))

(def initial-state {:inscription {:nat-lines 2 :overflow 3}})

(def ^:private long-text
  (str "Inscriptions show as much of their text as fits the space they are given, "
       "which suits list items and dashboards where a label would grow to fit. "
       "This one shares its text with the label beside it, so the natural lines "
       "and overflow controls beside it show exactly what gets cut off."))

(def ^:private overflows
  [{:title "Clip" :mode :clip} {:title "Ellipsize start" :mode :ellipsize-start}
   {:title "Ellipsize middle" :mode :ellipsize-middle}
   {:title "Ellipsize end" :mode :ellipsize-end}])

(defn- label-example []
  [example "Label"
   [showcase
    [:label {:label "Plain text" :xalign 0.0}]
    [:label {:markup "<b>Bold</b>, <i>italic</i> and <span font_family=\"monospace\">monospace</span>"
             :xalign 0.0}]
    [:label
     {:label
      "This label wraps onto multiple lines as the window narrows. Resize the window to see the text follow the available width."
      :wrap true
      :max-width-chars 48
      :xalign 0.0}]
    [:label
     {:label
      "This long single-line label ends with an ellipsis when there is not enough room to show all of its text."
      :ellipsize :end
      :max-width-chars 48
      :xalign 0.0}]
    [:label {:label "Select and copy this text." :selectable true :xalign 0.0}]]
   nil])

(defn- inscription-example [state path]
  (let [{:keys [nat-lines overflow]} (get-in state path)]
    [example "Inscription"
     [showcase
      [:hbox {:spacing 12 :homogeneous true}
       [:frame {:label "Label"}
        [:label {:label long-text :wrap true :max-width-chars 40 :xalign 0.0 :margin 8}]]
       [:frame {:label "Inscription"}
        [:inscription {:text long-text
                       :nat-lines nat-lines
                       :text-overflow (:mode (nth overflows overflow))
                       :wrap-mode :word-char
                       :xalign 0.0
                       :min-chars 20
                       :nat-chars 40
                       :valign :start
                       :margin 8
                       :tooltip "Shows as much text as its natural lines allow"}]]]]
     [support
      [:label {:label "Natural lines" :xalign 0.0}]
      [:spin-button {:adjustment {:lower 1.0 :upper 6.0 :step-increment 1.0 :page-increment 1.0}
                     :digits 0
                     :numeric true
                     :value nat-lines
                     :tooltip "Lines the inscription asks for"
                     :on-value-changed [[:assoc-in (conj path :nat-lines)]]
                     :halign :start}]
      [:dropdown {:items (mapv :title overflows)
                  :selected overflow
                  :tooltip "What happens to text that does not fit"
                  :on-selected [[:assoc-in (conj path :overflow)]]
                  :halign :start}]]]))

(defn page [state]
  [section
   [label-example]
   [inscription-example state [:inscription]]])

(ns gallery.common
  (:require [clojure.string :as str]))

(defn escape [text]
  (str/escape (str text) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&apos;"}))

(defn section [& children]
  (into [:vbox {:spacing 20 :margin-end 12 :margin-bottom 12}] children))

(defn showcase [& children]
  (into [:vbox {:spacing 8 :hexpand true :valign :start}] children))

(defn support [& children]
  (into [:vbox {:spacing 8 :valign :start :width 160}] children))

(defn example [title widgets detail]
  [:vbox {:spacing 8}
   [:label {:markup (str "<b>" (escape title) "</b>") :halign :start}]
   [:hbox {:spacing 16} widgets detail]])

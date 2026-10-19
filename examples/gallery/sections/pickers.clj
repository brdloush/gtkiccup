(ns gallery.sections.pickers
  (:require [gallery.common :refer [escape example section showcase support]]))

(def initial-state
  {:date [2000 1 1] :rgba [0.2 0.4 0.8 1.0] :font "Sans 12" :emoji "" :about-open false})

(defn- today [_]
  (let [date (java.time.LocalDate/now)]
    [(.getYear date) (.getMonthValue date) (.getDayOfMonth date)]))

(defn- date-text [[year month day]]
  (format "%04d-%02d-%02d" year month day))

(defn- calendar-example [state path]
  (let [date (get-in state path)]
    [example "Calendar"
     [showcase
      [:calendar {:date date :on-date [[:assoc-in path]] :halign :start}]]
     [support
      [:label {:label (str "Selected: " (date-text date))
               :xalign 0.0 :wrap true :max-width-chars 28}]
      [:button {:halign :start :on-click [[:update-in path today]]} "Today"]]]))

(defn- channel-hex [channel]
  (format "%02x" (Math/round (* 255.0 channel))))

(defn- rgba-hex [[red green blue _]]
  (str "#" (channel-hex red) (channel-hex green) (channel-hex blue)))

(defn- color-example [state path]
  (let [rgba (get-in state path)
        hex (rgba-hex rgba)]
    [example "ColorDialogButton"
     [showcase
      [:color-dialog-button {:rgba rgba
                             :dialog {:title "Pick a color" :with-alpha false}
                             :on-rgba [[:assoc-in path]] :halign :start}]]
     [support
      [:label {:markup (str "<span background=\"" hex "\">        </span>") :halign :start}]
      [:label {:label (str "Color: " hex) :xalign 0.0 :wrap true :max-width-chars 28}]]]))

(defn- font-example [state path]
  (let [font (get-in state path)]
    [example "FontDialogButton"
     [showcase
      [:font-dialog-button {:font-desc font :dialog {:title "Pick a font"}
                            :on-font-desc [[:assoc-in path]] :halign :start}]]
     [support
      [:label {:label (str "Font: " font) :xalign 0.0 :wrap true :max-width-chars 28}]
      [:label {:markup (str "<span font_desc=\"" (escape font)
                           "\">The quick brown fox jumps over the lazy dog.</span>")
               :xalign 0.0 :wrap true :max-width-chars 28}]]]))

(defn- emoji-example [state path]
  (let [emoji (get-in state path)]
    [example "EmojiChooser"
     [showcase
      [:menu-button {:icon "face-smile-symbolic" :tooltip "Pick an emoji" :halign :start}
       [:emoji-chooser {:on-emoji-picked [[:update-in path str]]}]]]
     [support
      [:label {:label (if (seq emoji) (str "Picked: " emoji) "Picked: nothing yet")
               :xalign 0.0 :wrap true :max-width-chars 28}]]]))

(defn- about-example [state path]
  (let [open? (get-in state path)]
    [example "AboutDialog"
     [showcase
      [:button {:on-click [[:assoc-in path true]] :halign :start} "About gtkiccup"]
      [:about-dialog {:open open?
                      :program-name "gtkiccup gallery"
                      :version "0.1.0"
                      :comments "GTK4 widgets rendered from hiccup by gtkiccup."
                      :authors ["gtkiccup contributors"]
                      :license-type :mit-x11
                      :on-close [[:assoc-in path false]]}]]
     [support
      [:label {:label (if open? "About dialog is open" "About dialog is closed")
               :xalign 0.0 :wrap true :max-width-chars 28}]]]))

(defn page [state]
  [section
   [calendar-example state [:date]]
   [color-example state [:rgba]]
   [font-example state [:font]]
   [emoji-example state [:emoji]]
   [about-example state [:about-open]]])

(ns gallery.main
  (:require [gallery.sections.buttons :as buttons]
            [gallery.sections.text-input :as text-input]
            [gallery.sections.numbers :as numbers]
            [gallery.sections.pickers :as pickers]
            [gallery.sections.labels :as labels]
            [gallery.sections.status :as status]
            [gallery.sections.images :as images]
            [gallery.sections.boxes :as boxes]
            [gallery.sections.containers :as containers]
            [gallery.sections.scrolling :as scrolling]
            [gallery.sections.lists :as lists]
            [gallery.sections.pages :as pages]
            [gallery.sections.window-bars :as window-bars]
            [gallery.common :as common]
            [gtkiccup.core :as ui]
            [gtkiccup.ratom :as r]))

(def sections
  [{:id :buttons :group "Input" :title "Buttons & Choices" :component buttons/page :enabled? true :initial-state buttons/initial-state}
   {:id :text-input :group "Input" :title "Text Input" :component text-input/page :enabled? true :initial-state text-input/initial-state}
   {:id :numbers :group "Input" :title "Numbers & Ranges" :component numbers/page :enabled? true :initial-state numbers/initial-state}
   {:id :pickers :group "Input" :title "Pickers & Dialogs" :component pickers/page :enabled? true :initial-state pickers/initial-state}
   {:id :labels :group "Display" :title "Labels & Text" :component labels/page :enabled? true :initial-state labels/initial-state}
   {:id :status :group "Display" :title "Progress & Status" :component status/page :enabled? true :initial-state status/initial-state}
   {:id :images :group "Display" :title "Images" :component images/page :enabled? true :initial-state images/initial-state}
   {:id :boxes :group "Layout" :title "Boxes & Alignment" :component boxes/page :enabled? true :initial-state boxes/initial-state}
   {:id :containers :group "Layout" :title "Grids & Containers" :component containers/page :enabled? true :initial-state containers/initial-state}
   {:id :scrolling :group "Layout" :title "Scrolling" :component scrolling/page :enabled? true :initial-state scrolling/initial-state}
   {:id :lists :group "Collections" :title "Lists & Flow" :component lists/page :enabled? true :initial-state lists/initial-state}
   {:id :pages :group "Navigation & Windows" :title "Pages & Disclosure" :component pages/page :enabled? true :initial-state pages/initial-state}
   {:id :window-bars :group "Navigation & Windows" :title "Window Bars & Controls" :component window-bars/page :enabled? true :initial-state window-bars/initial-state}])

(def enabled-sections (filterv :enabled? sections))

(def section-by-id (into {} (map (juxt :id identity)) enabled-sections))

(def sidebar-items
  ;; headings and sections share one vector because the list box addresses
  ;; rows by position: an item's index here is its row index
  (vec
   (mapcat (fn [group]
             (cons {:heading (:group (first group))} group))
           (partition-by :group sections))))

(def sidebar-index-by-id
  (into {} (keep-indexed (fn [i item] (when-let [id (:id item)] [id i])) sidebar-items)))

(defn initial-state []
  {:selected (:id (first enabled-sections))
   :sections (into {} (map (juxt :id :initial-state) enabled-sections))})

(defonce state (r/atom (initial-state)))

(def window-size
  "[width height]; shared with the smoke run and the screenshot."
  [1100 720])

(defn select-section
  "Only enabled sections are in section-by-id, so a heading, a section not
   yet ported, or an unknown id leaves the selection alone."
  [state id]
  (if (contains? section-by-id id)
    (assoc state :selected id)
    state))

(defn select! [id]
  (swap! state select-section id))

(defn update-state
  ;; handler data args come first, the event's own args are appended: on a
  ;; click [:update-in path inc] calls (inc old), on a toggle [:assoc-in path]
  ;; stores the toggle state, and [:assoc-in path v] stores v regardless
  [state event [op path & args]]
  (let [args (concat args (:gtkiccup/args event))
        section-path (into [:sections (:selected state)] path)]
    (case op
      :select (let [index (first args)
                    item (when (integer? index) (get sidebar-items index))]
                (select-section state (:id item)))
      :reset (assoc-in state [:sections (:selected state)]
                       (:initial-state (section-by-id (:selected state))))
      :update-in (apply update-in state section-path args)
      :assoc-in (assoc-in state section-path (first args)))))

(defn prepare! []
  (reset! state (initial-state))
  (ui/set-dispatch!
   (fn [event actions]
     (swap! state
            (fn [state]
              (reduce #(update-state %1 event %2) state actions))))))

(defn sidebar-row [{:keys [id title heading enabled?]}]
  (if heading
    [:list-box-row {:key heading :selectable false :activatable false}
     [:label.heading {:label heading :xalign 0.0
                      :margin-top 16 :margin-bottom 6
                      :margin-start 12 :margin-end 12}]]
    (let [enabled (boolean enabled?)]
      [:list-box-row {:key id :sensitive enabled :selectable enabled :activatable enabled
                      :tooltip (when-not enabled "Not ported yet")}
       [:label {:label title :xalign 0.0 :margin 8}]])))

(defn sidebar [selected]
  [:scrolled {:width 240 :vexpand true :hscroll :never}
   (into [:list-box.navigation-sidebar
          {:selection-mode :browse :selected (sidebar-index-by-id selected)
           :on-selected [[:select]]}]
         (map sidebar-row sidebar-items))])

(defn page [selected section-state]
  (let [{:keys [title component]} (section-by-id selected)]
    [:vbox {:spacing 12 :margin 16 :hexpand true :vexpand true}
     [:hbox {:spacing 12}
      [:label {:markup (str "<span size=\"xx-large\" weight=\"bold\">"
                           (common/escape title) "</span>")
               :halign :start :hexpand true}]
      [:button {:on-click [[:reset]]} "Reset"]]
     [:scrolled {:key selected :hexpand true :vexpand true}
      [component section-state]]]))

(defn gallery []
  (let [{:keys [selected sections]} @state]
    [:hbox {:spacing 0 :hexpand true :vexpand true}
     [sidebar selected]
     [:separator {:orientation :vertical}]
     [page selected (get sections selected)]]))

(defn -main [& _]
  (prepare!)
  (let [[width height] window-size]
    (ui/run #'gallery
            :title "gtkiccup · Widget gallery"
            :width width :height height
            :app-id "gtkiccup.gallery")))

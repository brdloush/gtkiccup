(ns gallery.sections.lists
  (:require [clojure.string :as str]
            [gallery.common :refer [example section showcase support]]))

(def ^:private initial-items
  [{:id 1 :title "Project notes" :subtitle "Ideas and meeting summaries"}
   {:id 2 :title "Reading list" :subtitle "Articles to revisit"}
   {:id 3 :title "Release checklist" :subtitle "Tasks before publishing"}])

(def initial-state
  {:list-box {:items initial-items :next-id 4 :selected-id nil :search-text "" :query ""}
   :flow-box {:max-per-line 8 :selection-mode 1 :selected nil}})

(defn- visible-items [{:keys [items query]}]
  (let [query (str/lower-case query)]
    (filterv #(str/includes? (str/lower-case (str (:title %) " " (:subtitle %))) query) items)))

(defn- filter-items [state query]
  (let [state (assoc state :query query)]
    (if (some #(= (:selected-id state) (:id %)) (visible-items state))
      state
      (assoc state :selected-id nil))))

(defn- select-item [state index]
  (assoc state :selected-id
         (when (some? index) (:id (get (visible-items state) index)))))

(defn- add-item [{:keys [next-id] :as state}]
  (-> state
      (update :items conj {:id next-id :title (str "Note " next-id)
                          :subtitle "Added in this session"})
      (update :next-id inc)))

(defn- remove-selected [{:keys [selected-id] :as state}]
  (-> state
      (update :items #(filterv (fn [item] (not= selected-id (:id item))) %))
      (assoc :selected-id nil)))

(defn- list-box-example [state path]
  (let [{:keys [items selected-id search-text] :as list-state} (get-in state path)
        matches (visible-items list-state)
        selected (some #(when (= selected-id (:id %)) %) items)
        selected-index (first (keep-indexed #(when (= selected-id (:id %2)) %1) matches))]
    [example "ListBox"
     [showcase
      (if (seq matches)
        [:frame
         (into [:list-box {:selection-mode :single :selected selected-index
                           :on-selected [[:update-in path select-item]]}]
               (map (fn [{:keys [id title subtitle]}]
                      [:list-box-row {:key id}
                       [:vbox {:spacing 4 :margin 10}
                        [:label {:label title :xalign 0.0}]
                        [:label {:label subtitle :xalign 0.0 :wrap true :max-width-chars 48}]]])
                    matches))]
        [:label {:label (if (empty? items) "No items. Add an item to begin." "No matching items.")
                 :xalign 0.0}])]
     [support
      [:label {:label "Select a row with the mouse or keyboard, then change the collection."
               :xalign 0.0 :wrap true :max-width-chars 28}]
      [:search-entry {:value search-text
                      :placeholder "Filter titles and descriptions"
                      :tooltip "Filter list items"
                      :on-change [[:assoc-in (conj path :search-text)]]
                      :on-search-changed [[:update-in path filter-items]]}]
      [:button {:label "Add item" :on-click [[:update-in path add-item]] :halign :start}]
      [:button {:label "Remove selected" :sensitive (some? selected)
                :on-click [[:update-in path remove-selected]] :halign :start}]
      [:button {:label "Reverse order" :sensitive (> (count items) 1)
                :on-click [[:update-in (conj path :items) (comp vec reverse)]] :halign :start}]
      [:label {:label (if selected (str "Selected: " (:title selected)) "No item selected")
               :xalign 0.0 :wrap true :max-width-chars 28}]
      [:label {:label (str (count matches) " of " (count items) " items")
               :xalign 0.0 :wrap true :max-width-chars 28}]]]))

(def ^:private tags
  ["Clojure" "GTK" "Reactive" "Hiccup" "Layout" "Widgets" "Signals" "Scheme" "FFI" "Gallery"])

(def ^:private selection-modes
  [{:title "No selection" :mode :none} {:title "Single" :mode :single}
   {:title "Multiple" :mode :multiple}])

(defn- set-selection-mode [state mode]
  (assoc state :selection-mode mode :selected nil))

(defn- selected-tags [selected]
  ;; one index in single mode, a collection in multiple mode
  (keep #(get tags %) (if (coll? selected) selected [selected])))

(defn- flow-box-example [state path]
  (let [{:keys [max-per-line selection-mode selected]} (get-in state path)
        chosen (selected-tags selected)]
    [example "FlowBox"
     [showcase
      (into [:flow-box {:selection-mode (:mode (nth selection-modes selection-mode))
                        :selected selected
                        :max-children-per-line max-per-line :min-children-per-line 1
                        :row-spacing 6 :column-spacing 6 :homogeneous false
                        :on-selected [[:assoc-in (conj path :selected)]]}]
            (for [tag tags]
              [:flow-box-child {:key tag}
               [:frame
                [:label {:label tag :margin-start 8 :margin-end 8
                         :margin-top 4 :margin-bottom 4}]]]))]
     [support
      [:label {:label "Tags wrap onto new lines as the window narrows."
               :wrap true :xalign 0.0 :max-width-chars 28}]
      [:label {:label "Max per line" :xalign 0.0}]
      [:spin-button {:value max-per-line :digits 0 :numeric true
                     :adjustment {:lower 1 :upper 10 :step-increment 1}
                     :tooltip "Maximum tags on one line"
                     :on-value-changed [[:assoc-in (conj path :max-per-line)]]
                     :halign :start}]
      [:dropdown {:items (mapv :title selection-modes) :selected selection-mode
                  :tooltip "Selection mode"
                  :on-selected [[:update-in path set-selection-mode]] :halign :start}]
      [:label {:label (if (seq chosen) (str "Selected: " (str/join ", " chosen)) "Nothing selected.")
               :xalign 0.0 :wrap true :max-width-chars 28}]]]))

(defn page [state]
  [section
   [list-box-example state [:list-box]]
   [flow-box-example state [:flow-box]]])

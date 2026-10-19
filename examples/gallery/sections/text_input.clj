(ns gallery.sections.text-input
  (:require [clojure.string :as str]
            [gallery.common :refer [example section showcase support]]))

(def ^:private initial-text
  "gtkiccup renders GTK4 widgets from hiccup.\nEdit this text; the count beside it follows every change.")

(def initial-state
  {:entry {:text "" :submitted nil} :password "" :search {:text "" :query ""}
   :text-view {:text initial-text :wrap-mode 1 :editable true :monospace false}
   :editable-label {:title "Untitled document" :committed "Untitled document" :editing false}})

(def ^:private wrap-modes
  [{:title "No wrap" :mode :none} {:title "Word" :mode :word} {:title "Character" :mode :char}
   {:title "Word or character" :mode :word-char}])

(def ^:private fruit ["Apple" "Apricot" "Banana" "Blueberry" "Cherry" "Pear"])

(defn- note [text]
  [:label {:label text :xalign 0.0 :wrap true :max-width-chars 28}])

(defn- entry-example [state path]
  (let [{:keys [text submitted]} (get-in state path)]
    [example "Entry"
     [showcase
      [:entry {:value text
               :placeholder "Type and press Enter"
               :tooltip "Message"
               :hexpand true
               :on-change [[:assoc-in (conj path :text)]]
               :on-activate [[:assoc-in (conj path :submitted)]]}]
      [:entry {:value "Read-only text" :editable false :tooltip "Select and copy"}]
      [:entry {:value "Disabled input" :sensitive false}]]
     [support
      [note (str (count text) " characters"
                 (when (some? submitted) (str " · Submitted: " submitted)))]]]))

(defn- password-entry-example [state path]
  (let [password (get-in state path)]
    [example "PasswordEntry"
     [showcase
      [:password-entry {:value password
                        :show-peek-icon true
                        :placeholder "Password"
                        :tooltip "Sample password"
                        :on-change [[:assoc-in path]]}]]
     [support [note (if (empty? password) "Empty" "Password entered")]]]))

(defn- search-entry-example [state path]
  (let [{:keys [text query]} (get-in state path)
        matches (filterv #(str/includes? (str/lower-case %) (str/lower-case query)) fruit)]
    [example "SearchEntry"
     [showcase
      [:search-entry {:value text
                      :placeholder "Filter fruit"
                      :tooltip "Filter fruit"
                      ;; Keep the controlled text current while filtering waits for GTK.
                      :on-change [[:assoc-in (conj path :text)]]
                      :on-search-changed [[:assoc-in (conj path :query)]]}]]
     [support [note (if (seq matches) (str/join ", " matches) "No matching fruit")]]]))

(defn- text-view-example [state path]
  (let [{:keys [text wrap-mode editable monospace]} (get-in state path)]
    [example "TextView"
     [showcase
      [:frame
       [:scrolled {:height 140 :hexpand true}
        [:text-view {:value text
                     :wrap-mode (:mode (nth wrap-modes wrap-mode))
                     :editable editable
                     :monospace monospace
                     :left-margin 8 :right-margin 8 :top-margin 8 :bottom-margin 8
                     :tooltip "Multi-line text"
                     :on-change [[:assoc-in (conj path :text)]]}]]]]
     [support
      [note (str (count text) " characters")]
      [:dropdown {:items (mapv :title wrap-modes)
                  :selected wrap-mode
                  :tooltip "Wrap mode"
                  :on-selected [[:assoc-in (conj path :wrap-mode)]]
                  :halign :start}]
      [:check {:active editable :on-toggle [[:assoc-in (conj path :editable)]]
               :halign :start} "Editable"]
      [:check {:active monospace :on-toggle [[:assoc-in (conj path :monospace)]]
               :halign :start} "Monospace"]
      [:button {:on-click [[:assoc-in (conj path :text) ""]] :halign :start} "Clear"]]]))

(defn track-editing
  "Record edit mode; leaving it commits the title, which :on-change has kept
  current keystroke by keystroke (Escape restores the previous text first)."
  [state editing?]
  (cond-> (assoc state :editing editing?) (not editing?) (assoc :committed (:title state))))

(defn- editable-label-example [state path]
  (let [{:keys [title committed editing]} (get-in state path)]
    [example "EditableLabel"
     [showcase
      [:editable-label {:value title
                        :editing editing
                        :tooltip "Click or press Enter to edit; Enter commits, Escape cancels"
                        :on-change [[:assoc-in (conj path :title)]]
                        :on-editing [[:update-in path track-editing]]}]]
     [support
      [:button {:on-click [[:assoc-in (conj path :editing) true]] :halign :start} "Edit"]
      [note (str "Committed: " committed)]
      [note (if editing "Editing" "Not editing")]]]))

(defn page [state]
  [section
   [entry-example state [:entry]]
   [password-entry-example state [:password]]
   [search-entry-example state [:search]]
   [text-view-example state [:text-view]]
   [editable-label-example state [:editable-label]]])

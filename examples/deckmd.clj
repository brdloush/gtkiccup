(ns deckmd
  "Markdown -> slides. A deliberately small subset: enough for a talk, small
   enough to be correct.

   Pure, so it tests without GTK. Inline markup becomes Pango markup, which
   means escaping matters: a bare & or < in someone's slides would otherwise
   break the label."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; inline markup -> Pango markup
;; ---------------------------------------------------------------------------

(defn escape
  "Pango markup is XML, so these five characters have to be escaped before any
   of our own tags go in. Done first, always."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn inline
  "**bold**, *italic*, `code`. Escapes first, so the source cannot inject tags."
  [s]
  (-> (escape s)
      ;; bold before italic, or ** would be eaten as two single stars
      (str/replace #"\*\*(.+?)\*\*" "<b>$1</b>")
      (str/replace #"(?<![\*\w])\*([^\*]+?)\*(?![\*\w])" "<i>$1</i>")
      (str/replace #"`([^`]+?)`" "<tt>$1</tt>")))

;; ---------------------------------------------------------------------------
;; markdown -> slides
;; ---------------------------------------------------------------------------

(defn- blank? [l] (str/blank? l))

(defn- parse-slide
  "One slide's lines become {:type .. :heading .. :sub .. :items .. :text ..}."
  [lines]
  (let [lines   (remove blank? lines)
        h       (some #(re-matches #"(#{1,3})\s+(.*)" %) lines)
        level   (count (second h))
        heading (last h)
        items   (keep #(second (re-matches #"[-*+]\s+(.*)" %)) lines)
        quotes  (keep #(second (re-matches #">\s*(.*)" %)) lines)
        prose   (remove #(or (re-matches #"#{1,3}\s+.*" %)
                             (re-matches #"[-*+]\s+.*" %)
                             (re-matches #">\s*.*" %))
                        lines)]
    (cond
      (seq quotes)
      {:type :quote :text (str/join " " quotes) :heading heading}

      (seq items)
      {:type :bullets :heading heading :items (vec items)}

      ;; a top-level heading with at most one line under it is a title slide,
      ;; which is how a deck gets visual variety without anyone marking it up.
      ;; ## and ### are content headings, however little follows them.
      (and heading (= 1 level) (<= (count prose) 1))
      {:type :title :heading heading :sub (first prose)}

      heading
      {:type :prose :heading heading :text (str/join " " prose)}

      :else
      {:type :prose :text (str/join " " prose)})))

(defn parse
  "Splits on a line of three or more dashes, then parses each slide.
   Empty slides are dropped, so a trailing separator is harmless."
  [md]
  (->> (str/split (str/replace (str md) "\r\n" "\n") #"(?m)^-{3,}\s*$")
       (map str/split-lines)
       (map parse-slide)
       (remove (fn [{:keys [heading items text]}]
                 (and (str/blank? heading) (empty? items) (str/blank? text))))
       vec))

(defn slide-count [slides] (count slides))

(defn clamp-index
  "Keeps a slide index inside the deck. An empty deck stays at 0."
  [i slides]
  (max 0 (min (dec (max 1 (count slides))) i)))

;; ---------------------------------------------------------------------------
;; what the keys mean
;; ---------------------------------------------------------------------------

(def key-actions
  "Key name -> what it does. Anything not here is left for GTK."
  {"Right" :next "space" :next "Page_Down" :next "Down" :next "Return" :next
   "Left" :prev "BackSpace" :prev "Page_Up" :prev "Up" :prev
   "Home" :first "End" :last
   "F5" :fullscreen "F11" :fullscreen
   "Escape" :escape "q" :quit})

(defn apply-action
  "Pure state transition, so the key handling is testable on its own."
  [{:keys [index slides] :as state} action]
  (case action
    :next  (assoc state :index (clamp-index (inc index) slides))
    :prev  (assoc state :index (clamp-index (dec index) slides))
    :first (assoc state :index 0)
    :last  (assoc state :index (clamp-index (dec (count slides)) slides))
    :fullscreen (update state :fullscreen? not)
    :escape (if (:fullscreen? state) (assoc state :fullscreen? false)
                (assoc state :quit? true))
    :quit  (assoc state :quit? true)
    state))

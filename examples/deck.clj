(ns deck
  "A markdown slide presenter, in babashka, over GTK4 + libadwaita.

   Driven entirely by the keyboard. Slides animate between each other, and the
   animation is declarative: the view sets :page on an :carousel and the spec
   turns that into adw_carousel_scroll_to. So the app stays a pure function of
   state even though it moves.

   Run it with `bb deck path/to/talk.md`."
  (:require [babashka.fs :as fs]
            [deck-css]
            [deckmd :as md]
            [gtkiccup.adw :as adw]
            [gtkiccup.core :as ui]
            [gtkiccup.ffi :as g]
            [gtkiccup.ratom :as r]))

(def css deck-css/css)

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(defonce state
  (r/atom {:slides [] :index 0 :fullscreen? false :quit? false
           :path nil :chrome? true :started-at (System/currentTimeMillis)}))

(defn load-deck!
  "Reads and parses the markdown. Keeps the current slide if it still exists,
   which is what makes live reloading pleasant."
  [path]
  (let [slides (md/parse (slurp path))]
    (swap! state (fn [s] (assoc s
                                :slides slides
                                :path path
                                :index (md/clamp-index (:index s) slides))))
    slides))

(defn watch-file!
  "Re-parses when the file changes on disk, so a talk can be edited while it is
   on screen. Polls, because that is fifteen lines and inotify is not."
  [path]
  (let [running? (volatile! true)]
    (future
      (loop [seen (str (fs/last-modified-time path))]
        (Thread/sleep 500)
        (when @running?
          (let [now (str (fs/last-modified-time path))]
            (when (not= now seen)
              (try (load-deck! path)
                   (catch Exception e
                     (println "[deck] could not reload:" (ex-message e)))))
            (recur now)))))
    #(vreset! running? false)))

;; ---------------------------------------------------------------------------
;; keys
;; ---------------------------------------------------------------------------

(defn on-key
  "Returns truthy when the key was ours, so unknown keys fall through to GTK."
  [{:keys [key]}]
  (when-let [action (md/key-actions key)]
    (swap! state md/apply-action action)
    ;; fullscreen and quitting are the two things that are not pure state
    (let [{:keys [fullscreen? quit?]} @state
          win (:window @ui/current)]
      (when win
        (if fullscreen? (g/window-fullscreen win) (g/window-unfullscreen win)))
      (when quit? (ui/close!)))
    true))

;; ---------------------------------------------------------------------------
;; view
;; ---------------------------------------------------------------------------

(defn- slide-body [{:keys [type heading sub items text]}]
  (case type
    :title
    [:vbox {:spacing 14 :valign :center}
     [:label {:class "heading" :halign :center :wrap true
              :markup (md/inline heading)}]
     (when sub
       [:label {:class "sub" :halign :center :wrap true
                :markup (md/inline sub)}])]

    :bullets
    [:vbox {:spacing 22 :valign :center}
     (when heading
       [:label {:class "heading" :halign :start :wrap true
                :markup (md/inline heading)}])
     (into [:vbox {:spacing 14}]
           (map (fn [i]
                  [:hbox {:spacing 14}
                   [:label {:class "dot" :valign :start :label "•"}]
                   [:label {:class "bullet" :halign :start :wrap true
                            :markup (md/inline i)}]])
                items))]

    :quote
    [:vbox {:spacing 10 :valign :center}
     [:label {:class "quote" :halign :center :wrap true
              :markup (str "“" (md/inline text) "”")}]]

    ;; :prose and anything else
    [:vbox {:spacing 18 :valign :center}
     (when heading
       [:label {:class "heading" :halign :start :wrap true
                :markup (md/inline heading)}])
     (when text
       [:label {:class "prose" :halign :start :wrap true
                :markup (md/inline text)}])]))

(defn slide [s]
  [:bin {:class (if (= :title (:type s)) ["slide" "title"] ["slide"])}
   [:clamp {:max 900}
    [:bin {:margin 48} (slide-body s)]]])

(defn- elapsed [ms]
  (let [secs (quot ms 1000)]
    (format "%d:%02d" (quot secs 60) (mod secs 60))))

(defn chrome
  "Slide counter and elapsed time. A revealer so it can fade out."
  [{:keys [index slides chrome? started-at]}]
  [:revealer {:revealed (boolean chrome?) :duration 200}
   [:hbox {:spacing 16 :class "chrome" :halign :center}
    [:label {:class "counter" :label (str (inc index) " / " (max 1 (count slides)))}]
    [:label {:label (elapsed (- (System/currentTimeMillis) started-at))}]
    [:label {:label "← →  ·  F5 fullscreen  ·  Esc quit"}]]])

(defn empty-deck [path]
  [:status-page {:icon "x-office-presentation-symbolic"
                 :title "Nothing to present"
                 :description (str path " has no slides yet. Separate them with ---")}])

(defn home [state]
  (let [{:keys [slides index] :as s} @state]
    [:bin {:on-key on-key}
     [:vbox {:spacing 0}
      (if (seq slides)
        (into [:carousel {:page index :animate true :vexpand true}]
              (map slide slides))
        [:bin {:vexpand true} (empty-deck (:path s))])
      (chrome s)]]))

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(defn app [] (fn [] (home state)))

(defn tick!
  "Nudges a re-render once a second, only so the elapsed clock moves."
  []
  (let [running? (volatile! true)]
    (future (while @running? (Thread/sleep 1000) (ui/refresh!)))
    #(vreset! running? false)))

(defn -main [& [path]]
  (let [path (or path "examples/talk.md")]
    (when-not (fs/exists? path)
      (println "usage: bb deck path/to/talk.md")
      (System/exit 1))
    (load-deck! path)
    (let [stop-watch (watch-file! path)
          stop-tick  (tick!)]
      (try
        (ui/run (app)
                :title "Deck"
                :app-id "cz.brdloush.BbDeck"
                :app-name "Deck"
                :width 900 :height 620
                :window adw/window
                :css css)
        (finally (stop-watch) (stop-tick))))))

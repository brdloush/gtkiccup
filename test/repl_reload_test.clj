;; Proves the REPL workflow: run the app on its own thread, redefine the view
;; fn, ask for a refresh, and confirm the LIVE GTK widget shows the new code.
(require '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g] '[gtkiccup.ratom :as r])

(def state (r/atom {:n 7}))

;; the view. `app` reaches it through a var, so a redef is visible.
(defn home [st]
  [:vbox {} [:label {:label (str "old " (:n @st))}]])

(defn app [] (fn [] (#'home state)))

(def app-thread (future (ui/run (app) :title "repl reload test")))
(Thread/sleep 700)

(defn live-label-text
  "Reads the text straight out of the real GtkLabel."
  []
  (-> @ui/current :tree :children first :widget g/label-get-text))

(println "1) initial:" (live-label-text))

;; --- a state change: picked up on its own -------------------------------
(swap! state assoc :n 8)
(Thread/sleep 200)
(println "2) after swap!, no refresh needed:" (live-label-text))

;; --- a code change: needs refresh! --------------------------------------
(defn home [st]
  [:vbox {} [:label {:label (str "NEW " (:n @st) " 123")}]])

(Thread/sleep 200)
(println "3) after redef, before refresh! (stale, as expected):" (live-label-text))

(ui/refresh!)
(Thread/sleep 200)
(println "4) after ui/refresh!:" (live-label-text))

(let [txt (live-label-text)]
  (assert (= txt "NEW 8 123") (str "expected redefined view, got " (pr-str txt))))
(println "ALL OK")
(shutdown-agents)
(System/exit 0)

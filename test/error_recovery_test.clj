;; A broken view, or a throwing event handler, must not take the window down.
;; Before this was guarded, an exception escaped render! -> escaped the main
;; loop -> killed the thread, leaving an unpumped, frozen window and no message.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g] '[gtkiccup.ratom :as r])
(defcfn emit "g_signal_emit_by_name" [:pointer :string :&] :void)

(def st (r/atom {:n 1}))
(defn home [s] [:vbox {} [:label {:label (str "ok " (:n @s))}]
                [:button {:label "boom" :on-click #(throw (ex-info "handler blew up" {}))}]])

(def th (future (ui/run (fn [] (home st)) :title "error recovery")))
(Thread/sleep 700)
(defn live [] (-> @ui/current :tree :children first :widget g/label-get-text))
(defn settle [] (Thread/sleep 300))

(println "0) initial:" (live))
(assert (= "ok 1" (live)))

;; --- 1. text on a container, which has no text of its own ---------------
(defn home [_] [:vbox {} "oops"])
(ui/refresh!) (settle)
(println "1) loop survived a bad view:" (not (future-done? th)))
(assert (not (future-done? th)) "the main loop died")
(println "   last render kept:" (live))
(assert (= "ok 1" (live)) "should hold the last good render")
(println "   error recorded:" (:message @ui/last-error))
(assert (re-find #"has no text of its own" (:message @ui/last-error)))

;; --- 2. an unknown widget ------------------------------------------------
(defn home [_] [:vbox {} [:slider {}]])
(ui/refresh!) (settle)
(println "2) unknown widget reported:" (:message @ui/last-error))
(assert (re-find #"known widgets" (:message @ui/last-error)))
(assert (not (future-done? th)))

;; --- 3. fixing the view recovers -----------------------------------------
(defn home [s] [:vbox {} [:label {:label (str "fixed " (:n @s))}]])
(ui/refresh!) (settle)
(println "3) recovered after fixing the view:" (live))
(assert (= "fixed 1" (live)) "did not recover")
(assert (nil? @ui/last-error) "error should clear on a good render")

;; --- 4. a throwing event handler -----------------------------------------
(defn home [s] [:vbox {} [:label {:label (str "fixed " (:n @s))}]
                [:button {:label "boom" :on-click #(throw (ex-info "handler blew up" {}))}]])
(ui/refresh!) (settle)
(emit (-> @ui/current :tree :children second :widget) "clicked")
(settle)
(println "4) handler threw, loop alive:" (not (future-done? th)))
(assert (not (future-done? th)) "a throwing handler killed the loop")
(println "   reported:" (:message @ui/last-error))
(assert (re-find #"handler blew up" (:message @ui/last-error)))

;; the window still works afterwards
(swap! st assoc :n 9) (settle)
(println "5) still repainting:" (live))
(assert (= "fixed 9" (live)))

(ui/close!)
(println "6) close! returned:" (deref th 3000 :TIMEOUT))
(assert (nil? @ui/current))
(println "ALL OK")
(System/exit 0)

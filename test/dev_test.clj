;; Exercises gtkiccup.dev against a live window: every mechanism must get a
;; redefined view onto the screen without a manual ui/refresh!.
(require '[babashka.fs :as fs]
         '[gtkiccup.core :as ui] '[gtkiccup.dev :as dev] '[gtkiccup.ffi :as g] '[gtkiccup.ratom :as r])

(def state (r/atom {:n 1}))

(defn home [st] [:vbox {} [:label {:label (str "v1 " (:n @st))}]])

;; a plain call, no #'  -- bb resolves through the var at call time
(def app-thread (future (ui/run (fn [] (home state)) :title "dev test")))
(Thread/sleep 700)

(defn live [] (-> @ui/current :tree :children first :widget g/label-get-text))
(defn settle [] (Thread/sleep 300))

(println "0) initial:" (live))
(assert (= "v1 1" (live)))

;; --- 1. watch-ns! ---------------------------------------------------------
(println "1) watch-ns! armed on" (dev/watch-ns! *ns*) "fns")
(defn home [st] [:vbox {} [:label {:label (str "v2 " (:n @st))}]])
(settle)
(println "   after redef, no refresh! called:" (live))
(assert (= "v2 1" (live)) "watch-ns! did not refresh")
(dev/unwatch-all!)

;; --- 2. a NEW fn is not covered by the earlier watch-ns! ------------------
(defn home [st] [:vbox {} [:label {:label (str "v3 " (:n @st))}]])
(settle)
(println "2) after unwatch-all!, redef is stale (expected):" (live))
(assert (= "v2 1" (live)) "should not have refreshed")

;; --- 3. defview arms itself ----------------------------------------------
(dev/defview home [st] [:vbox {} [:label {:label (str "v4 " (:n @st))}]])
(settle)
(println "3) defview first eval (arms, does not yet refresh):" (live))
(dev/defview home [st] [:vbox {} [:label {:label (str "v5 " (:n @st))}]])
(settle)
(println "   defview second eval:" (live))
(assert (= "v5 1" (live)) "defview did not refresh")
(dev/unwatch-all!)

;; --- 4. auto-refresh! needs no registration ------------------------------
(dev/auto-refresh! 50)
(defn home [st] [:vbox {} [:label {:label (str "v6 " (:n @st))}]])
(settle)
(println "4) auto-refresh! picked up an unregistered redef:" (live))
(assert (= "v6 1" (live)) "auto-refresh! did not refresh")

;; and it survives a state change at the same time
(swap! state assoc :n 42)
(settle)
(println "   with a state change too:" (live))
(assert (= "v6 42" (live)))
(dev/stop-auto-refresh!)

;; --- 5. watch-files! reloads from disk -----------------------------------
(def tmp (str (fs/create-temp-dir)))
(def tmp-file (str tmp "/reloadable.clj"))
(spit tmp-file "(ns reloadable) (defn tag [] \"from-disk-1\")")
(load-file tmp-file)
(dev/watch-ns! *ns*)                       ; arm first, then redefine
(defn home [st] [:vbox {} [:label {:label (str ((resolve 'reloadable/tag)) " " (:n @st))}]])
(settle)
(println "5) view reading a reloadable ns:" (live))
(assert (= "from-disk-1 42" (live)))

(dev/watch-files! tmp)
(spit tmp-file "(ns reloadable) (defn tag [] \"from-disk-2\")")
(Thread/sleep 900)
(println "   after saving the file on disk:" (live))
(assert (= "from-disk-2 42" (live)) "watch-files! did not reload+refresh")

;; --- 6. status / teardown ------------------------------------------------
(println "6) status:" (dev/status))
(dev/stop!)
(println "   after stop!:" (dev/status))
(ui/close!)
(println "   run returned:" (deref app-thread 3000 :TIMEOUT))
(println "ALL OK")
(System/exit 0)

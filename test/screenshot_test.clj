;; The app renders its own PNG through GSK. No compositor involved, which is
;; why this works at all: GNOME refuses D-Bus screenshots from plain processes.
(require '[babashka.fs :as fs]
         '[gtkiccup.adw :as adw] '[gtkiccup.core :as ui] '[gtkiccup.dev :as dev] '[gtkiccup.ffi])

(def out (str (fs/create-temp-dir) "/shot.png"))
(def gtk-thread (promise))
(def result (atom nil))

(def th (future
          (ui/run (fn [] [:toast-overlay {}
                          [:toolbar-view {}
                           [:header-bar {:slot :top}
                            [:window-title {:title "Screenshot test" :subtitle "gsk"}]]
                           [:scroll {} [:page {} [:group {:title "Group"}
                                                 [:row {:title "row" :subtitle "sub"}
                                                  [:level {:slot :suffix :value 0.5}]]]]]]])
                  :title "screenshot test" :width 520 :height 320
                  :window adw/window
                  :on-ready (fn [_win _tree]
                              (deliver gtk-thread (.getId (Thread/currentThread)))))))
(Thread/sleep 2200)

;; --- 1. shooting the whole window --------------------------------------
(reset! result (dev/screenshot! out))
(println "1) wrote" @result)
(assert (fs/exists? out) "no file written")
(assert (> (fs/size out) 3000) (str "png suspiciously small: " (fs/size out)))

;; --- 1b. it renders at the screen's scale, not the logical size ---------
(defn png-size
  "width and height straight out of the PNG's IHDR."
  [path]
  (let [b (fs/read-all-bytes path)
        n (fn [off] (reduce (fn [a i] (+ (* a 256) (bit-and (nth b (+ off i)) 0xff)))
                            0 (range 4)))]
    [(n 16) (n 20)]))

(def scale (dev/on-gtk-thread! #(gtkiccup.ffi/widget-get-scale-factor (:window @ui/current))))
(println "1b) display scale factor:" scale)
(assert (= scale (:scale @result)) "did not default to the widget's scale")
(assert (= [(:width @result) (:height @result)] (png-size out))
         "reported size does not match the file")
(println "    logical window:" (dev/on-gtk-thread! #(gtkiccup.ffi/widget-get-width (:window @ui/current)))
         "-> png:" (png-size out))
(assert (= (* scale (dev/on-gtk-thread! #(gtkiccup.ffi/widget-get-width (:window @ui/current))))
           (first (png-size out)))
        "png is not scale x the logical width")

;; scale 1 gives the logical size, for comparison
(def one (str (fs/create-temp-dir) "/one.png"))
(def r1 (dev/screenshot! one (:window @ui/current) 1))
(println "    forced scale 1 ->" (png-size one))
(assert (= 1 (:scale r1)))
(when (> scale 1)
  (assert (< (first (png-size one)) (first (png-size out)))
          "scale 1 should be smaller than the default"))

;; it really is a PNG
(def magic (take 4 (fs/read-all-bytes out)))
(println "   magic bytes:" (mapv #(bit-and % 0xff) magic))
(assert (= [137 80 78 71] (mapv #(bit-and % 0xff) magic)) "not a PNG")

;; --- 2. shooting one widget ------------------------------------------
(def part (str (fs/create-temp-dir) "/part.png"))
(def row-widget (-> @ui/current :tree :children first :children second :widget))
(def rpart (dev/screenshot! part row-widget))
(println "2) wrote a single widget too:" (fs/size part) "bytes," (png-size part) "px")
(assert (fs/exists? part))
(assert (< (fs/size part) (fs/size out)) "one widget should be smaller than the window")

;; --- 3. clear error when there is nothing to shoot -------------------
(println "3) no window ->"
         (try (dev/screenshot! "/tmp/nope.png" nil) :NO-ERROR
              (catch Exception e (ex-message e))))
(assert (= :threw (try (dev/screenshot! "/tmp/nope.png" nil) :no-error
                       (catch Exception _ :threw)))
        "should refuse when there is no widget")

;; --- 3b. it marshals itself onto the GTK thread ------------------------
;; every call above came from this thread, which is NOT the GTK thread. Widget
;; calls from the wrong thread segfault, so screenshot! hops over by itself.
(println "3b) this test thread is on the GTK thread?" (dev/on-gtk-thread?))
(assert (not (dev/on-gtk-thread?)) "test should be off the GTK thread")
(assert (= @gtk-thread (dev/on-gtk-thread! #(.getId (Thread/currentThread)))))
(println "    on-gtk-thread! returns a value from over there, and rethrows:"
         (try (dev/on-gtk-thread! #(throw (ex-info "boom" {}))) :NO-ERROR
              (catch Exception e (ex-message e))))
(assert (= "boom" (try (dev/on-gtk-thread! #(throw (ex-info "boom" {}))) nil
                       (catch Exception e (ex-message e)))))

;; --- 4. dev/later! runs on the GTK thread ------------------------------
(println "4) gtk thread id:" @gtk-thread)
(def where (promise))
(dev/later! (fn [] (deliver where (.getId (Thread/currentThread)))))
(assert (= @gtk-thread (deref where 2000 :TIMEOUT))
        "later! did not run on the GTK thread")
(println "   later! ran on the same thread:" true)
(println "   (this test thread is" (.getId (Thread/currentThread)) ")")
(assert (not= @gtk-thread (.getId (Thread/currentThread))) "test should be off-thread")

;; and that is how a worker screenshots: do the waiting off-thread, then marshal
(def marshalled (str (fs/create-temp-dir) "/via-later.png"))
(def done (promise))
(future (Thread/sleep 100)
        (dev/later! (fn [] (dev/screenshot! marshalled) (deliver done :ok))))
(assert (= :ok (deref done 4000 :TIMEOUT)) "screenshot via later! never ran")
(println "5) screenshot marshalled from a worker:" (fs/size marshalled) "bytes")
(assert (> (fs/size marshalled) 3000))

(ui/close!)
(assert (nil? (deref th 3000 :TIMEOUT)))
(println "ALL OK")
(System/exit 0)

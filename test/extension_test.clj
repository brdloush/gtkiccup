;; The four extension points that let an optional namespace add libadwaita
;; without core knowing it exists.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g])
(defcfn has-css-class? "gtk_widget_has_css_class" [:pointer :string] :int)
(defcfn label-get-text "gtk_label_get_text" [:pointer] :string)

(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec ui/default-window))

;; --- 1. register-widget! / register-signal! ------------------------------
(def slots (atom []))
(defcfn frame-new "gtk_frame_new" [:string] :pointer)
(defcfn frame-set-child "gtk_frame_set_child" [:pointer :pointer] :void)

(ui/register-widget! :frame
  {:text-prop :title
   :ctor  (fn [p] (frame-new (str (:title p ""))))
   :apply (fn [_w _p _changed] nil)
   ;; a container that records which slot each child asked for
   :append (fn [parent child props]
             (swap! slots conj (:slot props :default))
             (frame-set-child parent child))
   :remove (fn [parent _child props]
             (swap! slots conj [:removed (:slot props :default)])
             (frame-set-child parent nil))})

(println "1) registered:" (contains? @ui/widgets :frame))
(assert (contains? @ui/widgets :frame))

;; --- 2. :append and :remove receive the child's props -------------------
(def win (g/window-new))
(def t1 (reconcile root-spec win nil
                   (normalize [:frame {:title "outer"} [:label {:slot :body} "hi"]])))
(println "2) slot seen by the container:" @slots)
(assert (= [:body] @slots) "child props did not reach :append")

;; replacing the child by changing its tag passes the OLD props to :remove
(def t2 (reconcile root-spec win t1
                   (normalize [:frame {:title "outer"} [:button {:slot :other} "b"]])))
(println "   after a tag change:" @slots)
(assert (= [:body [:removed :body] :other] @slots) "wrong props on :remove")

;; boxes ignore the extra argument
(reset! slots [])
(def t3 (reconcile root-spec win nil
                   (normalize [:vbox {} [:label "a"] [:label "b"]])))
(println "3) a box still works with the 3-arg append:" (count (:children t3)))
(assert (= 2 (count (:children t3))))

;; --- 3. run's :window option is pluggable -------------------------------
(def made (atom nil))
(def custom {:ctor        (fn [] (reset! made :called) (g/window-new))
             :set-content g/window-set-child})
(println "4) default-window keys:" (sort (keys ui/default-window)))
(assert (= [:ctor :set-content] (sort (keys ui/default-window))))
(def w2 ((:ctor custom)))
(assert (= :called @made) "the :window ctor was not used")
((:set-content custom) w2 (g/label-new "in a custom window"))
(println "   custom window ctor + set-content both work")

;; --- 4. load-css! makes :class mean something --------------------------
(ui/load-css! ".probe { color: rgb(255,0,0); }")
(def t4 (reconcile root-spec win nil
                   (normalize [:vbox {} [:label {:class "probe"} "styled"]])))
(def lbl (-> t4 :children first :widget))
(println "5) css loaded, class attached:" (has-css-class? lbl "probe"))
(assert (= 1 (has-css-class? lbl "probe")))
(assert (= "styled" (label-get-text lbl)))
;; --- 5. run's :on-ready fires once, after the first render --------------
(def ready (atom []))
(def th (future (ui/run (fn [] [:vbox {} [:label "ready test"]])
                        :title "on-ready"
                        :on-ready (fn [w tree] (swap! ready conj [(some? w) (:tag tree)])))))
(Thread/sleep 700)
(println "6) :on-ready got the window and the root node:" @ready)
(assert (= [[true :vbox]] @ready) ":on-ready did not fire exactly once with both args")
;; it must not fire again on later renders
(ui/refresh!)
(Thread/sleep 300)
(assert (= 1 (count @ready)) ":on-ready fired more than once")
(println "   still once after a refresh:" (count @ready))
(ui/close!)
(assert (nil? (deref th 3000 :TIMEOUT)))
;; --- 7. g_setenv, which is how the monitor picks its renderer -----------
;; GSK reads GSK_RENDERER when the first window is realized, so an app can
;; choose it from inside rather than needing a wrapper script.
(g/setenv "GTK_FFI_PROBE" "yes" 1)
(println "7) g_setenv round-trips:" (pr-str (g/getenv "GTK_FFI_PROBE")))
(assert (= "yes" (g/getenv "GTK_FFI_PROBE")))
(assert (nil? (g/getenv "GTK_FFI_PROBE_UNSET")))

;; --- 8. :app-id / :app-name reach GLib before the window exists ---------
;; prgname becomes the Wayland app_id, which is the key a compositor matches
;; against a .desktop file. Set after the surface exists and it does nothing.
(def before-id (g/get-prgname))
(def th2 (future (ui/run (fn [] [:vbox {} [:label "identity"]])
                         :title "identity"
                         :app-id "cz.example.IdentityTest"
                         :app-name "Identity Test")))
(Thread/sleep 700)
(println "8) prgname:" (pr-str before-id) "->" (pr-str (g/get-prgname)))
(assert (= "cz.example.IdentityTest" (g/get-prgname)) ":app-id did not reach prgname")
(assert (= "Identity Test" (g/get-application-name)) ":app-name did not reach GLib")
(ui/close!)
(assert (nil? (deref th2 3000 :TIMEOUT)))

;; --- 9. :on-layout sees real sizes, where :on-render cannot -----------
;; A render only changes props; GTK lays the frame out later. So the first
;; :on-render reads a height of zero, and the first :on-layout -- hung off the
;; frame clock's after-paint -- already reads the real one.
(defcfn widget-height "gtk_widget_get_height" [:pointer] :int)
(def render-h (atom []))
(def layout-h (atom []))
(defn first-label [tree] (-> tree :children first :widget))
(def th3 (future (ui/run (fn [] [:vbox {} [:label "measure me"]])
                         :title "on-layout"
                         :on-render (fn [_ t] (swap! render-h conj (widget-height (first-label t))))
                         :on-layout (fn [_ t] (swap! layout-h conj (widget-height (first-label t)))))))
(Thread/sleep 1200)
(println "9) first :on-render height" (first @render-h) "| first :on-layout height" (first @layout-h))
(assert (= 0 (first @render-h)) "the first render runs before layout")
(assert (pos? (first @layout-h)) ":on-layout should see the label laid out, from the first frame")
;; it follows paints, not a timer: a quiet window stops calling it. A stray
;; pointer over the window can repaint a little, so this only rules out a loop.
(def settled (count @layout-h))
(Thread/sleep 1000)
(println "   calls during a quiet second:" (- (count @layout-h) settled))
(assert (< (- (count @layout-h) settled) 10) ":on-layout should not run while nothing paints")
(ui/close!)
(assert (nil? (deref th3 3000 :TIMEOUT)))

;; --- 10. :css styles the first render; :on-ready is too late ----------
;; An :on-render that asks a label for its Pango layout on the first render --
;; as an app placing a caret does -- makes GtkLabel build it unstyled, and the
;; label keeps that layout, default font and all, until its text changes or
;; something restyles the whole window. In a decorated window something does;
;; in a chromeless one nothing does, and the text stays small. CSS from
;; :on-ready lands after that first render; :css lands before it.
(defn styled-height [opts cls]
  (let [h (atom nil)
        th (future (apply ui/run (fn [] [:vbox {} [:label {:class cls} "big text"]])
                          :title "css" :window ui/chromeless-window
                          :on-render (fn [_ t] (g/label-get-layout (first-label t)))
                          :on-layout (fn [_ t] (reset! h (widget-height (first-label t))))
                          (mapcat identity opts)))]
    (Thread/sleep 1200)
    (ui/close!)
    (assert (nil? (deref th 3000 :TIMEOUT)))
    @h))
(def late (styled-height {:on-ready (fn [_ _] (ui/load-css! ".late-big { font-size: 60px; }"))}
                         "late-big"))
(def early (styled-height {:css ".early-big { font-size: 60px; }"} "early-big"))
(println "10) 60px label height -- css from :on-ready:" late "| from :css:" early)
(assert (>= early 60) ":css should style the label before the first render measures it")
;; not asserted: it is GTK's behaviour, not ours, and a GTK release may fix it
(when (< late 60) (println "    (the :on-ready trap still reproduces on this GTK)"))

(println "ALL OK")
(System/exit 0)

;; The :carousel tag: changing a :page prop animates. Proves motion is
;; declarative -- no imperative scroll calls in app code.
;;
;; Note a trap this test exists to catch: an unrealised carousel accepts
;; scroll_to and silently does nothing, so this must run in a real window.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.adw :as adw] '[gtkiccup.core :as ui] '[gtkiccup.dev :as dev])
(defcfn position "adw_carousel_get_position" [:pointer] :double)
(defcfn n-pages "adw_carousel_get_n_pages" [:pointer] :int)
(defcfn revealed? "gtk_revealer_get_reveal_child" [:pointer] :int)
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)

(def page (atom 0))
(def animate (atom true))
(def shown (atom true))

(defn view []
  [:carousel {:page @page :animate @animate}
   [:bin {} [:label "slide 0"]]
   [:bin {} [:label "slide 1"]]
   [:bin {} [:label "slide 2"]]
   [:bin {} [:revealer {:revealed @shown :duration 120} [:label "counter"]]]])

(def th (future (ui/run #'view :title "motion test" :width 520 :height 300
                        :window adw/window)))
(Thread/sleep 1800)

(def car (dev/on-gtk-thread! #(-> @ui/current :tree :widget)))
(println "0) root is" (type-name car) "with" (dev/on-gtk-thread! #(n-pages car)) "pages")
(assert (= "AdwCarousel" (type-name car)))
(assert (= 4 (dev/on-gtk-thread! #(n-pages car))))
(assert (< (dev/on-gtk-thread! #(position car)) 0.01) "should start at page 0")

;; --- 1. changing :page animates ---------------------------------------
(reset! page 2)
(ui/refresh!)
(Thread/sleep 60)
(def mid (dev/on-gtk-thread! #(position car)))
(Thread/sleep 500)
(def settled (dev/on-gtk-thread! #(position car)))
(println (format "1) mid-flight %.3f -> settled %.3f" (double mid) (double settled)))
(assert (< 1.99 settled 2.01) "did not arrive at page 2")
(assert (< mid settled) "position should have been in between: no animation happened")
(assert (> mid 0.01) "it jumped instantly instead of animating")

;; --- 2. :animate false jumps ------------------------------------------
(reset! animate false)
(reset! page 0)
(ui/refresh!)
(Thread/sleep 60)
(def jumped (dev/on-gtk-thread! #(position car)))
(println (format "2) with :animate false, position after 60ms: %.3f" (double jumped)))
(assert (< jumped 0.01) "should have jumped straight to page 0")

;; --- 3. re-rendering without changing :page does not re-animate -------
(reset! animate true)
(reset! page 3)
(ui/refresh!) (Thread/sleep 500)
(assert (< 2.99 (dev/on-gtk-thread! #(position car)) 3.01))
(ui/refresh!) (Thread/sleep 100)
(println "3) an unrelated re-render left it at"
         (format "%.3f" (double (dev/on-gtk-thread! #(position car)))))
(assert (< 2.99 (dev/on-gtk-thread! #(position car)) 3.01)
        "a re-render with an unchanged :page should not move anything")

;; --- 4. the revealer toggles ------------------------------------------
(def rev (dev/on-gtk-thread! #(-> @ui/current :tree :children last :children first :widget)))
(println "4) revealer is" (type-name rev) "revealed:" (dev/on-gtk-thread! #(revealed? rev)))
(assert (= "GtkRevealer" (type-name rev)))
(assert (= 1 (dev/on-gtk-thread! #(revealed? rev))))
(reset! shown false)
(ui/refresh!) (Thread/sleep 250)
(println "   after hiding:" (dev/on-gtk-thread! #(revealed? rev)))
(assert (= 0 (dev/on-gtk-thread! #(revealed? rev))))

;; --- 5. a :page past the end is ignored rather than crashing ----------
(reset! page 99)
(ui/refresh!) (Thread/sleep 200)
(println "5) :page 99 on a 4-page carousel left it at"
         (format "%.3f" (double (dev/on-gtk-thread! #(position car)))))
(assert (nil? @ui/last-error) "an out-of-range page should not raise")
;; and it must not walk off the end of the sibling chain either: a NULL from C
;; is not nil, so a nil? check there would print GTK CRITICALs
(assert (nil? (@#'gtkiccup.adw/nth-child car 99)) "nth-child should stop at the end")
(assert (some? (@#'gtkiccup.adw/nth-child car 3)) "nth-child should find the last page")

(ui/close!)
(assert (nil? (deref th 3000 :TIMEOUT)))
(println "ALL OK")
(System/exit 0)

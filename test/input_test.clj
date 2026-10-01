;; Keyboard input. Keys are not a widget signal: they arrive through a
;; GtkEventController, with a different callback signature and a return value
;; that decides whether the event stops here.
;;
;; Testable without a human because g_signal_emit_by_name can deliver a
;; key-pressed straight to the controller.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.adw :as adw] '[gtkiccup.core :as ui] '[gtkiccup.dev :as dev] '[gtkiccup.ffi :as g])
(defcfn emit "g_signal_emit_by_name" [:pointer :string :&] :void)
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)

(def RIGHT 0xff53) (def LEFT 0xff51) (def SPACE 0x020)
(def ESC 0xff1b)   (def F5 0xffc2)   (def A 0x061)

(def seen (atom []))
(def handled? (atom true))

(defn view []
  [:bin {:on-key (fn [k] (swap! seen conj k) @handled?)}
   [:label "input test"]])

;; pass the var, not its value, so alter-var-root below actually reaches the
;; running app -- (ui/run view ...) would capture this fn forever
(def th (future (ui/run #'view :title "input test" :width 360 :height 160
                        :window adw/window)))
(Thread/sleep 1800)

;; --- 1. a controller was created and attached to the toplevel ------------
(def ctrl (:controller (last @ui/controllers)))
(println "1) controllers:" (mapv :prop @ui/controllers))
(assert (= :on-key (:prop (last @ui/controllers))))
(println "   controller type:" (type-name ctrl))
(assert (= "GtkEventControllerKey" (type-name ctrl)))
(println "   attached to:" (type-name (:host (last @ui/controllers))))
;; CAPTURE, not the default BUBBLE. In the bubble phase the focused widget sees
;; the key first, so a focused button swallows space and activates itself --
;; which in a typing test silently restarts the whole run.
(defcfn ctrl-phase "gtk_event_controller_get_propagation_phase" [:pointer] :int)
(println "   propagation phase:" (dev/on-gtk-thread! #(ctrl-phase ctrl))
         "(1 = capture)")
(assert (= (:capture g/phase) (dev/on-gtk-thread! #(ctrl-phase ctrl)))
        "a bubble-phase key controller loses space to whatever has focus")
(assert (= "AdwWindow" (type-name (:host (last @ui/controllers))))
        ":on-key must land on the toplevel, not on a widget that never focuses")

;; --- 2. key names, not numbers -----------------------------------------
(defn press!
  ([keyval] (press! keyval 0))
  ([keyval state]
   (dev/on-gtk-thread!
    (fn []
      (with-open [arena (ffi/confined-arena)]
        (let [ret (ffi/alloc arena :int)]
          (emit ctrl "key-pressed" (int keyval) (int 0) (int state) ret)
          (ffi/read ret :int)))))))

(reset! seen [])
(press! RIGHT) (press! LEFT) (press! SPACE) (press! ESC) (press! F5) (press! A)
(println "2) keys seen:" (mapv :key @seen))
(assert (= ["Right" "Left" "space" "Escape" "F5" "a"] (mapv :key @seen))
        "handler should get names from gdk_keyval_name, not keyvals")

;; --- 2b. printable keys also arrive as :char ---------------------------
;; And the ones that are not printable must NOT: Escape, Tab and BackSpace map
;; to unicode 27, 9 and 8, so a (pos? uni) test would type an Escape.
(reset! seen [])
(doseq [c "az-?>!*1./=' "] (press! (@#'gtkiccup.ffi/unicode-to-keyval (int c))))
(println "2b) chars:" (pr-str (apply str (map :char @seen))))
(assert (= "az-?>!*1./=' " (apply str (map :char @seen)))
        "printable keys did not round-trip into :char")

(reset! seen [])
(press! ESC) (press! 0xff09) (press! 0xff08) (press! RIGHT)
(println "    control keys ->" (mapv (juxt :key :char) @seen))
(assert (every? #(nil? (:char %)) @seen)
        "a control key leaked into :char -- Escape would be typed")
(assert (= ["Escape" "Tab" "BackSpace" "Right"] (mapv :key @seen))
        "names should still come through")

;; --- 3. modifiers ------------------------------------------------------
(reset! seen [])
(press! RIGHT (:ctrl g/modifier))
(press! RIGHT (:shift g/modifier))
(press! RIGHT (bit-or (:ctrl g/modifier) (:alt g/modifier)))
(println "3) modifiers:" (mapv #(select-keys % [:ctrl? :shift? :alt?]) @seen))
(assert (true? (:ctrl? (first @seen))))
(assert (false? (:shift? (first @seen))))
(assert (true? (:shift? (second @seen))))
(assert (and (:ctrl? (nth @seen 2)) (:alt? (nth @seen 2))))

;; --- 4. the return value decides whether the event stops ---------------
(reset! handled? true)
(println "4) handler returns truthy ->" (press! RIGHT))
(assert (= 1 (press! RIGHT)) "a truthy handler should report the key as handled")
(reset! handled? false)
(println "   handler returns false ->" (press! RIGHT))
(assert (= 0 (press! RIGHT)) "a falsey handler should let the key through")
(reset! handled? nil)
(assert (= 0 (press! RIGHT)) "nil must become 0, not crash the C caller")
(reset! handled? true)

;; --- 5. a throwing handler is contained, and reports "not handled" -----
(def boom (atom nil))
(swap! ui/last-error (constantly nil))
(reset! seen [])
(def th2-view (fn [] [:bin {:on-key (fn [_] (throw (ex-info "key boom" {})))}
                      [:label "x"]]))
;; swap the handler on the live tree by re-rendering with a throwing one
(alter-var-root #'view (constantly th2-view))
(ui/refresh!)
(Thread/sleep 300)
(println "5) throwing handler returns:" (press! RIGHT))
(assert (= 0 (press! RIGHT)) "a throwing handler must not report the key handled")
(assert (re-find #"key boom" (:message @ui/last-error)))
(assert (not (future-done? th)) "the loop must survive a throwing key handler")
(println "   loop alive, error recorded:" (:message @ui/last-error))

;; --- 6. existing signals still work with the new plumbing -------------
(def clicks (atom 0))
(alter-var-root #'view
                (constantly (fn [] [:vbox {}
                                    [:button {:label "b" :on-click #(swap! clicks inc)}]])))
(ui/refresh!)
(Thread/sleep 300)
(def btn (dev/on-gtk-thread! #(-> @ui/current :tree :children first :widget)))
(dev/on-gtk-thread!
 (fn [] (with-open [a (ffi/confined-arena)] (emit btn "clicked"))))
(Thread/sleep 200)
(println "6) plain :on-click still fires:" @clicks)
(assert (= 1 @clicks) "the signature change broke ordinary signals")

(ui/close!)
(assert (nil? (deref th 3000 :TIMEOUT)))
(println "ALL OK")
(System/exit 0)

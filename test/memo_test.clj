;; Data-handler cache: an event handler written as data resolves to a fn that is
;; identical across renders for the same [prop data], distinct for different
;; data, still dispatches through @dispatch at click time, and an fn handler
;; passes through untouched. Pure hiccup -- no window, no gtk-init.
;;
;; (Goal 11 removed the `h/memoized` component cache; unchanged subtrees now
;; short-circuit on `identical?` at the Replicant level and components are late-
;; bound aliases, so the only cache left in hiccup is this data-handler one.)
(require '[gtkiccup.core :as ui]
         '[gtkiccup.hiccup :as h])

(def cases (atom 0))
(defn ok [cond_ msg]
  (swap! cases inc)
  (assert cond_ msg))

;; 1) data handlers are identical across renders and still dispatch
(def seen (atom []))
(ui/set-dispatch! (fn [e d] (swap! seen conj [(:gtkiccup/event e) d])))
(h/clear-memo!)
(def b1 (h/->replicant (h/normalize [:button {:on-click [:do 1]}])))
(def b2 (h/->replicant (h/normalize [:button {:on-click [:do 1]}])))
(def f1 (get-in (second b1) [:on :on-click]))
(def f2 (get-in (second b2) [:on :on-click]))
(ok (fn? f1) "a data handler is a fn in the output")
(ok (identical? f1 f2) "the same [prop data] yields the identical fn across renders")
(f1 "evt-arg")
(ok (= [[:on-click [:do 1]]] @seen) (str "it still dispatches: " @seen))
(def b3 (h/->replicant (h/normalize [:button {:on-click [:do 2]}])))
(ok (not (identical? f1 (get-in (second b3) [:on :on-click]))) "different data => different fn")

;; 2) an fn handler still passes through untouched
(def my-handler (fn [_] nil))
(def b4 (h/->replicant (h/normalize [:button {:on-click my-handler}])))
(ok (identical? my-handler (get-in (second b4) [:on :on-click])) "fn handlers pass through raw")

;; 3) a plain fn component (not an alias, not memoized) re-runs every render --
;;    the default, since the component memo cache is gone.
(def plain-calls (atom 0))
(defn plain [x] (swap! plain-calls inc) [:label {:label (str x)}])
(reset! plain-calls 0)
(h/normalize [plain 1])
(h/normalize [plain 1])
(ok (= 2 @plain-calls) "unmarked fn components are re-run each render (no component cache)")
(ui/set-dispatch! nil)

(println (str "MEMO-OK cases=" @cases))
(println "ALL OK")

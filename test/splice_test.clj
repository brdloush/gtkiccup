;; Child splicing: (map ...) is a seq, but (mapv ...), (for ...) that yields a
;; vector via vec, and (mapcat ...) yield vectors. A vector whose head is a
;; vector cannot be a widget form, so it is a run of siblings and must be
;; spliced -- otherwise the run is read as one giant node and rejected as an
;; unknown widget.
(require '[babashka.ffi :refer [defcfn]]
         '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g])
(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec ui/default-window))
(def win ((:ctor ui/default-window)))

(defn kids [form] (-> (normalize form) :children (->> (map :tag))))
(defn fails [form]
  (try (normalize form) :NO-ERROR (catch Exception e (ex-message e))))

(println "1) a vector of widgets, the (mapv) shape")
(assert (= '(:label :label :label)
           (kids '[:vbox {} [[:label "one"] [:label "two"] [:label "three"]]])))
(println "   ->" (kids '[:vbox {} [[:label "one"] [:label "two"] [:label "three"]]]))

(println "2) computed, not literal: (mapv f xs) and (map f xs) agree")
(let [xs [1 2 3]]
  (assert (= '(:label :label :label) (kids `[:vbox {} ~(mapv #(vector :label (str %)) xs)])))
  (assert (= '(:label :label :label) (kids `[:vbox {} ~(map #(vector :label (str %)) xs)])))
  (assert (= '(:label :label :label)
             (kids `[:vbox {} ~(for [x xs] (vector :label (str x)))]))))
(println "   -> mapv, map and for all splice")

(println "3) a run nested inside a run, and runs mixed with plain children")
(assert (= '(:hbox :label :label :hbox)
           (kids '[:vbox {}
                   [:hbox {}]
                   [[:label "a"] [:label "b"]]
                   [:hbox {}]])))
(assert (= '(:label :label :label)
           (kids '[:vbox {} [[:label "a"] [[:label "b"]]] [:label "c"]])))
(println "   -> splicing is recursive")

(println "4) empty run and nil children drop cleanly")
(assert (= '(:label) (kids '[:vbox {} [] [:label "x"] nil])))
(println "   ->" (kids '[:vbox {} [] [:label "x"] nil]))

(println "5) a widget vector is never mistaken for a run")
;; the head is a tag keyword, so it stays exactly one node: [:vbox {} [:hbox {}]]
(assert (= '(:hbox) (kids '[:vbox {} [:hbox {}]])))
(println "   -> single widget child untouched")

(println "6) live: the spliced children really are built, one widget each")
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(def t (reconcile root-spec win nil
                  (normalize `[:vbox {} ~(mapv #(vector :label (str %)) (range 3))])))
(def built (mapv :widget (:children t)))
(assert (= 3 (count built)) (str "built " (count built) " children, want 3"))
(assert (every? #(= "GtkLabel" (type-name %)) built)
        (str (mapv type-name built)))
(println "   ->" (count built) "real" (type-name (first built)) "children under the vbox")

(println "7) garbage still says what is wrong (no bogus unknown-widget)")
(assert (re-find #"unknown widget" (fails [:vbox {} [[:nope {}]]]))
  )
(println "   -> nested bad tag still reported")

(println "splice_test ok")

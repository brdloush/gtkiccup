;; Markdown -> slides, and the key-to-action mapping. Both pure: no GTK, no
;; network, no window.
(require '[clojure.string :as str] '[deckmd :as d])

;; --- 1. escaping happens, and happens first ----------------------------
(println "1) escape:" (pr-str (d/escape "a & b < c > d \" e ' f")))
(assert (= "a &amp; b &lt; c &gt; d &quot; e &apos; f"
           (d/escape "a & b < c > d \" e ' f")))
;; the source must not be able to inject markup
(println "   injection attempt ->" (pr-str (d/inline "<b>not bold</b>")))
(assert (= "&lt;b&gt;not bold&lt;/b&gt;" (d/inline "<b>not bold</b>"))
        "raw tags in the markdown must be inert")

;; --- 2. inline markup --------------------------------------------------
(println "2) inline:" (pr-str (d/inline "a **bold** b *it* c `code` d")))
(assert (= "a <b>bold</b> b <i>it</i> c <tt>code</tt> d"
           (d/inline "a **bold** b *it* c `code` d")))
;; bold wins over italic, so ** is never read as two single stars
(assert (= "<b>x</b>" (d/inline "**x**")))
;; an ampersand inside code is still escaped
(assert (= "<tt>a &amp; b</tt>" (d/inline "`a & b`")))
;; a lone star, or one inside a word, is left alone
(println "   lone stars:" (pr-str (d/inline "2 * 3 and a*b")))
(assert (= "2 * 3 and a*b" (d/inline "2 * 3 and a*b")))

;; --- 3. slide splitting -------------------------------------------------
(def md "# Title\nsubtitle\n\n---\n\n## Bullets\n\n- one\n- two **bold**\n\n---\n\n> a quote\n\n---\n\n## Prose\n\nsome text\n")
(def slides (d/parse md))
(println "3) types:" (mapv :type slides))
(assert (= [:title :bullets :quote :prose] (mapv :type slides)))
(assert (= 4 (d/slide-count slides)))

;; separators with more dashes, and a trailing one, are harmless
(println "   dashes and trailing separator:"
         (mapv :type (d/parse "# A\n-----\n# B\n---\n")))
(assert (= [:title :title] (mapv :type (d/parse "# A\n-----\n# B\n---\n"))))
;; windows line endings
(assert (= [:title :title] (mapv :type (d/parse "# A\r\n---\r\n# B\r\n"))))
;; an empty deck does not explode
(assert (= [] (d/parse "")))
(assert (= [] (d/parse "\n---\n\n---\n")))

;; --- 4. the slide shapes ----------------------------------------------
(println "4) title slide:" (first slides))
(assert (= {:type :title :heading "Title" :sub "subtitle"} (first slides)))
(println "   bullets:" (second slides))
(assert (= ["one" "two **bold**"] (:items (second slides))))
(assert (= "Bullets" (:heading (second slides))))
(assert (= "a quote" (:text (nth slides 2))))
;; only # makes a title slide; ## with one line is prose
(assert (= :prose (:type (first (d/parse "## Small\njust one line")))))
(assert (= :title (:type (first (d/parse "# Big\njust one line")))))
;; a heading with several lines under it is prose, not a title
(assert (= :prose (:type (first (d/parse "# Big\nline one\nline two")))))

;; --- 5. clamping ------------------------------------------------------
(println "5) clamp:" (mapv #(d/clamp-index % slides) [-5 0 3 99]))
(assert (= [0 0 3 3] (mapv #(d/clamp-index % slides) [-5 0 3 99])))
(assert (= 0 (d/clamp-index 5 [])) "an empty deck stays at slide 0")

;; --- 6. keys map to actions ------------------------------------------
(println "6) next keys:" (sort (keep (fn [[k a]] (when (= :next a) k)) d/key-actions)))
(assert (= :next (d/key-actions "Right")))
(assert (= :next (d/key-actions "space")))
(assert (= :prev (d/key-actions "Left")))
(assert (= :prev (d/key-actions "BackSpace")))
(assert (= :first (d/key-actions "Home")))
(assert (= :last (d/key-actions "End")))
(assert (= :fullscreen (d/key-actions "F5")))
(assert (nil? (d/key-actions "z")) "unknown keys must fall through to GTK")

;; --- 7. the state transitions ----------------------------------------
(def st {:index 0 :slides slides :fullscreen? false})
(println "7) next/prev:" (:index (d/apply-action st :next))
         (:index (d/apply-action (assoc st :index 3) :next))
         (:index (d/apply-action st :prev)))
(assert (= 1 (:index (d/apply-action st :next))))
(assert (= 3 (:index (d/apply-action (assoc st :index 3) :next))) "must not run off the end")
(assert (= 0 (:index (d/apply-action st :prev))) "must not run off the start")
(assert (= 3 (:index (d/apply-action st :last))))
(assert (= 0 (:index (d/apply-action (assoc st :index 2) :first))))
(assert (true? (:fullscreen? (d/apply-action st :fullscreen))))
(assert (false? (:fullscreen? (d/apply-action (assoc st :fullscreen? true) :fullscreen))))
;; Escape leaves fullscreen first, and only then quits
(println "   escape while fullscreen ->" (select-keys (d/apply-action (assoc st :fullscreen? true) :escape) [:fullscreen? :quit?]))
(assert (false? (:fullscreen? (d/apply-action (assoc st :fullscreen? true) :escape))))
(assert (nil? (:quit? (d/apply-action (assoc st :fullscreen? true) :escape))))
(assert (true? (:quit? (d/apply-action st :escape))))
(assert (true? (:quit? (d/apply-action st :quit))))
;; an unknown action is a no-op rather than an error
(assert (= st (d/apply-action st :nonsense)))
(println "ALL OK")

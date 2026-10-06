;; AdwViewStack: pages keyed by :name, with :title / :icon for a switcher to
;; read, and the visible page set by name -- both at build time and after.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.adw :as adw] '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g])
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn visible-name "adw_view_stack_get_visible_child_name" [:pointer] :string)
(defcfn child-by-name "adw_view_stack_get_child_by_name" [:pointer :string] :pointer)
(defcfn stack-pages "adw_view_stack_get_pages" [:pointer] :pointer)
(defcfn n-items "g_list_model_get_n_items" [:pointer] :int)
(defcfn page-of "adw_view_stack_get_page" [:pointer :pointer] :pointer)
(defcfn page-title "adw_view_stack_page_get_title" [:pointer] :string)
(defcfn page-icon "adw_view_stack_page_get_icon_name" [:pointer] :string)

(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec adw/window))
(def win ((:ctor adw/window)))

(defn node [t & path] (get-in t (interleave (repeat :children) path)))

(def pages
  '[[:vbox {:name "player" :title "Player" :icon "media-playback-start-symbolic"}
     [:label "player"]]
    [:vbox {:name "lyrics" :title "Lyrics"} [:label "lyrics"]]
    [:vbox {:name "queue" :title "Queue"} [:label "queue"]]])

(println "1) pages are added by :name, titled by :title / :icon")
(def t (reconcile root-spec win nil
                  (normalize (into [:view-stack {:visible-child-name "lyrics"}]
                                   pages))))
(def stack (:widget t))
(assert (= "AdwViewStack" (type-name stack)))
(assert (= 3 (n-items (stack-pages stack))) "three pages")
(doseq [[i name] [[0 "player"] [1 "lyrics"] [2 "queue"]]]
  (let [c (child-by-name stack name)
        p (page-of stack c)]
    (assert (not (g/null? c)) (str "page " name " missing"))
    (assert (= "GtkBox" (type-name c)) name)))
(let [p (page-of stack (child-by-name stack "player"))]
  (assert (= "Player" (page-title p)))
  (assert (= "media-playback-start-symbolic" (page-icon p))))
(assert (= "Lyrics" (page-title (page-of stack (child-by-name stack "lyrics")))))
(println "   -> 3 pages, titles and one icon read back through the page objects")

(println "2) the starting page is picked once the children are in")
(assert (= "lyrics" (visible-name stack))
        "visible-child-name is applied after children, not before")
(println "   ->" (visible-name stack))

(println "3) changing the name switches the page")
(def t2 (reconcile root-spec win t
                   (normalize (into [:view-stack {:visible-child-name "queue"}]
                                    pages))))
(assert (= "queue" (visible-name stack)))
(println "   ->" (visible-name stack))

(println "4) a page that goes away is removed from the stack")
(def t3 (reconcile root-spec win t2
                   (normalize (into [:view-stack {:visible-child-name "player"}]
                                    (take 1 pages)))))
(assert (= 1 (n-items (stack-pages stack))) "one page left")
(assert (g/null? (child-by-name stack "lyrics")) "the lyrics page is gone")
(assert (g/null? (child-by-name stack "queue")) "and the queue page too")
(assert (= "player" (visible-name stack)))
(println "   -> 1 page, the removed ones are out, player showing")

(g/window-destroy win)
(println "ALL OK")
(System/exit 0)

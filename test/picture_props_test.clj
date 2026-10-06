;; GtkPicture: a file turned into a GdkTexture, and what the picture does with
;; the space the layout gives it. These are the props a wall of album covers
;; needs -- :cover so a card has no letterboxing, :can-shrink so a grid can
;; get smaller than the bitmap.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[gtkiccup.adw :as adw] '[gtkiccup.core :as ui] '[gtkiccup.ffi :as g])
(defcfn type-name "g_type_name_from_instance" [:pointer] :string)
(defcfn paintable-of "gtk_picture_get_paintable" [:pointer] :pointer)
(defcfn fit-of "gtk_picture_get_content_fit" [:pointer] :int)
(defcfn shrink-of "gtk_picture_get_can_shrink" [:pointer] :int)
(defcfn img-paintable "gtk_image_get_paintable" [:pointer] :pointer)

(g/gtk-init)
(def normalize @#'ui/normalize)
(def reconcile @#'ui/reconcile)
(def root-spec (@#'ui/root-spec adw/window))
(def win ((:ctor adw/window)))

(defn node [t & path] (get-in t (interleave (repeat :children) path)))

;; a committed raster from this repo's own docs -- a real bitmap, not a stub
(def png "docs/images/weather.png")
(assert (.exists (java.io.File. png)) "test fixture missing")

(println "1) :file loads a texture into the picture")
(def t (reconcile root-spec win nil (normalize [:picture {:file png}])))
(def pic (:widget t))
(assert (= "GtkPicture" (type-name pic)))
(assert (not (g/null? (paintable-of pic))) "no paintable: the file did not load")
(println "   ->" (type-name pic) "shows a"
         (type-name (paintable-of pic)))

(println "2) the defaults are the useful ones")
(assert (= 1 (fit-of pic)) "content-fit defaults to :contain")
(assert (= 1 (shrink-of pic)) "can-shrink defaults to true")
(println "   -> fit" (fit-of pic) "shrink" (shrink-of pic))

(println "3) :content-fit is reactive (:cover 2, :scale-down 3, :fill 0)")
(def t2 (reconcile root-spec win t (normalize [:picture {:file png :content-fit :cover}])))
(assert (= 2 (fit-of pic)) ":content-fit :cover")
(def t3 (reconcile root-spec win t2 (normalize [:picture {:file png :content-fit :scale-down}])))
(assert (= 3 (fit-of pic)) ":content-fit :scale-down")
(reconcile root-spec win t3 (normalize [:picture {:file png :content-fit :fill}]))
(assert (= 0 (fit-of pic)) ":content-fit :fill")
(println "   ->" 2 3 0)

(println "4) :can-shrink false stops the picture getting smaller than the bitmap")
(def t4 (reconcile root-spec win t3 (normalize [:picture {:file png :can-shrink false}])))
(assert (= 0 (shrink-of pic)))
(println "   ->" (shrink-of pic))

(println "5) :texture / :paintable are the same request under another name")
(def t5 (reconcile root-spec win t4 (normalize [:picture {:texture png}])))
(assert (not (g/null? (paintable-of pic))) "the :texture alias loaded nothing")
(println "   ->", (type-name (paintable-of pic)))

(println "6) a file that is not there is blank, not an error")
(def t6 (reconcile root-spec win t5 (normalize [:picture {:file "no/such/image.png"}])))
(assert (g/null? (paintable-of pic)) "a missing file left a paintable behind")
(println "   -> blank, and still a" (type-name pic))

(println "7) :icon can take the same bitmap as a paintable")
(def t7 (reconcile root-spec win t6 (normalize [:icon {:texture png :size 24}])))
(def img (:widget t7))
(assert (= "GtkImage" (type-name img)))
(assert (not (g/null? (img-paintable img))) "the icon has no paintable")
(println "   ->" (type-name img) "from a" (type-name (img-paintable img)))

(g/window-destroy win)
(println "ALL OK")
(System/exit 0)

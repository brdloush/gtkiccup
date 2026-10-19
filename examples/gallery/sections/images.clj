(ns gallery.sections.images
  (:require
   [gallery.common :refer [example section showcase support]]))

(def ^:private sample-svg
  "<svg xmlns='http://www.w3.org/2000/svg' width='192' height='128' viewBox='0 0 192 128'>
     <path fill='#2e5cb8' d='M0 0h48v128H0z'/>
     <path fill='#2c9670' d='M48 0h48v128H48z'/>
     <path fill='#de8e30' d='M96 0h48v128H96z'/>
     <path fill='#c4465e' d='M144 0h48v128H144z'/>
     <path stroke='white' stroke-opacity='.35' stroke-width='14'
           d='M-64 0L64 128M0 0L128 128M64 0L192 128M128 0L256 128M192 0L320 128'/>
   </svg>")

;; File-loading APIs need a filename even when the artwork is embedded.
(def ^:private sample-file
  (let [file (java.io.File/createTempFile "gtkiccup-gallery-" ".svg")]
    (.deleteOnExit file)
    (spit file sample-svg)
    (str file)))

(def initial-state
  {:image 48 :picture {:content-fit 1 :can-shrink true}})

(defn- image-example [state path]
  (let [pixel-size (get-in state path)]
    [example "Image"
     [showcase
      [:hbox {:spacing 24 :halign :start}
       [:icon {:icon "list-add-symbolic"
               :size pixel-size
               :tooltip "A themed icon"}]
       [:icon {:file sample-file
               :size pixel-size
               :tooltip "An embedded SVG, scaled like an icon"}]]]
     [support
      [:label {:label "Pixel size" :xalign 0.0 :wrap true :max-width-chars 28}]
      [:spin-button {:value pixel-size
                     :digits 0
                     :numeric true
                     :adjustment {:lower 16 :upper 128 :step-increment 8 :page-increment 16}
                     :tooltip "Size of both images in pixels"
                     :on-value-changed [[:assoc-in path]]
                     :halign :start}]
      [:label {:label (str "Both images at " pixel-size " px; an image always draws at icon size.")
               :wrap true :xalign 0.0 :max-width-chars 28}]]]))

(def ^:private content-fits
  [{:title "Fill" :fit :fill} {:title "Contain" :fit :contain} {:title "Cover" :fit :cover}
   {:title "Scale down" :fit :scale-down}])

(defn- picture-example [state path]
  (let [{:keys [content-fit can-shrink]} (get-in state path)]
    [example "Picture"
     [showcase
      ;; A picture's natural height follows its width at the image's aspect ratio,
      ;; so a fixed-height scrolled window gives it a frame of a different shape.
      [:frame
       [:scrolled {:height 160 :vexpand false}
        [:picture {:file sample-file
                   :content-fit (:fit (nth content-fits content-fit))
                   :can-shrink can-shrink
                   :alternative-text "Four colored stripes with diagonal white lines"
                   :hexpand true}]]]]
     [support
      [:dropdown {:items (mapv :title content-fits)
                  :selected content-fit
                  :tooltip "How the image fills its frame"
                  :on-selected [[:assoc-in (conj path :content-fit)]]
                  :halign :start}]
      [:check {:active can-shrink
               :tooltip "Allow the picture to become smaller than the image"
               :on-toggle [[:assoc-in (conj path :can-shrink)]]
               :halign :start} "Can shrink"]
      [:label {:label "Resize the window to compare how each fit uses the frame."
               :wrap true :xalign 0.0 :max-width-chars 28}]]]))

(defn page [state]
  [section
   [image-example state [:image]]
   [picture-example state [:picture]]])

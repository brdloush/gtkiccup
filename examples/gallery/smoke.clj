(ns gallery.smoke
  (:require [gallery.main :as gallery]
            [gtkiccup.core :as ui]))

(defn -main [& _]
  (gallery/prepare!)
  (doseq [{:keys [id title]} gallery/enabled-sections
          :let [[width height] gallery/window-size]]
    (println "Gallery smoke:" (name id))
    (gallery/select! id)
    (ui/run #'gallery/gallery :title (str title " smoke")
            :width width :height height :app-id "gtkiccup.gallery.smoke"
            :on-ready (fn [_ _]
                        (future
                          (Thread/sleep 800)
                          (ui/close!))))
    (when-let [error @ui/last-error]
      (throw (ex-info "Gallery smoke failed" (assoc error :section id))))))
